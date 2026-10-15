# Architecture decision records

Each significant decision gets a short record: the context, the decision, the options rejected and the consequences. A record is never edited to change its decision. A new record supersedes it instead, so the history stays readable.

Every third-party dependency also needs a record before it is added (why it is needed, maintainer health, audit history, transitive dependencies, licence, and what breaks if it is compromised). The default answer is no.

| ADR | Title | Status |
|---|---|---|
| [001](ADR-001-stack.md) | Native Android UI over a shared Rust core | **Accepted** 2026-09-30 (Phase-0 spike) |
| [002](ADR-002-keyslots.md) | One random vault key wrapped in three keyslots | Proposed; for review at Gate G0 |
| [003](ADR-003-crypto-primitives.md) | Cryptographic primitives from RustCrypto | Proposed |
| [004](ADR-004-randomness-memory.md) | Randomness, constant-time comparison and memory wiping | Proposed |
| [005](ADR-005-storage-sqlite.md) | SQLite through `rusqlite`, with SQLite bundled | Proposed |
| [006](ADR-006-cbor-minicbor.md) | `minicbor` for deterministic CBOR | Proposed |
| [007](ADR-007-password-input.md) | Password strength (`zxcvbn`, conditional) and normalisation | Proposed |
| [008](ADR-008-bridge-errors.md) | The Rust–Kotlin bridge (`uniffi`, JNA) and error types | Proposed |
| [009](ADR-009-card-scanner-ocr.md) | Card scanner with CameraX and ML Kit's bundled text recognition | Proposed; versions pinned before Phase 5 |
| [010](ADR-010-backup-drive-saf.md) | Backups to the user's Google Drive through the system file picker | Proposed; cached key pending Q9 |
| [011](ADR-011-bank-catalogue.md) | A bundled bank catalogue and name normalisation for bank-grouped cards | Proposed |

**Supply-chain store** (`core/supply-chain/`, set up 2026-10-15 by owner decision): `cargo vet` runs in CI. As a starting point every crate in `Cargo.lock` on that date (50 third-party crates) is an **exemption**: accepted without a recorded audit. From then on, any new or updated crate fails CI until it is audited or exempted with a reason in its ADR. Next step: import published audits (for example Mozilla's, Google's and the Bytecode Alliance's) so that exemptions become audits.

**Dependency totals** (measured 2026-09-30 with `cargo tree` for `aarch64-linux-android`): about **74 crates compiled into the app**, 98 including build-time tools. Every licence in the tree is GPLv3-compatible (MIT, Apache-2.0, BSD-3-Clause, MPL-2.0, Zlib, Unlicense, Unicode-3.0, BlueOak-1.0.0). The largest single contributors are UniFFI's runtime (about 20) and `zxcvbn` (17). ADRs 003–008 group the dependencies by purpose, with one section per crate.

## Template

```markdown
# ADR-NNN: <decision in a few words>

- Status: Proposed | Accepted | Superseded by ADR-MMM
- Date: YYYY-MM-DD

## Context
## Decision
## Alternatives considered
## Consequences
## Revisit when
```
