package dev.vaultaire.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Component 9 (milestone M2): the app's one plaintext preferences file (vault-format §8;
 * threat-model I1, T08, T37).
 *
 * It may hold only eight harmless keys. Nothing about the user's cards, banks or settings is
 * ever written here: that all lives inside the encrypted vault.
 */
@RunWith(RobolectricTestRunner::class)
class PrefsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val documented = setOf(
        "vault_present",
        "onboarding_step",
        "theme",
        "haptics",
        "failed_attempts",
        "delay_started_elapsed_ms",
        "delay_started_boot_count",
        "failures_since_unlock",
    )

    @Test
    fun theAllowListIsExactlyTheDocumentedKeys() {
        assertEquals(documented, Prefs.ALLOWED_KEYS)
    }

    @Test
    fun aFreshInstallHasNoVaultAndNoFailures() {
        val prefs = Prefs(context)
        assertFalse(prefs.vaultPresent)
        assertEquals(0, prefs.failedAttempts)
        assertEquals(0, prefs.failuresSinceUnlock)
        assertEquals("system", prefs.theme)
    }

    @Test
    fun everyValueIsKeptAndNothingElseIsWritten() {
        val prefs = Prefs(context)
        prefs.vaultPresent = true
        prefs.onboardingStep = 3
        prefs.theme = "dark"
        prefs.haptics = false
        prefs.failedAttempts = 6
        prefs.delayStartedElapsedMs = 123_456L
        prefs.delayStartedBootCount = 9
        prefs.failuresSinceUnlock = 2

        val again = Prefs(context)
        assertTrue(again.vaultPresent)
        assertEquals(3, again.onboardingStep)
        assertEquals("dark", again.theme)
        assertFalse(again.haptics)
        assertEquals(6, again.failedAttempts)
        assertEquals(123_456L, again.delayStartedElapsedMs)
        assertEquals(9, again.delayStartedBootCount)
        assertEquals(2, again.failuresSinceUnlock)

        val written = context.getSharedPreferences(Prefs.FILE_NAME, Context.MODE_PRIVATE).all.keys
        assertTrue("only allowed keys: $written", documented.containsAll(written))
    }

    @Test
    fun onlyTheThreeThemesAreAccepted() {
        val prefs = Prefs(context)
        for (theme in listOf("system", "light", "dark")) {
            prefs.theme = theme
            assertEquals(theme, prefs.theme)
        }
        assertThrows(IllegalArgumentException::class.java) { prefs.theme = "SBI blue" }
    }
}
