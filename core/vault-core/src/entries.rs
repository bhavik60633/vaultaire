//! Cards, PINs and bank accounts as typed entries, and browsing cards by bank (vault-format §5.1–
//! §5.3, §5.9, §5.10; product plan §3–§5).
//!
//! The app hands in plain typed fields. This module builds the index and secret envelopes the
//! templates describe (title, subtitle, last four digits, bank display name, stable field ids),
//! and [`crate::item::validate`] checks them before anything is written. Secret values are
//! [`Secret`]: set, kept as they are on an edit, or empty.
//!
//! Browsing works only on the decrypted index envelopes in memory: groups, filters and search never
//! open a secret envelope, and never return one (threat-model I12, T37).

use std::cmp::Ordering;

use zeroize::Zeroizing;

use crate::banks::{self, Catalogue};
use crate::cbor::Value;
use crate::error::{Result, VaultError};
use crate::item::{item_type, kind, sensitivity};
use crate::organisation::{self, Added};
use crate::prims;
use crate::vault::Session;

// ---------- the typed entries ----------

/// Card kinds (vault-format §5.1).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum CardKind {
    #[default]
    Debit,
    Credit,
    Other,
}

impl CardKind {
    fn code(self) -> u64 {
        match self {
            CardKind::Debit => 1,
            CardKind::Credit => 2,
            CardKind::Other => 3,
        }
    }
    fn from_code(code: u64) -> Option<CardKind> {
        match code {
            1 => Some(CardKind::Debit),
            2 => Some(CardKind::Credit),
            3 => Some(CardKind::Other),
            _ => None,
        }
    }
    fn label(self) -> &'static str {
        match self {
            CardKind::Debit => "Debit",
            CardKind::Credit => "Credit",
            CardKind::Other => "Other",
        }
    }
}

/// PIN purposes (vault-format §5.1).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum PinPurpose {
    Locker,
    DematTpin,
    AppLogin,
    AtmPin,
    Mpin,
    Tpin,
    DoorAlarm,
    #[default]
    Other,
}

impl PinPurpose {
    const ALL: [PinPurpose; 8] = [
        PinPurpose::Locker,
        PinPurpose::DematTpin,
        PinPurpose::AppLogin,
        PinPurpose::AtmPin,
        PinPurpose::Mpin,
        PinPurpose::Tpin,
        PinPurpose::DoorAlarm,
        PinPurpose::Other,
    ];
    fn code(self) -> u64 {
        match self {
            PinPurpose::Locker => 1,
            PinPurpose::DematTpin => 2,
            PinPurpose::AppLogin => 3,
            PinPurpose::AtmPin => 4,
            PinPurpose::Mpin => 5,
            PinPurpose::Tpin => 6,
            PinPurpose::DoorAlarm => 7,
            PinPurpose::Other => 8,
        }
    }
    fn from_code(code: u64) -> Option<PinPurpose> {
        PinPurpose::ALL.into_iter().find(|p| p.code() == code)
    }
    fn label(self) -> &'static str {
        match self {
            PinPurpose::Locker => "Locker",
            PinPurpose::DematTpin => "Demat TPIN",
            PinPurpose::AppLogin => "App login",
            PinPurpose::AtmPin => "ATM PIN",
            PinPurpose::Mpin => "MPIN",
            PinPurpose::Tpin => "TPIN",
            PinPurpose::DoorAlarm => "Door or alarm",
            PinPurpose::Other => "PIN",
        }
    }
}

/// A secret value handed in by the app.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub enum Secret {
    /// The new value.
    Set(String),
    /// On an edit: unchanged. Invalid on a new entry.
    Keep,
    /// No value.
    #[default]
    Empty,
}

/// A card (vault-format §5.10, Card). The number and CVV are secrets; everything else is shown.
#[derive(Debug, Clone, Default)]
pub struct Card {
    pub bank_id: Option<String>,
    pub kind: CardKind,
    pub product: Option<String>,
    pub nickname: Option<String>,
    pub holder: Option<String>,
    pub number: Secret,
    pub expiry: Option<String>,
    pub network: Option<String>,
    pub cvv: Secret,
    pub user_note: Option<String>,
    pub customer_care: Option<String>,
}

/// An independent PIN (vault-format §5.10, PIN). An ATM PIN is critical-strict.
#[derive(Debug, Clone, Default)]
pub struct Pin {
    pub purpose: PinPurpose,
    pub label: String,
    pub value: Secret,
    pub linked_to: Option<[u8; 16]>,
}

