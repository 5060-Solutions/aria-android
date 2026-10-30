package com.solutions5060.aria.voicemail

import android.content.Context
import android.util.Log
import com.solutions5060.aria.security.SecurePrefs
import com.solutions5060.aria.service.EngineManager
import com.solutions5060.aria.service.PbxApiClient

/**
 * The user's own voicemail through the Aria PBX REST API, authenticated with
 * the extension JWT the app already holds from login.
 *
 * A JWT expires; on a 401 this logs in again with the saved extension
 * credentials once and retries, rather than asking the user to reconnect.
 * Every call blocks on the network and must run off the main thread.
 */
class VoicemailRepository(context: Context) {

    companion object {
        private const val TAG = "VoicemailRepository"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(EngineManager.PREFS_NAME, Context.MODE_PRIVATE)

    /** Cached for the life of the screen; a box id does not change. */
    private var boxId: String? = null

    private fun client(): PbxApiClient {
        val apiUrl = prefs.getString("api_url", "") ?: ""
        if (apiUrl.isEmpty()) throw IllegalStateException("No PBX configured. Scan a QR code in Settings.")
        return PbxApiClient(apiUrl)
    }

    /** The dial code for voicemail by phone (the provisioning `vm` value), if one was provisioned. */
    fun accessNumber(): String? = prefs.getString("voicemail_access", null)?.takeIf { it.isNotBlank() }

    fun messages(): List<VoicemailMessage> = withAuth { client, jwt ->
        VoicemailJson.parseMessages(client.voicemailMessages(jwt, box(client, jwt)))
    }

    fun markRead(messageId: String) = withAuth { client, jwt -> client.markVoicemailRead(jwt, messageId) }

    fun delete(messageId: String) = withAuth { client, jwt -> client.deleteVoicemail(jwt, messageId) }

    /** A URL the media player can stream this message from without our JWT. */
    fun audioUrl(messageId: String): String = withAuth { client, jwt ->
        client.voicemailAudioUrl(messageId, VoicemailJson.parseGrantToken(client.voicemailAudioGrant(jwt, messageId)))
    }

    private fun box(client: PbxApiClient, jwt: String): String {
        boxId?.let { return it }
        val extensionId = prefs.getString("extension_id", null)?.takeIf { it.isNotEmpty() }
            ?: VoicemailJson.parseActingExtension(client.me(jwt))
            ?: throw IllegalStateException("This account has no extension to read voicemail for.")
        val id = try {
            VoicemailJson.parseBoxId(client.voicemailBox(jwt, extensionId))
        } catch (e: PbxApiClient.HttpException) {
            if (e.code == 404) throw IllegalStateException("No voicemail box is set up for this extension.")
            throw e
        }
        boxId = id
        return id
    }

    private fun <T> withAuth(block: (PbxApiClient, String) -> T): T {
        val client = client()
        val jwt = SecurePrefs.getString(appContext, "jwt", null)
        if (!jwt.isNullOrEmpty()) {
            try {
                return block(client, jwt)
            } catch (e: PbxApiClient.HttpException) {
                if (e.code != 401) throw e
                Log.i(TAG, "PBX token expired; logging in again")
            }
        }
        return block(client, refreshJwt(client))
    }

    private fun refreshJwt(client: PbxApiClient): String {
        val credentials = EngineManager.savedCredentials(appContext)
            ?: throw IllegalStateException("Not signed in. Scan a QR code in Settings.")
        val login = client.extensionLogin(
            credentials.username,
            credentials.password,
            prefs.getString("tenant_domain", "") ?: "",
        )
        // The JWT is a bearer credential — store encrypted.
        SecurePrefs.putString(appContext, "jwt", login.jwt)
        prefs.edit().putString("extension_id", login.extensionId).apply()
        return login.jwt
    }
}
