package dev.vaultaire.ui

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import dev.vaultaire.core.BankAccountInput
import dev.vaultaire.core.CardKind
import dev.vaultaire.core.PinInput
import dev.vaultaire.core.PinPurpose
import dev.vaultaire.core.SecretInput
import java.nio.file.Files
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Component 14 (milestone M3): the home page and a bank's page (product plan §5.1, §5.2, §8
 * checks 1–3; threat-model I12).
 *
 * - Home shows one tile per bank with its card counts, an "All cards" tile, favourites, and
 *   sections for bank accounts and PINs. Search runs over names, never over secrets.
 * - A bank's page lists only its cards, with All, Credit and Debit filters and their counts.
 * - Lists show the title, card kind, network and last four digits only.
 */
@RunWith(RobolectricTestRunner::class)
class HomeAndBankTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir = Files.createTempDirectory("vaultaire-home").toFile()
    private val vault = RealVault(context, dir)

    @After
    fun cleanUp() {
        vault.lock()
        dir.deleteRecursively()
    }

    private fun start() {
        compose.setContent { VaultaireApp(vault, testBuild = false) }
    }

    private fun open(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    /** Every listed entry; each row is tagged `entry:<id>`. */
    private fun rows() = compose.onAllNodes(
        SemanticsMatcher("an entry row") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("entry:") == true
        },
    )

    @Test
    fun anEmptyVaultInvitesYouToAddACard() {
        start()
        compose.onNodeWithText("No cards yet").assertExists()
        compose.onNodeWithTag("addCard").assertExists()
    }

    @Test
    fun homeShowsATilePerBankWithItsCounts() {
        vault.fixture()
        start()
        compose.onNodeWithTag("bank:all").assertTextContains("All cards", substring = true)
        compose.onNodeWithTag("bank:all").assertTextContains("10 cards", substring = true)
        compose.onNodeWithTag("bank:in.sbi").assertTextContains("SBI", substring = true)
        compose.onNodeWithTag("bank:in.sbi")
            .assertTextContains("5 cards · 3 credit · 2 debit", substring = true)
        compose.onNodeWithTag("bank:in.hdfc")
            .assertTextContains("2 cards · 1 credit · 1 debit", substring = true)
        compose.onNodeWithTag("bank:in.axis")
            .assertTextContains("3 cards · 2 credit · 1 debit", substring = true)
    }

    @Test
    fun cardsWithoutABankHaveTheirOwnTile() {
        vault.card(null, CardKind.OTHER, "Forex card", "4000000000009001")
        start()
        compose.onNodeWithTag("bank:unassigned")
            .assertTextContains("Other / Unassigned", substring = true)
    }

    @Test
    fun favouritesAreShownOnHome() {
        val ids = vault.fixture()
        vault.session().setFavourite(ids[5], true)
        start()
        compose.onNodeWithTag("favourites").assertTextContains("HDFC Millennia", substring = true)
    }

    @Test
    fun bankAccountsAndPinsHaveTheirOwnSections() {
        vault.session().addBankAccount(
            BankAccountInput(bankId = "in.hdfc", accountNumber = SecretInput.Set("50100123456789".toCharArray())),
            RealVault.NOW,
        )
        vault.session().addPin(
            PinInput(PinPurpose.LOCKER, "SBI locker 214", SecretInput.Set("482913".toCharArray())),
            RealVault.NOW,
        )
        start()
        compose.onNodeWithTag("section:accounts").assertTextContains("HDFC Bank account", substring = true)
        compose.onNodeWithTag("section:pins").assertTextContains("SBI locker 214", substring = true)
        compose.onNodeWithText("482913", substring = true).assertDoesNotExist()
    }

    @Test
    fun searchFindsCardsByBankNameAndNeverShowsANumber() {
        vault.fixture()
        start()
        compose.onNodeWithTag("search").performTextInput("state bank")
        rows().assertCountEquals(5)
        compose.onNodeWithText("4111111111111234", substring = true).assertDoesNotExist()
        compose.onNodeWithText("4111 1111 1111 1234", substring = true).assertDoesNotExist()
    }

    @Test
    fun check2OpeningSbiShowsOnlyItsFiveCards() {
        vault.fixture()
        start()
        open("bank:in.sbi")
        compose.onNodeWithTag("bankPage").assertTextContains("SBI · 5 cards", substring = true)
        rows().assertCountEquals(5)
    }

    @Test
    fun check3TheFiltersShowCreditOrDebitWithTheirCounts() {
        vault.fixture()
        start()
        open("bank:in.sbi")
        compose.onNodeWithTag("filter:all").assertTextContains("All 5", substring = true)
        compose.onNodeWithTag("filter:credit").assertTextContains("Credit 3", substring = true)
        compose.onNodeWithTag("filter:debit").assertTextContains("Debit 2", substring = true)
        open("filter:credit")
        rows().assertCountEquals(3)
        open("filter:debit")
        rows().assertCountEquals(2)
    }

    @Test
    fun aRowShowsKindNetworkAndLastFourOnly() {
        vault.card("in.sbi", CardKind.CREDIT, "SBI Cashback", "4111111111111234")
        start()
        open("bank:in.sbi")
        compose.onNodeWithText("Credit · Visa · •••• 1234").assertExists()
        compose.onNodeWithText("4111", substring = true).assertDoesNotExist()
    }

    @Test
    fun anEmptyFilterSaysSo() {
        vault.card("in.sbi", CardKind.CREDIT, "SBI Cashback", "4111111111111234")
        start()
        open("bank:in.sbi")
        open("filter:debit")
        compose.onNodeWithText("No SBI debit cards").assertExists()
        rows().assertCountEquals(0)
    }

    @Test
    fun backFromABankReturnsHome() {
        vault.fixture()
        start()
        open("bank:in.sbi")
        open("back")
        compose.onNodeWithTag("bank:all").assertExists()
        compose.onAllNodesWithTag("bankPage").assertCountEquals(0)
    }
}
