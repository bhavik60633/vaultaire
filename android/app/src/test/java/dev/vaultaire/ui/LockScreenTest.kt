package dev.vaultaire.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Component 11 (milestone M2): opening and locking the vault (crypto spec §10, §11;
 * threat-model T02, T14, T16, T32; vault-format §5.7).
 *
 * - Starting the app with a vault always shows the lock screen first.
 * - The fingerprint or the vault password opens it.
 * - A wrong password gets one message that never says which part was wrong.
 * - While a waiting time runs, the password cannot be entered.
 * - After an unlock, the user is told about wrong passwords since the last unlock.
 * - The Lock button, or the vault locking by itself, brings back the lock screen and removes
 *   everything else from the screen.
 */
@RunWith(RobolectricTestRunner::class)
class LockScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val gateway = FakeGateway(exists = true)

    private fun start() {
        compose.setContent { VaultaireApp(gateway, testBuild = false) }
    }

    private fun typePassword(password: String) {
        compose.onNodeWithTag("usePassword").performClick()
        compose.onNodeWithTag("password").performTextInput(password)
        compose.onNodeWithTag("unlock").performClick()
    }

    @Test
    fun anExistingVaultStartsOnTheLockScreen() {
        start()
        compose.onNodeWithTag("unlockFingerprint").assertIsDisplayed()
        compose.onNodeWithTag("usePassword").assertIsDisplayed()
        compose.onNodeWithTag("home").assertDoesNotExist()
    }

    @Test
    fun theFingerprintOpensTheVault() {
        start()
        compose.onNodeWithTag("unlockFingerprint").performClick()
        compose.onNodeWithTag("home").assertIsDisplayed()
    }

    @Test
    fun theRightPasswordOpensTheVault() {
        start()
        typePassword(gateway.rightPassword)
        compose.onNodeWithTag("home").assertIsDisplayed()
    }

    @Test
    fun aWrongPasswordGetsOneGeneralMessage() {
        start()
        typePassword("a completely different passphrase 7")
        compose.onNodeWithTag("error")
            .assertTextContains("Couldn't unlock. Check your password and try again.")
        compose.onNodeWithTag("home").assertDoesNotExist()
    }

    @Test
    fun aWaitingTimeBlocksThePassword() {
        gateway.delayS = 30
        start()
        compose.onNodeWithTag("usePassword").performClick()
        compose.onNodeWithTag("unlock").assertIsNotEnabled()
        compose.onNodeWithText("Too many attempts. Try again in 30 s").assertIsDisplayed()
    }

    @Test
    fun wrongPasswordsSinceTheLastUnlockAreReported() {
        gateway.failures = 3
        start()
        typePassword(gateway.rightPassword)
        compose.onNodeWithText("3 failed password attempts since your last unlock").assertIsDisplayed()
    }

    @Test
    fun theLockButtonLocksAndClearsTheScreen() {
        start()
        compose.onNodeWithTag("unlockFingerprint").performClick()
        compose.onNodeWithTag("lock").performClick()
        assertEquals(1, gateway.locks)
        compose.onNodeWithTag("home").assertDoesNotExist()
        compose.onNodeWithTag("unlockFingerprint").assertIsDisplayed()
    }

    @Test
    fun whenTheVaultLocksByItselfTheLockScreenReturns() {
        start()
        compose.onNodeWithTag("unlockFingerprint").performClick()
        compose.onNodeWithTag("home").assertIsDisplayed()
        gateway.unlocked.value = false
        compose.waitForIdle()
        compose.onNodeWithTag("home").assertDoesNotExist()
        compose.onNodeWithTag("unlockFingerprint").assertIsDisplayed()
    }
}
