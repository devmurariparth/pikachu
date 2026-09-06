package com.example.contact

import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

data class ContactLookupResult(
    val found: Boolean,
    val contactName: String?,
    val phoneNumber: String?,
    val message: String
)

object ContactsManager {
    fun findContactPhoneNumber(context: Context, nameQuery: String): ContactLookupResult {
        val query = nameQuery.trim().removePrefix("my ").trim()
        if (query.isEmpty()) {
            return ContactLookupResult(false, null, null, "Please specify a contact name.")
        }

        // If query is already a phone number
        val digitsOnly = query.filter { it.isDigit() || it == '+' }
        if (digitsOnly.length >= 3 && query.all { it.isDigit() || it == '+' || it == '-' || it == ' ' || it == '(' || it == ')' }) {
            return ContactLookupResult(true, query, digitsOnly, "Calling $query.")
        }

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return ContactLookupResult(
                found = false,
                contactName = query,
                phoneNumber = null,
                message = "Contact access permission is needed to find $query in your contacts."
            )
        }

        return try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$query%")

            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val matchedName = if (nameIndex >= 0) cursor.getString(nameIndex) else query
                    val matchedNumber = if (numberIndex >= 0) cursor.getString(numberIndex) else null
                    if (!matchedNumber.isNullOrBlank()) {
                        ContactLookupResult(true, matchedName, matchedNumber, "Calling $matchedName.")
                    } else {
                        ContactLookupResult(false, query, null, "Found $query in contacts, but no phone number was listed.")
                    }
                } else {
                    ContactLookupResult(false, query, null, "I couldn't find $query in your contacts.")
                }
            } ?: ContactLookupResult(false, query, null, "I couldn't find $query in your contacts.")
        } catch (e: Exception) {
            ContactLookupResult(false, query, null, "Unable to access contacts.")
        }
    }
}
