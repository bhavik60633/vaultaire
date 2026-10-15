//! Component 12 (milestone M3): cards, PINs and bank accounts as entries, and browsing cards by
//! bank (vault-format §5.1–§5.3, §5.10; product plan §3–§5, §8 checks 1–7; threat-model I11, I12).
//!
//! Proposed for the owner to freeze. Once frozen it moves, unchanged, to
//! `core/vault-core/tests/entries.rs`, and `vault_core::entries` is written to pass it.
//!
//! What these tests fix in place:
//! - a card, a PIN and a bank account are saved from plain typed fields, and the core builds the
//!   records (title, subtitle, last four digits, bank name) following the templates;
//! - card numbers are stored without spaces; the CVV only when given; an ATM PIN only as its own
//!   PIN item, revealed only with a fresh fingerprint;
//! - summaries and searches never contain a secret;
//! - cards are grouped by bank, filtered by Credit or Debit, sorted favourites first, and found by
//!   bank name, nickname, card name, last four digits or the user's own note: the owner's checks
//!   1 to 7, with 5 SBI, 2 HDFC and 3 Axis cards.

use std::path::PathBuf;

use vault_core::VaultError;
use vault_core::entries::{
    BankAccount, BankGroup, Card, CardFilter, CardKind, Entry, EntryKind, Pin, PinPurpose, Scope,
    Secret, suggest_network,
};
use vault_core::organisation::{self, Added};
use vault_core::prims;
use vault_core::vault::{CreateOptions, KeyLevel, PwdHardware, Session, Vault};

const PASSWORD: &str = "correct horse battery staple 42";
const NOW: u64 = 1_760_000_000;

struct FakePwd;

impl PwdHardware for FakePwd {
    fn level(&self) -> KeyLevel {
        KeyLevel::Tee
    }
    fn hmac(&self, hw_in: &[u8; 32]) -> vault_core::Result<[u8; 32]> {
        Ok(prims::hmac_sha256(&[0x5a; 32], hw_in))
    }
}

struct Place {
    dir: PathBuf,
}

impl Place {
    fn new(name: &str) -> Place {
        let dir = std::env::temp_dir().join(format!(
            "vaultaire-entries-{name}-{}",
            std::process::id()
        ));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).expect("temp dir");
        Place { dir }
    }
    fn vault(&self) -> PathBuf {
        self.dir.join("vault.db")
    }
}

impl Drop for Place {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

fn open(place: &Place) -> Session {
    let options = CreateOptions {
        allow_software_keys: false,
        now: NOW,
    };
    Vault::create(&place.vault(), PASSWORD, &FakePwd, None, &options)
        .expect("create")
        .session
}

fn card(bank: &str, kind: CardKind, product: &str, number: &str) -> Card {
    Card {
        bank_id: Some(bank.to_string()),
        kind,
        product: Some(product.to_string()),
        number: Secret::Set(number.to_string()),
        network: suggest_network(number).map(str::to_string),
        holder: Some("ASHA RAO".to_string()),
        expiry: Some("08/29".to_string()),
        ..Card::default()
    }
}

fn add(session: &mut Session, card: Card) -> [u8; 16] {
    session.add_entry(&Entry::Card(card), NOW).expect("add card")
}

/// The owner's fixture (product plan §8): 5 SBI (3 credit, 2 debit), 2 HDFC (1 credit, 1 debit)
/// and 3 Axis (2 credit, 1 debit). Returns the ids in that order.
fn fixture(session: &mut Session) -> Vec<[u8; 16]> {
    use CardKind::{Credit, Debit};
    [
        ("in.sbi", Credit, "SBI Cashback", "4111111111111234"),
        ("in.sbi", Credit, "SBI Cashback", "4111111111115678"),
        ("in.sbi", Credit, "SBI SimplyCLICK", "5105105105102001"),
        ("in.sbi", Debit, "SBI Classic Debit", "6074000000002002"),
        ("in.sbi", Debit, "SBI Platinum Debit", "4000000000002003"),
        ("in.hdfc", Credit, "HDFC Millennia", "5555555555554444"),
        ("in.hdfc", Debit, "HDFC Debit", "4000000000003001"),
        ("in.axis", Credit, "Axis Ace", "4000000000004001"),
        ("in.axis", Credit, "Axis Magnus", "5200000000004002"),
        ("in.axis", Debit, "Axis Debit", "6074000000004003"),
    ]
    .into_iter()
    .map(|(bank, kind, product, number)| add(session, card(bank, kind, product, number)))
    .collect()
}

fn group<'a>(groups: &'a [BankGroup], bank: Option<&str>) -> Option<&'a BankGroup> {
    groups.iter().find(|g| g.bank_id.as_deref() == bank)
}

