package dev.vaultaire.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import uniffi.vault_ffi.BioKeyCallback
import uniffi.vault_ffi.FfiException
import uniffi.vault_ffi.FfiKeyLevel
import uniffi.vault_ffi.FfiSealed
import uniffi.vault_ffi.FfiSession
import uniffi.vault_ffi.FfiBankAccountInput
import uniffi.vault_ffi.FfiCardInput
import uniffi.vault_ffi.FfiPinInput
import uniffi.vault_ffi.FfiScope
import uniffi.vault_ffi.FfiSecret
import uniffi.vault_ffi.FfiSettings
import uniffi.vault_ffi.FfiSummary
import uniffi.vault_ffi.PwdKeyCallback

/** How well the phone protects a hardware key (crypto spec §5.2). */
enum class KeyLevel { SOFTWARE, TEE, STRONG_BOX }

/** The slot key as sealed by the fingerprint key: Keystore's GCM IV and the ciphertext. */
class SealedSlotKey(val iv: ByteArray, val blob: ByteArray)

/** `K_pwd` (crypto spec §4.3): one HMAC inside the phone's hardware per password attempt. */
interface PwdKey {
    val level: KeyLevel
    fun hmac(input: ByteArray): ByteArray

    /** The slot id ([VaultEngine.newSlotId]) this key's alias was made from, if any. */
    val slotId: ByteArray? get() = null
}

/** `K_bio` (crypto spec §4.2): seals the slot key, and releases it after a fingerprint. */
interface BioKey {
    val level: KeyLevel
    fun seal(slotKey: ByteArray): SealedSlotKey
    fun open(sealed: SealedSlotKey): ByteArray

    /** The slot id ([VaultEngine.newSlotId]) this key's alias was made from, if any. */
    val slotId: ByteArray? get() = null
}

/**
 * Read from the vault without unlocking it: the Keystore aliases of its slots, and the
 * fingerprint slot's sealed key, which the fingerprint prompt needs first. Nothing here is secret.
 */
class SlotInfo(val pwdAlias: String, val bioAlias: String?, val bioSealed: SealedSlotKey?)

/** Every vault failure, as the short code of crypto spec §11. Never contains a secret. */
class VaultException(val code: String) : Exception(code)

/** A new vault: its open session, and the Recovery Key to show once. */
class Created(val session: VaultSession, val recoveryKey: CharArray)

/** Security-log events, in registry order (vault-format §5.7). */
enum class LogEvent {
    UNLOCK_BIOMETRIC, UNLOCK_PASSWORD, UNLOCK_RECOVERY, FAILED_ATTEMPTS, BIOMETRIC_INVALIDATED,
    PASSWORD_CHANGED, RECOVERY_KEY_REGENERATED, VAULT_KEY_ROTATED, BACKUP_EXPORTED,
    BACKUP_IMPORTED, SETTING_WEAKENED, PERIODIC_CHECK_PASSED, PERIODIC_CHECK_FAILED,
}

class LogEntry(val atS: Long, val event: LogEvent, val count: Long?)

/** The security settings (vault-format §5.5). */
data class VaultSettings(
    val autoLockS: Long,
    val backgroundLockS: Long,
    val clipboardTtlS: Long,
    val criticalWindowS: Long,
    val passwordCheckDays: Long,
    val passwordOnly: Boolean,
    val eraseAfterFailures: Boolean,
    val backupReminderDays: Long,
)

/**
 * Opens and creates the vault file. Every password and Recovery Key handed in is wiped as soon
 * as the call returns, whether it succeeded or not (crypto spec §11).
 */
class VaultEngine(private val file: File, private val allowSoftwareKeys: Boolean) {

    fun exists(): Boolean = file.isFile

    fun slotInfo(): SlotInfo = bridge { uniffi.vault_ffi.slotInfo(file.path, allowSoftwareKeys) }.let {
        SlotInfo(it.pwdAlias, it.bioAlias, it.bioSealed?.let { s -> SealedSlotKey(s.iv, s.blob) })
    }

