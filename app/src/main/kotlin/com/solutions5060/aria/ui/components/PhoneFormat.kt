package com.solutions5060.aria.ui.components

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.solutions5060.aria.pbx.DialCountry

/**
 * Numbers as people read them. Pure: the default country is a parameter.
 *
 * Formatting is display only. What is dialed is [sanitize]d typing (digits plus a
 * leading "+", "*" and "#"), never a formatted or country-completed string: the PBX's
 * numbering plan normalizes, and forcing "+1" on anything would break extension dialing.
 * Extensions, star codes, short codes and anything that is not a phone number are
 * returned untouched.
 */
object PhoneFormat {

    private val util: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

    /** Fewest digits a national number is formatted from; shorter strings are extensions or short codes. */
    private const val MIN_NATIONAL_DIGITS = 7

    /** What is dialed for [raw]: digits, a leading "+", and "*" / "#"; spaces, dashes, dots and parentheses go. */
    fun sanitize(raw: String): String {
        val sb = StringBuilder()
        for (c in raw.trim()) {
            when {
                c in '0'..'9' || c == '*' || c == '#' -> sb.append(c)
                c == '+' && sb.isEmpty() -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** True for a string that is only a phone number's characters, with no star code or letters. */
    private fun isNumberLike(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' || it in " -().+" } &&
        s.indexOf('+').let { it <= 0 }

    /**
     * The dialer's display of what has been typed, as the number grows: "(800) 444-4444",
     * "+44 20 7946 0958". Anything starting with or holding "*" or "#", and national strings
     * under seven digits, stay as typed.
     */
    fun asYouType(typed: String, country: String): String {
        val s = sanitize(typed)
        if (s.isEmpty() || s.any { it == '*' || it == '#' }) return typed
        val international = s.startsWith("+")
        if (!international && s.length < MIN_NATIONAL_DIGITS) return s
        if (international && s.length < 2) return s
        val formatter = util.getAsYouTypeFormatter(if (international) "ZZ" else regionOrDefault(country))
        var out = ""
        for (c in s) out = formatter.inputDigit(c)
        // A formatter that dropped or added a digit is not trusted with what is dialed.
        return if (digitsOnly(out) == digitsOnly(s)) out else s
    }

    /**
     * "+1 (800) 444-4444" when [typed] is a valid national number of [country]: a hint of what
     * the number is, shown beneath the typed one. Null otherwise (extensions, star codes,
     * already-international numbers, invalid numbers).
     */
    fun hint(typed: String, country: String): String? {
        val s = sanitize(typed)
        if (s.startsWith("+") || s.any { it == '*' || it == '#' } || s.length < MIN_NATIONAL_DIGITS) return null
        val region = regionOrDefault(country)
        val number = parse(s, region) ?: return null
        if (!util.isValidNumberForRegion(number, region)) return null
        // NANP reads "+1 (800) 444-4444"; elsewhere the national form carries a trunk prefix ("020"), so use the international one.
        if (number.countryCode != 1) return util.format(number, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL)
        return "+1 " + util.format(number, PhoneNumberUtil.PhoneNumberFormat.NATIONAL)
    }

    /**
     * A number in a list: national format in [country], international elsewhere. Extensions,
     * names, star codes, short codes and invalid numbers come back as they are.
     */
    fun display(number: String, country: String = DialCountry.state.value): String {
        if (!isNumberLike(number)) return number
        val s = sanitize(number)
        val international = s.startsWith("+")
        if (s.filter { it.isDigit() }.length < MIN_NATIONAL_DIGITS) return number
        val region = regionOrDefault(country)
        val parsed = parse(s, if (international) null else region) ?: return number
        if (!util.isValidNumber(parsed)) return number
        val home = util.getRegionCodeForNumber(parsed) == region
        return util.format(
            parsed,
            if (home) PhoneNumberUtil.PhoneNumberFormat.NATIONAL else PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL,
        )
    }

    private fun parse(s: String, region: String?) = try {
        util.parse(s, region)
    } catch (_: NumberParseException) {
        null
    }

    private fun regionOrDefault(country: String): String {
        val c = country.trim().uppercase()
        return if (c in util.supportedRegions) c else DialCountry.FALLBACK
    }

    private fun digitsOnly(s: String) = s.filter { it.isDigit() }
}
