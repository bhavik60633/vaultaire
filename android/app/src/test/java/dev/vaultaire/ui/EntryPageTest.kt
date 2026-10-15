package dev.vaultaire.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import dev.vaultaire.core.CardInput
import dev.vaultaire.core.CardKind
import dev.vaultaire.core.EntryId
import dev.vaultaire.core.PinInput
import dev.vaultaire.core.PinPurpose
import dev.vaultaire.core.SecretInput
import java.nio.file.Files
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Component 14 (milestone M3): a card's page and a PIN's page (product plan §5.3, §8 check 9;
 * vault-format §5.5, §5.10; threat-model I10, I11, I12, T11).
 *
 * - Secret values are masked. Each row has its own copy button; there is no "copy all".
 * - Copying or showing the card number or CVV needs a fingerprint from the last minute; the
 *   number is copied without spaces and cleared after 15 seconds. Other values need none and
 *   are cleared after 30 seconds. A refused fingerprint copies and shows nothing.
 * - A card linked to a PIN shows only "Stored separately · fingerprint every time". Every PIN reveal or copy needs a
 *   fresh fingerprint.
 * - Leaving the page hides whatever was shown. Deleting asks first.
 */
@RunWith(RobolectricTestRunner::class)
class EntryPageTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val dir = Files.createTempDirectory("vaultaire-entry").toFile()
    private val vault = RealVault(context, dir)

    @After
    fun cleanUp() {
        vault.lock()
        dir.deleteRecursively()
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun clipText(): String? = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

    private fun waitS(seconds: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
        compose.waitForIdle()
    }

    private fun sbiCard(): EntryId = vault.session().addCard(
        CardInput(
            bankId = "in.sbi",
            kind = CardKind.CREDIT,
            product = "SBI Cashback",
            holder = "ASHA RAO",
            number = SecretInput.Set("4111111111111234".toCharArray()),
            expiry = "08/29",
            network = "Visa",
            cvv = SecretInput.Set("987".toCharArray()),
        ),
        RealVault.NOW,
    )

    private fun openCard(id: EntryId) {
        compose.setContent { VaultaireApp(vault, testBuild = false) }
        click("bank:in.sbi")
        click("entry:${id.hex}")
        compose.onNodeWithTag("entryPage").assertExists()
    }

    @Test
    fun secretsAreMaskedAndThereIsNoCopyAll() {
        openCard(sbiCard())
        compose.onNodeWithTag("value:card.number").assertTextContains("•••• •••• •••• 1234")
        compose.onNodeWithTag("value:card.cvv").assertTextContains("•••")
        compose.onNodeWithTag("value:card.holder").assertTextContains("ASHA RAO")
        compose.onNodeWithText("4111", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Copy all").assertDoesNotExist()
        compose.onNodeWithText("Copied without spaces · clears in 15 s").assertExists()
        compose.onNodeWithTag("copy:card.number").assertExists()
        compose.onNodeWithTag("copy:card.holder").assertExists()
    }

    @Test
    fun check9CopyingTheNumberAsksForAFingerprintAndClearsIn15Seconds() {
        openCard(sbiCard())
        click("copy:card.number")
        assertEquals(1, vault.prompts)
        assertEquals("copied without spaces", "4111111111111234", clipText())
        compose.onNodeWithText("Copied, clears in 15 s").assertExists()
        compose.onNodeWithText("4111111111111234").assertDoesNotExist()
        waitS(15)
        assertTrue(clipText().isNullOrEmpty())
    }

    @Test
    fun aRefusedFingerprintCopiesAndShowsNothing() {
        vault.fingerprintAccepted = false
        openCard(sbiCard())
        click("copy:card.number")
        click("reveal:card.number")
        assertEquals(2, vault.prompts)
        assertTrue(clipText().isNullOrEmpty())
        compose.onNodeWithTag("value:card.number").assertTextContains("•••• •••• •••• 1234")
    }

    @Test
    fun oneFingerprintCoversTheCardForAMinute() {
        openCard(sbiCard())
        click("reveal:card.number")
        compose.onNodeWithTag("value:card.number").assertTextContains("4111 1111 1111 1234")
        vault.clock.advanceS(30)
        click("copy:card.cvv")
        assertEquals("987", clipText())
        assertEquals("no second prompt inside the minute", 1, vault.prompts)
        vault.clock.advanceS(31)
        click("copy:card.number")
        assertEquals(2, vault.prompts)
    }

    @Test
    fun copyingTheNameNeedsNoFingerprintAndClearsIn30Seconds() {
        openCard(sbiCard())
        click("copy:card.holder")
        assertEquals(0, vault.prompts)
        assertEquals("ASHA RAO", clipText())
        compose.onNodeWithText("Copied, clears in 30 s").assertExists()
        waitS(29)
        assertEquals("ASHA RAO", clipText())
        waitS(1)
        assertTrue(clipText().isNullOrEmpty())
    }

    @Test
    fun leavingThePageHidesWhatWasShown() {
        val id = sbiCard()
        openCard(id)
        click("reveal:card.number")
        compose.onNodeWithTag("value:card.number").assertTextContains("4111 1111 1111 1234")
        click("back")
        click("entry:${id.hex}")
        compose.onNodeWithTag("value:card.number").assertTextContains("•••• •••• •••• 1234")
    }

    @Test
    fun aLinkedPinShowsOnlyAsALinkAndEveryRevealNeedsAFingerprint() {
        val cardId = sbiCard()
        vault.session().addPin(
            PinInput(PinPurpose.ATM_PIN, "SBI card PIN", SecretInput.Set("4321".toCharArray()), linkedTo = cardId),
            RealVault.NOW,
        )
        openCard(cardId)
        compose.onNodeWithTag("linkedPin").assertTextContains("ATM PIN")
        compose.onNodeWithTag("linkedPin").assertTextContains("Stored separately · fingerprint every time")
        compose.onNodeWithText("4321", substring = true).assertDoesNotExist()

        click("linkedPin")
        compose.onNodeWithTag("entryPage").assertTextContains("SBI card PIN", substring = true)
        click("reveal:pin.value")
        compose.onNodeWithTag("value:pin.value").assertTextContains("4321")
        click("copy:pin.value")
        assertEquals("a fresh fingerprint for each", 2, vault.prompts)
        assertEquals("4321", clipText())
        compose.onNodeWithText("Copied, clears in 15 s").assertExists()
    }

    @Test
    fun favouriteCanBeSwitchedOnFromTheCard() {
        val id = sbiCard()
        openCard(id)
        click("favourite")
        assertTrue(vault.session().summary(id)!!.favourite)
    }

    @Test
    fun deletingAsksFirstAndThenTheBankTileGoes() {
        val id = sbiCard()
        openCard(id)
        click("delete")
        compose.onNodeWithTag("confirmDelete").assertExists()
        click("confirmDelete")
        compose.onAllNodesWithTag("bank:in.sbi").assertCountEquals(0)
        assertEquals(null, vault.session().summary(id))
    }
}
