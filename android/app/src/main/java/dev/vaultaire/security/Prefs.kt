package dev.vaultaire.security

import android.content.Context

/**
 * The app's one plaintext preferences file. It holds only the keys of vault-format §8, none of
 * them sensitive (threat-model I1, T08, T37). This is the only module that opens
 * SharedPreferences (Semgrep rule `shared-preferences-outside-prefs-module`).
 */
class Prefs(context: Context) {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var vaultPresent: Boolean
        get() = prefs.getBoolean("vault_present", false)
        set(value) {
            prefs.edit().putBoolean("vault_present", value).commit()
        }

    var onboardingStep: Int
        get() = prefs.getInt("onboarding_step", 0)
        set(value) {
            prefs.edit().putInt("onboarding_step", value).commit()
        }

    /** `system`, `light` or `dark`. */
    var theme: String
        get() = prefs.getString("theme", "system") ?: "system"
        set(value) {
            require(value in THEMES) { "unknown theme" }
            prefs.edit().putString("theme", value).commit()
        }

    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(value) {
            prefs.edit().putBoolean("haptics", value).commit()
        }

    var failedAttempts: Int
        get() = prefs.getInt("failed_attempts", 0)
        set(value) {
            prefs.edit().putInt("failed_attempts", value).commit()
        }

    var delayStartedElapsedMs: Long
        get() = prefs.getLong("delay_started_elapsed_ms", 0L)
        set(value) {
            prefs.edit().putLong("delay_started_elapsed_ms", value).commit()
        }

    var delayStartedBootCount: Int
        get() = prefs.getInt("delay_started_boot_count", 0)
        set(value) {
            prefs.edit().putInt("delay_started_boot_count", value).commit()
        }

    var failuresSinceUnlock: Int
        get() = prefs.getInt("failures_since_unlock", 0)
        set(value) {
            prefs.edit().putInt("failures_since_unlock", value).commit()
        }

    companion object {
        const val FILE_NAME = "vaultaire_prefs"

        /** vault-format §8: the only keys this file may ever contain. */
        val ALLOWED_KEYS: Set<String> = setOf(
            "vault_present",
            "onboarding_step",
            "theme",
            "haptics",
            "failed_attempts",
            "delay_started_elapsed_ms",
            "delay_started_boot_count",
            "failures_since_unlock",
        )

        private val THEMES = setOf("system", "light", "dark")
    }
}
