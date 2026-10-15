//! The vault's cryptography and format.
//!
//! Implements `docs/crypto-spec.md` and `docs/vault-format.md`. Every construction cites its
//! section; the test vectors in `tests/vectors/v1/` (from the independent Python reference) must
//! be reproduced byte for byte.
//!
//! **Unaudited. Do not store real credentials** (Gate G0 owner override, 2026-09-30).

pub mod backup;
pub mod banks;
pub mod cbor;
pub mod error;
pub mod integrity;
pub mod item;
pub mod keyslot;
pub mod labels;
pub mod organisation;
pub mod prims;
pub mod record;
pub mod recovery_key;
pub mod vault;

pub use error::{Result, VaultError};
