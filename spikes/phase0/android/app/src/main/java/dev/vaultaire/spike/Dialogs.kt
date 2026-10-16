package dev.vaultaire.spike

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.sin

// ----------------------------------------------------
// PHASE 1: 3D INTERACTIVE CARD FLIP & CARD DETAIL
// ----------------------------------------------------
@Composable
fun CardDetailDialog(
    card: VaultItem,
    onDismiss: () -> Unit,
    onCopy: (String, String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var isFlipped by remember { mutableStateOf(false) }
    var showCvv by remember { mutableStateOf(false) }

    val rotation by animateFloatAsState(
        targetValue = if (isFlipped) 180f else 0f,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "cardFlipRotation"
    )

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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(ColorBg)
                .safeDrawingPadding()
                .padding(20.dp),
            color = ColorBg
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        VaultIcon(VaultIcons.CLOSE, tint = ColorTextPrimary, size = 20.dp)
                    }
                    Text(
                        text = "3D CARD INSPECTOR",
                        color = ColorAccentGold,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                    Spacer(Modifier.size(24.dp))
                }

                Spacer(Modifier.height(28.dp))

                // 3D Card Container
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                        .graphicsLayer {
                            rotationY = rotation
                            cameraDistance = 14f * density
                        }
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            isFlipped = !isFlipped
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (rotation <= 90f) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, ColorHairlineGold)
                        ) {
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
                                            drawLine(color = waveColor, start = Offset(x, 0f), end = Offset(x + h, h), strokeWidth = 1f)
                                            drawLine(color = waveColor, start = Offset(x + h, 0f), end = Offset(x, h), strokeWidth = 1f)
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
                                                val relX = s.toFloat() / steps
                                                val x = relX * w
                                                val y = (h * (132f / 221f)) +
                                                        (h * (26f / 221f)) * sin(relX * 2f * PI.toFloat() * 1.5f + i * 0.2f) +
                                                        (h * (9f / 221f)) * sin(relX * 2f * PI.toFloat() * 6f - i * 0.35f)
                                                if (s == 0) path.moveTo(x, y.toFloat()) else path.lineTo(x, y.toFloat())
                                            }
                                            drawPath(path, color = waveColor, style = Stroke(width = 1f))
                                        }
                                    }
                                }

                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(20.dp),
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = card.bankName.ifBlank { "VAULTAIRE PRIVATE BANK" },
                                            color = textColor.copy(alpha = 0.9f),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.2.sp
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = Color(0x33000000),
                                            border = BorderStroke(1.dp, ColorHairline)
                                        ) {
                                            Text(
                                                text = "${card.finish.uppercase()} FINISH",
                                                color = textColor.copy(alpha = 0.7f),
                                                fontSize = 8.sp,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 0.8.sp,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                                    ) {
                                        Surface(
                                            modifier = Modifier.size(width = 36.dp, height = 26.dp),
                                            shape = RoundedCornerShape(4.dp),
                                            color = Color(0xFFD4AF37),
                                            border = BorderStroke(1.dp, Color(0xFF997A15))
                                        ) {
                                            Canvas(modifier = Modifier.fillMaxSize()) {
                                                drawLine(Color(0xFF7A600A), Offset(size.width * 0.33f, 0f), Offset(size.width * 0.33f, size.height), 1f)
                                                drawLine(Color(0xFF7A600A), Offset(size.width * 0.66f, 0f), Offset(size.width * 0.66f, size.height), 1f)
                                                drawLine(Color(0xFF7A600A), Offset(0f, size.height * 0.5f), Offset(size.width, size.height * 0.5f), 1f)
                                            }
                                        }

                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .background(
                                                    Brush.sweepGradient(
                                                        listOf(Color(0x55C9B38A), Color(0x557DB8A5), Color(0x55E0A196), Color(0x55C9B38A))
                                                    ),
                                                    CircleShape
                                                )
                                                .border(1.dp, Color(0x40FFFFFF), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            VaultIcon(VaultIcons.SHIELD, tint = ColorTextPrimary.copy(alpha = 0.6f), size = 12.dp)
                                        }
                                    }

                                    Text(
                                        text = card.cardNumber,
                                        color = textColor,
                                        fontSize = 18.sp,
                                        fontFamily = FontFamily.Monospace,
                                        letterSpacing = 3.sp
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.Bottom
                                    ) {
                                        Column {
                                            Text(
                                                text = "CARDHOLDER",
                                                color = textColor.copy(alpha = 0.5f),
                                                fontSize = 8.sp,
                                                letterSpacing = 1.sp
                                            )
                                            Text(
                                                text = card.cardHolder.ifBlank { "AUTHORIZED MEMBER" },
                                                color = textColor,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }

                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = "EXPIRES",
                                                color = textColor.copy(alpha = 0.5f),
                                                fontSize = 8.sp,
                                                letterSpacing = 1.sp
                                            )
                                            Text(
                                                text = card.expiry.ifBlank { "12 / 28" },
                                                color = textColor,
                                                fontSize = 12.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }

                                        Text(
                                            text = card.network.ifBlank { "VISA" },
                                            color = textColor,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Surface(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { rotationY = 180f },
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, ColorHairlineGold)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Brush.linearGradient(bgColors))
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(36.dp)
                                            .padding(top = 10.dp)
                                            .background(Color(0xFF070708))
                                    )

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 20.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(32.dp),
                                            color = Color(0xFFF4F0E8),
                                            shape = RoundedCornerShape(2.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier.padding(horizontal = 8.dp),
                                                contentAlignment = Alignment.CenterStart
                                            ) {
                                                Text(
                                                    text = "AUTHORIZED SIGNATURE · NOT VALID UNLESS SIGNED",
                                                    fontSize = 7.sp,
                                                    color = Color(0xFF6B665E),
                                                    letterSpacing = 0.4.sp
                                                )
                                            }
                                        }

                                        Surface(
                                            modifier = Modifier
                                                .padding(start = 8.dp)
                                                .clickable {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    showCvv = !showCvv
                                                },
                                            shape = RoundedCornerShape(2.dp),
                                            color = ColorSurface,
                                            border = BorderStroke(1.dp, ColorAccentGold)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = if (showCvv) card.cvv else "•••",
                                                    fontSize = 12.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold,
                                                    color = ColorAccentGold
                                                )
                                                Spacer(Modifier.width(4.dp))
                                                VaultIcon(
                                                    pathData = if (showCvv) VaultIcons.EYE_OFF else VaultIcons.EYE,
                                                    tint = ColorAccentGold,
                                                    size = 12.dp
                                                )
                                            }
                                        }
                                    }

                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 20.dp, vertical = 14.dp)
                                    ) {
                                        Text(
                                            text = "24/7 International Concierge & Lost Card Hotline:",
                                            fontSize = 9.sp,
                                            color = textColor.copy(alpha = 0.5f)
                                        )
                                        Text(
                                            text = card.hotline,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = textColor,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = "ISSUED BY VAULTAIRE S.A. · ZERO KNOWLEDGE SECURED",
                                            fontSize = 7.sp,
                                            letterSpacing = 0.8.sp,
                                            color = textColor.copy(alpha = 0.4f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        isFlipped = !isFlipped
                    }
                ) {
                    VaultIcon(VaultIcons.REFRESH, tint = ColorAccentGold, size = 14.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isFlipped) "Showing back (tap to flip front)" else "Tap card to flip in 3D",
                        color = ColorAccentGold,
                        fontSize = 12.sp
                    )
                }

                Spacer(Modifier.height(28.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = ColorSurface,
                    border = BorderStroke(1.dp, ColorHairline)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onCopy(card.cardNumber, "Card Number")
                                }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Card Number", fontSize = 12.sp, color = ColorTextSecondary)
                                Text(card.cardNumber, fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = ColorTextPrimary)
                            }
                            VaultIcon(VaultIcons.COPY, tint = ColorAccentGold, size = 16.dp)
                        }

                        HorizontalDivider(color = ColorHairline, thickness = 1.dp)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onCopy(card.cvv, "CVV")
                                }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Security Code (CVV)", fontSize = 12.sp, color = ColorTextSecondary)
                                Text(card.cvv, fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = ColorTextPrimary)
                            }
                            VaultIcon(VaultIcons.COPY, tint = ColorAccentGold, size = 16.dp)
                        }

                        HorizontalDivider(color = ColorHairline, thickness = 1.dp)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onCopy(card.expiry, "Expiration Date")
                                }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Expiration Date", fontSize = 12.sp, color = ColorTextSecondary)
                                Text(card.expiry, fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = ColorTextPrimary)
                            }
                            VaultIcon(VaultIcons.COPY, tint = ColorAccentGold, size = 16.dp)
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = ColorAccentGold, contentColor = ColorBg),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("Done", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}

