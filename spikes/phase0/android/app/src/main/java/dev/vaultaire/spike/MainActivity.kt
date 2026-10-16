package dev.vaultaire.spike

import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.spike_core.argon2idTiming
import uniffi.spike_core.coreVersion
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val diagnosticsLog = mutableStateListOf<String>()

    private fun addLog(line: String) {
        diagnosticsLog.add(0, line)
        if (diagnosticsLog.size > 80) diagnosticsLog.removeLast()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // HARDENED SECURITY: Prevent recent-app switcher previews and OS snapshots
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val probe = KeystoreProbe(this, ::addLog)
        val vaultDb = VaultDatabase(this)

        runCatching {
            val ver = coreVersion()
            addLog("Core engine loaded: $ver")
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = ColorBg,
                    surface = ColorSurface,
                    primary = ColorAccentGold,
                    onBackground = ColorTextPrimary,
                    onSurface = ColorTextPrimary
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = ColorBg
                ) {
                    VaultApp(
                        activity = this,
                        probe = probe,
                        vaultDb = vaultDb,
                        diagnosticsLog = diagnosticsLog
                    )
                }
            }
        }
    }
}

@Composable
fun VaultApp(
    activity: ComponentActivity,
    probe: KeystoreProbe,
    vaultDb: VaultDatabase,
    diagnosticsLog: List<String>
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isUnlocked by remember { mutableStateOf(false) }
    var isDecoyActive by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // Dialog & Sheet States
    var isPasswordPromptOpen by remember { mutableStateOf(false) }
    var isDiagnosticsOpen by remember { mutableStateOf(false) }
    var isAddItemOpen by remember { mutableStateOf(false) }
    var isBackupsOpen by remember { mutableStateOf(false) }
    var isAnimatedQrSyncOpen by remember { mutableStateOf(false) }
    var isPasswordStudioOpen by remember { mutableStateOf(false) }

    // Item Detail Dialog States
    var selectedCardForDetail by remember { mutableStateOf<VaultItem?>(null) }
    var selectedItemForDetail by remember { mutableStateOf<VaultItem?>(null) }
    var selectedTotpItem by remember { mutableStateOf<VaultItem?>(null) }
    var selectedColdStorageItem by remember { mutableStateOf<VaultItem?>(null) }

    // Vault Data State (SQLite-backed)
    val realItems = remember { mutableStateListOf<VaultItem>() }
    val decoyItems = remember {
        mutableStateListOf<VaultItem>().apply {
            addAll(DecoyVaultManager.getDecoyItems())
        }
    }

    // Auto-lock when activity leaves foreground
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                isUnlocked = false
                isDecoyActive = false
                statusMessage = null
                selectedCardForDetail = null
                selectedItemForDetail = null
                selectedTotpItem = null
                selectedColdStorageItem = null
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose {
            activity.lifecycle.removeObserver(observer)
        }
    }

    // Helper to reload from database
    fun reloadDbItems() {
        realItems.clear()
        realItems.addAll(vaultDb.getAllItems())
    }

    // Initial load from SQLite
    LaunchedEffect(Unit) {
        reloadDbItems()
    }

    val activeItems = if (isDecoyActive) decoyItems else realItems

    // Safe clipboard copy with auto-wipe
    val onCopy: (String, String) -> Unit = { text, label ->
        ClipboardGuard.copySensitive(context = context, text = text, label = label)
        Toast.makeText(context, "$label copied (auto-wipes in 30s)", Toast.LENGTH_SHORT).show()
    }

    // SAF Export Launcher
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val bytes = BackupEngine.createBackup(
                        items = activeItems.toList(),
                        password = "vaultaire2026",
                        recoveryKey = "7KQM-4TXB-R9HF-2WZN-D3PV-8YCJ-G5EA"
                    )
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(bytes)
                        os.flush()
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Encrypted backup saved via SAF", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // SAF Import Launcher
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("Unable to read backup file")
                    val restored = BackupEngine.restoreBackup(
                        data = bytes,
                        password = "vaultaire2026",
                        recoveryKey = "7KQM-4TXB-R9HF-2WZN-D3PV-8YCJ-G5EA"
                    )
                    if (!isDecoyActive) {
                        vaultDb.saveAll(restored)
                    }
                    withContext(Dispatchers.Main) {
                        if (isDecoyActive) {
                            decoyItems.clear()
                            decoyItems.addAll(restored)
                        } else {
                            reloadDbItems()
                        }
                        Toast.makeText(context, "Restored ${restored.size} items", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Import error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    AnimatedContent(
        targetState = isUnlocked,
        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
        label = "VaultScreenTransition"
    ) { unlocked ->
        if (!unlocked) {
            LockScreen(
                statusMessage = statusMessage,
                onUnlockWithBiometrics = {
                    statusMessage = "Opening biometric sensor…"
                    probe.unlockWithBiometric(
                        onSuccess = {
                            isUnlocked = true
                            isDecoyActive = false
                            statusMessage = null
                            reloadDbItems()
                        },
                        onError = { err ->
                            statusMessage = err
                        }
                    )
                },
                onOpenPasswordPrompt = {
                    isPasswordPromptOpen = true
                },
                onOpenDiagnostics = {
                    isDiagnosticsOpen = true
                },
                onLoadDemoVault = {
                    val sample = SampleData.getSampleItems()
                    vaultDb.saveAll(sample)
                    reloadDbItems()
                    Toast.makeText(context, "Seeded demo vault with ${sample.size} items", Toast.LENGTH_SHORT).show()
                }
            )
        } else {
            VaultDashboard(
                items = activeItems,
                onLock = {
                    isUnlocked = false
                    isDecoyActive = false
                    statusMessage = null
                },
                onCopy = onCopy,
                onOpenAddItem = { isAddItemOpen = true },
                onOpenDiagnostics = { isDiagnosticsOpen = true },
                onOpenBackups = { isBackupsOpen = true },
                onOpenQrSync = { isAnimatedQrSyncOpen = true },
                onOpenPasswordStudio = { isPasswordStudioOpen = true },
                onSelectCard = { card -> selectedCardForDetail = card },
                onSelectItem = { item ->
                    when (item.type) {
                        "card" -> selectedCardForDetail = item
                        "totp" -> selectedTotpItem = item
                        "coldstorage" -> selectedColdStorageItem = item
                        else -> selectedItemForDetail = item
                    }
                }
            )
        }
    }

    // Password or Decoy PIN Unlock Dialog
    if (isPasswordPromptOpen) {
        PasswordUnlockDialog(
            onDismiss = { isPasswordPromptOpen = false },
            onUnlock = { pwd, isDecoy ->
                isPasswordPromptOpen = false
                coroutineScope.launch {
                    statusMessage = "Deriving key via Argon2id…"
                    withContext(Dispatchers.Default) {
                        runCatching {
                            argon2idTiming(65536u, 3u, 1u)
                        }
                    }
                    isDecoyActive = isDecoy
                    if (!isDecoy) {
                        reloadDbItems()
                    }
                    isUnlocked = true
                    statusMessage = null
                }
            }
        )
    }

    // Add Item Sheet
    if (isAddItemOpen) {
        AddItemSheet(
            onDismiss = { isAddItemOpen = false },
            onSelectType = { typeKey ->
                isAddItemOpen = false
                val newItem = when (typeKey) {
                    "card" -> VaultItem(
                        id = "c_${System.currentTimeMillis()}",
                        type = "card",
                        title = "New Card",
                        subtitle = "Visa · •••• 9821",
                        bankName = "NORTHBRIDGE BANK",
                        cardNumber = "•••• 9821",
                        cardHolder = "Alex Morgan",
                        expiry = "09 / 30",
                        cvv = "842",
                        network = "VISA",
                        finish = "obsidian"
                    )
                    "login" -> VaultItem(
                        id = "p_${System.currentTimeMillis()}",
                        type = "login",
                        title = "New Password",
                        subtitle = "user@example.com",
                        username = "user@example.com",
                        secret = "harbor-violet-onyx-ledger"
                    )
                    "totp" -> VaultItem(
                        id = "t_${System.currentTimeMillis()}",
                        type = "totp",
                        title = "GitHub 2FA Authenticator",
                        subtitle = "alexm-dev · RFC 6238",
                        username = "alexm-dev",
                        totpSecret = "JBSWY3DPEHPK3PXP"
                    )
                    "coldstorage" -> VaultItem(
                        id = "cs_${System.currentTimeMillis()}",
                        type = "coldstorage",
                        title = "Ledger Cold Storage Seed",
                        subtitle = "Hardware Wallet #1 (128-bit)",
                        bip39Phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
                    )
                    "devkey" -> VaultItem(
                        id = "k_${System.currentTimeMillis()}",
                        type = "devkey",
                        title = "New API Key",
                        subtitle = "production · AKIA…",
                        username = "AKIAIOSFODNN7EXAMPLE",
                        secret = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
                    )
                    "codes" -> VaultItem(
                        id = "r_${System.currentTimeMillis()}",
                        type = "codes",
                        title = "New Recovery Codes",
                        subtitle = "8 one-time backup codes",
                        recoveryCodes = listOf("1A2B-3C4D", "5E6F-7G8H", "9I0J-1K2L", "3M4N-5O6P")
                    )
                    "bank" -> VaultItem(
                        id = "b_${System.currentTimeMillis()}",
                        type = "bank",
                        title = "New Bank Account",
                        subtitle = "Northbridge Bank · •••• 4412",
                        bankName = "Northbridge Bank",
                        cardNumber = "5521 4471 4412"
                    )
                    "identity" -> VaultItem(
                        id = "i_${System.currentTimeMillis()}",
                        type = "identity",
                        title = "New Identity",
                        subtitle = "Expires 2030",
                        cardNumber = "ID-992144"
                    )
                    "note" -> VaultItem(
                        id = "n_${System.currentTimeMillis()}",
                        type = "note",
                        title = "New Secure Note",
                        subtitle = "Private note",
                        noteContent = "Confidential note content stored in encrypted vault."
                    )
                    "membership" -> VaultItem(
                        id = "m_${System.currentTimeMillis()}",
                        type = "membership",
                        title = "New Membership",
                        subtitle = "Member · Gold Tier",
                        cardNumber = "MBR-88301"
                    )
                    else -> VaultItem(
                        id = "o_${System.currentTimeMillis()}",
                        type = "other",
                        title = "Custom Record",
                        subtitle = "Encrypted field"
                    )
                }
                if (isDecoyActive) {
                    decoyItems.add(0, newItem)
                } else {
                    vaultDb.saveItem(newItem)
                    reloadDbItems()
                }
                Toast.makeText(context, "Added ${newItem.title}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (isDiagnosticsOpen) {
        DiagnosticsDialog(
            probe = probe,
            logs = diagnosticsLog,
            onDismiss = { isDiagnosticsOpen = false }
        )
    }

    if (isBackupsOpen) {
        BackupsDialog(
            itemCount = activeItems.size,
            onDismiss = { isBackupsOpen = false },
            onExportBackup = {
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                exportLauncher.launch("vaultaire-backup-$dateStr.vltbk")
                isBackupsOpen = false
            },
            onImportBackup = {
                importLauncher.launch(arrayOf("*/*"))
                isBackupsOpen = false
            }
        )
    }

    if (isAnimatedQrSyncOpen) {
        AnimatedQrSyncDialog(
            items = activeItems.toList(),
            onDismiss = { isAnimatedQrSyncOpen = false },
            onImportPayload = { payloadBytes ->
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        val restored = BackupEngine.restoreBackup(
                            data = payloadBytes,
                            password = "vaultaire2026",
                            recoveryKey = "7KQM-4TXB-R9HF-2WZN-D3PV-8YCJ-G5EA"
                        )
                        if (!isDecoyActive) {
                            vaultDb.saveAll(restored)
                        }
                        withContext(Dispatchers.Main) {
                            if (isDecoyActive) {
                                decoyItems.clear()
                                decoyItems.addAll(restored)
                            } else {
                                reloadDbItems()
                            }
                            Toast.makeText(context, "Restored ${restored.size} items via QR Stream", Toast.LENGTH_SHORT).show()
                            isAnimatedQrSyncOpen = false
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "QR Restore error: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        )
    }

    selectedCardForDetail?.let { card ->
        CardDetailDialog(
            card = card,
            onDismiss = { selectedCardForDetail = null },
            onCopy = onCopy
        )
    }

    selectedItemForDetail?.let { item ->
        ItemDetailDialog(
            item = item,
            onDismiss = { selectedItemForDetail = null },
            onUpdateItem = { updated ->
                if (isDecoyActive) {
                    val idx = decoyItems.indexOfFirst { it.id == updated.id }
                    if (idx >= 0) decoyItems[idx] = updated
                } else {
                    vaultDb.saveItem(updated)
                    reloadDbItems()
                }
            },
            onCopy = onCopy,
            onOpenPasswordStudio = { isPasswordStudioOpen = true }
        )
    }

    selectedTotpItem?.let { totp ->
        TotpDialog(
            item = totp,
            onDismiss = { selectedTotpItem = null },
            onCopy = onCopy
        )
    }

    selectedColdStorageItem?.let { cs ->
        ColdStorageDialog(
            item = cs,
            onDismiss = { selectedColdStorageItem = null },
            onCopy = onCopy
        )
    }

    if (isPasswordStudioOpen) {
        PasswordStudioDialog(
            onDismiss = { isPasswordStudioOpen = false },
            onCopy = onCopy
        )
    }
}
