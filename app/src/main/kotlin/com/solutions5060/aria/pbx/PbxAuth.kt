package com.solutions5060.aria.pbx

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.solutions5060.aria.security.SecurePrefs
import com.solutions5060.aria.service.EngineManager
import com.solutions5060.aria.service.PbxApiClient

/**
 * The user's PBX session: the extension JWT from `extension-login`, which
 * expires after 24 h. Repositories run requests through [withAuth], which
 * logs in again once on a 401 and retries.
 */
interface PbxAuth {
    /** The saved JWT, or null when there is none yet. */
    fun savedJwt(): String?

    /** Logs in again with the saved account and returns the new JWT. */
    fun relogin(): String

    /** The extension's id (a UUID, not its number), from the login. */
    fun extensionId(): String

    /** The tenant's id, from the login; older PBX releases need it on new forwards. */
    fun tenantId(): String?
}

/** Runs [block] with a JWT, renewing it once if the PBX answers 401. */
fun <T> PbxAuth.withAuth(block: (jwt: String) -> T): T {
    savedJwt()?.let { jwt ->
        try {
            return block(jwt)
        } catch (e: PbxApiClient.HttpException) {
            if (e.code != 401) throw e
        }
    }
    return block(relogin())
}

/** [PbxAuth] over the saved account (aria_prefs + SecurePrefs). */
class StoredPbxAuth(context: Context) : PbxAuth {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(EngineManager.PREFS_NAME, Context.MODE_PRIVATE)

    fun apiUrl(): String = prefs.getString("api_url", "")?.ifEmpty { null }
        ?: throw IllegalStateException("No PBX configured")

    override fun savedJwt(): String? = SecurePrefs.getString(appContext, "jwt", null)?.ifEmpty { null }

    override fun relogin(): String {
        val credentials = EngineManager.savedCredentials(appContext)
            ?: throw IllegalStateException("Not signed in")
        Log.i("PbxAuth", "PBX session expired; logging in again")
        val login = PbxApiClient(apiUrl()).extensionLogin(
            credentials.username,
            credentials.password,
            prefs.getString("tenant_domain", "") ?: "",
        )
        SecurePrefs.putString(appContext, "jwt", login.jwt)
        prefs.edit {
            putString("extension_id", login.extensionId)
            putString("tenant_id", login.tenantId)
        }
        return login.jwt
    }

    override fun extensionId(): String =
        prefs.getString("extension_id", null)?.ifEmpty { null } ?: run {
            relogin()
            prefs.getString("extension_id", null) ?: throw IllegalStateException("No extension")
        }

    override fun tenantId(): String? = prefs.getString("tenant_id", null)?.ifEmpty { null }
}
