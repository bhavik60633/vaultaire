# ADR-003: Cryptographic primitives from RustCrypto

- Status: **Proposed**, for review at Gate G0.
- Date: 2026-09-30

## Context

`vault-core` needs XChaCha20-Poly1305, Argon2id, HKDF-SHA256, HMAC-SHA256 and SHA-256 ([crypto spec §2](../crypto-spec.md#2-primitives)). The project must not write its own cryptography, and a compromised or buggy implementation here exposes **every key and every secret** in every vault.

The Phase-0 spike used `chacha20poly1305` and `argon2` on real phones without problems ([results](../spikes/phase0-results.md)).

## Decision

Use the RustCrypto crates below, pinned exactly in `Cargo.lock`:

| Crate | Version | Licence | Maintainers | Runtime deps (own) | Audit history |
|---|---|---|---|---|---|
| `chacha20poly1305` | 0.11.0 (2026-06) | MIT OR Apache-2.0 | RustCrypto AEADs team | `aead`, `chacha20`, `cipher`, `poly1305` | NCC Group audit of RustCrypto's AEAD crates, 2020 (funded by MobileCoin). It covered **much older versions**; 0.11 has been heavily refactored since, so the audit supports the implementation's lineage, not this exact code |
| `argon2` | 0.6.0 (2026-08) | MIT OR Apache-2.0 | RustCrypto password-hashes team | `base64ct`, `blake2`, `cpufeatures` | **None known.** Covered instead by the RFC 9106 vectors and the independent Python cross-check (crypto spec §12) |
| `hkdf` | 0.13.0 (2026-03) | MIT OR Apache-2.0 | RustCrypto KDFs team | `hmac` | None dedicated. RFC 5869 vectors |
| `hmac` | 0.13.0 (2026-03) | MIT OR Apache-2.0 | RustCrypto MACs team | `digest` | None dedicated. RFC 4231 vectors |
| `sha2` | 0.11.0 (2026-03) | MIT OR Apache-2.0 | RustCrypto hashes team (incl. Tony Arcieri) | `cfg-if`, `cpufeatures`, `digest` | None dedicated. FIPS 180-4 vectors. About 1 billion downloads |

Features: `argon2` with `default-features = false` and only `alloc` (the `password-hash` string format is not used; the spec stores raw parameters).

**Transitive cost:** about 20 runtime crates, all from the RustCrypto organisation or its long-standing shared utilities (`typenum`, `cpufeatures`, `hybrid-array`, `subtle`, `zeroize`).

## Why RustCrypto

- Memory-safe Rust, no C, no `unsafe` beyond small, reviewed SIMD paths.
- An organisation of several maintainers, not one person; several of the crates are among the most downloaded in the ecosystem, so bugs tend to be found fast.
- Pure Rust cross-compiles to Android with no extra toolchain (proven by the spike).

## Alternatives considered

- **libsodium** (via `libsodium-sys` or `sodiumoxide`): battle-tested and audited, but C code behind an FFI boundary, and `sodiumoxide` is unmaintained. Kept as the **named fallback** (crypto spec §2): the test vectors prove that switching changes no output byte.
- **`ring`:** well reviewed, but has no XChaCha20 and no Argon2, so it would still need a second provider.
- **Android platform crypto (JCA):** no XChaCha20-Poly1305 or Argon2id, and it would split the crypto between languages.

## Consequences

- Every algorithm is checked on every CI run against its published vectors (RFC 8439 and the XChaCha draft, RFC 9106, RFC 5869, RFC 4231, FIPS 180-4) and against the project's own vectors, cross-checked by an independent Python implementation.
- Updates are manual, reviewed and never auto-merged. A new minor version is treated like a new dependency: read the changelog and the diff of the relevant module.
- **If compromised:** total loss (keys, plaintext). This is the highest-risk group; that is why it is pinned, vector-tested and limited to one well-known organisation.

## Revisit when

A RustCrypto crate used here gets a security advisory, loses active maintainers, or an audit of the current versions is published (then record it here).
