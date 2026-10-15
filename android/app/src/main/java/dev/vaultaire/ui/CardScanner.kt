package dev.vaultaire.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.vaultaire.core.VaultEngine
import dev.vaultaire.scan.CardCamera
import dev.vaultaire.scan.CardTextReader
import dev.vaultaire.scan.ScannedCard
import dev.vaultaire.ui.theme.CapsLine
import dev.vaultaire.ui.theme.IconAction
import dev.vaultaire.ui.theme.LocalVaultType
import dev.vaultaire.ui.theme.Obsidian
import dev.vaultaire.ui.theme.tag

/**
 * The scanner: the camera's picture under a dark veil, with a card-shaped window edged in gold and
 * a line of light sweeping it. The camera runs only while this is on screen (ADR-009): it stops
 * when a card is read, when the scanner is closed, and when the vault locks, since locking takes
 * this screen away.
 */
@Composable
fun CardScanner(camera: CardCamera, onClose: () -> Unit, onCard: (ScannedCard) -> Unit) {
    val type = LocalVaultType.current
    val reader = remember { CardTextReader { line -> VaultEngine.findBank(line) } }
    val deliver by rememberUpdatedState(onCard)
    DisposableEffect(camera) {
        camera.start { lines -> reader.read(lines)?.let { deliver(it) } }
        onDispose { camera.stop() }
    }
    val sweep = rememberInfiniteTransition(label = "scan").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1_800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "line",
    ).value

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .tag("scanner")
            .semantics(mergeDescendants = true) {},
    ) {
        camera.Preview(Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val width = size.width - 48.dp.toPx()
            val height = width / 1.586f
            val left = 24.dp.toPx()
            val top = size.height * 0.42f - height / 2
            val radius = 16.dp.toPx()
            val window = RoundRect(left, top, left + width, top + height, CornerRadius(radius))
            // The veil, with the card's window cut out of it.
            val veil = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
                addRoundRect(window)
            }
            drawPath(veil, Color.Black.copy(alpha = 0.62f))
            drawRoundRect(
                Color.White.copy(alpha = 0.18f), Offset(left, top), Size(width, height), CornerRadius(radius),
                style = Stroke(1.dp.toPx()),
            )
            // Gold corners.
            val arm = 28.dp.toPx()
            val stroke = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
            listOf(
                Triple(Offset(left, top), 1f, 1f),
                Triple(Offset(left + width, top), -1f, 1f),
                Triple(Offset(left, top + height), 1f, -1f),
                Triple(Offset(left + width, top + height), -1f, -1f),
            ).forEach { (corner, dx, dy) ->
                val path = Path().apply {
                    moveTo(corner.x, corner.y + dy * arm)
                    lineTo(corner.x, corner.y + dy * radius)
                    quadraticTo(corner.x, corner.y, corner.x + dx * radius, corner.y)
                    lineTo(corner.x + dx * arm, corner.y)
                }
                drawPath(path, Obsidian.accent, style = stroke)
            }
            // The line of light sweeping the card.
            val y = top + 10.dp.toPx() + (height - 20.dp.toPx()) * sweep
            drawRect(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, Obsidian.accent.copy(alpha = 0.85f), Color.Transparent),
                    startX = left,
                    endX = left + width,
                ),
                topLeft = Offset(left + 12.dp.toPx(), y),
                size = Size(width - 24.dp.toPx(), 2.dp.toPx()),
            )
            drawRect(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Obsidian.accent.copy(alpha = 0.16f), Color.Transparent),
                    startY = y - 26.dp.toPx(),
                    endY = y + 26.dp.toPx(),
                ),
                topLeft = Offset(left + 12.dp.toPx(), y - 26.dp.toPx()),
                size = Size(width - 24.dp.toPx(), 52.dp.toPx()),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction("close", "Close scanner", onClose, Modifier.tag("closeScanner"), tint = Obsidian.text)
            Spacer(Modifier.width(4.dp))
            Text("Scan card", style = type.section)
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Hold the front of your card inside the frame", style = type.section, textAlign = TextAlign.Center)
            Text(
                "The number, expiry and name are read on this phone. The CVV is never read, no picture is kept, and nothing is saved until you tap Save.",
                style = type.small.copy(color = Obsidian.text2),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            CapsLine(BETA_WARNING)
        }
    }
}
