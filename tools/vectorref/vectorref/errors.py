"""Error names shared with the Rust core (test-vector plan §5.1).

Rule: an error may only distinguish failures that do not depend on a secret. Every failure that
does (wrong password, wrong Recovery Key, damaged or tampered ciphertext) is `auth_failed` for
imports and `unlock_failed` for unlock.
"""


class VaultError(Exception):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code
