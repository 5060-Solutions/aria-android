package com.solutions5060.aria.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * The caller's name from the phone's contacts, for the call screen, the
 * incoming-call notification and history. Only when the user already
 * granted contacts access (asked on the Contacts tab, never from here).
 */
object ContactNames {

    /** The number to look up for a SIP URI or dialled string, or null if it is not a phone number. */
    fun lookupNumber(remote: String): String? {
        val user = remote.trim().removePrefix("sips:").removePrefix("sip:").removePrefix("tel:")
            .substringBefore('@').substringBefore(';')
        val cleaned = user.filterNot { it in " -.()" }
        if (cleaned.length < 3) return null
        if (!cleaned.matches(Regex("^\\+?[0-9]+$"))) return null
        return cleaned
    }

    /** The display name of the contact with this number, or null. Fast (indexed); safe on the main thread. */
    fun nameFor(context: Context, remote: String): String? {
        val number = lookupNumber(remote) ?: return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null }
        } catch (_: Exception) {
            null
        }
    }
}
