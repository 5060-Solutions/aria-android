package com.solutions5060.aria.ui.components

import android.telephony.PhoneNumberUtils
import java.util.Locale

/** Numbers as people read them: "+1 555-318-2290", "(555) 201-4410". Extensions and SIP strings stay as typed. */
object PhoneFormat {
    fun display(number: String, country: String = Locale.getDefault().country): String {
        if (number.length < 7 || number.any { !it.isDigit() && it != '+' }) return number
        return PhoneNumberUtils.formatNumber(number, country) ?: number
    }
}