// ----------------------------------------------------
// PHASE 2: MULTI-MODE PASSWORD STUDIO (Passphrase / Complex / PIN)
// ----------------------------------------------------
@Composable
fun PasswordStudioDialog(
    onDismiss: () -> Unit,
    onCopy: (String, String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var mode by remember { mutableStateOf("complex") }
    var length by remember { mutableIntStateOf(20) }
    var wordCount by remember { mutableIntStateOf(5) }
    var pinLength by remember { mutableIntStateOf(6) }
    var useUpper by remember { mutableStateOf(true) }
    var useDigits by remember { mutableStateOf(true) }
    var useSymbols by remember { mutableStateOf(true) }

    var generatedPassword by remember {
        mutableStateOf(generateStudioPassword("complex", 20, useUpper = true, useDigits = true, useSymbols = true))
    }

    fun regenerate() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        generatedPassword = when (mode) {
            "passphrase" -> generateStudioPassword("passphrase", wordCount)
            "pin" -> generateStudioPassword("pin", pinLength)
            else -> generateStudioPassword("complex", length, useUpper, useDigits, useSymbols)
        }
    }

    val entropyBits = when (mode) {
        "passphrase" -> wordCount * 12.92f
        "pin" -> pinLength * 3.322f
        else -> {
            var pool = 26
            if (useUpper) pool += 26
            if (useDigits) pool += 10
            if (useSymbols) pool += 14
            length * log2(pool.toFloat())
        }
    }

    val (strengthLabel, strengthColor, activeSegments) = when {
        entropyBits < 35f -> Triple("Very Weak", ColorCrit, 1)
        entropyBits < 55f -> Triple("Weak", ColorWarn, 2)
        entropyBits < 75f -> Triple("Fair", Color(0xFFD4AF37), 3)
        entropyBits < 95f -> Triple("Strong", ColorOk, 4)
        else -> Triple("Military Grade", Color(0xFF50C878), 5)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(ColorBg)
                .safeDrawingPadding()
                .padding(20.dp),
            color = ColorBg
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        VaultIcon(VaultIcons.CLOSE, tint = ColorTextPrimary, size = 20.dp)
                    }
                    Text(
                        text = "PASSWORD STUDIO",
                        color = ColorAccentGold,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                    IconButton(onClick = { regenerate() }, modifier = Modifier.size(24.dp)) {
                        VaultIcon(VaultIcons.REFRESH, tint = ColorAccentGold, size = 18.dp)
                    }
                }

                Spacer(Modifier.height(20.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = ColorSurface,
                    border = BorderStroke(1.dp, ColorHairlineGold)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = generatedPassword,
                            color = ColorTextPrimary,
                            fontSize = if (generatedPassword.length > 25) 15.sp else 19.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            lineHeight = 26.sp
                        )

                        Spacer(Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (i in 1..5) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(5.dp)
                                        .background(
                                            if (i <= activeSegments) strengthColor else ColorTile,
                                            RoundedCornerShape(3.dp)
                                        )
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Strength: $strengthLabel",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = strengthColor
                            )
                            Text(
                                text = "${entropyBits.toInt()} bits entropy",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = ColorTextSecondary
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("complex" to "Complex", "passphrase" to "Passphrase", "pin" to "PIN").forEach { (mKey, mLabel) ->
                        val selected = mode == mKey
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clickable {
                                    mode = mKey
                                    regenerate()
                                },
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) ColorAccentSoft else ColorSurface,
                            border = BorderStroke(1.dp, if (selected) ColorAccentGold else ColorHairline)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = mLabel,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) ColorAccentGold else ColorTextSecondary
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = ColorSurface,
                    border = BorderStroke(1.dp, ColorHairline)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        when (mode) {
                            "passphrase" -> {
                                Text("Word Count: $wordCount words", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ColorTextPrimary)
                                Slider(
                                    value = wordCount.toFloat(),
                                    onValueChange = {
                                        wordCount = it.toInt()
                                        regenerate()
                                    },
                                    valueRange = 3f..8f,
                                    steps = 4,
                                    colors = SliderDefaults.colors(thumbColor = ColorAccentGold, activeTrackColor = ColorAccentGold)
                                )
                                Text("Generates memorable Diceware-style EFF passphrases separated by hyphens.", fontSize = 11.sp, color = ColorTextTertiary)
                            }
                            "pin" -> {
                                Text("PIN Length: $pinLength digits", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ColorTextPrimary)
                                Slider(
                                    value = pinLength.toFloat(),
                                    onValueChange = {
                                        pinLength = it.toInt()
                                        regenerate()
                                    },
                                    valueRange = 4f..12f,
                                    steps = 7,
                                    colors = SliderDefaults.colors(thumbColor = ColorAccentGold, activeTrackColor = ColorAccentGold)
                                )
                                Text("Pure numeric code for ATMs, SIM cards, or hardware locks.", fontSize = 11.sp, color = ColorTextTertiary)
                            }
                            else -> {
                                Text("Length: $length characters", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ColorTextPrimary)
                                Slider(
                                    value = length.toFloat(),
                                    onValueChange = {
                                        length = it.toInt()
                                        regenerate()
                                    },
                                    valueRange = 8f..48f,
                                    steps = 39,
                                    colors = SliderDefaults.colors(thumbColor = ColorAccentGold, activeTrackColor = ColorAccentGold)
                                )

                                Spacer(Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Uppercase (A-Z)", fontSize = 13.sp, color = ColorTextSecondary)
                                    Switch(
                                        checked = useUpper,
                                        onCheckedChange = { useUpper = it; regenerate() },
                                        colors = SwitchDefaults.colors(checkedThumbColor = ColorAccentGold, checkedTrackColor = ColorAccentSoft)
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Digits (0-9)", fontSize = 13.sp, color = ColorTextSecondary)
                                    Switch(
                                        checked = useDigits,
                                        onCheckedChange = { useDigits = it; regenerate() },
                                        colors = SwitchDefaults.colors(checkedThumbColor = ColorAccentGold, checkedTrackColor = ColorAccentSoft)
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Symbols (!@#$)", fontSize = 13.sp, color = ColorTextSecondary)
                                    Switch(
                                        checked = useSymbols,
                                        onCheckedChange = { useSymbols = it; regenerate() },
                                        colors = SwitchDefaults.colors(checkedThumbColor = ColorAccentGold, checkedTrackColor = ColorAccentSoft)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onCopy(generatedPassword, "Generated Password")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ColorAccentGold, contentColor = ColorBg),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    VaultIcon(VaultIcons.COPY, tint = ColorBg, size = 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Copy Password", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                Spacer(Modifier.height(10.dp))

                OutlinedButton(
                    onClick = { regenerate() },
                    border = BorderStroke(1.dp, ColorHairline),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    VaultIcon(VaultIcons.REFRESH, tint = ColorAccentGold, size = 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Generate Another", color = ColorTextPrimary, fontSize = 13.sp)
                }
            }
        }
    }
}

// ----------------------------------------------------
// PHASE 2: ITEM DETAIL, PASSWORD HISTORY & CUSTOM FIELDS
// ----------------------------------------------------
@Composable
fun ItemDetailDialog(
    item: VaultItem,
    onDismiss: () -> Unit,
    onUpdateItem: (VaultItem) -> Unit,
    onCopy: (String, String) -> Unit,
    onOpenPasswordStudio: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var showSecret by remember { mutableStateOf(false) }
    var isAddingField by remember { mutableStateOf(false) }
    var newFieldLabel by remember { mutableStateOf("") }
    var newFieldValue by remember { mutableStateOf("") }
    var newFieldTier by remember { mutableStateOf("concealed") }

    val revealedHistory = remember { mutableStateListOf<Int>() }
    val revealedFields = remember { mutableStateListOf<String>() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(ColorBg)
                .safeDrawingPadding()
                .padding(20.dp),
            color = ColorBg
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        VaultIcon(VaultIcons.CLOSE, tint = ColorTextPrimary, size = 20.dp)
                    }
                    Text(
                        text = "ITEM DETAILS",
                        color = ColorAccentGold,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onUpdateItem(item.copy(fav = !item.fav))
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        VaultIcon(
                            pathData = VaultIcons.STAR,
                            tint = if (item.fav) ColorAccentGold else ColorTextTertiary,
                            size = 20.dp
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = ColorSurface,
                    border = BorderStroke(1.dp, ColorHairline)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(42.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = ColorTile
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                val iconP = when (item.type) {
                                    "card" -> VaultIcons.CARD
                                    "login" -> VaultIcons.LOGIN
                                    "devkey" -> VaultIcons.DEVKEY
                                    "codes" -> VaultIcons.CODES
                                    "bank" -> VaultIcons.BANK
                                    "identity" -> VaultIcons.IDENTITY
                                    "note" -> VaultIcons.NOTE
                                    "membership" -> VaultIcons.MEMBERSHIP
                                    "totp" -> VaultIcons.CLOCK
                                    "coldstorage" -> VaultIcons.SHIELD
                                    else -> VaultIcons.OTHER
                                }
                                VaultIcon(iconP, tint = ColorAccentGold, size = 22.dp)
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(item.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = ColorTextPrimary)
                            Text(item.subtitle, fontSize = 12.sp, color = ColorTextSecondary)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = ColorSurface,
                    border = BorderStroke(1.dp, ColorHairline)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (item.username.isNotBlank()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onCopy(item.username, "Username")
                                    }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Username / Account", fontSize = 11.sp, color = ColorTextSecondary)
                                    Text(item.username, fontSize = 14.sp, color = ColorTextPrimary)
                                }
                                VaultIcon(VaultIcons.COPY, tint = ColorAccentGold, size = 16.dp)
                            }
                            HorizontalDivider(color = ColorHairline, thickness = 1.dp)
                        }

                        if (item.secret.isNotBlank()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Password / Key", fontSize = 11.sp, color = ColorTextSecondary)
                                    Text(
                                        text = if (showSecret) item.secret else "••••••••••••••••",
                                        fontSize = 14.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = ColorTextPrimary
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            showSecret = !showSecret
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        VaultIcon(
                                            pathData = if (showSecret) VaultIcons.EYE_OFF else VaultIcons.EYE,
                                            tint = ColorAccentGold,
                                            size = 16.dp
                                        )
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    IconButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onCopy(item.secret, "Password")
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        VaultIcon(VaultIcons.COPY, tint = ColorAccentGold, size = 16.dp)
                                    }
                                }
                            }
                        }

                        if (item.noteContent.isNotBlank()) {
                            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                Text("Secure Note", fontSize = 11.sp, color = ColorTextSecondary)
                                Spacer(Modifier.height(4.dp))
                                Text(item.noteContent, fontSize = 13.sp, color = ColorTextPrimary, lineHeight = 18.sp)
                            }
                        }

                        if (item.recoveryCodes.isNotEmpty()) {
                            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                Text("Recovery Codes (${item.recoveryCodes.size} codes)", fontSize = 11.sp, color = ColorTextSecondary)
                                Spacer(Modifier.height(6.dp))
                                item.recoveryCodes.chunked(2).forEach { pair ->
                                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        pair.forEach { c ->
                                            Surface(
                                                modifier = Modifier.weight(1f),
                                                color = ColorTile,
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(c, modifier = Modifier.padding(8.dp), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = ColorTextPrimary, textAlign = TextAlign.Center)
                                            }
                                        }
                                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }

                // PASSWORD HISTORY SECTION (Phase 2)
                if (item.history.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = "PASSWORD HISTORY (${item.history.size} ARCHIVED)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = ColorAccentGold,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(8.dp))

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = ColorSurface,
                        border = BorderStroke(1.dp, ColorHairline)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            item.history.forEachIndexed { idx, (historicVal, date) ->
                                val isRev = revealedHistory.contains(idx)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (isRev) historicVal else "••••••••••••••••",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 13.sp,
                                            color = ColorTextPrimary
                                        )
                                        Text(
                                            text = "Replaced $date",
                                            fontSize = 11.sp,
                                            color = ColorTextTertiary
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                if (isRev) revealedHistory.remove(idx) else revealedHistory.add(idx)
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            VaultIcon(if (isRev) VaultIcons.EYE_OFF else VaultIcons.EYE, tint = ColorAccentGold, size = 15.dp)
                                        }
                                        Spacer(Modifier.width(6.dp))
                                        IconButton(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onCopy(historicVal, "Archived Password")
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            VaultIcon(VaultIcons.COPY, tint = ColorAccentGold, size = 15.dp)
                                        }
                                    }
                                }
                                if (idx < item.history.size - 1) {
                                    HorizontalDivider(color = ColorHairline, thickness = 1.dp)
                                }
                            }
                        }
                    }
                }

                // DYNAMIC CUSTOM FIELD BUILDER (Phase 2)
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "CUSTOM FIELDS (${item.customFields.size})",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = ColorAccentGold,
                        letterSpacing = 1.sp
                    )
                    TextButton(onClick = { isAddingField = !isAddingField }) {
                        Text(if (isAddingField) "Cancel" else "+ Add Field", color = ColorAccentGold, fontSize = 12.sp)
                    }
                }

                if (item.customFields.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = ColorSurface,
                        border = BorderStroke(1.dp, ColorHairline)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            item.customFields.forEachIndexed { idx, field ->
                                val isFRev = revealedFields.contains(field.id) || field.tier == "normal"
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(field.label, fontSize = 12.sp, color = ColorTextSecondary)
                                            Spacer(Modifier.width(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = when (field.tier) {
                                                    "critical" -> ColorCrit.copy(alpha = 0.2f)
                                                    "concealed" -> ColorAccentSoft
                                                    else -> ColorTile
                                                }
                                            ) {
                                                Text(
                                                    text = field.tier.uppercase(),
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = when (field.tier) {
                                                        "critical" -> ColorCrit
                                                        "concealed" -> ColorAccentGold
                                                        else -> ColorTextSecondary
                                                    },
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = if (isFRev) field.value else "••••••••••••",
                                            fontFamily = if (field.tier == "normal") FontFamily.Default else FontFamily.Monospace,
                                            fontSize = 14.sp,
                                            color = ColorTextPrimary
                                        )
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (field.tier != "normal") {
                                            IconButton(
                                                onClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    if (isFRev) revealedFields.remove(field.id) else revealedFields.add(field.id)
                                                },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                VaultIcon(if (isFRev) VaultIcons.EYE_OFF else VaultIcons.EYE, tint = ColorAccentGold, size = 15.dp)
                                            }
                                            Spacer(Modifier.width(4.dp))
                                        }
                                        IconButton(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onCopy(field.value, field.label)
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            VaultIcon(VaultIcons.COPY, tint = ColorAccentGold, size = 15.dp)
                                        }
                                        Spacer(Modifier.width(4.dp))
                                        IconButton(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                val updatedList = item.customFields.filterNot { it.id == field.id }
                                                onUpdateItem(item.copy(customFields = updatedList))
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            VaultIcon(VaultIcons.TRASH, tint = ColorCrit, size = 15.dp)
                                        }
                                    }
                                }
                                if (idx < item.customFields.size - 1) {
                                    HorizontalDivider(color = ColorHairline, thickness = 1.dp)
                                }
                            }
                        }
                    }
                }

                if (isAddingField) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = ColorTile,
                        border = BorderStroke(1.dp, ColorAccentGold)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("New Custom Field", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ColorAccentGold)
                            Spacer(Modifier.height(10.dp))

                            OutlinedTextField(
                                value = newFieldLabel,
                                onValueChange = { newFieldLabel = it },
                                label = { Text("Field Label (e.g. PIN, Security Answer)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))

                            OutlinedTextField(
                                value = newFieldValue,
                                onValueChange = { newFieldValue = it },
                                label = { Text("Field Value") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(12.dp))

                            Text("Security Tier:", fontSize = 11.sp, color = ColorTextSecondary)
                            Spacer(Modifier.height(6.dp))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("normal" to "Normal", "concealed" to "Concealed", "critical" to "Critical").forEach { (tierKey, tierLbl) ->
                                    val isSel = newFieldTier == tierKey
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(34.dp)
                                            .clickable { newFieldTier = tierKey },
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSel) ColorAccentSoft else ColorSurface,
                                        border = BorderStroke(1.dp, if (isSel) ColorAccentGold else ColorHairline)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                tierLbl,
                                                fontSize = 11.sp,
                                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSel) ColorAccentGold else ColorTextSecondary
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(14.dp))

                            Button(
                                onClick = {
                                    if (newFieldLabel.isNotBlank() && newFieldValue.isNotBlank()) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        val newF = CustomField(
                                            label = newFieldLabel.trim(),
                                            value = newFieldValue.trim(),
                                            tier = newFieldTier
                                        )
                                        onUpdateItem(item.copy(customFields = item.customFields + newF))
                                        newFieldLabel = ""
                                        newFieldValue = ""
                                        isAddingField = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ColorAccentGold, contentColor = ColorBg),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Save Custom Field", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                OutlinedButton(
                    onClick = onOpenPasswordStudio,
                    border = BorderStroke(1.dp, ColorHairlineGold),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    VaultIcon(VaultIcons.KEY, tint = ColorAccentGold, size = 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Launch Password Studio", color = ColorAccentGold, fontSize = 13.sp)
                }

                Spacer(Modifier.height(10.dp))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = ColorTile, contentColor = ColorTextPrimary),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("Done", fontSize = 13.sp)
                }
            }
        }
    }
}

