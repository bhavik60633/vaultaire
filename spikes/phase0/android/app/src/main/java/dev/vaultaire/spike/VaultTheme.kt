package dev.vaultaire.spike

import androidx.compose.ui.graphics.Color
import java.security.SecureRandom
import java.util.Calendar

// --- Obsidian Design Palette from Blueprint §15 ---
val ColorBg = Color(0xFF0B0B0C)
val ColorSurface = Color(0xFF141416)
val ColorElevated = Color(0xFF1C1C1F)
val ColorTile = Color(0xFF242428)
val ColorAccentGold = Color(0xFFC9B38A)
val ColorAccentSoft = Color(0x22C9B38A)
val ColorTextPrimary = Color(0xFFF2EEE6)
val ColorTextSecondary = Color(0xFFA8A39A)
val ColorTextTertiary = Color(0xFF7A756D)
val ColorHairline = Color(0x18FFF5E6)
val ColorHairlineGold = Color(0x35C9B38A)
val ColorOk = Color(0xFF7DB8A5)
val ColorWarn = Color(0xFFD49E35)
val ColorCrit = Color(0xFFE0A196)

fun getTimeGreeting(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }
}

// EFF short word list for passphrase generation (blueprint §15)
val EFF_WORDS = listOf(
    "amber", "anchor", "atlas", "basalt", "beacon", "birch", "cedar", "cinder",
    "cobalt", "comet", "coral", "delta", "ember", "falcon", "fjord", "garnet",
    "glacier", "granite", "harbor", "hazel", "heron", "indigo", "iris", "jade",
    "juniper", "kestrel", "lantern", "larch", "ledger", "lumen", "marble", "meadow",
    "mesa", "nectar", "nimbus", "oak", "obsidian", "onyx", "opal", "orbit",
    "pewter", "pine", "prism", "quartz", "quill", "raven", "reef", "rune",
    "saffron", "sable", "slate", "sparrow", "summit", "tarn", "thistle", "tundra",
    "umber", "vale", "velvet", "violet", "willow", "yarrow", "zenith", "zephyr"
)

fun generateStudioPassword(
    mode: String,
    len: Int,
    useUpper: Boolean = true,
    useDigits: Boolean = true,
    useSymbols: Boolean = true
): String {
    val random = SecureRandom()
    return when (mode) {
        "passphrase" -> {
            List(len) { EFF_WORDS[random.nextInt(EFF_WORDS.size)] }.joinToString("-")
        }
        "pin" -> {
            List(len) { random.nextInt(10).toString() }.joinToString("")
        }
        else -> {
            var chars = "abcdefghijkmnopqrstuvwxyz"
            if (useUpper) chars += "ABCDEFGHJKLMNPQRSTUVWXYZ"
            if (useDigits) chars += "23456789"
            if (useSymbols) chars += "!@#$%^&*-_+=?"
            (0 until len).map { chars[random.nextInt(chars.length)] }.joinToString("")
        }
    }
}