    fun create(password: CharArray, pwdKey: PwdKey, bioKey: BioKey?, nowS: Long): Created =
        withUtf8(password) { pw ->
            val ffi = bridge {
                uniffi.vault_ffi.createVault(
                    file.path, pw, PwdAdapter(pwdKey), bioKey?.let(::BioAdapter),
                    allowSoftwareKeys, nowS.toULong(),
                )
            }
            val key = ffi.takeRecoveryKey() ?: ByteArray(0)
            Created(VaultSession(ffi), fromUtf8(key))
        }

    fun unlockWithPassword(password: CharArray, pwdKey: PwdKey): VaultSession =
        withUtf8(password) { pw ->
            VaultSession(bridge {
                uniffi.vault_ffi.unlockWithPassword(file.path, pw, PwdAdapter(pwdKey), allowSoftwareKeys)
            })
        }

    fun unlockWithBiometric(bioKey: BioKey): VaultSession =
        VaultSession(bridge {
            uniffi.vault_ffi.unlockWithBiometric(file.path, BioAdapter(bioKey), allowSoftwareKeys)
        })

    fun unlockWithRecoveryKey(password: CharArray, recoveryKey: CharArray): VaultSession =
        withUtf8(password) { pw ->
            withUtf8(recoveryKey) { rk ->
                VaultSession(bridge {
                    uniffi.vault_ffi.unlockWithRecoveryKey(file.path, pw, rk, allowSoftwareKeys)
                })
            }
        }

    companion object {
        /** The network a card number's first digits belong to, if known (a suggestion only). */
        fun suggestNetwork(number: String): String? =
            uniffi.vault_ffi.suggestNetwork(number.filter { it.isDigit() }.take(6))

        /** The bundled bank catalogue (ADR-011), for the bank picker. */
        fun catalogue(): List<CatalogueBank> =
            uniffi.vault_ffi.catalogueBanks().map { CatalogueBank(it.id, it.name, it.fullName) }

        /** A fresh slot id from the Rust core's OS random source, to name a new Keystore key. */
        fun newSlotId(): ByteArray = bridge { uniffi.vault_ffi.newSlotId() }

        /** The strength policy alone (crypto spec §3.2); throws `weak_password`. */
        fun checkPasswordStrength(password: CharArray) {
            withUtf8(password) { pw -> bridge { uniffi.vault_ffi.checkPasswordStrength(pw) } }
        }
    }
}

/** An unlocked vault. After [lock], every call throws `locked`. */
class VaultSession internal constructor(private val ffi: FfiSession) {

    val isLocked: Boolean get() = ffi.isLocked()

    /** Locks the vault: Rust drops and zeroizes the session and every key in it. */
    fun lock() = ffi.lock()

    fun securityLog(): List<LogEntry> = bridge { ffi.securityLog() }.map {
        LogEntry(it.at.toLong(), LogEvent.entries[it.event.toInt() - 1], it.count?.toLong())
    }

    fun recordFailedAttempts(count: Int) = bridge { ffi.recordFailedAttempts(count.toULong()) }

    fun settings(): VaultSettings = bridge { ffi.settings() }.let {
        VaultSettings(
            autoLockS = it.autoLockS.toLong(),
            backgroundLockS = it.backgroundLockS.toLong(),
            clipboardTtlS = it.clipboardTtlS.toLong(),
            criticalWindowS = it.criticalWindowS.toLong(),
            passwordCheckDays = it.passwordCheckDays.toLong(),
            passwordOnly = it.passwordOnly,
            eraseAfterFailures = it.eraseAfterFailures,
            backupReminderDays = it.backupReminderDays.toLong(),
        )
    }

