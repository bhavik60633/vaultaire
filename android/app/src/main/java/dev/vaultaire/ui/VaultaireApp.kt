package dev.vaultaire.ui

import androidx.compose.foundation.Image
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.vaultaire.R
import dev.vaultaire.core.VaultException
import dev.vaultaire.security.Refusal
import dev.vaultaire.ui.theme.CapsLine
import dev.vaultaire.ui.theme.FieldLabel
import dev.vaultaire.ui.theme.GhostButton
import dev.vaultaire.ui.theme.Grow
import dev.vaultaire.ui.theme.IconAction
import dev.vaultaire.ui.theme.LocalVaultType
import dev.vaultaire.ui.theme.LockEmblem
import dev.vaultaire.ui.theme.Notice
import dev.vaultaire.ui.theme.Obsidian
import dev.vaultaire.ui.theme.Panel
import dev.vaultaire.ui.theme.PrimaryButton
import dev.vaultaire.ui.theme.SecondaryButton
import dev.vaultaire.ui.theme.StepHeader
import dev.vaultaire.ui.theme.VaultField
import dev.vaultaire.ui.theme.VaultaireTheme
import dev.vaultaire.ui.theme.tag
import java.util.Calendar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val BETA_WARNING = "Unaudited beta. Do not store real credentials."
const val TEST_BUILD_WARNING = "Test build: keys are not hardware-protected"

/** Where the user is. Kept in memory only: a restarted app always starts at the lock screen. */
private sealed class Step {
    data object Welcome : Step()
    data object CreatePassword : Step()
    class ShowRecoveryKey(val key: CharArray) : Step()
    data object Fingerprint : Step()
    data object Home : Step()
}

/** The whole app: onboarding, the lock screen and home, in the Obsidian design. */
@Composable
fun VaultaireApp(gateway: VaultGateway, testBuild: Boolean) {
    VaultaireTheme {
        val unlocked by gateway.unlocked.collectAsState()
        var step by remember {
            mutableStateOf<Step>(if (gateway.vaultExists()) Step.Home else Step.Welcome)
        }
        var failures by remember { mutableStateOf(0) }
        val locked = !unlocked && gateway.vaultExists()

        Box(
            Modifier
                .fillMaxSize()
                .background(Obsidian.bg)
                .ambientLight()
                // Clear of the status bar, the navigation bar and the keyboard, so a field being
                // typed in always stays in view.
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            when {
                locked -> LockFlow(gateway, testBuild) {
                    failures = gateway.failuresSinceLastUnlock()
                    step = Step.Home
                }
                else -> when (val current = step) {
                    Step.Welcome -> Welcome(gateway, testBuild) { step = Step.CreatePassword }
                    Step.CreatePassword -> CreatePassword(gateway, testBuild) { step = Step.ShowRecoveryKey(it) }
                    is Step.ShowRecoveryKey -> ShowRecoveryKey(current.key, testBuild) { step = Step.Fingerprint }
                    Step.Fingerprint -> FingerprintStep(gateway, testBuild) { step = Step.Home }
                    Step.Home -> {
                        val data = gateway as? VaultData
                        if (data != null) {
                            // Recomputed at every unlock: the vault's own record of the last bank.
                            val start = remember(unlocked) { startPage(data) }
                            VaultArea(
                                data, start, failures,
                                onDismissFailures = { failures = 0 },
                                onLock = { gateway.lock() },
                                frame = { actions, content -> Screen(testBuild, actions, content) },
                            )
                        } else {
                            Home(failures, testBuild, onDismiss = { failures = 0 }) { gateway.lock() }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A page in the design: the content scrolls, the actions stay at the bottom, and the beta line
 * closes every screen.
 */
@Composable
private fun Screen(
    testBuild: Boolean,
    actions: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (testBuild) TestBuildNote()
            content()
        }
        // Scrollable too, so that on a short screen with a large font the buttons stay reachable.
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 16.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            actions()
            CapsLine(BETA_WARNING)
        }
    }
}

/**
 * The light the whole app sits in: a soft gold glow from above that drifts slowly across the
 * page, as if from a lamp. Still when the phone's animations are off.
 */
@Composable
private fun Modifier.ambientLight(): Modifier {
    val context = androidx.compose.ui.platform.LocalContext.current
    val motion = remember {
        android.provider.Settings.Global.getFloat(
            context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        ) > 0f
    }
    val drift = if (motion) {
        rememberInfiniteTransition(label = "light").animateFloat(
            0f, 1f, infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Reverse), label = "x",
        ).value
    } else {
        0.5f
    }
    return drawBehind {
        val w = size.width
        drawRect(
            Brush.radialGradient(
                listOf(Obsidian.accent.copy(alpha = 0.13f), Obsidian.accent.copy(alpha = 0.04f), Color.Transparent),
                center = Offset(w * (0.3f + 0.4f * drift), -w * 0.18f),
                radius = w * 1.05f,
            ),
        )
        drawRect(
            Brush.verticalGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)),
                startY = size.height * 0.6f,
                endY = size.height,
            ),
        )
    }
}

