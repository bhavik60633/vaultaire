package dev.vaultaire.security

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/**
 * The only code that writes to the clipboard (Semgrep rule `clipboard-outside-guard`;
 * vault-format §5.5; threat-model T11).
 *
 * Every clip is marked sensitive. Critical values are cleared after a fixed 15 s, everything else
 * after [normalTtlS] (the vault setting). A clip is cleared only if it is still ours, so whatever
 * the user copied afterwards is left alone. Clearing first writes an empty sensitive clip, because
 * on some phones `clearPrimaryClip()` alone does nothing (threat-model R1).
 */
class ClipboardGuard(context: Context) {

    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var lastId = 0L

    /** The vault setting `clipboard_ttl_s`: 30, 60 or 120. */
    var normalTtlS: Long = 30

    /** Copies [value]; returns the seconds until it is cleared. Call on the main thread. */
    fun copy(value: CharSequence, sensitivity: Sensitivity): Long {
        val ttlS = when (sensitivity) {
            Sensitivity.CRITICAL, Sensitivity.CRITICAL_STRICT -> CRITICAL_TTL_S
            Sensitivity.NORMAL, Sensitivity.CONCEALED -> normalTtlS
        }
        val id = ++lastId
        clipboard.setPrimaryClip(sensitiveClip(value, id))
        handler.postDelayed({ clearIfOurs(id) }, ttlS * 1000)
        return ttlS
    }

    private fun clearIfOurs(id: Long) {
        val extras = clipboard.primaryClipDescription?.extras ?: return
        if (extras.getLong(EXTRA_CLIP_ID, -1L) != id) return
        clipboard.setPrimaryClip(sensitiveClip("", 0L))
        clipboard.clearPrimaryClip()
    }

    private fun sensitiveClip(value: CharSequence, id: Long): ClipData =
        ClipData.newPlainText(LABEL, value).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(EXTRA_IS_SENSITIVE, true)
                putLong(EXTRA_CLIP_ID, id)
            }
        }

    companion object {
        /** Card numbers, CVVs, PINs, account numbers and banking passwords (vault-format §5.5). */
        const val CRITICAL_TTL_S = 15L

        /** `ClipDescription.EXTRA_IS_SENSITIVE` (API 33), spelled out so API 31–32 get it too. */
        private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
        private const val EXTRA_CLIP_ID = "dev.vaultaire.clip_id"
        private const val LABEL = "Vaultaire"

        /** The message shown after copying. It never contains the value (threat-model I12). */
        fun confirmation(seconds: Long): String = "Copied, clears in $seconds s"
    }
}
