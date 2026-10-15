package dev.vaultaire.ui

import dev.vaultaire.core.VaultException
import dev.vaultaire.security.Refusal
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Component 11 (milestone M2): a stand-in for the vault, so the screens can be tested without
 * the Rust engine, Keystore or a fingerprint sensor. The real app implements the same
 * [VaultGateway] with [dev.vaultaire.core.VaultEngine] and the fingerprint prompt.
 */
class FakeGateway(
    var exists: Boolean = false,
    var refusalValue: Refusal? = null,
) : VaultGateway {

    override val unlocked = MutableStateFlow(false)

    val rightPassword = "correct horse battery staple 42"
    val recoveryKey = "AAAA-BBBB-CCCC-DDDD-EEEE-FF"
    var weakPassword = false
    var fingerprintWorks = true
    var delayS = 0L
    var failures = 0

    var creates = 0
    var enrolments = 0
    var locks = 0

    override fun vaultExists(): Boolean = exists

    override fun refusal(): Refusal? = refusalValue

    override suspend fun create(password: CharArray): CharArray {
        creates++
        if (weakPassword) throw VaultException("weak_password")
        exists = true
        unlocked.value = true
        return recoveryKey.toCharArray()
    }

    override suspend fun enrolFingerprint(): Boolean {
        enrolments++
        return fingerprintWorks
    }

    override suspend fun unlockWithFingerprint(): Boolean {
        if (fingerprintWorks) unlocked.value = true
        return fingerprintWorks
    }

    override suspend fun unlockWithPassword(password: CharArray) {
        if (String(password) != rightPassword) throw VaultException("unlock_failed")
        unlocked.value = true
    }

    override fun remainingDelayS(): Long = delayS

    override fun failuresSinceLastUnlock(): Int = failures

    override fun lock() {
        locks++
        unlocked.value = false
    }
}
