// Phase-0 spike: THROWAWAY CODE. Not product code; see spikes/phase0/README.md.
package dev.vaultaire.spike

import android.app.Activity
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.InvalidAlgorithmParameterException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Creates and exercises K_bio and K_pwd exactly as crypto-spec §4.2 and §4.3 define them
 * (ADR-001 criterion 5). The "SK" wrapped here is random test data, not a real secret.
 */
class KeystoreProbe(private val activity: Activity, private val log: (String) -> Unit) {

    private val prefs = activity.getSharedPreferences("spike", Context.MODE_PRIVATE)
    private val random = SecureRandom()

    private fun keyStore() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    private fun key(alias: String) = keyStore().getKey(alias, null) as SecretKey?

    fun environment() {
        val am = activity.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val bm = activity.getSystemService(BiometricManager::class.java)
        val km = activity.getSystemService(KeyguardManager::class.java)
        log("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        log("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}, RAM: ${mem.totalMem / (1024 * 1024)} MiB")
        log("Secure lock screen: ${km.isDeviceSecure}")
        log("Class 3 biometric: ${canAuth(bm.canAuthenticate(Authenticators.BIOMETRIC_STRONG))}")
        log("Device credential: ${canAuth(bm.canAuthenticate(Authenticators.DEVICE_CREDENTIAL))}")
        log("StrongBox feature: ${activity.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)}")
    }

    fun isKeyWrapped(): Boolean {
        return prefs.contains("bio_blob") && prefs.contains("bio_iv") && key(BIO) != null
    }

