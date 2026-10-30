package com.solutions5060.aria.call

import android.content.ComponentName
import android.content.Context
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import com.solutions5060.aria.service.AriaConnectionService

/**
 * The app's Telecom phone account.
 *
 * Aria is a self-managed ConnectionService: it draws its own call UI and
 * Telecom arbitrates audio focus, routing and interaction with other calls.
 * A self-managed account must not also claim CAPABILITY_CALL_PROVIDER, which
 * would ask Telecom to route system-dialer calls here.
 */
object TelecomAccount {
    private const val TAG = "TelecomAccount"
    private const val ACCOUNT_ID = "aria_voip"
    private const val LABEL = "Aria VoIP"

    fun handle(context: Context): PhoneAccountHandle =
        PhoneAccountHandle(ComponentName(context, AriaConnectionService::class.java), ACCOUNT_ID)

    /** Registers (or refreshes) the account. Safe to call repeatedly. */
    fun register(context: Context) {
        try {
            val account = PhoneAccount.builder(handle(context), LABEL)
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_SIP, PhoneAccount.SCHEME_TEL))
                .build()
            context.getSystemService(TelecomManager::class.java).registerPhoneAccount(account)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register phone account: $e")
        }
    }
}
