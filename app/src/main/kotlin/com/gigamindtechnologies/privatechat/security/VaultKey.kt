package com.gigamindtechnologies.privatechat.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.gigamindtechnologies.privatechat.core.crypto.SecureRandomHolder
import com.gigamindtechnologies.privatechat.core.store.AesGcmSealer
import com.gigamindtechnologies.privatechat.core.store.Sealer
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Envelope encryption for everything PrivateChat stores:
 * a random 256-bit data key encrypts the files, and that data key is itself encrypted by an
 * AES key that lives inside the Android Keystore (hardware-backed on most phones) and can
 * never be extracted. Deleting the Keystore key makes every file permanently unreadable.
 */
object VaultKey {
    private const val ALIAS = "privatechat.vault.v1"
    private const val LABEL = "vault-key"

    fun sealer(context: Context): Sealer {
        val wrapper = AesGcmSealer(keystoreKey())
        val file = keyFile(context)
        val dataKey = if (file.exists()) {
            wrapper.open(file.readBytes(), LABEL)
        } else {
            SecureRandomHolder.bytes(32).also { key ->
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeBytes(wrapper.seal(key, LABEL))
                check(tmp.renameTo(file)) { "Could not save the vault key" }
            }
        }
        return AesGcmSealer(SecretKeySpec(dataKey, "AES"))
    }

    fun destroy(context: Context) {
        keyFile(context).delete()
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS) }
    }

    private fun keyFile(context: Context) = File(context.noBackupFilesDir, "vault.key")

    private fun keystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private const val KEYSTORE = "AndroidKeyStore"
}