fn sbi() -> Scope {
    Scope::Bank("in.sbi".to_string())
}

fn ids(summaries: &[vault_core::entries::Summary]) -> Vec<[u8; 16]> {
    summaries.iter().map(|s| s.id).collect()
}

// ---------- cards ----------

#[test]
fn a_card_is_saved_with_its_title_subtitle_and_last_four() {
    let place = Place::new("card");
    let mut session = open(&place);
    let id = add(
        &mut session,
        card("in.sbi", CardKind::Credit, "SBI Cashback", "4111 1111 1111 1234"),
    );
    let summary = session.summary(&id).expect("summary");
    assert_eq!(summary.kind, EntryKind::Card);
    assert_eq!(summary.title, "SBI Cashback");
    assert_eq!(
        summary.subtitle.as_deref(),
        Some("Credit · Visa · •••• 1234")
    );
    assert_eq!(summary.last4.as_deref(), Some("1234"));
    assert_eq!(summary.bank_id.as_deref(), Some("in.sbi"));
    assert_eq!(summary.card_kind, Some(CardKind::Credit));
    assert_eq!(
        session.reveal_key(&id, "card.number").expect("reveal").as_str(),
        "4111111111111234",
        "stored without spaces"
    );
}

#[test]
fn the_title_is_the_nickname_else_the_card_name_and_one_is_required() {
    let place = Place::new("titles");
    let mut session = open(&place);
    let nicknamed = add(
        &mut session,
        Card {
            nickname: Some("Amazon card".to_string()),
            ..card("in.hdfc", CardKind::Credit, "HDFC Millennia", "5555555555554444")
        },
    );
    let nickname_only = add(
        &mut session,
        Card {
            product: None,
            nickname: Some("Travel card".to_string()),
            ..card("in.axis", CardKind::Debit, "", "4000000000004001")
        },
    );
    assert_eq!(session.summary(&nicknamed).expect("s").title, "Amazon card");
    assert_eq!(session.summary(&nickname_only).expect("s").title, "Travel card");

    let nameless = Card {
        product: None,
        nickname: None,
        ..card("in.axis", CardKind::Debit, "", "4000000000004002")
    };
    assert_eq!(
        session.add_entry(&Entry::Card(nameless), NOW),
        Err(VaultError::SchemaViolation),
        "a card needs a card name or a nickname (vault-format §5.10)"
    );
}

#[test]
fn a_card_needs_a_valid_number() {
    let place = Place::new("card-invalid");
    let mut session = open(&place);
    for number in ["", "41111111111", "4111-1111-1111-1234", "4111abcd11111234"] {
        let bad = card("in.sbi", CardKind::Credit, "SBI Cashback", number);
        assert_eq!(
            session.add_entry(&Entry::Card(bad), NOW),
            Err(VaultError::SchemaViolation),
            "{number:?}"
        );
    }
    let no_number = Card {
        number: Secret::Empty,
        ..card("in.sbi", CardKind::Credit, "SBI Cashback", "4111111111111234")
    };
    assert_eq!(
        session.add_entry(&Entry::Card(no_number), NOW),
        Err(VaultError::SchemaViolation)
    );
    assert!(session.summaries().is_empty(), "nothing was saved");
}

