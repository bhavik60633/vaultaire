package dev.vaultaire.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Component 17 (milestone M5, ADR-009, ADR-011): the scanner asks the bank catalogue whether a
 * line printed on a card is a bank's name, using the same normaliser as the bank picker.
 */
class ScanBridgeTest {

    @Test
    fun aBankNameAsPrintedOnACardIsFound() {
        assertEquals("in.hdfc", VaultEngine.findBank("HDFC BANK"))
        assertEquals("in.sbi", VaultEngine.findBank("STATE BANK OF INDIA"))
        assertEquals("in.axis", VaultEngine.findBank("Axis Bank"))
    }

    @Test
    fun otherLinesAreNotBanks() {
        assertNull(VaultEngine.findBank("ASHA RAO"))
        assertNull(VaultEngine.findBank("PLATINUM"))
        assertNull(VaultEngine.findBank("VALID THRU 08/29"))
        assertNull(VaultEngine.findBank("BANK"))
        assertNull(VaultEngine.findBank(""))
    }
}