/// An Indian bank account (vault-format §5.10, Bank account).
#[derive(Debug, Clone, Default)]
pub struct BankAccount {
    pub bank_id: Option<String>,
    pub holder: Option<String>,
    pub account_number: Secret,
    pub ifsc: Option<String>,
    pub branch: Option<String>,
    pub customer_id: Secret,
    pub netbanking_user: Secret,
    pub netbanking_password: Secret,
    pub transaction_password: Secret,
    pub app_password: Secret,
    pub mpin: Secret,
    pub tpin: Secret,
    pub upi_pin: Secret,
    pub registered_mobile: Option<String>,
    pub rm_name: Option<String>,
    pub rm_phone: Option<String>,
    pub concierge_phone: Option<String>,
}

#[derive(Debug, Clone)]
pub enum Entry {
    Card(Card),
    Pin(Pin),
    BankAccount(BankAccount),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EntryKind {
    Card,
    Pin,
    BankAccount,
    Other,
}

/// What lists and search show for an entry: normal fields only, never a secret value.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Summary {
    pub id: [u8; 16],
    pub kind: EntryKind,
    pub title: String,
    pub subtitle: Option<String>,
    pub bank_id: Option<String>,
    pub card_kind: Option<CardKind>,
    pub last4: Option<String>,
    pub network: Option<String>,
    pub pin_purpose: Option<PinPurpose>,
    pub favourite: bool,
    pub has_cvv: bool,
    pub linked_to: Vec<[u8; 16]>,
    pub product: Option<String>,
    pub nickname: Option<String>,
    pub user_note: Option<String>,
    pub updated_at: u64,
    pub last_used_at: Option<u64>,
}

/// One row of an entry's page. `value` is present only for normal (sensitivity 1) fields.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FieldView {
    pub key: String,
    pub label: String,
    pub kind: u64,
    pub sensitivity: u64,
    pub copyable: bool,
    pub value: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Details {
    pub summary: Summary,
    pub fields: Vec<FieldView>,
}

/// One bank's tile: its cards and how many are credit or debit. `bank_id` is `None` for
/// "Other / Unassigned".
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BankGroup {
    pub bank_id: Option<String>,
    pub name: String,
    pub total: usize,
    pub credit: usize,
    pub debit: usize,
}

/// Which cards a bank page shows.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Scope {
    All,
    Unassigned,
    Bank(String),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CardFilter {
    All,
    Credit,
    Debit,
}

impl CardFilter {
    fn code(self) -> u64 {
        match self {
            CardFilter::All => 0,
            CardFilter::Credit => 1,
            CardFilter::Debit => 2,
        }
    }
    fn from_code(code: u64) -> Option<CardFilter> {
        match code {
            0 => Some(CardFilter::All),
            1 => Some(CardFilter::Credit),
            2 => Some(CardFilter::Debit),
            _ => None,
        }
    }
}

pub const UNASSIGNED_NAME: &str = "Other / Unassigned";

// ---------- the card network, from the number ----------

/// The network a card number's first digits belong to (vault-format §5.10 `card.network`), or
/// `None` if unknown. Only a suggestion: the user can change it.
pub fn suggest_network(number: &str) -> Option<&'static str> {
    let digits: String = number.chars().filter(char::is_ascii_digit).collect();
    let prefix = |n: usize| digits.get(..n).and_then(|p| p.parse::<u32>().ok());
    let within =
        |n: usize, low: u32, high: u32| prefix(n).is_some_and(|p| (low..=high).contains(&p));
    if digits.starts_with('4') {
        Some("Visa")
    } else if within(2, 51, 55) || within(4, 2221, 2720) {
        Some("Mastercard")
    } else if within(2, 34, 34) || within(2, 37, 37) {
        Some("American Express")
    } else if within(3, 300, 305) || within(2, 36, 36) || within(2, 38, 39) {
        Some("Diners Club")
    } else if within(4, 6011, 6011) || within(3, 644, 649) {
        Some("Discover")
    } else if within(2, 60, 60) || within(4, 6521, 6522) || within(2, 81, 82) || within(3, 508, 508)
    {
        Some("RuPay")
    } else if within(2, 65, 65) {
        Some("Discover")
    } else if within(4, 3528, 3589) {
        Some("JCB")
    } else if within(2, 62, 62) {
        Some("UnionPay")
    } else {
        None
    }
}

