package dev.vaultaire.security

import android.os.SystemClock

/** Milliseconds from a monotonic clock: changing the phone's date or time never moves it. */
fun interface Clock {
    fun elapsedMs(): Long

    companion object {
        /** `SystemClock.elapsedRealtime()`: counts through deep sleep, restarts at reboot. */
        val system = Clock { SystemClock.elapsedRealtime() }
    }
}
