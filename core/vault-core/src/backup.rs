//! Encrypted backups (crypto spec §9; vault-format §10).

use zeroize::Zeroizing;

use crate::cbor::{self, Limits, Value};
use crate::error::{Result, VaultError};
use crate::keyslot::KdfParams;
use crate::labels;
use crate::prims;
use crate::record;

/// `VLTBK` then `00 01 00` (vault-format §10.1).
pub const MAGIC: [u8; 8] = *b"VLTBK\x00\x01\x00";
pub const MAX_FILE: usize = 64 * 1024 * 1024;
pub const MAX_HEADER: usize = 4096;
const BUCKET: usize = 4096;
const FIXED_AFTER_HEADER: usize = 24 + 48 + 24 + 16; // nonces, wrapped FK, payload tag

/// §9.2: `rs_id = HKDF(RS, zeros(0), label, 8)` (as written; reviewer question Q5 proposes a salt).
pub fn rs_id(rs: &[u8; 16]) -> [u8; 8] {
    let mut out = [0u8; 8];
    prims::hkdf(rs, &[], labels::RS_ID, &mut out);
    out
}

fn backup_header(kdf: &KdfParams, rs_id: &[u8; 8]) -> Vec<u8> {
    cbor::encode(&Value::map(vec![
        (1, Value::Uint(1)),
        (2, kdf.to_value()),
        (3, Value::Bytes(rs_id.to_vec())),
        (4, Value::Uint(1)),
    ]))
}

fn associated_data(label: &[u8], header: &[u8]) -> Vec<u8> {
    let len = u32::try_from(header.len()).unwrap_or(u32::MAX);
    let mut ad = Vec::with_capacity(label.len() + 8 + 4 + header.len());
    ad.extend_from_slice(label);
    ad.extend_from_slice(&MAGIC);
    ad.extend_from_slice(&len.to_be_bytes());
    ad.extend_from_slice(header);
    ad
}

fn kek_export(password: &str, kdf: &KdfParams, rs: &[u8; 16]) -> Result<prims::Key32> {
    let pw = prims::encode_password(password)?;
    let h_exp = prims::argon2id(&pw, &kdf.salt, kdf.m, kdf.t, kdf.p)?;
    let ikm = prims::concat_secret(h_exp.as_ref(), rs);
    Ok(prims::hkdf32(&ikm, &kdf.salt, labels::KEK_EXPORT))
}

/// Randomness for one export, supplied by the caller so that tests are deterministic.
#[derive(Debug)]
pub struct ExportRandom {
    pub fk: Zeroizing<[u8; 32]>,
    pub nonce_wrap: [u8; 24],
    pub nonce_payload: [u8; 24],
}

impl ExportRandom {
    pub fn fresh() -> Result<ExportRandom> {
        Ok(ExportRandom {
            fk: Zeroizing::new(prims::random()?),
            nonce_wrap: prims::random()?,
            nonce_payload: prims::random()?,
        })
    }
}

/// §9.3: writes a complete `.vaultbackup` file.
pub fn export(
    payload: &Value,
    password: &str,
    rs: &[u8; 16],
    kdf: &KdfParams,
    rnd: &ExportRandom,
) -> Result<Vec<u8>> {
    prims::check_kdf_params(kdf.m, kdf.t, kdf.p)?;
    let header = backup_header(kdf, &rs_id(rs));
    let kek = kek_export(password, kdf, rs)?;
    let wrapped_fk = prims::seal(
        &kek,
        &rnd.nonce_wrap,
        rnd.fk.as_ref(),
        &associated_data(labels::EXPORT_WRAP, &header),
    );
    let plain = Zeroizing::new(record::pad(&cbor::encode(payload), BUCKET));
    let payload_ct = prims::seal(
        &rnd.fk,
        &rnd.nonce_payload,
        &plain,
        &associated_data(labels::EXPORT_PAYLOAD, &header),
    );
    let len = u32::try_from(header.len()).map_err(|_| VaultError::TooLarge)?;
    let mut file = Vec::with_capacity(12 + header.len() + FIXED_AFTER_HEADER + payload_ct.len());
    file.extend_from_slice(&MAGIC);
    file.extend_from_slice(&len.to_be_bytes());
    file.extend_from_slice(&header);
    file.extend_from_slice(&rnd.nonce_wrap);
    file.extend_from_slice(&wrapped_fk);
    file.extend_from_slice(&rnd.nonce_payload);
    file.extend_from_slice(&payload_ct);
    Ok(file)
}

