package dev.vaultaire.security

/** The sensitivity tiers of vault-format §5.1. */
enum class Sensitivity { NORMAL, CONCEALED, CRITICAL, CRITICAL_STRICT }

/**
 * Decides when showing or copying a value needs a fingerprint first (vault-format §5.5
 * `critical_window_s`; threat-model I10, I11). A convenience gate inside an unlocked session, not
 * a cryptographic boundary (threat-model T16).
 */
class RevealGate(private val clock: Clock) {

    /** The vault setting: how long a fingerprint covers critical values; 0 means every time. */
    var criticalWindowS: Long = 60

    private var confirmedAtMs: Long? = null
    private var unused = false

    fun needsBiometric(sensitivity: Sensitivity): Boolean = when (sensitivity) {
        Sensitivity.NORMAL, Sensitivity.CONCEALED -> false
        Sensitivity.CRITICAL ->
            if (criticalWindowS == 0L) !freshAndUnused() else !withinWindow()
        Sensitivity.CRITICAL_STRICT -> !freshAndUnused()
    }

    /** A strong fingerprint was just confirmed (its prompt released a Keystore key). */
    fun biometricConfirmed() {
        confirmedAtMs = clock.elapsedMs()
        unused = true
    }

    /** A value was shown or copied. Any critical use spends the fresh fingerprint. */
    fun used(sensitivity: Sensitivity) {
        if (sensitivity == Sensitivity.CRITICAL || sensitivity == Sensitivity.CRITICAL_STRICT) {
            unused = false
        }
    }

    /** On lock: forget every fingerprint. */
    fun reset() {
        confirmedAtMs = null
        unused = false
    }

    private fun withinWindow(): Boolean =
        confirmedAtMs?.let { clock.elapsedMs() - it < criticalWindowS * 1000 } ?: false

    /** A fingerprint not yet spent, from the last few seconds: one per PIN reveal or copy. */
    private fun freshAndUnused(): Boolean =
        unused && (confirmedAtMs?.let { clock.elapsedMs() - it < FRESH_MS } ?: false)

    private companion object {
        const val FRESH_MS = 10_000L
    }
}
