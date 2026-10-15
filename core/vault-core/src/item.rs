//! Item schema checks (vault-format §5.1 registries, §5.2 index envelope, §5.3 secret envelope,
//! §5.10 templates).
//!
//! [`validate`] runs on an item's decrypted index and secret envelopes before they are written. It
//! returns `Err(VaultError::SchemaViolation)` for anything the format does not allow, without saying
//! which rule failed: the caller built the item and already knows what it put in.
//!
//! Templates exist for cards, bank accounts and PIN items. Until the other item types get theirs
//! (at G0 review), their fields may only use the `custom` key.

use std::collections::{HashMap, HashSet};

use crate::banks;
use crate::cbor::Value;
use crate::error::{Result, VaultError};

/// Item types (vault-format §5.1).
pub mod item_type {
    pub const LOGIN: u64 = 1;
    pub const CARD: u64 = 2;
    pub const BANK_ACCOUNT: u64 = 3;
    pub const IDENTITY: u64 = 4;
    pub const SECURE_NOTE: u64 = 5;
    pub const DEVELOPER_KEY: u64 = 6;
    pub const MEMBERSHIP: u64 = 7;
    pub const OTHER: u64 = 8;
    pub const PIN: u64 = 9;
}

/// Field kinds (vault-format §5.1).
pub mod kind {
    pub const TEXT: u64 = 1;
    pub const CONCEALED: u64 = 2;
    pub const PAN: u64 = 3;
    pub const EXPIRY: u64 = 4;
    pub const PHONE: u64 = 8;
    pub const ACCOUNT_NUMBER: u64 = 10;
    pub const ROUTING_CODE: u64 = 11;
    pub const TOTP_SECRET: u64 = 13;
    pub const CVV: u64 = 14;
    pub const PIN: u64 = 15;
    pub const MAX: u64 = 15;
}

/// Sensitivity tiers (vault-format §5.1).
pub mod sensitivity {
    pub const NORMAL: u64 = 1;
    pub const CONCEALED: u64 = 2;
    pub const CRITICAL: u64 = 3;
    pub const CRITICAL_STRICT: u64 = 4;
}

/// `pin_purpose` of an ATM or card PIN, whose value must be critical-strict.
pub const PIN_PURPOSE_ATM: u64 = 4;

const MAX_TITLE: usize = 256;
const MAX_LABEL: usize = 128;
const MAX_FIELDS: usize = 100;
const MAX_TAGS: usize = 32;
const MAX_TAG: usize = 64;
const MAX_USER_NOTE: usize = 500;

const CARD_NETWORKS: [&str; 8] = [
    "Visa",
    "Mastercard",
    "RuPay",
    "American Express",
    "Diners Club",
    "Discover",
    "JCB",
    "UnionPay",
];

