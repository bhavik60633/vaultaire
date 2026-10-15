package dev.vaultaire.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.vaultaire.core.BankAccountInput
import dev.vaultaire.core.CardFinish
import dev.vaultaire.core.CardFilter
import dev.vaultaire.core.CardInput
import dev.vaultaire.core.CardKind
import dev.vaultaire.core.EntryId
import dev.vaultaire.core.EntryKind
import dev.vaultaire.core.EntrySummary
import dev.vaultaire.core.FieldView
import dev.vaultaire.core.PinInput
import dev.vaultaire.core.PinPurpose
import dev.vaultaire.core.Scope
import dev.vaultaire.core.SecretInput
import dev.vaultaire.core.VaultEngine
import dev.vaultaire.core.VaultException
import dev.vaultaire.core.VaultSettings
import dev.vaultaire.security.ClipboardGuard
import dev.vaultaire.security.Sensitivity
import dev.vaultaire.ui.theme.FieldLabel
import dev.vaultaire.ui.theme.GhostButton
import dev.vaultaire.ui.theme.Grow
import dev.vaultaire.ui.theme.IconAction
import dev.vaultaire.ui.theme.LocalVaultType
import dev.vaultaire.ui.theme.Notice
import dev.vaultaire.ui.theme.Obsidian
import dev.vaultaire.ui.theme.PrimaryButton
import dev.vaultaire.ui.theme.SecondaryButton
import dev.vaultaire.ui.theme.VIcon
import dev.vaultaire.ui.theme.VaultField
import dev.vaultaire.ui.theme.tag
import java.util.Calendar
import kotlinx.coroutines.launch

/** Where the user is inside the unlocked vault. Kept in memory only. */
sealed class Page {
    data object Home : Page()
    data class Bank(val scope: Scope) : Page()
    data class Entry(val id: EntryId, val from: Page) : Page()
    data class EditCard(val id: EntryId?, val bankId: String?, val from: Page) : Page()
    data class EditPin(val id: EntryId?, val from: Page) : Page()
    data class EditAccount(val id: EntryId?, val from: Page) : Page()
    data object Settings : Page()
}

/** The page to open after an unlock: the last bank and filter, if one was being looked at. */
fun startPage(data: VaultData): Page =
    try {
        data.session().lastCardView()?.let { Page.Bank(it.first) } ?: Page.Home
    } catch (e: VaultException) {
        Page.Home
    }

private val R12 = RoundedCornerShape(12.dp)

/** The unlocked vault: home, bank pages, entry pages, forms and settings. */
@Composable
fun VaultArea(
    data: VaultData,
    start: Page,
    failures: Int,
    onDismissFailures: () -> Unit,
    onLock: () -> Unit,
    frame: @Composable (actions: @Composable ColumnScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) -> Unit,
) {
    var page by remember { mutableStateOf(start) }
    var version by remember { mutableIntStateOf(0) }
    val changed: () -> Unit = { version++ }
    val session = data.session()

    fun go(next: Page) {
        if (next is Page.Home) runCatching { session.clearLastCardView() }
        page = next
    }

    when (val p = page) {
        Page.Home -> HomePage(data, version, failures, onDismissFailures, onLock, ::go, frame)
        is Page.Bank -> BankPage(data, p.scope, version, ::go, frame)
        is Page.Entry -> key(p.id) { EntryPage(data, p.id, version, changed, ::go, back = { go(p.from) }, frame) }
        is Page.EditCard -> CardForm(data, p.id, p.bankId, changed, done = { go(it ?: p.from) }, frame)
        is Page.EditPin -> PinForm(data, p.id, changed, done = { go(it ?: p.from) }, frame)
        is Page.EditAccount -> AccountForm(data, p.id, changed, done = { go(it ?: p.from) }, frame)
        Page.Settings -> SettingsPage(data, back = { go(Page.Home) }, frame)
    }
}

private typealias Frame = @Composable (actions: @Composable ColumnScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) -> Unit

// ---------- home ----------

@Composable
private fun HomePage(
    data: VaultData,
    version: Int,
    failures: Int,
    onDismiss: () -> Unit,
    onLock: () -> Unit,
    go: (Page) -> Unit,
    frame: Frame,
) {
    val type = LocalVaultType.current
    val session = data.session()
    var query by remember { mutableStateOf("") }
    val all = remember(version) { session.summaries() }
    val groups = remember(version) { session.bankGroups() }

    frame({}) {
        Column(Modifier.tag("home"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    FieldLabel("Vault")
                    Text(greeting(), style = type.display)
                }
                IconAction("sliders", "Settings", { go(Page.Settings) }, Modifier.tag("settings"))
                IconAction("lock", "Lock", onLock, Modifier.tag("lock"), tint = Obsidian.accent)
            }
            if (failures > 0) {
                val noun = if (failures == 1) "attempt" else "attempts"
                Notice("$failures failed password $noun since your last unlock", textModifier = Modifier.tag("error"))
                GhostButton("Dismiss", onDismiss)
            }
            SearchField(query, all.size) { query = it }
            if (query.isNotBlank()) {
                val found = remember(version, query) { session.search(query) }
                if (found.isEmpty()) {
                    Text("Nothing found", style = type.section)
                } else {
                    Group { found.forEach { EntryRow(it, Modifier.tag("entry:${it.id.hex}")) { go(Page.Entry(it.id, Page.Home)) } } }
                }
            } else {
                HomeSections(data, all, groups, go)
            }
        }
    }
}

