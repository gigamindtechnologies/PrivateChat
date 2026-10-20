package com.gigamindtechnologies.privatechat.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.gigamindtechnologies.privatechat.R
import com.gigamindtechnologies.privatechat.ui.MainActivity

/** Notifications never contain names or message text: only "you have a new message". */
object Notifications {
    const val SERVICE_ID = 1
    private const val MESSAGE_ID = 2
    private const val CHANNEL_SERVICE = "status"
    private const val CHANNEL_MESSAGES = "messages"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Connection status", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "New messages", NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    fun service(context: Context): Notification {
        val stop = PendingIntent.getService(
            context,
            0,
            Intent(context, ChatService::class.java).setAction(ChatService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_lock)
            .setContentTitle("PrivateChat is online")
            .setContentText("Your contacts can reach you privately through Tor")
            .setContentIntent(openApp(context))
            .addAction(0, "Go offline", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun newMessage(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_lock)
            .setContentTitle("New message")
            .setContentText("Open PrivateChat to read it")
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        NotificationManagerCompat.from(context).notify(MESSAGE_ID, notification)
    }

    fun clearMessages(context: Context) = NotificationManagerCompat.from(context).cancel(MESSAGE_ID)

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