#[test]
fn the_cvv_is_kept_only_when_the_user_gives_it() {
    let place = Place::new("cvv");
    let mut session = open(&place);
    let id = add(
        &mut session,
        card("in.sbi", CardKind::Credit, "SBI Cashback", "4111111111111234"),
    );
    assert!(!session.summary(&id).expect("s").has_cvv);
    assert!(session.reveal_key(&id, "card.cvv").is_err());

    let with_cvv = Card {
        number: Secret::Keep,
        cvv: Secret::Set("123".to_string()),
        ..card("in.sbi", CardKind::Credit, "SBI Cashback", "")
    };
    session
        .update_entry(&id, &Entry::Card(with_cvv), NOW + 1)
        .expect("add cvv");
    assert!(session.summary(&id).expect("s").has_cvv);
    assert_eq!(session.reveal_key(&id, "card.cvv").expect("cvv").as_str(), "123");

    let without = Card {
        number: Secret::Keep,
        cvv: Secret::Empty,
        ..card("in.sbi", CardKind::Credit, "SBI Cashback", "")
    };
    session
        .update_entry(&id, &Entry::Card(without), NOW + 2)
        .expect("remove cvv");
    assert!(!session.summary(&id).expect("s").has_cvv);
}

#[test]
fn an_edit_keeps_secrets_it_does_not_change() {
    let place = Place::new("edit");
    let mut session = open(&place);
    let id = add(
        &mut session,
        card("in.sbi", CardKind::Credit, "SBI Cashback", "4111111111111234"),
    );
    let renamed = Card {
        nickname: Some("Flipkart card".to_string()),
        number: Secret::Keep,
        ..card("in.sbi", CardKind::Credit, "SBI Cashback", "")
    };
    session
        .update_entry(&id, &Entry::Card(renamed), NOW + 5)
        .expect("edit");
    let summary = session.summary(&id).expect("s");
    assert_eq!(summary.title, "Flipkart card");
    assert_eq!(summary.last4.as_deref(), Some("1234"));
    assert_eq!(summary.updated_at, NOW + 5);
    assert_eq!(
        session.reveal_key(&id, "card.number").expect("n").as_str(),
        "4111111111111234"
    );
}

#[test]
fn a_new_entry_cannot_keep_a_secret_it_never_had() {
    let place = Place::new("keep-new");
    let mut session = open(&place);
    let keep = Card {
        number: Secret::Keep,
        ..card("in.sbi", CardKind::Credit, "SBI Cashback", "")
    };
    assert_eq!(
        session.add_entry(&Entry::Card(keep), NOW),
        Err(VaultError::SchemaViolation)
    );
}

#[test]
fn details_show_normal_values_and_hide_secret_ones() {
    let place = Place::new("details");
    let mut session = open(&place);
    let id = add(
        &mut session,
        Card {
            cvv: Secret::Set("123".to_string()),
            user_note: Some("Check this card for Amazon offers".to_string()),
            ..card("in.sbi", CardKind::Credit, "SBI Cashback", "4111111111111234")
        },
    );
    let details = session.details(&id).expect("details");
    let field = |key: &str| details.fields.iter().find(|f| f.key == key);
    assert_eq!(
        field("card.holder").and_then(|f| f.value.as_deref()),
        Some("ASHA RAO")
    );
    assert_eq!(
        field("card.user_note").and_then(|f| f.value.as_deref()),
        Some("Check this card for Amazon offers")
    );
    assert_eq!(
        field("card.issuer").and_then(|f| f.value.as_deref()),
        Some("SBI")
    );
    let number = field("card.number").expect("number row");
    assert_eq!(number.value, None, "secret values never come with the details");
    assert_eq!(number.sensitivity, 3);
    assert!(number.copyable);
    assert_eq!(field("card.cvv").map(|f| f.sensitivity), Some(3));
}

