//! Cards, PINs and bank accounts across the bridge (vault-format §5.10; crypto spec §5.1).
//!
//! Secret values travel as UTF-8 bytes in both directions, so the Kotlin side can wipe its copy
//! straight after the call. Lists, groups and searches carry summaries only, never a secret.

use vault_core::banks::Catalogue;
use vault_core::entries::{
    self, BankAccount, BankGroup, Card, CardFilter, CardKind, Details, Entry, EntryKind, FieldView,
    Pin, PinPurpose, Scope, Secret, Summary,
};
use zeroize::{Zeroize, Zeroizing};

use crate::{FfiResult, FfiSession, code};

/// A secret value from the app: new bytes, unchanged on an edit, or none.
#[derive(Debug, uniffi::Enum)]
pub enum FfiSecret {
    Keep,
    Empty,
    Set { value: Vec<u8> },
}

impl FfiSecret {
    /// Into the core's form; the bytes are wiped.
    fn take(self) -> FfiResult<Secret> {
        Ok(match self {
            FfiSecret::Keep => Secret::Keep,
            FfiSecret::Empty => Secret::Empty,
            FfiSecret::Set { mut value } => {
                let text = std::str::from_utf8(&value)
                    .map(str::to_owned)
                    .map_err(|_| code("schema_violation"));
                value.zeroize();
                Secret::Set(text?)
            }
        })
    }
}

#[derive(Debug, uniffi::Record)]
pub struct FfiCardInput {
    pub bank_id: Option<String>,
    /// 1 Debit, 2 Credit, 3 Other.
    pub kind: u32,
    pub product: Option<String>,
    pub nickname: Option<String>,
    pub holder: Option<String>,
    pub number: FfiSecret,
    pub expiry: Option<String>,
    pub network: Option<String>,
    pub cvv: FfiSecret,
    pub user_note: Option<String>,
    pub customer_care: Option<String>,
}

#[derive(Debug, uniffi::Record)]
pub struct FfiPinInput {
    /// 1–8 (vault-format §5.1 PIN purposes).
    pub purpose: u32,
    pub label: String,
    pub value: FfiSecret,
    pub linked_to: Option<Vec<u8>>,
}

#[derive(Debug, uniffi::Record)]
pub struct FfiBankAccountInput {
    pub bank_id: Option<String>,
    pub holder: Option<String>,
    pub account_number: FfiSecret,
    pub ifsc: Option<String>,
    pub branch: Option<String>,
    pub customer_id: FfiSecret,
    pub netbanking_user: FfiSecret,
    pub netbanking_password: FfiSecret,
    pub transaction_password: FfiSecret,
    pub app_password: FfiSecret,
    pub mpin: FfiSecret,
    pub tpin: FfiSecret,
    pub upi_pin: FfiSecret,
    pub registered_mobile: Option<String>,
    pub rm_name: Option<String>,
    pub rm_phone: Option<String>,
    pub concierge_phone: Option<String>,
}

#[derive(Debug, uniffi::Record)]
pub struct FfiSummary {
    pub id: Vec<u8>,
    /// 1 card, 2 PIN, 3 bank account, 4 other.
    pub kind: u32,
    pub title: String,
    pub subtitle: Option<String>,
    pub bank_id: Option<String>,
    pub card_kind: Option<u32>,
    pub last4: Option<String>,
    pub network: Option<String>,
    pub pin_purpose: Option<u32>,
    pub favourite: bool,
    pub has_cvv: bool,
    pub linked_to: Vec<Vec<u8>>,
    pub product: Option<String>,
    pub nickname: Option<String>,
    pub user_note: Option<String>,
    pub updated_at: u64,
    pub last_used_at: Option<u64>,
    /// Cards only: 1 Obsidian, 2 Titanium, 3 Platinum, 4 Ivory, 5 Champagne.
    pub finish: Option<u32>,
}

#[derive(Debug, uniffi::Record)]
pub struct FfiFieldView {
    pub key: String,
    pub label: String,
    pub kind: u32,
    pub sensitivity: u32,
    pub copyable: bool,
    pub value: Option<String>,
}

#[derive(Debug, uniffi::Record)]
pub struct FfiDetails {
    pub summary: FfiSummary,
    pub fields: Vec<FfiFieldView>,
}

