package dev.vaultaire.spike

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ----------------------------------------------------
// VAULT DASHBOARD (Matching iPhone PWA Screenshot 1)
// ----------------------------------------------------
@Composable
fun VaultDashboard(
    items: List<VaultItem>,
    onLock: () -> Unit,
    onCopy: (String, String) -> Unit,
    onOpenAddItem: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenBackups: () -> Unit,
    onOpenQrSync: () -> Unit,
    onOpenPasswordStudio: () -> Unit,
    onSelectCard: (VaultItem) -> Unit,
    onSelectItem: (VaultItem) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val greeting = remember { getTimeGreeting() }

    val cards = items.filter { it.type == "card" }
    val passwords = items.filter { it.type == "login" }
    val totps = items.filter { it.type == "totp" }
    val coldStorage = items.filter { it.type == "coldstorage" }
    val apiKeys = items.filter { it.type == "devkey" }
    val codes = items.filter { it.type == "codes" }
    val notes = items.filter { it.type == "note" }
    val pinned = items.filter { it.fav }
    val upcoming = items.filter { it.upcomingDays > 0 }.sortedBy { it.upcomingDays }

    Scaffold(
        containerColor = ColorBg,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ColorBg)
                    .safeDrawingPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                // Top Row: "VAULT" header with "+", "Studio Key", "Sliders", and "Lock" action icons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "VAULT",
                            color = ColorTextTertiary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.5.sp
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = greeting,
                            color = ColorTextPrimary,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onOpenAddItem, modifier = Modifier.size(24.dp)) {
                            VaultIcon(VaultIcons.PLUS, tint = ColorTextPrimary, size = 20.dp)
                        }
                        IconButton(onClick = onOpenPasswordStudio, modifier = Modifier.size(24.dp)) {
                            VaultIcon(VaultIcons.KEY, tint = ColorAccentGold, size = 20.dp)
                        }
                        IconButton(onClick = onOpenDiagnostics, modifier = Modifier.size(24.dp)) {
                            VaultIcon(VaultIcons.SLIDERS, tint = ColorTextPrimary, size = 20.dp)
                        }
                        IconButton(onClick = onLock, modifier = Modifier.size(24.dp)) {
                            VaultIcon(VaultIcons.LOCK, tint = ColorAccentGold, size = 20.dp)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Protections Health Pill
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenDiagnostics() },
                    shape = RoundedCornerShape(12.dp),
                    color = ColorSurface,
                    border = BorderStroke(1.dp, ColorHairline)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        VaultIcon(VaultIcons.SHIELD, tint = ColorOk, size = 16.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Protections on",
                            color = ColorTextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Normal
                        )
                        Text(
                            text = "  ·  ",
                            color = ColorTextTertiary,
                            fontSize = 13.sp
                        )
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(ColorWarn, CircleShape)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "1 thing to check",
                            color = ColorTextSecondary,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.weight(1f))
                        VaultIcon(VaultIcons.CHEVRON, tint = ColorTextTertiary, size = 14.dp)
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Search Bar matching screenshot
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = ColorSurface,
                    border = BorderStroke(1.dp, ColorHairline)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        VaultIcon(VaultIcons.SEARCH, tint = ColorTextTertiary, size = 18.dp)
                        Spacer(Modifier.width(10.dp))
                        androidx.compose.foundation.text.BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = ColorTextPrimary,
                                fontSize = 14.sp
                            ),
                            singleLine = true,
                            decorationBox = { innerTextField ->
                                Box {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = "Search ${items.size} items",
                                            color = ColorTextTertiary,
                                            fontSize = 14.sp
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            // ----------------------------------------------------
            // ★ PINNED FAVORITES (Screenshot 1)
            // ----------------------------------------------------
            if (pinned.isNotEmpty() && searchQuery.isEmpty()) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        VaultIcon(VaultIcons.STAR, tint = ColorAccentGold, size = 12.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "PINNED FAVORITES",
                            color = ColorAccentGold,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.2.sp
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        pinned.take(2).forEach { favItem ->
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(110.dp)
                                    .clickable { onSelectItem(favItem) },
                                shape = RoundedCornerShape(14.dp),
                                color = ColorSurface,
                                border = BorderStroke(1.dp, ColorHairline)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            modifier = Modifier.size(28.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            color = ColorTile
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                val pData = if (favItem.type == "card") VaultIcons.CARD else VaultIcons.LOGIN
                                                VaultIcon(pData, tint = ColorTextPrimary, size = 15.dp)
                                            }
                                        }
                                        VaultIcon(VaultIcons.STAR, tint = ColorAccentGold, size = 12.dp)
                                    }

                                    Column {
                                        Text(
                                            text = favItem.title,
                                            color = ColorTextPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = favItem.subtitle,
                                            color = ColorTextSecondary,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ----------------------------------------------------
            // CARDS (Horizontal Scroll Wallet with Wave & Finish Engravings)
            // ----------------------------------------------------
            if (cards.isNotEmpty()) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Cards",
                                color = ColorTextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "${cards.size}",
                                color = ColorTextTertiary,
                                fontSize = 14.sp
                            )
                        }
                        VaultIcon(VaultIcons.CHEVRON, tint = ColorTextTertiary, size = 16.dp)
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        cards.forEach { card ->
                            Column(modifier = Modifier.clickable { onSelectCard(card) }) {
                                Surface(
                                    modifier = Modifier
                                        .size(width = 240.dp, height = 150.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, ColorHairline)
                                ) {
                                    val (bgColors, textColor, waveColor) = when (card.finish) {
                                        "damascus" -> Triple(
                                            listOf(Color(0xFF1E222A), Color(0xFF101217)),
                                            Color(0xFFF2EEE6),
                                            Color(0x35C9B38A)
                                        )
                                        "carbon" -> Triple(
                                            listOf(Color(0xFF18181A), Color(0xFF0C0C0E)),
                                            Color(0xFFF2EEE6),
                                            Color(0x288E8E93)
                                        )
                                        "platinum" -> Triple(
                                            listOf(Color(0xFFF0EDE7), Color(0xFFD6D1C7)),
                                            Color(0xFF1A1917),
                                            Color(0x181A1917)
                                        )
                                        "champagne" -> Triple(
                                            listOf(Color(0xFFE2D1AE), Color(0xFFB39767)),
                                            Color(0xFF1A1917),
                                            Color(0x35FFFFFF)
                                        )
                                        "titanium" -> Triple(
                                            listOf(Color(0xFF34343A), Color(0xFF222226)),
                                            Color(0xFFF2EEE6),
                                            Color(0x15FFFFFF)
                                        )
                                        "ivory" -> Triple(
                                            listOf(Color(0xFFFBF8F2), Color(0xFFE9E1D2)),
                                            Color(0xFF1A1917),
                                            Color(0x227A6240)
                                        )
                                        else -> Triple( // "obsidian"
                                            listOf(Color(0xFF202023), Color(0xFF0D0D0F)),
                                            Color(0xFFF2EEE6),
                                            Color(0x2BC9B38A)
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Brush.linearGradient(bgColors))
                                    ) {
                                        Canvas(modifier = Modifier.fillMaxSize()) {
                                            val w = size.width
                                            val h = size.height
                                            if (card.finish == "carbon") {
                                                val step = 14f
                                                var x = -h
                                                while (x < w + h) {
                                                    drawLine(
                                                        color = waveColor,
                                                        start = Offset(x, 0f),
                                                        end = Offset(x + h, h),
                                                        strokeWidth = 1f
                                                    )
                                                    drawLine(
                                                        color = waveColor,
                                                        start = Offset(x + h, 0f),
                                                        end = Offset(x, h),
                                                        strokeWidth = 1f
                                                    )
                                                    x += step
                                                }
                                            } else if (card.finish == "damascus") {
                                                for (i in 0 until 18) {
                                                    val path = Path()
                                                    val steps = 80
                                                    for (s in 0..steps) {
                                                        val relX = s.toFloat() / steps
                                                        val x = relX * w
                                                        val y = (h * 0.5f) +
                                                                (h * 0.22f) * sin(relX * 2f * PI.toFloat() * 1.8f + i * 0.35f) +
                                                                (h * 0.12f) * sin(relX * 2f * PI.toFloat() * 4.2f - i * 0.2f) +
                                                                (h * 0.05f) * cos(relX * 2f * PI.toFloat() * 8.5f + i * 0.15f)
                                                        if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                                    }
                                                    drawPath(path, color = waveColor, style = Stroke(width = 1.1f))
                                                }
                                            } else {
                                                for (i in 0 until 16) {
                                                    val path = Path()
                                                    val steps = 60
                                                    for (s in 0..steps) {
                                                        val x = (s.toFloat() / steps) * w
                                                        val relX = x / w
                                                        val y = (h * (132f / 221f)) +
                                                                (h * (26f / 221f)) * sin(relX * 2f * PI.toFloat() * 1.5f + i * 0.2f) +
                                                                (h * (9f / 221f)) * sin(relX * 2f * PI.toFloat() * 6f - i * 0.35f)
                                                        if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                                    }
                                                    drawPath(path, color = waveColor, style = Stroke(width = 1f))
                                                }
                                            }
                                        }

                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(16.dp),
                                            verticalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = card.bankName.ifBlank { "VAULTAIRE BANK" },
                                                    color = textColor.copy(alpha = 0.85f),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    letterSpacing = 1.sp
                                                )
                                                Text(
                                                    text = card.finish.uppercase(),
                                                    color = textColor.copy(alpha = 0.5f),
                                                    fontSize = 8.sp,
                                                    letterSpacing = 0.5.sp
                                                )
                                            }

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.Bottom
                                            ) {
                                                Text(
                                                    text = card.cardNumber,
                                                    color = textColor,
                                                    fontSize = 15.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    letterSpacing = 2.sp
                                                )
                                                Text(
                                                    text = card.network,
                                                    color = textColor,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = card.title,
                                    color = ColorTextSecondary,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }

            // ----------------------------------------------------
            // COMING UP (Screenshot 1: Expiry Watch)
            // ----------------------------------------------------
            if (upcoming.isNotEmpty() && searchQuery.isEmpty()) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Coming up",
                                color = ColorTextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "${upcoming.size}",
                                color = ColorTextTertiary,
                                fontSize = 14.sp
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = ColorSurface,
                        border = BorderStroke(1.dp, ColorHairline)
                    ) {
                        Column {
                            upcoming.take(2).forEachIndexed { idx, it ->
                                val comingUpSub = if (it.type == "card" && it.upcomingDate.isNotBlank()) "Card expires ${it.upcomingDate}" else it.subtitle
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectItem(it) }
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        modifier = Modifier.size(36.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        color = ColorTile
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            VaultIcon(VaultIcons.CALENDAR, tint = ColorAccentGold, size = 18.dp)
                                        }
                                    }
                                    Spacer(Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(it.title, color = ColorTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                        Text(comingUpSub, color = ColorTextSecondary, fontSize = 12.sp)
                                    }
                                    Text(
                                        text = "in ${it.upcomingDays / 30} months",
                                        color = ColorAccentGold,
                                        fontSize = 12.sp
                                    )
                                }
                                if (idx == 0 && upcoming.size > 1) {
                                    HorizontalDivider(color = ColorHairline, thickness = 1.dp)
                                }
                            }
                        }
                    }
                }
            }

            // ----------------------------------------------------
            // PASSWORDS SECTION (Screenshot 1)
            // ----------------------------------------------------
            if (passwords.isNotEmpty()) {
                CategorySection(
                    title = "Passwords",
                    items = passwords,
                    iconPath = VaultIcons.LOGIN,
                    onCopy = onCopy,
                    onItemClick = onSelectItem
                )
            }

            // ----------------------------------------------------
            // PHASE 3: 2FA AUTHENTICATOR (RFC 6238)
            // ----------------------------------------------------
            if (totps.isNotEmpty()) {
                CategorySection(
                    title = "2FA Authenticator",
                    items = totps,
                    iconPath = VaultIcons.CLOCK,
                    onCopy = onCopy,
                    isTotp = true,
                    onItemClick = onSelectItem
                )
            }

            // ----------------------------------------------------
            // PHASE 3: COLD STORAGE (BIP-39)
            // ----------------------------------------------------
            if (coldStorage.isNotEmpty()) {
                CategorySection(
                    title = "Cold Storage (BIP-39)",
                    items = coldStorage,
                    iconPath = VaultIcons.SHIELD,
                    onCopy = onCopy,
                    onItemClick = onSelectItem
                )
            }

            // ----------------------------------------------------
            // API KEYS & OTHER SECTIONS
            // ----------------------------------------------------
            if (apiKeys.isNotEmpty()) {
                CategorySection(
                    title = "API keys",
                    items = apiKeys,
                    iconPath = VaultIcons.DEVKEY,
                    onCopy = onCopy,
                    onItemClick = onSelectItem
                )
            }

            if (codes.isNotEmpty()) {
                CategorySection(
                    title = "Recovery codes",
                    items = codes,
                    iconPath = VaultIcons.CODES,
                    onCopy = onCopy,
                    onItemClick = onSelectItem
                )
            }

            val bankAccounts = items.filter { it.type == "bank" }
            if (bankAccounts.isNotEmpty()) {
                CategorySection(
                    title = "Bank accounts",
                    items = bankAccounts,
                    iconPath = VaultIcons.BANK,
                    onCopy = onCopy,
                    onItemClick = onSelectItem
                )
            }

            val identities = items.filter { it.type == "identity" }
            if (identities.isNotEmpty()) {
                CategorySection(
                    title = "Identities",
                    items = identities,
                    iconPath = VaultIcons.IDENTITY,
                    onCopy = onCopy,
                    onItemClick = onSelectItem
                )
            }

            val noteItems = items.filter { it.type == "note" }
            if (noteItems.isNotEmpty()) {
                CategorySection(
                    title = "Secure notes",
                    items = noteItems,
                    iconPath = VaultIcons.NOTE,
                    onCopy = onCopy,
                    onItemClick = onSelectItem
                )
            }

            val memberships = items.filter { it.type == "membership" }
            if (memberships.isNotEmpty()) {
                CategorySection(
                    title = "Memberships",
                    items = memberships,
                    iconPath = VaultIcons.MEMBERSHIP,
                    onCopy = onCopy,
                    onItemClick = onSelectItem
                )
            }

            // Footer Air-Gapped Sync & Backups Actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenQrSync,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, ColorHairline)
                ) {
                    VaultIcon(VaultIcons.QR, tint = ColorAccentGold, size = 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("QR Sync", fontSize = 12.sp, color = ColorTextPrimary)
                }
                OutlinedButton(
                    onClick = onOpenBackups,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, ColorHairline)
                ) {
                    VaultIcon(VaultIcons.COPY, tint = ColorAccentGold, size = 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("SAF Backup", fontSize = 12.sp, color = ColorTextPrimary)
                }
            }

            Text(
                text = "Kept only on this phone. Never synced, never uploaded.",
                color = ColorTextTertiary,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}

