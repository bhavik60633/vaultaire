//! Component 3: item rules for cards, PIN items and bank accounts
//! (vault-format §5.1 registries, §5.2 index envelope, §5.3 secret envelope, §5.10 templates).
//!
//! Proposed for the owner to freeze. Once frozen it moves, unchanged, to
//! `core/vault-core/tests/items.rs`, and `vault_core::item::validate` is written to pass it.
//!
//! `validate(index, secret)` checks one item's decrypted index and secret envelopes before they
//! are written. It returns `Ok(())` or `Err(VaultError::SchemaViolation)`.
//!
//! Every negative test starts from a valid item and changes exactly one thing, so the only
//! possible reason for the violation is that one change.

use vault_core::VaultError;
use vault_core::cbor::Value;
use vault_core::item::validate;

// ---------- building items ----------

const T: u64 = 1_760_000_000; // a timestamp

fn text(s: &str) -> Value {
    Value::Text(s.to_string())
}

fn id(n: u8) -> Vec<u8> {
    vec![n; 16]
}

/// A field descriptor (vault-format §5.2). `value` is given only for sensitivity 1.
fn field(n: u8, key: &str, kind: u64, sensitivity: u64, value: Option<&str>) -> Value {
    let mut entries = vec![
        (1, Value::Bytes(id(n))),
        (2, text(key)),
        (3, text(key)),
        (4, Value::Uint(kind)),
        (5, Value::Uint(sensitivity)),
        (6, Value::Bool(true)),
    ];
    if let Some(v) = value {
        entries.push((7, text(v)));
    }
    Value::map(entries)
}

/// A secret envelope with these `(field number, value)` pairs; the numbers must be ascending.
fn secret(values: &[(u8, &str)]) -> Value {
    let pairs = values
        .iter()
        .map(|(n, v)| Value::Array(vec![Value::Bytes(id(*n)), text(v)]))
        .collect();
    Value::map(vec![(1, Value::Uint(1)), (2, Value::Array(pairs))])
}

fn base_index(item_type: u64, title: &str, fields: Vec<Value>) -> Vec<(u64, Value)> {
    vec![
        (1, Value::Uint(item_type)),
        (2, Value::Uint(1)),
        (3, Value::Uint(1)),
        (4, text(title)),
        (7, Value::Bool(false)),
        (8, Value::Uint(T)),
        (9, Value::Uint(T)),
        (14, Value::Array(fields)),
    ]
}

/// A valid credit card: SBI Cashback, Visa, number 4111 1111 1111 1111.
fn card() -> (Value, Value) {
    let fields = vec![
        field(1, "card.product", 1, 1, Some("SBI Cashback")),
        field(2, "card.issuer", 1, 1, Some("SBI")),
        field(3, "card.holder", 1, 1, Some("ASHA RAO")),
        field(4, "card.number", 3, 3, None),
        field(5, "card.expiry", 4, 1, Some("08/29")),
        field(6, "card.network", 1, 1, Some("Visa")),
    ];
    let mut index = base_index(2, "SBI Cashback", fields);
    index.extend([
        (5, text("Credit · Visa · •••• 1111")),
        (12, Value::Uint(1)),
        (13, Value::Bool(false)),
        (16, text("in.sbi")),
        (17, Value::Uint(2)),
        (18, text("1111")),
    ]);
    (Value::map(index), secret(&[(4, "4111111111111111")]))
}

/// A valid locker PIN.
fn pin_item() -> (Value, Value) {
    let fields = vec![
        field(1, "pin.value", 15, 3, None),
        field(2, "pin.label", 1, 1, Some("SBI locker 214")),
    ];
    let mut index = base_index(9, "SBI locker 214", fields);
    index.push((19, Value::Uint(1)));
    (Value::map(index), secret(&[(1, "4821")]))
}

