package dev.vaultaire.spike

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ----------------------------------------------------
// GUILLOCHÉ ROSETTE (Polar curve line art, blueprint §15)
// ----------------------------------------------------
@Composable
fun GuillocheRosetteCanvas() {
    val infiniteTransition = rememberInfiniteTransition(label = "rosetteRotation")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(45000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .rotate(angle)
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val outerRadius = size.width * 0.46f

        drawCircle(
            color = ColorAccentGold.copy(alpha = 0.15f),
            radius = outerRadius,
            center = center,
            style = Stroke(width = 1f)
        )
        drawCircle(
            color = ColorAccentGold.copy(alpha = 0.08f),
            radius = outerRadius * 0.72f,
            center = center,
            style = Stroke(width = 1f)
        )

        val k = 28f
        val lines = 8
        for (i in 0 until lines) {
            val phi = (i * 2 * PI.toFloat()) / (k * lines)
            val path = Path()
            val steps = 240
            for (s in 0..steps) {
                val t = (s.toFloat() / steps) * 2 * PI.toFloat()
                val r = (outerRadius * 0.78f) + 12f * sin(k * t + phi)
                val x = center.x + r * cos(t)
                val y = center.y + r * sin(t)
                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(
                path = path,
                color = ColorAccentGold.copy(alpha = 0.22f),
                style = Stroke(width = 1.2f)
            )
        }
    }
}
