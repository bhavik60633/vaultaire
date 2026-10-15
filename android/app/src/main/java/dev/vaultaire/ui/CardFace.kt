package dev.vaultaire.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vaultaire.core.CardFinish
import dev.vaultaire.ui.theme.LocalVaultType
import dev.vaultaire.ui.theme.Obsidian
import dev.vaultaire.ui.theme.PlexMono
import dev.vaultaire.ui.theme.PlexSans
import dev.vaultaire.ui.theme.VIcon
import dev.vaultaire.ui.theme.tag
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch

/** What the card shows. Secrets arrive already masked, or revealed after a fingerprint. */
class CardLook(
    val bank: String?,
    val kind: String?,
    val holder: String?,
    val expiry: String?,
    val network: String?,
    val number: String,
    val hasCvv: Boolean,
    val hasPin: Boolean,
    val finish: CardFinish,
)

/** A finish's colours, from the design mockup (blueprint §15). */
private class Palette(
    val top: Color,
    val bottom: Color,
    val ink: Color,
    val ink2: Color,
    val line: Color,
    val glow: Color,
    val light: Boolean,
)

private fun palette(finish: CardFinish): Palette = when (finish) {
    CardFinish.OBSIDIAN -> Palette(
        Color(0xFF202023), Color(0xFF0D0D0F), Color(0xFFF2EEE6), Color(0xFFA8A39A),
        Color(0x24C9B38A), Color(0xFFC9B38A), light = false,
    )
    CardFinish.TITANIUM -> Palette(
        Color(0xFF3A3A41), Color(0xFF1F1F23), Color(0xFFF2EEE6), Color(0xFFB3AEA5),
        Color(0x14FFFFFF), Color(0xFFA9B1BE), light = false,
    )
    CardFinish.PLATINUM -> Palette(
        Color(0xFFF0EDE7), Color(0xFFD6D1C7), Color(0xFF1A1917), Color(0xFF57534C),
        Color(0x141A1917), Color(0xFFE6E1D6), light = true,
    )
    CardFinish.IVORY -> Palette(
        Color(0xFFFBF8F2), Color(0xFFE9E1D2), Color(0xFF1A1917), Color(0xFF5E5A53),
        Color(0x217A6240), Color(0xFFF1E3C4), light = true,
    )
    CardFinish.CHAMPAGNE -> Palette(
        Color(0xFFE2D1AE), Color(0xFFB39767), Color(0xFF1A1917), Color(0xFF3F3526),
        Color(0x3DFFFFFF), Color(0xFFE2C68C), light = true,
    )
}

private val CardShape = RoundedCornerShape(16.dp)

/**
 * The card at the top of its page: it floats and catches the light, tilts under a finger and
 * turns over with a swipe or the Turn button. The number is shown only after a fingerprint, the
 * back carries the CVV under the same rules, and a PIN is only ever a link (threat-model I11).
 */
