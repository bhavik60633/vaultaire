package dev.vaultaire.ui

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Component 14 (milestone M3): coming back from a shopping app, and the settings page
 * (product plan §5.4, §8 check 10; vault-format §5.5; threat-model I6, I13, T02).
 *
 * - After the vault locked, unlocking returns to the same bank and filter, from the vault's own
 *   encrypted record, and never to a value that was showing. Going back home forgets the bank.
 * - The settings page changes the security settings. A stricter choice is saved at once; a
 *   weaker one asks for the vault password first.
 */
@RunWith(RobolectricTestRunner::class)
class ReturnAndSettingsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir = Files.createTempDirectory("vaultaire-return").toFile()
    private val vault = RealVault(context, dir)

    @After
    fun cleanUp() {
        vault.lock()
        dir.deleteRecursively()
    }

    private fun start() {
        compose.setContent { VaultaireApp(vault, testBuild = false) }
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun lockAndUnlock() {
        vault.lock()
        compose.waitForIdle()
        compose.onNodeWithTag("unlockFingerprint").assertExists()
        click("unlockFingerprint")
        compose.waitForIdle()
    }

    @Test
    fun check10AfterUnlockingTheSameBankAndFilterReturnWithNothingRevealed() {
        val ids = vault.fixture()
        start()
        click("bank:in.sbi")
        click("filter:credit")
        click("entry:${ids[0].hex}")
        click("reveal:card.number")
        compose.onNodeWithTag("value:card.number").assertTextContains("4111 1111 1111 1234")

        lockAndUnlock()
        compose.onNodeWithTag("bankPage").assertTextContains("5 cards · 2 debit · 3 credit")
        compose.onNodeWithTag("filter:credit").assertIsSelected()
        compose.onNodeWithText("4111 1111 1111 1234").assertDoesNotExist()
        compose.onAllNodesWithTag("entryPage").assertCountEquals(0)
    }

    @Test
    fun goingBackHomeMeansUnlockingReturnsHome() {
        vault.fixture()
        start()
        click("bank:in.sbi")
        click("back")
        lockAndUnlock()
        compose.onNodeWithTag("bank:all").assertExists()
        compose.onAllNodesWithTag("bankPage").assertCountEquals(0)
    }

    @Test
    fun theSettingsPageShowsTheCurrentChoices() {
        start()
        click("settings")
        compose.onNodeWithTag("autoLock:120").assertIsSelected()
        compose.onNodeWithTag("backgroundLock:30").assertIsSelected()
        compose.onNodeWithTag("clipboard:30").assertIsSelected()
    }

    @Test
    fun aStricterChoiceIsSavedAtOnce() {
        start()
        click("settings")
        click("autoLock:60")
        assertEquals(60L, vault.session().settings().autoLockS)
        compose.onAllNodesWithTag("settingsPassword").assertCountEquals(0)
    }

    @Test
    fun aWeakerChoiceAsksForTheVaultPassword() {
        start()
        click("settings")
        click("clipboard:120")
        compose.onNodeWithTag("settingsPassword").assertExists()
        assertEquals("not saved yet", 30L, vault.session().settings().clipboardTtlS)
        compose.onNodeWithTag("settingsPassword").performTextInput(vault.password)
        click("confirmSettings")
        assertEquals(120L, vault.session().settings().clipboardTtlS)
    }

    @Test
    fun aWrongPasswordLeavesTheSettingAsItWas() {
        start()
        click("settings")
        click("autoLock:900")
        compose.onNodeWithTag("settingsPassword").performTextInput("a completely different passphrase 7")
        click("confirmSettings")
        compose.onNodeWithTag("error").assertExists()
        assertEquals(120L, vault.session().settings().autoLockS)
    }
}
