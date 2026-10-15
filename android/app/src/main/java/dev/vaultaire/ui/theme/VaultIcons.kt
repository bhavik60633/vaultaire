package dev.vaultaire.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The design's line icons (prototype/app.js `PATHS`): 24 × 24, 1.5 stroke, round caps and joins.
 * Generated from the prototype so the app and the mockup draw the same shapes.
 */
object VaultIcons {
    private val paths: Map<String, List<String>> = mapOf(
        "lock" to listOf("M7 11h10a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-6a2 2 0 0 1 2 -2z", "M8 11V8a4 4 0 0 1 8 0v3"),
        "sliders" to listOf("M4 7h10M18 7h2M4 17h4M12 17h8", "M14 7a2 2 0 1 0 4 0a2 2 0 1 0 -4 0", "M8 17a2 2 0 1 0 4 0a2 2 0 1 0 -4 0"),
        "search" to listOf("M4.5 11a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0 -13 0", "M16 16l4.5 4.5"),
        "plus" to listOf("M12 5v14M5 12h14"),
        "back" to listOf("M15 5l-7 7 7 7"),
        "chev" to listOf("M9 5l7 7-7 7"),
        "close" to listOf("M6 6l12 12M18 6L6 18"),
        "copy" to listOf("M11 9h7a2 2 0 0 1 2 2v7a2 2 0 0 1 -2 2h-7a2 2 0 0 1 -2 -2v-7a2 2 0 0 1 2 -2z", "M5 15V6a2 2 0 0 1 2-2h8"),
        "eye" to listOf("M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0"),
        "eyeOff" to listOf("M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0", "M4 4l16 16"),
        "face" to listOf("M4 8V6a2 2 0 0 1 2-2h2M16 4h2a2 2 0 0 1 2 2v2M20 16v2a2 2 0 0 1-2 2h-2M8 20H6a2 2 0 0 1-2-2v-2", "M9 9v1.5M15 9v1.5M12.5 9v4h-1", "M9 15.5c1.7 1.3 4.3 1.3 6 0"),
        "finger" to listOf("M7.5 19c1.2-2 1.5-4 1.5-7a3 3 0 0 1 6 0c0 1.5-.1 3-.4 4.3", "M12 12c0 3-.6 5.6-2 8", "M5 15.5c.5-1.3.5-2.3.5-3.5a6.5 6.5 0 0 1 11.6-4", "M18.3 10.5c.2.5.2 1 .2 1.5 0 2.2-.3 4.2-.9 6"),
        "shield" to listOf("M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6z", "M9 12l2 2 4-4"),
        "alert" to listOf("M12 4l9 16H3z", "M12 10v4.5M12 17.5v.01"),
        "check" to listOf("M5 12.5l4.5 4.5L19 7.5"),
        "checkCircle" to listOf("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0", "M8 12.5l2.8 2.8L16.5 9.5"),
        "errCircle" to listOf("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0", "M12 7.5v5.5M12 16.5v.01"),
        "refresh" to listOf("M19 12a7 7 0 1 1-2.05-4.95M19 4.5V8h-3.5"),
        "star" to listOf("M12 4l2.4 4.9 5.4.8-3.9 3.8.9 5.4-4.8-2.5-4.8 2.5.9-5.4-3.9-3.8 5.4-.8z"),
        "login" to listOf("M4 15a4 4 0 1 0 8 0a4 4 0 1 0 -8 0", "M11 12l8-8M16 7l2 2M14 9l2 2"),
        "card" to listOf("M5 6h14a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-8a2 2 0 0 1 2 -2z", "M3 10h18M7 15h3"),
        "bank" to listOf("M3 10l9-5 9 5M5 10v8M9.5 10v8M14.5 10v8M19 10v8M3 20h18"),
        "identity" to listOf("M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-10a2 2 0 0 1 2 -2z", "M6.5 11a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0", "M5.5 16.5c.7-1.5 2-2.2 3.5-2.2s2.8.7 3.5 2.2M15 10h3.5M15 13.5h3.5"),
        "note" to listOf("M6 3.5h8l4 4v13H6z", "M14 3.5v4h4M9 12h6M9 15.5h6"),
        "devkey" to listOf("M8.5 8L4.5 12l4 4M15.5 8l4 4-4 4M13.5 5.5l-3 13"),
        "membership" to listOf("M4 6h16v4a2 2 0 0 0 0 4v4H4v-4a2 2 0 0 0 0-4z", "M14 8v2M14 12.5v1M14 16v.01"),
        "other" to listOf("M4.8 12a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0 -2.4 0", "M10.8 12a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0 -2.4 0", "M16.8 12a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0 -2.4 0"),
        "up" to listOf("M12 19V5M6 11l6-6 6 6"),
        "down" to listOf("M12 5v14M6 13l6 6 6-6"),
        "pin" to listOf("M9 4h6l-1 6 3 3H7l3-3z", "M12 13v7"),
        "folder" to listOf("M3.5 7.5a2 2 0 0 1 2-2h4l2 2h7a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z"),
        "phone" to listOf("M6.5 4h3l1.5 4-2 1.5a11 11 0 0 0 5.5 5.5l1.5-2 4 1.5v3a2 2 0 0 1-2 2A15.5 15.5 0 0 1 4.5 6a2 2 0 0 1 2-2z"),
        "calendar" to listOf("M6 5.5h12a2 2 0 0 1 2 2v10.5a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2v-10.5a2 2 0 0 1 2 -2z", "M4 10h16M8.5 3.5v4M15.5 3.5v4"),
        "pinpad" to listOf("M7.5 3h9a2.5 2.5 0 0 1 2.5 2.5v13a2.5 2.5 0 0 1 -2.5 2.5h-9a2.5 2.5 0 0 1 -2.5 -2.5v-13a2.5 2.5 0 0 1 2.5 -2.5z", "M8.1 8a0.9 0.9 0 1 0 1.8 0a0.9 0.9 0 1 0 -1.8 0", "M11.1 8a0.9 0.9 0 1 0 1.8 0a0.9 0.9 0 1 0 -1.8 0", "M14.1 8a0.9 0.9 0 1 0 1.8 0a0.9 0.9 0 1 0 -1.8 0", "M8.1 12a0.9 0.9 0 1 0 1.8 0a0.9 0.9 0 1 0 -1.8 0", "M11.1 12a0.9 0.9 0 1 0 1.8 0a0.9 0.9 0 1 0 -1.8 0", "M14.1 12a0.9 0.9 0 1 0 1.8 0a0.9 0.9 0 1 0 -1.8 0", "M11.1 16a0.9 0.9 0 1 0 1.8 0a0.9 0.9 0 1 0 -1.8 0"),
        "codes" to listOf("M10 7h10M10 12h10M10 17h10", "M4.5 6.5l1.2-.7v3.4M4.3 11h2.2l-2.2 2.6h2.2M4.3 16h2.2l-1.1 1.2 1.1 1.3H4.3"),
    )

    private val cache = HashMap<String, ImageVector>()

    fun get(name: String): ImageVector = cache.getOrPut(name) {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in paths.getValue(name)) {
            builder.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        builder.build()
    }
}
