package com.gigamindtechnologies.privatechat.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri

/**
 * There is no push server, so PrivateChat must keep its own connection open. Android's battery
 * optimization cuts background apps off the network, so we ask to be exempt (allowed by Google
 * Play for chat apps that cannot use Firebase Cloud Messaging).
 */
object BackgroundAccess {
    fun isUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** Shows the system's "Let app always run in background?" dialog. */
    @SuppressLint("BatteryLife")
    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())

    /** Fallback: the full list of apps, for phones that don't show the dialog. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun request(context: Context) {
        try {
            context.startActivity(requestIntent(context))
        } catch (_: Exception) {
            runCatching { context.startActivity(settingsIntent()) }
        }
    }
}
