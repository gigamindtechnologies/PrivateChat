package com.gigamindtechnologies.privatechat.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gigamindtechnologies.privatechat.graph

/** Goes back online after the phone restarts or the app is updated, unless the user chose to be offline. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val graph = context.graph
        if (!graph.hasIdentity() || !graph.wantsOnline.value) return
        try {
            graph.goOnline()
        } catch (e: Exception) {
            // Android refused a background start; the app reconnects when it is next opened.
            graph.log("Could not go online after boot", e)
        }
    }
}
