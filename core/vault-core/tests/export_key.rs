//! Component 6: the cached export key for auto-backup (crypto spec §9.5, PROPOSED, reviewer
//! question Q9; vault-format §5.6 key 4; threat-model T36).
//!
//! Proposed for the owner to freeze. Once frozen it moves, unchanged, to
//! `core/vault-core/tests/export_key.rs`, and `vault_core::backup::ExportKey` is written to pass it.
//!
//! What these tests fix in place:
//! - a backup made with the cached key is byte for byte the backup the password would make;
//! - every auto-backup still uses a fresh file key and nonces;
//! - such a backup still needs BOTH the vault password and the Recovery Key to open;
//! - the cached key is stored exactly as `{1: kdf, 2: kek}` and is read back strictly;
//! - the key never appears in debug output or logs.
//!
//! Each derivation runs Argon2id at the smallest allowed cost (64 MiB, t = 3).

use vault_core::VaultError;
use vault_core::backup::{self, ExportKey, ExportRandom};
use vault_core::cbor::Value;
use vault_core::keyslot::KdfParams;
use zeroize::Zeroizing;

const PASSWORD: &str = "correct horse battery staple 42";
const RS: [u8; 16] = [7; 16];

fn kdf() -> KdfParams {
    KdfParams {
        m: 65_536,
        t: 3,
        p: 1,
        salt: [3; 16],
    }
}

fn rnd(seed: u8) -> ExportRandom {
    ExportRandom {
        fk: Zeroizing::new([seed; 32]),
        nonce_wrap: [seed.wrapping_add(1); 24],
        nonce_payload: [seed.wrapping_add(2); 24],
    }
}

fn payload() -> Value {
    Value::map(vec![
        (1, Value::Uint(1)),
        (2, Value::Array(vec![])),
        (3, Value::map(vec![(1, Value::Uint(120))])),
    ])
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn kek_bytes(key: &ExportKey) -> Vec<u8> {
    match key.to_value().get(2) {
        Some(Value::Bytes(kek)) => kek.clone(),
        other => panic!("kek must be bytes, got {other:?}"),
    }
}

#[test]
fn cached_key_makes_the_same_file_as_the_password() {
    let key = ExportKey::derive(PASSWORD, &RS, &kdf()).expect("derive");
    let with_key =
        backup::export_with_key(&payload(), &RS, &key, &rnd(9)).expect("export with key");
    let with_password = backup::export(&payload(), PASSWORD, &RS, &kdf(), &rnd(9)).expect("export");
    assert_eq!(with_key, with_password);
    assert_eq!(key.kdf(), &kdf());
}

#[test]
fn auto_backups_use_fresh_randomness_and_still_need_both_secrets() {
    let key = ExportKey::derive(PASSWORD, &RS, &kdf()).expect("derive");
    let first =
        backup::export_with_key(&payload(), &RS, &key, &ExportRandom::fresh().expect("rng"))
            .expect("first auto-backup");
    let second =
        backup::export_with_key(&payload(), &RS, &key, &ExportRandom::fresh().expect("rng"))
            .expect("second auto-backup");
    assert_ne!(first, second, "fresh file key and nonces every time");

    assert_eq!(backup::import(&first, PASSWORD, &RS, None), Ok(payload()));
    assert_eq!(
        backup::import(&first, "wrong password entirely 42", &RS, None),
        Err(VaultError::AuthFailed),
        "the password is still needed"
    );
    assert_eq!(
        backup::import(&second, PASSWORD, &[8; 16], None),
        Err(VaultError::WrongRecoveryKey),
        "the Recovery Key is still needed"
    );
}

#[test]
fn stored_form_is_kdf_and_kek() {
    let key = ExportKey::derive(PASSWORD, &RS, &kdf()).expect("derive");
    let stored = key.to_value();
    assert_eq!(stored.keys(), vec![1, 2]);
    assert_eq!(stored.get(1), Some(&kdf().to_value()));
    assert_eq!(kek_bytes(&key).len(), 32);

    let restored = ExportKey::from_value(&stored).expect("read back");
    assert_eq!(restored.kdf(), &kdf());
    assert_eq!(kek_bytes(&restored), kek_bytes(&key));
    assert_eq!(
        backup::export_with_key(&payload(), &RS, &restored, &rnd(4)).expect("export"),
        backup::export_with_key(&payload(), &RS, &key, &rnd(4)).expect("export"),
        "a key read back from the vault makes identical backups"
    );
}

#[test]
fn stored_form_is_read_strictly() {
    let key = ExportKey::derive(PASSWORD, &RS, &kdf()).expect("derive");
    let kek = kek_bytes(&key);
    let with =
        |kdf_value: Value, kek_value: Value| Value::map(vec![(1, kdf_value), (2, kek_value)]);

    let mut short = kek.clone();
    short.pop();
    let cases = [
        (
            "kek of 31 bytes",
            with(kdf().to_value(), Value::Bytes(short)),
        ),
        (
            "kek not bytes",
            with(kdf().to_value(), Value::Text(hex(&kek))),
        ),
        ("missing kek", Value::map(vec![(1, kdf().to_value())])),
        (
            "missing kdf",
            Value::map(vec![(2, Value::Bytes(kek.clone()))]),
        ),
        (
            "unknown key",
            Value::map(vec![
                (1, kdf().to_value()),
                (2, Value::Bytes(kek.clone())),
                (3, Value::Uint(1)),
            ]),
        ),
        (
            "kdf not a map",
            with(Value::Uint(1), Value::Bytes(kek.clone())),
        ),
        ("not a map", Value::Bytes(kek.clone())),
    ];
    for (name, value) in cases {
        assert_eq!(
            ExportKey::from_value(&value).err(),
            Some(VaultError::SchemaViolation),
            "{name}"
        );
    }

    let weak = KdfParams { m: 32_768, ..kdf() };
    assert_eq!(
        ExportKey::from_value(&with(weak.to_value(), Value::Bytes(kek))).err(),
        Some(VaultError::ParamsOutOfRange),
        "a stored key cannot downgrade the Argon2id cost"
    );
}

#[test]
fn derive_refuses_out_of_range_parameters() {
    for weak in [
        KdfParams { m: 32_768, ..kdf() },
        KdfParams { t: 2, ..kdf() },
        KdfParams { p: 5, ..kdf() },
    ] {
        assert_eq!(
            ExportKey::derive(PASSWORD, &RS, &weak).err(),
            Some(VaultError::ParamsOutOfRange)
        );
    }
}

#[test]
fn the_key_never_shows_in_debug_output() {
    let key = ExportKey::derive(PASSWORD, &RS, &kdf()).expect("derive");
    let kek = kek_bytes(&key);
    let shown = format!("{key:?} {key:#?}");
    assert!(!shown.contains(&hex(&kek)), "no hex of the key");
    assert!(
        !shown.contains(&format!("{kek:?}")),
        "no byte list of the key"
    );
    assert!(shown.contains("redacted"), "says the key is hidden");
}
