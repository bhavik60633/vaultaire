package dev.vaultaire.spike

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.spike_core.aeadRoundtrip
import uniffi.spike_core.argon2idTiming

// ----------------------------------------------------
// ADD ITEM SHEET (Exact 2-column grid matching Screenshot 2)
// ----------------------------------------------------
@Composable
fun AddItemSheet(
    onDismiss: () -> Unit,
    onSelectType: (String) -> Unit
) {
    val items = listOf(
        Triple("card", "Card", "Debit or credit card") to VaultIcons.CARD,
        Triple("login", "Password", "For a website or app") to VaultIcons.LOGIN,
        Triple("devkey", "API key", "Secrets and access keys") to VaultIcons.DEVKEY,
        Triple("codes", "Recovery codes", "Backup codes for two-step login") to VaultIcons.CODES,
        Triple("bank", "Bank account", "Account and routing numbers") to VaultIcons.BANK,
        Triple("identity", "Identity", "Passport, licence, ID card") to VaultIcons.IDENTITY,
        Triple("note", "Secure note", "Private text") to VaultIcons.NOTE,
        Triple("membership", "Membership", "Loyalty and member numbers") to VaultIcons.MEMBERSHIP,
        Triple("other", "Other", "Your own fields") to VaultIcons.OTHER,
        Triple("totp", "2FA Token", "Rolling verification code") to VaultIcons.CLOCK,
        Triple("coldstorage", "Cold storage", "BIP-39 12/24 seed phrase") to VaultIcons.SHIELD
    )

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
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    VaultIcon(VaultIcons.CLOSE, tint = ColorTextPrimary, size = 20.dp)
                }

                Spacer(Modifier.height(18.dp))

                Text(
                    text = "Add item",
                    color = ColorTextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "What would you like to keep?",
                    color = ColorTextSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 22.dp)
                )

                // 2-Column Grid matching Screenshot 2
                items.chunked(2).forEach { rowPairs ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rowPairs.forEach { (info, iconPath) ->
                            val (key, title, desc) = info
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(125.dp)
                                    .clickable { onSelectType(key) },
                                shape = RoundedCornerShape(16.dp),
                                color = ColorSurface,
                                border = BorderStroke(1.dp, ColorHairline)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.SpaceBetween
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

                                    Column {
                                        Text(
                                            text = title,
                                            color = ColorTextPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = desc,
                                            color = ColorTextSecondary,
                                            fontSize = 11.sp,
                                            lineHeight = 14.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                        if (rowPairs.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------
// PASSWORD UNLOCK DIALOG
// ----------------------------------------------------
@Composable
fun PasswordUnlockDialog(
    onDismiss: () -> Unit,
    onUnlock: (String, Boolean) -> Unit
) {
    var password by remember { mutableStateOf("vaultaire2026") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = ColorSurface,
            border = BorderStroke(1.dp, ColorHairlineGold),
            modifier = Modifier.padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Master Vault Password", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = ColorTextPrimary)
                Text("Supports master password or decoy PIN (9999)", fontSize = 12.sp, color = ColorTextTertiary, modifier = Modifier.padding(top = 2.dp, bottom = 14.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password or Decoy PIN", color = ColorTextSecondary) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ColorTextPrimary,
                        unfocusedTextColor = ColorTextPrimary,
                        focusedBorderColor = ColorAccentGold,
                        unfocusedBorderColor = ColorHairline
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(18.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = ColorTextSecondary) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (password.isNotBlank()) {
                                val isDecoy = DecoyVaultManager.isDecoyUnlock(password)
                                onUnlock(password, isDecoy)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ColorAccentGold, contentColor = ColorBg)
                    ) {
                        Text("Unlock Vault")
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------
// BACKUPS DIALOG (APPROACH C: SAF)
// ----------------------------------------------------
@Composable
fun BackupsDialog(
    itemCount: Int,
    onDismiss: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = ColorSurface,
            border = BorderStroke(1.dp, ColorHairlineGold),
            modifier = Modifier.padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Cloud & Local Backups", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = ColorTextPrimary)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        VaultIcon(VaultIcons.CLOSE, tint = ColorTextTertiary, size = 18.dp)
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = ColorAccentSoft,
                    border = BorderStroke(1.dp, ColorHairlineGold),
                    modifier = Modifier.padding(vertical = 12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "STORAGE ACCESS FRAMEWORK (SAF)",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = ColorAccentGold,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Vaultaire has NO internet permission. Android's system document picker handles writing files directly to Google Drive, USB, or local storage.",
                            fontSize = 12.sp,
                            color = ColorTextSecondary,
                            lineHeight = 16.sp
                        )
                    }
                }

                Text(
                    text = "Backup Format: VLTBK v1 (AES-256-GCM, 4096-byte padded bucket)",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = ColorTextTertiary
                )
                Text(
                    text = "Protection: Master Password + Recovery Key (2 secrets)",
                    fontSize = 11.sp,
                    color = ColorTextTertiary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Button(
                    onClick = onExportBackup,
                    colors = ButtonDefaults.buttonColors(containerColor = ColorAccentGold, contentColor = ColorBg),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("Export Encrypted Backup (.vltbk)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                Spacer(Modifier.height(10.dp))

                OutlinedButton(
                    onClick = onImportBackup,
                    border = BorderStroke(1.dp, ColorHairline),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("Restore from File (.vltbk)", color = ColorTextPrimary, fontSize = 13.sp)
                }
            }
        }
    }
}

// ----------------------------------------------------
// AIR-GAPPED ANIMATED QR SYNC DIALOG
// ----------------------------------------------------
@Composable
fun AnimatedQrSyncDialog(
    items: List<VaultItem>,
    onDismiss: () -> Unit,
    onImportPayload: (ByteArray) -> Unit
) {
    var mode by remember { mutableStateOf("transmit") }
    var isPlaying by remember { mutableStateOf(true) }
    var currentFrameIndex by remember { mutableIntStateOf(0) }

    val frames = remember(items) {
        val backupBytes = BackupEngine.createBackup(
            items = items,
            password = "vaultaire2026",
            recoveryKey = "7KQM-4TXB-R9HF-2WZN-D3PV-8YCJ-G5EA"
        )
        QrSyncEngine.createFrames(backupBytes)
    }

    LaunchedEffect(isPlaying, frames) {
        if (frames.isEmpty()) return@LaunchedEffect
        while (isPlaying) {
            delay(125)
            currentFrameIndex = (currentFrameIndex + 1) % frames.size
        }
    }

    val currentFrame = if (frames.isNotEmpty()) frames[currentFrameIndex] else null
    val currentMatrix = remember(currentFrame) {
        currentFrame?.let { QrSyncEngine.generateQrMatrix(it.content, 260) }
    }

    val receiver = remember { QrSyncEngine.Receiver() }
    var receivedCount by remember { mutableIntStateOf(0) }
    var receiveMessage by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = ColorSurface,
            border = BorderStroke(1.dp, ColorHairlineGold),
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Air-Gapped QR Stream", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = ColorTextPrimary)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        VaultIcon(VaultIcons.CLOSE, tint = ColorTextTertiary, size = 18.dp)
                    }
                }

                Spacer(Modifier.height(14.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clickable { mode = "transmit" },
                        shape = RoundedCornerShape(8.dp),
                        color = if (mode == "transmit") ColorAccentSoft else ColorElevated,
                        border = BorderStroke(1.dp, if (mode == "transmit") ColorAccentGold else ColorHairline)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("Transmit (Show QR)", fontSize = 12.sp, color = if (mode == "transmit") ColorAccentGold else ColorTextSecondary)
                        }
                    }
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clickable { mode = "receive" },
                        shape = RoundedCornerShape(8.dp),
                        color = if (mode == "receive") ColorAccentSoft else ColorElevated,
                        border = BorderStroke(1.dp, if (mode == "receive") ColorAccentGold else ColorHairline)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("Receive (Simulate)", fontSize = 12.sp, color = if (mode == "receive") ColorAccentGold else ColorTextSecondary)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                if (mode == "transmit") {
                    if (currentMatrix != null) {
                        androidx.compose.foundation.Canvas(
                            modifier = Modifier
                                .size(240.dp)
                                .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            val rows = currentMatrix.size
                            val cols = currentMatrix[0].size
                            val cellW = size.width / cols
                            val cellH = size.height / rows
                            for (y in 0 until rows) {
                                for (x in 0 until cols) {
                                    if (currentMatrix[y][x]) {
                                        drawRect(
                                            color = androidx.compose.ui.graphics.Color.Black,
                                            topLeft = androidx.compose.ui.geometry.Offset(x * cellW, y * cellH),
                                            size = androidx.compose.ui.geometry.Size(cellW, cellH)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = "Frame ${currentFrameIndex + 1} of ${frames.size} · 8 fps · BC-UR v1",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = ColorTextSecondary
                    )

                    Spacer(Modifier.height(14.dp))

                    OutlinedButton(
                        onClick = { isPlaying = !isPlaying },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, ColorHairline)
                    ) {
                        Text(if (isPlaying) "Pause Loop" else "Resume Stream", fontSize = 12.sp, color = ColorAccentGold)
                    }
                } else {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        color = ColorTile,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            VaultIcon(VaultIcons.QR, tint = ColorAccentGold, size = 32.dp)
                            Spacer(Modifier.height(10.dp))
                            Text("Simulate Receiving QR Stream", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = ColorTextPrimary)
                            Text("Feeds local BC-UR multipart chunks into decoder engine.", fontSize = 11.sp, color = ColorTextSecondary, modifier = Modifier.padding(top = 4.dp))

                            Spacer(Modifier.height(14.dp))

                            Button(
                                onClick = {
                                    receiver.reset()
                                    var donePayload: ByteArray? = null
                                    for (f in frames) {
                                        if (receiver.addFrame(f.content)) {
                                            donePayload = receiver.assemble()
                                            break
                                        }
                                    }
                                    receivedCount = receiver.getReceivedCount()
                                    if (donePayload != null) {
                                        receiveMessage = "Reassembled ${donePayload.size} bytes from ${frames.size} parts!"
                                        onImportPayload(donePayload)
                                    } else {
                                        receiveMessage = "Missing parts. Reassembly incomplete."
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ColorAccentGold, contentColor = ColorBg),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Feed ${frames.size} frames to decoder", fontSize = 12.sp)
                            }
                        }
                    }

                    if (receiveMessage != null) {
                        Text(
                            text = receiveMessage!!,
                            color = ColorOk,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------
// DIAGNOSTICS & SPECS DIALOG
// ----------------------------------------------------
@Composable
fun DiagnosticsDialog(
    probe: KeystoreProbe,
    logs: List<String>,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var isBusy by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = ColorSurface,
            border = BorderStroke(1.dp, ColorHairline),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Security & Keystore", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = ColorTextPrimary)
                    TextButton(onClick = onDismiss) { Text("Done", color = ColorAccentGold) }
                }

                Spacer(Modifier.height(10.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { probe.environment() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Specs", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                withContext(Dispatchers.Default) {
                                    runCatching { aeadRoundtrip() }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("AEAD", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            if (!isBusy) {
                                isBusy = true
                                coroutineScope.launch {
                                    withContext(Dispatchers.Default) {
                                        runCatching { argon2idTiming(65536u, 3u, 1u) }
                                    }
                                    isBusy = false
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Argon2", fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.height(10.dp))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    color = ColorElevated,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        logs.forEach { line ->
                            Text(
                                text = line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = ColorTextSecondary,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
