package com.gigamindtechnologies.privatechat.core.store

import com.gigamindtechnologies.privatechat.core.crypto.SecureRandomHolder
import com.gigamindtechnologies.privatechat.core.encoding.B64
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.math.min

/**
 * The app lock PIN. Only a salted PBKDF2 hash is kept, inside the encrypted vault. The lock
 * keeps people who pick up an unlocked phone out of your chats; the messages themselves are
 * protected by the vault encryption either way. Anyone who could read the hash could already
 * read the vault, so the iteration count only needs to be moderate; guessing is slowed down by
 * [delayAfter] instead, and unlocking stays quick on slow phones.
 */
object PinLock {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 12
    private const val ITERATIONS = 25_000
    private const val FREE_TRIES = 5
    private const val FIRST_DELAY_MS = 30_000L
    private const val MAX_DELAY_MS = 15 * 60_000L

    fun isValid(pin: String) = pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }

    fun create(pin: String, biometric: Boolean = false): AppLockConfig {
        require(isValid(pin)) { "PIN must be $MIN_LENGTH to $MAX_LENGTH digits" }
        val salt = SecureRandomHolder.bytes(16)
        return AppLockConfig(B64.encode(salt), B64.encode(hash(pin, salt, ITERATIONS)), ITERATIONS, biometric)
    }

    fun matches(pin: String, config: AppLockConfig): Boolean {
        if (!isValid(pin)) return false
        val expected = B64.decode(config.hash)
        return MessageDigest.isEqual(expected, hash(pin, B64.decode(config.salt), config.iterations))
    }

    /** After [FREE_TRIES] wrong PINs, each further try waits 30 s, doubling up to 15 minutes. */
    fun delayAfter(failures: Int): Long {
        if (failures < FREE_TRIES) return 0
        val doublings = min(failures - FREE_TRIES, 10)
        return min(FIRST_DELAY_MS shl doublings, MAX_DELAY_MS)
    }

    /** The updated lock after a wrong PIN at time [now]. */
    fun failed(config: AppLockConfig, now: Long): AppLockConfig {
        val failures = config.failures + 1
        return config.copy(failures = failures, blockedUntil = now + delayAfter(failures))
    }

    private fun hash(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
