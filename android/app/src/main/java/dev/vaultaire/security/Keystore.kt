package dev.vaultaire.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import dev.vaultaire.core.BioKey
import dev.vaultaire.core.KeyLevel
import dev.vaultaire.core.PwdKey
import dev.vaultaire.core.SealedSlotKey
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/**
 * Android Keystore access for the two hardware keys (crypto spec §4.2, §4.3, §5.2). Keys are
 * created in StrongBox when the phone has one, otherwise in the TEE, and their actual protection
 * level is read back and handed to the core, which refuses software keys in release builds.
 */
object Keystore {

    private const val PROVIDER = "AndroidKeyStore"
    private const val PROBE_ALIAS = "vlt.v1.probe"

    private fun store(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    fun load(alias: String): SecretKey? = store().getKey(alias, null) as? SecretKey

    fun delete(alias: String) {
        store().deleteEntry(alias)
    }

    fun createPwdKey(slotId: ByteArray): KeystorePwdKey {
        val alias = KeySpecs.pwdAlias(slotId)
        val key = generate(KeyProperties.KEY_ALGORITHM_HMAC_SHA256) { KeySpecs.pwd(alias, it) }
        return KeystorePwdKey(key, slotId)
    }

    fun loadPwdKey(alias: String): KeystorePwdKey? = load(alias)?.let { KeystorePwdKey(it, null) }

    fun createBioKey(slotId: ByteArray): SecretKey {
        val alias = KeySpecs.bioAlias(slotId)
        return generate(KeyProperties.KEY_ALGORITHM_AES) { KeySpecs.bio(alias, it) }
    }

    /** The protection level new keys get on this phone, from a throwaway key. */
    fun probeLevel(): KeyLevel {
        val key = generate(KeyProperties.KEY_ALGORITHM_AES) { strongBox ->
            KeyGenParameterSpec.Builder(PROBE_ALIAS, KeyProperties.PURPOSE_ENCRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setIsStrongBoxBacked(strongBox)
                .build()
        }
        return try {
            level(key)
        } finally {
            delete(PROBE_ALIAS)
        }
    }

    fun level(key: SecretKey): KeyLevel {
        val info = SecretKeyFactory.getInstance(key.algorithm, PROVIDER)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        return when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyLevel.STRONG_BOX
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyLevel.TEE
            else -> KeyLevel.SOFTWARE
        }
    }

    private fun generate(algorithm: String, spec: (strongBox: Boolean) -> KeyGenParameterSpec): SecretKey {
        val generator = KeyGenerator.getInstance(algorithm, PROVIDER)
        return try {
            generator.init(spec(true))
            generator.generateKey()
        } catch (e: StrongBoxUnavailableException) {
            generator.init(spec(false))
            generator.generateKey()
        }
    }
}

/** `K_pwd`: one HMAC per password attempt, inside the phone's hardware. */
class KeystorePwdKey(private val key: SecretKey, override val slotId: ByteArray?) : PwdKey {

    override val level: KeyLevel = Keystore.level(key)

    /** False when the phone's screen lock must be confirmed first (crypto spec §4.3). */
    fun usableNow(): Boolean =
        try {
            Mac.getInstance("HmacSHA256").init(key)
            true
        } catch (e: UserNotAuthenticatedException) {
            false
        }

    override fun hmac(input: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(key)
            doFinal(input)
        }
}

/**
 * `K_bio` while creating its slot: [cipher] was released by a fingerprint prompt for encryption,
 * and seals the slot key once.
 */
class SealingBioKey(
    override val level: KeyLevel,
    override val slotId: ByteArray,
    private val cipher: Cipher,
) : BioKey {
    override fun seal(slotKey: ByteArray): SealedSlotKey {
        cipher.updateAAD(BIO_AAD)
        return SealedSlotKey(cipher.iv, cipher.doFinal(slotKey))
    }

    override fun open(sealed: SealedSlotKey): ByteArray = throw IllegalStateException("seal only")
}

/**
 * `K_bio` at unlock: the fingerprint prompt released the cipher, which already opened the sealed
 * slot key. Hands that key to the core once, only for the slot it was opened from.
 */
class ReleasedBioKey(
    override val level: KeyLevel,
    private val slotKey: ByteArray,
    private val sealed: SealedSlotKey,
) : BioKey {
    override fun seal(slotKey: ByteArray): SealedSlotKey = throw IllegalStateException("open only")

    override fun open(sealed: SealedSlotKey): ByteArray {
        check(sealed.iv.contentEquals(this.sealed.iv) && sealed.blob.contentEquals(this.sealed.blob))
        return slotKey.copyOf()
    }
}

/** AES-GCM associated data for the sealed slot key (crypto spec §4.2). */
val BIO_AAD: ByteArray = "vlt/v1/bio-blob".toByteArray(Charsets.US_ASCII)
