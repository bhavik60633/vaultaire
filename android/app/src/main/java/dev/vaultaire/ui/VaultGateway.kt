package dev.vaultaire.ui

import dev.vaultaire.security.Refusal
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the screens need from the vault. The app implements it with the Rust engine,
 * Android Keystore and the fingerprint prompt ([AppGateway]); tests use a stand-in.
 */
interface VaultGateway {
    /** True while a session is open. Turns false when the vault locks, for whatever reason. */
    val unlocked: StateFlow<Boolean>

    fun vaultExists(): Boolean

    /** Why this phone cannot have a vault, or null (crypto spec §5.2). */
    fun refusal(): Refusal?

    /** Creates the vault and opens it; returns the Recovery Key to show once. */
    suspend fun create(password: CharArray): CharArray

    /** Offers the fingerprint prompt and adds the fingerprint slot. False if it did not happen. */
    suspend fun enrolFingerprint(): Boolean

    suspend fun unlockWithFingerprint(): Boolean

    /** Throws [dev.vaultaire.core.VaultException] (`unlock_failed`) on any failure. */
    suspend fun unlockWithPassword(password: CharArray)

    /** Seconds left before another password may be tried. */
    fun remainingDelayS(): Long

    /** Wrong passwords between the last two unlocks, to tell the user once. */
    fun failuresSinceLastUnlock(): Int

    fun lock()

    /** Onboarding is over: anything kept for it (the new password, for the fingerprint step) goes. */
    fun onboardingFinished() {}
}