/// A valid ATM PIN item (purpose 4): its PIN must be critical-strict (4).
fn atm_pin_item() -> (Value, Value) {
    let fields = vec![
        field(1, "pin.value", 15, 4, None),
        field(2, "pin.label", 1, 1, Some("SBI Cashback ATM PIN")),
    ];
    let mut index = base_index(9, "SBI Cashback ATM PIN", fields);
    index.extend([
        (15, Value::Array(vec![Value::Bytes(id(99))])), // linked to a card
        (19, Value::Uint(4)),
    ]);
    (Value::map(index), secret(&[(1, "1234")]))
}

/// A valid Indian bank account.
fn bank_account() -> (Value, Value) {
    let fields = vec![
        field(1, "bank.issuer", 1, 1, Some("SBI")),
        field(2, "bank.holder", 1, 1, Some("ASHA RAO")),
        field(3, "bank.account_number", 10, 3, None),
        field(4, "bank.ifsc", 11, 1, Some("SBIN0001234")),
        field(5, "bank.mpin", 15, 3, None),
        field(6, "bank.rm_phone", 8, 1, Some("+91 98765 43210")),
        field(7, "bank.netbanking_user", 2, 2, None),
    ];
    let mut index = base_index(3, "SBI Salary", fields);
    index.push((16, text("in.sbi")));
    (
        Value::map(index),
        secret(&[(3, "30012345678"), (5, "123456"), (7, "asharao")]),
    )
}

/// A valid login with custom fields only (the login template is not part of this component).
fn login() -> (Value, Value) {
    let fields = vec![
        field(1, "custom", 1, 1, Some("asharao")),
        field(2, "custom", 2, 2, None),
    ];
    (
        Value::map(base_index(1, "Net banking", fields)),
        secret(&[(2, "correct horse battery staple")]),
    )
}

// ---------- editing items ----------

fn entries(map: &mut Value) -> &mut Vec<(u64, Value)> {
    match map {
        Value::Map(entries) => entries,
        _ => panic!("not a map"),
    }
}

/// Sets `key` in a map, keeping the keys sorted.
fn set(map: &mut Value, key: u64, value: Value) {
    let e = entries(map);
    e.retain(|(k, _)| *k != key);
    e.push((key, value));
    e.sort_by_key(|(k, _)| *k);
}

fn del(map: &mut Value, key: u64) {
    entries(map).retain(|(k, _)| *k != key);
}

fn fields(index: &mut Value) -> &mut Vec<Value> {
    let e = entries(index);
    match e.iter_mut().find(|(k, _)| *k == 14) {
        Some((_, Value::Array(items))) => items,
        _ => panic!("no fields"),
    }
}

/// The field descriptor with this template key.
fn field_mut<'a>(index: &'a mut Value, key: &str) -> &'a mut Value {
    fields(index)
        .iter_mut()
        .find(|f| f.get(2) == Some(&text(key)))
        .unwrap_or_else(|| panic!("no field {key}"))
}

/// Replaces the secret value of field number `n`.
fn set_secret(sec: &mut Value, n: u8, value: &str) {
    let pairs = match entries(sec).iter_mut().find(|(k, _)| *k == 2) {
        Some((_, Value::Array(pairs))) => pairs,
        _ => panic!("no values"),
    };
    for pair in pairs.iter_mut() {
        if let Value::Array(kv) = pair
            && kv.first() == Some(&Value::Bytes(id(n)))
        {
            *kv = vec![Value::Bytes(id(n)), text(value)];
            return;
        }
    }
    panic!("no secret value for field {n}");
}

fn ok(item: &(Value, Value)) -> bool {
    validate(&item.0, &item.1).is_ok()
}

fn assert_violation(name: &str, item: &(Value, Value)) {
    assert_eq!(
        validate(&item.0, &item.1),
        Err(VaultError::SchemaViolation),
        "{name} must be refused"
    );
}

// ---------- the valid items pass ----------

#[test]
fn valid_items_pass() {
    for (name, item) in [
        ("card", card()),
        ("locker PIN", pin_item()),
        ("ATM PIN", atm_pin_item()),
        ("bank account", bank_account()),
        ("login", login()),
    ] {
        assert_eq!(validate(&item.0, &item.1), Ok(()), "{name}");
    }
}