#[test]
fn summaries_and_details_never_contain_a_secret() {
    let place = Place::new("no-secrets");
    let mut session = open(&place);
    let card_id = add(
        &mut session,
        Card {
            cvv: Secret::Set("987".to_string()),
            ..card("in.sbi", CardKind::Credit, "SBI Cashback", "4111111111111234")
        },
    );
    let pin_id = session
        .add_entry(
            &Entry::Pin(Pin {
                purpose: PinPurpose::Locker,
                label: "SBI locker 214".to_string(),
                value: Secret::Set("482913".to_string()),
                ..Pin::default()
            }),
            NOW,
        )
        .expect("pin");
    let everything = format!(
        "{:?}{:?}{:?}{:?}",
        session.summaries(),
        session.details(&card_id),
        session.details(&pin_id),
        session.search("4")
    );
    for secret in ["4111111111111234", "987", "482913"] {
        assert!(!everything.contains(secret), "{secret} leaked");
    }
}

// ---------- PINs (threat-model I11) ----------

#[test]
fn an_atm_pin_is_its_own_entry_and_needs_a_fresh_fingerprint() {
    let place = Place::new("atm-pin");
    let mut session = open(&place);
    let card_id = add(
        &mut session,
        card("in.sbi", CardKind::Debit, "SBI Classic Debit", "6074000000002002"),
    );
    let pin_id = session
        .add_entry(
            &Entry::Pin(Pin {
                purpose: PinPurpose::AtmPin,
                label: "SBI debit card PIN".to_string(),
                value: Secret::Set("4321".to_string()),
                linked_to: Some(card_id),
            }),
            NOW,
        )
        .expect("pin");

    let pin = session.summary(&pin_id).expect("pin summary");
    assert_eq!(pin.kind, EntryKind::Pin);
    assert_eq!(pin.title, "SBI debit card PIN");
    assert_eq!(pin.pin_purpose, Some(PinPurpose::AtmPin));
    assert_eq!(pin.linked_to, vec![card_id]);
    let value = session.details(&pin_id).expect("details");
    let field = value.fields.iter().find(|f| f.key == "pin.value").expect("row");
    assert_eq!(field.sensitivity, 4, "critical-strict");
    assert_eq!(session.reveal_key(&pin_id, "pin.value").expect("pin").as_str(), "4321");

    let card_fields = session.details(&card_id).expect("card").fields;
    assert!(
        card_fields.iter().all(|f| !f.key.contains("pin") && f.kind != 15),
        "a card never carries a PIN"
    );
}

#[test]
fn other_pins_are_critical_and_need_four_to_twelve_digits() {
    let place = Place::new("pins");
    let mut session = open(&place);
    let id = session
        .add_entry(
            &Entry::Pin(Pin {
                purpose: PinPurpose::DematTpin,
                label: "CDSL Easiest".to_string(),
                value: Secret::Set("123456".to_string()),
                ..Pin::default()
            }),
            NOW,
        )
        .expect("pin");
    let details = session.details(&id).expect("details");
    let field = details.fields.iter().find(|f| f.key == "pin.value").expect("row");
    assert_eq!(field.sensitivity, 3);

    for bad in ["123", "1234567890123", "12a4", ""] {
        let pin = Pin {
            purpose: PinPurpose::AppLogin,
            label: "Paytm".to_string(),
            value: Secret::Set(bad.to_string()),
            ..Pin::default()
        };
        assert_eq!(
            session.add_entry(&Entry::Pin(pin), NOW),
            Err(VaultError::SchemaViolation),
            "{bad:?}"
        );
    }
    let unlabelled = Pin {
        purpose: PinPurpose::Other,
        label: String::new(),
        value: Secret::Set("1234".to_string()),
        ..Pin::default()
    };
    assert_eq!(
        session.add_entry(&Entry::Pin(unlabelled), NOW),
        Err(VaultError::SchemaViolation)
    );
}