#[derive(Debug, uniffi::Record)]
pub struct FfiBankGroup {
    pub bank_id: Option<String>,
    pub name: String,
    pub total: u32,
    pub credit: u32,
    pub debit: u32,
}

#[derive(Debug, uniffi::Enum)]
pub enum FfiScope {
    All,
    Unassigned,
    Bank { bank_id: String },
}

/// The last card view: a scope and a filter (0 all, 1 credit, 2 debit).
#[derive(Debug, uniffi::Record)]
pub struct FfiCardView {
    pub scope: FfiScope,
    pub filter: u32,
}

#[derive(Debug, uniffi::Record)]
pub struct FfiCatalogueBank {
    pub id: String,
    pub name: String,
    pub full_name: String,
}

// ---------- conversions ----------

fn card_kind(code_: u32) -> FfiResult<CardKind> {
    match code_ {
        1 => Ok(CardKind::Debit),
        2 => Ok(CardKind::Credit),
        3 => Ok(CardKind::Other),
        _ => Err(code("schema_violation")),
    }
}

fn card_kind_code(kind: CardKind) -> u32 {
    match kind {
        CardKind::Debit => 1,
        CardKind::Credit => 2,
        CardKind::Other => 3,
    }
}

const PURPOSES: [PinPurpose; 8] = [
    PinPurpose::Locker,
    PinPurpose::DematTpin,
    PinPurpose::AppLogin,
    PinPurpose::AtmPin,
    PinPurpose::Mpin,
    PinPurpose::Tpin,
    PinPurpose::DoorAlarm,
    PinPurpose::Other,
];

fn purpose(code_: u32) -> FfiResult<PinPurpose> {
    code_
        .checked_sub(1)
        .and_then(|i| PURPOSES.get(i as usize).copied())
        .ok_or_else(|| code("schema_violation"))
}

fn purpose_code(p: PinPurpose) -> u32 {
    PURPOSES
        .iter()
        .position(|x| *x == p)
        .map_or(8, |i| i as u32 + 1)
}

fn filter(code_: u32) -> FfiResult<CardFilter> {
    match code_ {
        0 => Ok(CardFilter::All),
        1 => Ok(CardFilter::Credit),
        2 => Ok(CardFilter::Debit),
        _ => Err(code("schema_violation")),
    }
}

fn filter_code(f: CardFilter) -> u32 {
    match f {
        CardFilter::All => 0,
        CardFilter::Credit => 1,
        CardFilter::Debit => 2,
    }
}

fn scope(s: FfiScope) -> Scope {
    match s {
        FfiScope::All => Scope::All,
        FfiScope::Unassigned => Scope::Unassigned,
        FfiScope::Bank { bank_id } => Scope::Bank(bank_id),
    }
}

fn id16(id: &[u8]) -> FfiResult<[u8; 16]> {
    id.try_into().map_err(|_| code("not_found"))
}

fn card(input: FfiCardInput) -> FfiResult<Entry> {
    Ok(Entry::Card(Card {
        bank_id: input.bank_id,
        kind: card_kind(input.kind)?,
        product: input.product,
        nickname: input.nickname,
        holder: input.holder,
        number: input.number.take()?,
        expiry: input.expiry,
        network: input.network,
        cvv: input.cvv.take()?,
        user_note: input.user_note,
        customer_care: input.customer_care,
    }))
}

fn pin(input: FfiPinInput) -> FfiResult<Entry> {
    Ok(Entry::Pin(Pin {
        purpose: purpose(input.purpose)?,
        label: input.label,
        value: input.value.take()?,
        linked_to: input.linked_to.as_deref().map(id16).transpose()?,
    }))
}

fn account(input: FfiBankAccountInput) -> FfiResult<Entry> {
    Ok(Entry::BankAccount(BankAccount {
        bank_id: input.bank_id,
        holder: input.holder,
        account_number: input.account_number.take()?,
        ifsc: input.ifsc,
        branch: input.branch,
        customer_id: input.customer_id.take()?,
        netbanking_user: input.netbanking_user.take()?,
        netbanking_password: input.netbanking_password.take()?,
        transaction_password: input.transaction_password.take()?,
        app_password: input.app_password.take()?,
        mpin: input.mpin.take()?,
        tpin: input.tpin.take()?,
        upi_pin: input.upi_pin.take()?,
        registered_mobile: input.registered_mobile,
        rm_name: input.rm_name,
        rm_phone: input.rm_phone,
        concierge_phone: input.concierge_phone,
    }))
}