    fun updateSettings(settings: VaultSettings, password: CharArray?) {
        val ffiSettings = FfiSettings(
            autoLockS = settings.autoLockS.toULong(),
            backgroundLockS = settings.backgroundLockS.toULong(),
            clipboardTtlS = settings.clipboardTtlS.toULong(),
            criticalWindowS = settings.criticalWindowS.toULong(),
            passwordCheckDays = settings.passwordCheckDays.toULong(),
            passwordOnly = settings.passwordOnly,
            eraseAfterFailures = settings.eraseAfterFailures,
            backupReminderDays = settings.backupReminderDays.toULong(),
        )
        if (password == null) {
            bridge { ffi.updateSettings(ffiSettings, null) }
        } else {
            withUtf8(password) { pw -> bridge { ffi.updateSettings(ffiSettings, pw) } }
        }
    }

    fun periodicCheckDue(nowS: Long): Boolean = bridge { ffi.periodicCheckDue(nowS.toULong()) }

    fun periodicCheck(password: CharArray, nowS: Long) =
        withUtf8(password) { pw -> bridge { ffi.periodicCheck(pw, nowS.toULong()) } }

    fun changePassword(current: CharArray, new: CharArray, pwdKey: PwdKey) =
        withUtf8(current) { cur ->
            withUtf8(new) { next -> bridge { ffi.changePassword(cur, next, PwdAdapter(pwdKey)) } }
        }

    fun enrolBiometric(bioKey: BioKey, password: CharArray) =
        withUtf8(password) { pw -> bridge { ffi.enrolBiometric(BioAdapter(bioKey), pw) } }

    // ---------- entries (vault-format §5.10) ----------

    fun addCard(input: CardInput, nowS: Long): EntryId =
        withSecrets { wipe -> EntryId.of(bridge { ffi.addCard(input.toFfi(wipe), nowS.toULong()) }) }

    fun updateCard(id: EntryId, input: CardInput, nowS: Long) =
        withSecrets { wipe -> bridge { ffi.updateCard(id.bytes(), input.toFfi(wipe), nowS.toULong()) } }

    fun addPin(input: PinInput, nowS: Long): EntryId =
        withSecrets { wipe -> EntryId.of(bridge { ffi.addPin(input.toFfi(wipe), nowS.toULong()) }) }

    fun updatePin(id: EntryId, input: PinInput, nowS: Long) =
        withSecrets { wipe -> bridge { ffi.updatePin(id.bytes(), input.toFfi(wipe), nowS.toULong()) } }

    fun addBankAccount(input: BankAccountInput, nowS: Long): EntryId =
        withSecrets { wipe -> EntryId.of(bridge { ffi.addBankAccount(input.toFfi(wipe), nowS.toULong()) }) }

    fun updateBankAccount(id: EntryId, input: BankAccountInput, nowS: Long) =
        withSecrets { wipe -> bridge { ffi.updateBankAccount(id.bytes(), input.toFfi(wipe), nowS.toULong()) } }

    fun delete(id: EntryId) = bridge { ffi.deleteEntry(id.bytes()) }

    fun summaries(): List<EntrySummary> = bridge { ffi.summaries() }.map { it.toKotlin() }

    fun summary(id: EntryId): EntrySummary? = bridge { ffi.summary(id.bytes()) }?.toKotlin()

    fun details(id: EntryId): EntryDetails = bridge { ffi.details(id.bytes()) }.let { d ->
        EntryDetails(
            d.summary.toKotlin(),
            d.fields.map { FieldView(it.key, it.label, it.kind.toInt(), it.sensitivity.toInt(), it.copyable, it.value) },
        )
    }

    /** One value, on an explicit reveal or copy. The caller wipes the characters after use. */
    fun reveal(id: EntryId, key: String): CharArray = fromUtf8(bridge { ffi.reveal(id.bytes(), key) })

    fun setFavourite(id: EntryId, on: Boolean) = bridge { ffi.setFavourite(id.bytes(), on) }

