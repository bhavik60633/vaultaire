// Vaultaire Phase 4: Plausible Deniability Decoy / Duress Vault Engine
package dev.vaultaire.spike

/**
 * Manages Duress / Decoy Vault switching.
 * Under coercion, entering a secondary Decoy PIN or password unlocks an authentic-looking,
 * completely separate Decoy Vault populated with benign everyday credentials (streaming services,
 * coffee rewards, public Wi-Fi). Real vault records are never touched, decrypted, or present in memory.
 */
object DecoyVaultManager {

    const val DECOY_PASSWORD_DEFAULT = "decoy2026"
    const val DECOY_PIN_DEFAULT = "9999"

    /**
     * Checks if the entered password matches the Decoy / Duress trigger.
     */
    fun isDecoyUnlock(password: String): Boolean {
        val clean = password.trim()
        return clean == DECOY_PASSWORD_DEFAULT || clean == DECOY_PIN_DEFAULT
    }

    /**
     * Generates a realistic set of benign, harmless credentials for plausible deniability.
     */
    fun getDecoyItems(): List<VaultItem> {
        return listOf(
            VaultItem(
                id = "decoy_c1",
                type = "card",
                title = "Coffee & Bakery Perks",
                subtitle = "Loyalty · •••• 9021",
                cardHolder = "Alex Morgan",
                cardNumber = "6011 4820 9912 9021",
                expiry = "11 / 27",
                cvv = "104",
                network = "DISCOVER",
                finish = "obsidian",
                isDecoy = true
            ),
            VaultItem(
                id = "decoy_p1",
                type = "login",
                title = "Netflix Family",
                subtitle = "alex.personal@gmail.com",
                username = "alex.personal@gmail.com",
                secret = "SummerSunset2025!",
                isDecoy = true
            ),
            VaultItem(
                id = "decoy_p2",
                type = "login",
                title = "Spotify Student",
                subtitle = "alex.morgan",
                username = "alex.morgan",
                secret = "GroovyRhythms#44",
                isDecoy = true
            ),
            VaultItem(
                id = "decoy_p3",
                type = "login",
                title = "Public Library Account",
                subtitle = "Card ID 20881920",
                username = "20881920",
                secret = "bookworm99",
                isDecoy = true
            ),
            VaultItem(
                id = "decoy_wifi",
                type = "devkey",
                title = "Home Guest Wi-Fi",
                subtitle = "NorthBridge_Guest_5G",
                username = "WPA2-PSK",
                secret = "WelcomeGuestPass2026",
                isDecoy = true
            ),
            VaultItem(
                id = "decoy_note",
                type = "note",
                title = "Grocery & Errands List",
                subtitle = "Weekly reminders",
                noteContent = "Whole milk, sourdough bread, organic olive oil, AA batteries, printer paper.",
                isDecoy = true
            )
        )
    }
}
