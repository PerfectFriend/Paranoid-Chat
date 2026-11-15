package com.example.data

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

class SecurityPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("simplex_secure_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PIN_HASH = "sec_pin_hash"
        private const val KEY_LOCKOUT_TIME = "sec_lockout_time"
        private const val KEY_TOR_VPN_ENABLED = "cfg_tor_vpn"
        private const val KEY_TAILSCALE_ENABLED = "cfg_tailscale"
        private const val KEY_ONION_ADDRESS = "cfg_onion_address"
        private const val KEY_TAILSCALE_IP = "cfg_tailscale_ip"
        private const val KEY_LOCAL_QUEUE_PORT = "cfg_queue_port"
        private const val KEY_MOCK_CONVERSATION_COUNT = "cfg_mock_convo"
        private const val KEY_DC_CLOUD_VAULT_SIZE = "cfg_dc_cloud_vault_size"
        private const val KEY_DC_CLOUD_AUTOCLEAR = "cfg_dc_cloud_autoclear"
        private const val KEY_DC_CLOUD_ENABLED = "cfg_dc_cloud_enabled"
    }

    // Hash the 6-digit PIN securely using SHA-256
    private fun sha256(input: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .fold("") { str, it -> str + "%02x".format(it) }
    }

    fun isPinSet(): Boolean {
        return prefs.getString(KEY_PIN_HASH, null) != null
    }

    fun setPin(pin: String): Boolean {
        if (pin.length != 6 || !pin.all { it.isDigit() }) return false
        val hashed = sha256(pin)
        prefs.edit().putString(KEY_PIN_HASH, hashed).apply()
        return true
    }

    fun verifyPin(pin: String): Boolean {
        val savedHash = prefs.getString(KEY_PIN_HASH, null) ?: return false
        return sha256(pin) == savedHash
    }

    // Error code 30-sec lockout handling
    fun setLockoutUntil(timestamp: Long) {
        prefs.edit().putLong(KEY_LOCKOUT_TIME, timestamp).apply()
    }

    fun getLockoutRemainingMs(currentTime: Long = System.currentTimeMillis()): Long {
        val lockoutUntil = prefs.getLong(KEY_LOCKOUT_TIME, 0L)
        val remaining = lockoutUntil - currentTime
        return if (remaining > 0) remaining else 0L
    }

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