@Composable
fun CardStage(
    look: CardLook,
    turned: Boolean,
    onTurn: () -> Unit,
    onNumber: () -> Unit,
    onCopyCvv: () -> Unit,
    onPin: () -> Unit,
    onFinish: (CardFinish) -> Unit,
) {
    val colors = palette(look.finish)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val motion = rememberMotionAllowed()

    val flip by animateFloatAsState(if (turned) 180f else 0f, tween(700, easing = FastOutSlowInEasing), label = "flip")
    val tiltX = remember { Animatable(0f) }
    val tiltY = remember { Animatable(0f) }
    var dragX by remember { mutableFloatStateOf(0f) }

    // A slow drift: the card breathes, sways a little and the light moves across it.
    val drift = if (motion) {
        rememberInfiniteTransition(label = "drift").animateFloat(
            0f, 1f, infiniteRepeatable(tween(7_000, easing = LinearEasing), RepeatMode.Restart), label = "t",
        ).value
    } else {
        0f
    }
    val wave = (2 * PI * drift).toFloat()
    val floatY = with(density) { (sin(wave) * 5).dp.toPx() }
    val swayX = sin(wave) * 1.6f
    val swayY = cos(wave) * 2.4f
    val angle = flip + tiltY.value + swayY
    val showBack = normalised(angle) > 90f
    val sheen = (0.5f + (tiltY.value + swayY) / 40f + (tiltX.value + swayX) / 80f).coerceIn(-0.2f, 1.2f)

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 18.dp)
                .drawBehind {
                    // The glow under the card, in the finish's own light: an ellipse that fades out
                    // well inside this box, so it never shows an edge.
                    val pulse = 0.38f + 0.12f * sin(wave)
                    val center = Offset(size.width / 2, size.height * 0.58f + floatY)
                    scale(scaleX = 1f, scaleY = 0.5f, pivot = center) {
                        drawCircle(
                            Brush.radialGradient(
                                listOf(colors.glow.copy(alpha = pulse), colors.glow.copy(alpha = pulse * 0.35f), Color.Transparent),
                                center = center,
                                radius = size.width * 0.5f,
                            ),
                            radius = size.width * 0.5f,
                            center = center,
                        )
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragX = 0f },
                        onDragEnd = {
                            if (abs(dragX) > 90.dp.toPx()) onTurn()
                            scope.launch { tiltX.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 120f)) }
                            scope.launch { tiltY.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 120f)) }
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            dragX += amount.x
                            scope.launch { tiltY.snapTo((tiltY.value + amount.x * 0.18f).coerceIn(-28f, 28f)) }
                            scope.launch { tiltX.snapTo((tiltX.value - amount.y * 0.18f).coerceIn(-20f, 20f)) }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.586f)
                    .graphicsLayer {
                        rotationY = angle
                        rotationX = tiltX.value + swayX
                        translationY = floatY
                        cameraDistance = 16f * density.density
                        shadowElevation = 22.dp.toPx()
                        shape = CardShape
                        clip = true
                        spotShadowColor = colors.glow
                        ambientShadowColor = colors.glow
                    },
            ) {
                if (showBack) {
                    // Drawn turned once more, so the back reads the right way round.
                    Box(Modifier.matchParentSize().graphicsLayer { rotationY = 180f }) {
                        CardBack(look, colors, sheen, onCopyCvv, onPin)
                    }
                } else {
                    CardFront(look, colors, sheen, onNumber)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FinishPicker(look.finish, onFinish)
            Spacer(Modifier.weight(1f))
            TurnButton(turned, onTurn)
        }
    }
}

/** The angle folded into 0..180, so a card tilted past its edge shows the other side. */
private fun normalised(angle: Float): Float {
    val a = ((angle % 360f) + 360f) % 360f
    return if (a > 180f) 360f - a else a
}

/** Motion follows the phone's animation setting: off means a still card. */
@Composable
private fun rememberMotionAllowed(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
}

/** The card's surface: the finish, its engraving and the moving light. */
private fun Modifier.surface(colors: Palette, sheen: Float): Modifier = this
    .background(Brush.linearGradient(listOf(colors.top, colors.bottom), start = Offset.Zero, end = Offset.Infinite))
    .drawWithContent {
        drawEngraving(colors.line)
        drawContent()
        val band = size.width * 0.55f
        val x = -band + (size.width + 2 * band) * sheen
        drawRect(
            Brush.linearGradient(
                listOf(
                    Color.Transparent,
                    Color.White.copy(alpha = if (colors.light) 0.42f else 0.12f),
                    Color.Transparent,
                ),
                start = Offset(x - band / 2, 0f),
                end = Offset(x + band / 2, size.height),
            ),
        )
        drawRoundRect(
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.03f))),
            cornerRadius = CornerRadius(16.dp.toPx()),
            style = Stroke(1.dp.toPx()),
        )
    }

