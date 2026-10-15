package dev.vaultaire.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Component 16 (milestone M5, ADR-009): reading the front of a card from the text the camera sees.
 *
 * - The number is taken only when it passes the Luhn check and was read the same in 3 frames,
 *   so a blurred or half-seen number never fills the form.
 * - The expiry is MM/YY, preferring the date after "VALID THRU"; the name is a line of capitals.
 * - A bank printed on the card is only offered (its id), never chosen.
 * - Nothing else is kept: no CVV, no other text.
 */
class CardTextReaderTest {

    private val banks = mapOf("HDFC BANK" to "in.hdfc", "STATE BANK OF INDIA" to "in.sbi")

    private fun reader() = CardTextReader { line -> banks[line.trim().uppercase()] }

    private val front = listOf(
        "HDFC BANK",
        "PLATINUM",
        "5555 5555 5555 4444",
        "VALID THRU 08/29",
        "ASHA RAO",
        "mastercard",
    )

    private fun CardTextReader.readTimes(times: Int, lines: List<String>): ScannedCard? {
        var card: ScannedCard? = null
        repeat(times) { card = read(lines) }
        return card
    }

    // ---------- the number ----------

    @Test
    fun theNumberIsTakenOnlyAfterThreeMatchingFrames() {
        val reader = reader()
        assertNull(reader.read(front))
        assertNull(reader.read(front))
        assertEquals("5555555555554444", reader.read(front)?.number)
    }

    @Test
    fun aNumberThatFailsTheLuhnCheckIsNeverTaken() {
        assertNull(reader().readTimes(6, listOf("5555 5555 5555 4445")))
    }

    @Test
    fun aDifferentNumberStartsTheCountAgain() {
        val reader = reader()
        reader.read(front)
        reader.read(front)
        assertNull(reader.read(listOf("4111 1111 1111 1111")))
        assertNull(reader.read(front))
        assertNull(reader.read(front))
        assertEquals("5555555555554444", reader.read(front)?.number)
    }

    @Test
    fun aFrameWithoutANumberNeitherCountsNorStartsAgain() {
        val reader = reader()
        reader.read(front)
        assertNull(reader.read(listOf("ASHA RAO")))
        reader.read(front)
        assertEquals("5555555555554444", reader.read(front)?.number)
    }

    @Test
    fun anAmericanExpressNumberOf15DigitsIsRead() {
        assertEquals("378282246310005", reader().readTimes(3, listOf("3782 822463 10005"))?.number)
    }

    @Test
    fun lettersTheCameraMistakesForDigitsAreReadAsDigits() {
        assertEquals("4012888888881881", reader().readTimes(3, listOf("4O12 8888 8888 188l"))?.number)
    }

    @Test
    fun aNumberWithWordsAroundItIsFound() {
        assertEquals("4111111111111111", reader().readTimes(3, listOf("CARD NO 4111 1111 1111 1111"))?.number)
    }

    @Test
    fun aSecurityCodeIsNeverReadOrKept() {
        val card = reader().readTimes(3, front + listOf("CVV 321", "321"))
        assertNotNull(card)
        assertEquals("5555555555554444", card!!.number)
        assertFalse("nothing but number, expiry, name and bank", card.toString().contains("321"))
    }

    // ---------- the expiry ----------

    @Test
    fun theExpiryIsReadAsMonthAndYear() {
        assertEquals("08/29", reader().readTimes(3, front)?.expiry)
    }

    @Test
    fun aFourDigitYearIsShortened() {
        val lines = listOf("5555 5555 5555 4444", "VALID THRU 08/2029")
        assertEquals("08/29", reader().readTimes(3, lines)?.expiry)
    }

    @Test
    fun theValidThruDateWinsOverTheValidFromDate() {
        val lines = listOf("5555 5555 5555 4444", "VALID FROM 01/22", "VALID THRU 08/29")
        assertEquals("08/29", reader().readTimes(3, lines)?.expiry)
    }

    @Test
    fun withoutLabelsTheLaterDateIsTheExpiry() {
        val lines = listOf("5555 5555 5555 4444", "01/22   08/29")
        assertEquals("08/29", reader().readTimes(3, lines)?.expiry)
    }

    @Test
    fun anImpossibleMonthIsNotAnExpiry() {
        val card = reader().readTimes(3, listOf("5555 5555 5555 4444", "VALID THRU 13/29"))
        assertEquals("5555555555554444", card?.number)
        assertNull(card!!.expiry)
    }

    // ---------- the name ----------

    @Test
    fun theNameIsTheLineOfCapitalLetters() {
        assertEquals("ASHA RAO", reader().readTimes(3, front)?.holder)
    }

    @Test
    fun bankCardAndNetworkWordsAreNeverTheName() {
        val lines = listOf("HDFC BANK", "PLATINUM", "DEBIT CARD", "VISA", "RUPAY", "MASTERCARD", "5555 5555 5555 4444", "VALID THRU 08/29")
        val card = reader().readTimes(3, lines)
        assertEquals("5555555555554444", card?.number)
        assertNull(card!!.holder)
    }

    @Test
    fun aLineWithDigitsOrSmallLettersOrTooManyLettersIsNotTheName() {
        val lines = listOf(
            "5555 5555 5555 4444",
            "ASHA RAO 2",
            "Customer care",
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ ABC",
        )
        val card = reader().readTimes(3, lines)
        assertEquals("5555555555554444", card?.number)
        assertNull(card!!.holder)
    }

    @Test
    fun detailsSeenInEarlierFramesAreKept() {
        val reader = reader()
        reader.read(front)
        reader.read(listOf("5555 5555 5555 4444"))
        val card = reader.read(listOf("5555 5555 5555 4444"))
        assertEquals("08/29", card?.expiry)
        assertEquals("ASHA RAO", card?.holder)
    }

    // ---------- the bank ----------

    @Test
    fun aBankPrintedOnTheCardIsOfferedByItsId() {
        assertEquals("in.hdfc", reader().readTimes(3, front)?.seenBankId)
    }

    @Test
    fun aCardWithoutAKnownBankOffersNone() {
        val card = reader().readTimes(3, listOf("5555 5555 5555 4444", "ASHA RAO"))
        assertEquals("ASHA RAO", card?.holder)
        assertNull(card!!.seenBankId)
    }
}
