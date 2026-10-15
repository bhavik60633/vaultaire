package dev.vaultaire.scan

/** What the reader took from the front of a card. Never a CVV, never any other text. */
data class ScannedCard(
    val number: String,
    val expiry: String?,
    val holder: String?,
    /** A catalogue bank printed on the card: only offered to the person, never chosen. */
    val seenBankId: String?,
)

/**
 * Reads the front of a card from the lines of text the camera recognised, one frame at a time
 * (ADR-009). The number counts only when it passes the Luhn check and is read the same in
 * [FRAMES] frames; a frame without a number neither counts nor starts again. The expiry, the name
 * and a printed bank are picked up from any of those frames. Everything else is dropped.
 *
 * [findBank] says whether a line is a bank's name (the catalogue's normaliser, ADR-011).
 */
class CardTextReader(private val findBank: (String) -> String?) {

    private var candidate: String? = null
    private var seen = 0
    private var expiry: Dated? = null
    private var holder: String? = null
    private var bank: String? = null

    /** One frame's lines; the card once its number has been read the same in [FRAMES] frames. */
    fun read(lines: List<String>): ScannedCard? {
        val clean = lines.map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }
        val number = clean.firstNotNullOfOrNull(::numberIn)
        if (number != null && number != candidate) {
            // Another card, or a misread: start again, details included.
            candidate = number
            seen = 0
            expiry = null
            holder = null
            bank = null
        }
        expiryIn(clean)?.let { found -> if (expiry == null || found.rank >= expiry!!.rank) expiry = found }
        nameIn(clean)?.let { holder = it }
        clean.firstNotNullOfOrNull(findBank)?.let { bank = it }
        if (number == null) return null
        seen++
        if (seen < FRAMES) return null
        val card = ScannedCard(number, expiry?.text, holder, bank)
        candidate = null
        seen = 0
        expiry = null
        holder = null
        bank = null
        return card
    }

    // ---------- the number ----------

    /** The card number in a line: 13 to 19 digits in groups, passing the Luhn check. */
    private fun numberIn(line: String): String? {
        val groups = line.split(' ').map(::digitsOf)
        var best: String? = null
        for (start in groups.indices) {
            if (groups[start] == null) continue
            var joined = ""
            for (end in start until groups.size) {
                joined += groups[end] ?: break
                if (joined.length > 19) break
                if (joined.length >= 13 && luhn(joined) && (best == null || joined.length > best.length)) best = joined
            }
        }
        return best
    }

    /**
     * A group of the number, with the letters a camera mistakes for digits read as digits ("4O12"
     * is 4012), but only when most of the group is digits already.
     */
    private fun digitsOf(group: String): String? {
        if (group.isEmpty()) return null
        val digits = group.count { it.isDigit() }
        if (digits * 2 < group.length || digits == 0) return null
        val read = group.map { CONFUSED[it] ?: it }.joinToString("")
        return read.takeIf { s -> s.all { it.isDigit() } }
    }

    private fun luhn(number: String): Boolean {
        var sum = 0
        number.reversed().forEachIndexed { i, c ->
            var d = c - '0'
            if (i % 2 == 1) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return sum % 10 == 0
    }

    // ---------- the expiry ----------

    /** A date found on the card; [rank] orders the candidates, highest wins. */
    private class Dated(val text: String, val rank: Long)

    /**
     * The expiry: MM/YY (or MM/YYYY, shortened). A date after "VALID THRU" or "EXPIRES" wins; a
     * "VALID FROM" date loses; otherwise the later date wins.
     */
    private fun expiryIn(lines: List<String>): Dated? {
        var best: Dated? = null
        for (line in lines) {
            val upper = line.uppercase()
            val label = when {
                THRU.any { it in upper } -> 2L
                FROM.any { it in upper } -> 0L
                else -> 1L
            }
            for (m in DATE.findAll(line)) {
                val month = m.groupValues[1].toInt()
                val year = m.groupValues[2].takeLast(2).toInt()
                val rank = label * 10_000 + year * 100L + month
                if (best == null || rank > best.rank) best = Dated("%02d/%02d".format(month, year), rank)
            }
        }
        return best
    }

    // ---------- the name ----------

    /**
     * The cardholder: a line of capital letters, 2 to 26 characters, that is not a bank's name or
     * a word printed on cards ("PLATINUM", "DEBIT", "VISA"). A name of two or more words wins.
     */
    private fun nameIn(lines: List<String>): String? {
        val names = lines.filter { line ->
            line.length in 2..26 &&
                NAME.matches(line) &&
                line.split(' ', '.', '-').none { it in CARD_WORDS } &&
                findBank(line) == null
        }
        return names.firstOrNull { ' ' in it } ?: names.firstOrNull()
    }

    companion object {
        /** Frames the number must be read the same in before it is taken. */
        const val FRAMES = 3

        private val CONFUSED = mapOf(
            'O' to '0', 'o' to '0', 'D' to '0', 'Q' to '0',
            'l' to '1', 'I' to '1', 'i' to '1', '|' to '1',
            'S' to '5', 's' to '5', 'B' to '8', 'Z' to '2',
        )
        private val DATE = Regex("(?<![0-9/])(0[1-9]|1[0-2]) ?/ ?([0-9]{4}|[0-9]{2})(?![0-9/])")
        private val THRU = listOf("THRU", "THROUGH", "EXPIR", "VALID TO", "VALID UPTO", "VALID UNTIL", "GOOD THRU", "UNTIL")
        private val FROM = listOf("FROM", "SINCE", "MEMBER")
        private val NAME = Regex("[A-Z][A-Z .'-]*[A-Z]")
        private val CARD_WORDS = setOf(
            "BANK", "CARD", "DEBIT", "CREDIT", "PREPAID", "FOREX", "VISA", "MASTERCARD", "RUPAY", "AMEX",
            "AMERICAN", "EXPRESS", "DINERS", "CLUB", "DISCOVER", "MAESTRO", "ELECTRON", "PLATINUM", "GOLD",
            "CLASSIC", "SIGNATURE", "INFINITE", "WORLD", "TITANIUM", "ELITE", "PREMIER", "PRIVILEGE",
            "BUSINESS", "CORPORATE", "REWARDS", "SELECT", "VALID", "THRU", "FROM", "MONTH", "YEAR",
            "EXPIRES", "EXPIRY", "MEMBER", "SINCE", "ELECTRONIC", "USE", "ONLY", "INTERNATIONAL",
            "CONTACTLESS", "AUTHORISED", "AUTHORIZED", "SIGNATURE", "CUSTOMER", "SERVICE",
        )
    }
}
