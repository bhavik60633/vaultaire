package dev.vaultaire.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.vaultaire.core.VaultException
import dev.vaultaire.security.Refusal
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

/** The whole app: onboarding, the lock screen, and (for now) an empty home. */
@Composable
fun VaultaireApp(gateway: VaultGateway, testBuild: Boolean) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            val unlocked by gateway.unlocked.collectAsState()
            var step by remember {
                mutableStateOf<Step>(if (gateway.vaultExists()) Step.Home else Step.Welcome)
            }
            var failures by remember { mutableStateOf(0) }
            val locked = !unlocked && gateway.vaultExists()

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Banners(testBuild)
                when {
                    locked -> LockScreen(gateway) {
                        failures = gateway.failuresSinceLastUnlock()
                        step = Step.Home
                    }
                    else -> when (val current = step) {
                        Step.Welcome -> Welcome(gateway) { step = Step.CreatePassword }
                        Step.CreatePassword -> CreatePassword(gateway) { step = Step.ShowRecoveryKey(it) }
                        is Step.ShowRecoveryKey -> ShowRecoveryKey(current.key) { step = Step.Fingerprint }
                        Step.Fingerprint -> FingerprintStep(gateway) { step = Step.Home }
                        Step.Home -> Home(failures) { gateway.lock() }
                    }
                }
            }
        }
    }
}

@Composable
private fun Banners(testBuild: Boolean) {
    Warning(BETA_WARNING)
    if (testBuild) Warning(TEST_BUILD_WARNING)
}

@Composable
private fun Warning(text: String) {
    Surface(color = Color(0xFFFFE8B0), shape = MaterialTheme.shapes.small) {
        Text(
            text,
            color = Color(0xFF4A3300),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
        )
    }
}

@Composable
private fun Welcome(gateway: VaultGateway, onCreate: () -> Unit) {
    val refusal = remember { gateway.refusal() }
    Text("Vaultaire", style = MaterialTheme.typography.headlineMedium)
    Text("Your cards, PINs and bank details, encrypted on this phone. Nothing is sent anywhere.")
    if (refusal != null) {
        Text(
            when (refusal) {
                Refusal.NO_SCREEN_LOCK ->
                    "Set a screen lock (PIN, pattern or password) on this phone first. " +
                        "Without one, anyone holding the phone could use the vault's keys."
                Refusal.SOFTWARE_KEYS ->
                    "This phone keeps keys in software, not in hardware, so a vault can't be " +
                        "created on it."
            },
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("refusal"),
        )
    } else {
        Button(onClick = onCreate, modifier = Modifier.testTag("create")) { Text("Create a vault") }
    }
}

@Composable
private fun CreatePassword(gateway: VaultGateway, onCreated: (CharArray) -> Unit) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Text("Choose a vault password", style = MaterialTheme.typography.titleLarge)
    Text("At least 12 characters. A few unrelated words make a strong, memorable password.")
    PasswordField(password, "Vault password", "password") { password = it }
    PasswordField(confirm, "Type it again", "confirm") { confirm = it }
    error?.let { ErrorText(it) }
    Button(
        onClick = {
            if (password != confirm) {
                error = "The passwords don't match."
                return@Button
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
        modifier = Modifier.testTag("continue"),
    ) { Text("Continue") }
}

@Composable
private fun ShowRecoveryKey(key: CharArray, onDone: () -> Unit) {
    var writtenDown by remember { mutableStateOf(false) }
    Text("Your Recovery Key", style = MaterialTheme.typography.titleLarge)
    Text(
        "Write it on paper and keep it somewhere safe. With your password, it restores your " +
            "backup on a new phone. It is shown only this once, and nobody can recover it for you.",
    )
    Text(
        String(key),
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.testTag("recoveryKey"),
    )
    Row(
        Modifier
            .testTag("writtenDown")
            .toggleable(value = writtenDown, role = Role.Checkbox) { writtenDown = it },
    ) {
        Checkbox(checked = writtenDown, onCheckedChange = null)
        Text("I have written down my Recovery Key", Modifier.padding(start = 8.dp))
    }
    Button(
        onClick = {
            key.fill('\u0000')
            onDone()
        },
        enabled = writtenDown,
        modifier = Modifier.testTag("continue"),
    ) { Text("Continue") }
}

@Composable
private fun FingerprintStep(gateway: VaultGateway, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    Text("Unlock with your fingerprint?", style = MaterialTheme.typography.titleLarge)
    Text(
        "Anyone whose fingerprint is on this phone can open the vault. Remove other people's " +
            "fingerprints first. You can always unlock with your password instead.",
    )
    Button(
        onClick = {
            scope.launch {
                gateway.enrolFingerprint()
                gateway.onboardingFinished()
                onDone()
            }
        },
        modifier = Modifier.testTag("useFingerprint"),
    ) { Text("Use fingerprint") }
    OutlinedButton(
        onClick = {
            gateway.onboardingFinished()
            onDone()
        },
        modifier = Modifier.testTag("notNow"),
    ) { Text("Not now") }
}

@Composable
private fun LockScreen(gateway: VaultGateway, onUnlocked: () -> Unit) {
    val scope = rememberCoroutineScope()
    var usePassword by remember { mutableStateOf(false) }
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

    Text("Vaultaire is locked", style = MaterialTheme.typography.titleLarge)
    Button(
        onClick = {
            scope.launch { if (gateway.unlockWithFingerprint()) onUnlocked() }
        },
        modifier = Modifier.testTag("unlockFingerprint"),
    ) { Text("Unlock with fingerprint") }
    OutlinedButton(
        onClick = { usePassword = true },
        modifier = Modifier.testTag("usePassword"),
    ) { Text("Use password") }

    if (usePassword) {
        PasswordField(password, "Vault password", "password") { password = it }
        if (delayS > 0) Text("Too many attempts. Try again in $delayS s")
        error?.let { ErrorText(it) }
        Button(
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
            modifier = Modifier.testTag("unlock"),
        ) { Text("Unlock") }
    }
}

@Composable
private fun Home(failures: Int, onLock: () -> Unit) {
    Column(Modifier.testTag("home"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (failures > 0) {
            val noun = if (failures == 1) "attempt" else "attempts"
            ErrorText("$failures failed password $noun since your last unlock")
        }
        Text("Your vault", style = MaterialTheme.typography.titleLarge)
        Text("It's empty for now. Cards, PINs and bank accounts arrive in the next update.")
        OutlinedButton(onClick = onLock, modifier = Modifier.testTag("lock")) { Text("Lock") }
    }
}

@Composable
private fun PasswordField(value: String, label: String, tag: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
    )
}

@Composable
private fun ErrorText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("error"))
}
