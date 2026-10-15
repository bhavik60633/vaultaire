package dev.vaultaire.ui

import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy

/** Every dialog and sheet blocks screenshots too (threat-model T12). */
object SecureUi {
    fun dialogProperties(): DialogProperties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)
}
