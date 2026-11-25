package com.solutions5060.aria.messaging

import android.content.Context
import androidx.core.content.edit
import com.solutions5060.aria.pbx.PbxAuth
import com.solutions5060.aria.pbx.StoredPbxAuth
import com.solutions5060.aria.pbx.withAuth
import com.solutions5060.aria.service.PbxApiClient

/**
 * The seat's business texting through the Aria PBX REST API, authenticated
 * with the extension JWT the app already holds from login (renewed once on a
 * 401, as voicemail does). Which inboxes appear is the server's call: a seat
 * reads only the numbers assigned to it. Every method blocks on the network.
 *
 * A refusal the PBX explains (an opted-out recipient, a number it will not
 * text, a carrier error) is an [PbxApiClient.HttpException] whose message is
 * the PBX's own text, to be shown as it is.
 */
class MessagingRepository(private val api: PbxApiClient, private val auth: PbxAuth) {

    companion object {
        fun from(context: Context): MessagingRepository {
            val auth = StoredPbxAuth(context)
            return MessagingRepository(PbxApiClient(auth.apiUrl()), auth)
        }
    }

    fun inboxes(): List<Inbox> = call { jwt, tenant -> MessagingJson.inboxes(api.messagingInboxes(jwt, tenant)) }

    fun conversations(didId: String): List<Conversation> =
        call { jwt, tenant -> MessagingJson.conversations(api.messagingConversations(jwt, tenant, didId)) }

    fun thread(didId: String, number: String): MessageThread =
        call { jwt, tenant -> MessagingJson.thread(api.messagingThread(jwt, tenant, didId, number)) }

    /** Sends [body] (and the already uploaded [attachmentIds]) from the inbox to [to], an E.164 number. */
    fun send(didId: String, to: String, body: String, attachmentIds: List<String>, idempotencyKey: String): ThreadMessage =
        call { jwt, tenant ->
            MessagingJson.sentMessage(api.sendMessage(jwt, tenant, didId, to, body, attachmentIds, idempotencyKey))
        }

    fun upload(bytes: ByteArray, contentType: String, fileName: String): Attachment =
        call { jwt, tenant -> MessagingJson.uploadedAttachment(api.uploadMessageAttachment(jwt, tenant, bytes, contentType, fileName)) }

    fun attachmentBytes(attachmentId: String): ByteArray =
        call { jwt, tenant -> api.messageAttachmentBytes(jwt, tenant, attachmentId) }

    private fun <T> call(block: (jwt: String, tenantId: String) -> T): T = auth.withAuth { jwt ->
        val tenant = auth.tenantId() ?: run {
            auth.relogin()
            auth.tenantId() ?: throw IllegalStateException("No tenant")
        }
        block(jwt, tenant)
    }
}

/** [UnreadTracker.Store] over the app's preferences. */
class PrefsUnreadStore(context: Context) : UnreadTracker.Store {
    private val prefs = context.applicationContext.getSharedPreferences("aria_messages", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) = prefs.edit { putString(key, value) }
}