// ---------- bank accounts ----------

#[test]
fn a_bank_account_keeps_every_indian_banking_detail() {
    let place = Place::new("account");
    let mut session = open(&place);
    let id = session
        .add_entry(
            &Entry::BankAccount(BankAccount {
                bank_id: Some("in.hdfc".to_string()),
                holder: Some("ASHA RAO".to_string()),
                account_number: Secret::Set("50100123456789".to_string()),
                ifsc: Some("HDFC0001234".to_string()),
                branch: Some("Andheri West".to_string()),
                customer_id: Secret::Set("87654321".to_string()),
                netbanking_user: Secret::Set("asha.rao".to_string()),
                netbanking_password: Secret::Set("Fake-NB-pass-1".to_string()),
                transaction_password: Secret::Set("Fake-TX-pass-2".to_string()),
                app_password: Secret::Set("Fake-app-pass-3".to_string()),
                mpin: Secret::Set("2468".to_string()),
                tpin: Secret::Set("1357".to_string()),
                upi_pin: Secret::Set("909090".to_string()),
                registered_mobile: Some("+91 98765 43210".to_string()),
                rm_name: Some("Priya Shah".to_string()),
                rm_phone: Some("+91 22 6160 6161".to_string()),
                concierge_phone: Some("1800 202 6161".to_string()),
            }),
            NOW,
        )
        .expect("account");

    let summary = session.summary(&id).expect("summary");
    assert_eq!(summary.kind, EntryKind::BankAccount);
    assert_eq!(summary.title, "HDFC Bank account");
    assert_eq!(summary.subtitle.as_deref(), Some("•••• 6789"));

    let details = session.details(&id).expect("details");
    let sensitivity = |key: &str| {
        details
            .fields
            .iter()
            .find(|f| f.key == key)
            .map(|f| f.sensitivity)
    };
    assert_eq!(sensitivity("bank.account_number"), Some(3));
    assert_eq!(sensitivity("bank.ifsc"), Some(1));
    assert_eq!(sensitivity("bank.customer_id"), Some(2));
    assert_eq!(sensitivity("bank.netbanking_password"), Some(3));
    assert_eq!(sensitivity("bank.mpin"), Some(3));
    assert_eq!(sensitivity("bank.rm_phone"), Some(1));
    for (key, value) in [
        ("bank.account_number", "50100123456789"),
        ("bank.netbanking_user", "asha.rao"),
        ("bank.transaction_password", "Fake-TX-pass-2"),
        ("bank.upi_pin", "909090"),
    ] {
        assert_eq!(session.reveal_key(&id, key).expect(key).as_str(), value);
    }
}

#[test]
fn an_ifsc_must_have_the_right_shape() {
    let place = Place::new("ifsc");
    let mut session = open(&place);
    for bad in ["HDFC1234567", "hdfc0001234", "HDF00001234", "HDFC000123"] {
        let account = BankAccount {
            bank_id: Some("in.hdfc".to_string()),
            account_number: Secret::Set("50100123456789".to_string()),
            ifsc: Some(bad.to_string()),
            ..BankAccount::default()
        };
        assert_eq!(
            session.add_entry(&Entry::BankAccount(account), NOW),
            Err(VaultError::SchemaViolation),
            "{bad}"
        );
    }
}

// ---------- browsing by bank: the owner's checks 1–7 (product plan §8) ----------

#[test]
fn check_1_the_groups_show_sbi_5_hdfc_2_axis_3_and_all_10() {
    let place = Place::new("check1");
    let mut session = open(&place);
    fixture(&mut session);
    let groups = session.bank_groups();
    let counts: Vec<(Option<&str>, usize, usize, usize)> = groups
        .iter()
        .map(|g| (g.bank_id.as_deref(), g.total, g.credit, g.debit))
        .collect();
    assert_eq!(
        counts,
        [
            (Some("in.sbi"), 5, 3, 2),
            (Some("in.axis"), 3, 2, 1),
            (Some("in.hdfc"), 2, 1, 1),
        ],
        "most cards first"
    );
    assert_eq!(group(&groups, Some("in.sbi")).map(|g| g.name.as_str()), Some("SBI"));
    assert_eq!(session.cards(&Scope::All, CardFilter::All).len(), 10);
}

