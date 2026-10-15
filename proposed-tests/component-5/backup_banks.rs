//! Component 5: backups carry bank groups (vault-format §10.3, §10.4; product plan §2).
//!
//! Proposed for the owner to freeze. Once frozen it moves, unchanged, to
//! `core/vault-core/tests/backup_banks.rs`, and `vault_core::backup` is extended to pass it.
//!
//! What these tests fix in place:
//! - a backup keeps every card field, favourite, note and bank group, through real encryption;
//! - device-only data never leaves the phone: the backup location (settings key 12) and the last
//!   card view (organisation key 2) are stripped from every backup;
//! - a backup never refers to a bank it does not define;
//! - merging a backup into an existing vault never duplicates a bank: a custom bank that matches
//!   an existing one, however spelled, is mapped onto it, and every card follows its bank;
//! - backups made before bank groups existed (no payload key 4) still import.
//!
//! Each encrypted round trip runs Argon2id at the smallest allowed cost (64 MiB, t = 3).

use vault_core::VaultError;
use vault_core::backup::{self, ExportRandom};
use vault_core::cbor::Value;
use vault_core::keyslot::KdfParams;
use zeroize::Zeroizing;

const PASSWORD: &str = "correct horse battery staple 42";
const RS: [u8; 16] = [7; 16];
const SUNRISE_ID: &str = "u:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const T: u64 = 1_760_000_000;

// ---------- building blocks ----------

fn text(s: &str) -> Value {
    Value::Text(s.to_string())
}

fn kdf() -> KdfParams {
    KdfParams {
        m: 65_536,
        t: 3,
        p: 1,
        salt: [3; 16],
    }
}

fn rnd() -> ExportRandom {
    ExportRandom {
        fk: Zeroizing::new([9; 32]),
        nonce_wrap: [1; 24],
        nonce_payload: [2; 24],
    }
}

