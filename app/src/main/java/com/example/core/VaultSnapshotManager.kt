package com.example.core

import android.content.Context
import android.net.Uri
import com.example.data.Contact
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts and restores portable `.pvault` sovereign node snapshots over Android SAF
 * without ever writing unencrypted backups to storage.
 *
 * Wire format:
 *   [0..3]   Magic bytes "PVLT"
 *   [4]      Format version (0x01)
 *   [5..20]  16-byte random PBKDF2/HKDF salt
 *   [21..32] 12-byte random AES-GCM IV
 *   [33..N]  AES-256-GCM ciphertext + 128-bit auth tag (AAD = "paranoid-vault-snapshot-v1")
 */
object VaultSnapshotManager {

    private val MAGIC = byteArrayOf(0x50, 0x56, 0x4C, 0x54) // "PVLT"
    private const val FORMAT_VERSION: Byte = 0x01
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val HEADER_BYTES = 4 + 1 + SALT_BYTES + IV_BYTES
    private const val GCM_TAG_BITS = 128
    private const val PBKDF2_ITERATIONS = 120_000
    private const val MIN_PASSPHRASE_LENGTH = 8
    private const val MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024

    private val HKDF_INFO = "paranoid-snapshot-enc-v1".toByteArray(Charsets.UTF_8)
    private val GCM_AAD = "paranoid-vault-snapshot-v1".toByteArray(Charsets.UTF_8)

    data class SnapshotConfig(
        val torVpnEnabled: Boolean,
        val tailscaleEnabled: Boolean,
        val dcCloudEnabled: Boolean,
        val dcVaultSizeGb: Int,
        val dcAutoclearEnabled: Boolean,
        val onionAddress: String,
        val tailscaleIp: String
    )

    data class VaultSnapshot(
        val createdAtMs: Long = System.currentTimeMillis(),
        val config: SnapshotConfig,
        val contacts: List<Contact>
    )

