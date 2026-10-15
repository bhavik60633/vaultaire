package dev.vaultaire.ui

import android.app.KeyguardManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.vaultaire.core.KeyLevel
import dev.vaultaire.core.SealedSlotKey
import dev.vaultaire.core.VaultEngine
import dev.vaultaire.core.VaultException
import dev.vaultaire.core.VaultSession
import dev.vaultaire.security.AutoLock
import dev.vaultaire.security.BIO_AAD
import dev.vaultaire.security.ClipboardGuard
import dev.vaultaire.security.Clock
import dev.vaultaire.security.DevicePolicy
import dev.vaultaire.security.Keystore
import dev.vaultaire.security.KeystorePwdKey
import dev.vaultaire.security.PasswordThrottle
import dev.vaultaire.security.Prefs
import dev.vaultaire.security.Prompts
import dev.vaultaire.security.Refusal
import dev.vaultaire.security.ReleasedBioKey
import dev.vaultaire.security.RevealGate
import dev.vaultaire.security.RevealedValues
import dev.vaultaire.security.SealingBioKey
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * The real vault behind the screens: the Rust engine, Android Keystore, the fingerprint prompt,
 * the password waiting times and auto-lock. Lives as long as its activity; the activity is never
 * recreated for configuration changes, and leaving it locks the vault.
 */
class AppGateway(private val activity: FragmentActivity, private val debugBuild: Boolean) : VaultGateway, VaultData {