/** Guilloché lines, as on a banknote: the same curves as the mockup's card. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEngraving(color: Color) {
    val w = size.width
    val h = size.height
    for (i in 0 until 16) {
        val path = Path()
        for (s in 0..70) {
            val x = s / 70f * w
            val y = (132 + 26 * sin(x / w * 2 * PI * 1.5 + i * 0.2) + 9 * sin(x / w * 2 * PI * 6 - i * 0.35)) / 221 * h
            if (s == 0) path.moveTo(x, y.toFloat()) else path.lineTo(x, y.toFloat())
        }
        drawPath(path, color, style = Stroke(0.8.dp.toPx()))
    }
}

private fun caps(color: Color, size: Int = 11) = TextStyle(
    fontFamily = PlexSans,
    fontSize = size.sp,
    letterSpacing = 1.6.sp,
    color = color,
)

@Composable
private fun BoxScope.CardFront(look: CardLook, colors: Palette, sheen: Float, onNumber: () -> Unit) {
    Column(
        Modifier
            .matchParentSize()
            .surface(colors, sheen)
            .tag("cardFace")
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 22.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                look.bank ?: "Card",
                style = TextStyle(fontFamily = PlexSans, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, color = colors.ink),
                modifier = Modifier.weight(1f),
            )
            look.kind?.let { Text(it.uppercase(), style = caps(colors.ink2, 10)) }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Chip()
            Spacer(Modifier.width(12.dp))
            Contactless(colors.ink2)
        }
        Spacer(Modifier.weight(1f))
        // The number, touched to show it; the touch area carries the action, the text stays part of
        // the card so the card reads as one.
        Box {
            Text(
                look.number,
                style = TextStyle(fontFamily = PlexMono, fontSize = 19.sp, letterSpacing = 1.4.sp, color = colors.ink),
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Box(
                Modifier
                    .matchParentSize()
                    .tag("cardNumber")
                    .clickable(role = Role.Button, onClick = onNumber)
                    .clearAndSetSemantics { contentDescription = "Show card number" },
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                look.expiry?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("VALID\nTHRU", style = caps(colors.ink2, 6).copy(lineHeight = 7.sp, letterSpacing = 0.8.sp))
                        Spacer(Modifier.width(6.dp))
                        Text(it, style = TextStyle(fontFamily = PlexMono, fontSize = 13.sp, color = colors.ink))
                    }
                }
                look.holder?.let { Text(it, style = caps(colors.ink, 12)) }
            }
            look.network?.let { NetworkMark(it, colors) }
        }
    }
}

@Composable
private fun BoxScope.CardBack(look: CardLook, colors: Palette, sheen: Float, onCopyCvv: () -> Unit, onPin: () -> Unit) {
    Column(
        Modifier
            .matchParentSize()
            .surface(colors, sheen)
            .tag("cardBack")
            .semantics(mergeDescendants = true) {},
    ) {
        Spacer(Modifier.height(18.dp))
        // The magnetic stripe.
        Box(
            Modifier
                .fillMaxWidth()
                .height(38.dp)
                .background(Brush.verticalGradient(listOf(Color(0xFF151515), Color(0xFF050505), Color(0xFF1C1C1C)))),
        )
        Spacer(Modifier.height(14.dp))
        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            // The signature strip, with the CVV at its end.
            Row(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFEDE8DF))
                    .drawBehind {
                        var x = 0f
                        while (x < size.width) {
                            drawLine(Color(0x1A7A6240), Offset(x, 0f), Offset(x + 12f, size.height), 1.5f)
                            x += 7f
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .height(36.dp)
                        .background(Color.White)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (look.hasCvv) {
                        Text("•••", style = TextStyle(fontFamily = PlexMono, fontSize = 16.sp, fontStyle = FontStyle.Italic, color = Color(0xFF1A1917)))
                    } else {
                        Text("No CVV stored", style = TextStyle(fontFamily = PlexSans, fontSize = 11.sp, color = Color(0xFF57534C)))
                    }
                }
            }
            if (look.hasCvv) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.ink.copy(alpha = 0.10f))
                        .border(1.dp, colors.ink.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
                        .tag("copyCvvOnCard")
                        .clickable(role = Role.Button, onClick = onCopyCvv)
                        .clearAndSetSemantics { contentDescription = "Copy CVV" },
                    contentAlignment = Alignment.Center,
                ) { VIcon("copy", tint = colors.ink, size = 18.dp) }
            }
        }
        Text(
            "CVV",
            style = caps(colors.ink2, 9),
            modifier = Modifier.padding(start = 18.dp, top = 4.dp),
        )
        Spacer(Modifier.weight(1f))
        Box(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VIcon("lock", tint = colors.ink2, size = 16.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (look.hasPin) "PIN stored separately" else "No PIN linked to this card",
                    style = TextStyle(fontFamily = PlexSans, fontSize = 13.sp, color = colors.ink),
                    modifier = Modifier.weight(1f),
                )
                if (look.hasPin) VIcon("chev", tint = colors.ink2, size = 16.dp)
            }
            if (look.hasPin) {
                Box(
                    Modifier
                        .matchParentSize()
                        .tag("pinOnCard")
                        .clickable(role = Role.Button, onClick = onPin)
                        .clearAndSetSemantics { contentDescription = "Open the PIN" },
                )
            }
        }
    }
}

/** The gold contact chip. */
@Composable
private fun Chip() {
    Canvas(Modifier.size(width = 40.dp, height = 30.dp)) {
        val r = CornerRadius(5.dp.toPx())
        drawRoundRect(
            Brush.linearGradient(listOf(Color(0xFFF1DDAE), Color(0xFFC9A764), Color(0xFFE8CF95))),
            cornerRadius = r,
        )
        val line = Color(0x66654A1E)
        val w = size.width
        val h = size.height
        drawLine(line, Offset(0f, h / 3), Offset(w * 0.36f, h / 3), 1.2f)
        drawLine(line, Offset(0f, h * 2 / 3), Offset(w * 0.36f, h * 2 / 3), 1.2f)
        drawLine(line, Offset(w * 0.64f, h / 3), Offset(w, h / 3), 1.2f)
        drawLine(line, Offset(w * 0.64f, h * 2 / 3), Offset(w, h * 2 / 3), 1.2f)
        drawRoundRect(line, Offset(w * 0.36f, h * 0.18f), Size(w * 0.28f, h * 0.64f), CornerRadius(3.dp.toPx()), style = Stroke(1.2f))
    }
}

