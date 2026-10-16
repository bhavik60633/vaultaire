package dev.vaultaire.spike

/**
 * Default sample items matching the iPhone PWA prototype for demonstration,
 * testing, and initial onboarding verification.
 */
object SampleData {
    fun getSampleItems(): List<VaultItem> = listOf(
        VaultItem(
            id = "c1",
            type = "card",
            title = "Travel card",
            subtitle = "Visa · •••• 4821",
            bankName = "NORTHBRIDGE BANK",
            cardHolder = "Alex Morgan",
            cardNumber = "•••• 4821",
            expiry = "08 / 29",
            cvv = "317",
            network = "VISA",
            finish = "damascus", // Damascus steel finish
            fav = true
        ),
        VaultItem(
            id = "c2",
            type = "card",
            title = "Everyday debit",
            subtitle = "Mastercard · •••• 1177",
            bankName = "HARBOUR MUTUAL",
            cardHolder = "Alex Morgan",
            cardNumber = "•••• 1177",
            expiry = "12 / 26",
            cvv = "492",
            network = "MASTERCARD",
            finish = "platinum",
            fav = false,
            upcomingDate = "Dec 2026",
            upcomingDays = 90
        ),
        VaultItem(
            id = "c3",
            type = "card",
            title = "Platinum",
            subtitle = "American Express · •••• 8431",
            bankName = "AMERICAN EXPRESS",
            cardHolder = "Alex Morgan",
            cardNumber = "•••• 8431",
            expiry = "05 / 28",
            cvv = "8431",
            network = "AMEX",
            finish = "carbon", // Carbon fiber finish
            fav = false
        ),
        VaultItem(
            id = "c4",
            type = "card",
            title = "Gold",
            subtitle = "American Express · •••• 1006",
            bankName = "AMERICAN EXPRESS",
            cardHolder = "Alex Morgan",
            cardNumber = "•••• 1006",
            expiry = "04 / 27",
            cvv = "1006",
            network = "AMEX",
            finish = "champagne",
            fav = false
        ),
        VaultItem(
            id = "c5",
            type = "card",
            title = "Business Platinum",
            subtitle = "American Express · •••• 0005",
            bankName = "AMERICAN EXPRESS",
            cardHolder = "Alex Morgan",
            cardNumber = "•••• 0005",
            expiry = "10 / 29",
            cvv = "0005",
            network = "AMEX",
            finish = "titanium",
            fav = false
        ),
        VaultItem(
            id = "c6",
            type = "card",
            title = "Blue Cash",
            subtitle = "American Express · •••• 0009",
            bankName = "AMERICAN EXPRESS",
            cardHolder = "Alex Morgan",
            cardNumber = "•••• 0009",
            expiry = "02 / 28",
            cvv = "0009",
            network = "AMEX",
            finish = "ivory",
            fav = false
        ),
        VaultItem(
            id = "p1",
            type = "login",
            title = "Primary email",
            subtitle = "alex.morgan@example.com",
            username = "alex.morgan@example.com",
            secret = "harbor-violet-onyx-ledger-canyon-rune",
            fav = true,
            history = listOf(
                "summit-birch-zephyr-garnet-ember-91" to "14 May 2026",
                "delta-granite-prism-cedar-harbor-22" to "12 Jan 2026"
            ),
            customFields = listOf(
                CustomField(label = "Security question", value = "High school mascot", tier = "concealed")
            )
        ),
        VaultItem(
            id = "p2",
            type = "login",
            title = "Online banking",
            subtitle = "amorgan · online.northbridge.example",
            username = "amorgan",
            secret = "Q7!vR2#mK9@xLp4s",
            fav = false,
            history = listOf("T3#nK8!xP4vL9@wM" to "15 Mar 2026")
        ),
        VaultItem(
            id = "p3",
            type = "login",
            title = "Code hosting",
            subtitle = "alexm-dev · code.example",
            username = "alexm-dev",
            secret = "lantern-quartz-willow-ember-orbit-slate",
            fav = false
        ),
        VaultItem(
            id = "t1",
            type = "totp",
            title = "GitHub 2FA Authenticator",
            subtitle = "alexm-dev · RFC 6238",
            username = "alexm-dev",
            totpSecret = "JBSWY3DPEHPK3PXP",
            fav = false
        ),
        VaultItem(
            id = "bip1",
            type = "coldstorage",
            title = "Ledger Cold Storage Seed",
            subtitle = "Hardware Wallet #1 (128-bit)",
            bip39Phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            fav = false
        ),
        VaultItem(
            id = "r1",
            type = "codes",
            title = "Code hosting",
            subtitle = "code.example",
            recoveryCodes = listOf("4f9a-2c71", "8b3e-d046", "19c5-7e2a", "a6d8-30fb", "e27b-9154", "0c3f-b8a9", "5d12-f7c0", "96ab-3e48"),
            fav = false
        ),
        VaultItem(
            id = "r2",
            type = "codes",
            title = "Primary email",
            subtitle = "mail.example.com",
            recoveryCodes = listOf("KQ7M-4TXB", "R9HF-2WZN", "D3PV-8YCJ", "G5EA-6LNU", "P2SD-9HBK", "W4MX-7QRT", "J8CF-3VZE", "N6TB-5AGY"),
            fav = false
        ),
        VaultItem(
            id = "k1",
            type = "devkey",
            title = "Cloud root key",
            subtitle = "production · AKIA…MPLE",
            username = "AKIAIOSFODNN7EXAMPLE",
            secret = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            fav = false
        ),
        VaultItem(
            id = "k2",
            type = "devkey",
            title = "Payments API",
            subtitle = "live · secret key",
            username = "pay_live_9fK2mQ7xR4vT1nB8wZ3cY6hJ",
            secret = "pay_live_9fK2mQ7xR4vT1nB8wZ3cY6hJ",
            fav = false
        ),
        VaultItem(
            id = "b1",
            type = "bank",
            title = "Salary account",
            subtitle = "Northbridge Bank · •••• 0932",
            bankName = "Northbridge Bank",
            cardNumber = "5521 4471 0932",
            fav = false
        ),
        VaultItem(
            id = "i1",
            type = "identity",
            title = "Passport",
            subtitle = "Expires Mar 2031",
            cardNumber = "552 081 443",
            fav = false
        ),
        VaultItem(
            id = "i2",
            type = "identity",
            title = "Driving licence",
            subtitle = "Expires Aug 2029",
            cardNumber = "MORGA 853214 AJ9",
            fav = false
        ),
        VaultItem(
            id = "n1",
            type = "note",
            title = "Home Wi-Fi and router",
            subtitle = "Secure note",
            noteContent = "Network name: Morgan-Home-5G\nWi-Fi password: tidal-cobalt-meadow-41\nRouter admin page is on sticker under router.",
            fav = false
        ),
        VaultItem(
            id = "m1",
            type = "membership",
            title = "Airline miles",
            subtitle = "Gold tier ends Jan 2027",
            upcomingDate = "Jan 2027",
            upcomingDays = 120,
            fav = false
        )
    )
}
