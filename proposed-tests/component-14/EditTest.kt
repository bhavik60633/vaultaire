package dev.vaultaire.ui

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import dev.vaultaire.core.CardKind
import dev.vaultaire.core.EntryKind
import dev.vaultaire.core.PinPurpose
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Component 14 (milestone M3): adding and editing cards, PINs and bank accounts (product plan
 * §3, §4, §5.5; vault-format §5.10).
 *
 * - A card needs a bank, a card type, a card name or nickname, and a number. The network is
 *   suggested from the number. A bank not in the list can be added by typing its name.
 * - Editing a card keeps its number unless the user types a new one.
 * - A PIN needs what it is for and its digits; an ATM PIN is saved as its own entry.
 * - A bank account checks the IFSC.
 */
@RunWith(RobolectricTestRunner::class)
class EditTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir = Files.createTempDirectory("vaultaire-edit").toFile()
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

    private fun type(tag: String, text: String) {
        compose.onNodeWithTag(tag).performScrollTo().performTextInput(text)
    }

    @Test
    fun aCardIsAddedWithItsBankTypeNameAndNumber() {
        start()
        click("addCard")
        type("bankPicker", "State Bank")
        click("pickBank:in.sbi")
        click("kind:credit")
        type("field:product", "SBI Cashback")
        type("field:holder", "ASHA RAO")
        type("field:number", "4111 1111 1111 1234")
        type("field:expiry", "08/29")
        click("save")

        val card = vault.session().summaries().single()
        assertEquals(EntryKind.CARD, card.kind)
        assertEquals("in.sbi", card.bankId)
        assertEquals(CardKind.CREDIT, card.cardKind)
        assertEquals("Credit · Visa · •••• 1234", card.subtitle)
        assertArrayEquals("4111111111111234".toCharArray(), vault.session().reveal(card.id, "card.number"))
        compose.onNodeWithTag("bank:in.sbi").assertExists()
    }

    @Test
    fun saveWaitsForTheCardTypeNameAndNumber() {
        start()
        click("addCard")
        compose.onNodeWithTag("save").assertIsNotEnabled()
        type("field:product", "SBI Cashback")
        type("field:number", "4111111111111234")
        compose.onNodeWithTag("save").assertIsNotEnabled()
        click("kind:debit")
        compose.onNodeWithTag("save").assertIsEnabled()
    }

    @Test
    fun theNetworkIsSuggestedFromTheNumber() {
        start()
        click("addCard")
        type("field:number", "5105105105105100")
        compose.onNodeWithTag("field:network").assertTextContains("Mastercard")
    }

    @Test
    fun aWrongNumberIsExplainedAndNothingIsSaved() {
        start()
        click("addCard")
        click("kind:credit")
        type("field:product", "SBI Cashback")
        type("field:number", "4111")
        click("save")
        compose.onNodeWithTag("error").assertTextContains("card number", substring = true)
        assertTrue(vault.session().summaries().isEmpty())
    }

    @Test
    fun aBankThatIsNotListedCanBeAddedByName() {
        start()
        click("addCard")
        type("bankPicker", "Saraswat Co-op Bank")
        compose.onNodeWithTag("pickNewBank").assertTextContains("Add “Saraswat Co-op Bank” as a bank")
        click("pickNewBank")
        click("kind:debit")
        type("field:product", "Saraswat Debit")
        type("field:number", "4000000000007001")
        click("save")
        val bankId = vault.session().summaries().single().bankId!!
        assertTrue(bankId.startsWith("u:"))
        compose.onNodeWithTag("bank:$bankId").assertTextContains("Saraswat Co-op Bank", substring = true)
    }

    @Test
    fun editingKeepsTheNumberUnlessANewOneIsTyped() {
        val id = vault.card("in.sbi", CardKind.CREDIT, "SBI Cashback", "4111111111111234")
        start()
        click("bank:in.sbi")
        click("entry:${id.hex}")
        click("edit")
        compose.onNodeWithTag("field:product").performTextClearance()
        type("field:product", "SBI Prime")
        click("save")
        assertEquals("SBI Prime", vault.session().summary(id)!!.title)
        assertArrayEquals("4111111111111234".toCharArray(), vault.session().reveal(id, "card.number"))
    }

    @Test
    fun anAtmPinIsSavedAsItsOwnEntry() {
        start()
        click("addPin")
        click("purpose:ATM_PIN")
        type("field:label", "SBI debit card PIN")
        type("field:pin", "4321")
        click("save")
        val pin = vault.session().summaries().single()
        assertEquals(EntryKind.PIN, pin.kind)
        assertEquals(PinPurpose.ATM_PIN, pin.pinPurpose)
        assertEquals(4, vault.session().details(pin.id).fields.single { it.key == "pin.value" }.sensitivity)
        compose.onNodeWithTag("section:pins").assertTextContains("SBI debit card PIN", substring = true)
    }

    @Test
    fun aBankAccountChecksItsIfsc() {
        start()
        click("addAccount")
        type("bankPicker", "HDFC")
        click("pickBank:in.hdfc")
        type("field:account_number", "50100123456789")
        type("field:ifsc", "HDFC1234567")
        click("save")
        compose.onNodeWithTag("error").assertTextContains("IFSC", substring = true)
        assertTrue(vault.session().summaries().isEmpty())

        compose.onNodeWithTag("field:ifsc").performTextClearance()
        type("field:ifsc", "HDFC0001234")
        click("save")
        assertEquals("HDFC Bank account", vault.session().summaries().single().title)
    }
}
