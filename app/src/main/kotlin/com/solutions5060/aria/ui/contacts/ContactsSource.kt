package com.solutions5060.aria.ui.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.solutions5060.aria.fixtures.Fixtures

/** A phone number from the address book. One person with two numbers is two entries. */
data class Contact(
    val name: String,
    val phoneNumber: String,
    /** "Mobile", "Work" … as the address book labels it; null when unknown. */
    val label: String? = null,
    /** Starred in the address book: shown as a favourite. */
    val starred: Boolean = false,
    /** The address book's contact id, grouping a person's numbers. */
    val contactId: Long? = null,
)

/** Reads the address book (or the debug fixture) and matches dialled numbers to it. */
object ContactsSource {

    fun hasPermission(context: Context): Boolean =
        Fixtures.active ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Every phone number in the address book, by name. Blocks: call off the main thread. */
    fun load(context: Context): List<Contact> {
        Fixtures.current?.let { return it.contacts }
        if (!hasPermission(context)) return emptyList()
        val contacts = mutableListOf<Contact>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL,
                ContactsContract.CommonDataKinds.Phone.STARRED,
            ),
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
        )?.use { c ->
            val id = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val name = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val number = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val type = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
            val label = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LABEL)
            val starred = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.STARRED)
            while (c.moveToNext()) {
                val n = c.getString(name) ?: continue
                val num = c.getString(number) ?: continue
                val typeLabel = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                    context.resources,
                    c.getInt(type),
                    c.getString(label),
                ).toString()
                contacts.add(
                    Contact(
                        name = n,
                        phoneNumber = num,
                        label = typeLabel,
                        starred = c.getInt(starred) == 1,
                        contactId = c.getLong(id),
                    ),
                )
            }
        }
        return contacts.distinctBy { it.name + digitsOf(it.phoneNumber) }
    }

    /** Digits (and a leading +) only. */
    fun digitsOf(number: String): String = number.filterIndexed { i, ch -> ch.isDigit() || (ch == '+' && i == 0) }

    /**
     * The contact whose number is [dialled]: an exact digit match, or, for
     * numbers of 7+ digits, the same last 7+ digits (country code and
     * formatting differ between address books and dialling).
     */
    fun match(dialled: String, contacts: List<Contact>): Contact? {
        val d = digitsOf(dialled).removePrefix("+")
        if (d.length < 3) return null
        contacts.firstOrNull { digitsOf(it.phoneNumber).removePrefix("+") == d }?.let { return it }
        if (d.length < 7) return null
        return contacts.firstOrNull {
            val c = digitsOf(it.phoneNumber).removePrefix("+")
            c.length >= 7 && (c.endsWith(d) || d.endsWith(c))
        }
    }
}
