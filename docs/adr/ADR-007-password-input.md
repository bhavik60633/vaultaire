# ADR-007: Password strength (`zxcvbn`) and normalisation (`unicode-normalization`)

- Status: **Proposed**, for review at Gate G0. `zxcvbn` is **conditional** on the review below.
- Date: 2026-09-30

## Context

The vault password is normalised to Unicode NFC before key derivation (crypto spec §3.2), and a new password must reach an estimated 10¹² guesses (§3.2). Both libraries **handle the user's password in plaintext**, so they are unusually sensitive for their size.

## Decision

| Crate | Version | Licence | Maintainers | Runtime deps |
|---|---|---|---|---|
| `unicode-normalization` | 0.1.25 (2025-10) | MIT OR Apache-2.0 (Unicode data: Unicode-3.0) | unicode-rs group, five owners | `tinyvec` |
| `zxcvbn` | 3.1.1 (2026-04) | MIT | **one** (Josh Holmer) | 16: `regex`, `regex-automata`, `regex-syntax`, `fancy-regex`, `aho-corasick`, `memchr`, `time`, `itertools`, `lazy_static` and others |

- **`unicode-normalization`:** accepted. Small, widely used (about 590 million downloads), several maintainers.
- **`zxcvbn`:** accepted **only if** its source and the version in use pass a manual review before G1 (no network, file or environment access; no logging of input). Use `default-features = false`.

`zxcvbn` is the heaviest single addition: 17 of the core's roughly 74 runtime crates come from it.

## Why accept `zxcvbn` at all

- It gives realistic estimates (dictionaries, keyboard patterns, dates, leetspeak), which simple rules cannot.
- It runs **only when a password is set or changed**, never on unlock.
- **The Android app has no INTERNET permission,** so even a malicious version has no direct way to send a password out.

## Fallback if the review fails

Drop `zxcvbn` and enforce: at least 12 characters, **plus** rejection against a bundled offline list of the most common leaked passwords, **plus** a simple character-set estimate. This is weaker at spotting clever-looking weak passwords, so the spec's "10¹² guesses" wording would change to the rule actually enforced.

## Alternatives considered

- **`zxcvbn4j` in Kotlin:** the same exposure, and it would move password handling out of the core.
- **No strength check:** users choose weak passwords, and KS-REC (password plus Recovery Secret) and backups depend on the password against offline guessing.

## Consequences

- **If compromised:** either crate could see the password as it is typed during set-up or change. Mitigations: pinned versions, manual review, the no-network app, and the password never being passed to them on unlock.

## Status of the fallback

Milestone M2 (2026-10-15) ships the fallback rule in `vault_core::password_policy`, with a small bundled list of common passwords and words, so `zxcvbn` is **not** a dependency yet. It is added only if its review passes.

## Revisit when

The `zxcvbn` review is done (record the result here), the crate changes maintainers, or its dependency count grows.
