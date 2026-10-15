package dev.vaultaire.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.vaultaire.security.Refusal
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Component 11 (milestone M2): creating a vault for the first time (product plan §1; crypto spec
 * §3.2, §5.2, §8; threat-model T27).
 *
 * 1. A phone without a screen lock, or with only software keys in a real build, is told why it
 *    cannot have a vault, and no Create button is offered.
 * 2. The user types the vault password twice. A mismatch or a weak password is explained.
 * 3. The Recovery Key is shown once. The user must confirm they wrote it down before going on.
 * 4. Fingerprint unlock is offered, and can be skipped.
 */
@RunWith(RobolectricTestRunner::class)
class OnboardingTest {

    @get:Rule
    val compose = createComposeRule()

    private val gateway = FakeGateway()

    private fun start() {
        compose.setContent { VaultaireApp(gateway, testBuild = false) }
    }

    private fun typePasswords(first: String, second: String) {
        compose.onNodeWithTag("create").performClick()
        compose.onNodeWithTag("password").performTextInput(first)
        compose.onNodeWithTag("confirm").performTextInput(second)
        compose.onNodeWithTag("continue").performClick()
    }

    private fun createVault() {
        typePasswords(gateway.rightPassword, gateway.rightPassword)
        compose.onNodeWithTag("writtenDown").performClick()
        compose.onNodeWithTag("continue").performClick()
    }

    @Test
    fun aFirstLaunchOffersToCreateAVault() {
        start()
        compose.onNodeWithTag("create").assertIsDisplayed()
    }

    @Test
    fun aPhoneWithoutAScreenLockIsToldWhyAndCannotCreate() {
        gateway.refusalValue = Refusal.NO_SCREEN_LOCK
        start()
        compose.onNodeWithTag("refusal").assertTextContains("screen lock", substring = true)
        compose.onNodeWithTag("create").assertDoesNotExist()
    }

    @Test
    fun softwareKeysInARealBuildAreRefusedWithAReason() {
        gateway.refusalValue = Refusal.SOFTWARE_KEYS
        start()
        compose.onNodeWithTag("refusal").assertTextContains("hardware", substring = true)
        compose.onNodeWithTag("create").assertDoesNotExist()
    }

    @Test
    fun continueWaitsForBothPasswords() {
        start()
        compose.onNodeWithTag("create").performClick()
        compose.onNodeWithTag("continue").assertIsNotEnabled()
        compose.onNodeWithTag("password").performTextInput(gateway.rightPassword)
        compose.onNodeWithTag("continue").assertIsNotEnabled()
        compose.onNodeWithTag("confirm").performTextInput(gateway.rightPassword)
        compose.onNodeWithTag("continue").assertIsEnabled()
    }

    @Test
    fun passwordsThatDoNotMatchAreCaughtBeforeAnythingIsCreated() {
        start()
        typePasswords(gateway.rightPassword, "correct horse battery staple 43")
        compose.onNodeWithTag("error").assertTextContains("don't match", substring = true)
        assertEquals(0, gateway.creates)
    }

    @Test
    fun aWeakPasswordIsExplained() {
        gateway.weakPassword = true
        start()
        typePasswords("password1234", "password1234")
        compose.onNodeWithTag("error").assertTextContains("at least 12 characters", substring = true)
        compose.onNodeWithTag("recoveryKey").assertDoesNotExist()
    }

    @Test
    fun theRecoveryKeyIsShownAndMustBeWrittenDown() {
        start()
        typePasswords(gateway.rightPassword, gateway.rightPassword)
        compose.onNodeWithTag("recoveryKey").assertTextContains(gateway.recoveryKey)
        compose.onNodeWithTag("continue").assertIsNotEnabled()
        compose.onNodeWithTag("writtenDown").performClick()
        compose.onNodeWithTag("continue").assertIsEnabled()
    }

    @Test
    fun fingerprintUnlockCanBeSkipped() {
        start()
        createVault()
        compose.onNodeWithTag("useFingerprint").assertIsDisplayed()
        compose.onNodeWithTag("notNow").performClick()
        compose.onNodeWithTag("home").assertIsDisplayed()
        assertEquals(0, gateway.enrolments)
    }

    @Test
    fun fingerprintUnlockCanBeTurnedOn() {
        start()
        createVault()
        compose.onNodeWithTag("useFingerprint").performClick()
        compose.onNodeWithTag("home").assertIsDisplayed()
        assertEquals(1, gateway.enrolments)
    }

    @Test
    fun theWarningAboutOtherFingerprintsIsShown() {
        start()
        createVault()
        compose.onNodeWithText("Anyone whose fingerprint is on this phone", substring = true)
            .assertIsDisplayed()
    }
}
