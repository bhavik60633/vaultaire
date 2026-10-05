//! Primitives (crypto spec §2, §3.1, §3.2), each a thin call into a RustCrypto crate (ADR-003).

use argon2::{Algorithm, Argon2, Params, Version};
use chacha20poly1305::aead::{Aead, Payload};
use chacha20poly1305::{KeyInit, XChaCha20Poly1305, XNonce};
use hkdf::Hkdf;
use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;
use unicode_normalization::UnicodeNormalization;
use zeroize::Zeroizing;

use crate::error::{Result, VaultError};

/// A 32-byte secret that is wiped when dropped.
pub type Key32 = Zeroizing<[u8; 32]>;

/// Accepted Argon2id range (§4.6), checked before any Argon2id work.
pub const M_MIN: u32 = 65_536;
pub const M_MAX: u32 = 524_288;
pub const T_MIN: u32 = 3;
pub const T_MAX: u32 = 16;
pub const P_MIN: u32 = 1;
pub const P_MAX: u32 = 4;

/// SHA-256.
pub fn sha256(data: &[u8]) -> [u8; 32] {
    Sha256::digest(data).into()
}

/// HMAC-SHA256.
pub fn hmac_sha256(key: &[u8], msg: &[u8]) -> [u8; 32] {
    // HMAC accepts any key length, so this cannot fail.
    let mut mac = <Hmac<Sha256> as KeyInit>::new_from_slice(key).unwrap_or_else(|_| unreachable!());
    mac.update(msg);
    mac.finalize().into_bytes().into()
}

/// HKDF-SHA256 into a buffer of any length up to 255 × 32 bytes.
pub fn hkdf(ikm: &[u8], salt: &[u8], info: &[u8], out: &mut [u8]) {
    // An empty salt equals "no salt" (§3.1, S-8); only an over-long output can fail, and every
    // output length in the spec is fixed and short.
    Hkdf::<Sha256>::new(Some(salt), ikm)
        .expand(info, out)
        .unwrap_or_else(|_| unreachable!("HKDF output length is fixed and valid"));
}

/// HKDF-SHA256 with a 32-byte output held in a zeroizing buffer.
pub fn hkdf32(ikm: &[u8], salt: &[u8], info: &[u8]) -> Key32 {
    let mut out = Zeroizing::new([0u8; 32]);
    hkdf(ikm, salt, info, out.as_mut());
    out
}

fn cipher(key: &[u8; 32]) -> XChaCha20Poly1305 {
    XChaCha20Poly1305::new_from_slice(key).unwrap_or_else(|_| unreachable!("key is 32 bytes"))
}

/// `Seal(K, N, P, AD)`: XChaCha20-Poly1305, returning ciphertext ‖ 16-byte tag.
pub fn seal(key: &[u8; 32], nonce: &[u8; 24], plaintext: &[u8], ad: &[u8]) -> Vec<u8> {
    let n = XNonce::try_from(&nonce[..]).unwrap_or_else(|_| unreachable!("nonce is 24 bytes"));
    cipher(key)
        .encrypt(
            &n,
            Payload {
                msg: plaintext,
                aad: ad,
            },
        )
        .unwrap_or_else(|_| unreachable!("encryption cannot fail for in-limit messages"))
}

/// `Open(K, N, C, AD)`: the plaintext only if the tag verifies; otherwise `on_failure`.
pub fn open(
    key: &[u8; 32],
    nonce: &[u8; 24],
    ct: &[u8],
    ad: &[u8],
    on_failure: VaultError,
) -> Result<Zeroizing<Vec<u8>>> {
    let n = XNonce::try_from(&nonce[..]).unwrap_or_else(|_| unreachable!("nonce is 24 bytes"));
    cipher(key)
        .decrypt(&n, Payload { msg: ct, aad: ad })
        .map(Zeroizing::new)
        .map_err(|_| on_failure)
}

/// §4.6: reject parameters outside the accepted range before any work.
pub fn check_kdf_params(m: u64, t: u64, p: u64) -> Result<(u32, u32, u32)> {
    let ok = (u64::from(M_MIN)..=u64::from(M_MAX)).contains(&m)
        && (u64::from(T_MIN)..=u64::from(T_MAX)).contains(&t)
        && (u64::from(P_MIN)..=u64::from(P_MAX)).contains(&p);
    if !ok {
        return Err(VaultError::ParamsOutOfRange);
    }
    // In range, so each fits in u32.
    Ok((m as u32, t as u32, p as u32))
}

/// `Argon2id(P, S, m, t, p)`: 32-byte output, version 0x13, no secret, no associated data.
pub fn argon2id(password: &[u8], salt: &[u8], m: u64, t: u64, p: u64) -> Result<Key32> {
    let (m, t, p) = check_kdf_params(m, t, p)?;
    let params = Params::new(m, t, p, Some(32)).map_err(|_| VaultError::ParamsOutOfRange)?;
    let mut out = Zeroizing::new([0u8; 32]);
    Argon2::new(Algorithm::Argon2id, Version::V0x13, params)
        .hash_password_into(password, salt, out.as_mut())
        .map_err(|_| VaultError::ParamsOutOfRange)?;
    Ok(out)
}

/// §3.2: normalise to NFC, encode as UTF-8, and reject an empty result or one over 1024 bytes.
pub fn encode_password(password: &str) -> Result<Zeroizing<Vec<u8>>> {
    let normalised: Zeroizing<String> = Zeroizing::new(password.nfc().collect());
    let bytes = Zeroizing::new(normalised.as_bytes().to_vec());
    if bytes.is_empty() || bytes.len() > 1024 {
        return Err(VaultError::PasswordLength);
    }
    Ok(bytes)
}

/// Bytes from the operating system's random generator. There is no fallback (§2.1).
pub fn random<const N: usize>() -> Result<[u8; N]> {
    let mut buf = [0u8; N];
    getrandom::fill(&mut buf).map_err(|_| VaultError::RandomUnavailable)?;
    Ok(buf)
}

/// Two secrets joined into one zeroizing buffer (for `H ‖ HW_pwd`, `H ‖ RS` and similar inputs).
pub fn concat_secret(a: &[u8], b: &[u8]) -> Zeroizing<Vec<u8>> {
    let mut v = Zeroizing::new(Vec::with_capacity(a.len() + b.len()));
    v.extend_from_slice(a);
    v.extend_from_slice(b);
    v
}

/// Constant-time equality for secrets, MACs and checksums (ADR-004).
pub fn ct_eq(a: &[u8], b: &[u8]) -> bool {
    a.ct_eq(b).into()
}
