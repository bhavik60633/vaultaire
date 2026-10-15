# Proposed tests, waiting for the owner to freeze them

Each component's tests are proposed here first (product plan §7). CI does not run this folder, so a
test that is red on purpose (its code does not exist yet) does not break the build.

When the owner freezes a component's tests, they move, byte for byte unchanged, to their real place
(for example `tools/test_frozen_tests.py`), their checksums go into `tests/FROZEN.sha256`, and only
then is the code written.

| Component | Tests | Moves to | Status |
|---|---|---|---|
| 1. Test-freeze guard | [`component-1/test_frozen_tests.py`](component-1/test_frozen_tests.py) | `tools/test_frozen_tests.py` | Waiting for the owner's review |
