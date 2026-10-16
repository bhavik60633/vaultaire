// Vaultaire Precision Vector Icons: 1.5 dp line stroke on a 24 dp grid (blueprint §15)
package dev.vaultaire.spike

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.PathParser

object VaultIcons {
    const val LOCK = "M5 11h14v10H5z M8 11V8a4 4 0 0 1 8 0v3"
    const val SLIDERS = "M4 7h10 M18 7h2 M4 17h4 M12 17h8 M14 7a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M8 17a2 2 0 1 0 4 0a2 2 0 1 0 -4 0"
    const val SEARCH = "M11 4.5a6.5 6.5 0 1 0 0 13a6.5 6.5 0 1 0 0 -13 M16 16l4.5 4.5"
    const val PLUS = "M12 5v14 M5 12h14"
    const val CLOSE = "M6 6l12 12 M18 6L6 18"
    const val CHEVRON = "M9 5l7 7-7 7"
    const val SHIELD = "M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6z M9 12l2 2 4-4"
    const val STAR = "M12 4l2.4 4.9 5.4.8-3.9 3.8.9 5.4-4.8-2.5-4.8 2.5.9-5.4-3.9-3.8 5.4-.8z"
    const val CARD = "M3 6h18v12H3z M3 10h18 M7 15h3"
    const val LOGIN = "M8 11a4 4 0 1 0 0 8a4 4 0 1 0 0 -8 M11 12l8-8 M16 7l2 2 M14 9l2 2"
    const val DEVKEY = "M8.5 8L4.5 12l4 4 M15.5 8l4 4-4 4 M13.5 5.5l-3 13"
    const val CODES = "M10 7h10 M10 12h10 M10 17h10 M4.5 6.5l1.2-.7v3.4 M4.3 11h2.2l-2.2 2.6h2.2 M4.3 16h2.2l-1.1 1.2 1.1 1.3H4.3"
    const val BANK = "M3 10l9-5 9 5 M5 10v8 M9.5 10v8 M14.5 10v8 M19 10v8 M3 20h18"
    const val IDENTITY = "M3 5h18v14H3z M6.5 11a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0 M5.5 16.5c.7-1.5 2-2.2 3.5-2.2s2.8.7 3.5 2.2 M15 10h3.5 M15 13.5h3.5"
    const val NOTE = "M6 3.5h8l4 4v13H6z M14 3.5v4h4 M9 12h6 M9 15.5h6"
    const val MEMBERSHIP = "M4 6h16v4a2 2 0 0 0 0 4v4H4v-4a2 2 0 0 0 0-4z M14 8v2 M14 12.5v1 M14 16v.01"
    const val OTHER = "M5 12a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0 -2.4 0 M11 12a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0 -2.4 0 M17 12a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0 -2.4 0"
    const val CALENDAR = "M4 5.5h16v14.5H4z M4 10h16 M8.5 3.5v4 M15.5 3.5v4"
    const val COPY = "M9 9h11v11H9z M5 15V6a2 2 0 0 1 2-2h8"
    const val EYE = "M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0"
    const val CLOCK = "M12 4a8 8 0 1 0 0 16a8 8 0 1 0 0 -16 M12 8v4l3 2"
    const val EYE_OFF = "M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0 M4 4l16 16"
    const val QR = "M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h3v3h-3z M18 14h3v3h-3z M14 18h3v3h-3z M18 18h3v3h-3z"
    const val BACK = "M15 5l-7 7 7 7"
    const val CHECK = "M5 12.5l4.5 4.5L19 7.5"
    const val REFRESH = "M19 12a7 7 0 1 1-2.05-4.95 M19 4.5V8h-3.5"
    const val TRASH = "M4 7h16 M10 11v6 M14 11v6 M5 7l1 12a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2l1-12 M9 7V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v3"
    const val KEY = "M21 2l-2 2m-1.5 1.5L14 9a6 6 0 1 0 2.5 4.5L19 11h2v-2h2V7h-2V5h-2V3z"
}

@Composable
fun VaultIcon(
    pathData: String,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    tint: Color = ColorTextPrimary,
    strokeWidth: Dp = 1.5.dp
) {
    val composePath = remember(pathData) {
        val androidPath = PathParser.createPathFromPathData(pathData)
        androidPath.asComposePath()
    }

    Canvas(modifier = modifier.size(size)) {
        val scaleX = this.size.width / 24f
        val scaleY = this.size.height / 24f
        withTransform({
            scale(scaleX, scaleY, Offset.Zero)
        }) {
            drawPath(
                path = composePath,
                color = tint,
                style = Stroke(
                    width = strokeWidth.toPx() / scaleX,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}
