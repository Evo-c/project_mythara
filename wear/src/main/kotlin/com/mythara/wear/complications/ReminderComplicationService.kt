package com.mythara.wear.complications

import android.app.PendingIntent
import android.content.Intent
import android.text.format.DateUtils
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import com.mythara.wear.ClusterDataStore
import com.mythara.wear.MainActivity

/**
 * Watch-face complication for the next upcoming reminder.
 *
 * The phone pushes the soonest scheduled task over the Data Layer;
 * [com.mythara.wear.MytharaWearDataReceiver] caches it via
 * [ClusterDataStore] and refreshes this service, so the Mythara
 * Tactical face's reminder line stays current. Tapping it opens the
 * watch app. Shows "No reminders" when nothing is upcoming.
 */
class ReminderComplicationService : ComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        complicationFor(type, "Stretch · in 2 hr")

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener,
    ) {
        val rem = ClusterDataStore.reminder(this)
        val text = if (rem != null) {
            val whenLabel = DateUtils.getRelativeTimeSpanString(
                rem.atMs,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            ).toString()
            // Lead with the live countdown so a glance at the wrist
            // answers "how soon" first; the title carries the
            // "what" after the dash.
            "$whenLabel - ${rem.title}"
        } else {
            "No reminders"
        }
        listener.onComplicationData(complicationFor(request.complicationType, text))
    }

    private fun complicationFor(type: ComplicationType, text: String): ComplicationData? {
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder(text.take(20)).build(),
                contentDescription = PlainComplicationText.Builder(text).build(),
            ).setTapAction(tap).build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = PlainComplicationText.Builder(text.take(80)).build(),
                contentDescription = PlainComplicationText.Builder(text).build(),
            ).setTapAction(tap).build()

            else -> null
        }
    }
}