    fun recordUse(id: EntryId, nowS: Long) = bridge { ffi.recordUse(id.bytes(), nowS.toULong()) }

    fun bankGroups(): List<BankGroup> = bridge { ffi.bankGroups() }.map {
        BankGroup(it.bankId, it.name, it.total.toInt(), it.credit.toInt(), it.debit.toInt())
    }

    fun cards(scope: Scope, filter: CardFilter): List<EntrySummary> =
        bridge { ffi.cards(scope.toFfi(), filter.ordinal.toUInt()) }.map { it.toKotlin() }

    fun search(query: String): List<EntrySummary> = bridge { ffi.search(query) }.map { it.toKotlin() }

    /** The id for a typed bank name: catalogue, one added before, or a new one. */
    fun addBank(name: String): String = bridge { ffi.addBank(name) }

    fun bankName(id: String): String? = bridge { ffi.bankName(id) }

    fun lastCardView(): Pair<Scope, CardFilter>? = bridge { ffi.lastCardView() }?.let { v ->
        val scope: Scope = when (val s = v.scope) {
            is FfiScope.All -> Scope.All
            is FfiScope.Unassigned -> Scope.Unassigned
            is FfiScope.Bank -> Scope.Bank(s.bankId)
        }
        scope to CardFilter.entries[v.filter.toInt()]
    }

    fun setLastCardView(scope: Scope, filter: CardFilter) =
        bridge { ffi.setLastCardView(scope.toFfi(), filter.ordinal.toUInt()) }

    fun clearLastCardView() = bridge { ffi.clearLastCardView() }
}

// ---------- plumbing ----------

private fun <T> bridge(call: () -> T): T =
    try {
        call()
    } catch (e: FfiException.Failed) {
        throw VaultException(e.code)
    } catch (e: FfiException) {
        throw VaultException("internal")
    }

/** UTF-8 bytes of [chars] without a String copy; both are wiped when [use] returns. */
private fun <T> withUtf8(chars: CharArray, use: (ByteArray) -> T): T {
    var bytes = ByteArray(0)
    try {
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val buffer: ByteBuffer = encoder.encode(CharBuffer.wrap(chars))
        bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        if (buffer.hasArray()) buffer.array().fill(0)
        return use(bytes)
    } catch (e: java.nio.charset.CharacterCodingException) {
        throw VaultException("password_length")
    } finally {
        bytes.fill(0)
        chars.fill('\u0000')
    }
}

/** Characters from UTF-8 bytes; the bytes are wiped. */
private fun fromUtf8(bytes: ByteArray): CharArray {
    try {
        val buffer = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes))
        val chars = CharArray(buffer.remaining())
        buffer.get(chars)
        if (buffer.hasArray()) buffer.array().fill('\u0000')
        return chars
    } finally {
        bytes.fill(0)
    }
}

private fun KeyLevel.toFfi(): FfiKeyLevel = when (this) {
    KeyLevel.SOFTWARE -> FfiKeyLevel.SOFTWARE
    KeyLevel.TEE -> FfiKeyLevel.TEE
    KeyLevel.STRONG_BOX -> FfiKeyLevel.STRONG_BOX
}

private class PwdAdapter(private val key: PwdKey) : PwdKeyCallback {
    override fun level(): FfiKeyLevel = key.level.toFfi()
    override fun slotId(): ByteArray? = key.slotId
    override fun hmac(input: ByteArray): ByteArray =
        try {
            key.hmac(input)
        } catch (e: Exception) {
            throw FfiException.Failed("unlock_failed")
        } finally {
            input.fill(0)
        }
}

