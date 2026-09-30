# Phase-0 spike: results

Status: **complete** (2026-09-30). ADR-001 is accepted on criteria 1–4. Criterion 5's prompt and invalidation tests are **moved to Gate G2** (see §6).

The spike code is throwaway and lives in [`spikes/phase0/`](../../spikes/phase0/). This page records what it measured and what that means for the specs. It feeds research items R3, R5 and R6 ([threat model §11](../threat-model.md#11-open-research-items)), crypto spec §4.6 and [ADR-001](../adr/ADR-001-stack.md).

## 1. How it was measured

- **Rust core:** `spike_core` 0.1.0 (UniFFI 0.32.2, `argon2` 0.6.0, `chacha20poly1305` 0.11.0). Release build with LTO, cross-compiled on Windows with cargo-ndk 4.1.2 and NDK 30.0.16248370.
- **Phones:** Firebase Test Lab, free plan, physical devices. The unattended instrumentation test `PhoneBenchmarkTest` ran once per phone, in one process: device details, AEAD check, Argon2id (5, 3 and 2 runs), then Keystore probes.
- **Keystore probes:** Test Lab phones have no screen lock, so the spec's `K_bio` and `K_pwd` cannot be created there. The test creates auth-free keys of the same algorithms only to read where Keystore puts them, and records how the phone refuses the real `K_pwd` definition.
- **Limits:** one run per phone, in a data-centre rack rather than a pocket, so thermal state and background load differ from real use. Treat the numbers as ±15%.

## 2. Devices

| Device | Chip | RAM | Android | Role |
|---|---|---|---|---|
| Samsung Galaxy A12 (SM-A125F) | MediaTek Helio P35 (MT6765) | 3.7 GB | 12 (API 31) | **Low end:** stand-in for the slowest supported phone |
| Samsung Galaxy S20 Ultra (SM-G988U) | Qualcomm Snapdragon 865 (SM8250) | 10.6 GB | 13 (API 33) | High end, has StrongBox |
| Development PC | AMD Ryzen 5 5600H | 16 GB | — | Reference |
| Emulator | PC CPU, x86_64 | 2.4 GB | 16 (API 36) | Build check only; its timings are not phone figures |

## 3. Results

### Argon2id (criterion 4, R5)

Times in milliseconds, in run order. The first run in a process is the one a real unlock pays.

| Parameters | Galaxy A12 | Galaxy S20 Ultra | PC |
|---|---|---|---|
| **64 MiB, t = 3, p = 1** (device slots) | **798** / 725 / 707 / 739 / 770 | 130 / 130 / 133 / 132 / 130 | 88 / 89 / 89 |
| 128 MiB, t = 4, p = 1 (backup) | 2273 / 2025 / 1988 | 353 / 350 / 353 | — |
| 256 MiB, t = 3, p = 1 | 3320 / 2936 | 534 / 535 | — |

- The provisional device-slot cost **meets the ≤ 1 s target on the A12: 798 ms on the first run.** The margin is about 200 ms, which leaves room for the Keystore HMAC and opening the database, but not much more.
- The backup cost is 2.3 s on the A12. That is acceptable for a rare, explicit action with a progress indicator.
- On the phones, the first run was about 5–10% slower than later ones. The emulator's much larger first-run penalty was an emulator effect.

### Keystore (criterion 5, unattended part; R3)

| | Galaxy A12 | Galaxy S20 Ultra |
|---|---|---|
| StrongBox feature | No | Yes |
| AES-256-GCM key | TEE, created in 21.5 ms | **StrongBox**, 68.3 ms (TEE: 6.7 ms) |
| HMAC-SHA256 key | TEE, 10.5 ms | **StrongBox**, 34.3 ms (TEE: 15.3 ms) |
| Real `K_pwd` definition, no screen lock | Refused: "Secure lock screen must be enabled…" | Refused, same message |

Neither phone fell back to software keys. Key creation happens once per vault, so StrongBox's slower creation does not matter. **Not yet measured:** the latency of each use (the unlock-time HMAC and AES operations) in StrongBox compared with TEE.

### Bridge cost (criteria 2 and 3, R6)

| | Galaxy A12 | Galaxy S20 Ultra | Emulator (release build) |
|---|---|---|---|
| AEAD round trip and tamper check | PASS | PASS | PASS |
| First call into Rust through JNA (loads JNA and the library) | **144 ms** | 22 ms | 20 ms (plain JNI: 3 ms) |

- Criterion 1 passes: both ABIs built on Windows and are 16 KB page-aligned.
- Criterion 2 passes for the **debug** build on real phones. The **R8 release** build has run only on the emulator so far.
- The size cost of JNA is about 0.3 MB per device: `libjnidispatch.so` is 177 KB on arm64, plus about 104 KB of dex.

## 4. Findings

| ID | Finding | Consequence |
|---|---|---|
| S1 | Keystore itself refuses `K_bio` when no biometric is enrolled, and `K_pwd` when there is no secure screen lock | Onboarding checks both first and explains. **Open product question:** can a vault start without KS-BIO (password and recovery slots only, biometric added later)? Recommended: yes, because many phones have only Class 2 face unlock |
| S2 | JNA costs about 20 ms at first call on fast hardware and about 0.3 MB | Keep UniFFI's JNA bindings unless the A12's JNI number shows most of its 144 ms is JNA |
| S3 | Judge Argon2id by the first run in a fresh process | The first run on the phones was 5–10% slower. Budget with the first run |
| S4 | The accepted import range allows m up to 512 MiB. The A12 needs 3.3 s for 256 MiB, and a 2 GB phone may be killed by the system at 512 MiB | New reviewer question Q8: also cap m by device RAM before any Argon2id work, and refuse with a clear message |
| S5 | Loading the core takes 144 ms on the A12 | Load it off the main thread while the lock screen draws, so no one waits for it |

## 5. Proposed decisions (for the G0 reviewer)

1. **Argon2id for device slots: m = 64 MiB, t = 3, p = 1 becomes final** (crypto spec §4.6, Q2). It meets the target on the A12-class low end. Lowering it would weaken every user's password protection to save a fraction of a second on the slowest phones. Phones slower than the A12 are accepted at roughly 1.1–1.3 s rather than lowering the floor.
2. **Backup: m = 128 MiB, t = 4, p = 1 becomes final.**
3. **Lowest supported device class:** Helio P35-class (2020 budget phone), Android 12 or newer, 3 GB RAM or more.
4. **TEE-only phones are supported;** StrongBox is used when present (R3). The spec already records which one was used in the slot parameters.

## 6. Moved, skipped and remaining

| Item | Decision |
|---|---|
| Criterion 5: `K_bio` and `K_pwd` with real prompts; the passcode must not release `K_bio`; enrolment invalidation | **Moved to G2** (product owner, 2026-09-30). These tests exercise Keystore, not the stack, and G2 already requires them on StrongBox and TEE devices against the real SecurityModule. Needs a borrowed or purchased Android phone with a fingerprint and screen lock |
| R4: lock-screen removal invalidates the keys | **Moved to G2.** Test only on a spare phone, never an owner's daily phone: removing the screen lock wipes every app's keys |
| R8 release build on a real phone | **Skipped.** The ARM-specific parts are identical in the debug build, which ran on both phones; R8 was exercised on the emulator |
| Plain-JNI first call on the A12; StrongBox and TEE latency per operation | **Skipped.** Neither can change a decision. `PhoneBenchmarkTest` can measure both if they are ever needed |
| GPU cost benchmark for the password-policy maths (R5) | **Remaining** for G0: desk research from published Argon2id cracking benchmarks |
