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
| 4. Organisation record (custom banks, last card view) | [`component-4/organisation.rs`](component-4/organisation.rs), moves to `core/vault-core/tests/organisation.rs` | **Waiting for the owner's review.** 17 tests |
| 5. Backups carry bank groups | [`component-5/backup_banks.rs`](component-5/backup_banks.rs), moves to `core/vault-core/tests/backup_banks.rs` | **Waiting for the owner's review.** 11 tests (two backward-compatibility tests already pass on today's code and must keep passing) |
| 6. Cached export key for auto-backup | [`component-6/export_key.rs`](component-6/export_key.rs), moves to `core/vault-core/tests/export_key.rs` | **Waiting for the owner's review.** 6 tests |

| 7. Vault store and session (milestone M1) | [`component-7/vault_store.rs`](component-7/vault_store.rs), moves to `core/vault-core/tests/vault_store.rs` | **Waiting for the owner's review.** 17 tests. Adds `rusqlite` (ADR-005) and four error names: `vault_exists`, `insecure_device`, `integrity_failed`, `not_found` |

**Milestone M1** is components 4–7 together (51 tests), frozen in one go (owner decision: batches per milestone). Components 4–6 were checked together: rustfmt clean, they compile and pass clippy `-D warnings` against stub APIs, and they fail against those stubs.
