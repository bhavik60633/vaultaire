# Vaultaire threat model

| | |
|---|---|
| Status | **Draft 0.1, not yet reviewed.** Input to Gate G0 (spec freeze). |
| Scope | Vaultaire V1 for Android (minSdk 31). iOS differences are listed in [§10](#10-ios-delta-deferred) and are out of scope until the iOS phase. |
| Companion specs | `crypto-spec.md` (key hierarchy, primitives, test vectors) and `vault-format.md` (on-disk and backup formats). Both are still to be written. |
| Last updated | 2026-09-29 |

Vaultaire is unaudited. Until an independent security review is published, nobody should store real credentials in it.

---

## 1. What Vaultaire is

Vaultaire is a local-only vault for high-value, structured secrets: payment cards, bank accounts, identity documents, developer keys, recovery codes and the logins that would hurt most to lose.

- The vault lives **only on the device**. There is no account, no server and no sync. The Android app ships **without the `INTERNET` permission**.
- The vault leaves the device only as an **encrypted backup file** that the user exports and carries themselves.
- All cryptography and file-format handling lives in one Rust library (`vault-core`). The Android app (Kotlin, Jetpack Compose) calls it through a narrow FFI.

This document states what Vaultaire protects, from whom, how, and **what it cannot protect against**. The residual risks in [§8](#8-residual-risks-published) are meant to be published to users in plain language.

## 2. Assets

| ID | Asset | Why it matters |
|---|---|---|
| AS-1 | **Vault key (VK)** and the subkeys derived from it | Decrypts everything. Its compromise is total compromise. |
| AS-2 | **Critical field values**: card numbers, card security codes (CVV) where the user opted in, bank account numbers and IBANs, identity-document numbers, API secrets, recovery codes | Directly usable for fraud or account takeover |
| AS-3 | **Concealed field values**: passwords, membership PINs, note bodies | Account takeover; personal information |
| AS-4 | **Index data**: item titles, types, usernames, tags, last-4 digits, card finishes | Reveals which banks, services and documents the user has. Useful for targeting and phishing. |
| AS-5 | **Vault password** | One of two secrets for recovery and backups; a factor in password unlock |
| AS-6 | **Recovery Secret (RS)**, shown to the user as the Recovery Key | The second secret for recovery and backups |
| AS-7 | **Vault integrity** | An undetected change could plant a wrong account number or a phishing URL |
| AS-8 | **Owner's access**: the vault is neither destroyed nor locked beyond recovery | Local-only means that a lost vault is gone for good |
| AS-9 | **Update channel**: release signing keys and store accounts | A malicious update can read the vault at the next unlock |

## 3. Security objectives

- **O1 Confidentiality.** AS-1 to AS-6 stay secret from every in-scope adversary while the vault is locked. While it is unlocked, exposure is limited to what the user reveals, and only for a bounded time.
- **O2 Integrity.** Any change to vault data or to a backup file is detected. Detection fails closed: nothing is shown and nothing is changed.
- **O3 Owner availability.** The owner can always recover with two secrets (vault password and Recovery Key). No single secret, and no third party, can do the same.
- **O4 No egress.** Nothing leaves the device except an encrypted backup the user chooses to export.
- **O5 Honesty.** Every residual risk is documented and published. There are no "military-grade" or "100% secure" claims.

### 3.1 Invariants

Each invariant must be testable. [§7](#7-threat-register) links threats to the tests that verify them.

- **I1** No vault plaintext or key material is ever persisted: not in files, preferences, plaintext database columns, logs, saved instance state, OS search indexes or notifications.
- **I2** VK exists only in `vault-core` memory while the vault is unlocked, and is zeroized on lock.
- **I3** Every path to VK needs one of:
  - (a) a hardware key release gated by the owner's strong biometric;
  - (b) the vault password **plus** a hardware factor gated by the device credential;
  - (c) the vault password **plus** the 128-bit Recovery Secret.
- **I4** Any cryptographic verification failure is fatal to that operation. There is no partial rendering and no best-effort decoding.
- **I5** No network access. The `INTERNET` permission is absent, and CI asserts this on the merged manifest.
- **I6** Security-relevant settings live inside the authenticated vault. Weakening any of them requires the vault password.
- **I7** A biometric result is never a boolean. Success means the hardware released a key.
- **I8** Untrusted input is authenticated (AEAD) before any parsing beyond a small, bounded, fuzzed header.
- **I9** Nothing sensitive reaches recents thumbnails, assistant or AI screen context, other autofill services, OS search, widgets or notifications.
- **I10** Every display or copy of a secret is started by the user and ends after a bounded time.
- **I11** A card security code (CVV) is stored only after an explicit, per-card opt-in. It is a critical field and is never autofilled. Card PINs are never stored. See [§9](#9-decision-record-card-security-codes-cvv).

## 4. System and trust boundaries

```
┌──────────────────────────── App process (single Activity) ─────────────────────────────┐
│ UI (Compose)        renders only values the session hands it; masked by default;       │
│                     no secrets in saved state                                           │
│ SessionManager      lock state machine, timers, lifecycle and screen-off hooks          │
│ SecurityModule      Keystore keys, BiometricPrompt + CryptoObject, device checks        │
│ PrivacyGuard        FLAG_SECURE, overlay hiding, accessibility-sensitive views,         │
│                     autofill exclusion                                                  │
│ ClipboardGuard      sensitive clips, time-limited clearing                              │
│ ═══════════════ boundary B1: FFI (opaque handles; secret buffers zeroed) ════════════ │
│ vault-core (Rust, no unsafe code)                                                       │
│   key hierarchy · AEAD envelopes · Argon2id · HKDF · strict CBOR · SQLite ·             │
│   export/import · refuses every operation while Locked                                  │
└───────────┬───────────────────────────────────────────────┬────────────────────────────┘
            │ B2: Keystore IPC                              │ B3: file I/O
   ┌────────▼────────┐                             ┌────────▼───────────────────────────┐
   │ StrongBox / TEE │                             │ vault.db (ciphertext), in           │
   │ K_bio, K_pwd    │                             │ credential-encrypted noBackupFiles │
   └─────────────────┘                             └────────────────────────────────────┘

B4: untrusted inputs — backup files being imported · intents · (V1.1) autofill view structures
B5: user-chosen storage for exported backups (cloud drives, USB, messaging) — assumed hostile
B6: build and release pipeline — developer machine, CI, dependency registries, Play Console
```

The key hierarchy is summarised here and specified in full in `crypto-spec.md`:

- **VK** is 256 random bits from the OS CSPRNG. It is never derived from a password and never stored in plaintext.
- VK is stored as three AEAD-wrapped copies, one per **keyslot**:

| Keyslot | Opens with | Bound to this device? |
|---|---|---|
| **KS-BIO** (daily unlock) | Slot key released by a Keystore key that requires a Class 3 biometric for each use and is invalidated when a new biometric is enrolled | Yes |
| **KS-PWD** (fallback) | Argon2id(vault password), combined with an HMAC from a Keystore key gated by the device credential. The HMAC runs in hardware **once per guess**. | Yes |
| **KS-REC** (recovery; also protects backups) | Argon2id(vault password), combined with the 128-bit Recovery Secret | No (portable) |

The following are **never** an unlock path on their own: the device passcode, a biometric "success" boolean, the Recovery Key, security questions, email or SMS, or support staff.

## 5. Assumptions

If one of these is false, the stated protections may not hold.

| ID | Assumption | If it fails |
|---|---|---|
| A1 | The Android kernel, app sandbox and Keystore behave as documented. Keys marked non-exportable cannot be extracted. | A Keystore or TEE break turns device-bound slots into password-only slots |
| A2 | StrongBox or the TEE enforces per-use biometric authentication and credential gating. The emulator's software Keystore is used for CI only and never for assurance. | Biometric or credential gating becomes bypassable |
| A3 | The audited primitives (XChaCha20-Poly1305, Argon2id, HKDF-SHA256, HMAC-SHA256) are secure as used | Cryptographic failure; to be reviewed at G0 and G1 |
| A4 | The device is not compromised when the vault is created | A backdoored device can capture VK or the Recovery Key at creation |
| A5 | The user keeps the Recovery Key away from the device and from their cloud photos | Backup security falls to password strength alone (T25, T29) |
| A6 | The user runs a genuine build from our signing key | A fake app can phish the secrets (T28) |
| A7 | Argon2id parameters, calibrated on the lowest supported device, make offline guessing expensive | Weaker offline resistance; open item R5 |

## 6. Adversaries

| ID | Adversary | Capabilities | Stance |
|---|---|---|---|
| ADV-1 | **Thief, locked device** | Physical possession; no passcode | Fully defended |
| ADV-2 | **Thief who knows the passcode** (shoulder-surfed) | Possession + device passcode; can enrol their own fingerprint | Fully defended: the passcode alone never opens the vault |
| ADV-3 | **Opportunist, unlocked app** | A few minutes with the phone while the vault is open | Limited: auto-lock, critical-field gates, password needed for export and changes |
| ADV-4 | **Household member** | May already have a fingerprint enrolled on the device; may know the passcode | Warned: anyone enrolled can open KS-BIO. Password-only mode is offered. |
| ADV-5 | **On-device malware, no root** | Overlays, accessibility services, a keyboard (IME), clipboard reading, a third-party autofill service | Partially defended; residual risks documented |
| ADV-6 | **Privileged attacker** | Root, an OS exploit or forensic extraction; can hook the app | Defended **while locked** only. While unlocked: out of scope. |
| ADV-7 | **File thief** | Copies of `vault.db`, OS backups or exported backup files, for example from the user's cloud drive | Defended: ciphertext only, two secrets needed |
| ADV-8 | **Crafter of malicious files** | Hands the user a crafted backup file to import | Defended: authenticate before parsing, strict limits, fuzzing |
| ADV-9 | **Social engineer** | Fake support, fake app, phishing for the password or Recovery Key | Partially defended: no support-assisted recovery exists to abuse |
| ADV-10 | **Supply-chain attacker** | Compromises a dependency, the CI pipeline, the developer's machine or the store account | Partially defended; **the top residual risk** of any app |

The developer is treated as ADV-10. Vaultaire holds no user keys or data, so the developer cannot decrypt a vault. A developer can, however, ship a malicious update.

### 6.1 Out of scope

- **Code execution inside Vaultaire's process while the vault is unlocked.** Whatever the app can read, such an attacker can read. No consumer app can defend against this.
- **Coercion of the user.** Duress PINs and decoy vaults are deliberately not built. They tend to fail in practice and add complexity.
- **Attacks on StrongBox, the TEE or the Secure Enclave hardware itself.**
- **A malicious update signed with our key.** This is handled by process (T21), not by the app.

## 7. Threat register

Risk is likelihood × impact: **L**ow, **M**edium or **H**igh. "Verified by" names the test and the gate at which it must pass (see `docs/gates/` once written).

### 7.1 Physical access

| ID | Threat | Adv. | Risk | Mitigations | Residual risk | Verified by |
|---|---|---|---|---|---|---|
| T01 | Lost or stolen phone, locked | ADV-1 | H | OS lock and file-based encryption. Vault records AEAD-encrypted under VK. Device keyslots bound to hardware. Metadata minimised and padded (I1). | See T03 and T05 for stronger attackers | Canary sweep of extracted files (G1, G4) |
| T02 | Someone uses the unlocked app | ADV-3 | M–H | Auto-lock after 2 min of inactivity, 30 s in the background, and at once on screen-off. Critical fields need a biometric within the last 60 s. Export and security changes need the vault password (I6). Always-visible lock button. Revealed values re-mask after 30 s (I10). | Can read non-critical values during the session | Instrumented tests (G2, G3) |
| T03 | Thief who knows the device passcode | ADV-2 | M | **The passcode alone never unlocks the vault** (I3). KS-BIO needs the owner's biometric, and enrolling a new one invalidates it. KS-PWD needs the passcode **and** the vault password. App-enforced delays between password attempts. | With an OS exploit as well, the vault password's strength decides | Invalidation scenarios on StrongBox and TEE devices (G2) |
| T13 | Shoulder surfing | ADV-3 | M | Masked by default. One field revealed at a time. Automatic re-mask. Biometric-first unlock means little typing. | Values are visible while revealed | Manual (G3) |
| T27 | Other people's fingerprints, or spoofed biometrics | ADV-4 | M | Class 3 biometrics only. Onboarding warns that anyone enrolled on the phone can open the vault. Password-only mode. | Spoofing of certified sensors; household members enrolled before the vault existed | Manual (G2) |

### 7.2 Malware and privileged attackers

| ID | Threat | Adv. | Risk | Mitigations | Residual risk | Verified by |
|---|---|---|---|---|---|---|
| T04 | Overlay, accessibility, keyboard or clipboard malware | ADV-5 | M | No exported components besides the launcher. FLAG_SECURE. `setHideOverlayWindows`. `setAccessibilityDataSensitive` (API 34+). Sensitive clips with a short lifetime. Advisory warnings about non-system accessibility services and keyboards. | **A malicious keyboard can log the vault password.** Accessibility malware on API 31–33. Clipboard reads within the lifetime. | Hostile test APK (G3) |
| T05 | Rooted or exploited device | ADV-6 | H impact | Root detection is advisory only, never a security boundary. Hardware keys stay non-exportable, and KS-BIO still needs the biometric. | **Can hook the app and read VK while unlocked** (out of scope) | Documented; Frida suite (G2) |
| T06 | Reverse engineering and debugging | ADV-6 | M | Release builds not debuggable. No secrets in the binary. Security never relies on obscurity; the core is open source. | Full reverse engineering is assumed | Manifest assertions (CI) |
| T10 | Memory inspection | ADV-6 | M–L | VK only in Rust memory, zeroized. Lock on background. Per-field decryption on demand. Few string copies. | Revealed values in Kotlin strings persist until garbage collection. A dump of an unlocked session exposes VK. | Heap dump after lock (G2) |
| T14 | Guessing the vault password | ADV-2, ADV-6 | M | **One hardware HMAC per guess**, gated by the device credential, so guesses cannot move off the device. Escalating delays (30 s at attempt 5, rising to 3 h) that a reboot does not shorten. Argon2id. Password policy: at least 12 characters and ≥ 10¹² estimated guesses. Optional erase after 10 failures, off by default. | Root bypasses the app's delays, but each guess still needs this device and a full Argon2id | 50-attempt automation with reboots (G2) |
| T16 | Authentication bypass: hooking the biometric callback, patching `isUnlocked`, replaying an old slot key | ADV-6 | H impact | Unlock is bound to cryptography: VK comes only from a key release (I7). The core refuses every operation while locked. The session is never restored from saved state. CI lint bans `BiometricPrompt` without a CryptoObject. | In-session gates (critical reveal) are anti-grab UX controls, **not cryptographic boundaries** | Frida bypass suite (G2, every release) |

### 7.3 Data at rest and leakage

| ID | Threat | Adv. | Risk | Mitigations | Residual risk | Verified by |
|---|---|---|---|---|---|---|
| T07 | Copy of `vault.db` | ADV-6, ADV-7 | M | Per-record AEAD. Keys in hardware. KS-REC needs the password **and** a 128-bit secret. | Item count, padded record sizes and keyslot KDF parameters are visible | DB extraction and canary grep (G1, G4) |
| T08 | Reading app preferences and other files | ADV-6 | M | Preferences hold only the theme, onboarding state, failure counter and lockout time | Root can reset the failure counter (see T14) | File review (G3) |
| T09 | Extraction through OS backups | ADV-7 | M | `allowBackup=false`. `dataExtractionRules` exclude everything, for both cloud and device-to-device transfer. Vault in `noBackupFilesDir`. | If an OS bug copies files anyway, they are ciphertext, and hardware keys never migrate | `adb backup`, `bmgr`, transfer simulation (G3) |
| T11 | Clipboard harvesting | ADV-5 | M | Copy only on an explicit tap. `EXTRA_IS_SENSITIVE`. Cleared after 60 s. Autofill in V1.1 reduces clipboard use. | Keyboard clipboard histories (OEM-dependent). Exposure lasts as long as the clip. **Background clearing is unreliable on some devices** (R1). | Device matrix (G3) |
| T12 | Screenshots, recording, casting | ADV-5 | M | FLAG_SECURE on **every** window, including dialogs and sheets. `setRecentsScreenshotEnabled(false)`. | A second camera cannot be stopped | Every screen and dialog (G3) |
| T22 | Secrets in logs | ADV-5, ADV-6 | M | Logging stripped from release builds. Lint bans. Redacted `toString` and `Debug`. Canary sweeps. | System logs written by keyboards and other apps | Canary sweep of logcat, bugreport and tombstones (every gate) |
| T23 | Secrets in crash reports | ADV-10 | L–M | No crash-reporting SDK. Sanitised exception messages. | OS crash reports contain stack traces, not heap contents | Forced crashes during reveal (every gate) |
| T24 | Analytics leakage | ADV-10 | L | No analytics, by construction (I5) | — | Manifest and dependency checks (CI) |
| T30 | Assistant or AI screen reading (for example Circle to Search) | ADV-5 | M | FLAG_SECURE | **OEM and OS behaviour varies** (R7) | Device matrix (G3) |
| T31 | Other autofill services reading Vaultaire's screens | ADV-5 | M | Root view marked `importantForAutofill=noExcludeDescendants`. The vault-password field is never tagged as a login field. | — | Hostile test APK (G3) |
| T32 | Secrets restored after the OS kills the app | ADV-3 | M | No secrets in `savedInstanceState`, `rememberSaveable`, `SavedStateHandle` or navigation arguments. A cold start always shows the lock screen. | — | Reveal → kill → restore test (G2, G3) |

### 7.4 Files, backups and recovery

| ID | Threat | Adv. | Risk | Mitigations | Residual risk | Verified by |
|---|---|---|---|---|---|---|
| T17 | Malicious backup file | ADV-8 | M | Header ≤ 4 KiB, strict CBOR, bounded KDF parameters. **Authenticate, then parse** (I8). Memory-safe Rust. Fuzzing. Hard limits on size and counts. Staged, then committed in one transaction. | Someone holding the user's password and Recovery Key can craft a valid file, so the parser must be robust anyway | Structure-aware fuzzing, ≥ 24 h per gate |
| T18 | Tampered vault records or backups | ADV-6, ADV-7 | M | AEAD with associated data binding vault id, item id, envelope kind and version, so records cannot be swapped or moved. Header MAC. Fail closed (I4). | **Rolling back the whole database to an older copy is not detected in V1** (root required). A generation counter is planned for V1.1. | Bit flips, swaps, truncation, old-copy restore (G1, G4) |
| T25 | Theft of an exported backup | ADV-7 | M | Two secrets: password **and** 128-bit Recovery Secret. Argon2id. AEAD. | File + Recovery Key + a weak password allows offline guessing | Export tamper tests (G4) |
| T29 | Offline guessing against a stolen backup | ADV-7 | M | Two secrets. Argon2id at 128 MiB memory, 4 passes. Password policy ≥ 10¹² estimated guesses. | If the Recovery Key is also stolen, password strength decides | Parameter-bound tests (G4) |
| T26 | Phishing the Recovery Key, fake support | ADV-9 | M | **No support-assisted recovery exists.** The app says "We never ask for your Recovery Key." It is entered only in restore and reset flows. | Users can still be tricked | Copy review (G4) |
| T28 | Repackaged or fake app | ADV-9 | M | Play App Signing. Published signing-certificate fingerprint. A fake app cannot read our data (different UID). | Phishing of the password plus the Recovery Key | — |
| T34 | **Vault destroyed or owner locked out** (uninstall, factory reset, lost phone, wipe after 10 failures if enabled, forgotten password with no working biometric) | ADV-1, ADV-3, or accident | M | Encrypted backups are part of V1, with reminders. A periodic password check keeps the password remembered and proves the recovery slot works. Erase-after-failures is off by default. Onboarding states that a lost password plus a lost biometric cannot be recovered. | **Without a backup, a lost or wiped phone means a lost vault.** This is inherent to local-only storage. | Lockout scenarios (G4) |

### 7.5 Interfaces and supply chain

| ID | Threat | Adv. | Risk | Mitigations | Residual risk | Verified by |
|---|---|---|---|---|---|---|
| T15 | Credential stuffing | — | L | The vault has no online login. Password generator; reuse detection in V1.1. | Users may reuse passwords elsewhere | — |
| T19 | Malicious deep links or intents | ADV-5 | L | No deep links or URL schemes. Only the launcher activity is exported. Intent extras ignored. `taskAffinity=""`. | — | Crafted-intent script (CI, G3) |
| T20 | Compromised dependency | ADV-10 | M, H impact | Few dependencies, each justified in an ADR, pinned with checksums. `cargo deny`, `cargo vet`, Gradle dependency verification. **No `INTERNET` permission.** Lint bans on `ACTION_VIEW` to external URIs and on WebView, which would otherwise let code leak data through a browser. | Indirect leakage through other apps | CI on every PR |
| T21 | Compromised build or release pipeline, or developer account takeover | ADV-10 | M, **critical impact** | Passkeys (FIDO2) on GitHub, Google and Play Console accounts. Clean CI runners; actions pinned by commit SHA. Reproducible builds. Play App Signing with a separate upload key. App Bundle code transparency. SBOM. | **A malicious update after an account takeover is the top residual risk.** It can read the vault at the next unlock. | Reproducible-build comparison (every release) |
| T33 | **Stored CVVs turn one vault compromise into ready-to-use card fraud** | ADV-3, ADV-6, ADV-7 | M, H impact | Opt-in per card, off by default, with the risk stated before the field appears (I11). Critical tier: fingerprint within the last 60 s to reveal or copy. Never autofilled. Never shown on the card face. Padded like every other secret, so its length is hidden. Settings shows how many cards have a stored CVV. | Any full compromise (T05 while unlocked, T25 with both secrets) yields **number + expiry + CVV** for every opted-in card, enough for online purchases | Reveal and copy gating (G3); canary sweep (G3) |

## 8. Residual risks (published)

These will be stated in the app's About section in plain language:

1. **Malware running inside an unlocked session** can read what the app can read.
2. **A malicious update** (after a developer or store account takeover) can read the vault at the next unlock.
3. **A malicious Android keyboard** can record the vault password as it is typed.
4. **Copied values** can be read from the clipboard until it is cleared, and some keyboards keep their own clipboard history.
5. **Losing the vault password and the working biometric together** means the vault cannot be recovered. Nobody, including the developer, can help.
6. **Losing the phone without a backup** means the vault is gone.
7. **Stored CVVs** make a compromised vault directly usable for online card fraud.
8. **Rolling back the vault file** to an older copy is not detected in V1 (requires root).

## 9. Decision record: card security codes (CVV)

- **Original decision (blueprint v0.1):** do not store CVVs. Card number, expiry and CVV together are everything needed for card-not-present fraud, so a vault compromise would hand over the whole kit. PCI DSS forbids storing verification codes after authorisation for entities in its scope. This does not bind an individual storing their own card, but it shows how the industry weighs the risk.
- **Change (2026-09-29):** the product owner decided that users should be able to store the CVV.
- **Conditions adopted**, the same ones the original decision set for any future reversal:
  - per-card opt-in, off by default, with the risk explained before the field appears;
  - critical tier (biometric within the last 60 s to reveal or copy);
  - never autofilled, never shown on the card face;
  - included in encrypted backups (leaving it out would lose data silently on restore);
  - the card PIN is still never stored.
- **Consequences:** new threat T33, invariant I11 and published residual risk 7. The G0 reviewer is asked to challenge this decision specifically.

## 10. iOS delta (deferred)

The iOS phase is paused. These known differences must be resolved at Gate G7:

- **No screenshot blocking** with public APIs. Capture can be detected and content hidden, but not prevented (affects T12).
- **No per-guess hardware binding for the password.** The Secure Enclave has no symmetric primitive, so KS-PWD's hardware factor is a pepper that can be extracted once by an attacker who has the passcode and an exploit. Offline guessing then depends on the password alone (affects T03, T14). This is why the password policy assumes offline attack.
- **Third-party keyboards can be blocked** app-wide, which removes part of T04.
- The AutoFill extension runs under a tight memory limit (open item R9).

## 11. Open research items

To be resolved, or explicitly accepted as risks, at Gate G0:

| ID | Question | Affects |
|---|---|---|
| R1 | Does background `clearPrimaryClip()` work reliably on API 31–36 and major OEM builds? | T11 |
| R2 | Do Gboard and Samsung Keyboard honour `EXTRA_IS_SENSITIVE` for clipboard history? | T11 |
| R3 | StrongBox availability and latency for AES-GCM and HMAC on target devices; the policy when only a TEE is available | T03, T14 |
| R4 | Are keys created with `setUnlockedDeviceRequired` invalidated when the lock screen is removed? How much do OEMs vary in invalidating keys after OS updates? | T34 |
| R5 | Argon2id time and memory on the lowest supported device; final parameter floors; GPU cost benchmark for the password-policy maths | T14, T29, A7 |
| R6 | UniFFI and JNA footprint, R8 compatibility and startup cost versus a minimal JNI layer | T20 |
| R7 | How assistants, Circle to Search and on-device AI treat FLAG_SECURE windows | T30 |
| R8–R11 | iOS items: `kSecAccessControlApplicationPassword`, AutoFill extension memory, screenshot blanking, SQLite file locking in App Groups | §10 |

## 12. Change control

- This document is frozen at Gate G0. The sign-off is committed to `docs/gates/G0.md`.
- After G0, any new threat, a changed mitigation or a changed invariant requires an update here **in the same change** as the design or code change.
- Changes to cryptography, the file format or keyslots after G1 reopen G1. Changes to unlock flows after G2 reopen G2.
- A review by someone other than the developer is required at G0 and before any public release.
