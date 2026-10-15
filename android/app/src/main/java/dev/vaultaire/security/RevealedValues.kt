package dev.vaultaire.security

/**
 * The one value currently shown on screen (threat-model I10, I13, T13). It hides itself after
 * [remaskAfterS] seconds, when another value is shown, and on [maskAll] (leaving the screen,
 * locking). Hiding overwrites its characters (crypto spec §11).
 */
class RevealedValues(private val clock: Clock, private val remaskAfterS: Long = 30) {

    private var field: String? = null
    private var value: CharArray? = null
    private var shownAtMs = 0L

    fun reveal(field: String, value: CharArray) {
        maskAll()
        this.field = field
        this.value = value
        shownAtMs = clock.elapsedMs()
    }

    /** The value of [field] if it is the one shown, else null. */
    fun shown(field: String): CharArray? = if (field == this.field) value else null

    fun check() {
        if (value != null && clock.elapsedMs() - shownAtMs >= remaskAfterS * 1000) maskAll()
    }

    fun maskAll() {
        value?.fill('\u0000')
        value = null
        field = null
    }
}
