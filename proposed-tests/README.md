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

**Milestone M1** is components 4–7 together (51 tests), frozen in one go (owner decision: batches per milestone). **M1 is complete: all 51 tests green, none changed.**

The Semgrep rule `secret-compared-with-eq` now skips `tests/`, like `no-printing-in-core`: the fake fingerprint hardware in the frozen `vault_store.rs` compares a tag with `!=`, tests never ship, and a frozen test cannot be edited.

**Milestone M2** is components 8–11 together (109 tests), frozen in one go on 2026-10-15. **M2 is complete: all 109 tests green, none changed.** Checked before sending: the Rust tests are rustfmt and clippy clean; every test compiles against stub code and fails against it, except three that guard behaviour the stubs already get right (a new vault's empty log, good passphrases accepted, and no "Test build" warning in a real build). The Android tests run on Robolectric, so CI needs no phone.
