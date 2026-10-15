package dev.vaultaire.ui

import dev.vaultaire.core.VaultSession
import dev.vaultaire.security.ClipboardGuard
import dev.vaultaire.security.RevealGate

/**
 * What the M3 screens need besides [VaultGateway]: the open session, the fingerprint gate for
 * reveals and copies, and the clipboard guard. The app's [AppGateway] provides both.
 */
interface VaultData {
    /** The open session; throws `locked` when the vault is locked. */
    fun session(): VaultSession

    val revealGate: RevealGate
    val clipboard: ClipboardGuard

    /** A fresh strong fingerprint for a reveal or copy; on success the gate is told. */
    suspend fun confirmFingerprint(): Boolean

    /** Seconds since the epoch, for created and used times. */
    fun nowS(): Long

    /** The security settings changed: auto-lock and clipboard times follow. */
    fun settingsChanged() {}
}