fn parse_header(h: &Value) -> Result<(KdfParams, [u8; 8])> {
    if h.keys() != [1, 2, 3, 4] {
        return Err(VaultError::MalformedHeader);
    }
    if h.get(1).and_then(Value::as_uint) != Some(1) {
        return Err(VaultError::UnsupportedVersion);
    }
    let k = h.get(2).ok_or(VaultError::MalformedHeader)?;
    let uint = |key| {
        k.get(key)
            .and_then(Value::as_uint)
            .ok_or(VaultError::MalformedHeader)
    };
    let salt: [u8; 16] = k
        .get(5)
        .and_then(Value::as_bytes)
        .and_then(|b| b.try_into().ok())
        .ok_or(VaultError::MalformedHeader)?;
    let rs_id: [u8; 8] = h
        .get(3)
        .and_then(Value::as_bytes)
        .and_then(|b| b.try_into().ok())
        .ok_or(VaultError::MalformedHeader)?;
    if k.keys() != [1, 2, 3, 4, 5] || uint(1)? != 1 || h.get(4).and_then(Value::as_uint) != Some(1)
    {
        return Err(VaultError::MalformedHeader);
    }
    Ok((
        KdfParams {
            m: uint(2)?,
            t: uint(3)?,
            p: uint(4)?,
            salt,
        },
        rs_id,
    ))
}

fn slice(data: &[u8], from: usize, len: usize) -> Result<&[u8]> {
    data.get(from..from + len).ok_or(VaultError::Truncated)
}

/// §9.4 import order. `total_ram` enables the proposed memory cap (Q8). Returns the payload.
pub fn import(data: &[u8], password: &str, rs: &[u8; 16], total_ram: Option<u64>) -> Result<Value> {
    if data.len() > MAX_FILE {
        return Err(VaultError::TooLarge); // 1
    }
    if data.len() < 12 {
        return Err(VaultError::Truncated);
    }
    if slice(data, 0, 8)? != MAGIC {
        return Err(VaultError::NotABackup); // 2
    }
    let hdr_len = u32::from_be_bytes(
        slice(data, 8, 4)?
            .try_into()
            .map_err(|_| VaultError::Truncated)?,
    ) as usize;
    if hdr_len > MAX_HEADER {
        return Err(VaultError::TooLarge); // 3
    }
    if data.len() < 12 + hdr_len + FIXED_AFTER_HEADER {
        return Err(VaultError::Truncated); // S-9
    }
    let header = slice(data, 12, hdr_len)?;
    let h = cbor::decode(header, Limits::HEADER).map_err(|_| VaultError::MalformedHeader)?;
    let (kdf, stored_rs_id) = parse_header(&h)?;
    prims::check_kdf_params(kdf.m, kdf.t, kdf.p)?; // 4
    if let Some(ram) = total_ram {
        let cap = (512u64 * 1024 * 1024).min(ram / 4);
        if kdf.m.saturating_mul(1024) > cap {
            return Err(VaultError::InsufficientMemory); // Q8 (proposed)
        }
    }
    if !prims::ct_eq(&stored_rs_id, &rs_id(rs)) {
        return Err(VaultError::WrongRecoveryKey); // 5
    }
    let pos = 12 + hdr_len;
    let nonce_wrap: [u8; 24] = slice(data, pos, 24)?
        .try_into()
        .map_err(|_| VaultError::Truncated)?;
    let wrapped_fk = slice(data, pos + 24, 48)?;
    let nonce_payload: [u8; 24] = slice(data, pos + 72, 24)?
        .try_into()
        .map_err(|_| VaultError::Truncated)?;
    let payload_ct = data.get(pos + 96..).ok_or(VaultError::Truncated)?;

    let kek = kek_export(password, &kdf, rs)?; // 6
    let fk_plain = prims::open(
        &kek,
        &nonce_wrap,
        wrapped_fk,
        &associated_data(labels::EXPORT_WRAP, header),
        VaultError::AuthFailed,
    )?;
    let fk: [u8; 32] = fk_plain
        .as_slice()
        .try_into()
        .map_err(|_| VaultError::AuthFailed)?;
    let fk = Zeroizing::new(fk);
    let plain = prims::open(
        &fk,
        &nonce_payload,
        payload_ct,
        &associated_data(labels::EXPORT_PAYLOAD, header),
        VaultError::AuthFailed,
    )?; // 7
    let payload = cbor::decode(record::unpad(&plain, BUCKET)?, Limits::PAYLOAD)?;
    if payload.keys() != [1, 2, 3] || payload.get(1).and_then(Value::as_uint) != Some(1) {
        return Err(VaultError::SchemaViolation); // 8 (structure only, for now)
    }
    Ok(payload)
}