impl From<Summary> for FfiSummary {
    fn from(s: Summary) -> FfiSummary {
        FfiSummary {
            id: s.id.to_vec(),
            kind: match s.kind {
                EntryKind::Card => 1,
                EntryKind::Pin => 2,
                EntryKind::BankAccount => 3,
                EntryKind::Other => 4,
            },
            title: s.title,
            subtitle: s.subtitle,
            bank_id: s.bank_id,
            card_kind: s.card_kind.map(card_kind_code),
            last4: s.last4,
            network: s.network,
            pin_purpose: s.pin_purpose.map(purpose_code),
            favourite: s.favourite,
            has_cvv: s.has_cvv,
            linked_to: s.linked_to.iter().map(|id| id.to_vec()).collect(),
            product: s.product,
            nickname: s.nickname,
            user_note: s.user_note,
            updated_at: s.updated_at,
            last_used_at: s.last_used_at,
            finish: s.finish.and_then(|f| u32::try_from(f).ok()),
        }
    }
}

impl From<FieldView> for FfiFieldView {
    fn from(f: FieldView) -> FfiFieldView {
        FfiFieldView {
            key: f.key,
            label: f.label,
            kind: u32::try_from(f.kind).unwrap_or(0),
            sensitivity: u32::try_from(f.sensitivity).unwrap_or(0),
            copyable: f.copyable,
            value: f.value,
        }
    }
}

impl From<Details> for FfiDetails {
    fn from(d: Details) -> FfiDetails {
        FfiDetails {
            summary: d.summary.into(),
            fields: d.fields.into_iter().map(FfiFieldView::from).collect(),
        }
    }
}

impl From<BankGroup> for FfiBankGroup {
    fn from(g: BankGroup) -> FfiBankGroup {
        let n = |v: usize| u32::try_from(v).unwrap_or(u32::MAX);
        FfiBankGroup {
            bank_id: g.bank_id,
            name: g.name,
            total: n(g.total),
            credit: n(g.credit),
            debit: n(g.debit),
        }
    }
}

fn summaries(list: Vec<Summary>) -> Vec<FfiSummary> {
    list.into_iter().map(FfiSummary::from).collect()
}

// ---------- functions without a session ----------

/// The network a card number's first digits belong to, if known.
#[uniffi::export]
pub fn suggest_network(number: String) -> Option<String> {
    entries::suggest_network(&number).map(str::to_string)
}

/// The catalogue bank a printed or typed name stands for, by the same normaliser as the bank
/// picker (ADR-011): "HDFC BANK" is `in.hdfc`. None for anything that is not a known bank.
#[uniffi::export]
pub fn find_bank(name: String) -> Option<String> {
    vault_core::banks::Catalogue::bundled()
        .lookup(&name)
        .map(|bank| bank.id.to_string())
}

/// The bundled bank catalogue (ADR-011), for the bank picker.
#[uniffi::export]
pub fn catalogue_banks() -> Vec<FfiCatalogueBank> {
    Catalogue::bundled()
        .banks()
        .iter()
        .map(|b| FfiCatalogueBank {
            id: b.id.to_string(),
            name: b.name.to_string(),
            full_name: b.full_name.to_string(),
        })
        .collect()
}

// ---------- the session ----------

#[uniffi::export]
impl FfiSession {
    pub fn add_card(&self, input: FfiCardInput, now: u64) -> FfiResult<Vec<u8>> {
        let entry = card(input)?;
        self.with(|s| s.add_entry(&entry, now).map(|id| id.to_vec()))
    }

    pub fn update_card(&self, id: Vec<u8>, input: FfiCardInput, now: u64) -> FfiResult<()> {
        let (id, entry) = (id16(&id)?, card(input)?);
        self.with(|s| s.update_entry(&id, &entry, now))
    }

    pub fn add_pin(&self, input: FfiPinInput, now: u64) -> FfiResult<Vec<u8>> {
        let entry = pin(input)?;
        self.with(|s| s.add_entry(&entry, now).map(|id| id.to_vec()))
    }

    pub fn update_pin(&self, id: Vec<u8>, input: FfiPinInput, now: u64) -> FfiResult<()> {
        let (id, entry) = (id16(&id)?, pin(input)?);
        self.with(|s| s.update_entry(&id, &entry, now))
    }