    private val app = activity.applicationContext
    private val prefs = Prefs(app)
    private val throttle = PasswordThrottle(prefs, Clock.system) {
        Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, 0)
    }
    private val file = File(app.noBackupFilesDir, "vault/vault.db")
    private val engine = VaultEngine(file, allowSoftwareKeys = debugBuild)
    private val ticker = Handler(Looper.getMainLooper())
    private var session: VaultSession? = null
    private var pendingPassword: CharArray? = null
    private var lastFailures = 0

    override val unlocked = MutableStateFlow(false)

    val autoLock = AutoLock(Clock.system) { closeSession() }
    override val revealGate = RevealGate(Clock.system)
    val revealed = RevealedValues(Clock.system)
    override val clipboard = ClipboardGuard(app)

    // ---------- VaultData ----------

    override fun session(): VaultSession = session ?: throw VaultException("locked")

    override fun nowS(): Long = System.currentTimeMillis() / 1000

    /**
     * A fresh strong fingerprint, bound to the fingerprint key through a CryptoObject so that the
     * prompt can't be answered by anything but the phone's sensor (crypto spec §4.2).
     */
    override suspend fun confirmFingerprint(): Boolean {
        return try {
            val info = withContext(Dispatchers.IO) { engine.slotInfo() }
            val alias = info.bioAlias ?: return false
            val sealed = info.bioSealed ?: return false
            val key = Keystore.load(alias) ?: return false
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed.iv))
                updateAAD(BIO_AAD)
            }
            val released = authenticate(Prompts.reveal(), BiometricPrompt.CryptoObject(cipher))
                ?.cryptoObject?.cipher ?: return false
            released.doFinal(sealed.blob).fill(0)
            revealGate.biometricConfirmed()
            autoLock.userActivity()
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun settingsChanged() {
        val settings = session?.settings() ?: return
        autoLock.setTimeouts(settings.autoLockS, settings.backgroundLockS)
        revealGate.criticalWindowS = settings.criticalWindowS
        clipboard.normalTtlS = settings.clipboardTtlS
    }

    override fun vaultExists(): Boolean = engine.exists()

    override fun refusal(): Refusal? {
        val secure = app.getSystemService(KeyguardManager::class.java).isDeviceSecure
        if (!secure) return Refusal.NO_SCREEN_LOCK
        val level = try {
            Keystore.probeLevel()
        } catch (e: Exception) {
            KeyLevel.SOFTWARE
        }
        return DevicePolicy.refusal(secure, level, debugBuild)
    }

    override suspend fun create(password: CharArray): CharArray {
        VaultEngine.checkPasswordStrength(password.copyOf())
        val slotId = VaultEngine.newSlotId()
        val pwdKey = Keystore.createPwdKey(slotId)
        try {
            confirmScreenLock(pwdKey)
            pendingPassword = password.copyOf()
            file.parentFile?.mkdirs()
            val created = withContext(Dispatchers.Default) {
                engine.create(password, pwdKey, null, System.currentTimeMillis() / 1000)
            }
            prefs.vaultPresent = true
            open(created.session)
            return created.recoveryKey
        } catch (e: Exception) {
            password.fill('\u0000')
            pendingPassword?.fill('\u0000')
            pendingPassword = null
            Keystore.delete(dev.vaultaire.security.KeySpecs.pwdAlias(slotId))
            throw e as? VaultException ?: VaultException("internal")
        }
    }

    override suspend fun enrolFingerprint(): Boolean {
        val password = pendingPassword ?: return false
        val current = session ?: return false
        return try {
            val slotId = VaultEngine.newSlotId()
            val key = Keystore.createBioKey(slotId)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            val released = authenticate(Prompts.enrol(), BiometricPrompt.CryptoObject(cipher))
                ?.cryptoObject?.cipher ?: return false
            val bio = SealingBioKey(Keystore.level(key), slotId, released)
            withContext(Dispatchers.Default) { current.enrolBiometric(bio, password.copyOf()) }
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun onboardingFinished() {
        pendingPassword?.fill('\u0000')
        pendingPassword = null
    }

    override suspend fun unlockWithFingerprint(): Boolean {
        return try {
            val info = withContext(Dispatchers.IO) { engine.slotInfo() }
            val alias = info.bioAlias ?: return false
            val sealed = info.bioSealed ?: return false
            val key = Keystore.load(alias) ?: return false
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed.iv))
                updateAAD(BIO_AAD)
            }
            val released = authenticate(Prompts.unlock(), BiometricPrompt.CryptoObject(cipher))
                ?.cryptoObject?.cipher ?: return false
            val slotKey = released.doFinal(sealed.blob)
            try {
                val bio = ReleasedBioKey(Keystore.level(key), slotKey, SealedSlotKey(sealed.iv, sealed.blob))
                open(withContext(Dispatchers.Default) { engine.unlockWithBiometric(bio) })
                true
            } finally {
                slotKey.fill(0)
            }
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun unlockWithPassword(password: CharArray) {
        try {
            if (throttle.remainingDelayS() > 0) throw VaultException("unlock_failed")
            val info = withContext(Dispatchers.IO) { engine.slotInfo() }
            val pwdKey = Keystore.loadPwdKey(info.pwdAlias) ?: throw VaultException("unlock_failed")
            if (!confirmScreenLock(pwdKey)) throw VaultException("unlock_failed")
            val opened = try {
                withContext(Dispatchers.Default) { engine.unlockWithPassword(password, pwdKey) }
            } catch (e: VaultException) {
                if (e.code == "unlock_failed") throttle.recordFailure()
                throw e
            }
            open(opened)
        } catch (e: VaultException) {
            throw e
        } catch (e: Exception) {
            throw VaultException("unlock_failed")
        } finally {
            password.fill('\u0000')
        }
    }

    override fun remainingDelayS(): Long = throttle.remainingDelayS()

    override fun failuresSinceLastUnlock(): Int = lastFailures

    override fun lock() {
        autoLock.lockNow()
        closeSession()
    }

    private fun open(opened: VaultSession) {
        session = opened
        lastFailures = throttle.recordSuccess()
        if (lastFailures > 0) opened.recordFailedAttempts(lastFailures)
        val settings = opened.settings()
        autoLock.setTimeouts(settings.autoLockS, settings.backgroundLockS)
        revealGate.criticalWindowS = settings.criticalWindowS
        clipboard.normalTtlS = settings.clipboardTtlS
        autoLock.unlocked()
        unlocked.value = true
        ticker.removeCallbacksAndMessages(null)
        ticker.postDelayed(::tick, TICK_MS)
    }

    private fun tick() {
        autoLock.check()
        revealed.check()
        if (autoLock.isUnlocked) ticker.postDelayed(::tick, TICK_MS)
    }

    private fun closeSession() {
        ticker.removeCallbacksAndMessages(null)
        revealed.maskAll()
        revealGate.reset()
        onboardingFinished()
        session?.lock()
        session = null
        unlocked.value = false
    }

    /** The phone's screen lock, when `K_pwd`'s 30-second window has passed (crypto spec §4.3). */
    private suspend fun confirmScreenLock(pwdKey: KeystorePwdKey): Boolean {
        if (pwdKey.usableNow()) return true
        authenticate(Prompts.deviceCredential(), null) ?: return false
        return pwdKey.usableNow()
    }

    private suspend fun authenticate(
        info: BiometricPrompt.PromptInfo,
        crypto: BiometricPrompt.CryptoObject?,
    ): BiometricPrompt.AuthenticationResult? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (continuation.isActive) continuation.resume(result)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                },
            )
            if (crypto != null) {
                prompt.authenticate(info, crypto)
            } else {
                // Only the device-credential prompt for K_pwd, a time-bound key (crypto spec §4.3).
                prompt.authenticate(info) // nosemgrep: biometric-prompt-without-cryptoobject
            }
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        }
    }

    private companion object {
        const val TICK_MS = 5_000L
    }
}
