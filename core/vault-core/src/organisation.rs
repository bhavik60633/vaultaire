//! The Organisation record (vault-format §5.9; ADR-011 decision 3): banks the user adds
//! themselves, and the last card view restored after unlock.
//!
//! No two custom banks may share a normalised name or alias. A custom bank that a later catalogue
//! also lists stays valid, so an app update never invalidates a vault: the duplicate check against
//! the catalogue happens when a bank is added ([`add_custom_bank`]), and name lookups check the
//! catalogue first ([`resolve`]).

use std::collections::{HashMap, HashSet};

use crate::banks::{self, Catalogue};
use crate::cbor::Value;
use crate::error::{Result, VaultError};

/// At most this many custom banks.
pub const MAX_CUSTOM_BANKS: usize = 200;
/// At most this many aliases per custom bank.
pub const MAX_ALIASES: usize = 16;
/// Longest name or alias, in bytes.
pub const MAX_NAME: usize = 64;

/// `last_card_view` scopes other than a bank id.
pub const SCOPE_ALL: &str = "all";
pub const SCOPE_UNASSIGNED: &str = "unassigned";
/// `last_card_view` filters: 0 All, 1 Credit, 2 Debit.
pub const MAX_FILTER: u64 = 2;

/// The outcome of [`add_custom_bank`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Added {
    /// The name was new: the updated record and the new bank's id.
    New { organisation: Value, id: String },
    /// The name already belongs to this bank (catalogue or custom); nothing was added.
    Existing(String),
}

/// An Organisation record with no custom banks and no last view.
pub fn empty() -> Value {
    Value::map(vec![(1, Value::Array(Vec::new()))])
}

/// Checks a record against vault-format §5.9.
pub fn validate(org: &Value) -> Result<()> {
    let entries = map(org)?;
    ensure(entries.iter().all(|(k, _)| *k == 1 || *k == 2))?;
    let banks = custom_banks(org)?;
    ensure(banks.len() <= MAX_CUSTOM_BANKS)?;

    let mut ids = HashSet::new();
    let mut owners: HashMap<String, &str> = HashMap::new();
    for bank in &banks {
        ensure(ids.insert(bank.id))?;
        for spelling in bank.spellings() {
            let key = banks::normalise(spelling);
            ensure(!key.is_empty())?;
            if let Some(owner) = owners.insert(key, bank.id) {
                ensure(owner == bank.id)?;
            }
        }
    }

    if let Some(view) = org.get(2) {
        let view_entries = map(view)?;
        ensure(view_entries.iter().map(|(k, _)| *k).eq([1, 2]))?;
        let scope = text(req(view, 1)?)?;
        let filter = uint(req(view, 2)?)?;
        ensure(valid_view(scope, filter))?;
    }
    Ok(())
}

/// Adds a bank the user typed, unless that name already belongs to a bank.
///
/// `random_id` comes from the CSPRNG; the new id is `u:` followed by its lowercase hex.
pub fn add_custom_bank(org: &Value, name: &str, random_id: [u8; 16]) -> Result<Added> {
    validate(org)?;
    let name = name.trim();
    ensure(!name.is_empty() && name.len() <= MAX_NAME && !banks::normalise(name).is_empty())?;
    if let Some(existing) = resolve(org, name) {
        return Ok(Added::Existing(existing));
    }

    let id = format!("u:{}", hex(&random_id));
    let mut banks_list = match org.get(1) {
        Some(Value::Array(items)) => items.clone(),
        _ => return Err(VaultError::SchemaViolation),
    };
    ensure(
        !banks_list
            .iter()
            .any(|b| b.get(1) == Some(&Value::Text(id.clone()))),
    )?;
    banks_list.push(Value::map(vec![
        (1, Value::Text(id.clone())),
        (2, Value::Text(name.to_string())),
    ]));

    let updated = with_key(org, 1, Value::Array(banks_list))?;
    validate(&updated)?;
    Ok(Added::New {
        organisation: updated,
        id,
    })
}

/// The bank id for a typed or scanned name: the catalogue first, then the user's own banks.
pub fn resolve(org: &Value, name: &str) -> Option<String> {
    let key = banks::normalise(name);
    if key.is_empty() {
        return None;
    }
    if let Some(bank) = Catalogue::bundled().lookup(name) {
        return Some(bank.id.to_string());
    }
    custom_banks(org)
        .ok()?
        .into_iter()
        .find(|bank| bank.spellings().any(|s| banks::normalise(s) == key))
        .map(|bank| bank.id.to_string())
}

