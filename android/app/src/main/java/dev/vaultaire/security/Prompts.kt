package dev.vaultaire.security

import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt

/**
 * The fingerprint prompts (crypto spec §4.2; threat-model I7, T16). Strong fingerprints only;
 * each is shown with a CryptoObject, so success releases a Keystore key.
 */
object Prompts {

    fun unlock(): BiometricPrompt.PromptInfo = strong("Unlock Vaultaire", "Use password")

    fun reveal(): BiometricPrompt.PromptInfo = strong("Confirm it's you", "Cancel")

    fun enrol(): BiometricPrompt.PromptInfo = strong("Use fingerprint to unlock", "Not now")

    /** The phone's screen lock, for the password key `K_pwd` (crypto spec §4.3). */
    fun deviceCredential(): BiometricPrompt.PromptInfo =
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Confirm your screen lock")
            .setSubtitle("Needed once to check the vault password on this phone")
            .setAllowedAuthenticators(Authenticators.DEVICE_CREDENTIAL)
            .build()

    private fun strong(title: String, negative: String): BiometricPrompt.PromptInfo =
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setNegativeButtonText(negative)
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()
}
