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
| 3. Card, PIN and bank-account record rules | [`component-3/items.rs`](component-3/items.rs), moves to `core/vault-core/tests/items.rs` | **Waiting for the owner's review.** 28 tests. Checked: rustfmt clean, compiles and passes clippy `-D warnings` against a stub; a stub that accepts everything fails 27 of 28, every failure being a refused-item assertion |