/// The name to show for a bank id: the catalogue's display name, the user's name for a custom
/// bank, or the code in capitals for a catalogue id this build does not know (vault-format §5.1).
pub fn display_name(org: &Value, bank_id: &str) -> Option<String> {
    if !banks::is_valid_bank_id(bank_id) {
        return None;
    }
    if let Some(code) = bank_id.strip_prefix("in.") {
        return Some(match Catalogue::bundled().get(bank_id) {
            Some(bank) => bank.name.to_string(),
            None => code.to_uppercase(),
        });
    }
    custom_banks(org)
        .ok()?
        .into_iter()
        .find(|bank| bank.id == bank_id)
        .map(|bank| bank.name.to_string())
}

/// Stores the bank (or `all`, `unassigned`) and filter last looked at.
pub fn set_last_card_view(org: &Value, scope: &str, filter: u64) -> Result<Value> {
    validate(org)?;
    ensure(valid_view(scope, filter))?;
    let view = Value::map(vec![
        (1, Value::Text(scope.to_string())),
        (2, Value::Uint(filter)),
    ]);
    with_key(org, 2, view)
}

/// The stored last card view, if any.
pub fn last_card_view(org: &Value) -> Option<(String, u64)> {
    let view = org.get(2)?;
    match (view.get(1), view.get(2)) {
        (Some(Value::Text(scope)), Some(Value::Uint(filter))) => Some((scope.clone(), *filter)),
        _ => None,
    }
}

/// The record without the device-only last view, as it goes into a backup (vault-format §10.3).
pub fn without_last_view(org: &Value) -> Result<Value> {
    let entries = map(org)?;
    Ok(Value::map(
        entries.iter().filter(|(k, _)| *k != 2).cloned().collect(),
    ))
}

/// Every custom bank id in the record.
pub fn custom_bank_ids(org: &Value) -> Result<Vec<String>> {
    Ok(custom_banks(org)?
        .into_iter()
        .map(|bank| bank.id.to_string())
        .collect())
}

/// One custom bank, borrowed from the record.
pub(crate) struct CustomBank<'a> {
    pub(crate) id: &'a str,
    pub(crate) name: &'a str,
    pub(crate) aliases: Vec<&'a str>,
}

impl<'a> CustomBank<'a> {
    pub(crate) fn spellings(&self) -> impl Iterator<Item = &'a str> + '_ {
        std::iter::once(self.name).chain(self.aliases.iter().copied())
    }
}

/// The custom banks, each checked for shape (vault-format §5.9 custom-bank map).
pub(crate) fn custom_banks(org: &Value) -> Result<Vec<CustomBank<'_>>> {
    let list = match req(org, 1)? {
        Value::Array(items) => items,
        _ => return Err(VaultError::SchemaViolation),
    };
    list.iter().map(custom_bank).collect()
}

fn custom_bank(bank: &Value) -> Result<CustomBank<'_>> {
    ensure(map(bank)?.iter().all(|(k, _)| (1..=3).contains(k)))?;
    let id = text(req(bank, 1)?)?;
    ensure(id.starts_with("u:") && banks::is_valid_bank_id(id))?;
    let name = text(req(bank, 2)?)?;
    ensure(!name.is_empty() && name.len() <= MAX_NAME)?;
    let aliases = match bank.get(3) {
        None => Vec::new(),
        Some(Value::Array(items)) => {
            ensure(!items.is_empty() && items.len() <= MAX_ALIASES)?;
            items
                .iter()
                .map(|item| {
                    let alias = text(item)?;
                    ensure(!alias.is_empty() && alias.len() <= MAX_NAME)?;
                    Ok(alias)
                })
                .collect::<Result<Vec<_>>>()?
        }
        Some(_) => return Err(VaultError::SchemaViolation),
    };
    Ok(CustomBank { id, name, aliases })
}

fn valid_view(scope: &str, filter: u64) -> bool {
    let scope_ok =
        scope == SCOPE_ALL || scope == SCOPE_UNASSIGNED || banks::is_valid_bank_id(scope);
    scope_ok && filter <= MAX_FILTER
}

/// A copy of the map with `key` set to `value`.
fn with_key(org: &Value, key: u64, value: Value) -> Result<Value> {
    let mut entries: Vec<(u64, Value)> = map(org)?
        .iter()
        .filter(|(k, _)| *k != key)
        .cloned()
        .collect();
    entries.push((key, value));
    Ok(Value::map(entries))
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn ensure(condition: bool) -> Result<()> {
    if condition {
        Ok(())
    } else {
        Err(VaultError::SchemaViolation)
    }
}

fn map(value: &Value) -> Result<&[(u64, Value)]> {
    match value {
        Value::Map(entries) => Ok(entries),
        _ => Err(VaultError::SchemaViolation),
    }
}

fn req(map: &Value, key: u64) -> Result<&Value> {
    map.get(key).ok_or(VaultError::SchemaViolation)
}

fn text(value: &Value) -> Result<&str> {
    match value {
        Value::Text(t) => Ok(t),
        _ => Err(VaultError::SchemaViolation),
    }
}

fn uint(value: &Value) -> Result<u64> {
    value.as_uint().ok_or(VaultError::SchemaViolation)
}
