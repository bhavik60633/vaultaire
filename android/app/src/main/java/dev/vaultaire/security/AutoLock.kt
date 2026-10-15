package dev.vaultaire.security

/**
 * Locks the vault after inactivity, after time in the background, on screen-off and on the Lock
 * button (crypto spec §10 "Lock"; vault-format §5.5; threat-model T02). [onLock] runs once per
 * unlock, before anything else is drawn.
 */
class AutoLock(private val clock: Clock, private val onLock: () -> Unit) {

    private var idleMs = 120_000L
    private var backgroundMs = 30_000L
    private var lastActivityMs = 0L
    private var backgroundSinceMs: Long? = null

    var isUnlocked: Boolean = false
        private set

    /** The vault settings `auto_lock_s` and `background_lock_s`. */
    fun setTimeouts(idleS: Long, backgroundS: Long) {
        idleMs = idleS * 1000
        backgroundMs = backgroundS * 1000
    }

    fun unlocked() {
        isUnlocked = true
        lastActivityMs = clock.elapsedMs()
        backgroundSinceMs = null
    }

    fun userActivity() {
        if (isUnlocked) lastActivityMs = clock.elapsedMs()
    }

    fun movedToBackground() {
        if (!isUnlocked) return
        backgroundSinceMs = clock.elapsedMs()
        if (backgroundMs == 0L) lock()
    }

    /** Locks at once if a limit passed while away, without waiting for the next [check]. */
    fun movedToForeground() {
        check()
        backgroundSinceMs = null
    }

    fun screenOff() = lock()

    fun lockNow() = lock()

    /** Called regularly while unlocked; locks when a limit has passed. */
    fun check() {
        if (!isUnlocked) return
        val now = clock.elapsedMs()
        val idle = now - lastActivityMs >= idleMs
        val away = backgroundSinceMs?.let { now - it >= backgroundMs } ?: false
        if (idle || away) lock()
    }

    private fun lock() {
        if (!isUnlocked) return
        isUnlocked = false
        backgroundSinceMs = null
        onLock()
    }
}
