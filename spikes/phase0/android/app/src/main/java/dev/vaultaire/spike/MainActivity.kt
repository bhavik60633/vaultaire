// Phase-0 spike: THROWAWAY CODE. Not product code; see spikes/phase0/README.md.
package dev.vaultaire.spike

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.spike_core.aeadRoundtrip
import uniffi.spike_core.argon2idTiming
import uniffi.spike_core.coreVersion

class MainActivity : ComponentActivity() {

    private val lines = mutableStateListOf<String>()

    private fun log(line: String) {
        lines += line
        Log.i(TAG, line)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Criterion 3: the first call loads the library (and JNA, on that path), so it carries the
        // start-up cost. `adb shell am start -e bridge jni ...` measures plain JNI instead.
        val start = SystemClock.elapsedRealtimeNanos()
        if (intent.getStringExtra("bridge") == "jni") {
            val r = Jni.noop(41)
            log("JNI first call (loads the library): ${ms(start)} ms, result $r")
        } else {
            val version = coreVersion()
            log("$version; JNA first call (loads JNA and the library): ${ms(start)} ms")
        }

        val probe = KeystoreProbe(this, ::log)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    SpikeScreen(probe)
                }
            }
        }
    }

    @Composable
    private fun SpikeScreen(probe: KeystoreProbe) {
        val scope = rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }

        fun argon2(memoryKib: Int, passes: Int, runs: Int) {
            busy = true
            scope.launch {
                log("Argon2id m=${memoryKib / 1024} MiB, t=$passes, p=1, $runs runs")
                repeat(runs) {
                    val t = withContext(Dispatchers.Default) {
                        runCatching { argon2idTiming(memoryKib.toUInt(), passes.toUInt(), 1u) }
                    }
                    log(t.fold({ "  ${it.millis} ms" }, { "  failed: ${it.message}" }))
                }
                busy = false
            }
        }

        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Vaultaire Phase-0 spike", style = MaterialTheme.typography.titleLarge)
            Text("Throwaway test app. Holds no real secrets.", style = MaterialTheme.typography.bodySmall)

            ButtonRow("Environment" to { probe.environment() }, "AEAD check" to {
                log("AEAD round trip and tamper check: ${runCatching { aeadRoundtrip() }.fold({ if (it) "PASS" else "FAIL" }, { "error ${it.message}" })}")
            })
            ButtonRow(
                "Argon2id 64 MiB ×3" to { if (!busy) argon2(65_536, 3, 3) },
                "Backup 128 MiB ×2" to { if (!busy) argon2(131_072, 4, 2) },
            )
            ButtonRow("1. Create keys" to { probe.createKeys() }, "2. Wrap (finger)" to { probe.wrapWithBiometric() })
            ButtonRow("3. Unwrap (finger)" to { probe.unwrapWithBiometric() }, "4. Unwrap (passcode)" to { probe.unwrapWithPasscode() })
            ButtonRow("5. HMAC K_pwd" to { probe.hmacWithPasswordKey() }, "Check keys" to { probe.checkKeys() })
            ButtonRow("Delete test keys" to { probe.deleteKeys() }, "Clear log" to { lines.clear() })

            if (busy) Text("Running Argon2id…")
            for (line in lines) {
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }

    @Composable
    private fun ButtonRow(vararg buttons: Pair<String, () -> Unit>) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, action) in buttons) {
                val safeAction = {
                    try {
                        action()
                    } catch (e: Exception) {
                        log("$label crashed: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
                OutlinedButton(onClick = safeAction, modifier = Modifier.weight(1f)) { Text(label, fontSize = 12.sp) }
            }
        }
    }

    private fun ms(startNanos: Long) = (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000.0

    private companion object {
        const val TAG = "VaultaireSpike"
    }
}
