package com.solutions5060.aria.proximity

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.solutions5060.aria.AppScope
import com.solutions5060.aria.move.CallMoveCoordinator
import com.solutions5060.aria.pbx.StoredPbxAuth
import com.solutions5060.aria.pbx.withAuth
import com.solutions5060.aria.service.PbxApiClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/** The parts of the Bluetooth proximity scheme that need no radio. */
object BleToken {
    /** The service UUID devices advertise and scan for. */
    val SERVICE_UUID: UUID = UUID.fromString("5f0a7a1e-6a2b-4c1d-9e5a-a21a0c0de001")

    /** Company id for the manufacturer data that carries the token (0xFFFF: reserved for testing and private use). */
    const val COMPANY_ID = 0xFFFF
    const val MAX_BYTES = 16
    const val MIN_REFRESH_MS = 30_000L
    const val DEFAULT_ROTATE_MS = 5 * 60_000L

    fun toBytes(hex: String): ByteArray? {
        if (hex.isEmpty() || hex.length % 2 != 0 || hex.length / 2 > MAX_BYTES) return null
        return try {
            ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (_: NumberFormatException) {
            null
        }
    }

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** How long to wait before fetching a new token: until just before it expires. */
    fun refreshDelayMs(expiresAtMillis: Long?, nowMillis: Long): Long =
        if (expiresAtMillis == null) DEFAULT_ROTATE_MS
        else (expiresAtMillis - nowMillis - 10_000L).coerceAtLeast(MIN_REFRESH_MS)
}

/**
 * Bluetooth proximity, opt in and foreground only: advertise this device's
 * current rotating token (fetched from the PBX) and scan for the other
 * devices' tokens, reporting the ones heard. Nothing runs unless the user
 * turned it on and granted BLUETOOTH_ADVERTISE, SCAN and CONNECT (Android 12+;
 * below that, scanning needs the location permission, so it is not offered).
 */
object BleProximity {

    private const val TAG = "BleProximity"

    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val permissions: Array<String> =
        if (supported()) arrayOf(
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) else emptyArray()

    fun hasPermissions(context: Context): Boolean = supported() && permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    private var job: Job? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null

    /** Start advertising and scanning; a no-op when already running or not allowed. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(context: Context) {
        if (job != null || !hasPermissions(context) || !HandoffPrefs.load(context).ble) return
        val app = context.applicationContext
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) return
        val heard = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        var ownToken: String? = null

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val data = result.scanRecord?.getManufacturerSpecificData(BleToken.COMPANY_ID) ?: return
                val hex = BleToken.toHex(data)
                if (hex != ownToken && BleToken.toBytes(hex) != null) heard.add(hex)
            }
        }
        try {
            adapter.bluetoothLeScanner?.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BleToken.SERVICE_UUID)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(),
                scanCallback,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Scan not started: ${e.javaClass.simpleName}")
        }

        job = AppScope.scope.launch {
            val auth = StoredPbxAuth(app)
            while (isActive) {
                var wait = BleToken.DEFAULT_ROTATE_MS
                try {
                    val token = ProximityJson.bleToken(auth.withAuth { PbxApiClient(auth.apiUrl()).bleToken(it) })
                    val bytes = BleToken.toBytes(token.hex)
                    if (bytes != null) {
                        ownToken = token.hex
                        advertise(adapter, bytes)
                    }
                    wait = BleToken.refreshDelayMs(token.expiresAtMillis, System.currentTimeMillis())
                    val own = CallMoveCoordinator.resolveOwnRegistration()
                    if (own != null && heard.isNotEmpty()) {
                        val tokens = heard.toList()
                        heard.clear()
                        auth.withAuth { PbxApiClient(auth.apiUrl()).reportBleSeen(it, ProximityJson.bleSeenBody(own, tokens)) }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.i(TAG, "Token round skipped: ${e.javaClass.simpleName}")
                }
                // Report what was heard in between, at least every minute.
                var slept = 0L
                while (slept < wait && isActive) {
                    delay(60_000L.coerceAtMost(wait - slept))
                    slept += 60_000L
                    val own = CallMoveCoordinator.ownRegistrationIdOrNull()
                    if (own != null && heard.isNotEmpty()) {
                        val tokens = heard.toList()
                        heard.clear()
                        runCatching { auth.withAuth { PbxApiClient(auth.apiUrl()).reportBleSeen(it, ProximityJson.bleSeenBody(own, tokens)) } }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun advertise(adapter: android.bluetooth.BluetoothAdapter, token: ByteArray) {
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        advertiseCallback?.let { runCatching { advertiser.stopAdvertising(it) } }
        val cb = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "Advertising failed: $errorCode")
            }
        }
        advertiseCallback = cb
        try {
            advertiser.startAdvertising(
                AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                    .setConnectable(false)
                    .setTimeout(0)
                    .build(),
                AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(ParcelUuid(BleToken.SERVICE_UUID)).build(),
                AdvertiseData.Builder().setIncludeDeviceName(false).addManufacturerData(BleToken.COMPANY_ID, token).build(),
                cb,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Advertising not started: ${e.javaClass.simpleName}")
        }
    }

    /** Stop everything (the app left the foreground, or the user turned it off). */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun stop(context: Context) {
        job?.cancel()
        job = null
        val adapter = context.applicationContext.getSystemService(BluetoothManager::class.java)?.adapter
        if (hasPermissions(context)) {
            advertiseCallback?.let { runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) } }
            scanCallback?.let { runCatching { adapter?.bluetoothLeScanner?.stopScan(it) } }
        }
        advertiseCallback = null
        scanCallback = null
    }
}
