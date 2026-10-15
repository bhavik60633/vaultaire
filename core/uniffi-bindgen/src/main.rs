//! Generates the Kotlin bindings for `vault-ffi` (ADR-008). Runs on the developer's machine and
//! in CI only; none of its code is compiled into the app.
//!
//! cargo build -p vault-ffi --release
//! cargo run -p uniffi-bindgen -- generate --library target/release/libvault_ffi.so \
//!     --language kotlin --out-dir ../android/core-bindings/src/main/java --no-format

fn main() {
    uniffi::uniffi_bindgen_main()
}
