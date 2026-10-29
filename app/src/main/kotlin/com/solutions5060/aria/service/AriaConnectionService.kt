package com.solutions5060.aria.service

import android.net.Uri
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import com.solutions5060.aria.call.CallManager

/**
 * Android Telecom ConnectionService for managing VoIP calls.
 *
 * The account is self-managed: Telecom arbitrates audio and interaction with
 * other calls, and Aria draws its own UI. Call logic lives in [CallManager];
 * this class only creates connections and hands them over.
 */
class AriaConnectionService : ConnectionService() {

    companion object {
        private const val TAG = "AriaConnectionService"
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Log.i(TAG, "Creating outgoing connection")
        val connection = AriaConnection().apply {
            setAddress(request?.address, TelecomManager.PRESENTATION_ALLOWED)
            setCallerDisplayName(
                request?.address?.schemeSpecificPart?.substringBefore("@") ?: "Unknown",
                TelecomManager.PRESENTATION_ALLOWED
            )
        }
        CallManager.onOutgoingConnectionCreated(connection, request?.extras?.getString(CallManager.EXTRA_RECORD_KEY))
        return connection
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        val extras = request?.extras
        val callerUri = extras?.getString(CallManager.EXTRA_CALLER_URI) ?: "Unknown"
        val callerName = extras?.getString(CallManager.EXTRA_CALLER_NAME)
        Log.i(TAG, "Creating incoming connection")

        val connection = AriaConnection().apply {
            setAddress(
                Uri.fromParts("sip", callerUri.removePrefix("sip:"), null),
                TelecomManager.PRESENTATION_ALLOWED
            )
            setCallerDisplayName(
                callerName ?: callerUri.removePrefix("sip:").substringBefore("@"),
                TelecomManager.PRESENTATION_ALLOWED
            )
        }
        if (!CallManager.onIncomingConnectionCreated(connection, extras)) {
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.BUSY))
        }
        return connection
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.e(TAG, "Failed to create outgoing connection")
        CallManager.onOutgoingConnectionFailed(request?.extras?.getString(CallManager.EXTRA_RECORD_KEY))
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.e(TAG, "Failed to create incoming connection")
        CallManager.onIncomingConnectionFailed(request?.extras)
    }
}

/**
 * Represents a single VoIP call connection. Telecom-originated actions (a
 * Bluetooth headset button, a wearable, the system ending the call) are
 * forwarded to [CallManager], which drives the engine and reports state back.
 */
class AriaConnection : Connection() {

    init {
        connectionProperties = PROPERTY_SELF_MANAGED
        connectionCapabilities = CAPABILITY_HOLD or CAPABILITY_SUPPORT_HOLD or CAPABILITY_MUTE
        // VoIP audio: Telecom puts the device in MODE_IN_COMMUNICATION.
        audioModeIsVoip = true
    }

    override fun onShowIncomingCallUi() {
        CallManager.onShowIncomingCallUi(this)
    }

    override fun onAnswer() {
        CallManager.answer()
    }

    override fun onAnswer(videoState: Int) {
        CallManager.answer()
    }

    override fun onReject() {
        CallManager.reject()
    }

    override fun onDisconnect() {
        CallManager.hangup()
    }

    override fun onAbort() {
        CallManager.hangup()
    }

    override fun onHold() {
        CallManager.setHold(true)
    }

    override fun onUnhold() {
        CallManager.setHold(false)
    }

    override fun onPlayDtmfTone(c: Char) {
        CallManager.sendDtmf(c.toString())
    }
}

/**
 * Global holder for the SIP engine instance.
 */
object SipEngineHolder {
    var engine: uniffi.aria_mobile.AriaMobileEngine? = null
    var audioBridge: com.solutions5060.aria.service.AudioBridge? = null
}
