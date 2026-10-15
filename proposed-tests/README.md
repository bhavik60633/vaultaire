# Proposed tests, waiting for the owner to freeze them

Each component's tests are proposed here first (product plan §7). CI does not run this folder, so a
test that is red on purpose (its code does not exist yet) does not break the build.

When the owner freezes a component's tests, they move, byte for byte unchanged, to their real place,
their checksums go into [`tests/FROZEN.sha256`](../tests/FROZEN.sha256), and only then is the code
written. From then on `python tools/frozen_tests.py check` (run in CI) fails if a frozen test changes.

| Component | Tests | Status |
|---|---|---|
| 1. Test-freeze guard | [`tools/test_frozen_tests.py`](../tools/test_frozen_tests.py) | **Frozen 2026-10-15** by the owner; code written; 27/27 green |
| 2. Bank catalogue and name normaliser | [`core/vault-core/tests/banks.rs`](../core/vault-core/tests/banks.rs) | **Frozen 2026-10-15** by the owner; code written (`vault_core::banks`); 17/17 green |
| 3. Card, PIN and bank-account record rules | [`core/vault-core/tests/items.rs`](../core/vault-core/tests/items.rs) | **Frozen 2026-10-15** by the owner; code written (`vault_core::item::validate`); 28/28 green |
| 4. Organisation record (custom banks, last card view) | [`core/vault-core/tests/organisation.rs`](../core/vault-core/tests/organisation.rs) | **Frozen 2026-10-15** by the owner; code written (`vault_core::organisation`); 17/17 green |
| 5. Backups carry bank groups | [`core/vault-core/tests/backup_banks.rs`](../core/vault-core/tests/backup_banks.rs) | **Frozen 2026-10-15** by the owner; code written (`vault_core::backup`); 11/11 green |
| 6. Cached export key for auto-backup | [`core/vault-core/tests/export_key.rs`](../core/vault-core/tests/export_key.rs) | **Frozen 2026-10-15** by the owner; code written (`vault_core::backup::ExportKey`); 6/6 green |
| 7. Vault store and session (milestone M1) | [`core/vault-core/tests/vault_store.rs`](../core/vault-core/tests/vault_store.rs) | **Frozen 2026-10-15** by the owner; code written (`vault_core::vault`, SQLite via `rusqlite`, ADR-005); 17/17 green |

| 8. The core's own security rules (milestone M2) | [`core/vault-core/tests/session_security.rs`](../core/vault-core/tests/session_security.rs) | **Frozen 2026-10-15** by the owner; code written; 22/22 green. 22 tests: password strength, security log, protected settings, periodic password check, adding or replacing the fingerprint |
| 9. Android security building blocks (milestone M2) | [`android/app/src/test/java/dev/vaultaire/security/`](../android/app/src/test/java/dev/vaultaire/security/) (9 files) | **Frozen 2026-10-15** by the owner; code written; 51/51 green. 51 tests: auto-lock, fingerprint gate, re-masking, clipboard, password waiting times, preferences allow-list, Keystore key settings, fingerprint prompts, refused phones |
| 10. Bridge from Kotlin to the Rust engine (milestone M2) | [`android/core-bindings/src/test/java/dev/vaultaire/core/VaultEngineTest.kt`](../android/core-bindings/src/test/java/dev/vaultaire/core/VaultEngineTest.kt) | **Frozen 2026-10-15** by the owner; code written (`vault-ffi`, `VaultEngine`); 11/11 green. 11 tests, run against the real Rust core |
| 11. First screens (milestone M2) | [`android/app/src/test/java/dev/vaultaire/ui/`](../android/app/src/test/java/dev/vaultaire/ui/) (5 files, one of them the stand-in vault `FakeGateway.kt`) | **Frozen 2026-10-15** by the owner; code written; 25/25 green. 25 tests: creating a vault, the lock screen, the beta and test-build warnings, screenshot blocking |

