package dev.vaultaire.security

import dev.vaultaire.core.KeyLevel

/** Why a vault cannot be created on this phone (crypto spec §5.2). */
enum class Refusal { NO_SCREEN_LOCK, SOFTWARE_KEYS }

object DevicePolicy {

    /**
     * A phone without a secure screen lock is always refused. Software keys (the emulator) are
     * accepted only in a debug build, which then shows a "Test build" banner.
     */
    fun refusal(deviceSecure: Boolean, level: KeyLevel, debugBuild: Boolean): Refusal? = when {
        !deviceSecure -> Refusal.NO_SCREEN_LOCK
        level == KeyLevel.SOFTWARE && !debugBuild -> Refusal.SOFTWARE_KEYS
        else -> null
    }
}
