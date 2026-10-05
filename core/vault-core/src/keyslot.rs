//! Keyslots (crypto spec §4; vault-format §4.4): one random vault key wrapped three times.

use crate::cbor::{self, Value};
use crate::error::{Result, VaultError};
use crate::labels::{self, FORMAT_VERSION};
use crate::prims::{self, Key32};

/// Slot types (§4.5).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum SlotType {
    Bio = 1,
    Pwd = 2,
    Rec = 3,
}

/// Argon2id parameters of a password-based slot (vault-format §4.4, `kdf` map).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct KdfParams {
    pub m: u64,
    pub t: u64,
    pub p: u64,
    pub salt: [u8; 16],
}

impl KdfParams {
    pub fn to_value(&self) -> Value {
        Value::map(vec![
            (1, Value::Uint(1)),
            (2, Value::Uint(self.m)),
            (3, Value::Uint(self.t)),
            (4, Value::Uint(self.p)),
            (5, Value::Bytes(self.salt.to_vec())),
        ])
    }
}

/// Hardware security level recorded in BIO and PWD slots.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum HwLevel {
    Tee = 1,
    StrongBox = 2,
}

/// The stored parameters of one slot, before encoding.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SlotParams {
    Bio {
        hw_level: HwLevel,
        alias: String,
        iv: [u8; 12],
        bio_blob: [u8; 48],
    },
    Pwd {
        hw_level: HwLevel,
        alias: String,
        kdf: KdfParams,
    },
    Rec {
        kdf: KdfParams,
    },
}

impl SlotParams {
    pub fn slot_type(&self) -> SlotType {
        match self {
            SlotParams::Bio { .. } => SlotType::Bio,
            SlotParams::Pwd { .. } => SlotType::Pwd,
            SlotParams::Rec { .. } => SlotType::Rec,
        }
    }

    /// The exact stored bytes: deterministic CBOR, hashed into the slot AD (§4.5).
    pub fn encode(&self) -> Vec<u8> {
        let t = Value::Uint(self.slot_type() as u64);
        let v = match self {
            SlotParams::Bio {
                hw_level,
                alias,
                iv,
                bio_blob,
            } => Value::map(vec![
                (1, t),
                (2, Value::Uint(*hw_level as u64)),
                (3, Value::Text(alias.clone())),
                (4, Value::Bytes(iv.to_vec())),
                (5, Value::Bytes(bio_blob.to_vec())),
            ]),
            SlotParams::Pwd {
                hw_level,
                alias,
                kdf,
            } => Value::map(vec![
                (1, t),
                (2, Value::Uint(*hw_level as u64)),
                (3, Value::Text(alias.clone())),
                (6, kdf.to_value()),
            ]),
            SlotParams::Rec { kdf } => Value::map(vec![(1, t), (6, kdf.to_value())]),
        };
        cbor::encode(&v)
    }
}

/// `vlt.v1.bio.<hex(slot_id)>` or `vlt.v1.pwd.<hex(slot_id)>` (vault-format §4.4).
pub fn keystore_alias(slot_type: SlotType, slot_id: &[u8; 16]) -> String {
    let kind = if slot_type == SlotType::Bio {
        "bio"
    } else {
        "pwd"
    };
    let hex: String = slot_id.iter().map(|b| format!("{b:02x}")).collect();
    format!("vlt.v1.{kind}.{hex}")
}

/// §4.5: `AD_slot`.
pub fn ad_slot(
    vault_id: &[u8; 16],
    slot_type: SlotType,
    slot_id: &[u8; 16],
    params_bytes: &[u8],
) -> Vec<u8> {
    let mut ad = Vec::with_capacity(labels::SLOT.len() + 2 + 16 + 1 + 16 + 32);
    ad.extend_from_slice(labels::SLOT);
    ad.extend_from_slice(&FORMAT_VERSION.to_be_bytes());
    ad.extend_from_slice(vault_id);
    ad.push(slot_type as u8);
    ad.extend_from_slice(slot_id);
    ad.extend_from_slice(&prims::sha256(params_bytes));
    ad
}

/// §4.2: `KEK_bio` from the SK that Keystore releases after a biometric.
pub fn kek_bio(sk: &[u8; 32], vault_id: &[u8; 16]) -> Key32 {
    prims::hkdf32(sk, vault_id, labels::KEK_BIO)
}

/// §4.3, first half: `H_pwd` and `hw_in`, the value sent to Keystore for the hardware HMAC.
pub fn pwd_hw_input(
    password: &str,
    kdf: &KdfParams,
    vault_id: &[u8; 16],
) -> Result<(Key32, Key32)> {
    let pw = prims::encode_password(password)?;
    let h_pwd = prims::argon2id(&pw, &kdf.salt, kdf.m, kdf.t, kdf.p)?;
    let hw_in = prims::hkdf32(h_pwd.as_ref(), vault_id, labels::HW_INPUT);
    Ok((h_pwd, hw_in))
}

/// §4.3, second half: `KEK_pwd` from `H_pwd` and the hardware result `HW_pwd`.
pub fn kek_pwd(h_pwd: &[u8; 32], hw_pwd: &[u8; 32], vault_id: &[u8; 16]) -> Key32 {
    let ikm = prims::concat_secret(h_pwd, hw_pwd);
    prims::hkdf32(&ikm, vault_id, labels::KEK_PWD)
}

/// §4.4: `KEK_rec` from the password and the Recovery Secret.
pub fn kek_rec(
    password: &str,
    kdf: &KdfParams,
    rs: &[u8; 16],
    vault_id: &[u8; 16],
) -> Result<(Key32, Key32)> {
    let pw = prims::encode_password(password)?;
    let h_rec = prims::argon2id(&pw, &kdf.salt, kdf.m, kdf.t, kdf.p)?;
    let ikm = prims::concat_secret(h_rec.as_ref(), rs);
    Ok((prims::hkdf32(&ikm, vault_id, labels::KEK_REC), h_rec))
}

/// §4.1: `wrapped_vk = Seal(KEK_slot, nonce, VK, AD_slot)`.
pub fn wrap_vk(kek: &[u8; 32], nonce: &[u8; 24], vk: &[u8; 32], ad: &[u8]) -> Vec<u8> {
    prims::seal(kek, nonce, vk, ad)
}

/// Opens a slot. Every failure is `UnlockFailed` (§11).
pub fn unwrap_vk(kek: &[u8; 32], nonce: &[u8; 24], wrapped: &[u8], ad: &[u8]) -> Result<Key32> {
    let vk = prims::open(kek, nonce, wrapped, ad, VaultError::UnlockFailed)?;
    let mut out = zeroize::Zeroizing::new([0u8; 32]);
    if vk.len() != 32 {
        return Err(VaultError::UnlockFailed);
    }
    out.copy_from_slice(&vk);
    Ok(out)
}