// ---------- templates: key, label, kind, default sensitivity ----------

type Row = (&'static str, &'static str, u64, u64);

const CARD_ROWS: &[Row] = &[
    ("card.product", "Card name", kind::TEXT, sensitivity::NORMAL),
    ("card.nickname", "Nickname", kind::TEXT, sensitivity::NORMAL),
    ("card.issuer", "Bank", kind::TEXT, sensitivity::NORMAL),
    (
        "card.holder",
        "Name on card",
        kind::TEXT,
        sensitivity::NORMAL,
    ),
    (
        "card.number",
        "Card number",
        kind::PAN,
        sensitivity::CRITICAL,
    ),
    ("card.expiry", "Expiry", kind::EXPIRY, sensitivity::NORMAL),
    ("card.network", "Network", kind::TEXT, sensitivity::NORMAL),
    (
        "card.cvv",
        "Security code (CVV)",
        kind::CVV,
        sensitivity::CRITICAL,
    ),
    (
        "card.user_note",
        "Your note",
        kind::TEXT,
        sensitivity::NORMAL,
    ),
    (
        "card.customer_care",
        "Customer care",
        kind::PHONE,
        sensitivity::NORMAL,
    ),
];

const BANK_ROWS: &[Row] = &[
    ("bank.issuer", "Bank", kind::TEXT, sensitivity::NORMAL),
    (
        "bank.holder",
        "Account holder",
        kind::TEXT,
        sensitivity::NORMAL,
    ),
    (
        "bank.account_number",
        "Account number",
        kind::ACCOUNT_NUMBER,
        sensitivity::CRITICAL,
    ),
    ("bank.ifsc", "IFSC", kind::ROUTING_CODE, sensitivity::NORMAL),
    ("bank.branch", "Branch", kind::TEXT, sensitivity::NORMAL),
    (
        "bank.customer_id",
        "Customer ID (CIF)",
        kind::CONCEALED,
        sensitivity::CONCEALED,
    ),
    (
        "bank.netbanking_user",
        "Net-banking user ID",
        kind::CONCEALED,
        sensitivity::CONCEALED,
    ),
    (
        "bank.netbanking_password",
        "Net-banking password",
        kind::CONCEALED,
        sensitivity::CRITICAL,
    ),
    (
        "bank.transaction_password",
        "Transaction password",
        kind::CONCEALED,
        sensitivity::CRITICAL,
    ),
    (
        "bank.app_password",
        "Mobile app password",
        kind::CONCEALED,
        sensitivity::CRITICAL,
    ),
    ("bank.mpin", "MPIN", kind::PIN, sensitivity::CRITICAL),
    ("bank.tpin", "TPIN", kind::PIN, sensitivity::CRITICAL),
    ("bank.upi_pin", "UPI PIN", kind::PIN, sensitivity::CRITICAL),
    (
        "bank.registered_mobile",
        "Registered mobile",
        kind::PHONE,
        sensitivity::NORMAL,
    ),
    (
        "bank.rm_name",
        "Relationship manager",
        kind::TEXT,
        sensitivity::NORMAL,
    ),
    (
        "bank.rm_phone",
        "RM phone",
        kind::PHONE,
        sensitivity::NORMAL,
    ),
    (
        "bank.concierge_phone",
        "Concierge / customer care",
        kind::PHONE,
        sensitivity::NORMAL,
    ),
];

const PIN_ROWS: &[Row] = &[
    ("pin.label", "Used for", kind::TEXT, sensitivity::NORMAL),
    ("pin.value", "PIN", kind::PIN, sensitivity::CRITICAL),
];

/// A field's value as handed in: a shown value, or a secret.
enum Given<'a> {
    Shown(Option<&'a str>),
    Hidden(&'a Secret),
}

fn shown(value: &Option<String>) -> Given<'_> {
    Given::Shown(value.as_deref().map(str::trim).filter(|v| !v.is_empty()))
}

// ---------- the session API ----------

impl Session {
    /// Saves a new entry; returns its id.
    pub fn add_entry(&mut self, entry: &Entry, now: u64) -> Result<[u8; 16]> {
        let id: [u8; 16] = prims::random()?;
        let (index, secret) = self.build(entry, None, now)?;
        self.put_item(id, &index, &secret)?;
        Ok(id)
    }

