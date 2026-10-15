//! Component 4: the Organisation record (vault-format §5.9): banks the user adds themselves, and
//! the last card view restored after unlock. ADR-011 decision 3.
//!
//! Proposed for the owner to freeze. Once frozen it moves, unchanged, to
//! `core/vault-core/tests/organisation.rs`, and `vault_core::organisation` is written to pass it.
//!
//! What these tests fix in place:
//! - the record's exact shape, and every limit in §5.9;
//! - no two custom banks share a name, however spelled; a vault stays valid when a later catalogue
//!   gains one of its custom banks;
//! - adding a bank that already exists returns the existing one instead of a duplicate;
//! - every bank id shows a name: catalogue names, custom names, and capitals for an unknown
//!   catalogue id;
//! - the last card view holds only a known scope and filter.

use vault_core::VaultError;
use vault_core::cbor::Value;
use vault_core::organisation::{self, Added};

const SUNRISE: [u8; 16] = [0xaa; 16];
const SUNRISE_ID: &str = "u:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const MOON_ID: &str = "u:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

fn text(s: &str) -> Value {
    Value::Text(s.to_string())
}

fn custom_bank(id: &str, name: &str, aliases: &[&str]) -> Value {
    let mut entries = vec![(1, text(id)), (2, text(name))];
    if !aliases.is_empty() {
        entries.push((3, Value::Array(aliases.iter().map(|a| text(a)).collect())));
    }
    Value::map(entries)
}

fn org(banks: Vec<Value>, last_view: Option<(&str, u64)>) -> Value {
    let mut entries = vec![(1, Value::Array(banks))];
    if let Some((scope, filter)) = last_view {
        entries.push((
            2,
            Value::map(vec![(1, text(scope)), (2, Value::Uint(filter))]),
        ));
    }
    Value::map(entries)
}

/// A valid record: two custom banks and a last view.
fn sample() -> Value {
    org(
        vec![
            custom_bank(SUNRISE_ID, "Sunrise Co-op", &["Sunrise Cooperative"]),
            custom_bank(MOON_ID, "Moonlight Credit Society", &[]),
        ],
        Some(("in.sbi", 1)),
    )
}

fn assert_invalid(name: &str, value: &Value) {
    assert_eq!(
        organisation::validate(value),
        Err(VaultError::SchemaViolation),
        "{name} must be refused"
    );
}

fn added_new(result: Added) -> (Value, String) {
    match result {
        Added::New { organisation, id } => (organisation, id),
        Added::Existing(id) => panic!("expected a new bank, got existing {id}"),
    }
}

// ---------- shape (vault-format §5.9) ----------

#[test]
fn empty_record() {
    let empty = organisation::empty();
    assert_eq!(empty, org(vec![], None));
    assert_eq!(organisation::validate(&empty), Ok(()));
}

#[test]
fn sample_record_is_valid() {
    assert_eq!(organisation::validate(&sample()), Ok(()));
    assert_eq!(
        organisation::validate(&org(vec![], Some(("all", 0)))),
        Ok(())
    );
    assert_eq!(
        organisation::validate(&org(vec![], Some(("unassigned", 2)))),
        Ok(())
    );
    assert_eq!(
        organisation::validate(&org(vec![], Some((SUNRISE_ID, 0)))),
        Ok(())
    );
}

#[test]
fn record_shape_is_strict() {
    assert_invalid("not a map", &Value::Array(vec![]));
    assert_invalid("missing custom_banks", &Value::map(vec![]));
    assert_invalid(
        "unknown key",
        &Value::map(vec![(1, Value::Array(vec![])), (3, Value::Uint(1))]),
    );
    assert_invalid(
        "custom_banks not an array",
        &Value::map(vec![(1, Value::Uint(0))]),
    );
}

