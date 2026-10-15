package dev.vaultaire.core

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Component 13 (milestone M3): cards, PINs and bank accounts across the bridge
 * (vault-format §5.10; crypto spec §5.1; threat-model I10, I12).
 *
 * These tests call the real Rust core. What they fix in place:
 * - entries go in as plain typed fields and come back as summaries, details and groups;
 * - secret values handed in are wiped from memory once saved, and come back only one at a time,
 *   as characters the caller can wipe, never as part of a list;
 * - grouping by bank, filters, search and the last card view work through the bridge;
 * - every failure is a [VaultException] with a short code.
 */
class EntriesBridgeTest {

    private val password = "correct horse battery staple 42"
    private val now = 1_760_000_000L
    private val dir: File = Files.createTempDirectory("vaultaire-entries").toFile()
    private val engine = VaultEngine(File(dir, "vault.db"), allowSoftwareKeys = false)
    private lateinit var session: VaultSession

    private object Pwd : PwdKey {
        override val level = KeyLevel.TEE
        override fun hmac(input: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(ByteArray(32) { 0x5a }, "HmacSHA256"))
            doFinal(input)
        }
    }

    @Before
    fun create() {
        session = engine.create(password.toCharArray(), Pwd, null, now).session
    }

    @After
    fun cleanUp() {
        session.lock()
        dir.deleteRecursively()
    }

    private fun secret(value: String) = SecretInput.Set(value.toCharArray())

    private fun card(bank: String?, kind: CardKind, product: String, number: String) = CardInput(
        bankId = bank,
        kind = kind,
        product = product,
        holder = "ASHA RAO",
        number = secret(number),
        expiry = "08/29",
        network = VaultEngine.suggestNetwork(number),
    )

    private fun wiped(chars: CharArray) = chars.all { it == '\u0000' }

    @Test
    fun aCardGoesInAndComesBackAsASummary() {
        val id = session.addCard(card("in.sbi", CardKind.CREDIT, "SBI Cashback", "4111111111111234"), now)
        val summary = session.summary(id)!!
        assertEquals(EntryKind.CARD, summary.kind)
        assertEquals("SBI Cashback", summary.title)
        assertEquals("Credit · Visa · •••• 1234", summary.subtitle)
        assertEquals("in.sbi", summary.bankId)
        assertEquals(CardKind.CREDIT, summary.cardKind)
        assertEquals("1234", summary.last4)
        assertArrayEquals("4111111111111234".toCharArray(), session.reveal(id, "card.number"))
    }

    @Test
    fun secretsHandedInAreWipedOnceSaved() {
        val number = "4111111111111234".toCharArray()
        val cvv = "123".toCharArray()
        session.addCard(
            card("in.sbi", CardKind.CREDIT, "SBI Cashback", "").copy(
                number = SecretInput.Set(number),
                cvv = SecretInput.Set(cvv),
            ),
            now,
        )
        assertTrue(wiped(number))
        assertTrue(wiped(cvv))
    }

    @Test
    fun anEditKeepsTheSecretsItDoesNotTouch() {
        val id = session.addCard(card("in.sbi", CardKind.CREDIT, "SBI Cashback", "4111111111111234"), now)
        session.updateCard(
            id,
            card("in.sbi", CardKind.CREDIT, "SBI Cashback", "").copy(
                nickname = "Flipkart card",
                number = SecretInput.Keep,
            ),
            now + 1,
        )
        assertEquals("Flipkart card", session.summary(id)!!.title)
        assertArrayEquals("4111111111111234".toCharArray(), session.reveal(id, "card.number"))
    }

    @Test
    fun anAtmPinIsItsOwnEntryAndCriticalStrict() {
        val cardId = session.addCard(card("in.sbi", CardKind.DEBIT, "SBI Classic Debit", "6074000000002002"), now)
        val pinId = session.addPin(
            PinInput(PinPurpose.ATM_PIN, "SBI debit card PIN", secret("4321"), linkedTo = cardId),
            now,
        )
        val pin = session.details(pinId)
        assertEquals(EntryKind.PIN, pin.summary.kind)
        assertEquals(listOf(cardId), pin.summary.linkedTo)
        assertEquals(4, pin.fields.single { it.key == "pin.value" }.sensitivity)
        assertNull("secret values never come with the details", pin.fields.single { it.key == "pin.value" }.value)
        assertArrayEquals("4321".toCharArray(), session.reveal(pinId, "pin.value"))
        assertTrue(session.details(cardId).fields.none { it.key.contains("pin") })
    }

    @Test
    fun aBankAccountKeepsItsIndianDetails() {
        val id = session.addBankAccount(
            BankAccountInput(
                bankId = "in.hdfc",
                accountNumber = secret("50100123456789"),
                ifsc = "HDFC0001234",
                netbankingPassword = secret("Fake-NB-pass-1"),
                mpin = secret("2468"),
                rmPhone = "+91 22 6160 6161",
            ),
            now,
        )
        val summary = session.summary(id)!!
        assertEquals(EntryKind.BANK_ACCOUNT, summary.kind)
        assertEquals("HDFC Bank account", summary.title)
        assertEquals("•••• 6789", summary.subtitle)
        val details = session.details(id)
        assertEquals("HDFC0001234", details.fields.single { it.key == "bank.ifsc" }.value)
        assertEquals(3, details.fields.single { it.key == "bank.netbanking_password" }.sensitivity)
        assertArrayEquals("2468".toCharArray(), session.reveal(id, "bank.mpin"))
    }

    @Test
    fun cardsAreGroupedFilteredAndSearchedThroughTheBridge() {
        session.addCard(card("in.sbi", CardKind.CREDIT, "SBI Cashback", "4111111111111234"), now)
        session.addCard(card("in.sbi", CardKind.CREDIT, "SBI SimplyCLICK", "5105105105102001"), now)
        session.addCard(card("in.sbi", CardKind.DEBIT, "SBI Classic Debit", "6074000000002002"), now)
        session.addCard(card("in.hdfc", CardKind.CREDIT, "HDFC Millennia", "5555555555554444"), now)

        val groups = session.bankGroups()
        assertEquals(listOf("SBI", "HDFC Bank"), groups.map { it.name })
        assertEquals(BankGroup("in.sbi", "SBI", total = 3, credit = 2, debit = 1), groups.first())
        assertEquals(2, session.cards(Scope.Bank("in.sbi"), CardFilter.CREDIT).size)
        assertEquals(4, session.cards(Scope.All, CardFilter.ALL).size)
        assertEquals(3, session.search("state bank").size)
        assertEquals(listOf("HDFC Millennia"), session.search("millennia").map { it.title })
    }

    @Test
    fun favouritesAndRecentUseComeAcross() {
        val id = session.addCard(card("in.sbi", CardKind.CREDIT, "SBI Cashback", "4111111111111234"), now)
        assertFalse(session.summary(id)!!.favourite)
        session.setFavourite(id, true)
        session.recordUse(id, now + 60)
        val summary = session.summary(id)!!
        assertTrue(summary.favourite)
        assertEquals(now + 60, summary.lastUsedAtS)
    }

    @Test
    fun deletingAnEntryRemovesIt() {
        val id = session.addCard(card("in.sbi", CardKind.CREDIT, "SBI Cashback", "4111111111111234"), now)
        session.delete(id)
        assertNull(session.summary(id))
        assertTrue(session.bankGroups().isEmpty())
    }

    @Test
    fun failuresArriveAsShortCodes() {
        val bad = assertThrows(VaultException::class.java) {
            session.addCard(card("in.sbi", CardKind.CREDIT, "SBI Cashback", "1234"), now)
        }
        assertEquals("schema_violation", bad.code)
        val missing = assertThrows(VaultException::class.java) {
            session.reveal(EntryId("00000000000000000000000000000000"), "card.number")
        }
        assertEquals("not_found", missing.code)
    }

    @Test
    fun theLastCardViewSurvivesLocking() {
        assertNull(session.lastCardView())
        session.setLastCardView(Scope.Bank("in.sbi"), CardFilter.DEBIT)
        session.lock()
        session = engine.unlockWithPassword(password.toCharArray(), Pwd)
        assertEquals(Scope.Bank("in.sbi") to CardFilter.DEBIT, session.lastCardView())
        session.clearLastCardView()
        assertNull(session.lastCardView())
    }

    @Test
    fun banksAreFoundByNameAndAddedOnce() {
        assertTrue(VaultEngine.catalogue().any { it.id == "in.sbi" && it.name == "SBI" })
        assertEquals("in.sbi", session.addBank("State Bank of India"))
        val custom = session.addBank("Saraswat Co-op Bank")
        assertTrue(custom.startsWith("u:"))
        assertEquals(custom, session.addBank("saraswat co-op"))
        assertEquals("Saraswat Co-op Bank", session.bankName(custom))
        assertEquals("Visa", VaultEngine.suggestNetwork("4111111111111234"))
        assertNull(VaultEngine.suggestNetwork("9999999999999999"))
    }
}
