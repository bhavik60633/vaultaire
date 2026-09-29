# ADR-001: Native Android UI over a shared Rust core

- Status: **Proposed.** Confirmed or replaced by the Phase-0 spike (time-boxed to 2 weeks).
- Date: 2026-09-29

## Context

- Vaultaire is a security product built by one developer who is new to Kotlin and to Rust. **The biggest risk is an implementation mistake, not a weak algorithm.** The stack should make mistakes harder and give auditors one place to look.
- Android comes first. iOS is paused indefinitely: it needs a Mac and a paid Apple developer account, and the project has no budget. The file format must still stay portable so an iOS app can read the same backups later.
- The app needs direct access to platform security APIs: Android Keystore with StrongBox, `BiometricPrompt` with a `CryptoObject`, window flags such as `FLAG_SECURE`, accessibility-sensitive views and autofill exclusion.
- The crypto needs Argon2id and XChaCha20-Poly1305 from audited or widely reviewed code (see [`crypto-spec.md`](../crypto-spec.md) §2).

## Decision

1. **UI and platform layer: Kotlin with Jetpack Compose.** Custom design system built on Compose Foundation; Material 3 only where it saves accessibility work.
2. **All cryptography, the file format and the database live in one Rust crate, `vault-core`,** with `#![forbid(unsafe_code)]` and a pinned toolchain. It uses RustCrypto primitives (`chacha20poly1305`, `argon2`, `hkdf`, `hmac`, `sha2`, `subtle`, `zeroize`, `secrecy`, `getrandom`) and SQLite through `rusqlite` with the bundled SQLite source.
3. **The two talk through UniFFI,** pinned, with a deliberately small surface: about 20 functions, opaque handles, and no key material crossing except the few values listed in the crypto spec §5.1.
4. **Fallback, decided by the spike:** if the Rust, UniFFI and NDK toolchain cannot be made to work on Windows within the time box, write a Kotlin-only core against the **same specs**, using the JCA and a vetted Argon2id binding, and make the shared JSON test vectors mandatory.

### Why Rust for the core, even with iOS paused

- **One implementation to audit, fuzz and test,** and later a direct path to iOS without a second implementation of the format.
- **Memory control.** Android's runtime uses a moving garbage collector, which can leave copies of key bytes behind in memory. Rust keeps VK and its subkeys on a native heap where they can be zeroized reliably.
- **A mature fuzzing and supply-chain toolchain:** `cargo-fuzz`, `cargo deny` and `cargo vet`.

## Alternatives considered

| Option | Why not chosen |
|---|---|
| **Kotlin-only core** | The fallback. Simpler toolchain and one fewer language to learn. But key bytes live on the garbage-collected heap, the JCA lacks Argon2id and XChaCha20, and iOS would later need a second implementation. |
| Kotlin Multiplatform | Runner-up for a cross-platform app. Weak crypto ecosystem (no first-party Argon2id or XChaCha20). Its iOS text input and accessibility are an unverified security surface. |
| React Native | JavaScript runtime and bridge; a large npm supply chain; immutable, uncontrollable secret strings; secure-window and autofill work needs native code anyway. |
| Flutter | Dart runtime; plugins join the trust base; common biometric plugins are boolean gates, which the design forbids. |
| Capacitor, Ionic | Built on a WebView, which the design bans. |
| Google Tink | Excellent on Android, but no Argon2id and weak iOS support. |
| SQLCipher instead of per-record encryption | Once open, all plaintext is readable through SQL and the page cache. Records are already encrypted individually with minimal metadata. |

## Consequences

- **Two languages to learn at once,** plus the Android NDK. This is the main cost, and the reason for the time-boxed spike and the fallback.
- **UniFFI's Kotlin bindings pull in JNA**, which adds size and startup cost. The spike compares it with a small hand-written JNI layer.
- The app must be built for several CPU architectures (at least `arm64-v8a` and `x86_64` for the emulator).
- One core means one audit target. It also means a bug in the core affects every platform.

## Spike exit criteria

The spike passes when, on the developer's Windows machine:

1. `cargo-ndk` builds `vault-core` for `arm64-v8a` and `x86_64`.
2. A Compose app calls a UniFFI function that runs Argon2id and XChaCha20-Poly1305 round trips, in both debug and **R8-minified release** builds.
3. The extra APK size and cold-start time are measured and recorded, for both JNA and a minimal JNI layer.
4. Argon2id at the provisional parameters (64 MiB, t = 3) is timed on the developer's phone; the result feeds the crypto spec §4.6.
5. `K_bio` and `K_pwd` are created on the developer's phone, the security level (TEE or StrongBox) is recorded, and biometric-enrolment invalidation and lock-screen removal behave as the crypto spec §4 expects.

If criteria 1 or 2 cannot be met within two weeks, this ADR is superseded by one choosing the Kotlin-only fallback.

## Revisit when

- The spike finishes (confirm or replace).
- The iOS phase starts (confirm that the Swift bindings meet the same surface).
- A RustCrypto crate used here loses maintenance or gets a serious advisory (switch to libsodium; crypto spec §2).
