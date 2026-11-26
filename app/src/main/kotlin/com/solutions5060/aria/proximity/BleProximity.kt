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

/**
 * The parts of the Bluetooth proximity scheme that need no radio. The PBX
 * issues a 16-byte token (32 hex digits); a device advertises it as one
 * 128-bit service UUID, which fits those 16 bytes exactly, and scans for the
 * 128-bit service UUIDs of the others.
 */
object BleToken {
    const val TOKEN_BYTES = 16
    const val MIN_REFRESH_MS = 30_000L
    const val DEFAULT_ROTATE_MS = 5 * 60_000L

    private val HEX = Regex("^[0-9a-f]{32}$")

    /** Bluetooth's base UUID suffix: a 16- or 32-bit assigned number widened, never one of our tokens. */
    private const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

    fun isToken(hex: String): Boolean = HEX.matches(hex)

    /** The token as the UUID that carries it, or null when it is not 32 lowercase hex digits. */
    fun toUuid(hex: String): UUID? {
        if (!isToken(hex)) return null
        return UUID.fromString(
            "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}",
        )
    }

    /** The token carried by [uuid], or null for a standard Bluetooth (base UUID) service. */
    fun fromUuid(uuid: UUID): String? {
        val text = uuid.toString()
        if (text.endsWith(BASE_SUFFIX)) return null
        return text.replace("-", "").lowercase()
    }

    /** How long to wait before fetching a new token: until just before it rotates. */
    fun refreshDelayMs(expiresAtMillis: Long?, nowMillis: Long): Long =
        if (expiresAtMillis == null) DEFAULT_ROTATE_MS
        else (expiresAtMillis - nowMillis - 10_000L).coerceAtLeast(MIN_REFRESH_MS)

    /** The strongest [limit] of the tokens heard, as the PBX takes at most that many. */
    fun strongest(heard: Map<String, Int>, limit: Int = ProximityJson.MAX_BLE_SEEN): List<BleSeen> =
        heard.entries.sortedByDescending { it.value }.take(limit).map { BleSeen(it.key, it.value) }
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
        // token -> strongest RSSI heard since the last report
        val heard = java.util.concurrent.ConcurrentHashMap<String, Int>()
        var ownToken: String? = null

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val uuids = result.scanRecord?.serviceUuids ?: return
                uuids.forEach { u ->
                    val hex = BleToken.fromUuid(u.uuid) ?: return@forEach
                    if (hex != ownToken) heard.merge(hex, result.rssi) { a, b -> maxOf(a, b) }
                }
            }
        }
        try {
            adapter.bluetoothLeScanner?.startScan(
                // The tokens are not known in advance, so no filter: only 128-bit service UUIDs are read.
                emptyList<ScanFilter>(),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(),
                scanCallback,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Scan not started: ${e.javaClass.simpleName}")
        }

        job = AppScope.scope.launch {
            val auth = StoredPbxAuth(app)
            val sipInstance = CallMoveCoordinator.sipInstance(app)
            while (isActive) {
                var wait = BleToken.DEFAULT_ROTATE_MS
                try {
                    val token = ProximityJson.bleToken(auth.withAuth { PbxApiClient(auth.apiUrl()).bleToken(it, sipInstance) })
                    val uuid = BleToken.toUuid(token.hex)
                    if (uuid != null) {
                        ownToken = token.hex
                        advertise(adapter, uuid)
                    }
                    wait = BleToken.refreshDelayMs(token.expiresAtMillis, System.currentTimeMillis())
                    reportHeard(app, auth, heard)
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
                    runCatching { reportHeard(app, auth, heard) }
                }
            }
        }
    }

    /** Report (and forget) the tokens heard, with the network hash this device has now. */
    private fun reportHeard(app: Context, auth: StoredPbxAuth, heard: MutableMap<String, Int>) {
        if (heard.isEmpty()) return
        val seen = BleToken.strongest(heard.toMap())
        heard.clear()
        val urn = CallMoveCoordinator.sipInstance(app)
        auth.withAuth { PbxApiClient(auth.apiUrl()).reportProximity(it, urn, ProximityJson.proximityBody(ProximityMonitor.currentHash(), seen)) }
    }

    @SuppressLint("MissingPermission")
    private fun advertise(adapter: android.bluetooth.BluetoothAdapter, token: UUID) {
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
                // The token is the 128-bit service UUID: 16 bytes, no name, nothing else.
                AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(ParcelUuid(token)).build(),
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
