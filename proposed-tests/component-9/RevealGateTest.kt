package dev.vaultaire.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Component 9 (milestone M2): when a fingerprint is needed to show or copy a value
 * (vault-format §5.1 sensitivity tiers, §5.5 `critical_window_s`; threat-model I10, I11, T02).
 *
 * - Normal and concealed values never need one.
 * - Critical values (card number, CVV, account number, banking passwords) need a fingerprint
 *   within the last 60 seconds (the vault setting; 0 means every time).
 * - Critical-strict values (every PIN) need a fresh fingerprint for every single reveal or copy.
 *
 * These are convenience gates inside an unlocked session, not cryptographic boundaries
 * (threat-model T16); the vault itself stays protected by its keys.
 */
class RevealGateTest {

    private class FakeClock(var nowMs: Long = 9_000_000L) : Clock {
        override fun elapsedMs(): Long = nowMs
        fun advanceS(seconds: Long) {
            nowMs += seconds * 1000
        }
    }

    private val clock = FakeClock()
    private val gate = RevealGate(clock)

    @Test
    fun normalAndConcealedValuesNeverNeedAFingerprint() {
        assertFalse(gate.needsBiometric(Sensitivity.NORMAL))
        assertFalse(gate.needsBiometric(Sensitivity.CONCEALED))
    }

    @Test
    fun criticalValuesNeedAFingerprintWithinTheLastMinute() {
        assertTrue(gate.needsBiometric(Sensitivity.CRITICAL))
        gate.biometricConfirmed()
        gate.used(Sensitivity.CRITICAL)
        clock.advanceS(59)
        assertFalse("still inside the window", gate.needsBiometric(Sensitivity.CRITICAL))
        gate.used(Sensitivity.CRITICAL)
        clock.advanceS(1)
        assertTrue("60 s have passed", gate.needsBiometric(Sensitivity.CRITICAL))
    }

    @Test
    fun everyPinNeedsItsOwnFreshFingerprint() {
        assertTrue(gate.needsBiometric(Sensitivity.CRITICAL_STRICT))
        gate.biometricConfirmed()
        assertFalse(gate.needsBiometric(Sensitivity.CRITICAL_STRICT))
        gate.used(Sensitivity.CRITICAL_STRICT)
        assertTrue("used once, so asked again", gate.needsBiometric(Sensitivity.CRITICAL_STRICT))
    }

    @Test
    fun aFingerprintGivenForACardNumberDoesNotOpenAPin() {
        gate.biometricConfirmed()
        gate.used(Sensitivity.CRITICAL)
        assertFalse(gate.needsBiometric(Sensitivity.CRITICAL))
        assertTrue(gate.needsBiometric(Sensitivity.CRITICAL_STRICT))
    }

    @Test
    fun aWindowOfZeroAsksEveryTime() {
        gate.criticalWindowS = 0
        gate.biometricConfirmed()
        assertFalse(gate.needsBiometric(Sensitivity.CRITICAL))
        gate.used(Sensitivity.CRITICAL)
        assertTrue(gate.needsBiometric(Sensitivity.CRITICAL))
    }

    @Test
    fun lockingForgetsTheLastFingerprint() {
        gate.biometricConfirmed()
        gate.reset()
        assertTrue(gate.needsBiometric(Sensitivity.CRITICAL))
        assertTrue(gate.needsBiometric(Sensitivity.CRITICAL_STRICT))
    }
}
