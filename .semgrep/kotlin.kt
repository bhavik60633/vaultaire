// Test cases for kotlin.yml. `semgrep --test .semgrep/` checks that every `ruleid:` line is flagged
// and every `ok:` line is not. Not compiled; never part of the app.
package test

fun biometric(prompt: BiometricPrompt, info: PromptInfo, crypto: BiometricPrompt.CryptoObject) {
    // ruleid: biometric-prompt-without-cryptoobject
    prompt.authenticate(info)
    // ok: biometric-prompt-without-cryptoobject
    prompt.authenticate(info, crypto)
    // ruleid: biometric-prompt-without-cryptoobject
    platformPrompt.authenticate(signal, executor, callback)
    // ok: biometric-prompt-without-cryptoobject
    platformPrompt.authenticate(cryptoObject, signal, executor, callback)
}

fun intents(uri: Uri) {
    // ruleid: no-external-view-intents
    val i = Intent(Intent.ACTION_VIEW, uri)
    // ruleid: no-external-view-intents
    val j = Intent("android.intent.action.VIEW")
    // ok: no-external-view-intents
    val k = Intent(Intent.ACTION_CREATE_DOCUMENT)
}

fun web(context: Context) {
    // ruleid: no-webview
    val w = WebView(context)
}

fun logging(e: Exception) {
    // ruleid: no-logging
    Log.d("Vault", "unlocked")
    // ruleid: no-logging
    println("secret")
    // ruleid: no-logging
    e.printStackTrace()
}

@Composable
fun screen() {
    // ruleid: no-secrets-in-saved-state
    val password = rememberSaveable { mutableStateOf("") }
    // ruleid: no-secrets-in-saved-state
    val recoveryKeyInput = rememberSaveable { mutableStateOf("") }
    // ok: no-secrets-in-saved-state
    val scrollPosition = rememberSaveable { mutableStateOf(0) }
    // ok: no-secrets-in-saved-state
    val password2 = remember { mutableStateOf("") }
}

fun prefs(editor: SharedPreferences.Editor, bundle: Bundle) {
    // ok: preference-key-not-allowlisted
    editor.putBoolean("vault_present", true)
    // ok: preference-key-not-allowlisted
    editor.putInt("failed_attempts", 3)
    // ruleid: preference-key-not-allowlisted
    editor.putString("last_search", "bank")
    // ruleid: preference-key-not-allowlisted
    bundle.putString("card_number", "4929")
}

fun openPrefs(context: Context) {
    // ruleid: shared-preferences-outside-prefs-module
    val p = context.getSharedPreferences("x", 0)
}

fun randomness() {
    // ruleid: no-insecure-random
    val a = java.util.Random()
    // ruleid: no-insecure-random
    val b = Random.nextInt()
    // ruleid: no-insecure-random
    val c = Math.random()
    // ok: no-insecure-random
    val d = java.security.SecureRandom()
}

fun clipboard(cm: ClipboardManager, clip: ClipData) {
    // ruleid: clipboard-outside-guard
    cm.setPrimaryClip(clip)
}
