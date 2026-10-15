package dev.vaultaire.security

import dev.vaultaire.core.KeyLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Component 9 (milestone M2): phones the vault refuses to be created on (crypto spec §5.2).
 *
 * - A phone without a secure screen lock: anyone holding it could use the keys.
 * - Keys kept in software rather than in hardware. The Android emulator only has software keys,
 *   so test builds (debug) accept them for trying the app on a laptop, and show a "Test build"
 *   banner on every screen. A release build never accepts them.
 */
class DevicePolicyTest {

    @Test
    fun aPhoneWithoutAScreenLockIsRefused() {
        assertEquals(
            Refusal.NO_SCREEN_LOCK,
            DevicePolicy.refusal(deviceSecure = false, level = KeyLevel.STRONG_BOX, debugBuild = false),
        )
        assertEquals(
            "even in a test build",
            Refusal.NO_SCREEN_LOCK,
            DevicePolicy.refusal(deviceSecure = false, level = KeyLevel.SOFTWARE, debugBuild = true),
        )
    }

    @Test
    fun softwareKeysAreRefusedInARealBuild() {
        assertEquals(
            Refusal.SOFTWARE_KEYS,
            DevicePolicy.refusal(deviceSecure = true, level = KeyLevel.SOFTWARE, debugBuild = false),
        )
    }

    @Test
    fun softwareKeysAreAcceptedOnlyInATestBuild() {
        assertNull(DevicePolicy.refusal(deviceSecure = true, level = KeyLevel.SOFTWARE, debugBuild = true))
    }

    @Test
    fun hardwareKeysOnASecurePhoneAreAccepted() {
        assertNull(DevicePolicy.refusal(deviceSecure = true, level = KeyLevel.TEE, debugBuild = false))
        assertNull(DevicePolicy.refusal(deviceSecure = true, level = KeyLevel.STRONG_BOX, debugBuild = false))
    }
}
