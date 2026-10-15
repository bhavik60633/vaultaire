//! Encrypted backups (crypto spec §9; vault-format §10).

use zeroize::Zeroizing;

use crate::cbor::{self, Limits, Value};
use crate::error::{Result, VaultError};
use crate::item;
use crate::keyslot::KdfParams;
use crate::labels;
use crate::organisation;
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
    let kek = kek_export(password, kdf, rs)?;
    seal_file(payload, rs, kdf, &kek, rnd)
}

/// §9.5 (PROPOSED, Q9): writes a backup with a cached export key, without the password. The file
/// is byte for byte what [`export`] makes from the same password, Recovery Secret and randomness.
pub fn export_with_key(
    payload: &Value,
    rs: &[u8; 16],
    key: &ExportKey,
    rnd: &ExportRandom,
) -> Result<Vec<u8>> {
    prims::check_kdf_params(key.kdf.m, key.kdf.t, key.kdf.p)?;
    seal_file(payload, rs, &key.kdf, &key.kek, rnd)
}

/// §9.5: `KEK_exp` derived once from the password and Recovery Secret and kept in the vault
/// (vault-format §5.6 key 4) for auto-backup. Its `Debug` output never shows the key.
pub struct ExportKey {
    kdf: KdfParams,
    kek: prims::Key32,
}

impl core::fmt::Debug for ExportKey {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.debug_struct("ExportKey")
            .field("kdf", &self.kdf)
            .field("kek", &"<redacted>")
            .finish()
    }
}

impl ExportKey {
    /// Runs Argon2id once (§9.2) and keeps the result.
    pub fn derive(password: &str, rs: &[u8; 16], kdf: &KdfParams) -> Result<ExportKey> {
        prims::check_kdf_params(kdf.m, kdf.t, kdf.p)?;
        Ok(ExportKey {
            kdf: kdf.clone(),
            kek: kek_export(password, kdf, rs)?,
        })
    }

    /// The Argon2id parameters and `salt_exp` the key was derived with.
    pub fn kdf(&self) -> &KdfParams {
        &self.kdf
    }

    /// The stored form: `{1: kdf, 2: kek}` (vault-format §5.6 key 4).
    pub fn to_value(&self) -> Value {
        Value::map(vec![
            (1, self.kdf.to_value()),
            (2, Value::Bytes(self.kek.to_vec())),
        ])
    }

    /// Reads the stored form strictly. Parameters out of range are refused, so a stored key can
    /// never lower the Argon2id cost of later backups.
    pub fn from_value(value: &Value) -> Result<ExportKey> {
        if !matches!(value, Value::Map(_)) || value.keys() != [1, 2] {
            return Err(VaultError::SchemaViolation);
        }
        let kdf = parse_kdf(value.get(1).ok_or(VaultError::SchemaViolation)?)?;
        prims::check_kdf_params(kdf.m, kdf.t, kdf.p)?;
        let kek: [u8; 32] = value
            .get(2)
            .and_then(Value::as_bytes)
            .and_then(|b| b.try_into().ok())
            .ok_or(VaultError::SchemaViolation)?;
        Ok(ExportKey {
            kdf,
            kek: Zeroizing::new(kek),
        })
    }
}

/// A `kdf` map (vault-format §4.4), checked for shape only.
pub(crate) fn parse_kdf(k: &Value) -> Result<KdfParams> {
    if !matches!(k, Value::Map(_)) || k.keys() != [1, 2, 3, 4, 5] {
        return Err(VaultError::SchemaViolation);
    }
    let uint = |key| {
        k.get(key)
            .and_then(Value::as_uint)
            .ok_or(VaultError::SchemaViolation)
    };
    if uint(1)? != 1 {
        return Err(VaultError::SchemaViolation);
    }
    let salt: [u8; 16] = k
        .get(5)
        .and_then(Value::as_bytes)
        .and_then(|b| b.try_into().ok())
        .ok_or(VaultError::SchemaViolation)?;
    Ok(KdfParams {
        m: uint(2)?,
        t: uint(3)?,
        p: uint(4)?,
        salt,
    })
}