@Composable
private fun TestBuildNote() {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Obsidian.warnSoft, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .padding(vertical = 6.dp),
    ) { CapsLine(TEST_BUILD_WARNING, color = Obsidian.warn) }
}

@Composable
private fun Lockup() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painterResource(R.drawable.vaultaire_lockup),
            contentDescription = "Vaultaire",
            modifier = Modifier
                .width(300.dp)
                .height(200.dp),
        )
        Text(
            "You have things worth protecting.",
            style = LocalVaultType.current.small.copy(color = Obsidian.text2),
            modifier = Modifier.padding(top = 0.dp),
        )
    }
}

// ---------- onboarding ----------

@Composable
private fun Welcome(gateway: VaultGateway, testBuild: Boolean, onCreate: () -> Unit) {
    val type = LocalVaultType.current
    val refusal = remember { gateway.refusal() }
    Screen(testBuild, actions = {
        if (refusal == null) PrimaryButton("Create a vault", onCreate, Modifier.tag("create"))
    }) {
        Lockup()
        Spacer(Modifier.height(8.dp))
        Text(
            "Your cards, PINs and bank details, encrypted on this phone. Nothing is sent anywhere.",
            style = type.body.copy(color = Obsidian.text2),
        )
        Spacer(Modifier.height(24.dp))
        if (refusal != null) {
            Notice(
                when (refusal) {
                    Refusal.NO_SCREEN_LOCK ->
                        "Set a screen lock (PIN, pattern or password) on this phone first. " +
                            "Without one, anyone holding the phone could use the vault's keys."
                    Refusal.SOFTWARE_KEYS ->
                        "This phone keeps keys in software, not in hardware, so a vault can't be " +
                            "created on it."
                },
                textModifier = Modifier.tag("refusal"),
            )
        }
    }
}

