package com.gigamindtechnologies.privatechat.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import com.gigamindtechnologies.privatechat.graph
import kotlin.concurrent.thread

/**
 * Wakes the phone about every 15 minutes, even in deep sleep (Doze), to check that connections
 * are still alive and to reach contacts again. Without it, a sleeping phone's connections can
 * silently die and nothing would wake it up to notice. Uses an inexact alarm, which needs no
 * special permission and is cheap on battery.
 */
object Heartbeat {
    private const val INTERVAL_MS = 15 * 60_000L
    private const val AWAKE_MS = 30_000L

    fun schedule(context: Context) {
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + INTERVAL_MS,
            pendingIntent(context),
        )
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, Receiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val node = context.graph.node.value ?: return // offline: nothing to keep alive
            // Stay awake long enough for Tor to catch up and for contacts to reconnect.
            context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PrivateChat:heartbeat")
                .acquire(AWAKE_MS)
            val pending = goAsync()
            thread {
                try {
                    node.heartbeat()
                } finally {
                    pending.finish()
                }
            }
            schedule(context)
        }
    }
}