@Composable
private fun HomeSections(data: VaultData, all: List<EntrySummary>, groups: List<dev.vaultaire.core.BankGroup>, go: (Page) -> Unit) {
    val type = LocalVaultType.current
    val cards = all.filter { it.kind == EntryKind.CARD }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Browse by bank", style = type.section)
        Spacer(Modifier.width(8.dp))
        Text("${cards.size}", style = type.small.copy(color = Obsidian.text3))
        Grow()
        SmallButton("Add card", "plus", Modifier.tag("addCard")) { go(Page.EditCard(null, null, Page.Home)) }
    }
    if (all.isEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Nothing here yet", style = type.display)
            Text("Cards, PINs and bank accounts you add will appear here.", style = type.small.copy(color = Obsidian.text2))
        }
    }
    if (cards.isNotEmpty()) {
        val tiles = listOf(TileInfo("all", "All cards", cards.size, cards.count { it.cardKind == CardKind.DEBIT }, cards.count { it.cardKind == CardKind.CREDIT }, Scope.All)) +
            groups.map { g ->
                TileInfo(g.bankId ?: "unassigned", g.name, g.total, g.debit, g.credit, g.bankId?.let { Scope.Bank(it) } ?: Scope.Unassigned)
            }
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { t -> BankTile(t, Modifier.weight(1f)) { go(Page.Bank(t.scope)) } }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    val favourites = all.filter { it.favourite }
    if (favourites.isNotEmpty()) Section("Favourites", favourites, "favourites", go)
    val accounts = all.filter { it.kind == EntryKind.BANK_ACCOUNT }
    Section("Bank accounts", accounts, "section:accounts", go, addTag = "addAccount") { go(Page.EditAccount(null, Page.Home)) }
    val pins = all.filter { it.kind == EntryKind.PIN }
    Section("PINs", pins, "section:pins", go, addTag = "addPin") { go(Page.EditPin(null, Page.Home)) }
}

private data class TileInfo(val key: String, val name: String, val total: Int, val debit: Int, val credit: Int, val scope: Scope)

private fun cardCount(n: Int) = if (n == 1) "1 card" else "$n cards"

private fun kindCounts(debit: Int, credit: Int): String =
    listOfNotNull(if (debit > 0) "$debit debit" else null, if (credit > 0) "$credit credit" else null).joinToString(" · ")

@Composable
private fun BankTile(t: TileInfo, modifier: Modifier, onClick: () -> Unit) {
    val type = LocalVaultType.current
    val unassigned = t.scope == Scope.Unassigned
    Column(
        modifier
            .clip(R12)
            .background(Obsidian.raised)
            .border(1.dp, if (unassigned) Obsidian.hair2 else Obsidian.hair, R12)
            .clickable(role = Role.Button, onClick = onClick)
            .tag("bank:${t.key}")
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(t.name, style = type.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), modifier = Modifier.weight(1f))
            Text("${t.total}", style = type.small.copy(color = Obsidian.accent))
        }
        Text(kindCounts(t.debit, t.credit).ifEmpty { cardCount(t.total) }, style = type.faint.copy(color = Obsidian.text2))
    }
}

@Composable
private fun Section(
    title: String,
    entries: List<EntrySummary>,
    tag: String,
    go: (Page) -> Unit,
    addTag: String? = null,
    onAdd: (() -> Unit)? = null,
) {
    val type = LocalVaultType.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = type.section)
        Spacer(Modifier.width(8.dp))
        Text("${entries.size}", style = type.small.copy(color = Obsidian.text3))
        Grow()
        if (addTag != null && onAdd != null) SmallButton("Add", "plus", Modifier.tag(addTag), onAdd)
    }
    if (entries.isNotEmpty()) {
        Column(
            Modifier
                .tag(tag)
                .semantics(mergeDescendants = true) {}
                .clip(R12)
                .border(1.dp, Obsidian.hair, R12),
        ) {
            entries.forEach { e ->
                // Taps without a merge boundary, so the section reads as one list.
                EntryRowContent(
                    e,
                    Modifier
                        .semantics { role = Role.Button; onClick { go(Page.Entry(e.id, Page.Home)); true } }
                        .pointerInput(e.id) { detectTapGestures { go(Page.Entry(e.id, Page.Home)) } },
                )
            }
        }
    }
}

