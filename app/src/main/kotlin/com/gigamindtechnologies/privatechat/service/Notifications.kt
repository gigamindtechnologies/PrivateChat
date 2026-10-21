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
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.gigamindtechnologies.privatechat.R
import com.gigamindtechnologies.privatechat.ui.MainActivity

/** Notifications never contain names or message text: only how many new messages there are. */
object Notifications {
    const val SERVICE_ID = 1
    private const val MESSAGES_ID = 2
    private const val PROBLEM_ID = 3
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

    /** The permanent notice of the background service; [text] says what the connection is doing. */
    fun service(context: Context, text: String): Notification {
        val stop = PendingIntent.getService(
            context,
            0,
            Intent(context, ChatService::class.java).setAction(ChatService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_lock)
            .setContentTitle("PrivateChat")
            .setContentText(text)
            .setContentIntent(openApp(context))
            .addAction(0, "Go offline", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun updateService(context: Context, text: String) = post(context, SERVICE_ID, service(context, text))

    /**
     * Shows "N new messages", or removes the notification when there are none. [alert] makes
     * it ring (a new message arrived); otherwise the number is updated silently.
     */
    fun showUnread(context: Context, count: Int, alert: Boolean) {
        if (count <= 0) {
            NotificationManagerCompat.from(context).cancel(MESSAGES_ID)
            return
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_lock)
            .setContentTitle(if (count == 1) "New message" else "$count new messages")
            .setContentText("Open PrivateChat to read")
            .setNumber(count)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setOnlyAlertOnce(!alert)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        post(context, MESSAGES_ID, notification)
    }

    /** Shown when the app could not stay online, so the user knows they are unreachable. */
    fun connectionProblem(context: Context) = post(
        context,
        PROBLEM_ID,
        NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_lock)
            .setContentTitle("PrivateChat is offline")
            .setContentText("It couldn't connect. Tap to try again.")
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build(),
    )

    fun clearConnectionProblem(context: Context) = NotificationManagerCompat.from(context).cancel(PROBLEM_ID)

    /** True when the user turned notifications off for the app or for new messages. */
    fun areBlocked(context: Context): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return true
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_MESSAGES)
        return channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE
    }

    fun openSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun post(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        // A broken notification must never break message delivery.
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
