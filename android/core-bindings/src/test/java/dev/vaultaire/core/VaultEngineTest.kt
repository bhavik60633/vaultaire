package dev.vaultaire.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Component 10 (milestone M2): the bridge from the Kotlin app to the Rust vault engine
 * (ADR-001, ADR-008; crypto spec §5.1, §11).
 *
 * These tests call the real Rust core, built for the computer running the tests. The phone's
 * hardware keys are played by ordinary Java crypto keys with the same shape.
 *
 * What they fix in place:
 * - a vault is created, locked, and opened again by password, fingerprint or Recovery Key;
 * - passwords and Recovery Keys handed to the engine are wiped from memory afterwards;
 * - every failure arrives as one typed error with a short code, never with the secret in it;
 * - every password attempt uses the hardware key exactly once;
 * - software keys are refused unless the build allows them;
 * - a locked session refuses to do anything;
 * - the security log and settings come across the bridge intact.
 */
class VaultEngineTest {

    private val password = "correct horse battery staple 42"
    private val dir: File = Files.createTempDirectory("vaultaire-bridge").toFile()
    private val file = File(dir, "vault.db")
    private val now = 1_760_000_000L

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** Plays `K_pwd`: an HMAC key, counting how often it is used. */
    private class JavaPwdKey(override val level: KeyLevel = KeyLevel.TEE) : PwdKey {
        private val key = SecretKeySpec(ByteArray(32) { 0x5a }, "HmacSHA256")
        var calls = 0
        override fun hmac(input: ByteArray): ByteArray {
            calls++
            return Mac.getInstance("HmacSHA256").run {
                init(key)
                doFinal(input)
            }
        }
    }