/// One template field: key, kind and default (minimum) sensitivity.
type TemplateField = (&'static str, u64, u64);

const CARD_TEMPLATE: &[TemplateField] = &[
    ("card.product", kind::TEXT, sensitivity::NORMAL),
    ("card.nickname", kind::TEXT, sensitivity::NORMAL),
    ("card.issuer", kind::TEXT, sensitivity::NORMAL),
    ("card.holder", kind::TEXT, sensitivity::NORMAL),
    ("card.number", kind::PAN, sensitivity::CRITICAL),
    ("card.expiry", kind::EXPIRY, sensitivity::NORMAL),
    ("card.network", kind::TEXT, sensitivity::NORMAL),
    ("card.cvv", kind::CVV, sensitivity::CRITICAL),
    ("card.user_note", kind::TEXT, sensitivity::NORMAL),
    ("card.customer_care", kind::PHONE, sensitivity::NORMAL),
];

const BANK_TEMPLATE: &[TemplateField] = &[
    ("bank.issuer", kind::TEXT, sensitivity::NORMAL),
    ("bank.holder", kind::TEXT, sensitivity::NORMAL),
    (
        "bank.account_number",
        kind::ACCOUNT_NUMBER,
        sensitivity::CRITICAL,
    ),
    ("bank.ifsc", kind::ROUTING_CODE, sensitivity::NORMAL),
    ("bank.branch", kind::TEXT, sensitivity::NORMAL),
    ("bank.customer_id", kind::CONCEALED, sensitivity::CONCEALED),
    (
        "bank.netbanking_user",
        kind::CONCEALED,
        sensitivity::CONCEALED,
    ),
    (
        "bank.netbanking_password",
        kind::CONCEALED,
        sensitivity::CRITICAL,
    ),
    (
        "bank.transaction_password",
        kind::CONCEALED,
        sensitivity::CRITICAL,
    ),
    ("bank.app_password", kind::CONCEALED, sensitivity::CRITICAL),
    ("bank.mpin", kind::PIN, sensitivity::CRITICAL),
    ("bank.tpin", kind::PIN, sensitivity::CRITICAL),
    ("bank.upi_pin", kind::PIN, sensitivity::CRITICAL),
    ("bank.registered_mobile", kind::PHONE, sensitivity::NORMAL),
    ("bank.rm_name", kind::TEXT, sensitivity::NORMAL),
    ("bank.rm_phone", kind::PHONE, sensitivity::NORMAL),
    ("bank.concierge_phone", kind::PHONE, sensitivity::NORMAL),
];

const PIN_TEMPLATE: &[TemplateField] = &[
    ("pin.value", kind::PIN, sensitivity::CRITICAL),
    ("pin.label", kind::TEXT, sensitivity::NORMAL),
];

fn template(item_type: u64) -> &'static [TemplateField] {
    match item_type {
        item_type::CARD => CARD_TEMPLATE,
        item_type::BANK_ACCOUNT => BANK_TEMPLATE,
        item_type::PIN => PIN_TEMPLATE,
        _ => &[],
    }
}

/// Checks one item's index and secret envelopes (decrypted, before padding) against the format.
pub fn validate(index: &Value, secret: &Value) -> Result<()> {
    let index_map = entries(index)?;
    ensure(index_map.iter().all(|(k, _)| (1..=19).contains(k)))?;

    let item_type = uint(req(index, 1)?)?;
    ensure((item_type::LOGIN..=item_type::PIN).contains(&item_type))?;
    ensure(uint(req(index, 2)?)? == 1)?;
    let revision = uint(req(index, 3)?)?;
    ensure(text(req(index, 4)?)?.len() <= MAX_TITLE)?;
    opt_text(index.get(5))?;
    check_tags(index.get(6))?;
    boolean(req(index, 7)?)?;
    uint(req(index, 8)?)?;
    uint(req(index, 9)?)?;
    if let Some(v) = index.get(10) {
        uint(v)?;
    }
    if let Some(v) = index.get(11) {
        for host in non_empty_array(v)? {
            ensure(!text(host)?.is_empty())?;
        }
    }
    if let Some(v) = index.get(15) {
        for linked in non_empty_array(v)? {
            id16(linked)?;
        }
    }

    check_type_keys(index, item_type)?;

    let fields = array(req(index, 14)?)?;
    ensure(fields.len() <= MAX_FIELDS)?;
    let values = secret_values(secret, revision)?;

    let mut ids = HashSet::new();
    let mut used_keys = HashSet::new();
    let mut hidden_ids = HashSet::new();
    let mut by_key: HashMap<&str, &str> = HashMap::new();
    let template = template(item_type);
    let pin_purpose = index.get(19).map(uint).transpose()?;

    for field in fields {
        let f = check_field(field)?;
        ensure(ids.insert(f.id))?;
        if f.name != "custom" {
            let &(_, t_kind, t_sensitivity) = template
                .iter()
                .find(|(name, _, _)| *name == f.name)
                .ok_or(VaultError::SchemaViolation)?;
            ensure(used_keys.insert(f.name))?;
            ensure(f.kind == t_kind && f.sensitivity >= t_sensitivity)?;
        }
        if item_type == item_type::CARD {
            ensure(f.kind != kind::PIN)?;
        }
        if f.name == "pin.value" && pin_purpose == Some(PIN_PURPOSE_ATM) {
            ensure(f.sensitivity == sensitivity::CRITICAL_STRICT)?;
        }
        let value = match f.value {
            Some(value) => value,
            None => {
                hidden_ids.insert(f.id);
                *values.get(f.id).ok_or(VaultError::SchemaViolation)?
            }
        };
        check_value(f.name, f.kind, value)?;
        if f.name != "custom" {
            by_key.insert(f.name, value);
        }
    }
    // Exactly one secret value per concealed or critical field, and nothing else.
    ensure(values.len() == hidden_ids.len() && values.keys().all(|id| hidden_ids.contains(id)))?;

    match item_type {
        item_type::CARD => check_card(index, &by_key),
        item_type::BANK_ACCOUNT => ensure(by_key.contains_key("bank.account_number")),
        item_type::PIN => ensure(by_key.contains_key("pin.value")),
        _ => Ok(()),
    }
}

