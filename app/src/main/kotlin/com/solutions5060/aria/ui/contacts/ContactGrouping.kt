package com.solutions5060.aria.ui.contacts

import java.text.Normalizer

/** One person in the A–Z list: every number the address book has for them. */
data class ContactPerson(
    val key: String,
    val name: String,
    val numbers: List<Contact>,
    val starred: Boolean,
)

/** Pure grouping and search for the Contacts tab, unit-tested. */
object ContactGrouping {

    /** Numbers grouped into people (by contact id, else by name), sorted by name. */
    fun people(contacts: List<Contact>): List<ContactPerson> =
        contacts.groupBy { it.contactId?.toString() ?: ("n:" + it.name) }
            .map { (key, numbers) ->
                ContactPerson(
                    key = key,
                    name = numbers.first().name,
                    numbers = numbers,
                    starred = numbers.any { it.starred },
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    /** People whose name or any number matches [query] (accent- and format-insensitive). */
    fun search(people: List<ContactPerson>, query: String): List<ContactPerson> {
        val q = fold(query.trim())
        if (q.isEmpty()) return people
        val digits = ContactsSource.digitsOf(query).removePrefix("+")
        return people.filter { p ->
            fold(p.name).contains(q) ||
                (digits.length >= 2 && p.numbers.any { ContactsSource.digitsOf(it.phoneNumber).contains(digits) })
        }
    }

    /** The section letter: A–Z, or "#" for anything else. */
    fun letterOf(name: String): String {
        val c = fold(name).firstOrNull()?.uppercaseChar() ?: return "#"
        return if (c in 'A'..'Z') c.toString() else "#"
    }

    fun byLetter(people: List<ContactPerson>): List<Pair<String, List<ContactPerson>>> =
        people.groupBy { letterOf(it.name) }.toList().sortedBy { (letter, _) -> if (letter == "#") "~" else letter }

    private fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
}
