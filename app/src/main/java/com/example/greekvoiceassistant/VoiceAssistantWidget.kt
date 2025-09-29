package com.example.greekvoiceassistant

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.RemoteViews
import android.widget.Toast
import java.util.Locale

class VoiceAssistantWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        super.onReceive(context, intent)
        Log.d("VoiceWidget", "onReceive action=${intent?.action}")

        if (intent?.action == "VOICE_BUTTON_CLICKED" && context != null) {
            Log.d("VoiceWidget", "Voice button clicked - starting SpeakService")

            val serviceIntent = Intent(context, SpeakService::class.java).apply {
                putExtra(SpeakService.EXTRA_TEXT, "πείτε όνομα") // Greek text to speak
            }

            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e("VoiceWidget", "Failed to start SpeakService", e)
            }
        }
    }

    companion object {
        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.voice_assistant_widget)

            // Create a simple broadcast intent
            val intent = Intent(context, VoiceAssistantWidget::class.java)
            intent.action = "VOICE_BUTTON_CLICKED"

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0, // Use 0 instead of appWidgetId
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            views.setOnClickPendingIntent(R.id.voice_button, pendingIntent)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    private fun speakTest(context: Context) {
        var tts: TextToSpeech? = null

        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.getDefault())
                if (result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    tts?.speak("Hello test", TextToSpeech.QUEUE_FLUSH, null, null)
                }
            }
        }
    }
}