/// Index keys 12, 13, 16–19 belong to particular item types (vault-format §5.2).
fn check_type_keys(index: &Value, item_type: u64) -> Result<()> {
    let card = item_type == item_type::CARD;
    let pin = item_type == item_type::PIN;

    ensure(index.get(12).is_some() == card)?;
    ensure(index.get(13).is_some() == card)?;
    ensure(index.get(17).is_some() == card)?;
    ensure(index.get(18).is_some() == card)?;
    ensure(index.get(19).is_some() == pin)?;
    if let Some(v) = index.get(16) {
        ensure(card || item_type == item_type::BANK_ACCOUNT)?;
        ensure(banks::is_valid_bank_id(text(v)?))?;
    }
    if card {
        ensure((1..=5).contains(&uint(req(index, 12)?)?))?;
        boolean(req(index, 13)?)?;
        ensure((1..=3).contains(&uint(req(index, 17)?)?))?;
        let last4 = text(req(index, 18)?)?;
        ensure(last4.len() == 4 && last4.bytes().all(|b| b.is_ascii_digit()))?;
    }
    if pin {
        ensure((1..=8).contains(&uint(req(index, 19)?)?))?;
    }
    Ok(())
}

fn check_card(index: &Value, by_key: &HashMap<&str, &str>) -> Result<()> {
    let number = by_key
        .get("card.number")
        .ok_or(VaultError::SchemaViolation)?;
    let last4 = text(req(index, 18)?)?;
    ensure(number.ends_with(last4))?;
    ensure(by_key.contains_key("card.product") || by_key.contains_key("card.nickname"))?;
    let has_cvv = boolean(req(index, 13)?)?;
    ensure(has_cvv == by_key.contains_key("card.cvv"))
}

struct Field<'a> {
    id: &'a [u8],
    name: &'a str,
    kind: u64,
    sensitivity: u64,
    value: Option<&'a str>,
}

/// A field descriptor (vault-format §5.2).
fn check_field(field: &Value) -> Result<Field<'_>> {
    ensure(entries(field)?.iter().all(|(k, _)| (1..=7).contains(k)))?;
    let id = id16(req(field, 1)?)?;
    let name = text(req(field, 2)?)?;
    ensure(text(req(field, 3)?)?.len() <= MAX_LABEL)?;
    let kind = uint(req(field, 4)?)?;
    ensure((1..=kind::MAX).contains(&kind) && kind != kind::TOTP_SECRET)?;
    let sensitivity = uint(req(field, 5)?)?;
    ensure((sensitivity::NORMAL..=sensitivity::CRITICAL_STRICT).contains(&sensitivity))?;
    boolean(req(field, 6)?)?;
    let value = field.get(7).map(text).transpose()?;
    // A value lives in the index exactly when the field is normal (§5.2).
    ensure(value.is_some() == (sensitivity == sensitivity::NORMAL))?;
    Ok(Field {
        id,
        name,
        kind,
        sensitivity,
        value,
    })
}

/// Rules on a field's value, by kind and by template key (vault-format §5.10).
fn check_value(key: &str, field_kind: u64, value: &str) -> Result<()> {
    match field_kind {
        kind::PAN => ensure((12..=19).contains(&value.len()) && all_ascii_digits(value))?,
        kind::EXPIRY => ensure(is_expiry(value))?,
        kind::PIN => ensure((4..=12).contains(&value.len()) && all_ascii_digits(value))?,
        _ => {}
    }
    match key {
        "bank.ifsc" => ensure(is_ifsc(value)),
        "card.network" => ensure(CARD_NETWORKS.contains(&value)),
        "card.user_note" => ensure(value.len() <= MAX_USER_NOTE),
        _ => Ok(()),
    }
}