// ----------------------------------------------------
// PHASE 3: TOTP 2FA ROLLING CODE DIALOG
// ----------------------------------------------------
@Composable
fun TotpDialog(
    item: VaultItem,
    onDismiss: () -> Unit,
    onCopy: (String, String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis() / 1000L) }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = System.currentTimeMillis() / 1000L
            delay(500)
        }
    }

    val code = TotpEngine.generateTotp(item.totpSecret, currentTime)
    val remSec = TotpEngine.getRemainingSeconds(currentTime)
    val progress = TotpEngine.getRemainingProgress(currentTime)
    val formattedCode = if (code.length == 6) "${code.take(3)} ${code.takeLast(3)}" else code
    val arcColor = if (remSec <= 5) ColorWarn else ColorAccentGold

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(ColorBg)
                .safeDrawingPadding()
                .padding(20.dp),
            color = ColorBg
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        VaultIcon(VaultIcons.CLOSE, tint = ColorTextPrimary, size = 20.dp)
                    }
                    Text(
                        text = "2FA AUTHENTICATOR",
                        color = ColorAccentGold,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                    Spacer(Modifier.size(24.dp))
                }

                Spacer(Modifier.height(30.dp))

                Text(
                    text = item.title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = ColorTextPrimary
                )
                Text(
                    text = item.subtitle.ifBlank { "RFC 6238 Time-based OTP" },
                    fontSize = 13.sp,
                    color = ColorTextSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(Modifier.height(36.dp))

                Box(
                    modifier = Modifier.size(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(
                            color = ColorHairline,
                            radius = size.width / 2f - 4f,
                            style = Stroke(width = 4.dp.toPx())
                        )
                        drawArc(
                            color = arcColor,
                            startAngle = -90f,
                            sweepAngle = progress * 360f,
                            useCenter = false,
                            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${remSec}s",
                            fontSize = 22.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = arcColor
                        )
                        Text(
                            text = "rollover",
                            fontSize = 10.sp,
                            color = ColorTextTertiary
                        )
                    }
                }

                Spacer(Modifier.height(30.dp))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onCopy(code, item.title)
                        },
                    shape = RoundedCornerShape(16.dp),
                    color = ColorSurface,
                    border = BorderStroke(1.dp, ColorHairlineGold)
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 24.dp, horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = formattedCode,
                            fontSize = 38.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = ColorTextPrimary,
                            letterSpacing = 4.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            VaultIcon(VaultIcons.COPY, tint = ColorAccentGold, size = 14.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("Tap to copy code", fontSize = 12.sp, color = ColorAccentGold)
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = ColorTile,
                    border = BorderStroke(1.dp, ColorHairline)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("AUTHENTICATOR SPECS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ColorTextSecondary, letterSpacing = 1.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("Algorithm: HMAC-SHA1 · 30s timestep · 6 digits", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = ColorTextTertiary)
                        Text("Secret: ${item.totpSecret}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = ColorTextTertiary)
                    }
                }

                Spacer(Modifier.height(30.dp))

                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onCopy(code, item.title)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ColorAccentGold, contentColor = ColorBg),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    VaultIcon(VaultIcons.COPY, tint = ColorBg, size = 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Copy 2FA Code", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                Spacer(Modifier.height(10.dp))

                OutlinedButton(
                    onClick = onDismiss,
                    border = BorderStroke(1.dp, ColorHairline),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("Done", color = ColorTextPrimary, fontSize = 13.sp)
                }
            }
        }
    }
}

