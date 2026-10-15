package dev.vaultaire.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Component 9 (milestone M2): auto-lock (crypto spec §10 "Lock"; vault-format §5.5; threat-model T02).
 *
 * The vault locks after 2 minutes without a touch, 30 seconds after the app goes to the
 * background, and at once when the screen turns off or the user taps Lock. Times come from a
 * monotonic clock (`SystemClock.elapsedRealtime()` in the app), so changing the phone's clock
 * never keeps the vault open.
 */
class AutoLockTest {

    private class FakeClock(var nowMs: Long = 5_000_000L) : Clock {
        override fun elapsedMs(): Long = nowMs
        fun advanceS(seconds: Long) {
            nowMs += seconds * 1000
        }
    }

    private val clock = FakeClock()
    private var locks = 0
    private val autoLock = AutoLock(clock) { locks++ }

    @Test
    fun locksAfterTwoMinutesWithoutActivity() {
        autoLock.unlocked()
        clock.advanceS(119)
        autoLock.check()
        assertTrue(autoLock.isUnlocked)
        clock.advanceS(1)
        autoLock.check()
        assertFalse(autoLock.isUnlocked)
        assertEquals(1, locks)
    }

    @Test
    fun activityRestartsTheIdleTimer() {
        autoLock.unlocked()
        clock.advanceS(100)
        autoLock.userActivity()
        clock.advanceS(100)
        autoLock.check()
        assertTrue(autoLock.isUnlocked)
        clock.advanceS(20)
        autoLock.check()
        assertFalse(autoLock.isUnlocked)
    }

    @Test
    fun locksThirtySecondsAfterGoingToTheBackground() {
        autoLock.unlocked()
        autoLock.movedToBackground()
        clock.advanceS(29)
        autoLock.check()
        assertTrue(autoLock.isUnlocked)
        clock.advanceS(1)
        autoLock.check()
        assertFalse(autoLock.isUnlocked)
        assertEquals(1, locks)
    }

    @Test
    fun comingBackInTimeKeepsTheVaultOpen() {
        autoLock.unlocked()
        autoLock.movedToBackground()
        clock.advanceS(20)
        autoLock.movedToForeground()
        clock.advanceS(20)
        autoLock.check()
        assertTrue("40 s idle in total is under 2 minutes", autoLock.isUnlocked)
    }

    @Test
    fun comingBackTooLateLocksAtOnceWithoutWaitingForATick() {
        autoLock.unlocked()
        autoLock.movedToBackground()
        clock.advanceS(31)
        autoLock.movedToForeground()
        assertFalse(autoLock.isUnlocked)
        assertEquals(1, locks)
    }

    @Test
    fun aBackgroundLimitOfZeroLocksImmediately() {
        autoLock.setTimeouts(idleS = 120, backgroundS = 0)
        autoLock.unlocked()
        autoLock.movedToBackground()
        assertFalse(autoLock.isUnlocked)
    }

    @Test
    fun screenOffLocksAtOnce() {
        autoLock.unlocked()
        autoLock.screenOff()
        assertFalse(autoLock.isUnlocked)
        assertEquals(1, locks)
    }

    @Test
    fun theLockButtonLocksAtOnce() {
        autoLock.unlocked()
        autoLock.lockNow()
        assertFalse(autoLock.isUnlocked)
        assertEquals(1, locks)
    }

    @Test
    fun theVaultIsLockedOnlyOnce() {
        autoLock.unlocked()
        autoLock.lockNow()
        clock.advanceS(500)
        autoLock.check()
        autoLock.screenOff()
        autoLock.movedToBackground()
        assertEquals(1, locks)
    }

    @Test
    fun nothingHappensBeforeTheFirstUnlock() {
        clock.advanceS(1000)
        autoLock.check()
        autoLock.screenOff()
        assertFalse(autoLock.isUnlocked)
        assertEquals(0, locks)
    }

    @Test
    fun theTimersStartAgainAfterTheNextUnlock() {
        autoLock.unlocked()
        autoLock.lockNow()
        clock.advanceS(1000)
        autoLock.unlocked()
        assertTrue(autoLock.isUnlocked)
        clock.advanceS(119)
        autoLock.check()
        assertTrue(autoLock.isUnlocked)
        assertEquals(1, locks)
    }

    @Test
    fun theVaultSettingsChooseTheLimits() {
        autoLock.setTimeouts(idleS = 30, backgroundS = 10)
        autoLock.unlocked()
        clock.advanceS(30)
        autoLock.check()
        assertFalse(autoLock.isUnlocked)

        autoLock.unlocked()
        autoLock.movedToBackground()
        clock.advanceS(10)
        autoLock.check()
        assertFalse(autoLock.isUnlocked)
        assertEquals(2, locks)
    }
}