#[test]
fn check_2_opening_sbi_shows_only_its_five_cards() {
    let place = Place::new("check2");
    let mut session = open(&place);
    let all = fixture(&mut session);
    let mut shown = ids(&session.cards(&sbi(), CardFilter::All));
    shown.sort();
    let mut expected: Vec<[u8; 16]> = all.iter().take(5).copied().collect();
    expected.sort();
    assert_eq!(shown, expected);
}

#[test]
fn check_3_the_credit_and_debit_filters_show_the_right_cards() {
    let place = Place::new("check3");
    let mut session = open(&place);
    fixture(&mut session);
    let credit = session.cards(&sbi(), CardFilter::Credit);
    let debit = session.cards(&sbi(), CardFilter::Debit);
    assert_eq!(credit.len(), 3);
    assert_eq!(debit.len(), 2);
    assert!(credit.iter().all(|c| c.card_kind == Some(CardKind::Credit)));
    assert!(debit.iter().all(|c| c.card_kind == Some(CardKind::Debit)));
    assert_eq!(session.cards(&Scope::All, CardFilter::Debit).len(), 4);
}

#[test]
fn check_4_two_cards_with_the_same_name_stay_distinguishable() {
    let place = Place::new("check4");
    let mut session = open(&place);
    let all = fixture(&mut session);
    let (first, second) = (all.first().expect("1"), all.get(1).expect("2"));
    let a = session.summary(first).expect("a");
    let b = session.summary(second).expect("b");
    assert_eq!(a.product, b.product, "both are SBI Cashback");
    assert_ne!(a.subtitle, b.subtitle, "the last four digits tell them apart");

    let nicknamed = Card {
        nickname: Some("Amazon one".to_string()),
        number: Secret::Keep,
        ..card("in.sbi", CardKind::Credit, "SBI Cashback", "")
    };
    session
        .update_entry(second, &Entry::Card(nicknamed), NOW + 1)
        .expect("nickname");
    assert_eq!(session.summary(first).expect("a").title, "SBI Cashback");
    assert_eq!(session.summary(second).expect("b").title, "Amazon one");
}

#[test]
fn check_5_changing_a_cards_bank_updates_both_groups() {
    let place = Place::new("check5");
    let mut session = open(&place);
    let all = fixture(&mut session);
    let hdfc_credit = all.get(5).expect("HDFC Millennia");
    let moved = Card {
        number: Secret::Keep,
        ..card("in.axis", CardKind::Credit, "HDFC Millennia", "")
    };
    session
        .update_entry(hdfc_credit, &Entry::Card(moved), NOW + 1)
        .expect("move");
    let groups = session.bank_groups();
    assert_eq!(group(&groups, Some("in.hdfc")).map(|g| g.total), Some(1));
    assert_eq!(group(&groups, Some("in.axis")).map(|g| g.total), Some(4));
}

#[test]
fn check_6_deleting_the_last_card_of_a_bank_removes_its_tile() {
    let place = Place::new("check6");
    let mut session = open(&place);
    let all = fixture(&mut session);
    session.delete_item(all.get(5).expect("hdfc 1")).expect("delete");
    session.delete_item(all.get(6).expect("hdfc 2")).expect("delete");
    let groups = session.bank_groups();
    assert!(group(&groups, Some("in.hdfc")).is_none());
    assert_eq!(groups.len(), 2);
}

