// VoIPDirectCall.kt

package com.t4paN.AVA

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.text.format.DateFormat
import android.util.Log

/**
 * VoIPDirectCall - place a VoIP call through the contact's own data row instead
 * of driving the messenger's user interface.
 *
 * Viber, WhatsApp and Signal each sync extra rows into the Android address book:
 * the "Free call" / "Video call" / "Message" entries that appear under a contact
 * in the Contacts app. Every one is a row in ContactsContract.Data carrying an
 * app-specific mimetype, and ACTION_VIEW on that row performs the action
 * outright. No deep link into a chat screen, no calibrated tap coordinates, no
 * AccessibilityService — and it survives the messenger redesigning its screens.
 *
 * Needs only READ_CONTACTS, which AVA already holds.
 *
 * It works only where the messenger actually synced the contact into the address
 * book. That is the default behaviour but not a guarantee, and the exact
 * mimetype strings have shifted between app versions, so every caller must keep
 * the deep-link + auto-click path as a fallback. Whatever rows a device really
 * has are recorded in [lastReport] so an unknown build can be read off the
 * phone itself rather than guessed at.
 */
object VoIPDirectCall {
    private const val TAG = "VoIPDirectCall"

    private const val PREFS_NAME = "voip_manager_prefs"
    private const val KEY_LAST_REPORT = "last_direct_call_report"

