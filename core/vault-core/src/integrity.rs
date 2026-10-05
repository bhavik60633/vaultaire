//! Header and keyslot-table MAC (crypto spec §7.1; vault-format §4.3).

use crate::cbor::{self, Value};
use crate::labels;
use crate::prims;

/// One keyslot row as stored (vault-format §4.2).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct KeyslotRow {
    pub slot_id: [u8; 16],
    pub slot_type: u8,
    pub params: Vec<u8>,
    pub nonce: [u8; 24],
    pub wrapped_vk: Vec<u8>,
}

/// vault-format §4.3: the vault header map.
pub fn vault_header(vault_id: &[u8; 16]) -> Vec<u8> {
    cbor::encode(&Value::map(vec![
        (1, Value::Uint(u64::from(labels::FORMAT_VERSION))),
        (2, Value::Bytes(vault_id.to_vec())),
        (3, Value::Uint(1)),
        (4, Value::Uint(1)),
        (5, Value::Uint(1)),
    ]))
}

/// vault-format §4.3: every row as `[slot_id, slot_type, params, nonce, wrapped_vk]`, sorted by `slot_id`.
pub fn keyslot_table(rows: &[KeyslotRow]) -> Vec<u8> {
    let mut sorted: Vec<&KeyslotRow> = rows.iter().collect();
    sorted.sort_by_key(|r| r.slot_id);
    cbor::encode(&Value::Array(
        sorted
            .into_iter()
            .map(|r| {
                Value::Array(vec![
                    Value::Bytes(r.slot_id.to_vec()),
                    Value::Uint(u64::from(r.slot_type)),
                    Value::Bytes(r.params.clone()),
                    Value::Bytes(r.nonce.to_vec()),
                    Value::Bytes(r.wrapped_vk.clone()),
                ])
            })
            .collect(),
    ))
}

/// §7.1: `HMAC(K_hdr, label ‖ u32(len(header)) ‖ header ‖ keyslot_table)`.
pub fn header_mac(k_hdr: &[u8; 32], header: &[u8], table: &[u8]) -> [u8; 32] {
    let len = u32::try_from(header.len()).unwrap_or(u32::MAX); // headers are tiny; the MAC fails if not
    let mut msg = Vec::with_capacity(labels::HEADER_MAC.len() + 4 + header.len() + table.len());
    msg.extend_from_slice(labels::HEADER_MAC);
    msg.extend_from_slice(&len.to_be_bytes());
    msg.extend_from_slice(header);
    msg.extend_from_slice(table);
    prims::hmac_sha256(k_hdr, &msg)
}

/// Verifies the stored MAC in constant time (§7.1).
pub fn verify_header_mac(k_hdr: &[u8; 32], header: &[u8], table: &[u8], stored: &[u8]) -> bool {
    prims::ct_eq(&header_mac(k_hdr, header, table), stored)
}