@Composable
private fun SearchField(query: String, count: Int, onChange: (String) -> Unit) {
    val type = LocalVaultType.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(R12)
            .background(Obsidian.raised)
            .border(1.dp, Obsidian.hair, R12)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VIcon("search", size = 20.dp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text("Search $count items", style = type.body.copy(color = Obsidian.text3))
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = type.body,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Obsidian.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .tag("search"),
            )
        }
    }
}

@Composable
private fun SmallButton(text: String, icon: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Obsidian.hair2, RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VIcon(icon, tint = Obsidian.accent, size = 18.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, style = LocalVaultType.current.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
    }
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(R12)
            .border(1.dp, Obsidian.hair, R12),
        content = content,
    )
}

/** A list row: title, then kind · network · last four. Never a full number, CVV or PIN. */
@Composable
private fun EntryRow(e: EntrySummary, modifier: Modifier, onClick: () -> Unit) {
    EntryRowContent(e, modifier.clickable(role = Role.Button, onClick = onClick))
}

@Composable
private fun EntryRowContent(e: EntrySummary, modifier: Modifier) {
    val type = LocalVaultType.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Obsidian.elev)
                .border(1.dp, Obsidian.hair, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            VIcon(
                when (e.kind) {
                    EntryKind.CARD -> "card"
                    EntryKind.PIN -> "pinpad"
                    EntryKind.BANK_ACCOUNT -> "bank"
                    EntryKind.OTHER -> "other"
                },
                size = 20.dp,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, style = type.body)
            e.subtitle?.let { Text(it, style = type.faint.copy(color = Obsidian.text2)) }
        }
        if (e.favourite) VIcon("star", tint = Obsidian.accent, size = 14.dp)
        VIcon("chev", size = 18.dp, tint = Obsidian.text3)
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

// ---------- a bank's page ----------

@Composable
private fun BankPage(data: VaultData, scope: Scope, version: Int, go: (Page) -> Unit, frame: Frame) {
    val type = LocalVaultType.current
    val session = data.session()
    val saved = remember { runCatching { session.lastCardView() }.getOrNull() }
    var filter by remember { mutableStateOf(if (saved?.first == scope) saved.second else CardFilter.ALL) }
    val all = remember(version) { session.cards(scope, CardFilter.ALL) }
    val shown = remember(version, filter) { session.cards(scope, filter) }
    val name = when (scope) {
        Scope.All -> "All cards"
        Scope.Unassigned -> "Other / Unassigned"
        is Scope.Bank -> session.bankName(scope.bankId) ?: scope.bankId
    }
    fun choose(f: CardFilter) {
        filter = f
        runCatching { session.setLastCardView(scope, f) }
    }
    androidx.compose.runtime.LaunchedEffect(scope) { runCatching { session.setLastCardView(scope, filter) } }

    val debit = all.count { it.cardKind == CardKind.DEBIT }
    val credit = all.count { it.cardKind == CardKind.CREDIT }
    frame({}) {
        IconAction("back", "Back to vault", { go(Page.Home) }, Modifier.tag("back"))
        Column(Modifier.tag("bankPage").semantics(mergeDescendants = true) {}) {
            FieldLabel(if (scope == Scope.All) "Cards" else "Bank")
            Text(name, style = type.display)
            Text(
                listOf(cardCount(all.size), kindCounts(debit, credit)).filter { it.isNotEmpty() }.joinToString(" · "),
                style = type.small.copy(color = Obsidian.text2),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FieldLabel("Show")
            Spacer(Modifier.width(12.dp))
            Row(
                Modifier
                    .clip(R12)
                    .border(1.dp, Obsidian.hair2, R12)
                    .padding(3.dp),
            ) {
                FilterChip("All ${all.size}", filter == CardFilter.ALL, "filter:all") { choose(CardFilter.ALL) }
                FilterChip("Credit $credit", filter == CardFilter.CREDIT, "filter:credit") { choose(CardFilter.CREDIT) }
                FilterChip("Debit $debit", filter == CardFilter.DEBIT, "filter:debit") { choose(CardFilter.DEBIT) }
            }
        }
        if (shown.isEmpty()) {
            val word = when (filter) {
                CardFilter.ALL -> ""
                CardFilter.CREDIT -> " credit"
                CardFilter.DEBIT -> " debit"
            }
            Text("No $name$word cards", style = type.section)
            Text("Try another filter.", style = type.small.copy(color = Obsidian.text2))
        } else {
            Group { shown.forEach { EntryRow(it, Modifier.tag("entry:${it.id.hex}")) { go(Page.Entry(it.id, Page.Bank(scope))) } } }
        }
        val bankId = (scope as? Scope.Bank)?.bankId
        SecondaryButton(
            if (bankId != null) "Add $name card" else "Add card",
            { go(Page.EditCard(null, bankId, Page.Bank(scope))) },
            Modifier.tag("addCardHere"),
            icon = "plus",
        )
    }
}

@Composable
fun FilterChip(text: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Obsidian.elev else androidx.compose.ui.graphics.Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .tag(tag)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text, style = LocalVaultType.current.small.copy(color = if (selected) Obsidian.text else Obsidian.text2))
    }
}