| 12. Cards, PINs and bank accounts as entries; browsing by bank (milestone M3) | [`core/vault-core/tests/entries.rs`](../core/vault-core/tests/entries.rs) | **Frozen 2026-10-15** by the owner; code written (`vault_core::entries`); 29/29 green. 29 tests, including the owner's checks 1–7 on 5 SBI, 2 HDFC and 3 Axis cards |
| 13. Entries across the bridge (milestone M3) | [`android/core-bindings/src/test/java/dev/vaultaire/core/EntriesBridgeTest.kt`](../android/core-bindings/src/test/java/dev/vaultaire/core/EntriesBridgeTest.kt) | **Frozen 2026-10-15** by the owner; code written; 11/11 green. 11 tests, run against the real Rust core |
| 14. The M3 screens (milestone M3) | [`android/app/src/test/java/dev/vaultaire/ui/`](../android/app/src/test/java/dev/vaultaire/ui/) (5 files, one of them `RealVault.kt`, a real vault for the screen tests) | **Frozen 2026-10-15** by the owner; code written; 34/34 green. 34 tests: home and bank pages, card and PIN pages with copy rules, adding and editing, returning after unlock (check 10), settings. Wording follows the design mockup |

| 15. The card itself: floating, glowing, turns over (owner request) | [`android/app/src/test/java/dev/vaultaire/ui/CardFaceTest.kt`](../android/app/src/test/java/dev/vaultaire/ui/CardFaceTest.kt) | **Frozen 2026-10-15** by the owner; code written (`CardFace.kt`, card finish in the vault); 8/8 green. 8 tests: the front masks the number until a fingerprint, the back shows the CVV under the same rules, a PIN is only ever a link, the finish is kept. The animation itself is judged from recorded previews |

| 16. Reading a card's front from the camera's text (milestone M5) | [`m5/CardTextReaderTest.kt`](m5/CardTextReaderTest.kt), moves to `android/app/src/test/java/dev/vaultaire/scan/` | **Waiting for the owner's freeze.** 19 tests: the number only after 3 matching frames and the Luhn check, 15-digit Amex, camera mix-ups (O for 0), expiry MM/YY preferring VALID THRU, the name as a line of capitals, a printed bank only offered, the CVV never read |
| 17. Bank names printed on a card (milestone M5) | [`m5/ScanBridgeTest.kt`](m5/ScanBridgeTest.kt), moves to `android/core-bindings/src/test/java/dev/vaultaire/core/` | **Waiting for the owner's freeze.** 2 tests, against the real catalogue and normaliser |
| 18. Scanning from the Add card form (milestone M5) | [`m5/ScanCardTest.kt`](m5/ScanCardTest.kt) and the stand-in camera [`m5/FakeCamera.kt`](m5/FakeCamera.kt), move to `android/app/src/test/java/dev/vaultaire/ui/` | **Waiting for the owner's freeze.** 9 tests: the camera only with permission, and stopped after a read, on close and on lock; number, expiry, name and network filled; bank and card type never chosen; nothing saved before Save |
| 19. Manifest rules for the camera (milestone M5) | [`m5/test_manifest_camera.py`](m5/test_manifest_camera.py), moves to `tools/` | **Waiting for the owner's freeze.** 4 tests: CAMERA allowed only with the camera marked optional; INTERNET and ACCESS_NETWORK_STATE still refused |

**Milestone M1** is components 4–7 together (51 tests), frozen in one go (owner decision: batches per milestone). **M1 is complete: all 51 tests green, none changed.**

The Semgrep rule `secret-compared-with-eq` now skips `tests/`, like `no-printing-in-core`: the fake fingerprint hardware in the frozen `vault_store.rs` compares a tag with `!=`, tests never ship, and a frozen test cannot be edited.

**Milestone M2** is components 8–11 together (109 tests), frozen in one go on 2026-10-15. **M2 is complete: all 109 tests green, none changed.** Checked before sending: the Rust tests are rustfmt and clippy clean; every test compiles against stub code and fails against it, except three that guard behaviour the stubs already get right (a new vault's empty log, good passphrases accepted, and no "Test build" warning in a real build). The Android tests run on Robolectric, so CI needs no phone.

**Milestone M3** is components 12–14 together (74 tests), frozen in one go on 2026-10-15. **M3 is complete: all 74 tests green, none changed.** Checked before sending: the Rust tests are rustfmt and clippy clean; every test compiles against stub code and fails against it, except one that guards behaviour the stubs already get right (going back home leaves no remembered bank). The screen tests drive a real vault (the Rust engine); only the phone's hardware is played.

**Milestone M5** (the scanner, moved ahead of M4 at the owner's request on 2026-10-15) is components 16–19 together (34 tests). Checked before sending: every test compiles against stub code and fails against it, except three that guard behaviour the stubs already get right (an invalid number is never taken; lines that are not banks are not banks; network permissions stay refused). The bank lookups were checked against the real catalogue.
