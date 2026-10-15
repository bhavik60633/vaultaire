package dev.vaultaire.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val Radius12 = RoundedCornerShape(12.dp)

/** The page background, with the 24 dp side padding of the design. */
@Composable
fun Page(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxSize()
            .background(Obsidian.bg)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        content = content,
    )
}

@Composable
fun VIcon(name: String, modifier: Modifier = Modifier, tint: Color = Obsidian.text2, size: Dp = 22.dp) {
    Icon(VaultIcons.get(name), contentDescription = null, tint = tint, modifier = modifier.size(size))
}

/** Cream, full width, 52 dp: the one main action on a screen. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: String? = null,
) {
    VaultButton(text, onClick, modifier, enabled, icon, filled = true)
}

/** Outlined with a hairline: the second choice. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: String? = null,
) {
    VaultButton(text, onClick, modifier, enabled, icon, filled = false)
}

@Composable
private fun VaultButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    icon: String?,
    filled: Boolean,
) {
    val type = LocalVaultType.current
    val background = when {
        !enabled -> Obsidian.raised
        filled -> Obsidian.text
        else -> Color.Transparent
    }
    val content = when {
        !enabled -> Obsidian.text3
        filled -> Obsidian.bg
        else -> Obsidian.text
    }
    val border = if (filled && enabled) Color.Transparent else if (enabled) Obsidian.hair2 else Obsidian.hair
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(Radius12)
            .background(background)
            .border(1.dp, border, Radius12)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            VIcon(icon, tint = content, size = 20.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = type.button.copy(color = content))
    }
}

/** A quiet text-only button. */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(Radius12)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = LocalVaultType.current.body.copy(color = Obsidian.text2))
    }
}

/** A 44 dp icon button, as in the top bars. */
@Composable
fun IconAction(name: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Obsidian.text2) {
    Box(
        modifier
            .size(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        VIcon(name, tint = tint)
    }
}

/** Small uppercase label with letter spacing ("VAULT PASSWORD"). */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = LocalVaultType.current.label, modifier = modifier)
}

/** A text field in the design: raised, hairline border, 12 dp corners, 52 dp tall. */
@Composable
fun VaultField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    mono: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
    fieldModifier: Modifier = Modifier,
) {
    val type = LocalVaultType.current
    var shown by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(label)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                enabled = enabled,
                singleLine = true,
                textStyle = (if (mono || secret) type.mono else type.body).copy(color = Obsidian.text),
                cursorBrush = SolidColor(Obsidian.accent),
                visualTransformation = if (secret && !shown) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (secret) KeyboardType.Password else keyboardType,
                    autoCorrectEnabled = false,
                ),
                modifier = fieldModifier
                    .weight(1f)
                    .height(52.dp)
                    .clip(Radius12)
                    .background(Obsidian.raised)
                    .border(1.dp, Obsidian.hair2, Radius12)
                    .padding(horizontal = 14.dp),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) { inner() }
                },
            )
            if (secret) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(Radius12)
                        .border(1.dp, Obsidian.hair2, Radius12)
                        .clickable(role = Role.Button) { shown = !shown }
                        .clearAndSetSemantics { contentDescription = if (shown) "Hide" else "Show" },
                    contentAlignment = Alignment.Center,
                ) { VIcon(if (shown) "eyeOff" else "eye") }
            }
        }
    }
}

/** A warning or information box with an icon (prototype `.notice`). */
@Composable
fun Notice(text: String, modifier: Modifier = Modifier, icon: String = "alert", textModifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(Radius12)
            .background(Obsidian.warnSoft)
            .border(1.dp, Obsidian.warnLine, Radius12)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VIcon(icon, tint = Obsidian.warn, size = 20.dp)
        Text(text, style = LocalVaultType.current.small, modifier = textModifier)
    }
}

/** "STEP 2 OF 3 · SETUP" with one bar per step. */
@Composable
fun StepHeader(step: Int, of: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FieldLabel("Step $step of $of · Setup")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(of) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(2.dp)
                        .background(if (i < step) Obsidian.accent else Obsidian.hair2),
                )
            }
        }
    }
}

/** A raised panel with a hairline border. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(Radius12)
            .background(Obsidian.raised)
            .border(1.dp, Obsidian.hair, Radius12)
            .padding(16.dp),
        content = content,
    )
}

/**
 * A line of small, letter-spaced capitals. Screen readers and tests read [text] as written; only
 * the drawing is in capitals, as in the design.
 */
@Composable
fun CapsLine(text: String, modifier: Modifier = Modifier, color: Color = Obsidian.text3) {
    Text(
        text.uppercase().replace(". ", " · ").trimEnd('.'),
        style = LocalVaultType.current.label.copy(color = color),
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { this.text = AnnotatedString(text) },
    )
}

/**
 * Guilloché: closed polar waves r(θ) = base + amp·sin(kθ + φ), the engraved line work of
 * banknotes (prototype `rosette()`).
 */
@Composable
fun Rosette(modifier: Modifier, bands: List<Band>, color: Color = Obsidian.accent.copy(alpha = 0.22f)) {
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val scale = size.minDimension / 300f
        for (band in bands) {
            for (i in 0 until band.lines) {
                val phi = i * 2 * PI / (band.k * band.lines)
                val path = Path()
                for (s in 0..480) {
                    val t = s / 480.0 * 2 * PI
                    val r = (band.base + band.amp * sin(band.k * t + phi)) * scale
                    val x = (c.x + r * cos(t)).toFloat()
                    val y = (c.y + r * sin(t)).toFloat()
                    if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(width = 0.5.dp.toPx()))
            }
        }
    }
}

data class Band(val base: Double, val amp: Double, val k: Int, val lines: Int)

/** The lock screen's emblem: rosette, a gold arc on a faint track, and the lock in a dark core. */
@Composable
fun LockEmblem(modifier: Modifier = Modifier) {
    Box(modifier.size(260.dp), contentAlignment = Alignment.Center) {
        Rosette(
            Modifier.fillMaxSize(),
            listOf(Band(132.0, 8.0, 28, 9), Band(108.0, 5.0, 36, 7)),
        )
        Canvas(Modifier.size(140.dp)) {
            val stroke = 1.5.dp.toPx()
            drawCircle(Color(0x1AFFF5E6), style = Stroke(stroke))
            drawArc(
                Obsidian.accent, startAngle = -90f, sweepAngle = 270f, useCenter = false,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Box(
            Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFF1E1E21), Color(0xFF111113))))
                .border(1.dp, Obsidian.hair, CircleShape),
            contentAlignment = Alignment.Center,
        ) { VIcon("lock", tint = Obsidian.accent, size = 36.dp) }
    }
}

@Composable
fun RowScope.Grow() = Spacer(Modifier.weight(1f))

@Composable
fun ColumnScope.Grow() = Spacer(Modifier.weight(1f))

/** Tag helper so screens read like the tests. */
fun Modifier.tag(name: String): Modifier = testTag(name)