    /// Replaces an entry's fields. Secrets marked [`Secret::Keep`] keep their value, favourite and
    /// use history are kept, and the revision goes up.
    pub fn update_entry(&mut self, id: &[u8; 16], entry: &Entry, now: u64) -> Result<()> {
        let old_index = self.index_of(id).cloned().ok_or(VaultError::NotFound)?;
        let old_secret = self.secret_of(id)?;
        let (index, secret) = self.build(entry, Some((&old_index, &old_secret)), now)?;
        self.put_item(*id, &index, &secret)
    }

    /// Every entry, as summaries.
    pub fn summaries(&self) -> Vec<Summary> {
        self.indexes()
            .filter_map(|(id, index)| summary_of(id, index))
            .collect()
    }

    pub fn summary(&self, id: &[u8; 16]) -> Option<Summary> {
        self.index_of(id).and_then(|index| summary_of(id, index))
    }

    /// An entry's rows, without any secret value.
    pub fn details(&self, id: &[u8; 16]) -> Result<Details> {
        let index = self.index_of(id).ok_or(VaultError::NotFound)?;
        let summary = summary_of(id, index).ok_or(VaultError::NotFound)?;
        let rows = rows_for(uint_at(index, 1).unwrap_or(0));
        let fields = field_list(index)
            .iter()
            .filter_map(|f| {
                let key = text_at(f, 2)?;
                let sens = uint_at(f, 5)?;
                Some(FieldView {
                    key: key.to_string(),
                    label: rows
                        .iter()
                        .find(|r| r.0 == key)
                        .map(|r| r.1.to_string())
                        .or_else(|| text_at(f, 3).map(str::to_string))
                        .unwrap_or_default(),
                    kind: uint_at(f, 4)?,
                    sensitivity: sens,
                    copyable: matches!(f.get(6), Some(Value::Bool(true))),
                    value: if sens == sensitivity::NORMAL {
                        text_at(f, 7).map(str::to_string)
                    } else {
                        None
                    },
                })
            })
            .collect();
        Ok(Details { summary, fields })
    }

    /// One value, by its template key, on an explicit reveal or copy.
    pub fn reveal_key(&self, id: &[u8; 16], key: &str) -> Result<Zeroizing<String>> {
        let index = self.index_of(id).ok_or(VaultError::NotFound)?;
        let field_id: [u8; 16] = field_list(index)
            .iter()
            .find(|f| text_at(f, 2) == Some(key))
            .and_then(|f| f.get(1))
            .and_then(Value::as_bytes)
            .and_then(|b| b.try_into().ok())
            .ok_or(VaultError::NotFound)?;
        self.reveal(id, &field_id)
    }

    pub fn set_favourite(&mut self, id: &[u8; 16], favourite: bool) -> Result<()> {
        self.rewrite_index(id, |index| with_key(index, 7, Value::Bool(favourite)))
    }

    pub fn record_use(&mut self, id: &[u8; 16], now: u64) -> Result<()> {
        self.rewrite_index(id, |index| with_key(index, 10, Value::Uint(now)))
    }

    /// The bank tiles: one per bank with cards, most cards first, then by name; cards without a
    /// bank last, as "Other / Unassigned".
    pub fn bank_groups(&self) -> Vec<BankGroup> {
        let cards: Vec<Summary> = self
            .summaries()
            .into_iter()
            .filter(|s| s.kind == EntryKind::Card)
            .collect();
        let mut ids: Vec<Option<String>> = Vec::new();
        for card in &cards {
            if !ids.contains(&card.bank_id) {
                ids.push(card.bank_id.clone());
            }
        }
        let mut groups: Vec<BankGroup> = ids
            .into_iter()
            .map(|bank_id| {
                let mine: Vec<&Summary> = cards.iter().filter(|c| c.bank_id == bank_id).collect();
                BankGroup {
                    name: match &bank_id {
                        Some(id) => self.bank_name(id).unwrap_or_else(|| id.clone()),
                        None => UNASSIGNED_NAME.to_string(),
                    },
                    total: mine.len(),
                    credit: mine
                        .iter()
                        .filter(|c| c.card_kind == Some(CardKind::Credit))
                        .count(),
                    debit: mine
                        .iter()
                        .filter(|c| c.card_kind == Some(CardKind::Debit))
                        .count(),
                    bank_id,
                }
            })
            .collect();
        groups.sort_by(|a, b| {
            a.bank_id
                .is_none()
                .cmp(&b.bank_id.is_none())
                .then(b.total.cmp(&a.total))
                .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
        });
        groups
    }

