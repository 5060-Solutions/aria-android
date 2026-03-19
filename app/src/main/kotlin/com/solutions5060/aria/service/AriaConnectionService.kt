package com.solutions5060.aria.service

import android.net.Uri
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import uniffi.aria_mobile.CallInfo
import uniffi.aria_mobile.CallState

/**
 * Android Telecom ConnectionService for managing VoIP calls.
 *
 * Integrates with the system dialer and call management UI,
 * similar to CallKit on iOS.
 */
class AriaConnectionService : ConnectionService() {

    companion object {
        private const val TAG = "AriaConnectionService"
        var activeConnection: AriaConnection? = null
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Log.i(TAG, "Creating outgoing connection: ${request?.address}")

        val connection = AriaConnection().apply {
            setAddress(request?.address, TelecomManager.PRESENTATION_ALLOWED)
            setCallerDisplayName(
                request?.address?.schemeSpecificPart ?: "Unknown",
                TelecomManager.PRESENTATION_ALLOWED
            )
            connectionProperties = Connection.PROPERTY_SELF_MANAGED
            connectionCapabilities = (
                Connection.CAPABILITY_HOLD or
                Connection.CAPABILITY_MUTE or
                Connection.CAPABILITY_SUPPORT_HOLD
            )
            setInitializing()
        }

        activeConnection = connection
        return connection
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        val extras = request?.extras
        val callerUri = extras?.getString("caller_uri") ?: "Unknown"
        val callerName = extras?.getString("caller_name")
        val callToken = extras?.getString("call_token") ?: ""

        Log.i(TAG, "Creating incoming connection from: $callerUri")

        val callExtras = Bundle().apply {
            putString("call_token", callToken)
        }

        val connection = AriaConnection().apply {
            setAddress(
                Uri.fromParts("sip", callerUri, null),
                TelecomManager.PRESENTATION_ALLOWED
            )
            setCallerDisplayName(
                callerName ?: callerUri,
                TelecomManager.PRESENTATION_ALLOWED
            )
            connectionProperties = Connection.PROPERTY_SELF_MANAGED
            connectionCapabilities = (
                Connection.CAPABILITY_HOLD or
                Connection.CAPABILITY_MUTE or
                Connection.CAPABILITY_SUPPORT_HOLD
            )
            this.extras = callExtras
            this.callToken = callToken
            setRinging()
        }

        activeConnection = connection
        return connection
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.e(TAG, "Failed to create outgoing connection")
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.e(TAG, "Failed to create incoming connection")
    }
}

/**
 * Represents a single VoIP call connection.
 */
class AriaConnection : Connection() {

    companion object {
        private const val TAG = "AriaConnection"
    }

    var callToken: String? = null
    var callId: String? = null

    override fun onAnswer() {
        Log.i(TAG, "Call answered")
        setActive()

        // Accept via Rust engine
        val token = callToken ?: extras?.getString("call_token") ?: return
        Thread {
            try {
                val engine = SipEngineHolder.engine ?: return@Thread
                val info = engine.acceptIncomingCall(token, emptyList())
                callId = info.callId
            } catch (e: Exception) {
                Log.e(TAG, "Failed to accept call: $e")
                setDisconnected(DisconnectCause(DisconnectCause.ERROR))
                destroy()
            }
        }.start()
    }

    override fun onReject() {
        Log.i(TAG, "Call rejected")
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()

        val token = callToken ?: extras?.getString("call_token") ?: return
        Thread {
            try {
                SipEngineHolder.engine?.rejectIncomingCall(token)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reject call: $e")
            }
        }.start()

        AriaConnectionService.activeConnection = null
    }

    override fun onDisconnect() {
        Log.i(TAG, "Call disconnected")
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()

        val id = callId ?: return
        Thread {
            try {
                SipEngineHolder.engine?.hangup(id)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to hangup: $e")
            }
        }.start()

        AriaConnectionService.activeConnection = null
    }

    override fun onHold() {
        Log.i(TAG, "Call held")
        setOnHold()
        val id = callId ?: return
        Thread {
            try {
                SipEngineHolder.engine?.setHold(id, true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to hold: $e")
            }
        }.start()
    }

    override fun onUnhold() {
        Log.i(TAG, "Call unheld")
        setActive()
        val id = callId ?: return
        Thread {
            try {
                SipEngineHolder.engine?.setHold(id, false)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unhold: $e")
            }
        }.start()
    }

    override fun onPlayDtmfTone(c: Char) {
        Log.i(TAG, "DTMF: $c")
        val id = callId ?: return
        Thread {
            try {
                SipEngineHolder.engine?.sendDtmf(id, c.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send DTMF: $e")
            }
        }.start()
    }

    fun updateFromCallInfo(info: CallInfo) {
        when (info.state) {
            CallState.CONNECTED -> setActive()
            CallState.ON_HOLD -> setOnHold()
            CallState.ENDED -> {
                setDisconnected(DisconnectCause(DisconnectCause.REMOTE))
                destroy()
                AriaConnectionService.activeConnection = null
            }
            else -> {}
        }
    }
}

/**
 * Global holder for the SIP engine instance.
 */
object SipEngineHolder {
    var engine: uniffi.aria_mobile.AriaMobileEngine? = null
}
