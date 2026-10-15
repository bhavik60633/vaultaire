package dev.vaultaire.security

/**
 * Waiting times after wrong vault passwords (crypto spec §10; threat-model T14). Counts live in
 * plaintext preferences (vault-format §8), so closing the app never ends a wait; a changed boot
 * count restarts the full wait, so rebooting never shortens it.
 */
class PasswordThrottle(
    private val prefs: Prefs,
    private val clock: Clock,
    private val bootCount: () -> Int,
) {

    /** Whole seconds left before the next password may be tried. */
    fun remainingDelayS(): Long {
        val delayS = delayAfter(prefs.failedAttempts)
        if (delayS == 0L) return 0L
        val now = clock.elapsedMs()
        val elapsedMs = now - prefs.delayStartedElapsedMs
        if (prefs.delayStartedBootCount != bootCount() || elapsedMs < 0) {
            startWait(now)
            return delayS
        }
        val leftMs = delayS * 1000 - elapsedMs
        return if (leftMs <= 0) 0L else (leftMs + 999) / 1000
    }

    fun recordFailure() {
        prefs.failedAttempts = prefs.failedAttempts + 1
        prefs.failuresSinceUnlock = prefs.failuresSinceUnlock + 1
        startWait(clock.elapsedMs())
    }

    /** After an unlock: returns the wrong passwords since the last one, for the security log. */
    fun recordSuccess(): Int {
        val failures = prefs.failuresSinceUnlock
        prefs.failedAttempts = 0
        prefs.failuresSinceUnlock = 0
        prefs.delayStartedElapsedMs = 0L
        return failures
    }

    private fun startWait(now: Long) {
        prefs.delayStartedElapsedMs = now
        prefs.delayStartedBootCount = bootCount()
    }

    companion object {
        /** Seconds to wait after this many wrong passwords in a row. */
        fun delayAfter(failures: Int): Long = when {
            failures < 5 -> 0L
            failures == 5 -> 30L
            failures == 6 -> 60L
            failures == 7 -> 300L
            failures == 8 -> 900L
            failures == 9 -> 3_600L
            else -> 10_800L
        }
    }
}
