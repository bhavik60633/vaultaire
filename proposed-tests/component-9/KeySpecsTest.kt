package dev.vaultaire.security

import android.security.keystore.KeyProperties
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Component 9 (milestone M2): the two hardware keys in Android Keystore (crypto spec §4.2, §4.3;
 * threat-model I3, I7, T03, T27).
 *
 * - The fingerprint key (`K_bio`) works only after a fresh strong (Class 3) fingerprint, every
 *   single time. The phone's PIN or pattern can never use it, and enrolling a new fingerprint
 *   destroys it, so someone who adds their own finger cannot open the vault.
 * - The password key (`K_pwd`) needs the phone's screen lock (one prompt covers 30 seconds of
 *   retries) and is used once per password attempt, so guesses cannot be made off the phone.
 * - Both keys work only while the phone is unlocked, and use the separate secure chip
 *   (StrongBox) when the phone has one.
 */
@RunWith(RobolectricTestRunner::class)
class KeySpecsTest {

    @Test
    fun theFingerprintKeyNeedsAFreshStrongFingerprintEveryTime() {
        val spec = KeySpecs.bio("vlt.v1.bio.0123456789abcdef", strongBox = true)
        assertEquals("vlt.v1.bio.0123456789abcdef", spec.keystoreAlias)
        assertEquals(KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT, spec.purposes)
        assertEquals(256, spec.keySize)
        assertArrayEquals(arrayOf(KeyProperties.BLOCK_MODE_GCM), spec.blockModes)
        assertArrayEquals(arrayOf(KeyProperties.ENCRYPTION_PADDING_NONE), spec.encryptionPaddings)
        assertTrue(spec.isRandomizedEncryptionRequired)
        assertTrue(spec.isUserAuthenticationRequired)
        assertEquals("every use", 0, spec.userAuthenticationValidityDurationSeconds)
        assertEquals(
            "strong fingerprint only, never the phone's PIN",
            KeyProperties.AUTH_BIOMETRIC_STRONG,
            spec.userAuthenticationType,
        )
        assertTrue("a new fingerprint destroys the key", spec.isInvalidatedByBiometricEnrollment)
        assertTrue(spec.isUnlockedDeviceRequired)
        assertTrue(spec.isStrongBoxBacked)
    }

    @Test
    fun thePasswordKeyNeedsTheScreenLockAndSignsOncePerAttempt() {
        val spec = KeySpecs.pwd("vlt.v1.pwd.0123456789abcdef", strongBox = true)
        assertEquals("vlt.v1.pwd.0123456789abcdef", spec.keystoreAlias)
        assertEquals(KeyProperties.PURPOSE_SIGN, spec.purposes)
        assertTrue(spec.digests.contains(KeyProperties.DIGEST_SHA256))
        assertTrue(spec.isUserAuthenticationRequired)
        assertEquals(30, spec.userAuthenticationValidityDurationSeconds)
        assertEquals(KeyProperties.AUTH_DEVICE_CREDENTIAL, spec.userAuthenticationType)
        assertTrue(spec.isUnlockedDeviceRequired)
        assertTrue(spec.isStrongBoxBacked)
    }

    @Test
    fun phonesWithoutASecureChipUseTheTrustedEnvironment() {
        assertFalse(KeySpecs.bio("vlt.v1.bio.a", strongBox = false).isStrongBoxBacked)
        assertFalse(KeySpecs.pwd("vlt.v1.pwd.a", strongBox = false).isStrongBoxBacked)
    }

    @Test
    fun theTwoKeysNeverShareAnAlias() {
        val slotId = ByteArray(16) { it.toByte() }
        assertNotEquals(KeySpecs.bioAlias(slotId), KeySpecs.pwdAlias(slotId))
        assertEquals("vlt.v1.bio.000102030405060708090a0b0c0d0e0f", KeySpecs.bioAlias(slotId))
        assertEquals("vlt.v1.pwd.000102030405060708090a0b0c0d0e0f", KeySpecs.pwdAlias(slotId))
    }
}
