// MissedCallsReader.kt

package com.t4paN.AVA

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Calendar

/**
 * Reads missed calls straight from the system call log.
 *
 * This replaces the previous approach of launching the dialer and driving it with
 * calibrated coordinate taps through the accessibility service, then scraping
 * contentDescription strings off the screen. That was per-device fragile (it
 * depended on screen size, One UI version, dialer layout and load timing) and
 * needed a calibration pass on every phone. CallLog is the actual API for this
 * data: no navigation, no coordinates, no timing races, identical on every device.
 */
object MissedCallsReader {
    private const val TAG = "MissedCallsReader"
    private const val UTTERANCE_ID = "missed_calls_tts"

    /** Cap on how many calls we read out — beyond this it stops being useful to listen to. */
    private const val MAX_CALLS = 5

    private val greekDays = arrayOf(
        "Κυριακή", "Δευτέρα", "Τρίτη", "Τετάρτη", "Πέμπτη", "Παρασκευή", "Σάββατο"
    )

    private val greekMonths = arrayOf(
        "Ιανουαρίου", "Φεβρουαρίου", "Μαρτίου", "Απριλίου", "Μαΐου", "Ιουνίου",
        "Ιουλίου", "Αυγούστου", "Σεπτεμβρίου", "Οκτωβρίου", "Νοεμβρίου", "Δεκεμβρίου"
    )

    data class MissedCall(
        val name: String?,
        val number: String,
        val timestamp: Long
    )

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Most recent missed calls, newest first. Returns empty if the permission is
     * missing or the query fails — callers should check hasPermission() to tell
     * "no permission" apart from "nothing missed".
     */
    fun query(context: Context, limit: Int = MAX_CALLS): List<MissedCall> {
        if (!hasPermission(context)) {
            Log.w(TAG, "READ_CALL_LOG not granted")
            return emptyList()
        }

        val calls = mutableListOf<MissedCall>()
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                "${CallLog.Calls.TYPE} = ?",
                arrayOf(CallLog.Calls.MISSED_TYPE.toString()),
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val numberCol = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val dateCol = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)

                while (cursor.moveToNext() && calls.size < limit) {
                    val number = cursor.getString(numberCol) ?: ""
                    val cachedName = cursor.getString(nameCol)?.takeIf { it.isNotBlank() }
                    calls.add(
                        MissedCall(
                            name = cachedName ?: lookupContactName(context, number),
                            number = number,
                            timestamp = cursor.getLong(dateCol)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Call log query failed", e)
            return emptyList()
        }

        Log.d(TAG, "Found ${calls.size} missed calls")
        return calls
    }

    /**
     * CACHED_NAME is usually populated by the dialer, but it's empty for calls that
     * arrived before the contact was saved, so fall back to our own contact list.
     */
    private fun lookupContactName(context: Context, number: String): String? {
        if (number.isBlank()) return null
        return try {
            val tail = digitTail(number)
            if (tail.isEmpty()) return null
            ContactRepository.loadContacts(context)
                .firstOrNull { digitTail(it.phoneNumber) == tail }
                ?.displayName
        } catch (e: Exception) {
            Log.e(TAG, "Contact lookup failed", e)
            null
        }
    }

    /**
     * Last 9 digits, which makes +30 210…, 0030 210… and 210… all compare equal
     * without needing to know the dialling code.
     */
    private fun digitTail(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length <= 9) digits else digits.takeLast(9)
    }

    /** Greek announcement: count, then each caller with day and time. */
    fun buildAnnouncement(calls: List<MissedCall>): String {
        if (calls.isEmpty()) return "Δεν έχετε αναπάντητες κλήσεις"

        val intro = if (calls.size == 1) {
            "Έχετε μία αναπάντητη κλήση"
        } else {
            "Έχετε ${calls.size} αναπάντητες κλήσεις"
        }

        val details = calls.mapIndexed { index, call ->
            val who = call.name ?: spokenNumber(call.number)
            val when_ = formatWhen(call.timestamp)
            if (calls.size == 1) "Από $who, $when_" else "${index + 1}. Από $who, $when_"
        }.joinToString(". ")

        return "$intro. $details."
    }

    /**
     * Unknown numbers are spaced out so TTS reads them digit by digit instead of as
     * one enormous number, which is unusable if you're trying to write it down.
     */
    private fun spokenNumber(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.isEmpty()) "άγνωστο αριθμό" else digits.toCharArray().joinToString(" ")
    }

    private fun formatWhen(timestamp: Long): String {
        val call = Calendar.getInstance().apply { timeInMillis = timestamp }
        val now = Calendar.getInstance()

        val time = String.format(
            "%02d:%02d",
            call.get(Calendar.HOUR_OF_DAY),
            call.get(Calendar.MINUTE)
        )

        val daysApart = daysBetween(call, now)
        val day = when {
            daysApart == 0L -> "σήμερα"
            daysApart == 1L -> "χθες"
            daysApart in 2..6 -> greekDays[call.get(Calendar.DAY_OF_WEEK) - 1]
            else -> "${call.get(Calendar.DAY_OF_MONTH)} ${greekMonths[call.get(Calendar.MONTH)]}"
        }

        return "$day, $time"
    }

    /** Whole calendar days between two instants, ignoring time of day. */
    private fun daysBetween(from: Calendar, to: Calendar): Long {
        val a = (from.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val b = (to.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return (b.timeInMillis - a.timeInMillis) / 86_400_000L
    }

    /**
     * Full flow: query off the main thread, then announce with a cancel overlay.
     * Unlike the old dialer-driven version this never leaves the current screen,
     * so there is nothing to navigate back from afterwards.
     */
    fun announce(context: Context) {
        val handler = Handler(Looper.getMainLooper())

        if (!hasPermission(context)) {
            Log.e(TAG, "Cannot read missed calls without READ_CALL_LOG")
            handler.post {
                TtsManager.speak(
                    "Χρειάζεται άδεια για το ιστορικό κλήσεων. Ανοίξτε την εφαρμογή για να τη δώσετε.",
                    UTTERANCE_ID
                )
            }
            return
        }

        Thread {
            val calls = query(context)
            val announcement = buildAnnouncement(calls)
            Log.i(TAG, "Announcement: $announcement")

            handler.post {
                CallOverlayController.showRecording {
                    Log.d(TAG, "User cancelled missed calls announcement")
                    TtsManager.stop()
                    CallOverlayController.dismiss()
                }

                TtsManager.setUtteranceListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == UTTERANCE_ID) {
                            handler.post { CallOverlayController.dismiss() }
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "TTS error: $utteranceId")
                        handler.post { CallOverlayController.dismiss() }
                    }
                })

                TtsManager.speak(announcement, UTTERANCE_ID)
            }
        }.start()
    }
}