@Composable
private fun CreatePassword(gateway: VaultGateway, testBuild: Boolean, onCreated: (CharArray) -> Unit) {
    val type = LocalVaultType.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Screen(testBuild, actions = {
            PrimaryButton(
                if (busy) "Creating…" else "Continue",
                onClick = {
                    if (password != confirm) {
                        error = "The passwords don't match."
                        return@PrimaryButton
                    }
                    busy = true
                    scope.launch {
                        try {
                            onCreated(gateway.create(password.toCharArray()))
                        } catch (e: VaultException) {
                            error = when (e.code) {
                                "weak_password" ->
                                    "Choose a stronger password: at least 12 characters, and not a " +
                                        "common word or pattern."
                                else -> "Couldn't create the vault. Nothing was saved."
                            }
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = password.isNotEmpty() && confirm.isNotEmpty() && !busy,
                modifier = Modifier.tag("continue"),
            )
    }) {
        StepHeader(1, 3)
        Spacer(Modifier.height(20.dp))
        Text("Choose a vault password", style = type.displayL)
        Text(
            "At least 12 characters. A few unrelated words make a strong password you can remember.",
            style = type.body.copy(color = Obsidian.text2),
        )
        Spacer(Modifier.height(8.dp))
        VaultField(password, { password = it }, "Vault password", secret = true, fieldModifier = Modifier.tag("password"))
        VaultField(confirm, { confirm = it }, "Type it again", secret = true, fieldModifier = Modifier.tag("confirm"))
        error?.let { ErrorText(it) }
    }
}

@Composable
private fun ShowRecoveryKey(key: CharArray, testBuild: Boolean, onDone: () -> Unit) {
    val type = LocalVaultType.current
    var writtenDown by remember { mutableStateOf(false) }
    val groups = remember { String(key).split('-') }

    Screen(testBuild, actions = {
            PrimaryButton(
                "Continue",
                onClick = {
                    key.fill('\u0000')
                    onDone()
                },
                enabled = writtenDown,
                modifier = Modifier.tag("continue"),
            )
    }) {
        StepHeader(2, 3)
        Spacer(Modifier.height(20.dp))
        Text("Your Recovery Key", style = type.displayL)
        Text(
            "Write it down by hand. With your vault password, it is the only way back in if this " +
                "phone loses its unlock keys, and the only way to restore a backup on a new phone.",
            style = type.body.copy(color = Obsidian.text2),
        )
        Panel(
            Modifier
                .tag("recoveryKey")
                .semantics(mergeDescendants = false) { text = AnnotatedString(String(key)) },
        ) {
            groups.chunked(2).forEachIndexed { row, pair ->
                Row(Modifier.padding(vertical = 6.dp)) {
                    pair.forEachIndexed { col, group ->
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text("${row * 2 + col + 1}", style = type.label.copy(color = Obsidian.accent))
                            Spacer(Modifier.width(10.dp))
                            Text(group, style = type.monoLarge)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Notice("Keep this away from your phone and your cloud photos. We will never ask you for it.")
        Text("Screenshots are blocked on this screen.", style = type.faint)
        Row(
            Modifier
                .tag("writtenDown")
                .toggleable(value = writtenDown, role = Role.Checkbox) { writtenDown = it }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = writtenDown,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(
                    checkedColor = Obsidian.accent,
                    uncheckedColor = Obsidian.text3,
                    checkmarkColor = Obsidian.bg,
                ),
            )
            Spacer(Modifier.width(10.dp))
            Text("I have written it down by hand", style = type.body)
        }
    }
}

@Composable
private fun FingerprintStep(gateway: VaultGateway, testBuild: Boolean, onDone: () -> Unit) {
    val type = LocalVaultType.current
    val scope = rememberCoroutineScope()
    Screen(testBuild, actions = {
            PrimaryButton(
                "Use fingerprint",
                onClick = {
                    scope.launch {
                        gateway.enrolFingerprint()
                        gateway.onboardingFinished()
                        onDone()
                    }
                },
                icon = "finger",
                modifier = Modifier.tag("useFingerprint"),
            )
            GhostButton(
                "Not now",
                onClick = {
                    gateway.onboardingFinished()
                    onDone()
                },
                modifier = Modifier.tag("notNow"),
            )
    }) {
        StepHeader(3, 3)
        Spacer(Modifier.height(20.dp))
        Text("Unlock with your fingerprint?", style = type.displayL)
        Text(
            "Your fingerprint opens the vault without typing. You can always use your vault password instead.",
            style = type.body.copy(color = Obsidian.text2),
        )
        Notice(
            "Anyone whose fingerprint is on this phone can open the vault. Remove other people's " +
                "fingerprints first.",
        )
    }
}

// ---------- lock ----------

@Composable
private fun LockFlow(gateway: VaultGateway, testBuild: Boolean, onUnlocked: () -> Unit) {
    var usePassword by remember { mutableStateOf(false) }
    if (usePassword) {
        PasswordScreen(gateway, testBuild, onBack = { usePassword = false }, onUnlocked = onUnlocked)
    } else {
        LockScreen(gateway, testBuild, onPassword = { usePassword = true }, onUnlocked = onUnlocked)
    }
}

@Composable
private fun LockScreen(gateway: VaultGateway, testBuild: Boolean, onPassword: () -> Unit, onUnlocked: () -> Unit) {
    val scope = rememberCoroutineScope()
    Screen(testBuild, actions = {
            PrimaryButton(
                "Unlock with fingerprint",
                onClick = { scope.launch { if (gateway.unlockWithFingerprint()) onUnlocked() } },
                icon = "finger",
                modifier = Modifier.tag("unlockFingerprint"),
            )
            SecondaryButton("Use vault password", onPassword, Modifier.tag("usePassword"))
    }) {
        Lockup()
        Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) { LockEmblem() }
    }
}

@Composable
private fun PasswordScreen(gateway: VaultGateway, testBuild: Boolean, onBack: () -> Unit, onUnlocked: () -> Unit) {
    val type = LocalVaultType.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var delayS by remember { mutableStateOf(gateway.remainingDelayS()) }

    LaunchedEffect(delayS) {
        if (delayS > 0) {
            delay(1_000)
            delayS = gateway.remainingDelayS()
        }
    }

    Screen(testBuild, actions = {
            PrimaryButton(
                if (busy) "Verifying…" else "Unlock",
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            gateway.unlockWithPassword(password.toCharArray())
                            password = ""
                            onUnlocked()
                        } catch (e: VaultException) {
                            error = "Couldn't unlock. Check your password and try again."
                            delayS = gateway.remainingDelayS()
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = password.isNotEmpty() && delayS == 0L && !busy,
                modifier = Modifier.tag("unlock"),
            )
    }) {
        IconAction("back", "Back to lock", onBack, Modifier.tag("back"))
        Spacer(Modifier.height(16.dp))
        Text("Enter vault password", style = type.displayL)
        Text(
            "Your phone will then ask for its screen lock. Both are needed to open the vault this way.",
            style = type.body.copy(color = Obsidian.text2),
        )
        Spacer(Modifier.height(8.dp))
        VaultField(
            password, { password = it }, "Vault password", secret = true,
            enabled = delayS == 0L, fieldModifier = Modifier.tag("password"),
        )
        Text("Forgot it? You will need your Recovery Key.", style = type.faint)
        if (delayS > 0) {
            Text("Too many attempts. Try again in $delayS s", style = type.small.copy(color = Obsidian.warn))
        }
        error?.let { ErrorText(it) }
    }
}

// ---------- home ----------

@Composable
private fun Home(failures: Int, testBuild: Boolean, onDismiss: () -> Unit, onLock: () -> Unit) {
    val type = LocalVaultType.current
    Screen(testBuild) {
        Column(Modifier.tag("home"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    FieldLabel("Vault")
                    Text(greeting(), style = type.display)
                }
                IconAction("lock", "Lock", onLock, Modifier.tag("lock"), tint = Obsidian.accent)
            }
            if (failures > 0) {
                val noun = if (failures == 1) "attempt" else "attempts"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Notice(
                        "$failures failed password $noun since your last unlock",
                        modifier = Modifier.weight(1f),
                        textModifier = Modifier.tag("error"),
                    )
                }
                GhostButton("Dismiss", onDismiss)
            }
            Panel {
                Text("Your vault is empty", style = type.section)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Cards, PINs and bank accounts arrive in the next update.",
                    style = type.body.copy(color = Obsidian.text2),
                )
            }
        }
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
private fun ErrorText(text: String) {
    Text(text, style = LocalVaultType.current.small.copy(color = Obsidian.crit), modifier = Modifier.tag("error"))
}