fn seal_file(
    payload: &Value,
    rs: &[u8; 16],
    kdf: &KdfParams,
    kek: &[u8; 32],
    rnd: &ExportRandom,
) -> Result<Vec<u8>> {
    let header = backup_header(kdf, &rs_id(rs));
    let wrapped_fk = prims::seal(
        kek,
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
    check_payload_shape(&payload)?; // 8 (structure only, for now)
    Ok(payload)
}

/// §10.3: keys 1–3, plus key 4 only when it carries custom banks and no device-only last view.
fn check_payload_shape(payload: &Value) -> Result<()> {
    let keys = payload.keys();
    if (keys != [1, 2, 3] && keys != [1, 2, 3, 4])
        || payload.get(1).and_then(Value::as_uint) != Some(1)
    {
        return Err(VaultError::SchemaViolation);
    }
    if let Some(org) = payload.get(4) {
        organisation::validate(org)?;
        if org.get(2).is_some() || organisation::custom_bank_ids(org)?.is_empty() {
            return Err(VaultError::SchemaViolation);
        }
    }
    Ok(())
}

/// Builds a backup payload (vault-format §10.3) from item maps, the settings record and the
/// Organisation record. Device-only data is left out: the backup location (settings key 12) and
/// the last card view (organisation key 2). Key 4 is present only when there are custom banks.
///
/// Every item is checked against the item rules, and every custom bank an item uses must be
/// defined in the organisation, so a backup never refers to a bank it does not carry.
pub fn build_payload(items: Vec<Value>, settings: &Value, organisation: &Value) -> Result<Value> {
    organisation::validate(organisation)?;
    let defined = organisation::custom_bank_ids(organisation)?;
    for entry in &items {
        let (_, index, secret) = item_parts(entry)?;
        item::validate(index, secret)?;
        if let Some(bank) = custom_bank_of(index)
            && !defined.iter().any(|id| id == bank)
        {
            return Err(VaultError::SchemaViolation);
        }
    }

    let settings = match settings {
        Value::Map(entries) => {
            Value::map(entries.iter().filter(|(k, _)| *k != 12).cloned().collect())
        }
        _ => return Err(VaultError::SchemaViolation),
    };
    let mut payload = vec![(1, Value::Uint(1)), (2, Value::Array(items)), (3, settings)];
    if !defined.is_empty() {
        payload.push((4, organisation::without_last_view(organisation)?));
    }
    Ok(Value::map(payload))
}

/// Merges the custom banks of an imported backup into the vault's Organisation record
/// (vault-format §10.4) and rewrites the imported items' bank ids to match.
///
/// An imported bank whose name or an alias matches a catalogue bank maps onto that catalogue id;
/// one matching an existing custom bank maps onto it; any other is added with a fresh id. Nothing
/// in `items` changes unless the whole merge succeeds.
pub fn merge_banks(
    existing: &Value,
    imported: Option<&Value>,
    items: &mut [Value],
    fresh_id: &mut dyn FnMut() -> Result<[u8; 16]>,
) -> Result<Value> {
    organisation::validate(existing)?;
    let mut merged = existing.clone();
    let mut mapping: Vec<(String, String)> = Vec::new();

    if let Some(imported) = imported {
        organisation::validate(imported)?;
        for bank in organisation::custom_banks(imported)? {
            let target = bank
                .spellings()
                .find_map(|spelling| organisation::resolve(&merged, spelling));
            let target = match target {
                Some(id) => id,
                None => {
                    let raw = fresh_id()?;
                    let id = format!(
                        "u:{}",
                        raw.iter().map(|b| format!("{b:02x}")).collect::<String>()
                    );
                    if organisation::custom_bank_ids(&merged)?.contains(&id) {
                        return Err(VaultError::SchemaViolation);
                    }
                    merged = append_bank(&merged, &id, bank.name, &bank.aliases)?;
                    id
                }
            };
            mapping.push((bank.id.to_string(), target));
        }
    }

    let known = organisation::custom_bank_ids(&merged)?;
    let mut rewritten = Vec::with_capacity(items.len());
    for entry in items.iter() {
        let (_, index, _) = item_parts(entry)?;
        let target = match custom_bank_of(index) {
            None => None,
            Some(bank) => match mapping.iter().find(|(from, _)| from == bank) {
                Some((_, to)) => Some(to.clone()),
                None if known.iter().any(|id| id == bank) => None,
                None => return Err(VaultError::SchemaViolation),
            },
        };
        rewritten.push(match target {
            Some(to) => set_bank(entry, &to)?,
            None => entry.clone(),
        });
    }
    for (slot, new) in items.iter_mut().zip(rewritten) {
        *slot = new;
    }
    organisation::validate(&merged)?;
    Ok(merged)
}

/// The id, index and secret of a payload item map (vault-format §10.3).
fn item_parts(entry: &Value) -> Result<(&[u8], &Value, &Value)> {
    if !matches!(entry, Value::Map(_)) || entry.keys() != [1, 2, 3, 4] {
        return Err(VaultError::SchemaViolation);
    }
    let id = entry
        .get(1)
        .and_then(Value::as_bytes)
        .filter(|b| b.len() == 16)
        .ok_or(VaultError::SchemaViolation)?;
    match entry.get(4) {
        Some(Value::Array(history)) if history.len() <= 10 => {}
        _ => return Err(VaultError::SchemaViolation),
    }
    let index = entry.get(2).ok_or(VaultError::SchemaViolation)?;
    let secret = entry.get(3).ok_or(VaultError::SchemaViolation)?;
    Ok((id, index, secret))
}

/// The item's custom bank id (`u:`), if it has one.
fn custom_bank_of(index: &Value) -> Option<&str> {
    match index.get(16) {
        Some(Value::Text(id)) if id.starts_with("u:") => Some(id),
        _ => None,
    }
}

/// A copy of the item map with its index envelope's `bank_id` replaced.
fn set_bank(entry: &Value, bank_id: &str) -> Result<Value> {
    let replace = |map: &Value, key: u64, value: Value| -> Result<Value> {
        match map {
            Value::Map(entries) => {
                let mut out: Vec<(u64, Value)> =
                    entries.iter().filter(|(k, _)| *k != key).cloned().collect();
                out.push((key, value));
                Ok(Value::map(out))
            }
            _ => Err(VaultError::SchemaViolation),
        }
    };
    let index = entry.get(2).ok_or(VaultError::SchemaViolation)?;
    let new_index = replace(index, 16, Value::Text(bank_id.to_string()))?;
    replace(entry, 2, new_index)
}

fn append_bank(org: &Value, id: &str, name: &str, aliases: &[&str]) -> Result<Value> {
    let mut banks = match org.get(1) {
        Some(Value::Array(items)) => items.clone(),
        _ => return Err(VaultError::SchemaViolation),
    };
    let mut bank = vec![
        (1, Value::Text(id.to_string())),
        (2, Value::Text(name.to_string())),
    ];
    if !aliases.is_empty() {
        bank.push((
            3,
            Value::Array(
                aliases
                    .iter()
                    .map(|a| Value::Text((*a).to_string()))
                    .collect(),
            ),
        ));
    }
    banks.push(Value::map(bank));
    let mut entries: Vec<(u64, Value)> = match org {
        Value::Map(entries) => entries.iter().filter(|(k, _)| *k != 1).cloned().collect(),
        _ => return Err(VaultError::SchemaViolation),
    };
    entries.push((1, Value::Array(banks)));
    Ok(Value::map(entries))
}