// ---------- cards ----------

#[test]
fn card_last4_must_match_the_number() {
    let mut item = card();
    set(&mut item.0, 18, text("1112"));
    assert_violation("last4 that does not match the number", &item);

    let mut item = card();
    set_secret(&mut item.1, 4, "5500000000000004");
    assert_violation("number changed without last4", &item);
    set(&mut item.0, 18, text("0004"));
    assert!(ok(&item), "number and last4 changed together");
}

#[test]
fn card_last4_is_required_and_exactly_four_digits() {
    let mut item = card();
    del(&mut item.0, 18);
    assert_violation("card without last4", &item);
    for bad in ["111", "11111", "11a1", ""] {
        let mut item = card();
        set(&mut item.0, 18, text(bad));
        assert_violation(&format!("last4 {bad:?}"), &item);
    }
}

#[test]
fn card_number_is_12_to_19_digits_without_spaces() {
    for good in ["411111111111", "4111111111111111", "4111111111111111111"] {
        let mut item = card();
        set_secret(&mut item.1, 4, good);
        set(&mut item.0, 18, text(good.split_at(good.len() - 4).1));
        assert!(ok(&item), "number {good:?} should pass");
    }
    for bad in [
        "41111111111",          // 11 digits
        "41111111111111111111", // 20 digits
        "4111 1111 1111 1111",  // spaces are display formatting only
        "4111-1111-1111-1111",
        "411111111111111a",
    ] {
        let mut item = card();
        set_secret(&mut item.1, 4, bad);
        assert_violation(&format!("number {bad:?}"), &item);
    }
}

#[test]
fn card_kind_is_required_and_known() {
    for kind in [1, 2, 3] {
        let mut item = card();
        set(&mut item.0, 17, Value::Uint(kind));
        assert!(ok(&item), "card kind {kind}");
    }
    let mut item = card();
    del(&mut item.0, 17);
    assert_violation("card without card_kind", &item);
    for kind in [0, 4] {
        let mut item = card();
        set(&mut item.0, 17, Value::Uint(kind));
        assert_violation(&format!("card kind {kind}"), &item);
    }
}

#[test]
fn card_bank_id_is_optional_but_well_formed() {
    let mut item = card();
    del(&mut item.0, 16);
    assert!(ok(&item), "no bank: Other / Unassigned");

    for good in [
        "in.hdfc",
        "in.not_in_this_catalogue",
        "u:0123456789abcdef0123456789abcdef",
    ] {
        let mut item = card();
        set(&mut item.0, 16, text(good));
        assert!(ok(&item), "bank id {good:?}");
    }
    for bad in ["SBI", "State Bank of India", "in.SBI", ""] {
        let mut item = card();
        set(&mut item.0, 16, text(bad));
        assert_violation(&format!("bank id {bad:?}"), &item);
    }
}

#[test]
fn card_never_holds_a_pin() {
    let mut item = card();
    fields(&mut item.0).push(field(7, "card.pin", 15, 4, None));
    set(
        &mut item.1,
        2,
        secret_pairs(&[(4, "4111111111111111"), (7, "1234")]),
    );
    assert_violation("card.pin on a card", &item);

    let mut item = card();
    fields(&mut item.0).push(field(7, "custom", 15, 4, None));
    set(
        &mut item.1,
        2,
        secret_pairs(&[(4, "4111111111111111"), (7, "1234")]),
    );
    assert_violation("a custom PIN field on a card", &item);
}

fn secret_pairs(values: &[(u8, &str)]) -> Value {
    secret(values)
        .get(2)
        .cloned()
        .unwrap_or(Value::Array(vec![]))
}

#[test]
fn card_cvv_matches_has_cvv() {
    let mut item = card();
    fields(&mut item.0).push(field(7, "card.cvv", 14, 3, None));
    set(
        &mut item.1,
        2,
        secret_pairs(&[(4, "4111111111111111"), (7, "123")]),
    );
    assert_violation("CVV stored but has_cvv false", &item);
    set(&mut item.0, 13, Value::Bool(true));
    assert!(ok(&item), "CVV stored and has_cvv true");

    let mut item = card();
    set(&mut item.0, 13, Value::Bool(true));
    assert_violation("has_cvv true but no CVV field", &item);
}