    /** Plays `K_bio`: an AES-GCM key that seals and releases the slot key. */
    private class JavaBioKey(override val level: KeyLevel = KeyLevel.TEE) : BioKey {
        private val key: SecretKey = KeyGenerator.getInstance("AES").run {
            init(256)
            generateKey()
        }
        override fun seal(slotKey: ByteArray): SealedSlotKey {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            return SealedSlotKey(iv, cipher.doFinal(slotKey))
        }
        override fun open(sealed: SealedSlotKey): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed.iv))
            return cipher.doFinal(sealed.blob)
        }
    }

    private fun wiped(chars: CharArray) = chars.all { it == '\u0000' }

    private fun engine(allowSoftwareKeys: Boolean = false) = VaultEngine(file, allowSoftwareKeys)

    @Test
    fun aVaultIsCreatedLockedAndOpenedAgainWithThePassword() {
        val pwd = JavaPwdKey()
        assertFalse(engine().exists())
        val created = engine().create(password.toCharArray(), pwd, null, now)
        assertTrue(engine().exists())
        assertTrue(created.recoveryKey.isNotEmpty())
        assertFalse(created.session.isLocked)
        created.session.lock()
        assertTrue(created.session.isLocked)

        val session = engine().unlockWithPassword(password.toCharArray(), pwd)
        assertFalse(session.isLocked)
        session.lock()
    }

    @Test
    fun theFingerprintOpensTheVault() {
        val pwd = JavaPwdKey()
        val bio = JavaBioKey()
        engine().create(password.toCharArray(), pwd, bio, now).session.lock()
        val session = engine().unlockWithBiometric(bio)
        assertEquals(LogEvent.UNLOCK_BIOMETRIC, session.securityLog().last().event)
        session.lock()
    }

    @Test
    fun theRecoveryKeyOpensTheVaultWithoutThePhonesKeys() {
        val created = engine().create(password.toCharArray(), JavaPwdKey(), null, now)
        val recoveryKey = created.recoveryKey.copyOf()
        created.session.lock()
        val session = engine().unlockWithRecoveryKey(password.toCharArray(), recoveryKey)
        assertEquals(LogEvent.UNLOCK_RECOVERY, session.securityLog().last().event)
        assertTrue("the Recovery Key is wiped after use", wiped(recoveryKey))
        session.lock()
    }

    @Test
    fun passwordsAreWipedFromMemoryAfterUse() {
        val pwd = JavaPwdKey()
        val forCreate = password.toCharArray()
        engine().create(forCreate, pwd, null, now).session.lock()
        assertTrue(wiped(forCreate))

        val forUnlock = password.toCharArray()
        engine().unlockWithPassword(forUnlock, pwd).lock()
        assertTrue(wiped(forUnlock))

        val wrong = "a completely different passphrase 7".toCharArray()
        assertThrows(VaultException::class.java) { engine().unlockWithPassword(wrong, pwd) }
        assertTrue("wiped even when it fails", wiped(wrong))
    }

    @Test
    fun aWrongPasswordGivesOneShortErrorWithoutThePassword() {
        val pwd = JavaPwdKey()
        engine().create(password.toCharArray(), pwd, null, now).session.lock()
        val error = assertThrows(VaultException::class.java) {
            engine().unlockWithPassword("a completely different passphrase 7".toCharArray(), pwd)
        }
        assertEquals("unlock_failed", error.code)
        assertFalse(error.message.orEmpty().contains("different passphrase"))
    }

    @Test
    fun everyPasswordAttemptUsesTheHardwareKeyOnce() {
        val pwd = JavaPwdKey()
        engine().create(password.toCharArray(), pwd, null, now).session.lock()
        val before = pwd.calls
        assertThrows(VaultException::class.java) {
            engine().unlockWithPassword("a completely different passphrase 7".toCharArray(), pwd)
        }
        engine().unlockWithPassword(password.toCharArray(), pwd).lock()
        assertEquals(2, pwd.calls - before)
    }

    @Test
    fun aWeakPasswordIsRefusedAndNothingIsWritten() {
        val error = assertThrows(VaultException::class.java) {
            engine().create("password1234".toCharArray(), JavaPwdKey(), null, now)
        }
        assertEquals("weak_password", error.code)
        assertFalse(engine().exists())
    }

    @Test
    fun softwareKeysAreRefusedUnlessTheBuildAllowsThem() {
        val software = JavaPwdKey(KeyLevel.SOFTWARE)
        val error = assertThrows(VaultException::class.java) {
            engine(allowSoftwareKeys = false).create(password.toCharArray(), software, null, now)
        }
        assertEquals("insecure_device", error.code)
        assertFalse(engine().exists())

        engine(allowSoftwareKeys = true).create(password.toCharArray(), software, null, now).session.lock()
        val refused = assertThrows(VaultException::class.java) {
            engine(allowSoftwareKeys = false).unlockWithPassword(password.toCharArray(), software)
        }
        assertEquals("a release build never opens it", "insecure_device", refused.code)
    }

    @Test
    fun aMissingVaultIsReportedAsNotFound() {
        val error = assertThrows(VaultException::class.java) {
            engine().unlockWithPassword(password.toCharArray(), JavaPwdKey())
        }
        assertEquals("not_found", error.code)
    }

    @Test
    fun aLockedSessionRefusesEverything() {
        val session = engine().create(password.toCharArray(), JavaPwdKey(), null, now).session
        session.lock()
        val error = assertThrows(VaultException::class.java) { session.securityLog() }
        assertEquals("locked", error.code)
        assertThrows(VaultException::class.java) { session.settings() }
        session.lock()
    }

    @Test
    fun theLogAndSettingsCrossTheBridge() {
        val pwd = JavaPwdKey()
        engine().create(password.toCharArray(), pwd, null, now).session.lock()
        val session = engine().unlockWithPassword(password.toCharArray(), pwd)
        session.recordFailedAttempts(3)
        val log = session.securityLog()
        assertEquals(LogEvent.UNLOCK_PASSWORD, log.first().event)
        assertEquals(LogEvent.FAILED_ATTEMPTS, log.last().event)
        assertEquals(3L, log.last().count)

        val settings = session.settings()
        assertEquals(120L, settings.autoLockS)
        assertEquals(30L, settings.backgroundLockS)
        assertEquals(30L, settings.clipboardTtlS)
        assertEquals(60L, settings.criticalWindowS)

        val weaker = settings.copy(autoLockS = 900L)
        val error = assertThrows(VaultException::class.java) { session.updateSettings(weaker, null) }
        assertEquals("password_required", error.code)
        val typed = password.toCharArray()
        session.updateSettings(weaker, typed)
        assertTrue(wiped(typed))
        assertEquals(900L, session.settings().autoLockS)
        session.lock()
    }
}