// ---------- an entry's page ----------

private fun Int.tier(): Sensitivity = when (this) {
    1 -> Sensitivity.NORMAL
    2 -> Sensitivity.CONCEALED
    3 -> Sensitivity.CRITICAL
    else -> Sensitivity.CRITICAL_STRICT
}

private fun masked(field: FieldView, last4: String?): String = when (field.key) {
    "card.number" -> "•••• •••• •••• ${last4 ?: ""}".trimEnd()
    "card.cvv" -> "•••"
    else -> if (field.kind == 15) "••••" else "••••••••"
}

private fun grouped(key: String, value: String): String =
    if (key == "card.number") value.chunked(4).joinToString(" ") else value

@Composable
private fun EntryPage(
    data: VaultData,
    id: EntryId,
    version: Int,
    changed: () -> Unit,
    go: (Page) -> Unit,
    back: () -> Unit,
    frame: Frame,
) {
    val type = LocalVaultType.current
    val scope = rememberCoroutineScope()
    val session = data.session()
    val details = remember(version) { runCatching { session.details(id) }.getOrNull() }
    if (details == null) {
        back()
        return
    }
    val summary = details.summary
    var revealedKey by remember { mutableStateOf<String?>(null) }
    var revealedValue by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var turned by remember { mutableStateOf(false) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val linkedPin = remember(version) {
        if (summary.kind == EntryKind.CARD) session.summaries().firstOrNull { it.kind == EntryKind.PIN && id in it.linkedTo } else null
    }

    fun hide() {
        revealedKey = null
        revealedValue = null
    }
    DisposableEffect(id) {
        onDispose {
            handler.removeCallbacksAndMessages(null)
            hide()
        }
    }

    /** Asks for the fingerprint if this tier needs it; false if it was refused. */
    suspend fun allowed(tier: Sensitivity): Boolean {
        if (!data.revealGate.needsBiometric(tier)) return true
        return data.confirmFingerprint()
    }

    fun reveal(field: FieldView) {
        scope.launch {
            val tier = field.sensitivity.tier()
            if (!allowed(tier)) return@launch
            val chars = session.reveal(id, field.key)
            data.revealGate.used(tier)
            revealedKey = field.key
            revealedValue = String(chars)
            chars.fill('\u0000')
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ hide() }, 30_000)
        }
    }

    fun copy(field: FieldView) {
        scope.launch {
            val tier = field.sensitivity.tier()
            if (!allowed(tier)) return@launch
            val value = if (field.sensitivity == 1) {
                field.value ?: return@launch
            } else {
                val chars = session.reveal(id, field.key)
                data.revealGate.used(tier)
                String(chars).also { chars.fill('\u0000') }
            }
            val seconds = data.clipboard.copy(value, tier)
            message = ClipboardGuard.confirmation(seconds)
            if (field.key == "card.number") runCatching { session.recordUse(id, data.nowS()) }
        }
    }

    frame({
        message?.let { Text(it, style = type.small.copy(color = Obsidian.ok), modifier = Modifier.tag("copied")) }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconAction("back", "Back", back, Modifier.tag("back"))
            Grow()
            IconAction(
                "star", if (summary.favourite) "Remove from favourites" else "Add to favourites",
                {
                    runCatching { session.setFavourite(id, !summary.favourite) }
                    changed()
                },
                Modifier.tag("favourite"),
                tint = if (summary.favourite) Obsidian.accent else Obsidian.text2,
            )
            IconAction(
                "note", "Edit",
                {
                    go(
                        when (summary.kind) {
                            EntryKind.CARD -> Page.EditCard(id, summary.bankId, Page.Entry(id, Page.Home))
                            EntryKind.PIN -> Page.EditPin(id, Page.Entry(id, Page.Home))
                            else -> Page.EditAccount(id, Page.Entry(id, Page.Home))
                        },
                    )
                },
                Modifier.tag("edit"),
            )
        }
        Column(Modifier.tag("entryPage").semantics(mergeDescendants = true) {}) {
            FieldLabel(
                when (summary.kind) {
                    EntryKind.CARD -> listOfNotNull(summary.bankId?.let { session.bankName(it) }, summary.cardKind?.name?.lowercase()?.replaceFirstChar { it.uppercase() }).joinToString(" · ")
                    EntryKind.PIN -> "PINs"
                    EntryKind.BANK_ACCOUNT -> "Bank account"
                    EntryKind.OTHER -> "Item"
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(summary.title, style = type.display)
                if (summary.favourite) {
                    Spacer(Modifier.width(8.dp))
                    VIcon("star", tint = Obsidian.accent, size = 18.dp)
                }
            }
        }
        if (summary.kind == EntryKind.CARD) {
            val numberField = details.fields.firstOrNull { it.key == "card.number" }
            val cvvField = details.fields.firstOrNull { it.key == "card.cvv" }
            fun text(key: String) = details.fields.firstOrNull { it.key == key }?.value
            CardStage(
                CardLook(
                    bank = summary.bankId?.let { session.bankName(it) },
                    kind = summary.cardKind?.name?.lowercase()?.replaceFirstChar { it.uppercase() },
                    holder = text("card.holder"),
                    expiry = text("card.expiry"),
                    network = summary.network,
                    number = if (revealedKey == "card.number") {
                        revealedValue?.let { grouped("card.number", it) }.orEmpty()
                    } else {
                        "•••• •••• •••• ${summary.last4.orEmpty()}".trimEnd()
                    },
                    hasCvv = cvvField != null,
                    hasPin = linkedPin != null,
                    finish = summary.finish ?: CardFinish.OBSIDIAN,
                ),
                turned = turned,
                onTurn = { turned = !turned },
                onNumber = { numberField?.let { if (revealedKey == it.key) hide() else reveal(it) } },
                onCopyCvv = { cvvField?.let { copy(it) } },
                onPin = { linkedPin?.let { go(Page.Entry(it.id, Page.Entry(id, Page.Home))) } },
                onFinish = { f ->
                    runCatching { session.setCardFinish(id, f) }
                    changed()
                },
            )
        }
        FieldLabel(if (summary.kind == EntryKind.CARD) "For checkout" else "Details")
        for (field in details.fields) {
            if (field.key == "card.issuer" || field.key == "bank.issuer") continue
            FieldRow(
                field,
                shown = if (revealedKey == field.key) revealedValue?.let { grouped(field.key, it) } else null,
                masked = masked(field, summary.last4),
                hint = when (field.key) {
                    "card.number" -> "Copied without spaces · clears in 15 s"
                    "card.cvv" -> "Stored at your request · never autofilled"
                    "card.user_note" -> "Your own words, not a checked offer"
                    else -> null
                },
                onReveal = { if (revealedKey == field.key) hide() else reveal(field) },
                onCopy = { copy(field) },
            )
        }
        if (linkedPin != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(R12)
                    .clickable(role = Role.Button) { go(Page.Entry(linkedPin.id, Page.Entry(id, Page.Home))) }
                    .tag("linkedPin")
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("ATM PIN", style = type.faint)
                    Text("Stored separately · fingerprint every time", style = type.body)
                }
                VIcon("chev", size = 18.dp, tint = Obsidian.text3)
            }
        }
        Notice(
            "No \"copy all\": each detail is copied on its own, and leaving Vaultaire hides anything you revealed.",
            icon = "lock",
        )
        if (!confirmDelete) {
            GhostButton("Delete", { confirmDelete = true }, Modifier.tag("delete"))
        } else {
            Text("Delete ${summary.title}? This can't be undone.", style = type.body)
            SecondaryButton(
                "Delete for good",
                {
                    runCatching { session.delete(id) }
                    changed()
                    go(Page.Home)
                },
                Modifier.tag("confirmDelete"),
            )
            GhostButton("Keep it", { confirmDelete = false })
        }
    }
}

