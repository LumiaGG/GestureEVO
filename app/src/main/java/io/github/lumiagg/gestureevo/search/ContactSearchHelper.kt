package io.github.lumiagg.gestureevo.search

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log
import android.widget.Toast
import androidx.core.net.toUri
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.db.ContactModel
import io.github.lumiagg.gestureevo.db.getContactDao
import io.github.lumiagg.gestureevo.utils.ClipboardUtils
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.saveKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Contact search helper utilizing Room database cache.
 */
object ContactSearchHelper {

    // Contacts stored per contact + phone number in v2; bump key triggers a full sync.
    private const val KEY_LAST_SYNC_CONTACT_TIME = "last_sync_contact_time_v2"

    /**
     * Checks whether synchronization is needed.
     */
    private suspend fun needsSync(context: Context): Boolean {
        val lastSyncTime = context.settingsStore.readKey(KEY_LAST_SYNC_CONTACT_TIME, 0L)
        val lastUpdateTime = getSystemContactsLastUpdate(context)
        return lastUpdateTime > lastSyncTime
    }

    /**
     * Synchronizes system contacts to Room database.
     */
    suspend fun syncContactsToDatabase(context: Context, force: Boolean = false) =
        withContext(Dispatchers.IO) {
            if (!needsSync(context) && !force) {
                return@withContext
            }
            Log.d("ContactSearchHelper", "syncContactsToDatabase: ")
            val contactsMap = linkedMapOf<Pair<Long, String>, ContactModel>()
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.PHOTO_URI
            )

            runCatching {
                val cursor: Cursor? = context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
                )

                cursor?.use {
                    val idColumn =
                        it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                    val nameColumn =
                        it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numberColumn =
                        it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val photoColumn =
                        it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)

                    while (it.moveToNext()) {
                        val id = it.getLong(idColumn)
                        val name = it.getString(nameColumn) ?: continue
                        val number = it.getString(numberColumn) ?: ""
                        val photoUri = it.getString(photoColumn)

                        val normalizedNumber = normalizePhoneNumber(number)
                        val contactNumberKey = id to normalizedNumber.ifEmpty { number }
                        if (!contactsMap.containsKey(contactNumberKey)) {
                            val contact = ContactModel().apply {
                                this.contactId = id
                                this.name = name
                                this.phoneNumber = number
                                this.photoUri = photoUri
                            }
                            contactsMap[contactNumberKey] = contact
                        }
                    }
                }

                // Clear old records and insert refreshed contacts
                val dao = getContactDao()
                dao.deleteAll()
                if (contactsMap.isNotEmpty()) {
                    dao.insert(*contactsMap.values.toTypedArray())
                }
                context.settingsStore.saveKey(
                    KEY_LAST_SYNC_CONTACT_TIME,
                    System.currentTimeMillis()
                )
            }
        }

    private fun getSystemContactsLastUpdate(context: Context): Long {
        var lastUpdate = 0L

        runCatching {
            val cursor = context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP),
                null,
                null,
                "${ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP} DESC"
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndexOrThrow(
                        ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP
                    )
                    lastUpdate = it.getLong(index)
                }
            }
        }

        return lastUpdate
    }


    /**
     * Searches contacts cached in Room database.
     */
    suspend fun searchContacts(context: Context, query: String): List<ContactModel> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()

            syncContactsToDatabase(context)

            val dao = getContactDao()
            val q = query.lowercase()

            // Select search strategy based on query content
            val contacts = mutableListOf<ContactModel>()

            // 1. Match by name
            contacts.addAll(dao.findByName(query))

            // 2. Match by full pinyin
            contacts.addAll(dao.findByPinYinFK(q))

            // 3. Match by pinyin initials
            contacts.addAll(dao.findByInitialsFK(q))

            // 4. Match by phone number digits
            if (query.all { it.isDigit() }) {
                val normalizedQuery = normalizePhoneNumber(query)
                val allContacts = dao.getAll()
                contacts.addAll(
                    allContacts.filter {
                        normalizePhoneNumber(it.phoneNumber).contains(normalizedQuery)
                    }
                )
            }

            // A contact may have multiple phone numbers; deduplicate identical records only.
            contacts.distinctBy {
                it.contactId to normalizePhoneNumber(it.phoneNumber)
            }
                .sortedWith(compareByDescending { calculateMatchScore(query, it) })
        }

    /**
     * Computes relevance match score for contact ranking.
     */
    private fun calculateMatchScore(query: String, contact: ContactModel): Double {
        val q = query.lowercase()
        val name = contact.name.lowercase()
        val pinyin = contact.pinyinFK.lowercase()
        val initials = contact.initialsFK.lowercase()
        val phone = normalizePhoneNumber(contact.phoneNumber)
        val normalizedQuery = normalizePhoneNumber(query)

        return when {
            // Exact name match
            name == q -> 100.0
            // Name prefix match
            name.startsWith(q) -> 90.0
            // Full pinyin exact match
            pinyin == q -> 85.0
            // Full pinyin prefix match
            pinyin.startsWith(q) -> 80.0
            // Initials exact match
            initials == q -> 75.0
            // Initials prefix match
            initials.startsWith(q) -> 70.0
            // Phone number match
            normalizedQuery.isNotEmpty() && phone.contains(normalizedQuery) -> 60.0
            // Name contains query
            name.contains(q) -> 50.0
            // Pinyin contains query
            pinyin.contains(q) -> 40.0
            // Initials contains query
            initials.contains(q) -> 30.0
            else -> 0.0
        }
    }

    private fun normalizePhoneNumber(number: String): String = number.filter(Char::isDigit)

    /**
     * Checks whether READ_CONTACTS permission is granted.
     */
    fun hasContactPermission(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
    }
}

/**
 * Initiates phone call action for the selected contact.
 */
fun callContact(activity: QuickSearchActivity, contact: ContactModel) {
    try {
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = "tel:${contact.phoneNumber}".toUri()
        }
        activity.startActivity(intent)
        activity.finishWithAnimation()
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(activity, activity.getString(R.string.contact_call_failed), Toast.LENGTH_SHORT)
            .show()
    }
}

fun copyContactNumber(activity: QuickSearchActivity, contact: ContactModel) {
    ClipboardUtils.copyText(activity, contact.phoneNumber)
    activity.finishWithAnimation()
}

/**
 * Initiates SMS compose action for the selected contact.
 */
fun sendSmsToContact(activity: QuickSearchActivity, contact: ContactModel) {
    try {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = "smsto:${contact.phoneNumber}".toUri()
        }
        activity.startActivity(intent)
        activity.finishWithAnimation()
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(activity, activity.getString(R.string.contact_sms_failed), Toast.LENGTH_SHORT)
            .show()
    }
}
