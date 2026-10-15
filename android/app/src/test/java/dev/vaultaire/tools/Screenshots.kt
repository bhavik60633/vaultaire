package dev.vaultaire.tools

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.vaultaire.ui.FakeGateway
import dev.vaultaire.ui.VaultaireApp
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Not a test: renders each screen to a PNG so the owner can compare it with the design mockup
 * before installing anything. Runs only with `./gradlew :app:testDebugUnitTest -Pscreenshots`;
 * the images land in `app/build/screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Screenshots {

    @get:Rule
    val compose = createComposeRule()

    private val out: String? = System.getProperty("vaultaire.screenshots")

    @Before
    fun onlyWhenAsked() {
        assumeTrue(out != null)
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out!!).mkdirs()
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun click(tag: String) = compose.onNodeWithTag(tag).performClick()

    @Test
    fun onboarding() {
        val gateway = FakeGateway()
        compose.setContent { VaultaireApp(gateway, testBuild = false) }
        save("1-welcome")
        click("create")
        save("2-password-empty")
        compose.onNodeWithTag("password").performTextInput("password1234")
        compose.onNodeWithTag("confirm").performTextInput("password1234")
        gateway.weakPassword = true
        click("continue")
        save("3-password-weak")
        gateway.weakPassword = false
        click("continue")
        save("4-recovery-key")
        click("writtenDown")
        click("continue")
        save("5-fingerprint")
    }

    @Test
    fun lockAndHome() {
        val gateway = FakeGateway(exists = true)
        gateway.failures = 3
        compose.setContent { VaultaireApp(gateway, testBuild = false) }
        save("6-lock")
        click("usePassword")
        save("7-password")
        compose.onNodeWithTag("password").performTextInput("a completely different passphrase 7")
        click("unlock")
        save("8-password-wrong")
        compose.onNodeWithTag("password").performTextInput("x")
        click("back")
        click("unlockFingerprint")
        save("9-home")
    }

    @Test
    fun testBuildAndRefusal() {
        val gateway = FakeGateway(refusalValue = dev.vaultaire.security.Refusal.NO_SCREEN_LOCK)
        compose.setContent { VaultaireApp(gateway, testBuild = true) }
        save("10-refusal-test-build")
    }
}