    /// The cards a bank page shows: favourites first, then the most recently used, then by name.
    pub fn cards(&self, scope: &Scope, filter: CardFilter) -> Vec<Summary> {
        let mut cards: Vec<Summary> = self
            .summaries()
            .into_iter()
            .filter(|s| s.kind == EntryKind::Card)
            .filter(|s| match scope {
                Scope::All => true,
                Scope::Unassigned => s.bank_id.is_none(),
                Scope::Bank(id) => s.bank_id.as_deref() == Some(id.as_str()),
            })
            .filter(|s| match filter {
                CardFilter::All => true,
                CardFilter::Credit => s.card_kind == Some(CardKind::Credit),
                CardFilter::Debit => s.card_kind == Some(CardKind::Debit),
            })
            .collect();
        cards.sort_by(display_order);
        cards
    }

    /// Entries whose bank, title, card name, nickname, note or last four digits match. Runs over
    /// the in-memory index only; secrets are never searched (product plan §5.1).
    pub fn search(&self, query: &str) -> Vec<Summary> {
        let q = query.trim().to_lowercase();
        if q.is_empty() {
            return Vec::new();
        }
        let bank_key = banks::normalise(query);
        let mut found: Vec<Summary> = self
            .summaries()
            .into_iter()
            .filter(|s| {
                let text_hit = [
                    &Some(s.title.clone()),
                    &s.product,
                    &s.nickname,
                    &s.user_note,
                ]
                .into_iter()
                .flatten()
                .any(|t| t.to_lowercase().contains(&q));
                let last4_hit = s.last4.as_deref().is_some_and(|l| l.contains(&q));
                let bank_hit = !bank_key.is_empty()
                    && s.bank_id.as_deref().is_some_and(|id| {
                        self.bank_spellings(id)
                            .iter()
                            .any(|n| n.contains(&bank_key))
                    });
                text_hit || last4_hit || bank_hit
            })
            .collect();
        found.sort_by(display_order);
        found
    }

    /// The id for a typed bank name: a catalogue bank, a bank the user added before under any
    /// spelling, or a new one.
    pub fn add_bank(&mut self, name: &str) -> Result<String> {
        if name.trim().is_empty() {
            return Err(VaultError::SchemaViolation);
        }
        let org = self.organisation();
        match organisation::add_custom_bank(&org, name, prims::random()?)? {
            Added::Existing(id) => Ok(id),
            Added::New { organisation, id } => {
                self.set_organisation(&organisation)?;
                Ok(id)
            }
        }
    }

    /// A bank's display name: the catalogue's, the user's own, or the code in capitals.
    pub fn bank_name(&self, id: &str) -> Option<String> {
        organisation::display_name(&self.organisation(), id)
    }

    /// The bank and filter last looked at, kept in the vault (product plan §5.4).
    pub fn last_card_view(&self) -> Option<(Scope, CardFilter)> {
        let (scope, filter) = organisation::last_card_view(&self.organisation())?;
        let scope = match scope.as_str() {
            organisation::SCOPE_ALL => Scope::All,
            organisation::SCOPE_UNASSIGNED => Scope::Unassigned,
            _ => Scope::Bank(scope),
        };
        Some((scope, CardFilter::from_code(filter)?))
    }

    pub fn set_last_card_view(&mut self, scope: &Scope, filter: CardFilter) -> Result<()> {
        let scope = match scope {
            Scope::All => organisation::SCOPE_ALL,
            Scope::Unassigned => organisation::SCOPE_UNASSIGNED,
            Scope::Bank(id) => id.as_str(),
        };
        let org = organisation::set_last_card_view(&self.organisation(), scope, filter.code())?;
        self.set_organisation(&org)
    }

    /// Back on the home page: unlocking returns home, not to a bank.
    pub fn clear_last_card_view(&mut self) -> Result<()> {
        let org = organisation::without_last_view(&self.organisation())?;
        self.set_organisation(&org)
    }

    // ---------- internals ----------

    fn bank_spellings(&self, id: &str) -> Vec<String> {
        let mut names: Vec<String> = Vec::new();
        if let Some(bank) = Catalogue::bundled().get(id) {
            names.push(banks::normalise(bank.name));
            names.push(banks::normalise(bank.full_name));
            names.extend(bank.aliases.iter().map(|a| banks::normalise(a)));
        }
        if let Some(name) = self.bank_name(id) {
            names.push(banks::normalise(&name));
        }
        names.retain(|n| !n.is_empty());
        names
    }

