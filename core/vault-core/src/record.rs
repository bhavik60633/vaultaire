//! Record encryption (crypto spec §6): subkeys, envelopes, padding and record AD.

use crate::cbor::{self, Limits, Value};
use crate::error::{Result, VaultError};
use crate::labels::{self, FORMAT_VERSION, SCHEMA_VERSION};
use crate::prims::{self, Key32};

/// `record_kind` (§6.4; vault-format §4.5).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum RecordKind {
    Item = 1,
    History = 2,
    Settings = 3,
    Recovery = 4,
    SecurityLog = 5,
    Manifest = 6,
    Organisation = 7,
}

/// `envelope_kind` (§6.2).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum EnvelopeKind {
    Index = 1,
    Secret = 2,
    Meta = 3,
}

impl EnvelopeKind {
    /// Padding bucket (§6.2).
    pub fn bucket(self) -> usize {
        match self {
            EnvelopeKind::Index => 64,
            EnvelopeKind::Secret | EnvelopeKind::Meta => 256,
        }
    }
}

/// The four subkeys derived from VK (§6.1).
#[derive(Debug)]
pub struct Subkeys {
    pub k_idx: Key32,
    pub k_sec: Key32,
    pub k_meta: Key32,
    pub k_hdr: Key32,
}

impl Subkeys {
    pub fn derive(vk: &[u8; 32], vault_id: &[u8; 16]) -> Subkeys {
        Subkeys {
            k_idx: prims::hkdf32(vk, vault_id, labels::SUB_INDEX),
            k_sec: prims::hkdf32(vk, vault_id, labels::SUB_SECRET),
            k_meta: prims::hkdf32(vk, vault_id, labels::SUB_META),
            k_hdr: prims::hkdf32(vk, vault_id, labels::SUB_HEADER),
        }
    }

    pub fn for_envelope(&self, kind: EnvelopeKind) -> &[u8; 32] {
        match kind {
            EnvelopeKind::Index => &self.k_idx,
            EnvelopeKind::Secret => &self.k_sec,
            EnvelopeKind::Meta => &self.k_meta,
        }
    }
}

/// §6.5: append 0x80, then the fewest 0x00 bytes to reach a multiple of the bucket.
pub fn pad(data: &[u8], bucket: usize) -> Vec<u8> {
    let mut out = Vec::with_capacity(data.len() + bucket);
    out.extend_from_slice(data);
    out.push(0x80);
    let rem = out.len() % bucket;
    if rem != 0 {
        out.resize(out.len() + bucket - rem, 0);
    }
    out
}

/// §6.5: strict unpadding. Anything but zeros after exactly one 0x80, at a bucket length, fails.
pub fn unpad(data: &[u8], bucket: usize) -> Result<&[u8]> {
    if data.is_empty() || !data.len().is_multiple_of(bucket) {
        return Err(VaultError::AuthFailed);
    }
    let end = data
        .iter()
        .rposition(|&b| b != 0)
        .ok_or(VaultError::AuthFailed)?;
    match data.get(end) {
        Some(0x80) => data.get(..end).ok_or(VaultError::AuthFailed),
        _ => Err(VaultError::AuthFailed),
    }
}

/// §6.4: `AD_record`.
pub fn ad_record(
    vault_id: &[u8; 16],
    kind: RecordKind,
    record_id: &[u8; 16],
    parent_id: &[u8; 16],
    env: EnvelopeKind,
) -> Vec<u8> {
    let mut ad = Vec::with_capacity(labels::RECORD.len() + 2 + 16 + 1 + 16 + 16 + 1 + 2);
    ad.extend_from_slice(labels::RECORD);
    ad.extend_from_slice(&FORMAT_VERSION.to_be_bytes());
    ad.extend_from_slice(vault_id);
    ad.push(kind as u8);
    ad.extend_from_slice(record_id);
    ad.extend_from_slice(parent_id);
    ad.push(env as u8);
    ad.extend_from_slice(&SCHEMA_VERSION.to_be_bytes());
    ad
}

/// §6.2: `Seal(key, nonce, pad(CBOR(record)), AD)`.
pub fn seal_record(
    key: &[u8; 32],
    nonce: &[u8; 24],
    record: &Value,
    env: EnvelopeKind,
    ad: &[u8],
) -> Vec<u8> {
    let plain = zeroize::Zeroizing::new(pad(&cbor::encode(record), env.bucket()));
    prims::seal(key, nonce, &plain, ad)
}

/// Opens and decodes a record: authenticate, unpad, decode strictly (canonical check included).
pub fn open_record(
    key: &[u8; 32],
    nonce: &[u8; 24],
    ct: &[u8],
    env: EnvelopeKind,
    ad: &[u8],
) -> Result<Value> {
    let plain = prims::open(key, nonce, ct, ad, VaultError::AuthFailed)?;
    cbor::decode(unpad(&plain, env.bucket())?, Limits::RECORD)
}
