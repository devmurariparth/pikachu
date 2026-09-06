package com.example.contact

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.example.AssistantLogger

data class ContactPhoneEntry(
    val contactId: Long,
    val displayName: String,
    val phoneNumber: String,
    val type: Int,
    val typeLabel: String
)

sealed class ContactLookupOutcome {
    data class SingleMatch(val entry: ContactPhoneEntry) : ContactLookupOutcome()
    data class MultipleContacts(val query: String, val contacts: List<ContactPhoneEntry>) : ContactLookupOutcome()
    data class MultipleNumbersForContact(val contactName: String, val numbers: List<ContactPhoneEntry>) : ContactLookupOutcome()
    data class ContactHasNoNumber(val contactName: String) : ContactLookupOutcome()
    data class NotFound(val query: String) : ContactLookupOutcome()
    data class PermissionRequired(val message: String) : ContactLookupOutcome()
    data class DirectNumber(val phoneNumber: String) : ContactLookupOutcome()
    data class Error(val message: String) : ContactLookupOutcome()
}

data class ContactLookupResult(
    val found: Boolean,
    val contactName: String?,
    val phoneNumber: String?,
    val message: String
)

object ContactsManager {
    private const val TAG = "ContactsManager"

    /**
     * Checks if READ_CONTACTS permission is granted.
     */
    fun hasContactsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Look up contacts by name or phone query.
     */
    fun lookupContact(context: Context, nameQuery: String): ContactLookupOutcome {
        val query = nameQuery.trim().removePrefix("my ").trim()
        if (query.isEmpty()) {
            return ContactLookupOutcome.Error("Please specify a contact name.")
        }

        // Direct phone number check
        val digitsOnly = query.filter { it.isDigit() || it == '+' }
        if (digitsOnly.length >= 3 && query.all { it.isDigit() || it == '+' || it == '-' || it == ' ' || it == '(' || it == ')' }) {
            return ContactLookupOutcome.DirectNumber(digitsOnly)
        }

        // Permission check
        if (!hasContactsPermission(context)) {
            return ContactLookupOutcome.PermissionRequired(
                "Contact access permission is needed to look up contacts."
            )
        }

        return try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL
            )

            // Step 1: Query exact matches first (case-insensitive)
            val exactSelection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ? COLLATE NOCASE"
            val exactArgs = arrayOf(query)
            val exactEntries = queryPhoneEntries(context, projection, exactSelection, exactArgs)

            val matchedEntries = if (exactEntries.isNotEmpty()) {
                exactEntries
            } else {
                // Step 2: Prefix match or contains match
                val containsSelection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
                val containsArgs = arrayOf("%$query%")
                queryPhoneEntries(context, projection, containsSelection, containsArgs)
            }

            if (matchedEntries.isEmpty()) {
                // Check if contact exists in ContactsContract.Contacts with no phone number
                val contactNameWithNoPhone = findContactWithNoPhone(context, query)
                if (contactNameWithNoPhone != null) {
                    return ContactLookupOutcome.ContactHasNoNumber(contactNameWithNoPhone)
                }
                return ContactLookupOutcome.NotFound(query)
            }

            // Group by contactId to distinguish multiple people vs multiple numbers for one person
            val byContactId = matchedEntries.groupBy { it.contactId }