    /// Re-seals an entry with a changed index and the same secret envelope and revision.
    fn rewrite_index(&mut self, id: &[u8; 16], change: impl FnOnce(&Value) -> Value) -> Result<()> {
        let index = self.index_of(id).cloned().ok_or(VaultError::NotFound)?;
        let secret = self.secret_of(id)?;
        self.put_item(*id, &change(&index), &secret)
    }

    fn build(
        &self,
        entry: &Entry,
        old: Option<(&Value, &Value)>,
        now: u64,
    ) -> Result<(Value, Value)> {
        let (item_type, rows): (u64, &[Row]) = match entry {
            Entry::Card(_) => (item_type::CARD, CARD_ROWS),
            Entry::Pin(_) => (item_type::PIN, PIN_ROWS),
            Entry::BankAccount(_) => (item_type::BANK_ACCOUNT, BANK_ROWS),
        };
        if let Some((old_index, _)) = old
            && uint_at(old_index, 1) != Some(item_type)
        {
            return Err(VaultError::SchemaViolation);
        }
        let bank_id = match entry {
            Entry::Card(c) => clean(&c.bank_id),
            Entry::BankAccount(a) => clean(&a.bank_id),
            Entry::Pin(_) => None,
        };
        let bank_name = match &bank_id {
            Some(b) => Some(self.bank_name(b).ok_or(VaultError::SchemaViolation)?),
            None => None,
        };
        let bank_name_value = bank_name.clone();
        let given =
            |key: &str| -> Given<'_> { given_value(entry, key, bank_name_value.as_deref()) };

        let revision = old.and_then(|(i, _)| uint_at(i, 3)).map_or(1, |r| r + 1);
        let mut fields: Vec<Value> = Vec::new();
        let mut secrets: Vec<([u8; 16], String)> = Vec::new();
        let mut values: Vec<(&str, String)> = Vec::new();
        for &(name, label, field_kind, default_sens) in rows {
            let sens = if name == "pin.value"
                && matches!(entry, Entry::Pin(p) if p.purpose == PinPurpose::AtmPin)
            {
                sensitivity::CRITICAL_STRICT
            } else {
                default_sens
            };
            let value: Option<String> = match given(name) {
                Given::Shown(v) => v.map(str::to_string),
                Given::Hidden(Secret::Set(v)) => Some(if name == "card.number" {
                    v.chars().filter(|c| *c != ' ').collect()
                } else {
                    v.clone()
                }),
                Given::Hidden(Secret::Empty) => None,
                Given::Hidden(Secret::Keep) => match old {
                    Some((old_index, old_secret)) => old_secret_value(old_index, old_secret, name),
                    None => return Err(VaultError::SchemaViolation),
                },
            };
            let Some(value) = value else { continue };
            let field_id = match old.and_then(|(i, _)| old_field_id(i, name)) {
                Some(fid) => fid,
                None => prims::random()?,
            };
            let mut descriptor = vec![
                (1, Value::Bytes(field_id.to_vec())),
                (2, Value::Text(name.to_string())),
                (3, Value::Text(label.to_string())),
                (4, Value::Uint(field_kind)),
                (5, Value::Uint(sens)),
                (6, Value::Bool(true)),
            ];
            if sens == sensitivity::NORMAL {
                descriptor.push((7, Value::Text(value.clone())));
            } else {
                secrets.push((field_id, value.clone()));
            }
            fields.push(Value::map(descriptor));
            values.push((name, value));
        }
        let get = |key: &str| {
            values
                .iter()
                .find(|(k, _)| *k == key)
                .map(|(_, v)| v.as_str())
        };

        let mut index = vec![
            (1, Value::Uint(item_type)),
            (2, Value::Uint(1)),
            (3, Value::Uint(revision)),
            (
                7,
                Value::Bool(old.is_some_and(|(i, _)| matches!(i.get(7), Some(Value::Bool(true))))),
            ),
            (
                8,
                Value::Uint(old.and_then(|(i, _)| uint_at(i, 8)).unwrap_or(now)),
            ),
            (9, Value::Uint(now)),
            (14, Value::Array(fields)),
        ];
        if let Some(used) = old.and_then(|(i, _)| uint_at(i, 10)) {
            index.push((10, Value::Uint(used)));
        }
        if let Some(b) = &bank_id {
            index.push((16, Value::Text(b.clone())));
        }
        let (title, subtitle) = match entry {
            Entry::Card(c) => {
                let number = get("card.number").ok_or(VaultError::SchemaViolation)?;
                let last4: String = number
                    .get(number.len().saturating_sub(4)..)
                    .unwrap_or_default()
                    .to_string();
                let finish = old.and_then(|(i, _)| uint_at(i, 12)).unwrap_or(1);
                index.push((12, Value::Uint(finish)));
                index.push((13, Value::Bool(get("card.cvv").is_some())));
                index.push((17, Value::Uint(c.kind.code())));
                index.push((18, Value::Text(last4.clone())));
                let title = get("card.nickname")
                    .or_else(|| get("card.product"))
                    .ok_or(VaultError::SchemaViolation)?
                    .to_string();
                let mut parts = vec![c.kind.label().to_string()];
                if let Some(network) = get("card.network") {
                    parts.push(network.to_string());
                }
                parts.push(format!("•••• {last4}"));
                (title, Some(parts.join(" · ")))
            }
            Entry::Pin(p) => {
                let label = p.label.trim();
                if label.is_empty() {
                    return Err(VaultError::SchemaViolation);
                }
                index.push((19, Value::Uint(p.purpose.code())));
                if let Some(linked) = p.linked_to {
                    index.push((15, Value::Array(vec![Value::Bytes(linked.to_vec())])));
                }
                (label.to_string(), Some(p.purpose.label().to_string()))
            }
            Entry::BankAccount(_) => {
                let number = get("bank.account_number").ok_or(VaultError::SchemaViolation)?;
                let last4 = number
                    .get(number.len().saturating_sub(4)..)
                    .unwrap_or_default();
                let title = match &bank_name {
                    Some(name) => format!("{name} account"),
                    None => "Bank account".to_string(),
                };
                (title, Some(format!("•••• {last4}")))
            }
        };
        index.push((4, Value::Text(title)));
        if let Some(sub) = subtitle.filter(|s| !s.is_empty()) {
            index.push((5, Value::Text(sub)));
        }

        secrets.sort_by_key(|(fid, _)| *fid);
        let secret = Value::map(vec![
            (1, Value::Uint(revision)),
            (
                2,
                Value::Array(
                    secrets
                        .into_iter()
                        .map(|(fid, v)| {
                            Value::Array(vec![Value::Bytes(fid.to_vec()), Value::Text(v)])
                        })
                        .collect(),
                ),
            ),
        ]);
        let index = Value::map(index);
        crate::item::validate(&index, &secret)?;
        Ok((index, secret))
    }
}

