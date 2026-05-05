package com.xtrust.standalone.wrapup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.xtrust.standalone.MainActivity

class WrapupNotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "session_wrapup"
        const val NOTIFICATION_ID = 1001
        private const val TOTAL_STEPS = 4
    }

    init {
        createChannel()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AI議事録",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "議事録の要約処理"
            setSound(null, null)
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun buildProgressNotification(text: String, step: Int): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("議事録を作成中 ($step/$TOTAL_STEPS)")
            .setContentText(text)
            .setProgress(TOTAL_STEPS, step, false)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun buildGeneratingNotification(elapsedSeconds: Int): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("議事録を作成中 (3/$TOTAL_STEPS)")
            .setContentText("要約生成中… (${elapsedSeconds}秒経過)")
            .setProgress(TOTAL_STEPS, 3, false)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun buildCompletedNotification(sessionTitle: String): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("議事録ができました")
            .setContentText(sessionTitle)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
    }

    fun buildFailedNotification(error: String): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("議事録の作成に失敗しました")
            .setContentText(error)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
    }

    fun notify(notification: Notification) {
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }
}