#[test]
fn custom_bank_shape_is_strict() {
    let cases = [
        (
            "id from the catalogue namespace",
            custom_bank("in.sunrise", "Sunrise Co-op", &[]),
        ),
        (
            "uppercase id",
            custom_bank("u:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "Sunrise Co-op", &[]),
        ),
        ("short id", custom_bank("u:aaaa", "Sunrise Co-op", &[])),
        ("empty name", custom_bank(SUNRISE_ID, "", &[])),
        ("name of only symbols", custom_bank(SUNRISE_ID, "&-./", &[])),
        (
            "65-byte name",
            custom_bank(SUNRISE_ID, &"s".repeat(65), &[]),
        ),
        (
            "empty alias",
            custom_bank(SUNRISE_ID, "Sunrise Co-op", &[""]),
        ),
        (
            "65-byte alias",
            custom_bank(SUNRISE_ID, "Sunrise Co-op", &[&"s".repeat(65)]),
        ),
        (
            "unknown key",
            Value::map(vec![
                (1, text(SUNRISE_ID)),
                (2, text("Sunrise Co-op")),
                (4, Value::Uint(1)),
            ]),
        ),
        (
            "empty alias array",
            Value::map(vec![
                (1, text(SUNRISE_ID)),
                (2, text("Sunrise Co-op")),
                (3, Value::Array(vec![])),
            ]),
        ),
    ];
    for (name, bank) in cases {
        assert_invalid(name, &org(vec![bank], None));
    }

    let longest = custom_bank(SUNRISE_ID, &"s".repeat(64), &[&"t".repeat(64)]);
    assert_eq!(organisation::validate(&org(vec![longest], None)), Ok(()));
}

#[test]
fn limits() {
    let sixteen: Vec<String> = (0..16).map(|i| format!("Sunrise alias {i}")).collect();
    let refs: Vec<&str> = sixteen.iter().map(String::as_str).collect();
    let ok = custom_bank(SUNRISE_ID, "Sunrise Co-op", &refs);
    assert_eq!(organisation::validate(&org(vec![ok], None)), Ok(()));

    let seventeen: Vec<String> = (0..17).map(|i| format!("Sunrise alias {i}")).collect();
    let refs: Vec<&str> = seventeen.iter().map(String::as_str).collect();
    let too_many = custom_bank(SUNRISE_ID, "Sunrise Co-op", &refs);
    assert_invalid("17 aliases", &org(vec![too_many], None));

    let banks = |n: u32| -> Vec<Value> {
        (0..n)
            .map(|i| custom_bank(&format!("u:{i:032x}"), &format!("Village Society {i}"), &[]))
            .collect()
    };
    assert_eq!(organisation::validate(&org(banks(200), None)), Ok(()));
    assert_invalid("201 custom banks", &org(banks(201), None));
}

#[test]
fn no_duplicate_banks() {
    assert_invalid(
        "same id twice",
        &org(
            vec![
                custom_bank(SUNRISE_ID, "Sunrise Co-op", &[]),
                custom_bank(SUNRISE_ID, "Moonlight Credit Society", &[]),
            ],
            None,
        ),
    );
    assert_invalid(
        "same normalised name twice",
        &org(
            vec![
                custom_bank(SUNRISE_ID, "Sunrise Co-op", &[]),
                custom_bank(MOON_ID, "SUNRISE CO OP", &[]),
            ],
            None,
        ),
    );
    assert_invalid(
        "an alias that is another custom bank's name",
        &org(
            vec![
                custom_bank(SUNRISE_ID, "Sunrise Co-op", &[]),
                custom_bank(MOON_ID, "Moonlight Credit Society", &["Sunrise Co-op"]),
            ],
            None,
        ),
    );
}

#[test]
fn catalogue_growth_never_invalidates_a_vault() {
    // A later app version may add to its catalogue a bank the user had already added themselves.
    // That vault must stay valid: the duplicate check happens when a bank is added, not here.
    let record = org(
        vec![custom_bank(SUNRISE_ID, "State Bank of India", &["HDFC"])],
        None,
    );
    assert_eq!(organisation::validate(&record), Ok(()));
    assert_eq!(
        organisation::resolve(&record, "State Bank of India"),
        Some("in.sbi".to_string()),
        "the catalogue is checked first"
    );
    assert_eq!(
        organisation::display_name(&record, SUNRISE_ID),
        Some("State Bank of India".to_string()),
        "cards already in the custom bank still show its name"
    );
}

#[test]
fn last_card_view_shape() {
    for (name, view) in [
        ("free-text scope", ("SBI", 0)),
        ("empty scope", ("", 0)),
        ("scope in capitals", ("ALL", 0)),
        ("filter 3", ("all", 3)),
    ] {
        assert_invalid(name, &org(vec![], Some(view)));
    }
    assert_invalid(
        "unknown key in last_card_view",
        &Value::map(vec![
            (1, Value::Array(vec![])),
            (
                2,
                Value::map(vec![
                    (1, text("all")),
                    (2, Value::Uint(0)),
                    (3, Value::Uint(0)),
                ]),
            ),
        ]),
    );
    assert_invalid(
        "last_card_view without a filter",
        &Value::map(vec![
            (1, Value::Array(vec![])),
            (2, Value::map(vec![(1, text("all"))])),
        ]),
    );
}

// ---------- adding a bank ----------

#[test]
fn adding_a_new_bank() {
    let (record, id) = added_new(
        organisation::add_custom_bank(&organisation::empty(), "Sunrise Co-op", SUNRISE)
            .expect("a new name is accepted"),
    );
    assert_eq!(id, SUNRISE_ID);
    assert_eq!(organisation::validate(&record), Ok(()));
    assert_eq!(
        record,
        org(vec![custom_bank(SUNRISE_ID, "Sunrise Co-op", &[])], None)
    );
    assert_eq!(
        organisation::resolve(&record, "sunrise co op"),
        Some(SUNRISE_ID.to_string())
    );
}

#[test]
fn adding_keeps_existing_banks_and_the_last_view() {
    let (record, _) = added_new(
        organisation::add_custom_bank(&sample(), "Riverbank Mutual", [0xcc; 16])
            .expect("a new name is accepted"),
    );
    assert_eq!(organisation::validate(&record), Ok(()));
    assert_eq!(
        organisation::resolve(&record, "Sunrise Cooperative"),
        Some(SUNRISE_ID.to_string())
    );
    assert_eq!(
        organisation::last_card_view(&record),
        Some(("in.sbi".to_string(), 1))
    );
}

#[test]
fn adding_a_catalogue_bank_returns_it() {
    for name in ["State Bank", "SBI", "state bank of india"] {
        assert_eq!(
            organisation::add_custom_bank(&sample(), name, [0xcc; 16]),
            Ok(Added::Existing("in.sbi".to_string())),
            "{name}"
        );
    }
}

#[test]
fn adding_a_known_custom_bank_returns_it() {
    for name in [
        "SUNRISE CO-OP",
        "sunrise cooperative",
        "Moonlight Credit Society",
    ] {
        let expected = if name.starts_with('M') {
            MOON_ID
        } else {
            SUNRISE_ID
        };
        assert_eq!(
            organisation::add_custom_bank(&sample(), name, [0xcc; 16]),
            Ok(Added::Existing(expected.to_string())),
            "{name}"
        );
    }
}

#[test]
fn adding_refuses_bad_names_and_id_collisions() {
    for name in ["", "   ", "&-./"] {
        assert_eq!(
            organisation::add_custom_bank(&sample(), name, [0xcc; 16]),
            Err(VaultError::SchemaViolation),
            "name {name:?}"
        );
    }
    assert_eq!(
        organisation::add_custom_bank(&sample(), &"r".repeat(65), [0xcc; 16]),
        Err(VaultError::SchemaViolation),
        "65-byte name"
    );
    assert_eq!(
        organisation::add_custom_bank(&sample(), "Riverbank Mutual", SUNRISE),
        Err(VaultError::SchemaViolation),
        "random id that is already in use"
    );
    assert_eq!(
        organisation::add_custom_bank(&Value::Uint(0), "Riverbank Mutual", [0xcc; 16]),
        Err(VaultError::SchemaViolation),
        "not an organisation record"
    );
}

// ---------- resolving names and showing banks ----------

#[test]
fn resolve_checks_the_catalogue_then_custom_banks() {
    let record = sample();
    let cases = [
        ("HDFC Bank Ltd", Some("in.hdfc")),
        ("State Bank of India", Some("in.sbi")),
        ("Sunrise Co-op", Some(SUNRISE_ID)),
        ("SUNRISE COOPERATIVE", Some(SUNRISE_ID)),
        ("moonlight credit society", Some(MOON_ID)),
        ("Riverbank Mutual", None),
        ("", None),
    ];
    for (name, expected) in cases {
        assert_eq!(
            organisation::resolve(&record, name),
            expected.map(str::to_string),
            "resolve({name:?})"
        );
    }
}

#[test]
fn display_names() {
    let record = sample();
    let cases = [
        ("in.sbi", Some("SBI")),
        ("in.hdfc", Some("HDFC Bank")),
        (SUNRISE_ID, Some("Sunrise Co-op")),
        (MOON_ID, Some("Moonlight Credit Society")),
        // A catalogue id this build does not know is kept and shown by its code (§5.1).
        ("in.new_bank", Some("NEW_BANK")),
        ("u:cccccccccccccccccccccccccccccccc", None),
        ("SBI", None),
        ("", None),
    ];
    for (id, expected) in cases {
        assert_eq!(
            organisation::display_name(&record, id),
            expected.map(str::to_string),
            "display_name({id:?})"
        );
    }
}

// ---------- the last card view ----------

#[test]
fn last_card_view_round_trip() {
    assert_eq!(organisation::last_card_view(&organisation::empty()), None);
    assert_eq!(
        organisation::last_card_view(&sample()),
        Some(("in.sbi".to_string(), 1))
    );

    for (scope, filter) in [("all", 0), ("unassigned", 2), ("in.axis", 2), (MOON_ID, 1)] {
        let record = organisation::set_last_card_view(&sample(), scope, filter)
            .expect("a valid view is stored");
        assert_eq!(organisation::validate(&record), Ok(()));
        assert_eq!(
            organisation::last_card_view(&record),
            Some((scope.to_string(), filter))
        );
        assert_eq!(
            organisation::resolve(&record, "Sunrise Co-op"),
            Some(SUNRISE_ID.to_string()),
            "custom banks are untouched"
        );
    }
}

#[test]
fn set_last_card_view_refuses_bad_views() {
    for (scope, filter) in [("SBI", 0), ("", 0), ("all", 3), ("in.SBI", 1)] {
        assert_eq!(
            organisation::set_last_card_view(&sample(), scope, filter),
            Err(VaultError::SchemaViolation),
            "view ({scope:?}, {filter})"
        );
    }
}