/// The value the app gave for one template key.
fn given_value<'a>(entry: &'a Entry, key: &str, bank_name: Option<&'a str>) -> Given<'a> {
    match entry {
        Entry::Card(c) => match key {
            "card.product" => shown(&c.product),
            "card.nickname" => shown(&c.nickname),
            "card.issuer" => Given::Shown(bank_name),
            "card.holder" => shown(&c.holder),
            "card.number" => Given::Hidden(&c.number),
            "card.expiry" => shown(&c.expiry),
            "card.network" => shown(&c.network),
            "card.cvv" => Given::Hidden(&c.cvv),
            "card.user_note" => shown(&c.user_note),
            "card.customer_care" => shown(&c.customer_care),
            _ => Given::Shown(None),
        },
        Entry::Pin(p) => match key {
            "pin.label" => Given::Shown(Some(p.label.trim()).filter(|l| !l.is_empty())),
            "pin.value" => Given::Hidden(&p.value),
            _ => Given::Shown(None),
        },
        Entry::BankAccount(a) => match key {
            "bank.issuer" => Given::Shown(bank_name),
            "bank.holder" => shown(&a.holder),
            "bank.account_number" => Given::Hidden(&a.account_number),
            "bank.ifsc" => shown(&a.ifsc),
            "bank.branch" => shown(&a.branch),
            "bank.customer_id" => Given::Hidden(&a.customer_id),
            "bank.netbanking_user" => Given::Hidden(&a.netbanking_user),
            "bank.netbanking_password" => Given::Hidden(&a.netbanking_password),
            "bank.transaction_password" => Given::Hidden(&a.transaction_password),
            "bank.app_password" => Given::Hidden(&a.app_password),
            "bank.mpin" => Given::Hidden(&a.mpin),
            "bank.tpin" => Given::Hidden(&a.tpin),
            "bank.upi_pin" => Given::Hidden(&a.upi_pin),
            "bank.registered_mobile" => shown(&a.registered_mobile),
            "bank.rm_name" => shown(&a.rm_name),
            "bank.rm_phone" => shown(&a.rm_phone),
            "bank.concierge_phone" => shown(&a.concierge_phone),
            _ => Given::Shown(None),
        },
    }
}

