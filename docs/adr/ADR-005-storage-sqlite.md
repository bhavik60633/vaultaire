# ADR-005: SQLite through `rusqlite`, with SQLite bundled

- Status: **Proposed**, for review at Gate G0.
- Date: 2026-09-30

## Context

The vault is a SQLite database of encrypted records ([vault-format §4](../vault-format.md)). The core, not the Android app, owns the format, so the database has to be opened from Rust. SQLite itself stores only ciphertext and non-secret layout data; record encryption happens in the core before anything reaches SQLite.

## Decision

| Crate | Version | Licence | Maintainers | Runtime deps |
|---|---|---|---|---|
| `rusqlite` | 0.40.2 (2026-08) | MIT | Thom Chiovoloni, gwenn | `bitflags`, `fallible-iterator`, `fallible-streaming-iterator`, `hashlink`, `smallvec`, `libsqlite3-sys` |
| `libsqlite3-sys` | 0.38.2 (2026-08) | MIT (SQLite itself: public domain) | same | none; compiles the SQLite C source |

Feature `bundled`: SQLite is compiled from the official amalgamation shipped inside `libsqlite3-sys`, so every phone runs the **same SQLite version**, not whatever the phone maker shipped. The exact SQLite version is recorded in the build log and in the SBOM.

About 9 runtime crates in total.

## Alternatives considered

- **Android's built-in SQLite from Kotlin:** would move database access out of the core, split the format across two languages, and tie behaviour to each phone maker's SQLite version.
- **SQLCipher:** encrypts the whole file, but the design already encrypts every record with keys only the core holds. It would add a second, redundant key-management path and a large C dependency.
- **A custom file format:** more code the project would have to get right (atomic writes, crash safety). SQLite's reliability is hard to match.

## Consequences

- SQLite is **C code**, the one significant non-Rust component in the core. Exposure is limited: it only ever opens the app's own database, in the app's private storage, and backups are imported through the CBOR parser (ADR-006), never by opening a foreign SQLite file.
- The vault format uses `application_id` and `user_version` checks before any other read (vault-format §4).
- **If compromised:** the database layer sees only ciphertext and layout, not keys or plaintext. A malicious SQLite could corrupt or delete the vault (a denial of service that backups recover from), but not read secrets.

## Revisit when

A SQLite CVE affects features the vault uses, `rusqlite` loses its maintainers, or the design ever needs to open SQLite files it did not create (it must not).
