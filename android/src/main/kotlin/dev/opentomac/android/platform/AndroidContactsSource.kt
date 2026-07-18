package dev.opentomac.android.platform

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import dev.opentomac.shared.contacts.ContactsSource
import dev.opentomac.shared.protocol.ContactItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidContactsSource(context: Context) : ContactsSource {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    override suspend fun search(query: String, limit: Int): List<ContactItem>? = withContext(Dispatchers.IO) {
        if (
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext null
        }

        // The query is wire-supplied: bound its length and neutralize LIKE
        // metacharacters so a crafted "%" or "_" cannot enumerate the address book.
        val literal = query.trim().take(MAX_QUERY_LENGTH)
        val escaped = literal
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        if (escaped.isBlank()) return@withContext emptyList()

        try {
            queryContacts(escaped, limit)
        } catch (_: SecurityException) {
            // Permission can be revoked between the check above and provider access.
            null
        }
    }

    private fun queryContacts(escapedQuery: String, limit: Int): List<ContactItem> {
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME,
        )
        val queryArgs = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ? ESCAPE '\\'",
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf("%$escapedQuery%"),
            )
            putString(
                ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                "${ContactsContract.Contacts.DISPLAY_NAME} COLLATE NOCASE ASC",
            )
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        val items = mutableListOf<ContactItem>()
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            queryArgs,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
            while (cursor.moveToNext() && items.size < limit) {
                val contactId = cursor.getLong(idColumn)
                items += ContactItem(
                    name = cursor.getString(nameColumn) ?: "Untitled",
                    phones = phoneNumbers(contactId),
                    emails = emailAddresses(contactId),
                )
            }
        }
        return items
    }

    private fun phoneNumbers(contactId: Long): List<String> {
        val values = mutableListOf<String>()
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null,
        )?.use { cursor ->
            val valueColumn = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                cursor.getString(valueColumn)?.takeIf { it.isNotBlank() }?.let(values::add)
            }
        }
        return values.distinct()
    }

    private fun emailAddresses(contactId: Long): List<String> {
        val values = mutableListOf<String>()
        resolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null,
        )?.use { cursor ->
            val valueColumn = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)
            while (cursor.moveToNext()) {
                cursor.getString(valueColumn)?.takeIf { it.isNotBlank() }?.let(values::add)
            }
        }
        return values.distinct()
    }

    private companion object {
        const val MAX_QUERY_LENGTH = 64
    }
}
