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
import uniffi.vault_ffi.FfiSettings
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