fn field(n: u8, key: &str, kind: u64, sensitivity: u64, value: Option<&str>) -> Value {
    let mut entries = vec![
        (1, Value::Bytes(vec![n; 16])),
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

/// A payload item map (vault-format §10.3): a valid credit card in bank `bank_id`.
fn card_item(item_id: u8, bank_id: Option<&str>, last4: &str, favorite: bool) -> Value {
    let number = format!("411111111111{last4}");
    let fields = vec![
        field(1, "card.product", 1, 1, Some("Cashback")),
        field(2, "card.number", 3, 3, None),
        field(3, "card.expiry", 4, 1, Some("08/29")),
        field(
            4,
            "card.user_note",
            1,
            1,
            Some("Check this card for Amazon offers"),
        ),
    ];
    let mut index = vec![
        (1, Value::Uint(2)),
        (2, Value::Uint(1)),
        (3, Value::Uint(1)),
        (4, text("Cashback")),
        (7, Value::Bool(favorite)),
        (8, Value::Uint(T)),
        (9, Value::Uint(T)),
        (12, Value::Uint(1)),
        (13, Value::Bool(false)),
        (14, Value::Array(fields)),
        (17, Value::Uint(2)),
        (18, text(last4)),
    ];
    if let Some(id) = bank_id {
        index.push((16, text(id)));
    }
    let secret = Value::map(vec![
        (1, Value::Uint(1)),
        (
            2,
            Value::Array(vec![Value::Array(vec![
                Value::Bytes(vec![2; 16]),
                text(&number),
            ])]),
        ),
    ]);
    Value::map(vec![
        (1, Value::Bytes(vec![item_id; 16])),
        (2, Value::map(index)),
        (3, secret),
        (4, Value::Array(vec![])),
    ])
}

fn custom_bank(id: &str, name: &str) -> Value {
    Value::map(vec![(1, text(id)), (2, text(name))])
}

fn organisation(banks: Vec<Value>, last_view: bool) -> Value {
    let mut entries = vec![(1, Value::Array(banks))];
    if last_view {
        entries.push((
            2,
            Value::map(vec![(1, text("in.sbi")), (2, Value::Uint(1))]),
        ));
    }
    Value::map(entries)
}

/// Settings with a device-only backup location (key 12) that must never be exported.
fn settings() -> Value {
    Value::map(vec![
        (1, Value::Uint(120)),
        (3, Value::Uint(30)),
        (
            12,
            text("content://com.google.android.apps.docs.storage/document/abc"),
        ),
        (13, Value::Bool(true)),
    ])
}

fn items() -> Vec<Value> {
    vec![
        card_item(1, Some("in.sbi"), "1234", true),
        card_item(2, Some(SUNRISE_ID), "5678", false),
        card_item(3, None, "9012", false),
    ]
}

fn bank_of(item: &Value) -> Option<&str> {
    match item.get(2).and_then(|index| index.get(16)) {
        Some(Value::Text(id)) => Some(id),
        _ => None,
    }
}

// ---------- building the payload ----------

#[test]
fn payload_carries_items_settings_and_custom_banks() {
    let org = organisation(vec![custom_bank(SUNRISE_ID, "Sunrise Co-op")], true);
    let payload = backup::build_payload(items(), &settings(), &org).expect("valid inputs");

    assert_eq!(payload.keys(), vec![1, 2, 3, 4]);
    assert_eq!(payload.get(1), Some(&Value::Uint(1)), "export_format 1");
    assert_eq!(
        payload.get(2),
        Some(&Value::Array(items())),
        "items unchanged"
    );
    assert_eq!(
        payload.get(4),
        Some(&organisation(
            vec![custom_bank(SUNRISE_ID, "Sunrise Co-op")],
            false
        )),
        "custom banks kept, last card view stripped"
    );
}

#[test]
fn payload_never_carries_the_backup_location() {
    let payload = backup::build_payload(
        items(),
        &settings(),
        &organisation(vec![custom_bank(SUNRISE_ID, "Sunrise Co-op")], false),
    )
    .expect("valid inputs");
    let exported_settings = payload.get(3).expect("settings present");
    assert_eq!(exported_settings.keys(), vec![1, 3, 13], "key 12 stripped");
    assert_eq!(exported_settings.get(13), Some(&Value::Bool(true)));
}

#[test]
fn payload_omits_organisation_when_there_are_no_custom_banks() {
    let only_catalogue = vec![card_item(1, Some("in.sbi"), "1234", true)];
    let payload = backup::build_payload(only_catalogue, &settings(), &organisation(vec![], true))
        .expect("valid inputs");
    assert_eq!(payload.keys(), vec![1, 2, 3]);
}

#[test]
fn payload_refuses_dangling_or_invalid_data() {
    let empty = organisation(vec![], false);
    assert_eq!(
        backup::build_payload(items(), &settings(), &empty),
        Err(VaultError::SchemaViolation),
        "a card in a custom bank the organisation does not define"
    );

    let mut bad_card = card_item(1, Some("in.sbi"), "1234", false);
    if let Value::Map(entries) = &mut bad_card
        && let Some((_, Value::Map(index))) = entries.iter_mut().find(|(k, _)| *k == 2)
        && let Some((_, last4)) = index.iter_mut().find(|(k, _)| *k == 18)
    {
        *last4 = text("0000");
    }
    assert_eq!(
        backup::build_payload(vec![bad_card], &settings(), &empty),
        Err(VaultError::SchemaViolation),
        "an item that breaks the item rules"
    );

    let bad_org = organisation(vec![custom_bank("in.sunrise", "Sunrise Co-op")], false);
    assert_eq!(
        backup::build_payload(vec![], &settings(), &bad_org),
        Err(VaultError::SchemaViolation),
        "an invalid organisation record"
    );
}

// ---------- through real encryption ----------

#[test]
fn bank_groups_survive_backup_and_restore() {
    let org = organisation(vec![custom_bank(SUNRISE_ID, "Sunrise Co-op")], true);
    let payload = backup::build_payload(items(), &settings(), &org).expect("valid inputs");
    let file = backup::export(&payload, PASSWORD, &RS, &kdf(), &rnd()).expect("export");
    let restored = backup::import(&file, PASSWORD, &RS, None).expect("import");

    assert_eq!(
        restored, payload,
        "every field, favourite, note and bank id comes back"
    );
    let restored_items = match restored.get(2) {
        Some(Value::Array(items)) => items.clone(),
        _ => panic!("items"),
    };
    let banks: Vec<Option<&str>> = restored_items.iter().map(bank_of).collect();
    assert_eq!(banks, vec![Some("in.sbi"), Some(SUNRISE_ID), None]);
}

#[test]
fn backups_without_bank_groups_still_import() {
    let payload = Value::map(vec![
        (1, Value::Uint(1)),
        (
            2,
            Value::Array(vec![card_item(1, Some("in.sbi"), "1234", false)]),
        ),
        (3, Value::map(vec![(1, Value::Uint(120))])),
    ]);
    let file = backup::export(&payload, PASSWORD, &RS, &kdf(), &rnd()).expect("export");
    assert_eq!(backup::import(&file, PASSWORD, &RS, None), Ok(payload));
}

#[test]
fn import_refuses_device_only_or_invalid_bank_data() {
    let base = |extra: (u64, Value)| {
        Value::map(vec![
            (1, Value::Uint(1)),
            (2, Value::Array(vec![])),
            (3, Value::map(vec![(1, Value::Uint(120))])),
            extra,
        ])
    };
    let cases = [
        (
            "a last card view inside the backup",
            base((
                4,
                organisation(vec![custom_bank(SUNRISE_ID, "Sunrise Co-op")], true),
            )),
        ),
        (
            "an invalid organisation record",
            base((
                4,
                organisation(vec![custom_bank("in.sunrise", "Sunrise Co-op")], false),
            )),
        ),
        ("an unknown payload key", base((5, Value::Uint(1)))),
    ];
    for (name, payload) in cases {
        let file = backup::export(&payload, PASSWORD, &RS, &kdf(), &rnd()).expect("export");
        assert_eq!(
            backup::import(&file, PASSWORD, &RS, None),
            Err(VaultError::SchemaViolation),
            "{name}"
        );
    }
}

// ---------- merging into an existing vault (vault-format §10.4) ----------

fn ids(list: &[[u8; 16]]) -> impl FnMut() -> vault_core::Result<[u8; 16]> + '_ {
    let mut next = list.iter();
    move || next.next().copied().ok_or(VaultError::RandomUnavailable)
}

#[test]
fn merge_maps_matching_banks_and_renames_new_ones() {
    let existing = organisation(vec![custom_bank(SUNRISE_ID, "Sunrise Co-op")], true);
    let imported = organisation(
        vec![
            custom_bank("u:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "SUNRISE CO OP"),
            custom_bank(
                "u:cccccccccccccccccccccccccccccccc",
                "Moonlight Credit Society",
            ),
            custom_bank("u:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "State Bank"),
        ],
        false,
    );
    let mut imported_items = vec![
        card_item(1, Some("u:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"), "1234", false),
        card_item(2, Some("u:cccccccccccccccccccccccccccccccc"), "5678", true),
        card_item(3, Some("u:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"), "9012", false),
        card_item(4, Some("in.hdfc"), "3456", false),
        card_item(5, None, "7890", false),
    ];

    let fresh = [[0xdd; 16]];
    let merged = backup::merge_banks(
        &existing,
        Some(&imported),
        &mut imported_items,
        &mut ids(&fresh),
    )
    .expect("merge");

    assert_eq!(
        merged,
        organisation(
            vec![
                custom_bank(SUNRISE_ID, "Sunrise Co-op"),
                custom_bank(
                    "u:dddddddddddddddddddddddddddddddd",
                    "Moonlight Credit Society"
                ),
            ],
            true,
        ),
        "the matching bank is not duplicated, the new one gets a fresh id, the last view stays"
    );
    let banks: Vec<Option<&str>> = imported_items.iter().map(bank_of).collect();
    assert_eq!(
        banks,
        vec![
            Some(SUNRISE_ID),                           // same bank, other spelling
            Some("u:dddddddddddddddddddddddddddddddd"), // new bank, fresh id
            Some("in.sbi"),                             // a custom bank the catalogue now has
            Some("in.hdfc"),                            // catalogue ids are kept
            None,                                       // Other / Unassigned stays unassigned
        ]
    );
}

#[test]
fn merge_without_an_imported_organisation() {
    let existing = organisation(vec![custom_bank(SUNRISE_ID, "Sunrise Co-op")], false);
    let mut imported_items = vec![card_item(1, Some("in.sbi"), "1234", false)];
    let merged =
        backup::merge_banks(&existing, None, &mut imported_items, &mut ids(&[])).expect("merge");
    assert_eq!(merged, existing);
    assert_eq!(imported_items.first().and_then(bank_of), Some("in.sbi"));
}

#[test]
fn merge_refuses_cards_in_undefined_banks() {
    let existing = organisation(vec![], false);
    let mut imported_items = vec![card_item(
        1,
        Some("u:ffffffffffffffffffffffffffffffff"),
        "1234",
        false,
    )];
    assert_eq!(
        backup::merge_banks(&existing, None, &mut imported_items, &mut ids(&[])),
        Err(VaultError::SchemaViolation)
    );
    assert_eq!(
        imported_items.first().and_then(bank_of),
        Some("u:ffffffffffffffffffffffffffffffff"),
        "nothing is changed when the merge is refused"
    );
}

#[test]
fn merge_refuses_a_fresh_id_already_in_use() {
    let existing = organisation(vec![custom_bank(SUNRISE_ID, "Sunrise Co-op")], false);
    let imported = organisation(
        vec![custom_bank(
            "u:cccccccccccccccccccccccccccccccc",
            "Moonlight Credit Society",
        )],
        false,
    );
    let mut imported_items = vec![card_item(
        1,
        Some("u:cccccccccccccccccccccccccccccccc"),
        "1234",
        false,
    )];
    let fresh = [[0xaa; 16]]; // collides with Sunrise
    assert_eq!(
        backup::merge_banks(
            &existing,
            Some(&imported),
            &mut imported_items,
            &mut ids(&fresh)
        ),
        Err(VaultError::SchemaViolation)
    );
}