@Composable
private fun FieldRow(
    field: FieldView,
    shown: String?,
    masked: String,
    hint: String?,
    onReveal: () -> Unit,
    onCopy: () -> Unit,
) {
    val type = LocalVaultType.current
    val secret = field.sensitivity > 1
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(field.label, style = type.faint)
                if (field.sensitivity >= 3) {
                    Spacer(Modifier.width(8.dp))
                    Badge(if (field.sensitivity == 4) "EVERY TIME" else "CRITICAL")
                }
            }
            Text(
                if (secret) shown ?: masked else field.value.orEmpty(),
                style = if (secret || field.kind in setOf(3, 4, 10, 11, 15)) type.mono else type.body,
                modifier = Modifier.tag("value:${field.key}"),
            )
            hint?.let { Text(it, style = type.faint) }
        }
        if (secret) IconAction(if (shown != null) "eyeOff" else "eye", "Show ${field.label}", onReveal, Modifier.tag("reveal:${field.key}"))
        if (field.copyable) IconAction("copy", "Copy ${field.label}", onCopy, Modifier.tag("copy:${field.key}"))
    }
}

@Composable
private fun Badge(text: String) {
    Box(
        Modifier
            .border(1.dp, Obsidian.accent.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) { Text(text, style = LocalVaultType.current.label.copy(color = Obsidian.accent)) }
}

// ---------- forms ----------

/** The bank picker: the catalogue and the user's banks, or "Add “…” as a bank". */
@Composable
private fun BankPicker(data: VaultData, bankId: String?, onPick: (String?) -> Unit) {
    val type = LocalVaultType.current
    val session = data.session()
    var query by remember { mutableStateOf(bankId?.let { session.bankName(it) } ?: "") }
    var picked by remember { mutableStateOf(bankId) }
    VaultField(
        query,
        {
            query = it
            picked = null
            onPick(null)
        },
        "Bank",
        fieldModifier = Modifier.tag("bankPicker"),
    )
    if (picked == null && query.isNotBlank()) {
        val q = query.trim().lowercase()
        val matches = remember(q) {
            VaultEngine.catalogue().filter { it.name.lowercase().contains(q) || it.fullName.lowercase().contains(q) }.take(6)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            matches.forEach { bank ->
                Text(
                    bank.name,
                    style = type.body,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) {
                            picked = bank.id
                            query = bank.name
                            onPick(bank.id)
                        }
                        .tag("pickBank:${bank.id}")
                        .padding(vertical = 10.dp),
                )
            }
            if (matches.isEmpty()) {
                Text(
                    "Add “${query.trim()}” as a bank",
                    style = type.body.copy(color = Obsidian.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) {
                            val id = runCatching { session.addBank(query.trim()) }.getOrNull()
                            if (id != null) {
                                picked = id
                                query = session.bankName(id) ?: query.trim()
                                onPick(id)
                            }
                        }
                        .tag("pickNewBank")
                        .padding(vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun CardForm(data: VaultData, id: EntryId?, bankId: String?, changed: () -> Unit, done: (Page?) -> Unit, frame: Frame) {
    val type = LocalVaultType.current
    val session = data.session()
    val existing = remember { id?.let { runCatching { session.details(it) }.getOrNull() } }
    fun value(key: String) = existing?.fields?.firstOrNull { it.key == key }?.value ?: ""
    var bank by remember { mutableStateOf(existing?.summary?.bankId ?: bankId) }
    var kind by remember { mutableStateOf(existing?.summary?.cardKind) }
    var product by remember { mutableStateOf(value("card.product")) }
    var nickname by remember { mutableStateOf(value("card.nickname")) }
    var holder by remember { mutableStateOf(value("card.holder")) }
    var number by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf(value("card.expiry")) }
    var network by remember { mutableStateOf(value("card.network")) }
    var cvv by remember { mutableStateOf("") }
    var note by remember { mutableStateOf(value("card.user_note")) }
    var error by remember { mutableStateOf<String?>(null) }
    val ready = kind != null && (product.isNotBlank() || nickname.isNotBlank()) && (id != null || number.isNotBlank())

    fun save() {
        val input = CardInput(
            bankId = bank,
            kind = kind ?: return,
            product = product.ifBlank { null },
            nickname = nickname.ifBlank { null },
            holder = holder.ifBlank { null },
            number = if (number.isBlank() && id != null) SecretInput.Keep else SecretInput.Set(number.filter { it != ' ' }.toCharArray()),
            expiry = expiry.ifBlank { null },
            network = network.ifBlank { null },
            cvv = when {
                cvv.isNotBlank() -> SecretInput.Set(cvv.toCharArray())
                id != null && existing?.summary?.hasCvv == true -> SecretInput.Keep
                else -> SecretInput.Empty
            },
            userNote = note.ifBlank { null },
        )
        try {
            if (id == null) session.addCard(input, data.nowS()) else session.updateCard(id, input, data.nowS())
            changed()
            done(if (id == null) Page.Home else null)
        } catch (e: VaultException) {
            error = "Check the card number (12 to 19 digits) and the expiry (MM/YY)."
        }
    }

    frame({ PrimaryButton("Save card", ::save, Modifier.tag("save"), enabled = ready) }) {
        IconAction("close", "Cancel", { done(null) }, Modifier.tag("back"))
        Text(if (id == null) "Add card" else "Edit card", style = type.displayL)
        BankPicker(data, bank) { bank = it }
        FieldLabel("Card type")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip("Credit", kind == CardKind.CREDIT, "kind:credit") { kind = CardKind.CREDIT }
            FilterChip("Debit", kind == CardKind.DEBIT, "kind:debit") { kind = CardKind.DEBIT }
            FilterChip("Other", kind == CardKind.OTHER, "kind:other") { kind = CardKind.OTHER }
        }
        VaultField(product, { product = it }, "Card name", fieldModifier = Modifier.tag("field:product"))
        VaultField(nickname, { nickname = it }, "Nickname (optional)", fieldModifier = Modifier.tag("field:nickname"))
        VaultField(holder, { holder = it }, "Name on card", fieldModifier = Modifier.tag("field:holder"))
        VaultField(
            number,
            {
                number = it
                VaultEngine.suggestNetwork(it)?.let { n -> network = n }
            },
            if (id == null) "Card number" else "Card number (leave empty to keep it)",
            mono = true,
            keyboardType = KeyboardType.Number,
            fieldModifier = Modifier.tag("field:number"),
        )
        VaultField(expiry, { expiry = it }, "Expiry (MM/YY)", mono = true, fieldModifier = Modifier.tag("field:expiry"))
        VaultField(network, { network = it }, "Network", fieldModifier = Modifier.tag("field:network"))
        VaultField(cvv, { cvv = it }, "CVV (optional, stored only if you add it)", secret = true, fieldModifier = Modifier.tag("field:cvv"))
        VaultField(note, { note = it }, "Your note (optional)", fieldModifier = Modifier.tag("field:user_note"))
        error?.let { Text(it, style = type.small.copy(color = Obsidian.crit), modifier = Modifier.tag("error")) }
    }
}

@Composable
private fun PinForm(data: VaultData, id: EntryId?, changed: () -> Unit, done: (Page?) -> Unit, frame: Frame) {
    val type = LocalVaultType.current
    val session = data.session()
    val existing = remember { id?.let { runCatching { session.details(it) }.getOrNull() } }
    var purpose by remember { mutableStateOf(existing?.summary?.pinPurpose) }
    var label by remember { mutableStateOf(existing?.fields?.firstOrNull { it.key == "pin.label" }?.value ?: "") }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val ready = purpose != null && label.isNotBlank() && (id != null || pin.isNotBlank())

    fun save() {
        val input = PinInput(
            purpose ?: return,
            label,
            if (pin.isBlank() && id != null) SecretInput.Keep else SecretInput.Set(pin.toCharArray()),
            existing?.summary?.linkedTo?.firstOrNull(),
        )
        try {
            if (id == null) session.addPin(input, data.nowS()) else session.updatePin(id, input, data.nowS())
            changed()
            done(if (id == null) Page.Home else null)
        } catch (e: VaultException) {
            error = "A PIN is 4 to 12 digits, and needs a name."
        }
    }

    frame({ PrimaryButton("Save PIN", ::save, Modifier.tag("save"), enabled = ready) }) {
        IconAction("close", "Cancel", { done(null) }, Modifier.tag("back"))
        Text(if (id == null) "Add PIN" else "Edit PIN", style = type.displayL)
        FieldLabel("What is it for?")
        val purposes = listOf(
            PinPurpose.ATM_PIN to "ATM / card", PinPurpose.LOCKER to "Locker", PinPurpose.DEMAT_TPIN to "Demat TPIN",
            PinPurpose.APP_LOGIN to "App login", PinPurpose.MPIN to "MPIN", PinPurpose.TPIN to "TPIN",
            PinPurpose.DOOR_ALARM to "Door / alarm", PinPurpose.OTHER to "Other",
        )
        purposes.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (p, text) -> FilterChip(text, purpose == p, "purpose:${p.name}") { purpose = p } }
            }
        }
        if (purpose == PinPurpose.ATM_PIN) {
            Notice("An ATM PIN is kept as its own entry and needs your fingerprint every time. It is never shown on the card.")
        }
        VaultField(label, { label = it }, "Used for", fieldModifier = Modifier.tag("field:label"))
        VaultField(pin, { pin = it }, if (id == null) "PIN" else "PIN (leave empty to keep it)", secret = true, keyboardType = KeyboardType.NumberPassword, fieldModifier = Modifier.tag("field:pin"))
        error?.let { Text(it, style = type.small.copy(color = Obsidian.crit), modifier = Modifier.tag("error")) }
    }
}

@Composable
private fun AccountForm(data: VaultData, id: EntryId?, changed: () -> Unit, done: (Page?) -> Unit, frame: Frame) {
    val type = LocalVaultType.current
    val session = data.session()
    val existing = remember { id?.let { runCatching { session.details(it) }.getOrNull() } }
    fun value(key: String) = existing?.fields?.firstOrNull { it.key == key }?.value ?: ""
    var bank by remember { mutableStateOf(existing?.summary?.bankId) }
    val shown = remember { mutableStateOf(mapOf<String, String>()) }
    val keys = listOf(
        "bank.holder" to "Account holder", "bank.account_number" to "Account number", "bank.ifsc" to "IFSC",
        "bank.branch" to "Branch", "bank.customer_id" to "Customer ID (CIF)", "bank.netbanking_user" to "Net-banking user ID",
        "bank.netbanking_password" to "Net-banking password", "bank.transaction_password" to "Transaction password",
        "bank.app_password" to "Mobile app password", "bank.mpin" to "MPIN", "bank.tpin" to "TPIN", "bank.upi_pin" to "UPI PIN",
        "bank.registered_mobile" to "Registered mobile", "bank.rm_name" to "Relationship manager", "bank.rm_phone" to "RM phone",
        "bank.concierge_phone" to "Concierge / customer care",
    )
    val secretKeys = setOf(
        "bank.account_number", "bank.customer_id", "bank.netbanking_user", "bank.netbanking_password",
        "bank.transaction_password", "bank.app_password", "bank.mpin", "bank.tpin", "bank.upi_pin",
    )
    val values = remember { mutableStateOf(keys.associate { (k, _) -> k to (if (k in secretKeys) "" else value(k)) }) }
    var error by remember { mutableStateOf<String?>(null) }
    fun v(k: String) = values.value[k].orEmpty()
    fun secret(k: String): SecretInput = when {
        v(k).isNotBlank() -> SecretInput.Set(v(k).toCharArray())
        id != null && existing?.fields?.any { it.key == k } == true -> SecretInput.Keep
        else -> SecretInput.Empty
    }
    fun text(k: String) = v(k).ifBlank { null }

    fun save() {
        val input = BankAccountInput(
            bankId = bank, holder = text("bank.holder"), accountNumber = secret("bank.account_number"),
            ifsc = text("bank.ifsc")?.uppercase(), branch = text("bank.branch"), customerId = secret("bank.customer_id"),
            netbankingUser = secret("bank.netbanking_user"), netbankingPassword = secret("bank.netbanking_password"),
            transactionPassword = secret("bank.transaction_password"), appPassword = secret("bank.app_password"),
            mpin = secret("bank.mpin"), tpin = secret("bank.tpin"), upiPin = secret("bank.upi_pin"),
            registeredMobile = text("bank.registered_mobile"), rmName = text("bank.rm_name"),
            rmPhone = text("bank.rm_phone"), conciergePhone = text("bank.concierge_phone"),
        )
        try {
            if (id == null) session.addBankAccount(input, data.nowS()) else session.updateBankAccount(id, input, data.nowS())
            changed()
            done(if (id == null) Page.Home else null)
        } catch (e: VaultException) {
            error = "Check the IFSC: 4 letters, a 0, then 6 letters or digits, like HDFC0001234. PINs are 4 to 12 digits."
        }
    }
    shown.value = values.value

    frame({ PrimaryButton("Save account", ::save, Modifier.tag("save"), enabled = id != null || v("bank.account_number").isNotBlank()) }) {
        IconAction("close", "Cancel", { done(null) }, Modifier.tag("back"))
        Text(if (id == null) "Add bank account" else "Edit bank account", style = type.displayL)
        BankPicker(data, bank) { bank = it }
        keys.forEach { (k, label) ->
            VaultField(
                v(k),
                { values.value = values.value + (k to it) },
                if (k in secretKeys && id != null) "$label (leave empty to keep it)" else label,
                secret = k in secretKeys,
                mono = k == "bank.ifsc",
                fieldModifier = Modifier.tag("field:${k.removePrefix("bank.")}"),
            )
        }
        error?.let { Text(it, style = type.small.copy(color = Obsidian.crit), modifier = Modifier.tag("error")) }
    }
}

// ---------- settings ----------

@Composable
private fun SettingsPage(data: VaultData, back: () -> Unit, frame: Frame) {
    val type = LocalVaultType.current
    val session = data.session()
    var settings by remember { mutableStateOf(session.settings()) }
    var pending by remember { mutableStateOf<VaultSettings?>(null) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun apply(next: VaultSettings) {
        try {
            session.updateSettings(next, null)
            settings = session.settings()
            data.settingsChanged()
        } catch (e: VaultException) {
            if (e.code == "password_required") {
                pending = next
                password = ""
                error = null
            }
        }
    }

    fun confirm() {
        val next = pending ?: return
        try {
            session.updateSettings(next, password.toCharArray())
            settings = session.settings()
            pending = null
            data.settingsChanged()
        } catch (e: VaultException) {
            error = "That isn't the vault password. Nothing was changed."
        }
        password = ""
    }

    frame({}) {
        IconAction("back", "Back to vault", back, Modifier.tag("back"))
        Text("Settings", style = type.displayL)
        Choice("Lock after no use", listOf(30L to "30 s", 60L to "1 min", 120L to "2 min", 300L to "5 min", 900L to "15 min"), settings.autoLockS, "autoLock") {
            apply(settings.copy(autoLockS = it))
        }
        Choice("Lock in the background after", listOf(0L to "At once", 30L to "30 s", 60L to "1 min", 300L to "5 min"), settings.backgroundLockS, "backgroundLock") {
            apply(settings.copy(backgroundLockS = it))
        }
        Choice("Clear copied names and other details after", listOf(30L to "30 s", 60L to "1 min", 120L to "2 min"), settings.clipboardTtlS, "clipboard") {
            apply(settings.copy(clipboardTtlS = it))
        }
        Text("Card numbers, CVVs and PINs are always cleared after 15 s.", style = type.faint)
        if (pending != null) {
            Notice("This makes the vault less strict, so it needs your vault password.")
            VaultField(password, { password = it }, "Vault password", secret = true, fieldModifier = Modifier.tag("settingsPassword"))
            error?.let { Text(it, style = type.small.copy(color = Obsidian.crit), modifier = Modifier.tag("error")) }
            PrimaryButton("Confirm", ::confirm, Modifier.tag("confirmSettings"), enabled = password.isNotEmpty())
            GhostButton("Cancel", { pending = null })
        }
    }
}

@Composable
private fun Choice(title: String, options: List<Pair<Long, String>>, current: Long, tag: String, onPick: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(title)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (value, label) -> FilterChip(label, current == value, "$tag:$value") { onPick(value) } }
        }
    }
}
