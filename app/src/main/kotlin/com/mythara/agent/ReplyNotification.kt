package com.mythara.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.mythara.MainActivity
import com.mythara.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts a notification with Lumi's reply text when she finishes a
 * turn while Mythara isn't visible to the user.
 *
 * Solves a real failure mode: when the agent calls open_app (or any
 * other tool that launches a different activity), Mythara is pushed
 * to background. The reply lands cleanly in Room and TTS plays
 * audibly, but the user — now staring at the other app — doesn't
 * see the text answer and may not register the spoken one. They
 * have to manually navigate back to find the answer they asked for.
 *
 * This notification fixes that: the user sees "Lumi: here are your
 * events for today …" in their status bar within a second of the
 * turn finishing. Tap → opens Mythara chat with that turn focused.
 *
 * Only posts when the app is NOT in foreground; ProcessLifecycle
 * RESUMED state means the chat surface (or settings) is visible and
 * the user will see the reply natively. We don't double up.
 *
 * Auto-cancels on tap and after [AUTO_DISMISS_MS] so old replies
 * don't accumulate in the shade. Each new reply replaces the
 * previous via fixed NOTIFICATION_ID.
 */
@Singleton
class ReplyNotification @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {

    /**
     * Post the reply notification if Mythara is currently in
     * background. No-op when foregrounded — the chat surface is
     * already showing the answer.
     */
    fun postIfBackgrounded(replyText: String) {
        if (replyText.isBlank()) return
        if (isAppForegrounded()) return
        createChannel()

        val openChat = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val tapPi = PendingIntent.getActivity(
            ctx,
            REQUEST_TAP,
            openChat,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // BigTextStyle so long replies expand cleanly in the shade
        // when the user pulls it down. Truncate to a sane cap for
        // the in-line view.
        val preview = replyText.take(MAX_PREVIEW_CHARS)

        // Action 1: REPLY — inline text input via RemoteInput. Works
        // on the lockscreen, in the notification shade, and on
        // Wear OS connected watches (which surface it as a
        // smart-reply chip + dictation field). The text routes
        // through NotificationReplyReceiver → AgentRunner.submit
        // without ever opening the app.
        val replyRemoteInput = RemoteInput.Builder(NotificationReplyReceiver.KEY_REPLY_TEXT)
            .setLabel("Reply to Lumi")
            .setAllowFreeFormInput(true)
            // Suggest 3 quick chips that work both on watch + phone.
            // Wear OS surfaces these as tap-to-send canned replies.
            .setChoices(arrayOf("Got it", "Tell me more", "Not now"))
            .build()
        val replyIntent = Intent(ctx, NotificationReplyReceiver::class.java).apply {
            action = NotificationReplyReceiver.ACTION_NOTIFICATION_REPLY
        }
        val replyPi = PendingIntent.getBroadcast(
            ctx,
            REQUEST_REPLY,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val replyAction = NotificationCompat.Action.Builder(
            R.mipmap.ic_launcher_round,
            "Reply",
            replyPi,
        )
            .addRemoteInput(replyRemoteInput)
            .setAllowGeneratedReplies(true)
            // SEMANTIC_ACTION_REPLY makes Wear OS treat this as a
            // first-class reply slot (chip in the message gutter).
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false) // critical for Wear inline-reply
            .build()

        // Action 2: VOICE — opens Mythara with mic listening
        // (ACTION_ASSIST path; same as Pixel Buds tap-and-hold).
        // Wear OS routes this to the watch's "tap to talk" surface
        // when the user is wearing one.
        val voiceIntent = Intent(ctx, MainActivity::class.java).apply {
            action = Intent.ACTION_ASSIST
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val voicePi = PendingIntent.getActivity(
            ctx,
            REQUEST_VOICE,
            voiceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val voiceAction = NotificationCompat.Action.Builder(
            R.mipmap.ic_launcher_round,
            "Voice",
            voicePi,
        ).build()

        // Wearable extender. The Reply action is bridged to Wear
        // automatically; setHintLaunchesActivity=false on actions
        // already-bridged keeps them on the watch's notification
        // card rather than forcing a phone launch.
        val wearable = NotificationCompat.WearableExtender()
            .addAction(replyAction)
            .addAction(voiceAction)
            // Big icon could go here later; default is fine.

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle("Lumi answered")
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(replyText))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(tapPi)
            .addAction(replyAction)
            .addAction(voiceAction)
            .extend(wearable)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            // PRIORITY_HIGH so the heads-up surface appears on
            // Android 10+. The user explicitly asked for this in
            // the bug report ("I had to manually go back to get
            // the answer") — silent dismissal isn't what they want.
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setTimeoutAfter(AUTO_DISMISS_MS)
            .build()
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, notification)
    }

    fun cancel() {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(NOTIFICATION_ID)
    }

    private fun isAppForegrounded(): Boolean {
        return ProcessLifecycleOwner.get().lifecycle.currentState
            .isAtLeast(Lifecycle.State.RESUMED)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Lumi replies",
            // DEFAULT (heads-up + sound) so backgrounded answers
            // actually catch the user's attention.
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Shows Lumi's reply when she finishes a turn while Mythara is in background."
            setShowBadge(true)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "mythara_agent_reply"
        private const val NOTIFICATION_ID = 0xA9E49 // distinct from FGS + QuickTalk ids
        private const val REQUEST_TAP = 4072
        private const val REQUEST_REPLY = 4073
        private const val REQUEST_VOICE = 4074
        private const val MAX_PREVIEW_CHARS = 120
        /** Notification auto-dismisses after 5 min so old replies don't pile up. */
        private const val AUTO_DISMISS_MS = 5L * 60 * 1000
    }
}
