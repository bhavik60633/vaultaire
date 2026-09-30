# Phase-0 spike

> **Throwaway code.** This folder exists to answer questions before Gate G0, then it gets deleted. Nothing here is product code, and nothing may be copied into `vault-core` or the Android app. The real code is written later, against the reviewed specs.

It tests the exit criteria in [ADR-001](../../docs/adr/ADR-001-stack.md#spike-exit-criteria) and feeds research items R3, R5 and R6 in the [threat model](../../docs/threat-model.md#11-open-research-items).

## Contents

| Path | What |
|---|---|
| `core/` | A tiny Rust library, exported through UniFFI: `coreVersion()`, `aeadRoundtrip()` (XChaCha20-Poly1305 round trip plus a tamper check) and `argon2idTiming(m, t, p)`. Also one hand-written JNI export, `Jni.noop`, for the JNA-versus-JNI comparison |
| `core/examples/pc_timing.rs` | Times Argon2id on the development PC, for comparison |
| `android/app/src/androidTest/` | `PhoneBenchmarkTest`: an unattended run for Firebase Test Lab. It records the device, the AEAD check, Argon2id timings (64, 128 and 256 MiB), where Keystore puts keys (StrongBox, TEE or software) and how the phone treats the real `K_pwd` definition. Every result is a logcat line tagged `VaultaireSpike` that starts with `RESULT` |
| `android/` | A minimal Compose app (minSdk 31, no INTERNET permission) that calls the library, times Argon2id, and creates and exercises `K_bio` and `K_pwd` exactly as crypto-spec §4.2–4.3 define them |

## Toolchain (2026-09-29)

All free, installed from official sources with verified hashes (winget, the Gradle checksum) or from crates.io with `--locked`.

| Tool | Version |
|---|---|
| Rust (rustup, stable, MSVC host) | 1.98.1; targets `aarch64-linux-android`, `x86_64-linux-android` |
| cargo-ndk | 4.1.2 |
| UniFFI | 0.32.2 |
| `chacha20poly1305` / `argon2` / `getrandom` | 0.11.0 / 0.6.0 / 0.4.3 |
| Android Studio | 2026.1.4.7, bundled OpenJDK 25.0.3 |
| Android SDK | platform 37, build-tools 36.0.0, NDK 30.0.16248370 |
| Gradle (wrapper) | 9.8.0, distribution pinned by SHA-256 in `gradle-wrapper.properties` |
| Android Gradle Plugin / Kotlin / Compose BOM | 9.4.1 / 2.4.20 / 2026.09.00 |
| JNA | 5.19.1 |
| Emulator image | `android-36/aosp_atd/x86_64` (AOSP, no Google services), WHPX acceleration |
| Visual Studio Build Tools | 2026 (already installed) |

## Exit criteria and results

**Results and findings are written up in [docs/spikes/phase0-results.md](../../docs/spikes/phase0-results.md).** Summary (2026-09-30):

| # | Criterion (ADR-001) | Status |
|---|---|---|
| 1 | `cargo-ndk` builds the core for `arm64-v8a` and `x86_64` on Windows | **Pass**, 16 KB page-aligned |
| 2 | A Compose app calls the library in debug and R8-minified release builds | **Pass** for debug on real phones (Galaxy A12, S20 Ultra) and release on the emulator; release on a real phone pending |
| 3 | APK size and cold-start cost, JNA versus JNI | Measured: about 0.3 MB; first JNA call 144 ms on the A12, 22 ms on the S20 Ultra. The A12's JNI figure is pending |
| 4 | Argon2id timed on a phone at the provisional settings | **798 ms** on the A12 (first run), 130 ms on the S20 Ultra, 88 ms on the PC |
| 5 | `K_bio` and `K_pwd` on a phone: security level, prompts, invalidation | Security level measured (A12: TEE; S20 Ultra: StrongBox). Prompts and invalidation pending (needs a borrowed phone) |

## Findings for the specs (emulator stage; the full list is in docs/spikes)

- **S1. Keystore enforces the §5.2 preconditions itself.** With no biometric enrolled, creating `K_bio` fails with `InvalidAlgorithmParameterException` ("At least one biometric must be enrolled to create keys requiring user authentication for every use"). With no secure lock screen, `K_pwd` fails ("Secure lock screen must be enabled"). The product must check `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)` and `KeyguardManager.isDeviceSecure()` *before* offering vault creation, and show the reason. **Open question for crypto-spec §4.2:** can a vault be created with no fingerprint enrolled (password and recovery slots only, KS-BIO added later), or is a Class 3 biometric required? Today the spec assumes all three slots exist.
- **S3. Time the first Argon2id run, not the average.** In a fresh process the first 64 MiB run took 1176 ms on the emulator, then about 300 ms. The memory has to be handed over by the OS the first time. A real unlock is always a first run in a fresh process, so the ≤ 1 s target in crypto-spec §4.6 must be checked against the first run.
- **S2. JNA is affordable.** On the emulator it costs about 18 ms once, at start-up, and about 0.3 MB per device. Unless the phone numbers are much worse, UniFFI's default JNA bindings stay, and a hand-written JNI layer is not worth its extra unsafe code.

## Running it

Rust core, on the PC:

```powershell
cd spikes/phase0/core
cargo test
cargo run --release --example pc_timing
cargo run --release --bin uniffi-bindgen -- generate --library target/release/spike_core.dll --language kotlin --out-dir target/kotlin-bindings
```

Android libraries and app (PowerShell; set `ANDROID_NDK_HOME` to the installed NDK and `JAVA_HOME` to Android Studio's `jbr`):

```powershell
cd spikes/phase0/core
cargo ndk -t arm64-v8a -t x86_64 -P 26 -o ..\android\app\src\main\jniLibs build --release
cd ..\android
.\gradlew.bat assembleDebug assembleRelease
```

If the Rust API changes, regenerate the bindings and copy `spike_core.kt` into `android/app/src/main/java/uniffi/spike_core/`.

Cold start, JNA versus JNI (`adb` from `platform-tools`):

```sh
adb shell am start -W -e bridge jna -n dev.vaultaire.spike/.MainActivity   # or -e bridge jni
adb logcat -s VaultaireSpike:I
```

Firebase Test Lab (instrumentation test): build with `.\gradlew.bat assembleDebug assembleDebugAndroidTest`, then upload `app/build/outputs/apk/debug/app-debug.apk` as the app and `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` as the test. Read the results by filtering the logcat for `RESULT`. The same test runs locally with `adb shell am instrument -w dev.vaultaire.spike.test/androidx.test.runner.AndroidJUnitRunner`.

The release build is signed with the local debug key so it installs. That is only acceptable for this spike.
