// Phase-0 spike: THROWAWAY CODE. Not product code; see spikes/phase0/README.md.
package dev.vaultaire.spike

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import uniffi.spike_core.aeadRoundtrip
import uniffi.spike_core.argon2idTiming
import uniffi.spike_core.coreVersion

/**
 * Runs unattended on Firebase Test Lab phones (ADR-001 criteria 2–5, the parts that need no finger).
 * Every result line is logged with the tag VaultaireSpike and starts with "RESULT", so it can be
 * filtered from the Test Lab logcat.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PhoneBenchmarkTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun result(line: String) {
        Log.i(TAG, "RESULT $line")
    }

    @Test
    fun a_device() {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        result("device ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}")
        result("soc ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}, abis ${Build.SUPPORTED_ABIS.joinToString()}, ram ${mem.totalMem / (1024 * 1024)} MiB")
        result("secure lock screen ${context.getSystemService(KeyguardManager::class.java).isDeviceSecure}")
        result("strongbox feature ${context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)}")
    }

    @Test
    fun b_coreAndAead() {
        val start = SystemClock.elapsedRealtimeNanos()
        val version = coreVersion()
        result("core $version, first call ${(SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0} ms")
        val ok = aeadRoundtrip()
        result("aead ${if (ok) "PASS" else "FAIL"}")
        assertTrue(ok)
    }

    /** Criterion 4 and threat-model R5: the unlock cost on real phones. Target ≤ 1 s on the slowest supported one. */
    @Test
    fun c_argon2id() {
        for ((m, t, runs) in listOf(Triple(65_536, 3, 5), Triple(131_072, 4, 3), Triple(262_144, 3, 2))) {
            val times = (1..runs).map { argon2idTiming(m.toUInt(), t.toUInt(), 1u).millis.toLong() }
            result("argon2id m=${m / 1024}MiB t=$t p=1 runs=${times.joinToString("/")} ms median=${times.sorted()[times.size / 2]} ms")
        }
    }

    /**
     * Criterion 5, unattended part: where keys land on this phone. The spec keys need an enrolled
     * biometric and a secure lock screen, which Test Lab phones usually lack (finding S1), so this
     * also creates auth-free probe keys of the same algorithms just to read the security level.
     */
    @Test
    fun d_keystore() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val probes = listOf(
            Triple("aes-gcm", KeyProperties.KEY_ALGORITHM_AES, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT),
            Triple("hmac-sha256", KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KeyProperties.PURPOSE_SIGN),
        )
        for ((label, algorithm, purposes) in probes) {
            for (strongBox in listOf(true, false)) {
                val alias = "probe_${label}_$strongBox"
                val outcome = try {
                    val builder = KeyGenParameterSpec.Builder(alias, purposes).setIsStrongBoxBacked(strongBox)
                    if (algorithm == KeyProperties.KEY_ALGORITHM_AES) {
                        builder.setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    }
                    val start = SystemClock.elapsedRealtimeNanos()
                    val key = KeyGenerator.getInstance(algorithm, "AndroidKeyStore").run { init(builder.build()); generateKey() }
                    "level ${level(key)}, created in ${(SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0} ms"
                } catch (e: StrongBoxUnavailableException) {
                    "no StrongBox"
                } catch (e: Exception) {
                    "failed: ${e.javaClass.simpleName}: ${e.message}"
                } finally {
                    ks.deleteEntry(alias)
                }
                result("keystore probe $label strongbox=$strongBox -> $outcome")
            }
        }
        // The real K_pwd definition, to record how this phone refuses it (or not).
        val outcome = try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder("probe_k_pwd", KeyProperties.PURPOSE_SIGN)
                        .setUserAuthenticationRequired(true)
                        .setUserAuthenticationParameters(30, KeyProperties.AUTH_DEVICE_CREDENTIAL)
                        .setUnlockedDeviceRequired(true)
                        .build(),
                )
                "created, level ${level(generateKey())}"
            }
        } catch (e: Exception) {
            "refused: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            ks.deleteEntry("probe_k_pwd")
        }
        result("keystore spec K_pwd -> $outcome")
    }

    private fun level(key: SecretKey): String {
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
        return when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> "SOFTWARE"
            KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> "unknown-secure"
            else -> "unknown"
        }
    }

    private companion object {
        const val TAG = "VaultaireSpike"
    }
}