    /**
     * A contact data row we can fire to start a call.
     */
    data class Action(
        val dataId: Long,
        val mimeType: String,
        val packageName: String,
        val label: String
    ) {
        val uri: Uri
            get() = ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, dataId)
    }

    /**
     * Mimetypes known to place a free voice call, per package, best first.
     * Anything not listed here can still be picked up by [isPlausibleCallRow].
     */
    private val knownCallMimeTypes = mapOf(
        "com.viber.voip" to listOf(
            "vnd.android.cursor.item/vnd.com.viber.voip.viber_number_call"
        ),
        "com.whatsapp" to listOf(
            "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
        ),
        "org.thoughtcrime.securesms" to listOf(
            "vnd.android.cursor.item/vnd.org.thoughtcrime.securesms.call"
        )
    )

    /**
     * Tokens that disqualify a row from being fired automatically.
     *
     * "out" is the important one: Viber registers viber_out_call for paid calls
     * to ordinary landlines. Firing that by accident would spend the user's
     * money. "video" is excluded because a video call is the wrong thing for a
     * partially-sighted user and eats mobile data.
     */
    private val excludedTokens = setOf(
        "out", "video", "message", "msg", "chat", "sms", "invite", "profile", "share"
    )

    /**
     * Find a fireable call row for this contact, or null if there is none.
     *
     * Always writes [lastReport], whether it succeeds or not.
     */
    fun findAction(context: Context, packageName: String, phoneNumber: String): Action? {
        val report = StringBuilder()
        val stamp = DateFormat.format("HH:mm:ss", System.currentTimeMillis())
        report.appendLine("[$stamp] direct call lookup")
        report.appendLine("number: $phoneNumber")
        report.appendLine("app: $packageName")

        val contactId = lookupContactId(context, phoneNumber)
        if (contactId == null) {
            Log.w(TAG, "No contact matches $phoneNumber - direct path unavailable")
            report.appendLine("RESULT: no contact matched this number")
            saveReport(context, report.toString())
            return null
        }
        report.appendLine("contact id: $contactId")

        val rows = readContactRows(context, contactId)
        Log.i(TAG, "Contact $contactId carries ${rows.size} data rows")
        report.appendLine("data rows: ${rows.size}")
        rows.forEach {
            Log.i(TAG, "  id=${it.dataId} mime=${it.mimeType} label=${it.label}")
            report.appendLine("  ${it.mimeType}${if (it.label.isNotEmpty()) "  (${it.label})" else ""}")
        }

        val ours = rows.filter { it.mimeType.contains(packageName) }
        if (ours.isEmpty()) {
            Log.w(TAG, "$packageName registered no rows on this contact")
            report.appendLine("RESULT: $packageName has not synced this contact")
            saveReport(context, report.toString())
            return null
        }

        val known = knownCallMimeTypes[packageName].orEmpty()
        val picked = ours.firstOrNull { it.mimeType in known }
            ?: ours.firstOrNull { isPlausibleCallRow(it.mimeType) }

        if (picked == null) {
            Log.w(TAG, "$packageName rows present but none look like a free voice call")
            report.appendLine("RESULT: rows found, none usable as a free voice call")
        } else {
            val how = if (picked.mimeType in known) "known" else "guessed from the mimetype"
            Log.i(TAG, "Chose ${picked.mimeType} ($how)")
            report.appendLine("RESULT: using ${picked.mimeType} ($how)")
        }
        saveReport(context, report.toString())
        return picked?.copy(packageName = packageName)
    }

    /**
     * Bring the messenger up before firing a row at it.
     *
     * A cold app cannot service the row: on a Moto G84 with Viber 28.5.2.0
     * force-stopped, firing straight away made Viber show its
     * `com.viber.voip.action.SYSTEM_DIALOG` screen and no call happened, while the
     * identical row on a phone where Viber was already running dialled instantly.
     * Launching first and waiting is the same reason twoStageLaunch exists.
     *
     * Returns false when the app has no launcher intent, so the caller can skip
     * the warm-up wait rather than pausing for nothing.
     */
    fun warmUp(context: Context, packageName: String): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            Log.w(TAG, "No launcher intent for $packageName - cannot warm it up")
            return false
        }
        return try {
            context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Log.d(TAG, "Warming up $packageName")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not warm up $packageName", e)
            false
        }
    }

    /**
     * Fire the row. Returns false if the messenger refused the intent, in which
     * case the caller should fall back to the deep-link path.
     */
    fun place(context: Context, action: Action): Boolean {
        Log.i(TAG, "Firing ${action.mimeType} on ${action.uri}")

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(action.uri, action.mimeType)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        // Named package first, so a second app claiming the same mimetype cannot
        // steal the call. If that finds no activity, retry unrestricted.
        return try {
            context.startActivity(Intent(intent).setPackage(action.packageName))
            true
        } catch (e: Exception) {
            Log.w(TAG, "No activity in ${action.packageName} took the row, retrying open", e)
            try {
                context.startActivity(intent)
                true
            } catch (e2: Exception) {
                Log.e(TAG, "Direct call intent rejected outright", e2)
                false
            }
        }
    }

    /**
     * Heuristic for a mimetype we have never seen: it has to name a call, and it
     * must not carry any of the disqualifying tokens. Deliberately strict — a
     * wrong guess here places a paid call or a video call.
     */
    private fun isPlausibleCallRow(mimeType: String): Boolean {
        val subtype = mimeType.substringAfterLast('/')
        val tokens = subtype.split('.', '_').filter { it.isNotEmpty() }
        if (tokens.none { it == "call" }) return false
        return tokens.none { it in excludedTokens }
    }

    private fun lookupContactId(context: Context, phoneNumber: String): Long? {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.CONTACT_ID),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "PhoneLookup failed for $phoneNumber", e)
            null
        }
    }

    /**
     * Read every data row belonging to an aggregated contact.
     *
     * This goes through RawContactsEntity, not ContactsContract.Data, and the
     * difference is not cosmetic: measured on a Galaxy A05s (Android 15) with
     * Viber, WhatsApp and Signal all syncing, the Data view exposed Viber's call
     * rows but hid WhatsApp's and Signal's entirely, while RawContactsEntity
     * returned all three. Query Data here and WhatsApp and Signal silently never
     * work.
     */
    private fun readContactRows(context: Context, contactId: Long): List<Action> {
        val projection = arrayOf(
            ContactsContract.RawContactsEntity.DATA_ID,
            ContactsContract.RawContactsEntity.MIMETYPE,
            ContactsContract.RawContactsEntity.DATA3
        )
        return try {
            context.contentResolver.query(
                ContactsContract.RawContactsEntity.CONTENT_URI,
                projection,
                "${ContactsContract.RawContacts.CONTACT_ID} = ?",
                arrayOf(contactId.toString()),
                null
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        if (cursor.isNull(0)) continue
                        val mime = cursor.getString(1) ?: continue
                        add(
                            Action(
                                dataId = cursor.getLong(0),
                                mimeType = mime,
                                // Filled in by findAction, which knows the target app.
                                packageName = "",
                                label = cursor.getString(2) ?: ""
                            )
                        )
                    }
                }
            }.orEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Could not read data rows for contact $contactId", e)
            emptyList()
        }
    }

    // ==================== Diagnostics ====================

    private fun saveReport(context: Context, report: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_REPORT, report).apply()
    }

    /**
     * What the last lookup saw. Surfaced in MainActivity's menu so a test on a
     * phone with no adb attached can still report which mimetypes exist.
     */
    fun lastReport(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_REPORT, null)
}
