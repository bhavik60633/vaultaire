package dev.vaultaire.spike

import java.util.UUID

data class CustomField(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val value: String,
    val tier: String = "concealed" // "normal", "concealed", "critical"
)

data class VaultItem(
    val id: String,
    val type: String, // "card", "login", "totp", "coldstorage", "devkey", "codes", "bank", "identity", "note", "membership", "other"
    val title: String,
    val subtitle: String,
    val bankName: String = "",
    val username: String = "",
    val secret: String = "",
    val cardNumber: String = "",
    val cardHolder: String = "",
    val expiry: String = "",
    val cvv: String = "",
    val network: String = "",
    val finish: String = "obsidian", // "obsidian", "platinum", "titanium", "champagne", "damascus", "carbon", "ivory"
    val fav: Boolean = false,
    val recoveryCodes: List<String> = emptyList(),
    val noteContent: String = "",
    val totpSecret: String = "",
    val bip39Phrase: String = "",
    val upcomingDate: String = "",
    val upcomingDays: Int = 0,
    val isDecoy: Boolean = false,
    val hotline: String = "+1 800 555 0142",
    val history: List<Pair<String, String>> = emptyList(), // value to changedAt
    val customFields: List<CustomField> = emptyList()
)
