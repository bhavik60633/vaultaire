package dev.vaultaire.spike

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import kotlinx.coroutines.*

/**
 * Production ClipboardGuard (Threat model R1, R2, ADR-005):
 * 1. Sets ClipDescription.EXTRA_IS_SENSITIVE to block keyboards from logging clipboard text.
 * 2. Starts an automatic wipe timer (30s) that overwrites sensitive clip data before clearing.
 */
object ClipboardGuard {

    private var wipeJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun copySensitive(
        context: Context,
        text: String,
        label: String,
        wipeTimeoutMs: Long = 30_000L,
        onWiped: (() -> Unit)? = null
    ) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)

        // Android 13+ (API 33): EXTRA_IS_SENSITIVE instructs keyboards not to show or persist clipboard previews
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
            clip.description.extras = extras
        }

        clipboard.setPrimaryClip(clip)

        // Cancel any pending wipe and schedule a fresh one
        wipeJob?.cancel()
        wipeJob = scope.launch {
            delay(wipeTimeoutMs)
            wipeClipboard(clipboard)
            onWiped?.invoke()
        }
    }

    private fun wipeClipboard(clipboard: ClipboardManager) {
        try {
            // R1: overwrite first with blank sensitive clip to prevent OEM recovery, then clear
            val emptyClip = ClipData.newPlainText("", "")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                emptyClip.description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            clipboard.setPrimaryClip(emptyClip)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            }
        } catch (_: Exception) {
            // Safe fallback
        }
    }
}
