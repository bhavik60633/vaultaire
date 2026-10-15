package dev.vaultaire.ui

import android.view.View
import android.view.WindowManager
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.test.core.app.ActivityScenario
import dev.vaultaire.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Component 11 (milestone M2): nothing on Vaultaire's screens can be captured
 * (threat-model I9, T12, T30, T31).
 *
 * - The app's window blocks screenshots, screen recording, casting and recents thumbnails
 *   (FLAG_SECURE).
 * - Every dialog and sheet blocks them too.
 * - Other apps' autofill services are kept out of every screen.
 */
@RunWith(RobolectricTestRunner::class)
class SecureWindowTest {

    @Test
    fun theAppWindowBlocksScreenshots() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val flags = activity.window.attributes.flags
                assertTrue(flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
        }
    }

    @Test
    fun otherAutofillServicesAreKeptOut() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(
                    View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
                    activity.window.decorView.importantForAutofill,
                )
            }
        }
    }

    @Test
    fun dialogsBlockScreenshotsToo() {
        assertEquals(SecureFlagPolicy.SecureOn, SecureUi.dialogProperties().securePolicy)
    }
}
