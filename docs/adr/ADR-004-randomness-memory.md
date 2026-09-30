# ADR-004: Randomness, constant-time comparison and memory wiping

- Status: **Proposed**, for review at Gate G0.
- Date: 2026-09-30

## Context

Every key, salt and nonce comes from the operating system's random generator, with no fallback (crypto spec §2.1). Secret comparisons must not leak timing. Keys and decrypted values must be wiped from memory after use (threat-model invariants). These are small, foundational utilities, so each must be minimal and widely trusted.

## Decision

| Crate | Version | Licence | Maintainers | Runtime deps | Why |
|---|---|---|---|---|---|
| `getrandom` | 0.4.3 (2026-06) | MIT OR Apache-2.0 | rust-random organisation | `cfg-if`, `libc` (Android) | The OS random generator. On Android it calls the `getrandom` system call. About 2 billion downloads |
| `subtle` | 2.6.1 (2024-06) | BSD-3-Clause | dalek-cryptography maintainers | none | Constant-time equality for MAC and checksum comparisons |
| `zeroize` | 1.9.0 (2026-06) | Apache-2.0 OR MIT | RustCrypto utilities team | none | Guaranteed wiping of secret buffers, including on drop (`derive` feature) |
| `secrecy` | 0.10.3 (2024-10) | Apache-2.0 OR MIT | iqlusion (Tony Arcieri) | `zeroize` | Wrapper types that zeroize on drop and refuse to print their contents in logs or debug output |

`subtle` and `secrecy` have not had releases for a while. For crates this small and finished, that is a sign of stability, not neglect.

## Alternatives considered

- **`rand` with `OsRng`:** pulls in more code for the same system call. Not needed: the spec never uses a seeded or user-space generator.
- **Hand-written constant-time comparison or wiping:** exactly the kind of subtle code the project must not write. Compilers optimise naive versions away.

## Consequences

- If `getrandom` fails, the operation aborts (crypto spec §2.1).
- A lint rule forbids `==` on secret byte slices; `subtle` or the AEAD's own tag check is the only way to compare secrets.
- **If compromised:** `getrandom` returning predictable output breaks every key and nonce, which is total loss. It is small, heavily reviewed and pinned. `zeroize` failing silently leaves secrets in memory longer, which weakens but does not break the design.

## Revisit when

A security advisory is published for any of them, or Android changes its recommended random source.