#[test]
fn card_needs_a_name() {
    let mut item = card();
    fields(&mut item.0).retain(|f| f.get(2) != Some(&text("card.product")));
    assert_violation("card with neither product nor nickname", &item);
    fields(&mut item.0).push(field(9, "card.nickname", 1, 1, Some("Travel card")));
    assert!(ok(&item), "a nickname alone is enough");
}

#[test]
fn card_expiry_is_mm_slash_yy() {
    for good in ["01/30", "12/26", "08/29"] {
        let mut item = card();
        set(field_mut(&mut item.0, "card.expiry"), 7, text(good));
        assert!(ok(&item), "expiry {good:?}");
    }
    for bad in ["13/29", "00/29", "8/29", "08/2029", "08-29", ""] {
        let mut item = card();
        set(field_mut(&mut item.0, "card.expiry"), 7, text(bad));
        assert_violation(&format!("expiry {bad:?}"), &item);
    }
}

#[test]
fn card_network_is_from_the_list() {
    for good in [
        "Visa",
        "Mastercard",
        "RuPay",
        "American Express",
        "Diners Club",
        "Discover",
        "JCB",
        "UnionPay",
    ] {
        let mut item = card();
        set(field_mut(&mut item.0, "card.network"), 7, text(good));
        assert!(ok(&item), "network {good:?}");
    }
    let mut item = card();
    fields(&mut item.0).retain(|f| f.get(2) != Some(&text("card.network")));
    assert!(ok(&item), "network unknown: field absent");
    for bad in ["VISA", "Visa Card", "Maestro", ""] {
        let mut item = card();
        set(field_mut(&mut item.0, "card.network"), 7, text(bad));
        assert_violation(&format!("network {bad:?}"), &item);
    }
}

#[test]
fn card_user_note_is_at_most_500_bytes() {
    let mut item = card();
    fields(&mut item.0).push(field(9, "card.user_note", 1, 1, Some(&"a".repeat(500))));
    assert!(ok(&item), "500-byte note");
    let mut item = card();
    fields(&mut item.0).push(field(9, "card.user_note", 1, 1, Some(&"a".repeat(501))));
    assert_violation("501-byte note", &item);
}

#[test]
fn card_rejects_keys_of_other_types() {
    let mut item = card();
    set(&mut item.0, 19, Value::Uint(1));
    assert_violation("pin_purpose on a card", &item);
    let mut item = card();
    del(&mut item.0, 12);
    assert_violation("card without card_finish", &item);
    let mut item = card();
    del(&mut item.0, 13);
    assert_violation("card without has_cvv", &item);
}

// ---------- PIN items ----------

#[test]
fn pin_is_4_to_12_digits() {
    for good in ["0000", "4821", "123456", "123456789012"] {
        let mut item = pin_item();
        set_secret(&mut item.1, 1, good);
        assert!(ok(&item), "PIN {good:?}");
    }
    for bad in ["123", "1234567890123", "12a4", "12 34", "", "١٢٣٤"] {
        let mut item = pin_item();
        set_secret(&mut item.1, 1, bad);
        assert_violation(&format!("PIN {bad:?}"), &item);
    }
}

#[test]
fn pin_purpose_is_required_and_known() {
    for purpose in 1..=8 {
        let mut item = pin_item();
        set(&mut item.0, 19, Value::Uint(purpose));
        if purpose == 4 {
            set(field_mut(&mut item.0, "pin.value"), 5, Value::Uint(4));
        }
        assert!(ok(&item), "PIN purpose {purpose}");
    }
    let mut item = pin_item();
    del(&mut item.0, 19);
    assert_violation("PIN item without pin_purpose", &item);
    for purpose in [0, 9] {
        let mut item = pin_item();
        set(&mut item.0, 19, Value::Uint(purpose));
        assert_violation(&format!("PIN purpose {purpose}"), &item);
    }
}

