package dev.vaultaire.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Component 11 (milestone M2): the warnings every screen carries (Gate G0 owner override;
 * crypto spec §5.2).
 *
 * - Until the independent security review, every screen says
 *   "Unaudited beta. Do not store real credentials."
 * - A test build (debug, used in the emulator) also says its keys are not hardware-protected.
 */
@RunWith(RobolectricTestRunner::class)
class BannersTest {

    @get:Rule
    val compose = createComposeRule()

    private val beta = "Unaudited beta. Do not store real credentials."
    private val testBuild = "Test build: keys are not hardware-protected"

    @Test
    fun theBetaWarningIsOnTheFirstScreenAndThePasswordScreen() {
        compose.setContent { VaultaireApp(FakeGateway(), testBuild = false) }
        compose.onNodeWithText(beta).assertIsDisplayed()
        compose.onNodeWithTag("create").performClick()
        compose.onNodeWithText(beta).assertIsDisplayed()
    }

    @Test
    fun theBetaWarningIsOnTheLockScreenAndHome() {
        compose.setContent { VaultaireApp(FakeGateway(exists = true), testBuild = false) }
        compose.onNodeWithText(beta).assertIsDisplayed()
        compose.onNodeWithTag("unlockFingerprint").performClick()
        compose.onNodeWithTag("home").assertIsDisplayed()
        compose.onNodeWithText(beta).assertIsDisplayed()
    }

    @Test
    fun aTestBuildSaysSo() {
        compose.setContent { VaultaireApp(FakeGateway(exists = true), testBuild = true) }
        compose.onNodeWithText(testBuild).assertIsDisplayed()
    }

    @Test
    fun aRealBuildDoesNotShowTheTestBuildWarning() {
        compose.setContent { VaultaireApp(FakeGateway(exists = true), testBuild = false) }
        compose.onNodeWithText(testBuild).assertDoesNotExist()
    }
}
