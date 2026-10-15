package dev.vaultaire.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Component 15 (milestone M3, owner request 2026-10-15): the card itself, drawn as a premium
 * physical card at the top of its page, floating and catching the light, that can be turned over.
 *
 * The animation is judged by the owner from recorded previews; these tests fix what the card
 * shows and what touching it does, so the security rules hold on the card exactly as in the rows
 * below it (threat-model I10, I11, I12, T11):
 * - The front shows the bank, the network, the name and expiry, and the number masked except the
 *   last four digits. Touching the number asks for the fingerprint, then shows it on the card.
 * - The back shows the CVV masked (or that none is stored). Copying it from the back follows the
 *   same fingerprint and 15-second rules.
 * - The back never shows a PIN, only that one is stored separately; touching that opens the PIN.
 * - The card's finish (Obsidian, Titanium, Platinum, Ivory, Champagne) is chosen under the card
 *   and kept in the vault.
 */
@RunWith(RobolectricTestRunner::class)
class CardFaceTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val dir = Files.createTempDirectory("vaultaire-cardface").toFile()
    private val vault = RealVault(context, dir)

    @After
    fun cleanUp() {
        vault.lock()
        dir.deleteRecursively()
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun card(cvv: String?): EntryId = vault.session().addCard(
        CardInput(
            bankId = "in.hdfc",
            kind = CardKind.CREDIT,
            product = "HDFC Millennia",
            holder = "ASHA RAO",
            number = SecretInput.Set("5555555555554444".toCharArray()),
            expiry = "08/29",
            network = "Mastercard",
            cvv = if (cvv == null) SecretInput.Empty else SecretInput.Set(cvv.toCharArray()),
        ),
        RealVault.NOW,
    )

    private fun open(id: EntryId) {
        compose.setContent { VaultaireApp(vault, testBuild = false) }
        click("bank:in.hdfc")
        click("entry:${id.hex}")
    }

    @Test
    fun theFrontShowsTheCardWithTheNumberMasked() {
        open(card("321"))
        val front = compose.onNodeWithTag("cardFace")
        front.assertTextContains("HDFC Bank")
        front.assertTextContains("•••• •••• •••• 4444")
        front.assertTextContains("ASHA RAO")
        front.assertTextContains("08/29")
        front.assertTextContains("Mastercard")
        compose.onNodeWithText("5555", substring = true).assertDoesNotExist()
    }

    @Test
    fun touchingTheNumberOnTheCardAsksForTheFingerprintThenShowsIt() {
        open(card("321"))
        click("cardNumber")
        assertEquals(1, vault.prompts)
        compose.onNodeWithTag("cardFace").assertTextContains("5555 5555 5555 4444")
    }

    @Test
    fun aRefusedFingerprintLeavesTheCardMasked() {
        vault.fingerprintAccepted = false
        open(card("321"))
        click("cardNumber")
        compose.onNodeWithTag("cardFace").assertTextContains("•••• •••• •••• 4444")
    }

    @Test
    fun turningTheCardShowsTheCvvMaskedAndCopiesItWithTheSameRules() {
        open(card("321"))
        click("turnCard")
        compose.onNodeWithTag("cardBack").assertTextContains("•••")
        compose.onNodeWithText("321").assertDoesNotExist()
        click("copyCvvOnCard")
        assertEquals(1, vault.prompts)
        assertEquals("321", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        compose.onNodeWithText("Copied, clears in 15 s").assertExists()
    }

    @Test
    fun aCardWithoutACvvSaysSoOnTheBack() {
        open(card(null))
        click("turnCard")
        compose.onNodeWithTag("cardBack").assertTextContains("No CVV stored")
    }

    @Test
    fun theBackNeverShowsAPinOnlyALinkToIt() {
        val id = card("321")
        vault.session().addPin(
            PinInput(PinPurpose.ATM_PIN, "HDFC card PIN", SecretInput.Set("8642".toCharArray()), linkedTo = id),
            RealVault.NOW,
        )
        open(id)
        click("turnCard")
        compose.onNodeWithTag("cardBack").assertTextContains("PIN stored separately")
        compose.onNodeWithText("8642", substring = true).assertDoesNotExist()
        click("pinOnCard")
        compose.onNodeWithTag("entryPage").assertTextContains("HDFC card PIN", substring = true)
        assertEquals("opening the PIN page asks for nothing yet", 0, vault.prompts)
    }

    @Test
    fun turningTheCardBackShowsTheFrontAgain() {
        open(card("321"))
        click("turnCard")
        click("turnCard")
        compose.onNodeWithTag("cardFace").assertTextContains("•••• •••• •••• 4444")
    }

    @Test
    fun theChosenFinishIsKeptInTheVault() {
        val id = card("321")
        open(id)
        compose.onNodeWithTag("finish:obsidian").assertIsSelected()
        click("finish:champagne")
        click("back")
        click("entry:${id.hex}")
        compose.onNodeWithTag("finish:champagne").assertIsSelected()
        assertTrue(vault.prompts == 0)
    }
}
