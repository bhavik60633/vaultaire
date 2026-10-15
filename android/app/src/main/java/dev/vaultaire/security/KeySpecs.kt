package dev.vaultaire.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/** The two Android Keystore keys (crypto spec §4.2, §4.3; vault-format §4.4 aliases). */
object KeySpecs {

    /**
     * `K_bio`: AES-256-GCM, usable only right after a strong (Class 3) fingerprint, every time;
     * destroyed by a new fingerprint enrolment. Never accepts the phone's PIN.
     */
    fun bio(alias: String, strongBox: Boolean): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true)
            .setUnlockedDeviceRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()

    /** `K_pwd`: HMAC-SHA256, usable for 30 s after the phone's screen-lock credential. */
    fun pwd(alias: String, strongBox: Boolean): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(30, KeyProperties.AUTH_DEVICE_CREDENTIAL)
            .setUnlockedDeviceRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()

    fun bioAlias(slotId: ByteArray): String = "vlt.v1.bio." + hex(slotId)

    fun pwdAlias(slotId: ByteArray): String = "vlt.v1.pwd." + hex(slotId)

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
