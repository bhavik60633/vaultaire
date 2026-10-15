package dev.vaultaire.security

import androidx.biometric.BiometricManager.Authenticators
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Component 9 (milestone M2): the fingerprint prompts (crypto spec §4.2; threat-model I7, T16).
 *
 * Every prompt accepts only a strong (Class 3) fingerprint, never the phone's PIN, pattern or a
 * weak face unlock, and always offers a way out. The prompt is always shown with a CryptoObject
 * (Semgrep rule `biometric-prompt-without-cryptoobject`), so success releases a key rather than
 * answering yes or no.
 */
@RunWith(RobolectricTestRunner::class)
class PromptsTest {

    private val all = listOf(Prompts.unlock(), Prompts.reveal(), Prompts.enrol())

    @Test
    fun everyPromptAcceptsOnlyAStrongFingerprint() {
        for (prompt in all) {
            assertEquals(Authenticators.BIOMETRIC_STRONG, prompt.allowedAuthenticators)
            assertFalse(prompt.isDeviceCredentialAllowed)
        }
    }

    @Test
    fun everyPromptHasATitleAndAWayOut() {
        for (prompt in all) {
            assertTrue(prompt.title.isNotBlank())
            assertTrue(prompt.negativeButtonText.isNotBlank())
        }
        assertEquals("Use password", Prompts.unlock().negativeButtonText.toString())
    }
}