/** The contactless waves. */
@Composable
private fun Contactless(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        for (i in 0 until 4) {
            val radius = size.minDimension * (0.18f + i * 0.16f)
            drawArc(
                color.copy(alpha = 0.85f),
                startAngle = -45f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(size.width * 0.1f - radius, size.height / 2 - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(1.6.dp.toPx()),
            )
        }
    }
}

/** The network, set in the card's ink; two rings for Mastercard. Never a copied logo. */
@Composable
private fun NetworkMark(network: String, colors: Palette) {
    Column(horizontalAlignment = Alignment.End) {
        if (network.equals("Mastercard", ignoreCase = true)) {
            Canvas(Modifier.size(width = 38.dp, height = 24.dp)) {
                val r = size.height / 2
                drawCircle(colors.ink.copy(alpha = 0.55f), r, Offset(r, r))
                drawCircle(colors.ink.copy(alpha = 0.30f), r, Offset(size.width - r, r))
            }
            Spacer(Modifier.height(2.dp))
        }
        Text(
            network,
            style = TextStyle(
                fontFamily = PlexSans,
                fontSize = if (network.length <= 4) 18.sp else 12.sp,
                fontWeight = FontWeight.Bold,
                fontStyle = if (network.equals("Visa", ignoreCase = true)) FontStyle.Italic else FontStyle.Normal,
                letterSpacing = 0.8.sp,
                color = colors.ink,
            ),
        )
    }
}

@Composable
private fun FinishPicker(current: CardFinish, onFinish: (CardFinish) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CardFinish.entries.forEach { f ->
            val colors = palette(f)
            val selected = f == current
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .selectable(selected = selected, role = Role.RadioButton) { onFinish(f) }
                    .tag("finish:${f.name.lowercase()}")
                    .clearAndSetSemantics { contentDescription = "${f.label} finish" }
                    .padding(6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 28.dp, height = 18.dp)
                        .then(
                            if (selected) {
                                Modifier.border(1.5.dp, Obsidian.accent, RoundedCornerShape(5.dp)).padding(2.5.dp)
                            } else {
                                Modifier
                            },
                        )
                        .clip(RoundedCornerShape(3.dp))
                        .background(Brush.linearGradient(listOf(colors.top, colors.bottom)))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(3.dp)),
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(current.label, style = LocalVaultType.current.small.copy(color = Obsidian.text2), softWrap = false)
    }
}

@Composable
private fun TurnButton(turned: Boolean, onTurn: () -> Unit) {
    Row(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Obsidian.hair2, RoundedCornerShape(8.dp))
            .tag("turnCard")
            .clickable(role = Role.Button, onClick = onTurn)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VIcon("refresh", tint = Obsidian.accent, size = 18.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            if (turned) "Front" else "Turn",
            style = LocalVaultType.current.small.copy(fontWeight = FontWeight.Medium),
            softWrap = false,
        )
    }
}

/** A card in miniature, in its finish, for list rows (prototype `.mini`). */
@Composable
fun CardThumb(finish: CardFinish, modifier: Modifier = Modifier) {
    val colors = palette(finish)
    Box(
        modifier
            .size(width = 30.dp, height = 19.dp)
            .clip(RoundedCornerShape(3.5.dp))
            .background(Brush.linearGradient(listOf(colors.top, colors.bottom)))
            .border(1.dp, Color.White.copy(alpha = if (colors.light) 0.30f else 0.12f), RoundedCornerShape(3.5.dp)),
    ) {
        Box(
            Modifier
                .padding(start = 4.dp, top = 6.dp)
                .size(width = 6.dp, height = 4.5.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(Color(0xFFD9BC7C)),
        )
    }
}
