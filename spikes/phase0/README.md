# Phase-0 spike

> **Throwaway code.** This folder exists to answer questions before Gate G0, then it gets deleted. Nothing here is product code, and nothing may be copied into `vault-core` or the Android app. The real code is written later, against the reviewed specs.

It tests the exit criteria in [ADR-001](../../docs/adr/ADR-001-stack.md#spike-exit-criteria) and feeds research items R3, R5 and R6 in the [threat model](../../docs/threat-model.md#11-open-research-items).

## Contents

| Path | What |
|---|---|
| `core/` | A tiny Rust library, exported through UniFFI: `coreVersion()`, `aeadRoundtrip()` (XChaCha20-Poly1305 round trip plus a tamper check) and `argon2idTiming(m, t, p)` |
| `core/examples/pc_timing.rs` | Times Argon2id on the development PC, for comparison |
| `android/` | *(next)* A minimal Compose app that calls the library and tests the Keystore keys |

## Toolchain (2026-09-29)

All free, installed from official sources with verified hashes (winget) or from crates.io with `--locked`.

| Tool | Version |
|---|---|
| Rust (rustup, stable, MSVC host) | 1.98.1; targets `aarch64-linux-android`, `x86_64-linux-android` |
| cargo-ndk | 4.1.2 |
| UniFFI | 0.32.2 |
| `chacha20poly1305` / `argon2` / `getrandom` | 0.11.0 / 0.6.0 / 0.4.3 |
| Android Studio | 2026.1.4.7, bundled OpenJDK 25.0.3 |
| Visual Studio Build Tools | 2026 (already installed) |

## Exit criteria and results

| # | Criterion (ADR-001) | Result |
|---|---|---|
| 1 | `cargo-ndk` builds the core for `arm64-v8a` and `x86_64` on Windows | Pending: needs the Android NDK |
| 2 | A Compose app calls the library in debug and R8-minified release builds | Pending |
| 3 | APK size and cold-start cost measured, JNA versus a minimal JNI layer | Pending |
| 4 | Argon2id timed on the phone at the provisional settings | Pending. **PC reference: 88 ms** (64 MiB, t = 3, p = 1; Ryzen 5 5600H, release build, 3 runs: 88 / 89 / 89 ms) |
| 5 | `K_bio` and `K_pwd` created on the phone; security level recorded; enrolment invalidation and lock-screen removal behave as specified | Pending |

Already confirmed on the PC:

- The core compiles and its tests pass (AEAD round trip rejects a tampered ciphertext; Argon2id runs).
- UniFFI generates the Kotlin bindings from the compiled library (`coreVersion`, `aeadRoundtrip`, `argon2idTiming`).

## Running it

```powershell
cd spikes/phase0/core
cargo test
cargo run --release --example pc_timing
cargo run --release --bin uniffi-bindgen -- generate --library target/release/spike_core.dll --language kotlin --out-dir target/kotlin-bindings
```
