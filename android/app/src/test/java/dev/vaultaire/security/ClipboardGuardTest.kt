package dev.vaultaire.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * Component 9 (milestone M2): copying to the clipboard (vault-format §5.5; threat-model T11, I12).
 *
 * - Every copy is marked sensitive, so the keyboard and the clipboard preview hide it.
 * - Card numbers, CVVs, PINs, account numbers and banking passwords are cleared after a fixed
 *   15 seconds. Everything else after the vault setting: 30 seconds unless changed to 60 or 120.
 * - Clearing never wipes something the user copied afterwards from another app.
 * - The "Copied" message never contains the value.
 *
 * Only ClipboardGuard may write to the clipboard (Semgrep rule `clipboard-outside-guard`).
 */
@RunWith(RobolectricTestRunner::class)
class ClipboardGuardTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val guard = ClipboardGuard(context)

    private fun clipText(): String? = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

    private fun waitS(seconds: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
    }

    private fun cleared(): Boolean = clipText().isNullOrEmpty()

    @Test
    fun aCopyIsMarkedSensitiveAndItsLabelHidesTheValue() {
        guard.copy("4111111111111234", Sensitivity.CRITICAL)
        assertEquals("4111111111111234", clipText())
        val description = clipboard.primaryClip!!.description
        assertTrue(description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
        assertFalse(description.label.toString().contains("4111"))
    }

    @Test
    fun criticalValuesAreClearedAfterFifteenSeconds() {
        assertEquals(15L, guard.copy("4111111111111234", Sensitivity.CRITICAL))
        waitS(14)
        assertEquals("4111111111111234", clipText())
        waitS(1)
        assertTrue(cleared())
    }

    @Test
    fun pinsAreClearedAfterFifteenSeconds() {
        assertEquals(15L, guard.copy("4321", Sensitivity.CRITICAL_STRICT))
        waitS(15)
        assertTrue(cleared())
    }

    @Test
    fun otherValuesAreClearedAfterThirtySecondsByDefault() {
        assertEquals(30L, guard.copy("ASHA RAO", Sensitivity.NORMAL))
        waitS(29)
        assertEquals("ASHA RAO", clipText())
        waitS(1)
        assertTrue(cleared())
    }

    @Test
    fun concealedValuesFollowTheSetting() {
        guard.normalTtlS = 60
        assertEquals(60L, guard.copy("netbanking-user-17", Sensitivity.CONCEALED))
        waitS(59)
        assertEquals("netbanking-user-17", clipText())
        waitS(1)
        assertTrue(cleared())
    }

    @Test
    fun theSettingNeverLengthensCriticalValues() {
        guard.normalTtlS = 120
        assertEquals(15L, guard.copy("4111111111111234", Sensitivity.CRITICAL))
        waitS(15)
        assertTrue(cleared())
    }

    @Test
    fun somethingTheUserCopiedLaterIsLeftAlone() {
        guard.copy("4111111111111234", Sensitivity.CRITICAL)
        waitS(5)
        clipboard.setPrimaryClip(ClipData.newPlainText("note", "hello from another app"))
        waitS(20)
        assertEquals("hello from another app", clipText())
    }

    @Test
    fun aNewerCopyGetsItsOwnTime() {
        guard.copy("4111111111111234", Sensitivity.CRITICAL)
        waitS(10)
        guard.copy("ASHA RAO", Sensitivity.NORMAL)
        waitS(10)
        assertEquals("the first timer does not clear the newer copy", "ASHA RAO", clipText())
        waitS(20)
        assertTrue(cleared())
    }

    @Test
    fun theConfirmationNeverShowsTheValue() {
        val seconds = guard.copy("4111111111111234", Sensitivity.CRITICAL)
        val message = ClipboardGuard.confirmation(seconds)
        assertEquals("Copied, clears in 15 s", message)
        assertFalse(message.contains("4111"))
    }
}