    pub fn add_bank_account(&self, input: FfiBankAccountInput, now: u64) -> FfiResult<Vec<u8>> {
        let entry = account(input)?;
        self.with(|s| s.add_entry(&entry, now).map(|id| id.to_vec()))
    }

    pub fn update_bank_account(
        &self,
        id: Vec<u8>,
        input: FfiBankAccountInput,
        now: u64,
    ) -> FfiResult<()> {
        let (id, entry) = (id16(&id)?, account(input)?);
        self.with(|s| s.update_entry(&id, &entry, now))
    }

    pub fn delete_entry(&self, id: Vec<u8>) -> FfiResult<()> {
        let id = id16(&id)?;
        self.with(|s| s.delete_item(&id))
    }

    pub fn summaries(&self) -> FfiResult<Vec<FfiSummary>> {
        self.with(|s| Ok(summaries(s.summaries())))
    }

    pub fn summary(&self, id: Vec<u8>) -> FfiResult<Option<FfiSummary>> {
        let Ok(id) = id16(&id) else { return Ok(None) };
        self.with(|s| Ok(s.summary(&id).map(FfiSummary::from)))
    }

    pub fn details(&self, id: Vec<u8>) -> FfiResult<FfiDetails> {
        let id = id16(&id)?;
        self.with(|s| s.details(&id).map(FfiDetails::from))
    }

    /// One value, as UTF-8 bytes the caller wipes after use.
    pub fn reveal(&self, id: Vec<u8>, key: String) -> FfiResult<Vec<u8>> {
        let id = id16(&id)?;
        self.with(|s| {
            let value: Zeroizing<String> = s.reveal_key(&id, &key)?;
            Ok(value.as_bytes().to_vec())
        })
    }

    pub fn set_favourite(&self, id: Vec<u8>, favourite: bool) -> FfiResult<()> {
        let id = id16(&id)?;
        self.with(|s| s.set_favourite(&id, favourite))
    }

    pub fn set_card_finish(&self, id: Vec<u8>, finish: u32) -> FfiResult<()> {
        let id = id16(&id)?;
        self.with(|s| s.set_card_finish(&id, u64::from(finish)))
    }

    pub fn record_use(&self, id: Vec<u8>, now: u64) -> FfiResult<()> {
        let id = id16(&id)?;
        self.with(|s| s.record_use(&id, now))
    }

    pub fn bank_groups(&self) -> FfiResult<Vec<FfiBankGroup>> {
        self.with(|s| {
            Ok(s.bank_groups()
                .into_iter()
                .map(FfiBankGroup::from)
                .collect())
        })
    }

    pub fn cards(&self, scope_: FfiScope, filter_: u32) -> FfiResult<Vec<FfiSummary>> {
        let (scope_, filter_) = (scope(scope_), filter(filter_)?);
        self.with(|s| Ok(summaries(s.cards(&scope_, filter_))))
    }

    pub fn search(&self, query: String) -> FfiResult<Vec<FfiSummary>> {
        self.with(|s| Ok(summaries(s.search(&query))))
    }

    pub fn add_bank(&self, name: String) -> FfiResult<String> {
        self.with(|s| s.add_bank(&name))
    }

    pub fn bank_name(&self, id: String) -> FfiResult<Option<String>> {
        self.with(|s| Ok(s.bank_name(&id)))
    }

    pub fn last_card_view(&self) -> FfiResult<Option<FfiCardView>> {
        self.with(|s| {
            Ok(s.last_card_view().map(|(sc, f)| FfiCardView {
                scope: match sc {
                    Scope::All => FfiScope::All,
                    Scope::Unassigned => FfiScope::Unassigned,
                    Scope::Bank(bank_id) => FfiScope::Bank { bank_id },
                },
                filter: filter_code(f),
            }))
        })
    }

    pub fn set_last_card_view(&self, scope_: FfiScope, filter_: u32) -> FfiResult<()> {
        let (scope_, filter_) = (scope(scope_), filter(filter_)?);
        self.with(|s| s.set_last_card_view(&scope_, filter_))
    }

    pub fn clear_last_card_view(&self) -> FfiResult<()> {
        self.with(|s| s.clear_last_card_view())
    }
}