private class BioAdapter(private val key: BioKey) : BioKeyCallback {
    override fun level(): FfiKeyLevel = key.level.toFfi()
    override fun slotId(): ByteArray? = key.slotId
    override fun seal(slotKey: ByteArray): FfiSealed =
        try {
            key.seal(slotKey).let { FfiSealed(it.iv, it.blob) }
        } catch (e: Exception) {
            throw FfiException.Failed("unlock_failed")
        } finally {
            slotKey.fill(0)
        }
    override fun open(sealed: FfiSealed): ByteArray =
        try {
            key.open(SealedSlotKey(sealed.iv, sealed.blob))
        } catch (e: Exception) {
            throw FfiException.Failed("unlock_failed")
        }
}

// ---------- entries: conversions ----------

/** Runs [use] with a list that collects every secret buffer it creates; all are wiped after. */
private fun <T> withSecrets(use: (MutableList<Any>) -> T): T {
    val toWipe = mutableListOf<Any>()
    try {
        return use(toWipe)
    } finally {
        for (item in toWipe) {
            when (item) {
                is ByteArray -> item.fill(0)
                is CharArray -> item.fill('\u0000')
            }
        }
    }
}

private fun SecretInput.toFfi(wipe: MutableList<Any>): FfiSecret = when (this) {
    SecretInput.Keep -> FfiSecret.Keep
    SecretInput.Empty -> FfiSecret.Empty
    is SecretInput.Set -> {
        wipe += value
        val bytes = utf8Of(value)
        wipe += bytes
        FfiSecret.Set(bytes)
    }
}

private fun utf8Of(chars: CharArray): ByteArray {
    val buffer = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(chars))
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    if (buffer.hasArray()) buffer.array().fill(0)
    return bytes
}

private fun CardInput.toFfi(wipe: MutableList<Any>) = FfiCardInput(
    bankId = bankId, kind = (kind.ordinal + 1).toUInt(), product = product, nickname = nickname,
    holder = holder, number = number.toFfi(wipe), expiry = expiry, network = network,
    cvv = cvv.toFfi(wipe), userNote = userNote, customerCare = customerCare,
)

private fun PinInput.toFfi(wipe: MutableList<Any>) = FfiPinInput(
    purpose = (purpose.ordinal + 1).toUInt(), label = label, value = value.toFfi(wipe),
    linkedTo = linkedTo?.bytes(),
)

private fun BankAccountInput.toFfi(wipe: MutableList<Any>) = FfiBankAccountInput(
    bankId = bankId, holder = holder, accountNumber = accountNumber.toFfi(wipe), ifsc = ifsc,
    branch = branch, customerId = customerId.toFfi(wipe), netbankingUser = netbankingUser.toFfi(wipe),
    netbankingPassword = netbankingPassword.toFfi(wipe),
    transactionPassword = transactionPassword.toFfi(wipe), appPassword = appPassword.toFfi(wipe),
    mpin = mpin.toFfi(wipe), tpin = tpin.toFfi(wipe), upiPin = upiPin.toFfi(wipe),
    registeredMobile = registeredMobile, rmName = rmName, rmPhone = rmPhone,
    conciergePhone = conciergePhone,
)

private fun Scope.toFfi(): FfiScope = when (this) {
    Scope.All -> FfiScope.All
    Scope.Unassigned -> FfiScope.Unassigned
    is Scope.Bank -> FfiScope.Bank(bankId)
}

private fun FfiSummary.toKotlin() = EntrySummary(
    id = EntryId.of(id),
    kind = EntryKind.entries[(kind.toInt() - 1).coerceIn(0, 3)],
    title = title,
    subtitle = subtitle,
    bankId = bankId,
    cardKind = cardKind?.let { CardKind.entries[it.toInt() - 1] },
    last4 = last4,
    network = network,
    pinPurpose = pinPurpose?.let { PinPurpose.entries[it.toInt() - 1] },
    favourite = favourite,
    hasCvv = hasCvv,
    linkedTo = linkedTo.map { EntryId.of(it) },
    product = product,
    nickname = nickname,
    userNote = userNote,
    updatedAtS = updatedAt.toLong(),
    lastUsedAtS = lastUsedAt?.toLong(),
)