    fun hasStrongBiometrics(): Boolean {
        val bm = activity.getSystemService(BiometricManager::class.java) ?: return false
        return bm.canAuthenticate(Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun unlockWithBiometric(onSuccess: (ByteArray) -> Unit, onError: (String) -> Unit) {
        if (!isKeyWrapped()) {
            createKeys()
            wrapWithBiometric(
                onSuccess = { unlockWithBiometric(onSuccess, onError) },
                onError = onError
            )
            return
        }
        val key = key(BIO) ?: return onError("No K_bio found in Keystore")
        val iv = prefs.getString("bio_iv", null) ?: return onError("No biometric IV stored")
        val cipher = Cipher.getInstance(AES_GCM)
        try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, unb64(iv)))
        } catch (e: KeyPermanentlyInvalidatedException) {
            return onError("K_bio permanently invalidated (enrolment changed or screen lock removed)")
        } catch (e: Exception) {
            return onError("K_bio init failed: ${name(e)}")
        }
        prompt("Unlock Vaultaire", Authenticators.BIOMETRIC_STRONG, BiometricPrompt.CryptoObject(cipher), onError) { result ->
            val c = result.cryptoObject?.cipher ?: return@prompt onError("Missing cipher in CryptoObject")
            try {
                c.updateAAD(unb64(prefs.getString("vault_id", "")!!) + "KS-BIO".toByteArray())
                val sk = c.doFinal(unb64(prefs.getString("bio_blob", "")!!))
                onSuccess(sk)
            } catch (e: Exception) {
                onError("Failed to unwrap SK: ${name(e)}")
            }
        }
    }

    fun createKeys() {
        keyStore().apply { deleteEntry(BIO); deleteEntry(PWD) }
        prefs.edit().clear().apply()

        val bio = generate("K_bio", KeyProperties.KEY_ALGORITHM_AES) { strongBox ->
            KeyGenParameterSpec.Builder(BIO, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                .setInvalidatedByBiometricEnrollment(true)
                .setUnlockedDeviceRequired(true)
                .setIsStrongBoxBacked(strongBox)
                .build()
        }
        val pwd = generate("K_pwd", KeyProperties.KEY_ALGORITHM_HMAC_SHA256) { strongBox ->
            KeyGenParameterSpec.Builder(PWD, KeyProperties.PURPOSE_SIGN)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(30, KeyProperties.AUTH_DEVICE_CREDENTIAL)
                .setUnlockedDeviceRequired(true)
                .setIsStrongBoxBacked(strongBox)
                .build()
        }
        bio?.let { log("K_bio: ${describe(it)}") }
        pwd?.let { log("K_pwd: ${describe(it)}") }
    }

    /** KS-BIO creation: a fresh SK is sealed by K_bio inside a Class 3 biometric prompt. */
    fun wrapWithBiometric(onSuccess: (() -> Unit)? = null, onError: ((String) -> Unit)? = null) {
        val key = key(BIO) ?: run {
            val msg = "No K_bio. Create the keys first."
            log(msg)
            onError?.invoke(msg)
            return
        }
        val cipher = Cipher.getInstance(AES_GCM)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key)
        } catch (e: Exception) {
            val msg = "K_bio init failed: ${name(e)}"
            log(msg)
            onError?.invoke(msg)
            return
        }
        prompt("Enroll Vaultaire Biometrics", Authenticators.BIOMETRIC_STRONG, BiometricPrompt.CryptoObject(cipher), onError) { result ->
            val sk = ByteArray(32).also(random::nextBytes)
            val vaultId = ByteArray(16).also(random::nextBytes)
            val c = result.cryptoObject!!.cipher!!
            c.updateAAD(vaultId + "KS-BIO".toByteArray())
            val blob = c.doFinal(sk)
            prefs.edit()
                .putString("vault_id", b64(vaultId))
                .putString("bio_iv", b64(c.iv))
                .putString("bio_blob", b64(blob))
                .putString("sk_sha256", b64(sha256(sk)))
                .apply()
            sk.fill(0)
            log("Wrapped: iv ${c.iv.size} bytes, blob ${blob.size} bytes")
            onSuccess?.invoke()
        }
    }

    /** KS-BIO unlock: the biometric result is the key release itself (threat-model I7). */
    fun unwrapWithBiometric() = unwrap(Authenticators.BIOMETRIC_STRONG, expectSuccess = true)

    /** Spec §4.2: K_bio must not accept the device credential. This must fail. */
    fun unwrapWithPasscode() = unwrap(Authenticators.DEVICE_CREDENTIAL, expectSuccess = false)

    private fun unwrap(authenticators: Int, expectSuccess: Boolean) {
        val key = key(BIO) ?: return log("No K_bio. Create the keys first.")
        val iv = prefs.getString("bio_iv", null) ?: return log("Nothing wrapped yet. Wrap first.")
        val cipher = Cipher.getInstance(AES_GCM)
        try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, unb64(iv)))
        } catch (e: KeyPermanentlyInvalidatedException) {
            return log("K_bio is permanently invalidated (enrolment changed or lock screen removed)")
        } catch (e: Exception) {
            return log("K_bio init failed: ${name(e)}")
        }
        val label = if (expectSuccess) "Unwrap with fingerprint" else "Unwrap with PASSCODE (must fail)"
        try {
            prompt(label, authenticators, BiometricPrompt.CryptoObject(cipher)) { result ->
                val c = result.cryptoObject!!.cipher!!
                try {
                    c.updateAAD(unb64(prefs.getString("vault_id", "")!!) + "KS-BIO".toByteArray())
                    val sk = c.doFinal(unb64(prefs.getString("bio_blob", "")!!))
                    val match = sha256(sk).contentEquals(unb64(prefs.getString("sk_sha256", "")!!))
                    sk.fill(0)
                    log(if (expectSuccess) "Unwrapped, SK matches: $match" else "FAIL: the passcode released K_bio (match=$match)")
                } catch (e: Exception) {
                    log(if (expectSuccess) "Unwrap failed: ${name(e)}" else "PASS: key refused after passcode (${name(e)})")
                }
            }
        } catch (e: Exception) {
            log(if (expectSuccess) "Prompt refused: ${name(e)}" else "PASS: framework refused the passcode prompt (${name(e)})")
        }
    }

    /** KS-PWD: one HMAC inside Keystore per password guess, gated by the device credential. */
    fun hmacWithPasswordKey() {
        val key = key(PWD) ?: return log("No K_pwd. Create the keys first.")
        val hwIn = ByteArray(32).also(random::nextBytes)
        fun attempt(): Boolean {
            val mac = Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256)
            val start = System.nanoTime()
            mac.init(key)
            val out = mac.doFinal(hwIn)
            log("HMAC ok: ${out.size} bytes in ${(System.nanoTime() - start) / 1_000_000.0} ms")
            return true
        }
        try {
            attempt()
            log("  (used the 30 s window from an earlier credential prompt)")
        } catch (e: UserNotAuthenticatedException) {
            log("Needs the device credential; prompting")
            prompt("Confirm with your PIN, pattern or password", Authenticators.DEVICE_CREDENTIAL, null) {
                try {
                    attempt(); attempt()
                } catch (e: Exception) {
                    log("HMAC failed after the prompt: ${name(e)}")
                }
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            log("K_pwd is permanently invalidated")
        } catch (e: Exception) {
            log("HMAC failed: ${name(e)}")
        }
    }

    /** For the invalidation tests: run after adding a fingerprint, or after removing the lock screen. */
    fun checkKeys() {
        val ks = keyStore()
        for ((label, alias) in listOf("K_bio" to BIO, "K_pwd" to PWD)) {
            if (!ks.containsAlias(alias)) {
                log("$label: not present (never created, or deleted by the system)")
                continue
            }
            val key = key(alias)!!
            val state = try {
                if (alias == BIO) {
                    Cipher.getInstance(AES_GCM).init(Cipher.ENCRYPT_MODE, key)
                    "present, usable after a biometric"
                } else {
                    Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256).init(key)
                    "present, inside the 30 s credential window"
                }
            } catch (e: KeyPermanentlyInvalidatedException) {
                "INVALIDATED"
            } catch (e: UserNotAuthenticatedException) {
                "present, needs the device credential"
            } catch (e: Exception) {
                "error: ${name(e)}"
            }
            log("$label: $state")
        }
    }

    fun deleteKeys() {
        keyStore().apply { deleteEntry(BIO); deleteEntry(PWD) }
        prefs.edit().clear().apply()
        log("Test keys and wrapped test data deleted")
    }

    private fun generate(label: String, algorithm: String, spec: (Boolean) -> KeyGenParameterSpec): SecretKey? {
        for (strongBox in listOf(true, false)) {
            try {
                return KeyGenerator.getInstance(algorithm, ANDROID_KEYSTORE).run {
                    init(spec(strongBox))
                    generateKey()
                }
            } catch (e: StrongBoxUnavailableException) {
                log("$label: no StrongBox; falling back to TEE")
            } catch (e: Exception) {
                // Finding: e.g. an every-use biometric key cannot be created with no biometric enrolled.
                log("$label: creation failed${if (strongBox) " (StrongBox attempt)" else ""}: ${name(e)}")
                if (!strongBox || e is InvalidAlgorithmParameterException) return null
            }
        }
        return null
    }

    private fun describe(key: SecretKey): String {
        val info = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        val level = when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> "SOFTWARE (spec §5.2: the app must refuse)"
            KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> "unknown secure hardware"
            else -> "unknown"
        }
        return "$level; auth type ${info.userAuthenticationType}, " +
            "timeout ${info.userAuthenticationValidityDurationSeconds} s, " +
            "invalidated by enrolment ${info.isInvalidatedByBiometricEnrollment}"
    }

    private fun prompt(
        title: String,
        authenticators: Int,
        crypto: BiometricPrompt.CryptoObject?,
        onError: ((String) -> Unit)? = null,
        onSuccess: (BiometricPrompt.AuthenticationResult) -> Unit,
    ) {
        val executor = activity.mainExecutor
        val builder = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setSubtitle("Vaultaire zero-knowledge hardware authentication")
            .setAllowedAuthenticators(authenticators)
        if ((authenticators and Authenticators.DEVICE_CREDENTIAL) == 0) {
            builder.setNegativeButton("Cancel", executor) { _, _ ->
                log("  cancelled")
                onError?.invoke("Authentication cancelled")
            }
        }
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                try {
                    onSuccess(result)
                } catch (e: Exception) {
                    val msg = "failed after authentication: ${name(e)}"
                    log("  $msg")
                    onError?.invoke(msg)
                }
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                log("  prompt ended: $errString ($errorCode)")
                onError?.invoke(errString.toString())
            }
            override fun onAuthenticationFailed() {
                log("  not recognised")
            }
        }
        val prompt = builder.build()
        if (crypto != null) {
            prompt.authenticate(crypto, CancellationSignal(), executor, callback)
        } else {
            prompt.authenticate(CancellationSignal(), executor, callback)
        }
    }

    private fun canAuth(code: Int) = when (code) {
        BiometricManager.BIOMETRIC_SUCCESS -> "available"
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "none enrolled"
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "no hardware"
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "hardware unavailable"
        else -> "code $code"
    }

    private fun name(e: Exception) = "${e.javaClass.simpleName}: ${e.message}"
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val AES_GCM = "AES/GCM/NoPadding"
        const val BIO = "spike_k_bio"
        const val PWD = "spike_k_pwd"
    }
}
