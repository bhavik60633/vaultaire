# ADR-006: `minicbor` for deterministic CBOR

- Status: **Proposed**, for review at Gate G0.
- Date: 2026-09-30

## Context

Records, the vault header and the backup file are encoded as **deterministic CBOR** ([vault-format §2](../vault-format.md)): definite lengths, integer map keys in ascending order, shortest integer forms, no floats and no tags. Two properties matter:

1. **The same data must always produce the same bytes,** because encodings are authenticated (MACs and AEAD associated data cover them).
2. **Decoding attacker-controlled bytes must be safe:** a backup file is untrusted input until it verifies.

The blueprint allowed `ciborium` or `minicbor`. This record chooses.

## Decision

Use **`minicbor` 2.3.0** (2026-07), with only the `alloc` feature.

| | `minicbor` | `ciborium` |
|---|---|---|
| Runtime deps | **none** | `ciborium-io`, `ciborium-ll`, `serde` |
| API | Explicit `Encoder` / `Decoder`: the code decides every byte | Through `serde`: byte layout follows struct definitions and derive macros |
| Latest release | 2026-07 | 2024-01 |
| Maintainers | one (Toralf Wittner) | enarx project |
| Licence | BlueOak-1.0.0 | Apache-2.0 |

`minicbor` wins because the project needs **explicit control of every byte**, and it adds no dependencies. Its licence, the Blue Oak Model License 1.0.0, is OSI-approved (2024), and the Blue Oak Council states it is compatible with GPLv3.

**Rules in the core, on top of the library:**

- Encoding goes through hand-written functions per structure, never derive macros, so the canonical order is visible in the code.
- Every decode is followed by a **canonical check**: re-encode the decoded value and require byte-for-byte equality with the input. Non-canonical input is rejected, which also closes "same meaning, different bytes" tricks.
- Decoding enforces the size and depth limits in vault-format before allocating.
- The decoder is fuzzed continuously (G1: at least 24 h clean).

## Alternatives considered

- **`ciborium`:** larger dependency footprint through `serde`, less direct control over bytes, and no release for over two years.
- **Writing a CBOR subset by hand:** fewer lines to trust, but parsers are exactly where memory-safety and logic bugs hide. A small, well-used library plus fuzzing is the safer bet.

## Consequences

- One maintainer is a bus-factor risk. Mitigation: the crate is small, has no dependencies, is pinned, and the version in use can be vendored if it is ever abandoned.
- **If compromised:** a decoding bug could crash the import or mis-read a structure. Every structure is authenticated before its contents are trusted, and the canonical check rejects anything the core would not have produced itself.

## Revisit when

`minicbor` is abandoned, gets an advisory, or changes its licence.