/// The secret envelope (§5.3): its revision must match the index; returns field id → value.
fn secret_values(envelope: &Value, revision: u64) -> Result<HashMap<&[u8], &str>> {
    ensure(entries(envelope)?.iter().all(|(k, _)| (1..=3).contains(k)))?;
    ensure(uint(req(envelope, 1)?)? == revision)?;
    opt_text(envelope.get(3))?;
    let mut values = HashMap::new();
    let mut previous: Option<&[u8]> = None;
    for pair in array(req(envelope, 2)?)? {
        let (id, value) = match array(pair)? {
            [id, value] => (id16(id)?, text(value)?),
            _ => return Err(VaultError::SchemaViolation),
        };
        // Strictly ascending ids: sorted, and no id twice.
        ensure(previous.is_none_or(|p| p < id))?;
        previous = Some(id);
        values.insert(id, value);
    }
    Ok(values)
}

fn check_tags(tags: Option<&Value>) -> Result<()> {
    let Some(tags) = tags else { return Ok(()) };
    let tags = non_empty_array(tags)?;
    ensure(tags.len() <= MAX_TAGS)?;
    for tag in tags {
        let tag = text(tag)?;
        ensure(!tag.is_empty() && tag.len() <= MAX_TAG)?;
    }
    Ok(())
}

fn is_expiry(value: &str) -> bool {
    match value.as_bytes() {
        [m1, m2, b'/', y1, y2] => {
            let month = matches!((m1, m2), (b'0', b'1'..=b'9') | (b'1', b'0'..=b'2'));
            month && y1.is_ascii_digit() && y2.is_ascii_digit()
        }
        _ => false,
    }
}

/// `^[A-Z]{4}0[A-Z0-9]{6}$`
fn is_ifsc(value: &str) -> bool {
    let b = value.as_bytes();
    b.len() == 11
        && b.iter().take(4).all(u8::is_ascii_uppercase)
        && b.get(4) == Some(&b'0')
        && b.iter()
            .skip(5)
            .all(|c| c.is_ascii_uppercase() || c.is_ascii_digit())
}

fn all_ascii_digits(value: &str) -> bool {
    value.bytes().all(|b| b.is_ascii_digit())
}

// ---------- typed access to CBOR values ----------

fn ensure(condition: bool) -> Result<()> {
    if condition {
        Ok(())
    } else {
        Err(VaultError::SchemaViolation)
    }
}

fn entries(value: &Value) -> Result<&[(u64, Value)]> {
    match value {
        Value::Map(entries) => Ok(entries),
        _ => Err(VaultError::SchemaViolation),
    }
}

fn req(map: &Value, key: u64) -> Result<&Value> {
    map.get(key).ok_or(VaultError::SchemaViolation)
}

fn uint(value: &Value) -> Result<u64> {
    value.as_uint().ok_or(VaultError::SchemaViolation)
}

fn boolean(value: &Value) -> Result<bool> {
    match value {
        Value::Bool(b) => Ok(*b),
        _ => Err(VaultError::SchemaViolation),
    }
}

fn text(value: &Value) -> Result<&str> {
    match value {
        Value::Text(t) => Ok(t),
        _ => Err(VaultError::SchemaViolation),
    }
}

/// An optional text key, if present, is never empty (vault-format §2, S-1).
fn opt_text(value: Option<&Value>) -> Result<()> {
    match value {
        Some(v) => ensure(!text(v)?.is_empty()),
        None => Ok(()),
    }
}

fn array(value: &Value) -> Result<&[Value]> {
    match value {
        Value::Array(items) => Ok(items),
        _ => Err(VaultError::SchemaViolation),
    }
}

/// An optional array key, if present, is never empty (vault-format §2, S-1).
fn non_empty_array(value: &Value) -> Result<&[Value]> {
    let items = array(value)?;
    ensure(!items.is_empty())?;
    Ok(items)
}

fn id16(value: &Value) -> Result<&[u8]> {
    match value.as_bytes() {
        Some(id) if id.len() == 16 => Ok(id),
        _ => Err(VaultError::SchemaViolation),
    }
}