#[test]
fn check_7_search_finds_cards_by_bank_nickname_card_name_or_last_four() {
    let place = Place::new("check7");
    let mut session = open(&place);
    let all = fixture(&mut session);
    let axis_ace = *all.get(7).expect("Axis Ace");
    session
        .update_entry(
            &axis_ace,
            &Entry::Card(Card {
                nickname: Some("Flipkart card".to_string()),
                user_note: Some("Check this card for Amazon offers".to_string()),
                number: Secret::Keep,
                ..card("in.axis", CardKind::Credit, "Axis Ace", "")
            }),
            NOW + 1,
        )
        .expect("edit");

    assert_eq!(session.search("state bank").len(), 5, "by the bank's full name");
    assert_eq!(session.search("SBI").len(), 5);
    assert_eq!(ids(&session.search("flipkart")), vec![axis_ace], "by nickname");
    assert_eq!(session.search("millennia").len(), 1, "by card name");
    assert_eq!(
        ids(&session.search("1234")),
        vec![*all.first().expect("first")],
        "by last four"
    );
    assert_eq!(ids(&session.search("amazon offers")), vec![axis_ace], "by note");
    assert!(
        session.search("41111111").is_empty(),
        "never by the hidden digits of a card number"
    );
    assert!(session.search("").is_empty());
}

// ---------- order, favourites, unassigned, custom banks ----------

#[test]
fn favourites_come_first_then_recently_used_then_by_name() {
    let place = Place::new("order");
    let mut session = open(&place);
    let all = fixture(&mut session);
    let platinum = *all.get(4).expect("Platinum Debit");
    let simply = *all.get(2).expect("SimplyCLICK");
    session.set_favourite(&platinum, true).expect("favourite");
    session.record_use(&simply, NOW + 100).expect("use");

    let titles: Vec<String> = session
        .cards(&sbi(), CardFilter::All)
        .into_iter()
        .map(|c| c.title)
        .collect();
    assert_eq!(
        titles,
        [
            "SBI Platinum Debit",
            "SBI SimplyCLICK",
            "SBI Cashback",
            "SBI Cashback",
            "SBI Classic Debit",
        ]
    );
    assert!(session.summary(&platinum).expect("s").favourite);
}

#[test]
fn cards_without_a_bank_are_grouped_last_as_unassigned() {
    let place = Place::new("unassigned");
    let mut session = open(&place);
    add(
        &mut session,
        card("in.hdfc", CardKind::Credit, "HDFC Millennia", "5555555555554444"),
    );
    for number in ["4000000000009001", "4000000000009002"] {
        add(
            &mut session,
            Card {
                bank_id: None,
                ..card("in.sbi", CardKind::Credit, "Forex card", number)
            },
        );
    }
    let groups = session.bank_groups();
    let last = groups.last().expect("groups");
    assert_eq!(last.bank_id, None, "unassigned is last, even with more cards");
    assert_eq!(last.name, "Other / Unassigned");
    assert_eq!(last.total, 2);
    assert_eq!(session.cards(&Scope::Unassigned, CardFilter::All).len(), 2);
}

#[test]
fn a_bank_the_user_added_groups_and_searches_by_its_own_name() {
    let place = Place::new("custom-bank");
    let mut session = open(&place);
    let id = match organisation::add_custom_bank(
        &session.organisation(),
        "Saraswat Co-op Bank",
        [0x42; 16],
    )
    .expect("add bank")
    {
        Added::New { organisation, id } => {
            session.set_organisation(&organisation).expect("save");
            id
        }
        Added::Existing(_) => panic!("new bank expected"),
    };
    add(
        &mut session,
        card(&id, CardKind::Debit, "Saraswat Debit", "4000000000007001"),
    );
    let groups = session.bank_groups();
    assert_eq!(
        group(&groups, Some(&id)).map(|g| g.name.as_str()),
        Some("Saraswat Co-op Bank")
    );
    assert_eq!(session.search("saraswat").len(), 1);
}

