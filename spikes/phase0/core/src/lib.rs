//! Phase-0 spike: THROWAWAY CODE.
//!
//! Proves that Rust can be called from an Android app through UniFFI on this machine,
//! and measures Argon2id on a real phone. Not product code: nothing here may be copied
//! into `vault-core`. See ../README.md.

uniffi::setup_scaffolding!();

use std::fmt;
use std::time::Instant;

use argon2::{Algorithm, Argon2, Params, Version};
use chacha20poly1305::aead::Aead;
use chacha20poly1305::{KeyInit, XChaCha20Poly1305, XNonce};

#[derive(Debug, uniffi::Error)]
#[uniffi(flat_error)]
pub enum SpikeError {
    Random,
    Crypto,
    Params,
}

impl fmt::Display for SpikeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let msg = match self {
            SpikeError::Random => "the OS random number generator failed",
            SpikeError::Crypto => "a cryptographic operation failed",
            SpikeError::Params => "invalid Argon2id parameters",
        };
        f.write_str(msg)
    }
}

/// Timing of one Argon2id run.
#[derive(uniffi::Record)]
pub struct Argon2Timing {
    pub millis: u64,
    pub memory_kib: u32,
    pub passes: u32,
    pub lanes: u32,
}

fn random<const N: usize>() -> Result<[u8; N], SpikeError> {
    let mut buf = [0u8; N];
    getrandom::fill(&mut buf).map_err(|_| SpikeError::Random)?;
    Ok(buf)
}

#[uniffi::export]
pub fn core_version() -> String {
    format!("spike_core {}", env!("CARGO_PKG_VERSION"))
}

/// Encrypts a random message with a random key and nonce, decrypts it, and checks that a
/// ciphertext with one flipped bit is rejected. Returns true only if all three hold.
#[uniffi::export]
pub fn aead_roundtrip() -> Result<bool, SpikeError> {
    let key: [u8; 32] = random()?;
    let nonce_bytes: [u8; 24] = random()?;
    let message: [u8; 64] = random()?;

    let cipher = XChaCha20Poly1305::new_from_slice(&key).map_err(|_| SpikeError::Crypto)?;
    let nonce = XNonce::try_from(&nonce_bytes[..]).map_err(|_| SpikeError::Crypto)?;

    let sealed = cipher.encrypt(&nonce, &message[..]).map_err(|_| SpikeError::Crypto)?;
    let opened = cipher.decrypt(&nonce, &sealed[..]).map_err(|_| SpikeError::Crypto)?;

    let mut tampered = sealed.clone();
    tampered[0] ^= 0x01;
    let tamper_rejected = cipher.decrypt(&nonce, &tampered[..]).is_err();

    Ok(opened == message && tamper_rejected)
}

/// Runs Argon2id once with the given cost and returns how long it took.
/// Provisional vault settings (crypto-spec §4.6): 65 536 KiB, 3 passes, 1 lane.
#[uniffi::export]
pub fn argon2id_timing(memory_kib: u32, passes: u32, lanes: u32) -> Result<Argon2Timing, SpikeError> {
    let params = Params::new(memory_kib, passes, lanes, Some(32)).map_err(|_| SpikeError::Params)?;
    let argon2 = Argon2::new(Algorithm::Argon2id, Version::V0x13, params);
    let salt: [u8; 16] = random()?;
    let mut out = [0u8; 32];

    let start = Instant::now();
    argon2
        .hash_password_into(b"correct horse battery staple", &salt, &mut out)
        .map_err(|_| SpikeError::Crypto)?;
    let millis = start.elapsed().as_millis() as u64;

    Ok(Argon2Timing { millis, memory_kib, passes, lanes })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn aead_roundtrip_passes() {
        assert!(aead_roundtrip().unwrap());
    }

    #[test]
    fn argon2id_runs_at_small_cost() {
        let t = argon2id_timing(8 * 1024, 1, 1).unwrap();
        assert_eq!(t.memory_kib, 8 * 1024);
    }
}