#[test]
fn atm_pin_is_critical_strict() {
    let mut item = atm_pin_item();
    set(field_mut(&mut item.0, "pin.value"), 5, Value::Uint(3));
    assert_violation(
        "ATM PIN at critical (3) instead of critical-strict (4)",
        &item,
    );

    let mut item = pin_item();
    set(field_mut(&mut item.0, "pin.value"), 5, Value::Uint(4));
    assert!(ok(&item), "any PIN may be raised to critical-strict");

    let mut item = pin_item();
    set(field_mut(&mut item.0, "pin.value"), 5, Value::Uint(2));
    assert_violation("PIN lowered to concealed", &item);
}

#[test]
fn pin_item_rejects_card_and_bank_keys() {
    for (key, value) in [
        (16, text("in.sbi")),
        (17, Value::Uint(2)),
        (18, text("1234")),
        (12, Value::Uint(1)),
        (13, Value::Bool(false)),
    ] {
        let mut item = pin_item();
        set(&mut item.0, key, value);
        assert_violation(&format!("index key {key} on a PIN item"), &item);
    }
}

#[test]
fn pin_item_needs_its_value() {
    let mut item = pin_item();
    fields(&mut item.0).retain(|f| f.get(2) != Some(&text("pin.value")));
    item.1 = secret(&[]);
    assert_violation("PIN item without pin.value", &item);
}

// ---------- bank accounts ----------

#[test]
fn ifsc_format() {
    for good in ["SBIN0001234", "HDFC0000001", "ICIC0ABC123"] {
        let mut item = bank_account();
        set(field_mut(&mut item.0, "bank.ifsc"), 7, text(good));
        assert!(ok(&item), "IFSC {good:?}");
    }
    for bad in [
        "SBIN1001234",
        "sbin0001234",
        "SBIN000123",
        "SBIN00012345",
        "SB1N0001234",
        "",
    ] {
        let mut item = bank_account();
        set(field_mut(&mut item.0, "bank.ifsc"), 7, text(bad));
        assert_violation(&format!("IFSC {bad:?}"), &item);
    }
}

#[test]
fn bank_account_needs_its_number() {
    let mut item = bank_account();
    fields(&mut item.0).retain(|f| f.get(2) != Some(&text("bank.account_number")));
    item.1 = secret(&[(5, "123456"), (7, "asharao")]);
    assert_violation("bank account without an account number", &item);
}

#[test]
fn bank_pins_follow_the_pin_rules() {
    let mut item = bank_account();
    set_secret(&mut item.1, 5, "12ab");
    assert_violation("MPIN with letters", &item);

    let mut item = bank_account();
    set(field_mut(&mut item.0, "bank.mpin"), 5, Value::Uint(2));
    assert_violation("MPIN lowered to concealed", &item);
}

#[test]
fn bank_account_rejects_card_and_pin_keys() {
    for (key, value) in [
        (17, Value::Uint(2)),
        (18, text("1234")),
        (19, Value::Uint(5)),
    ] {
        let mut item = bank_account();
        set(&mut item.0, key, value);
        assert_violation(&format!("index key {key} on a bank account"), &item);
    }
}

// ---------- rules for every item ----------

#[test]
fn other_types_reject_bank_card_and_pin_keys() {
    for (key, value) in [
        (16, text("in.sbi")),
        (17, Value::Uint(2)),
        (18, text("1234")),
        (19, Value::Uint(1)),
    ] {
        let mut item = login();
        set(&mut item.0, key, value);
        assert_violation(&format!("index key {key} on a login"), &item);
    }
}