#[test]
fn equal_sized_groups_are_sorted_by_name() {
    let place = Place::new("ties");
    let mut session = open(&place);
    add(
        &mut session,
        card("in.icici", CardKind::Credit, "ICICI Amazon Pay", "4000000000008001"),
    );
    add(
        &mut session,
        card("in.axis", CardKind::Credit, "Axis Ace", "4000000000008002"),
    );
    let names: Vec<String> = session.bank_groups().into_iter().map(|g| g.name).collect();
    assert_eq!(names, ["Axis Bank", "ICICI Bank"]);
}

#[test]
fn a_typed_bank_name_finds_the_catalogue_bank_or_adds_one_once() {
    let place = Place::new("add-bank");
    let mut session = open(&place);
    assert_eq!(session.add_bank("State Bank of India").expect("sbi"), "in.sbi");
    assert_eq!(session.add_bank("hdfc bank ltd").expect("hdfc"), "in.hdfc");
    let custom = session.add_bank("Saraswat Co-op Bank").expect("new");
    assert!(custom.starts_with("u:"));
    assert_eq!(
        session.add_bank("saraswat co-op").expect("again"),
        custom,
        "a second spelling finds the same bank"
    );
    assert_eq!(session.bank_name(&custom).as_deref(), Some("Saraswat Co-op Bank"));
    assert_eq!(session.bank_name("in.sbi").as_deref(), Some("SBI"));
    assert_eq!(session.add_bank("   "), Err(VaultError::SchemaViolation));
}

#[test]
fn the_last_bank_and_filter_are_kept_inside_the_vault() {
    let place = Place::new("last-view");
    let mut session = open(&place);
    assert_eq!(session.last_card_view(), None);
    session
        .set_last_card_view(&sbi(), CardFilter::Credit)
        .expect("remember");
    session.lock();
    let session =
        Vault::unlock_with_password(&place.vault(), PASSWORD, &FakePwd, false).expect("unlock");
    assert_eq!(session.last_card_view(), Some((sbi(), CardFilter::Credit)));
}

// ---------- lifetime ----------

#[test]
fn entries_are_kept_after_locking_and_unlocking() {
    let place = Place::new("persist");
    let mut session = open(&place);
    fixture(&mut session);
    session.lock();
    let session =
        Vault::unlock_with_password(&place.vault(), PASSWORD, &FakePwd, false).expect("unlock");
    assert_eq!(session.cards(&Scope::All, CardFilter::All).len(), 10);
    assert_eq!(session.bank_groups().len(), 3);
}

#[test]
fn a_missing_entry_is_reported_as_not_found() {
    let place = Place::new("missing");
    let mut session = open(&place);
    let ghost = [0x99; 16];
    assert!(session.summary(&ghost).is_none());
    assert_eq!(session.details(&ghost).err(), Some(VaultError::NotFound));
    assert_eq!(
        session.reveal_key(&ghost, "card.number").err(),
        Some(VaultError::NotFound)
    );
    let any = card("in.sbi", CardKind::Credit, "SBI Cashback", "4111111111111234");
    assert_eq!(
        session.update_entry(&ghost, &Entry::Card(any), NOW),
        Err(VaultError::NotFound)
    );
    assert_eq!(session.set_favourite(&ghost, true), Err(VaultError::NotFound));
}

// ---------- the card network, suggested from the number ----------

#[test]
fn the_network_is_suggested_from_the_first_digits() {
    for (number, network) in [
        ("4111111111111234", Some("Visa")),
        ("5105105105105100", Some("Mastercard")),
        ("2223000048400011", Some("Mastercard")),
        ("6074000000002002", Some("RuPay")),
        ("6521000000000001", Some("RuPay")),
        ("8200000000000001", Some("RuPay")),
        ("378282246310005", Some("American Express")),
        ("6011111111111117", Some("Discover")),
        ("30569309025904", Some("Diners Club")),
        ("9999999999999999", None),
        ("", None),
    ] {
        assert_eq!(suggest_network(number), network, "{number}");
    }
}
