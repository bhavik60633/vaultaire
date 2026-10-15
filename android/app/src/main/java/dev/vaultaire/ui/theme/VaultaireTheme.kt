package dev.vaultaire.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.vaultaire.R

/**
 * "Obsidian", the agreed design (prototype/styles.css, blueprint §15): near-black surfaces, warm
 * cream text, one champagne accent, IBM Plex type. Every screen takes its colours and type from
 * here, never from Material defaults.
 */
@Immutable
object Obsidian {
    val bg = Color(0xFF0B0B0C)
    val raised = Color(0xFF141416)
    val elev = Color(0xFF1C1C1F)
    val hair = Color(0x14FFF5E6)
    val hair2 = Color(0x29FFF5E6)
    val text = Color(0xFFF2EEE6)
    val text2 = Color(0xFFA8A39A)
    val text3 = Color(0xFF8A857C)
    val accent = Color(0xFFC9B38A)
    val accentSoft = Color(0x1FC9B38A)
    val crit = Color(0xFFE0A196)
    val ok = Color(0xFF7DB8A5)
    val warn = Color(0xFFD8AE5E)
    val warnSoft = Color(0x0DD8AE5E)
    val warnLine = Color(0x59D8AE5E)
}

val PlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
)

val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

/** The type scale of prototype/styles.css (typeface A, IBM Plex). */
@Immutable
data class VaultType(
    val displayL: TextStyle = TextStyle(
        fontFamily = PlexSans, fontWeight = FontWeight.Medium, fontSize = 28.sp,
        lineHeight = 34.sp, letterSpacing = (-0.02).em, color = Obsidian.text,
    ),
    val display: TextStyle = TextStyle(
        fontFamily = PlexSans, fontWeight = FontWeight.Medium, fontSize = 26.sp,
        lineHeight = 32.sp, letterSpacing = (-0.02).em, color = Obsidian.text,
    ),
    val section: TextStyle = TextStyle(
        fontFamily = PlexSans, fontWeight = FontWeight.Medium, fontSize = 19.sp,
        lineHeight = 24.sp, letterSpacing = (-0.01).em, color = Obsidian.text,
    ),
    val body: TextStyle = TextStyle(
        fontFamily = PlexSans, fontSize = 15.sp, lineHeight = 22.sp, color = Obsidian.text,
    ),
    val small: TextStyle = TextStyle(
        fontFamily = PlexSans, fontSize = 14.sp, lineHeight = 20.sp, color = Obsidian.text,
    ),
    val faint: TextStyle = TextStyle(
        fontFamily = PlexSans, fontSize = 13.sp, lineHeight = 18.sp, color = Obsidian.text3,
    ),
    val label: TextStyle = TextStyle(
        fontFamily = PlexSans, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.06.em,
        color = Obsidian.text3,
    ),
    val button: TextStyle = TextStyle(
        fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp,
    ),
    val mono: TextStyle = TextStyle(
        fontFamily = PlexMono, fontSize = 16.sp, lineHeight = 22.sp, color = Obsidian.text,
    ),
    val monoLarge: TextStyle = TextStyle(
        fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp,
        letterSpacing = 0.12.em, color = Obsidian.text,
    ),
)

val LocalVaultType = staticCompositionLocalOf { VaultType() }

@Composable
fun VaultaireTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Obsidian.text,
            onPrimary = Obsidian.bg,
            secondary = Obsidian.accent,
            onSecondary = Obsidian.bg,
            background = Obsidian.bg,
            onBackground = Obsidian.text,
            surface = Obsidian.bg,
            onSurface = Obsidian.text,
            surfaceVariant = Obsidian.raised,
            onSurfaceVariant = Obsidian.text2,
            outline = Obsidian.hair2,
            outlineVariant = Obsidian.hair,
            error = Obsidian.crit,
            onError = Obsidian.bg,
        ),
        content = content,
    )
}
