# ADR-008: The Rust–Kotlin bridge (`uniffi`, JNA) and error types (`thiserror`)

- Status: **Proposed**, for review at Gate G0.
- Date: 2026-09-30

## Context

ADR-001 chose a Rust core called from Kotlin through UniFFI, and the Phase-0 spike kept UniFFI's default JNA bindings. This record covers the libraries that make that bridge, and the error-type helper the core uses.

## Decision

| Crate or library | Version | Licence | Maintainers | Notes |
|---|---|---|---|---|
| `uniffi` | 0.32.2 (2026-09) | MPL-2.0 | Mozilla (four owners) | `default-features = false` |
| JNA (Android side) | 5.19.1 | Apache-2.0 or LGPL-2.1 | JNA project | Used by the generated Kotlin; about 0.3 MB per device (spike) |
| `thiserror` | 2.0.21 (2026-09) | MIT OR Apache-2.0 | David Tolnay | Build-time macro; generates plain Rust |

**Build-time versus shipped code:**

- UniFFI's **code generator** (`uniffi-bindgen`: `askama`, `goblin`, `toml`, `cargo_metadata` and more) runs only on the developer's machine and in CI, as a **separate crate** (`core/uniffi-bindgen`, the only one with the `cli` feature). None of it is compiled into the app: the Android build compiles `-p vault-ffi` only.
- The generated Kotlin (`android/core-bindings/src/main/java/uniffi/vault_ffi/vault_ffi.kt`) is committed, so it can be reviewed. CI regenerates it and fails if it differs.
- With `default-features = false`, the runtime part of `uniffi` still compiles about 20 crates (for example `anyhow`, `bytes`, `once_cell`, `indexmap`), much of which link-time optimisation removes. This is the price of generated bindings, and far less risky than hand-written JNI.
- `thiserror` is a procedural macro: it runs **at build time** and leaves ordinary code behind.

## Why

- Generated bindings remove hand-written `unsafe` glue, which is where FFI bugs usually live.
- Mozilla maintains UniFFI for its own products (Firefox for Android and iOS).
- MPL-2.0 is compatible with GPLv3 (MPL-2.0 §3.3).

## Alternatives considered

- **Hand-written JNI** (the spike measured it): about 18 ms faster at start-up on fast phones, but every function needs hand-written `unsafe` conversion code. Rejected in ADR-001.
- **`anyhow` for errors in the core's public API:** loses the typed errors that the Kotlin side needs to decide what to show (wrong password against corrupted file, for example). `anyhow` stays only where UniFFI uses it internally.

## Consequences

- **Secrets across the bridge:** only the values listed in crypto spec §5.1 ever cross it. The generated code copies them into Kotlin byte arrays, which the Kotlin side zeroes after use.
- **Build-time supply chain:** a compromised build-time crate (the generator or a macro) could inject code into the build. Mitigations: `Cargo.lock` pinned, `cargo vet` and `cargo deny` in CI, reproducible builds before release (G5).
- **If compromised at runtime:** the bridge sees every value that crosses it, which by design excludes VK and its subkeys.

## Revisit when

UniFFI changes its bridge mechanism (for example a JNI backend), the runtime dependency count grows, or a better-audited option appears.