fn clean(value: &Option<String>) -> Option<String> {
    value
        .as_deref()
        .map(str::trim)
        .filter(|v| !v.is_empty())
        .map(str::to_string)
}

fn rows_for(item_type: u64) -> &'static [Row] {
    match item_type {
        item_type::CARD => CARD_ROWS,
        item_type::BANK_ACCOUNT => BANK_ROWS,
        item_type::PIN => PIN_ROWS,
        _ => &[],
    }
}

fn summary_of(id: &[u8; 16], index: &Value) -> Option<Summary> {
    let kind = match uint_at(index, 1)? {
        item_type::CARD => EntryKind::Card,
        item_type::PIN => EntryKind::Pin,
        item_type::BANK_ACCOUNT => EntryKind::BankAccount,
        _ => EntryKind::Other,
    };
    let field = |key: &str| {
        field_list(index)
            .iter()
            .find(|f| text_at(f, 2) == Some(key))
            .and_then(|f| text_at(f, 7))
            .map(str::to_string)
    };
    Some(Summary {
        id: *id,
        kind,
        title: text_at(index, 4)?.to_string(),
        subtitle: text_at(index, 5).map(str::to_string),
        bank_id: text_at(index, 16).map(str::to_string),
        card_kind: uint_at(index, 17).and_then(CardKind::from_code),
        last4: text_at(index, 18).map(str::to_string),
        network: field("card.network"),
        pin_purpose: uint_at(index, 19).and_then(PinPurpose::from_code),
        favourite: matches!(index.get(7), Some(Value::Bool(true))),
        has_cvv: matches!(index.get(13), Some(Value::Bool(true))),
        linked_to: match index.get(15) {
            Some(Value::Array(items)) => items
                .iter()
                .filter_map(|v| v.as_bytes().and_then(|b| b.try_into().ok()))
                .collect(),
            _ => Vec::new(),
        },
        product: field("card.product"),
        nickname: field("card.nickname"),
        user_note: field("card.user_note"),
        updated_at: uint_at(index, 9).unwrap_or(0),
        last_used_at: uint_at(index, 10),
    })
}

/// Favourites first, then the most recently used, then by title (product plan §5.2).
fn display_order(a: &Summary, b: &Summary) -> Ordering {
    b.favourite
        .cmp(&a.favourite)
        .then_with(|| {
            b.last_used_at
                .unwrap_or(0)
                .cmp(&a.last_used_at.unwrap_or(0))
        })
        .then_with(|| a.title.to_lowercase().cmp(&b.title.to_lowercase()))
        .then_with(|| a.id.cmp(&b.id))
}

fn field_list(index: &Value) -> &[Value] {
    match index.get(14) {
        Some(Value::Array(fields)) => fields,
        _ => &[],
    }
}

fn old_field_id(index: &Value, key: &str) -> Option<[u8; 16]> {
    field_list(index)
        .iter()
        .find(|f| text_at(f, 2) == Some(key))
        .and_then(|f| f.get(1))
        .and_then(Value::as_bytes)
        .and_then(|b| b.try_into().ok())
}

fn old_secret_value(index: &Value, secret: &Value, key: &str) -> Option<String> {
    let field_id = old_field_id(index, key)?;
    match secret.get(2) {
        Some(Value::Array(pairs)) => pairs.iter().find_map(|pair| match pair {
            Value::Array(kv) => match kv.as_slice() {
                [Value::Bytes(fid), Value::Text(value)] if fid.as_slice() == field_id => {
                    Some(value.clone())
                }
                _ => None,
            },
            _ => None,
        }),
        _ => None,
    }
}

fn with_key(map: &Value, key: u64, value: Value) -> Value {
    let mut entries: Vec<(u64, Value)> = match map {
        Value::Map(entries) => entries.iter().filter(|(k, _)| *k != key).cloned().collect(),
        _ => Vec::new(),
    };
    entries.push((key, value));
    Value::map(entries)
}

fn uint_at(map: &Value, key: u64) -> Option<u64> {
    map.get(key).and_then(Value::as_uint)
}

fn text_at(map: &Value, key: u64) -> Option<&str> {
    match map.get(key) {
        Some(Value::Text(t)) => Some(t),
        _ => None,
    }
}
