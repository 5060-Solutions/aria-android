package com.solutions5060.aria.pbx

import android.content.Context
import android.telephony.TelephonyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * The country numbers are read in: the company's calling country from the PBX
 * (`GET /tenants/{id}/calling/settings` -> `country`), cached on the device, else the
 * device's region (SIM, then locale). Display only; it never changes what is dialed.
 */
object DialCountry {

    /** Used when neither the PBX nor the device says. */
    const val FALLBACK = "US"

    private const val PREFS = "aria_prefs"
    private const val KEY_PBX = "dial_country_pbx"

    private val _state = MutableStateFlow(FALLBACK)
    val state: StateFlow<String> = _state.asStateFlow()

    @Volatile
    private var pbx: String? = null

    @Volatile
    private var device: String? = null

    /** Reads the cached PBX country and the device's region. Call once at startup. */
    fun init(context: Context) {
        val app = context.applicationContext
        pbx = normalize(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PBX, null))
        device = deviceRegion(
            (app.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.simCountryIso,
            Locale.getDefault().country,
        )
        publish()
    }

    /** Remembers the PBX's country (null forgets it, falling back to the device's). */
    fun setFromPbx(context: Context, country: String?) {
        pbx = normalize(country)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PBX, pbx).apply()
        publish()
    }

    private fun publish() {
        _state.value = effective(pbx, device)
    }

    /** The PBX's country, else the device's, else [FALLBACK]. */
    fun effective(pbx: String?, device: String?): String = pbx ?: device ?: FALLBACK

    /** The device's region: SIM country, else locale country; null when neither is a country. */
    fun deviceRegion(sim: String?, locale: String?): String? = normalize(sim) ?: normalize(locale)

    private fun normalize(code: String?): String? =
        code?.trim()?.uppercase()?.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } }
}