    class SnapshotException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private fun deriveSnapshotAesKey(passphraseChars: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passphraseChars, salt, PBKDF2_ITERATIONS, 256)
        val masterIkm = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return try {
            HkdfSha256.deriveKey(
                ikm = masterIkm,
                salt = salt,
                info = HKDF_INFO,
                outputLength = 32
            )
        } finally {
            masterIkm.fill(0)
        }
    }

    internal fun serializeSnapshotJson(snapshot: VaultSnapshot): ByteArray {
        val root = JSONObject().apply {
            put("formatVersion", 1)
            put("createdAtMs", snapshot.createdAtMs)
            put("config", JSONObject().apply {
                put("torVpnEnabled", snapshot.config.torVpnEnabled)
                put("tailscaleEnabled", snapshot.config.tailscaleEnabled)
                put("dcCloudEnabled", snapshot.config.dcCloudEnabled)
                put("dcVaultSizeGb", snapshot.config.dcVaultSizeGb)
                put("dcAutoclearEnabled", snapshot.config.dcAutoclearEnabled)
                put("onionAddress", snapshot.config.onionAddress)
                put("tailscaleIp", snapshot.config.tailscaleIp)
            })
            val contactsArray = JSONArray()
            for (c in snapshot.contacts) {
                contactsArray.put(JSONObject().apply {
                    put("id", c.id)
                    put("displayName", c.displayName)
                    put("onionAddress", c.onionAddress)
                    put("tailscaleIp", c.tailscaleIp ?: JSONObject.NULL)
                    put("isBlocked", c.isBlocked)
                    put("publicKey", c.publicKey)
                    put("lastSeenTimestamp", c.lastSeenTimestamp)
                })
            }
            put("contacts", contactsArray)
        }
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    internal fun deserializeSnapshotJson(bytes: ByteArray): VaultSnapshot {
        val root = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw SnapshotException("Corrupted snapshot payload.", e)
        }
        val version = root.optInt("formatVersion", -1)
        if (version != 1) {
            throw SnapshotException("Unsupported snapshot schema version: $version")
        }
        val cfgObj = root.optJSONObject("config")
            ?: throw SnapshotException("Missing node configuration block.")
        val config = SnapshotConfig(
            torVpnEnabled = cfgObj.optBoolean("torVpnEnabled", true),
            tailscaleEnabled = cfgObj.optBoolean("tailscaleEnabled", true),
            dcCloudEnabled = cfgObj.optBoolean("dcCloudEnabled", true),
            dcVaultSizeGb = cfgObj.optInt("dcVaultSizeGb", 16).coerceAtLeast(1),
            dcAutoclearEnabled = cfgObj.optBoolean("dcAutoclearEnabled", true),
            onionAddress = cfgObj.optString("onionAddress", ""),
            tailscaleIp = cfgObj.optString("tailscaleIp", "")
        )

        val contactsJson = root.optJSONArray("contacts") ?: JSONArray()
        val contacts = ArrayList<Contact>(contactsJson.length())
        for (i in 0 until contactsJson.length()) {
            val item = contactsJson.optJSONObject(i) ?: continue
            val id = item.optString("id", "").trim()
            val name = item.optString("displayName", "").trim()
            val onion = item.optString("onionAddress", "").trim()
            val pubKey = item.optString("publicKey", "").trim()
            if (id.isEmpty() || name.isEmpty() || onion.isEmpty()) continue
            val tsIp = if (item.isNull("tailscaleIp")) null else item.optString("tailscaleIp").takeIf { it.isNotBlank() }
            contacts.add(
                Contact(
                    id = id,
                    displayName = name,
                    onionAddress = onion,
                    tailscaleIp = tsIp,
                    isBlocked = item.optBoolean("isBlocked", false),
                    publicKey = pubKey.ifEmpty { "KeyEd25519-restored" },
                    lastSeenTimestamp = item.optLong("lastSeenTimestamp", System.currentTimeMillis())
                )
            )
        }

        return VaultSnapshot(
            createdAtMs = root.optLong("createdAtMs", System.currentTimeMillis()),
            config = config,
            contacts = contacts
        )
    }

    fun encryptSnapshot(snapshot: VaultSnapshot, passphrase: String): ByteArray {
        if (passphrase.length < MIN_PASSPHRASE_LENGTH) {
            throw SnapshotException("Snapshot passphrase must be at least $MIN_PASSPHRASE_LENGTH characters.")
        }

        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val passChars = passphrase.toCharArray()
        var aesKey = ByteArray(0)
        var plaintext = ByteArray(0)

        return try {
            aesKey = deriveSnapshotAesKey(passChars, salt)
            plaintext = serializeSnapshotJson(snapshot)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(aesKey, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            cipher.updateAAD(GCM_AAD)
            val ciphertext = cipher.doFinal(plaintext)

            ByteArray(HEADER_BYTES + ciphertext.size).apply {
                System.arraycopy(MAGIC, 0, this, 0, MAGIC.size)
                this[4] = FORMAT_VERSION
                System.arraycopy(salt, 0, this, 5, SALT_BYTES)
                System.arraycopy(iv, 0, this, 5 + SALT_BYTES, IV_BYTES)
                System.arraycopy(ciphertext, 0, this, HEADER_BYTES, ciphertext.size)
            }
        } catch (e: SnapshotException) {
            throw e
        } catch (e: Exception) {
            throw SnapshotException("Snapshot encryption failed: ${e.message}", e)
        } finally {
            passChars.fill('\u0000')
            aesKey.fill(0)
            plaintext.fill(0)
            salt.fill(0)
            iv.fill(0)
        }
    }

    fun decryptSnapshot(envelope: ByteArray, passphrase: String): VaultSnapshot {
        if (passphrase.isEmpty()) {
            throw SnapshotException("Passphrase cannot be empty.")
        }
        if (envelope.size <= HEADER_BYTES + 16) {
            throw SnapshotException("Snapshot file is truncated or invalid.")
        }
        for (i in MAGIC.indices) {
            if (envelope[i] != MAGIC[i]) {
                throw SnapshotException("Unrecognized snapshot header (expected PVLT).")
            }
        }
        if (envelope[4] != FORMAT_VERSION) {
            throw SnapshotException("Unsupported binary snapshot version: ${envelope[4].toInt()}")
        }

        val salt = envelope.copyOfRange(5, 5 + SALT_BYTES)
        val iv = envelope.copyOfRange(5 + SALT_BYTES, HEADER_BYTES)
        val ciphertext = envelope.copyOfRange(HEADER_BYTES, envelope.size)
        val passChars = passphrase.toCharArray()
        var aesKey = ByteArray(0)
        var plaintext = ByteArray(0)

        return try {
            aesKey = deriveSnapshotAesKey(passChars, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(aesKey, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            cipher.updateAAD(GCM_AAD)
            plaintext = cipher.doFinal(ciphertext)
            deserializeSnapshotJson(plaintext)
        } catch (e: SnapshotException) {
            throw e
        } catch (e: Exception) {
            throw SnapshotException("Decryption rejected: wrong passphrase or tampered snapshot.", e)
        } finally {
            passChars.fill('\u0000')
            aesKey.fill(0)
            plaintext.fill(0)
            salt.fill(0)
            iv.fill(0)
        }
    }

    fun exportToUri(
        context: Context,
        uri: Uri,
        snapshot: VaultSnapshot,
        passphrase: String
    ): Int {
        val encrypted = encryptSnapshot(snapshot, passphrase)
        val out = context.contentResolver.openOutputStream(uri)
            ?: throw SnapshotException("Unable to open destination SAF stream.")
        out.use {
            it.write(encrypted)
            it.flush()
        }
        return encrypted.size
    }

    fun importFromUri(
        context: Context,
        uri: Uri,
        passphrase: String
    ): VaultSnapshot {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw SnapshotException("Unable to open source SAF stream.")
        val bytes = input.use { stream ->
            val buffer = ByteArray(8192)
            val out = ByteArrayOutputStream()
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                total += read
                if (total > MAX_SNAPSHOT_BYTES) {
                    throw SnapshotException("Snapshot exceeds maximum permitted size (8 MB).")
                }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
        return decryptSnapshot(bytes, passphrase)
    }
}