// ----------------------------------------------------
// PHASE 3: BIP-39 COLD STORAGE SEED VERIFICATION
// ----------------------------------------------------
@Composable
fun ColdStorageDialog(
    item: VaultItem,
    onDismiss: () -> Unit,
    onCopy: (String, String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var isRevealed by remember { mutableStateOf(false) }

    val words = remember(item.bip39Phrase) { Bip39Engine.parseWords(item.bip39Phrase) }
    val validation = remember(words) { Bip39Engine.validate(words) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(ColorBg)
                .safeDrawingPadding()
                .padding(20.dp),
            color = ColorBg
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        VaultIcon(VaultIcons.CLOSE, tint = ColorTextPrimary, size = 20.dp)
                    }
                    Text(
                        text = "COLD STORAGE ENGINE",
                        color = ColorAccentGold,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                    Spacer(Modifier.size(24.dp))
                }

                Spacer(Modifier.height(20.dp))

                Text(
                    text = item.title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = ColorTextPrimary
                )
                Text(
                    text = item.subtitle.ifBlank { "Hardware Wallet Recovery Phrase" },
                    fontSize = 13.sp,
                    color = ColorTextSecondary,
                    modifier = Modifier.padding(top = 2.dp, bottom = 16.dp)
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (validation.status.isValid) ColorAccentSoft else ColorCrit.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, if (validation.status.isValid) ColorAccentGold else ColorCrit),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        VaultIcon(
                            pathData = if (validation.status.isValid) VaultIcons.SHIELD else VaultIcons.CLOSE,
                            tint = if (validation.status.isValid) ColorOk else ColorCrit,
                            size = 20.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (validation.status.isValid) "BIP-39 CHECKSUM VERIFIED" else "CHECKSUM INVALID",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (validation.status.isValid) ColorOk else ColorCrit,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = validation.status.label,
                                fontSize = 12.sp,
                                color = ColorTextPrimary
                            )
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = ColorSurface,
                    border = BorderStroke(1.dp, ColorHairline)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Privacy Shield", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ColorTextPrimary)
                                Text("Conceals seed words from cameras & onlookers", fontSize = 11.sp, color = ColorTextTertiary)
                            }
                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    isRevealed = !isRevealed
                                }
                            ) {
                                VaultIcon(if (isRevealed) VaultIcons.EYE_OFF else VaultIcons.EYE, tint = ColorAccentGold, size = 20.dp)
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        if (!isRevealed) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        isRevealed = true
                                    },
                                shape = RoundedCornerShape(10.dp),
                                color = ColorTile
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    VaultIcon(VaultIcons.LOCK, tint = ColorAccentGold, size = 26.dp)
                                    Spacer(Modifier.height(8.dp))
                                    Text("Seed Words Concealed", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ColorTextPrimary)
                                    Text("Tap to reveal ${words.size} mnemonic words", fontSize = 11.sp, color = ColorTextSecondary)
                                }
                            }
                        } else {
                            words.chunked(2).forEachIndexed { rowIdx, pair ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    pair.forEachIndexed { colIdx, w ->
                                        val wordNum = (rowIdx * 2) + colIdx + 1
                                        Surface(
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp),
                                            color = ColorTile,
                                            border = BorderStroke(1.dp, ColorHairline)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = wordNum.toString().padStart(2, '0'),
                                                    fontSize = 11.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = ColorAccentGold,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    text = w,
                                                    fontSize = 13.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = ColorTextPrimary
                                                )
                                            }
                                        }
                                    }
                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onCopy(item.bip39Phrase, "BIP-39 Seed Phrase")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ColorAccentGold, contentColor = ColorBg),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    VaultIcon(VaultIcons.COPY, tint = ColorBg, size = 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Copy Seed Phrase", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                Spacer(Modifier.height(10.dp))

                OutlinedButton(
                    onClick = onDismiss,
                    border = BorderStroke(1.dp, ColorHairline),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("Done", color = ColorTextPrimary, fontSize = 13.sp)
                }
            }
        }
    }
}
