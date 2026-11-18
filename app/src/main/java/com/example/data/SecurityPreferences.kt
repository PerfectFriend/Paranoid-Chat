package com.example.data

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

enum class PinCheckResult {
    VALID,
    DURESS_TRIGGERED,
    INVALID
}

class SecurityPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("simplex_secure_prefs", Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()

    companion object {
        private const val KEY_PIN_HASH = "sec_pin_hash"
        private const val KEY_DURESS_PIN_HASH = "sec_duress_pin_hash"
        private const val KEY_LOCKOUT_TIME = "sec_lockout_time"
        private const val KEY_FAILED_ATTEMPTS = "sec_failed_attempts"
        private const val KEY_AUTO_LOCK_BG = "cfg_auto_lock_bg"
        private const val KEY_TOR_VPN_ENABLED = "cfg_tor_vpn"
        private const val KEY_TAILSCALE_ENABLED = "cfg_tailscale"
        private const val KEY_ONION_ADDRESS = "cfg_onion_address"
        private const val KEY_TAILSCALE_IP = "cfg_tailscale_ip"
        private const val KEY_LOCAL_QUEUE_PORT = "cfg_queue_port"
        private const val KEY_MOCK_CONVERSATION_COUNT = "cfg_mock_convo"
        private const val KEY_DC_CLOUD_VAULT_SIZE = "cfg_dc_cloud_vault_size"
        private const val KEY_DC_CLOUD_AUTOCLEAR = "cfg_dc_cloud_autoclear"
        private const val KEY_DC_CLOUD_ENABLED = "cfg_dc_cloud_enabled"

        private const val PBKDF2_PREFIX = "pbkdf2$"
        private const val PBKDF2_ITERATIONS = 120_000
        private const val PBKDF2_KEY_BITS = 256
        private const val SALT_BYTES = 16
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun legacySha256(input: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .toHex()
    }

    private fun derivePbkdf2(pin: String, salt: ByteArray): ByteArray {
        val chars = pin.toCharArray()
        val spec = PBEKeySpec(chars, salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            factory.generateSecret(spec).encoded
        } finally {
            chars.fill('\u0000')
            spec.clearPassword()
        }
    }

    private fun encodePbkdf2Hash(pin: String): String {
        val salt = ByteArray(SALT_BYTES)
        secureRandom.nextBytes(salt)
        val derived = derivePbkdf2(pin, salt)
        return try {
            "$PBKDF2_PREFIX${salt.toHex()}$${derived.toHex()}"
        } finally {
            derived.fill(0)
            salt.fill(0)
        }
    }

    private fun verifyPbkdf2Match(pin: String, savedHash: String?): Boolean {
        if (savedHash.isNullOrEmpty() || !savedHash.startsWith(PBKDF2_PREFIX)) return false
        val parts = savedHash.removePrefix(PBKDF2_PREFIX).split('$')
        if (parts.size != 2) return false
        val salt = hexToBytes(parts[0]) ?: return false
        val expected = hexToBytes(parts[1]) ?: return false
        val candidate = derivePbkdf2(pin, salt)
        return try {
            MessageDigest.isEqual(candidate, expected)
        } finally {
            candidate.fill(0)
            expected.fill(0)
            salt.fill(0)
        }
    }

    fun isPinSet(): Boolean {
        return prefs.getString(KEY_PIN_HASH, null) != null
    }

    fun hasDuressPin(): Boolean {
        return prefs.getString(KEY_DURESS_PIN_HASH, null) != null
    }

    fun setPin(pin: String): Boolean {
        if (pin.length != 6 || !pin.all { it.isDigit() }) return false
        // Reject primary PIN if it collides with an active Duress PIN
        val existingDuress = prefs.getString(KEY_DURESS_PIN_HASH, null)
        if (verifyPbkdf2Match(pin, existingDuress)) return false

        val encoded = encodePbkdf2Hash(pin)
        prefs.edit()
            .putString(KEY_PIN_HASH, encoded)
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .apply()
        return true
    }

    fun setDuressPin(pin: String): Boolean {
        if (pin.length != 6 || !pin.all { it.isDigit() }) return false
        // Duress PIN must require an active primary PIN and must never equal it
        if (!isPinSet() || verifyPin(pin)) return false

        val encoded = encodePbkdf2Hash(pin)
        prefs.edit()
            .putString(KEY_DURESS_PIN_HASH, encoded)
            .apply()
        return true
    }

    fun clearDuressPin() {
        prefs.edit().remove(KEY_DURESS_PIN_HASH).apply()
    }

    fun verifyPin(pin: String): Boolean {
        val savedHash = prefs.getString(KEY_PIN_HASH, null) ?: return false
        val matched = if (savedHash.startsWith(PBKDF2_PREFIX)) {
            verifyPbkdf2Match(pin, savedHash)
        } else {
            // Transparently verify and upgrade legacy unsalted SHA-256 hashes
            val legacyCandidate = legacySha256(pin).toByteArray(Charsets.UTF_8)
            val legacySaved = savedHash.toByteArray(Charsets.UTF_8)
            val ok = MessageDigest.isEqual(legacyCandidate, legacySaved)
            if (ok) {
                setPin(pin)
            }
            ok
        }

        if (matched) {
            prefs.edit().putInt(KEY_FAILED_ATTEMPTS, 0).apply()
        }
        return matched
    }

    fun verifyPinWithDuress(pin: String): PinCheckResult {
        if (verifyPin(pin)) {
            return PinCheckResult.VALID
        }
        val duressHash = prefs.getString(KEY_DURESS_PIN_HASH, null)
        if (verifyPbkdf2Match(pin, duressHash)) {
            return PinCheckResult.DURESS_TRIGGERED
        }
        return PinCheckResult.INVALID
    }

    fun recordFailedPinAttempt(): Long {
        val attempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        // Progressive lockout: 30s base, doubled every extra consecutive failure up to 5 min
        val multiplier = 1L shl (attempts - 1).coerceAtMost(3)
        val durationMs = (30_000L * multiplier).coerceAtMost(300_000L)
        val until = System.currentTimeMillis() + durationMs
        prefs.edit()
            .putInt(KEY_FAILED_ATTEMPTS, attempts)
            .putLong(KEY_LOCKOUT_TIME, until)
            .apply()
        return durationMs
    }

    fun getFailedPinAttempts(): Int = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)

    // Error code lockout handling
    fun setLockoutUntil(timestamp: Long) {
        prefs.edit().putLong(KEY_LOCKOUT_TIME, timestamp).apply()
    }

    fun getLockoutRemainingMs(currentTime: Long = System.currentTimeMillis()): Long {
        val lockoutUntil = prefs.getLong(KEY_LOCKOUT_TIME, 0L)
        val remaining = lockoutUntil - currentTime
        return if (remaining > 0) remaining else 0L
    }

    var isAutoLockOnBackgroundEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_LOCK_BG, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_LOCK_BG, value).apply()

    // Config Toggles
    var isTorVpnEnabled: Boolean
        get() = prefs.getBoolean(KEY_TOR_VPN_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_TOR_VPN_ENABLED, value).apply()

    var isTailscaleEnabled: Boolean
        get() = prefs.getBoolean(KEY_TAILSCALE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_TAILSCALE_ENABLED, value).apply()

    var onionAddress: String
        get() = prefs.getString(KEY_ONION_ADDRESS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ONION_ADDRESS, value).apply()

    var tailscaleIp: String
        get() = prefs.getString(KEY_TAILSCALE_IP, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TAILSCALE_IP, value).apply()

    var localQueuePort: Int
        get() = prefs.getInt(KEY_LOCAL_QUEUE_PORT, 5223)
        set(value) = prefs.edit().putInt(KEY_LOCAL_QUEUE_PORT, value).apply()

    var areMockConversationsInitialized: Boolean
        get() = prefs.getBoolean(KEY_MOCK_CONVERSATION_COUNT, false)
        set(value) = prefs.edit().putBoolean(KEY_MOCK_CONVERSATION_COUNT, value).apply()

    var dcCloudVaultSize: Int
        get() = prefs.getInt(KEY_DC_CLOUD_VAULT_SIZE, 16)
        set(value) = prefs.edit().putInt(KEY_DC_CLOUD_VAULT_SIZE, value).apply()

    var isDcCloudAutoclearEnabled: Boolean
        get() = prefs.getBoolean(KEY_DC_CLOUD_AUTOCLEAR, true)
        set(value) = prefs.edit().putBoolean(KEY_DC_CLOUD_AUTOCLEAR, value).apply()

    var isDcCloudEnabled: Boolean
        get() = prefs.getBoolean(KEY_DC_CLOUD_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_DC_CLOUD_ENABLED, value).apply()

    // Fully reset all configurations on Panic Button press
    fun clearAll() {
        prefs.edit().clear().apply()
    }
}

/**
 * High-Security Zero Persistent Log Engine.
 * Messages do not flow into Logcat. Instead, we write temporary, memory-only session histories
 * for debugging inside the app. They are wiped instantly on app restart or panic call.
 */
object ZeroLogEngine {
    private val memoryLogs = mutableListOf<LogEntry>()

    data class LogEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val tag: String,
        val message: String,
        val level: String = "INFO"
    )

    @Synchronized
    fun log(tag: String, message: String, level: String = "INFO") {
        // We do NOT use fallback android.util.Log to comply with ZERO PERSISTENT LOG.
        // We only append to memory logs (up to 100 lines for visual debug panel inside the app).
        if (memoryLogs.size >= 100) {
            memoryLogs.removeAt(0)
        }
        memoryLogs.add(LogEntry(tag = tag, message = message, level = level))
    }

    @Synchronized
    fun getLogs(): List<LogEntry> {
        return ArrayList(memoryLogs)
    }

    @Synchronized
    fun clearLogs() {
        memoryLogs.clear()
    }
}
