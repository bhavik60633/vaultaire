package dev.vaultaire.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.vaultaire.ui.theme.LocalVaultType
import dev.vaultaire.ui.theme.Obsidian
import dev.vaultaire.ui.theme.VIcon
import dev.vaultaire.ui.theme.tag

internal val R12 = RoundedCornerShape(12.dp)

/** Rises softly into place when the page opens; later blocks follow a beat behind. */
fun Modifier.appear(order: Int): Modifier = composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMillis = 420, delayMillis = 45 * order, easing = FastOutSlowInEasing))
    }
    graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 14.dp.toPx()
    }
}

/** A raised group of rows with hairlines between them (prototype `.list`). */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(R12)
            .background(Brush.verticalGradient(listOf(Obsidian.raised, Obsidian.raised.copy(alpha = 0.82f))))
            .border(1.dp, Obsidian.hair, R12),
        content = content,
    )
}

@Composable
fun Divider() {
    Box(
        Modifier
            .padding(start = 16.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(Obsidian.hair),
    )
}

/** "Favourites 3" and an optional small round add button. */
@Composable
fun SectionHeader(title: String, count: Int?, addTag: String? = null, addLabel: String = "Add", onAdd: (() -> Unit)? = null) {
    val type = LocalVaultType.current
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = type.section, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (count != null) {
                Spacer(Modifier.width(8.dp))
                Text("$count", style = type.small.copy(color = Obsidian.text3))
            }
        }
        if (addTag != null && onAdd != null) RoundAdd(addTag, addLabel, onAdd)
    }
}

@Composable
fun RoundAdd(tag: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Obsidian.accentSoft)
            .border(1.dp, Obsidian.accent.copy(alpha = 0.35f), CircleShape)
            .tag(tag)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { VIcon("plus", tint = Obsidian.accent, size = 18.dp) }
}

/** "Protections on · Locks after 2 min": the vault's state at a glance; opens the settings. */
@Composable
fun ProtectionPill(detail: String, onClick: () -> Unit) {
    val type = LocalVaultType.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(R12)
            .background(Obsidian.raised)
            .border(1.dp, Obsidian.hair, R12)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VIcon("shield", tint = Obsidian.ok, size = 18.dp)
        Spacer(Modifier.width(10.dp))
        Text("Protections on", style = type.small.copy(fontWeight = FontWeight.Medium), maxLines = 1)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(4.dp).clip(CircleShape).background(Obsidian.accent))
        Spacer(Modifier.width(10.dp))
        Text(
            detail,
            style = type.small.copy(color = Obsidian.text2),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        VIcon("chev", tint = Obsidian.text3, size = 16.dp)
    }
}

/** A warning on one line with its Dismiss beside it (prototype home). */
@Composable
fun InlineNotice(text: String, action: String, onAction: () -> Unit, textModifier: Modifier = Modifier) {
    val type = LocalVaultType.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(R12)
            .background(Obsidian.warnSoft)
            .border(1.dp, Obsidian.warnLine, R12)
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VIcon("alert", tint = Obsidian.warn, size = 18.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = type.small, modifier = Modifier.weight(1f).then(textModifier))
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = onAction)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) { Text(action, style = type.small.copy(color = Obsidian.warn, fontWeight = FontWeight.SemiBold), maxLines = 1) }
    }
}

/**
 * Equal segments that always fit the width, whatever the text size; the chosen one is lifted and
 * edged in gold. Each segment is selectable under its own tag ("autoLock:120").
 */
@Composable
fun Segmented(options: List<Pair<Long, String>>, current: Long, tagPrefix: String, onPick: (Long) -> Unit) {
    val type = LocalVaultType.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Obsidian.bg.copy(alpha = 0.55f))
            .border(1.dp, Obsidian.hair2, RoundedCornerShape(10.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, label) ->
            val selected = value == current
            val fill by animateColorAsState(if (selected) Obsidian.elev else Color.Transparent, tween(220), label = "fill")
            val edge by animateColorAsState(
                if (selected) Obsidian.accent.copy(alpha = 0.45f) else Color.Transparent, tween(220), label = "edge",
            )
            val ink by animateColorAsState(if (selected) Obsidian.text else Obsidian.text2, tween(220), label = "ink")
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(fill)
                    .border(1.dp, edge, RoundedCornerShape(8.dp))
                    .selectable(selected = selected, role = Role.RadioButton) { onPick(value) }
                    .tag("$tagPrefix:$value")
                    .padding(horizontal = 2.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = type.small.copy(color = ink, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

/** A settings row: what it is on the left, its current value on the right (prototype settings). */
@Composable
fun InfoRow(label: String, value: String) {
    val type = LocalVaultType.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = type.body)
        Spacer(Modifier.width(16.dp))
        Text(value, style = type.small.copy(color = Obsidian.text2), modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

/** A small uppercase label above a group ("PROTECTION"). */
@Composable
fun GroupLabel(text: String) {
    Text(
        text.uppercase(),
        style = LocalVaultType.current.label,
        modifier = Modifier.padding(start = 4.dp, top = 10.dp),
    )
}
