package dev.vaultaire.core

/** An entry's id: 16 bytes, written as 32 lowercase hex characters. */
@JvmInline
value class EntryId(val hex: String) {
    fun bytes(): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    companion object {
        fun of(bytes: ByteArray): EntryId = EntryId(bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) })
    }
}

enum class CardKind { DEBIT, CREDIT, OTHER }

enum class PinPurpose { LOCKER, DEMAT_TPIN, APP_LOGIN, ATM_PIN, MPIN, TPIN, DOOR_ALARM, OTHER }

/** A secret value handed to the vault: new characters (wiped once saved), unchanged, or none. */
sealed interface SecretInput {
    data object Keep : SecretInput
    data object Empty : SecretInput
    class Set(val value: CharArray) : SecretInput
}

data class CardInput(
    val bankId: String?,
    val kind: CardKind,
    val product: String? = null,
    val nickname: String? = null,
    val holder: String? = null,
    val number: SecretInput = SecretInput.Empty,
    val expiry: String? = null,
    val network: String? = null,
    val cvv: SecretInput = SecretInput.Empty,
    val userNote: String? = null,
    val customerCare: String? = null,
)

data class PinInput(
    val purpose: PinPurpose,
    val label: String,
    val value: SecretInput,
    val linkedTo: EntryId? = null,
)

data class BankAccountInput(
    val bankId: String? = null,
    val holder: String? = null,
    val accountNumber: SecretInput = SecretInput.Empty,
    val ifsc: String? = null,
    val branch: String? = null,
    val customerId: SecretInput = SecretInput.Empty,
    val netbankingUser: SecretInput = SecretInput.Empty,
    val netbankingPassword: SecretInput = SecretInput.Empty,
    val transactionPassword: SecretInput = SecretInput.Empty,
    val appPassword: SecretInput = SecretInput.Empty,
    val mpin: SecretInput = SecretInput.Empty,
    val tpin: SecretInput = SecretInput.Empty,
    val upiPin: SecretInput = SecretInput.Empty,
    val registeredMobile: String? = null,
    val rmName: String? = null,
    val rmPhone: String? = null,
    val conciergePhone: String? = null,
)

enum class EntryKind { CARD, PIN, BANK_ACCOUNT, OTHER }

/** What lists show for an entry: never a secret value (threat-model I12). */
data class EntrySummary(
    val id: EntryId,
    val kind: EntryKind,
    val title: String,
    val subtitle: String?,
    val bankId: String?,
    val cardKind: CardKind?,
    val last4: String?,
    val network: String?,
    val pinPurpose: PinPurpose?,
    val favourite: Boolean,
    val hasCvv: Boolean,
    val linkedTo: List<EntryId>,
    val product: String?,
    val nickname: String?,
    val userNote: String?,
    val updatedAtS: Long,
    val lastUsedAtS: Long?,
    /** Cards only: how the card is drawn. */
    val finish: CardFinish? = null,
)

/** A card's finish, in the vault's order (vault-format §5.1). */
enum class CardFinish(val label: String) {
    OBSIDIAN("Obsidian"),
    TITANIUM("Titanium"),
    PLATINUM("Platinum"),
    IVORY("Ivory"),
    CHAMPAGNE("Champagne"),
}

/** One row of an entry's page; [value] only for normal (sensitivity 1) fields. */
data class FieldView(
    val key: String,
    val label: String,
    val kind: Int,
    val sensitivity: Int,
    val copyable: Boolean,
    val value: String?,
)

data class EntryDetails(val summary: EntrySummary, val fields: List<FieldView>)

data class BankGroup(val bankId: String?, val name: String, val total: Int, val credit: Int, val debit: Int)

sealed interface Scope {
    data object All : Scope
    data object Unassigned : Scope
    data class Bank(val bankId: String) : Scope
}

enum class CardFilter { ALL, CREDIT, DEBIT }

data class CatalogueBank(val id: String, val name: String, val fullName: String)
