package dev.vaultaire.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Component 9 (milestone M2): a revealed value is shown one at a time and hidden again on its
 * own (threat-model I10, I13, T10, T13).
 *
 * - Showing a second value hides the first.
 * - A value hides itself after 30 seconds, and at once when the app leaves the screen or locks.
 * - Hiding overwrites the characters in memory, so the value does not linger until garbage
 *   collection (crypto spec §11).
 */
class RevealedValuesTest {

    private class FakeClock(var nowMs: Long = 2_000_000L) : Clock {
        override fun elapsedMs(): Long = nowMs
        fun advanceS(seconds: Long) {
            nowMs += seconds * 1000
        }
    }

    private val clock = FakeClock()
    private val revealed = RevealedValues(clock)

    private fun wiped(chars: CharArray) = chars.all { it == '\u0000' }

    @Test
    fun aRevealedValueCanBeRead() {
        revealed.reveal("card.number", "4111111111111234".toCharArray())
        assertArrayEquals("4111111111111234".toCharArray(), revealed.shown("card.number"))
    }

    @Test
    fun onlyOneValueIsShownAtATime() {
        val number = "4111111111111234".toCharArray()
        revealed.reveal("card.number", number)
        revealed.reveal("card.cvv", "123".toCharArray())
        assertNull(revealed.shown("card.number"))
        assertTrue("the hidden value is overwritten", wiped(number))
    }

    @Test
    fun aValueHidesItselfAfterThirtySeconds() {
        val number = "4111111111111234".toCharArray()
        revealed.reveal("card.number", number)
        clock.advanceS(29)
        revealed.check()
        assertArrayEquals("4111111111111234".toCharArray(), revealed.shown("card.number"))
        clock.advanceS(1)
        revealed.check()
        assertNull(revealed.shown("card.number"))
        assertTrue(wiped(number))
    }

    @Test
    fun leavingTheScreenOrLockingHidesEverything() {
        val number = "4111111111111234".toCharArray()
        revealed.reveal("card.number", number)
        revealed.maskAll()
        assertNull(revealed.shown("card.number"))
        assertTrue(wiped(number))
    }
}
