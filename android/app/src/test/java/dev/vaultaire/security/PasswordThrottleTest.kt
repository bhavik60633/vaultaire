package dev.vaultaire.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Component 9 (milestone M2): waiting times after wrong vault passwords (threat-model T14;
 * vault-format §8).
 *
 * The first four wrong passwords cost nothing. Then the app makes the user wait: 30 s after the
 * 5th, 1 min after the 6th, 5 min after the 7th, 15 min after the 8th, 1 h after the 9th and 3 h
 * after the 10th and every later one. Closing the app does not end a wait, and restarting the
 * phone starts the full wait again, so neither shortens it. Every guess also still needs this
 * phone's hardware and a full Argon2id, which is the real protection.
 */
@RunWith(RobolectricTestRunner::class)
class PasswordThrottleTest {

    private class FakeClock(var nowMs: Long = 7_000_000L) : Clock {
        override fun elapsedMs(): Long = nowMs
        fun advanceS(seconds: Long) {
            nowMs += seconds * 1000
        }
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(context)
    private val clock = FakeClock()
    private var bootCount = 4

    private fun throttle() = PasswordThrottle(prefs, clock) { bootCount }

    private fun fail(throttle: PasswordThrottle, times: Int) {
        repeat(times) { throttle.recordFailure() }
    }

    @Test
    fun theWaitingTimesFollowTheSchedule() {
        val expected = mapOf(
            1 to 0L, 2 to 0L, 3 to 0L, 4 to 0L,
            5 to 30L, 6 to 60L, 7 to 300L, 8 to 900L, 9 to 3600L,
            10 to 10_800L, 11 to 10_800L, 50 to 10_800L,
        )
        for ((failures, seconds) in expected) {
            assertEquals("after $failures wrong passwords", seconds, PasswordThrottle.delayAfter(failures))
        }
    }

    @Test
    fun fourWrongPasswordsCostNothing() {
        val throttle = throttle()
        fail(throttle, 4)
        assertEquals(0L, throttle.remainingDelayS())
    }

    @Test
    fun theFifthMeansWaitingThirtySeconds() {
        val throttle = throttle()
        fail(throttle, 5)
        assertEquals(30L, throttle.remainingDelayS())
        clock.advanceS(12)
        assertEquals(18L, throttle.remainingDelayS())
        clock.advanceS(18)
        assertEquals(0L, throttle.remainingDelayS())
    }

    @Test
    fun closingTheAppDoesNotEndTheWait() {
        fail(throttle(), 6)
        clock.advanceS(10)
        assertEquals(50L, throttle().remainingDelayS())
    }

    @Test
    fun restartingThePhoneStartsTheFullWaitAgain() {
        fail(throttle(), 5)
        clock.advanceS(25)
        bootCount = 5
        clock.nowMs = 1_000L
        val afterReboot = throttle()
        assertEquals(30L, afterReboot.remainingDelayS())
        clock.advanceS(30)
        assertEquals(0L, afterReboot.remainingDelayS())
    }

    @Test
    fun anUnlockReportsTheFailuresAndStartsAfresh() {
        val throttle = throttle()
        fail(throttle, 3)
        assertEquals(3, throttle.recordSuccess())
        assertEquals(0, prefs.failuresSinceUnlock)
        fail(throttle, 4)
        assertEquals(0L, throttle.remainingDelayS())
    }
}
