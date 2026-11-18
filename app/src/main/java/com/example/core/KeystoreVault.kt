package com.example.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.data.ZeroLogEngine
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Hardware-backed AES-256-GCM master vault wrapper using AndroidKeyStore when present,
 * with an explicit memory/device-bound AES-256-GCM fallback for JVM Robolectric environments.
 * Designed to protect future Rust `smp-rs` store keys (`SMP_STORE_KEY`) and BIP39 root seeds.
 */
class KeystoreVault(private val context: Context) {

    enum class VaultMode(val label: String) {
        ANDROID_KEYSTORE_GCM("AndroidKeyStore AES-256-GCM"),
        SOFTWARE_GCM_FALLBACK("JVM/Software AES-256-GCM")
    }

    data class SealedBlob(
        val ivBase64: String,
        val ciphertextBase64: String,
        val mode: VaultMode
    ) {
        fun serialize(): String = "${mode.name}:$ivBase64:$ciphertextBase64"

        companion object {
            fun deserialize(raw: String): SealedBlob? {
                val parts = raw.split(':')
                if (parts.size != 3) return null
                val parsedMode = try {
                    VaultMode.valueOf(parts[0])
                } catch (_: IllegalArgumentException) {
                    return null
                }
                return SealedBlob(
                    ivBase64 = parts[1],
                    ciphertextBase64 = parts[2],
                    mode = parsedMode
                )
            }
        }
    }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "paranoid_vault_master_v1"
        private const val FALLBACK_PREFS = "paranoid_vault_fallback_prefs"
        private const val FALLBACK_KEY_PREF = "fallback_wrapped_key"
        private const val AES_GCM_NOPADDING = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val GCM_IV_BYTES = 12
    }

    private val secureRandom = SecureRandom()

    fun activeMode(): VaultMode {
        return if (isAndroidKeyStoreAvailable()) {
            VaultMode.ANDROID_KEYSTORE_GCM
        } else {
            VaultMode.SOFTWARE_GCM_FALLBACK
        }
    }

    fun isAndroidKeyStoreAvailable(): Boolean {
        return try {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER)
            ks.load(null)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun getOrCreateKey(): Pair<SecretKey, VaultMode> {
        if (isAndroidKeyStoreAvailable()) {
            try {
                val ks = KeyStore.getInstance(KEYSTORE_PROVIDER)
                ks.load(null)
                val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
                if (existing != null) {
                    return existing to VaultMode.ANDROID_KEYSTORE_GCM
                }
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
                generator.init(spec)
                return generator.generateKey() to VaultMode.ANDROID_KEYSTORE_GCM
            } catch (e: Exception) {
                ZeroLogEngine.log("Vault", "AndroidKeyStore init fallback: ${e.message}", "WARN")
            }
        }

        // Software AES-256-GCM fallback (used on JVM/Robolectric or stripped ROMs without KeyStore)
        val prefs = context.getSharedPreferences(FALLBACK_PREFS, Context.MODE_PRIVATE)
        val existingB64 = prefs.getString(FALLBACK_KEY_PREF, null)
        val keyBytes = if (existingB64 != null) {
            Base64.decode(existingB64, Base64.NO_WRAP)
        } else {
            ByteArray(32).also { generated ->
                secureRandom.nextBytes(generated)
                prefs.edit().putString(
                    FALLBACK_KEY_PREF,
                    Base64.encodeToString(generated, Base64.NO_WRAP)
                ).apply()
            }
        }
        return try {
            SecretKeySpec(keyBytes, "AES") to VaultMode.SOFTWARE_GCM_FALLBACK
        } finally {
            keyBytes.fill(0)
        }
    }

    fun seal(plaintext: ByteArray, associatedData: ByteArray? = null): SealedBlob {
        val (key, mode) = getOrCreateKey()
        val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
        if (mode == VaultMode.ANDROID_KEYSTORE_GCM) {
            cipher.init(Cipher.ENCRYPT_MODE, key)
        } else {
            val iv = ByteArray(GCM_IV_BYTES)
            secureRandom.nextBytes(iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        if (associatedData != null && associatedData.isNotEmpty()) {
            cipher.updateAAD(associatedData)
        }
        val encrypted = cipher.doFinal(plaintext)
        return SealedBlob(
            ivBase64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            ciphertextBase64 = Base64.encodeToString(encrypted, Base64.NO_WRAP),
            mode = mode
        )
    }

    fun unseal(blob: SealedBlob, associatedData: ByteArray? = null): ByteArray? {
        val (key, _) = getOrCreateKey()
        return try {
            val iv = Base64.decode(blob.ivBase64, Base64.NO_WRAP)
            val encrypted = Base64.decode(blob.ciphertextBase64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            if (associatedData != null && associatedData.isNotEmpty()) {
                cipher.updateAAD(associatedData)
            }
            cipher.doFinal(encrypted)
        } catch (_: Exception) {
            null
        }
    }

    fun wipeMasterKey() {
        try {
            if (isAndroidKeyStoreAvailable()) {
                val ks = KeyStore.getInstance(KEYSTORE_PROVIDER)
                ks.load(null)
                if (ks.containsAlias(KEY_ALIAS)) {
                    ks.deleteEntry(KEY_ALIAS)
                }
            }
        } catch (_: Exception) {}
        try {
            context.getSharedPreferences(FALLBACK_PREFS, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
        } catch (_: Exception) {}
        ZeroLogEngine.log("Vault", "Hardware/master wrapping key destroyed.")
    }
}
