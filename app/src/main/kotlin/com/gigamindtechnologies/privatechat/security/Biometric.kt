package com.gigamindtechnologies.privatechat.security

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal

/**
 * Fingerprint unlock through Android's own prompt (Android 10 and newer). It only opens the
 * app lock; the PIN always works as well.
 */
object Biometric {
    fun isAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val manager = context.getSystemService(BiometricManager::class.java) ?: return false
        val result = if (Build.VERSION.SDK_INT >= 30) {
            manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            manager.canAuthenticate()
        }
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    /** Shows the system fingerprint prompt; [onSuccess] runs on the main thread. Returns a way to cancel it. */
    fun prompt(activity: Activity, onSuccess: () -> Unit): CancellationSignal? {
        if (Build.VERSION.SDK_INT < 29 || !isAvailable(activity)) return null
        val executor = activity.mainExecutor
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle("Unlock PrivateChat")
            .setNegativeButton("Use PIN", executor) { _, _ -> }
            .setConfirmationRequired(false)
            .apply { if (Build.VERSION.SDK_INT >= 30) setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG) }
            .build()
        val cancel = CancellationSignal()
        runCatching {
            prompt.authenticate(
                cancel,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
                },
            )
        }.onFailure { return null }
        return cancel
    }
}
