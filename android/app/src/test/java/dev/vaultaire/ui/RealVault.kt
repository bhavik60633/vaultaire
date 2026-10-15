package dev.vaultaire.ui

import android.content.Context
import dev.vaultaire.core.CardInput
import dev.vaultaire.core.CardKind
import dev.vaultaire.core.EntryId
import dev.vaultaire.core.KeyLevel
import dev.vaultaire.core.PwdKey
import dev.vaultaire.core.SecretInput
import dev.vaultaire.core.VaultEngine
import dev.vaultaire.core.VaultException
import dev.vaultaire.core.VaultSession
import dev.vaultaire.security.ClipboardGuard
import dev.vaultaire.security.Clock
import dev.vaultaire.security.Refusal
import dev.vaultaire.security.RevealGate
import java.io.File
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Component 14 (milestone M3): a real vault (the Rust engine) for the screen tests. Only the
 * phone's hardware is played: the password key is a plain HMAC key, and the fingerprint prompt
 * answers [fingerprintAccepted] and counts how often it was shown.
 */
class RealVault(context: Context, dir: File) : VaultGateway, VaultData {

    class TestClock(var nowMs: Long = 1_000_000L) : Clock {
        override fun elapsedMs(): Long = nowMs
        fun advanceS(seconds: Long) {
            nowMs += seconds * 1000
        }
    }

    private object Pwd : PwdKey {
        override val level = KeyLevel.TEE
        override fun hmac(input: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(ByteArray(32) { 0x5a }, "HmacSHA256"))
            doFinal(input)
        }
    }

    val password = "correct horse battery staple 42"
    val clock = TestClock()
    var fingerprintAccepted = true
    var prompts = 0
    var locks = 0

    private val engine = VaultEngine(File(dir, "vault.db"), allowSoftwareKeys = false)
    private var open: VaultSession? = engine.create(password.toCharArray(), Pwd, null, NOW).session

    override val unlocked = MutableStateFlow(true)
    override val revealGate = RevealGate(clock)
    override val clipboard = ClipboardGuard(context)

    // ---------- VaultData ----------

    override fun session(): VaultSession = open ?: throw VaultException("locked")

    override suspend fun confirmFingerprint(): Boolean {
        prompts++
        if (fingerprintAccepted) revealGate.biometricConfirmed()
        return fingerprintAccepted
    }

    override fun nowS(): Long = NOW + clock.nowMs / 1000

    // ---------- VaultGateway ----------

    override fun vaultExists(): Boolean = true

    override fun refusal(): Refusal? = null

    override suspend fun create(password: CharArray): CharArray = throw VaultException("vault_exists")

    override suspend fun enrolFingerprint(): Boolean = false

    override suspend fun unlockWithFingerprint(): Boolean {
        unlockWithPassword(password.toCharArray())
        return true
    }

    override suspend fun unlockWithPassword(password: CharArray) {
        open = engine.unlockWithPassword(password, Pwd)
        unlocked.value = true
    }

    override fun remainingDelayS(): Long = 0

    override fun failuresSinceLastUnlock(): Int = 0

    override fun lock() {
        locks++
        revealGate.reset()
        open?.lock()
        open = null
        unlocked.value = false
    }

    // ---------- test data ----------

    fun card(bank: String?, kind: CardKind, product: String, number: String): EntryId =
        session().addCard(
            CardInput(
                bankId = bank,
                kind = kind,
                product = product,
                holder = "ASHA RAO",
                number = SecretInput.Set(number.toCharArray()),
                expiry = "08/29",
                network = VaultEngine.suggestNetwork(number),
            ),
            NOW,
        )

    /** The owner's fixture (product plan §8): 5 SBI, 2 HDFC and 3 Axis cards. */
    fun fixture(): List<EntryId> = listOf(
        card("in.sbi", CardKind.CREDIT, "SBI Cashback", "4111111111111234"),
        card("in.sbi", CardKind.CREDIT, "SBI Cashback", "4111111111115678"),
        card("in.sbi", CardKind.CREDIT, "SBI SimplyCLICK", "5105105105102001"),
        card("in.sbi", CardKind.DEBIT, "SBI Classic Debit", "6074000000002002"),
        card("in.sbi", CardKind.DEBIT, "SBI Platinum Debit", "4000000000002003"),
        card("in.hdfc", CardKind.CREDIT, "HDFC Millennia", "5555555555554444"),
        card("in.hdfc", CardKind.DEBIT, "HDFC Debit", "4000000000003001"),
        card("in.axis", CardKind.CREDIT, "Axis Ace", "4000000000004001"),
        card("in.axis", CardKind.CREDIT, "Axis Magnus", "5200000000004002"),
        card("in.axis", CardKind.DEBIT, "Axis Debit", "6074000000004003"),
    )

    companion object {
        const val NOW = 1_760_000_000L
    }
}
