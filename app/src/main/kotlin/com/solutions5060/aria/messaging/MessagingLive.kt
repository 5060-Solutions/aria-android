package com.solutions5060.aria.messaging

import android.content.Context
import android.util.Log
import com.solutions5060.aria.pbx.StoredPbxAuth
import com.solutions5060.aria.service.PbxApiClient
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.trySendBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Live refresh: the `sms_received` events of `/api/v1/ws`. A seat that is not
 * an admin receives only the events for the numbers assigned to it. The flow
 * holds one socket while it is collected (the screens collect it only while
 * the app is in the foreground), reconnects with a backoff, and logs in again
 * when the session was refused. An event carries ids only; the screens
 * fetch what changed through the API, which checks access again.
 */
object MessagingLive {

    fun events(context: Context): Flow<SmsEvent> = EventSocket.texts(context).mapNotNull { MessagingJson.smsEvent(it) }
}

/**
 * The raw text frames of `/api/v1/ws`, for every feature that reads an event
 * (texts, call moves). One socket per collector.
 */
object EventSocket {

    private const val TAG = "EventSocket"
    private val BACKOFF_MS = listOf(1_000L, 2_000L, 5_000L, 15_000L, 30_000L)

    fun texts(context: Context): Flow<String> = callbackFlow {
        val auth = StoredPbxAuth(context)
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val closed = kotlinx.coroutines.CompletableDeferred<Int>()
            var socket: WebSocket? = null
            try {
                val api = PbxApiClient(auth.apiUrl())
                val jwt = auth.savedJwt() ?: auth.relogin()
                socket = api.socketClient().newWebSocket(
                    api.eventSocketRequest(jwt),
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            attempt = 0
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            trySendBlocking(text)
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(1000, null)
                            closed.complete(code)
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            closed.complete(code)
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            closed.complete(response?.code ?: -1)
                        }
                    },
                )
                val code = closed.await()
                // 401 on the upgrade, or 4001 when the session ended: sign in again.
                if (code == 401 || code == 4001) runCatching { auth.relogin() }
            } catch (e: Exception) {
                Log.w(TAG, "Event socket: ${e.javaClass.simpleName}")
            } finally {
                socket?.cancel()
            }
            delay(BACKOFF_MS[attempt.coerceAtMost(BACKOFF_MS.lastIndex)])
            attempt++
        }
        awaitClose { }
    }.flowOn(Dispatchers.IO)
}