            if (byContactId.size > 1) {
                // Multiple different contacts match the query!
                // Prioritize exact displayName match if exactly one of them matches exactly
                val exactMatches = byContactId.filter { (_, entries) ->
                    entries.first().displayName.equals(query, ignoreCase = true)
                }
                if (exactMatches.size == 1) {
                    // Single exact contact among multiple partial matches
                    resolveSingleContactNumbers(exactMatches.values.first())
                } else {
                    val distinctContacts = byContactId.values.map { it.first() }
                    ContactLookupOutcome.MultipleContacts(query, distinctContacts)
                }
            } else {
                // Exactly 1 contact found
                resolveSingleContactNumbers(byContactId.values.first())
            }
        } catch (e: SecurityException) {
            AssistantLogger.e(TAG, "SecurityException while accessing ContactsContract", e)
            ContactLookupOutcome.PermissionRequired("Contact access permission is required.")
        } catch (e: Exception) {
            AssistantLogger.e(TAG, "Error looking up contact: $query", e)
            ContactLookupOutcome.Error("Unable to access contacts.")
        }
    }

    private fun resolveSingleContactNumbers(entries: List<ContactPhoneEntry>): ContactLookupOutcome {
        val contactName = entries.first().displayName
        // Deduplicate phone numbers that are functionally identical
        val distinctNumbers = entries.distinctBy { it.phoneNumber.filter { ch -> ch.isDigit() || ch == '+' } }

        return if (distinctNumbers.size == 1) {
            ContactLookupOutcome.SingleMatch(distinctNumbers.first())
        } else if (distinctNumbers.size > 1) {
            ContactLookupOutcome.MultipleNumbersForContact(contactName, distinctNumbers)
        } else {
            ContactLookupOutcome.ContactHasNoNumber(contactName)
        }
    }

    private fun queryPhoneEntries(
        context: Context,
        projection: Array<String>,
        selection: String,
        selectionArgs: Array<String>
    ): List<ContactPhoneEntry> {
        val results = mutableListOf<ContactPhoneEntry>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val typeIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
            val labelIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LABEL)

            while (cursor.moveToNext()) {
                val contactId = if (idIndex >= 0) cursor.getLong(idIndex) else 0L
                val displayName = if (nameIndex >= 0) cursor.getString(nameIndex) ?: "" else ""
                val rawNumber = if (numberIndex >= 0) cursor.getString(numberIndex) ?: "" else ""
                val type = if (typeIndex >= 0) cursor.getInt(typeIndex) else ContactsContract.CommonDataKinds.Phone.TYPE_OTHER
                val label = if (labelIndex >= 0 && !cursor.isNull(labelIndex)) cursor.getString(labelIndex) else null

                val typeLabel = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                    context.resources,
                    type,
                    label
                ).toString()

                if (rawNumber.isNotBlank()) {
                    results.add(
                        ContactPhoneEntry(
                            contactId = contactId,
                            displayName = displayName,
                            phoneNumber = rawNumber.trim(),
                            type = type,
                            typeLabel = typeLabel
                        )
                    )
                }
            }
        }
        return results
    }

    private fun findContactWithNoPhone(context: Context, query: String): String? {
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME,
            ContactsContract.Contacts.HAS_PHONE_NUMBER
        )
        val selection = "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$query%")

        return context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
            val hasPhoneIndex = cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)

            while (cursor.moveToNext()) {
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                val hasPhone = if (hasPhoneIndex >= 0) cursor.getInt(hasPhoneIndex) else 1
                if (hasPhone == 0 && !name.isNullOrBlank()) {
                    return@use name
                }
            }
            null
        }
    }

    /**
     * Legacy method preserved for compatibility.
     */
    fun findContactPhoneNumber(context: Context, nameQuery: String): ContactLookupResult {
        return when (val outcome = lookupContact(context, nameQuery)) {
            is ContactLookupOutcome.SingleMatch -> {
                ContactLookupResult(
                    found = true,
                    contactName = outcome.entry.displayName,
                    phoneNumber = outcome.entry.phoneNumber,
                    message = "Calling ${outcome.entry.displayName}."
                )
            }
            is ContactLookupOutcome.DirectNumber -> {
                ContactLookupResult(
                    found = true,
                    contactName = outcome.phoneNumber,
                    phoneNumber = outcome.phoneNumber,
                    message = "Calling ${outcome.phoneNumber}."
                )
            }
            is ContactLookupOutcome.MultipleContacts -> {
                ContactLookupResult(
                    found = true,
                    contactName = outcome.contacts.first().displayName,
                    phoneNumber = outcome.contacts.first().phoneNumber,
                    message = "I found multiple contacts named ${outcome.query}. Which one should I call?"
                )
            }
            is ContactLookupOutcome.MultipleNumbersForContact -> {
                val labels = outcome.numbers.map { it.typeLabel.lowercase() }.distinct().joinToString(", or ")
                ContactLookupResult(
                    found = true,
                    contactName = outcome.contactName,
                    phoneNumber = outcome.numbers.first().phoneNumber,
                    message = "Which number should I call: $labels?"
                )
            }
            is ContactLookupOutcome.ContactHasNoNumber -> {
                ContactLookupResult(
                    found = false,
                    contactName = outcome.contactName,
                    phoneNumber = null,
                    message = "That contact doesn't have a phone number."
                )
            }
            is ContactLookupOutcome.NotFound -> {
                ContactLookupResult(
                    found = false,
                    contactName = outcome.query,
                    phoneNumber = null,
                    message = "I couldn't find that contact."
                )
            }
            is ContactLookupOutcome.PermissionRequired -> {
                ContactLookupResult(
                    found = false,
                    contactName = null,
                    phoneNumber = null,
                    message = outcome.message
                )
            }
            is ContactLookupOutcome.Error -> {
                ContactLookupResult(
                    found = false,
                    contactName = null,
                    phoneNumber = null,
                    message = outcome.message
                )
            }
        }
    }
}