#[test]
fn sensitivity_may_be_raised_never_lowered() {
    // Raising the cardholder name to concealed moves its value into the secret envelope.
    let mut item = card();
    let holder = field_mut(&mut item.0, "card.holder");
    set(holder, 5, Value::Uint(2));
    del(holder, 7);
    item.1 = secret(&[(3, "ASHA RAO"), (4, "4111111111111111")]);
    assert!(ok(&item), "holder raised to concealed");

    let mut item = card();
    set(field_mut(&mut item.0, "card.number"), 5, Value::Uint(2));
    assert_violation("card number lowered to concealed", &item);

    let mut item = bank_account();
    set(
        field_mut(&mut item.0, "bank.account_number"),
        5,
        Value::Uint(2),
    );
    assert_violation("account number lowered to concealed", &item);
}

#[test]
fn values_live_where_their_sensitivity_says() {
    let mut item = card();
    set(
        field_mut(&mut item.0, "card.number"),
        7,
        text("4111111111111111"),
    );
    assert_violation("critical value in the index envelope", &item);

    let mut item = card();
    del(field_mut(&mut item.0, "card.holder"), 7);
    assert_violation("normal field without its value", &item);

    let mut item = card();
    item.1 = secret(&[]);
    assert_violation("critical field missing from the secret envelope", &item);

    let mut item = card();
    item.1 = secret(&[(3, "ASHA RAO"), (4, "4111111111111111")]);
    assert_violation("secret value for a normal field", &item);
}

#[test]
fn secret_values_are_sorted_and_unique() {
    let mut item = bank_account();
    item.1 = secret(&[(5, "123456"), (3, "30012345678"), (7, "asharao")]);
    assert_violation("secret values out of order", &item);

    let mut item = bank_account();
    item.1 = secret(&[
        (3, "30012345678"),
        (3, "30012345678"),
        (5, "123456"),
        (7, "asharao"),
    ]);
    assert_violation("secret value listed twice", &item);
}

#[test]
fn field_rules() {
    let mut item = card();
    set(field_mut(&mut item.0, "card.holder"), 5, Value::Uint(5));
    assert_violation("sensitivity 5", &item);

    let mut item = card();
    set(field_mut(&mut item.0, "card.number"), 4, Value::Uint(1));
    assert_violation("card.number with the wrong kind", &item);

    let mut item = card();
    fields(&mut item.0).push(field(9, "card.holder", 1, 1, Some("SOMEONE ELSE")));
    assert_violation("template key used twice", &item);

    let mut item = card();
    fields(&mut item.0).push(field(1, "custom", 1, 1, Some("x")));
    assert_violation("field id used twice", &item);

    let mut item = card();
    fields(&mut item.0).push(field(9, "card.colour", 1, 1, Some("blue")));
    assert_violation("unknown template key", &item);

    let mut item = card();
    fields(&mut item.0).push(field(9, "pin.value", 15, 3, None));
    set(
        &mut item.1,
        2,
        secret_pairs(&[(4, "4111111111111111"), (9, "1234")]),
    );
    assert_violation("another type's template key", &item);

    let mut item = card();
    fields(&mut item.0).push(field(9, "custom", 13, 2, None));
    set(
        &mut item.1,
        2,
        secret_pairs(&[(4, "4111111111111111"), (9, "JBSWY3DPEHPK3PXP")]),
    );
    assert_violation("TOTP secrets are reserved for V1.1", &item);
}

#[test]
fn envelope_rules() {
    let mut item = card();
    set(&mut item.1, 1, Value::Uint(2));
    assert_violation("index and secret revisions differ", &item);

    let mut item = card();
    set(&mut item.0, 4, text(&"a".repeat(257)));
    assert_violation("title over 256 bytes", &item);

    let mut item = card();
    set(&mut item.0, 4, text(&"a".repeat(256)));
    assert!(ok(&item), "title of exactly 256 bytes");

    let mut item = card();
    set(&mut item.0, 2, Value::Uint(2));
    assert_violation("unknown template version", &item);

    let mut item = card();
    set(&mut item.0, 1, Value::Uint(10));
    assert_violation("unknown item type", &item);

    let mut item = card();
    set(&mut item.0, 20, Value::Uint(1));
    assert_violation("unknown index key", &item);
}
