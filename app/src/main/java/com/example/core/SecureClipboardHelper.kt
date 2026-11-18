package com.example.core

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import com.example.data.ZeroLogEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Clipboard safety wrapper for sensitive handshake strings (.onion links, invite tokens):
 * 1. Marks ClipData with Android 13+ EXTRA_IS_SENSITIVE so OS clipboard previews stay hidden.
 * 2. Automatically purges the primary clip after [autoClearDelayMs] (default 30s).
 * 3. Provides immediate [wipeNow] invoked on manual lock, background lock, and panic purge.
 */
object SecureClipboardHelper {

    private const val EXTRA_IS_SENSITIVE_COMPAT = "android.content.extra.IS_SENSITIVE"
    const val DEFAULT_AUTO_CLEAR_MS = 30_000L

    private var clearTimerJob: Job? = null

    @Synchronized
    fun copySensitive(
        context: Context,
        scope: CoroutineScope,
        label: String,
        secretText: String,
        autoClearDelayMs: Long = DEFAULT_AUTO_CLEAR_MS,
        onAutoCleared: (() -> Unit)? = null
    ) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return

        val clip = ClipData.newPlainText(label, secretText).apply {
            val extras = PersistableBundle().apply {
                val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ClipDescription.EXTRA_IS_SENSITIVE
                } else {
                    EXTRA_IS_SENSITIVE_COMPAT
                }
                putBoolean(key, true)
            }
            description.extras = extras
        }

        clipboard.setPrimaryClip(clip)
        ZeroLogEngine.log("Security", "Copied '$label' as SENSITIVE clip (${autoClearDelayMs / 1000}s auto-wipe).")

        clearTimerJob?.cancel()
        clearTimerJob = scope.launch {
            delay(autoClearDelayMs)
            wipeNow(context, silent = false)
            onAutoCleared?.invoke()
        }
    }

    @Synchronized
    fun wipeNow(context: Context, silent: Boolean = true) {
        clearTimerJob?.cancel()
        clearTimerJob = null
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
            if (!silent) {
                ZeroLogEngine.log("Security", "Sensitive clipboard auto-wiped.")
            }
        } catch (_: Exception) {
            // Some OEMs throw if backgrounded while wiping; ignore gracefully
        }
    }
}
