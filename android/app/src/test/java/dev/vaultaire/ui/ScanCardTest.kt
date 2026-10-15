package dev.vaultaire.ui

import android.Manifest
import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import dev.vaultaire.core.CardKind
import dev.vaultaire.scan.LocalCardCamera
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Component 18 (milestone M5, ADR-009; threat-model T35): scanning the front of a card from the
 * Add card form.
 *
 * - The camera starts only with the camera permission, and stops when a card is read, when the
 *   scanner is closed and when the vault locks.
 * - A scan fills the number, expiry and name (and the network, from the number). It never chooses
 *   the bank or the card type and never reads the CVV; a bank printed on the card is only offered.
 * - Nothing is saved until the person taps Save.
 */
@RunWith(RobolectricTestRunner::class)
class ScanCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val dir = Files.createTempDirectory("vaultaire-scan").toFile()
    private val vault = RealVault(context, dir)
    private val camera = FakeCamera()

    private val hdfcFront = arrayOf(
        "HDFC BANK",
        "PLATINUM",
        "5555 5555 5555 4444",
        "VALID THRU 08/29",
        "ASHA RAO",
        "CVV 321",
    )

    @After
    fun cleanUp() {
        vault.lock()
        dir.deleteRecursively()
    }

    private fun start(cameraAllowed: Boolean = true) {
        if (cameraAllowed) shadowOf(context).grantPermissions(Manifest.permission.CAMERA)
        compose.setContent {
            CompositionLocalProvider(LocalCardCamera provides camera) { VaultaireApp(vault, testBuild = false) }
        }
    }

    /** A control in the scrolling page. */
    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    /** A control on the scanner, which fills the screen and does not scroll. */
    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performClick()
    }

    private fun type(tag: String, text: String) {
        compose.onNodeWithTag(tag).performScrollTo().performTextInput(text)
    }

    /** The same text in three frames in a row: enough for the reader to take the number. */
    private fun scan(vararg lines: String) {
        repeat(3) { compose.runOnIdle { camera.show(*lines) } }
        compose.waitForIdle()
    }

    private fun openScanner() {
        click("addCard")
        click("scanCard")
    }

    private fun saveAsCredit(name: String) {
        click("kind:credit")
        type("field:product", name)
        click("save")
    }

    @Test
    fun theAddCardFormOffersToScanTheCard() {
        start()
        click("addCard")
        compose.onNodeWithTag("scanCard").assertHasClickAction()
    }

    @Test
    fun withoutTheCameraPermissionTheCameraNeverStarts() {
        start(cameraAllowed = false)
        openScanner()
        assertEquals(0, camera.starts)
        compose.onAllNodesWithTag("scanner").assertCountEquals(0)
    }

    @Test
    fun theScannerShowsHowToHoldTheCard() {
        start()
        openScanner()
        compose.onNodeWithTag("scanner").assertTextContains("Hold the front of your card inside the frame", substring = true)
        assertTrue(camera.running)
    }

    @Test
    fun aScanFillsTheNumberExpiryAndNameAndNothingElse() {
        start()
        openScanner()
        scan(*hdfcFront)

        compose.onAllNodesWithTag("scanner").assertCountEquals(0)
        assertFalse("the camera stops once the card is read", camera.running)
        compose.onNodeWithTag("field:number").assertTextContains("5555 5555 5555 4444")
        compose.onNodeWithTag("field:expiry").assertTextContains("08/29")
        compose.onNodeWithTag("field:holder").assertTextContains("ASHA RAO")
        compose.onNodeWithTag("field:network").assertTextContains("Mastercard")
        compose.onNodeWithTag("kind:credit").assertIsNotSelected()
        compose.onNodeWithTag("kind:debit").assertIsNotSelected()
        compose.onNodeWithTag("save").assertIsNotEnabled()
        compose.onAllNodesWithText("321", substring = true).assertCountEquals(0)
        assertTrue("nothing is saved until Save", vault.session().summaries().isEmpty())
    }

    @Test
    fun aBankSeenOnTheCardIsOnlyASuggestion() {
        start()
        openScanner()
        scan(*hdfcFront)
        compose.onNodeWithTag("seenBank").assertTextContains("Seen on card: HDFC Bank")
        saveAsCredit("HDFC Millennia")
        assertNull("not chosen, so no bank", vault.session().summaries().single().bankId)
    }

    @Test
    fun tappingTheSuggestionChoosesThatBank() {
        start()
        openScanner()
        scan(*hdfcFront)
        click("seenBank")
        saveAsCredit("HDFC Millennia")
        val card = vault.session().summaries().single()
        assertEquals("in.hdfc", card.bankId)
        assertEquals(CardKind.CREDIT, card.cardKind)
        assertEquals("4444", card.last4)
    }

    @Test
    fun aBankChosenBeforeScanningIsKept() {
        vault.card("in.sbi", CardKind.DEBIT, "SBI Classic Debit", "6074000000002002")
        start()
        click("bank:in.sbi")
        click("addCardHere")
        click("scanCard")
        scan(*hdfcFront)
        saveAsCredit("Second card")
        assertEquals("in.sbi", vault.session().summaries().single { it.title == "Second card" }.bankId)
    }

    @Test
    fun closingTheScannerFillsNothingAndStopsTheCamera() {
        start()
        openScanner()
        compose.runOnIdle { camera.show(*hdfcFront) }
        tap("closeScanner")
        compose.onAllNodesWithTag("scanner").assertCountEquals(0)
        assertFalse(camera.running)
        click("kind:credit")
        type("field:product", "HDFC Millennia")
        compose.onNodeWithTag("save").assertIsNotEnabled()
    }

    @Test
    fun lockingWhileScanningStopsTheCamera() {
        start()
        openScanner()
        vault.lock()
        compose.waitForIdle()
        assertFalse(camera.running)
        compose.onNodeWithTag("unlockFingerprint").assertExists()
    }
}
