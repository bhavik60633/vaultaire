//! The vault's CBOR profile (vault-format §2), on top of `minicbor` (ADR-006).
//!
//! Allowed: unsigned integers, byte strings, UTF-8 text, arrays, maps with unsigned-integer keys,
//! `true` and `false`. Every decode is followed by a canonical check: re-encoding must give back
//! the exact input bytes.

use minicbor::data::Type;
use minicbor::{Decoder, Encoder};

use crate::error::{Result, VaultError};

/// A decoded CBOR value in the vault's profile. Map entries are kept in ascending key order.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Value {
    Uint(u64),
    Bytes(Vec<u8>),
    Text(String),
    Bool(bool),
    Array(Vec<Value>),
    Map(Vec<(u64, Value)>),
}

impl Value {
    /// A map from `(key, value)` pairs in any order; they are sorted, so the encoding is canonical.
    pub fn map(mut entries: Vec<(u64, Value)>) -> Value {
        entries.sort_by_key(|(k, _)| *k);
        Value::Map(entries)
    }

    pub fn get(&self, key: u64) -> Option<&Value> {
        match self {
            Value::Map(entries) => entries.iter().find(|(k, _)| *k == key).map(|(_, v)| v),
            _ => None,
        }
    }

    pub fn as_uint(&self) -> Option<u64> {
        match self {
            Value::Uint(n) => Some(*n),
            _ => None,
        }
    }

    pub fn as_bytes(&self) -> Option<&[u8]> {
        match self {
            Value::Bytes(b) => Some(b),
            _ => None,
        }
    }

    /// The set of keys of a map, in order.
    pub fn keys(&self) -> Vec<u64> {
        match self {
            Value::Map(entries) => entries.iter().map(|(k, _)| *k).collect(),
            _ => Vec::new(),
        }
    }
}

/// Deterministic encoding (RFC 8949 §4.2.1): shortest forms, definite lengths, sorted keys.
pub fn encode(value: &Value) -> Vec<u8> {
    let mut e = Encoder::new(Vec::new());
    write(&mut e, value);
    e.into_writer()
}

fn write(e: &mut Encoder<Vec<u8>>, value: &Value) {
    // Writing into a Vec cannot fail.
    let infallible = |r: core::result::Result<&mut Encoder<Vec<u8>>, _>| {
        r.unwrap_or_else(|_: minicbor::encode::Error<core::convert::Infallible>| unreachable!());
    };
    match value {
        Value::Uint(n) => infallible(e.u64(*n)),
        Value::Bytes(b) => infallible(e.bytes(b)),
        Value::Text(t) => infallible(e.str(t)),
        Value::Bool(b) => infallible(e.bool(*b)),
        Value::Array(items) => {
            infallible(e.array(items.len() as u64));
            for item in items {
                write(e, item);
            }
        }
        Value::Map(entries) => {
            let mut sorted: Vec<&(u64, Value)> = entries.iter().collect();
            sorted.sort_by_key(|(k, _)| *k); // for unsigned keys, numeric order is bytewise order
            infallible(e.map(sorted.len() as u64));
            for (k, v) in sorted {
                infallible(e.u64(*k));
                write(e, v);
            }
        }
    }
}

/// Decoding limits (vault-format §2).
#[derive(Debug, Clone, Copy)]
pub struct Limits {
    /// Nested arrays and maps; plain values do not count (S-3).
    pub max_depth: usize,
    pub max_entries: u64,
    pub max_string: usize,
}

impl Limits {
    pub const HEADER: Limits = Limits {
        max_depth: 4,
        max_entries: 16,
        max_string: 65_536,
    };
    pub const RECORD: Limits = Limits {
        max_depth: 6,
        max_entries: 1_000,
        max_string: 65_536,
    };
    pub const PAYLOAD: Limits = Limits {
        max_depth: 6,
        max_entries: 10_000,
        max_string: 65_536,
    };
}

/// Strict decoding plus the canonical check.
pub fn decode(data: &[u8], limits: Limits) -> Result<Value> {
    let mut d = Decoder::new(data);
    let value = read(&mut d, 0, limits)?;
    if d.position() != data.len() {
        return Err(VaultError::Malformed); // trailing bytes
    }
    if encode(&value) != data {
        return Err(VaultError::NonCanonical);
    }
    Ok(value)
}

fn read(d: &mut Decoder<'_>, depth: usize, limits: Limits) -> Result<Value> {
    let bad = |_| VaultError::Malformed;
    match d.datatype().map_err(bad)? {
        Type::U8 | Type::U16 | Type::U32 | Type::U64 => Ok(Value::Uint(d.u64().map_err(bad)?)),
        Type::Bool => Ok(Value::Bool(d.bool().map_err(bad)?)),
        Type::Bytes => {
            let b = d.bytes().map_err(bad)?;
            if b.len() > limits.max_string {
                return Err(VaultError::TooLarge);
            }
            Ok(Value::Bytes(b.to_vec()))
        }
        Type::String => {
            let s = d.str().map_err(bad)?; // rejects invalid UTF-8
            if s.len() > limits.max_string {
                return Err(VaultError::TooLarge);
            }
            Ok(Value::Text(s.to_owned()))
        }
        Type::Array => {
            if depth + 1 > limits.max_depth {
                return Err(VaultError::Malformed);
            }
            let n = d.array().map_err(bad)?.ok_or(VaultError::Malformed)?; // None = indefinite
            if n > limits.max_entries {
                return Err(VaultError::TooLarge);
            }
            (0..n)
                .map(|_| read(d, depth + 1, limits))
                .collect::<Result<Vec<_>>>()
                .map(Value::Array)
        }
        Type::Map => {
            if depth + 1 > limits.max_depth {
                return Err(VaultError::Malformed);
            }
            let n = d.map().map_err(bad)?.ok_or(VaultError::Malformed)?;
            if n > limits.max_entries {
                return Err(VaultError::TooLarge);
            }
            let mut entries: Vec<(u64, Value)> = Vec::new();
            for _ in 0..n {
                let key = match read(d, depth + 1, limits)? {
                    Value::Uint(k) => k,
                    _ => return Err(VaultError::Malformed), // map keys must be unsigned integers
                };
                if entries.last().is_some_and(|(prev, _)| key <= *prev) {
                    return Err(VaultError::NonCanonical); // unsorted or duplicate keys
                }
                let value = read(d, depth + 1, limits)?;
                entries.push((key, value));
            }
            Ok(Value::Map(entries))
        }
        // Negative integers, floats, tags, null, undefined, other simple values, indefinite lengths.
        _ => Err(VaultError::Malformed),
    }
}