// ----------------------------------------------------
// CATEGORY GROUP ROW (Matching Screenshot 1)
// ----------------------------------------------------
@Composable
fun CategorySection(
    title: String,
    items: List<VaultItem>,
    iconPath: String,
    onCopy: (String, String) -> Unit,
    isTotp: Boolean = false,
    onItemClick: (VaultItem) -> Unit = {}
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = ColorTextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${items.size}",
                    color = ColorTextTertiary,
                    fontSize = 14.sp
                )
            }
            VaultIcon(VaultIcons.CHEVRON, tint = ColorTextTertiary, size = 16.dp)
        }

        Spacer(Modifier.height(12.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = ColorSurface,
            border = BorderStroke(1.dp, ColorHairline)
        ) {
            Column {
                items.forEachIndexed { idx, it ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onItemClick(it) }
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(36.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = ColorTile
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                VaultIcon(iconPath, tint = ColorTextPrimary, size = 18.dp)
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(it.title, color = ColorTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(it.subtitle, color = ColorTextSecondary, fontSize = 12.sp)
                        }

                        if (isTotp) {
                            var currentTime by remember { mutableLongStateOf(System.currentTimeMillis() / 1000L) }
                            LaunchedEffect(Unit) {
                                while (true) {
                                    currentTime = System.currentTimeMillis() / 1000L
                                    delay(500)
                                }
                            }
                            val code = TotpEngine.generateTotp(it.totpSecret, currentTime)
                            Text(
                                text = if (code.length == 6) "${code.take(3)} ${code.takeLast(3)}" else code,
                                color = ColorAccentGold,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }

                        if (it.fav) {
                            VaultIcon(VaultIcons.STAR, tint = ColorAccentGold, size = 14.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        IconButton(
                            onClick = {
                                if (it.secret.isNotBlank()) onCopy(it.secret, it.title)
                                else if (it.totpSecret.isNotBlank()) onCopy(TotpEngine.generateTotp(it.totpSecret), it.title)
                                else if (it.bip39Phrase.isNotBlank()) onCopy(it.bip39Phrase, it.title)
                                else onCopy(it.title, it.title)
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            VaultIcon(VaultIcons.COPY, tint = ColorTextTertiary, size = 14.dp)
                        }
                        Spacer(Modifier.width(4.dp))
                        VaultIcon(VaultIcons.CHEVRON, tint = ColorTextTertiary, size = 14.dp)
                    }
                    if (idx < items.size - 1) {
                        HorizontalDivider(color = ColorHairline, thickness = 1.dp)
                    }
                }
            }
        }
    }
}
