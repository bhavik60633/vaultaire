//! Component 7 (milestone M1): the encrypted vault on disk and its unlocked session
//! (crypto spec §4, §5, §7, §9, §10; vault-format §3, §4, §7, §10.4; threat-model I1–I4).
//!
//! Proposed for the owner to freeze. Once frozen it moves, unchanged, to
//! `core/vault-core/tests/vault_store.rs`, and `vault_core::vault` is written to pass it.
//!
//! The phone's hardware keys are played here by two small fakes: the real app implements the
//! same two interfaces with Android Keystore and the fingerprint prompt.
//!
//! What these tests fix in place:
//! - a vault is created with a Recovery Key, and opens three ways: fingerprint, password with the
//!   phone's screen lock, and password with the Recovery Key;
//! - a wrong password or Recovery Key opens nothing, and every password guess costs one hardware
//!   operation;
//! - entries are checked before they are saved, survive closing and reopening, and only the one
//!   value asked for is ever decrypted;
//! - nothing readable is ever written to disk;
//! - a tampered vault fails closed;
//! - changing the password keeps the Recovery Key working;
//! - a backup restores onto a new phone with the same Recovery Key;
//! - software-only keys (the emulator) are refused unless the build allows them.
//!
//! Each create, password unlock and backup runs Argon2id at the smallest allowed cost.

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};

use vault_core::VaultError;
use vault_core::cbor::Value;
use vault_core::prims;
use vault_core::recovery_key;
use vault_core::vault::{
    BioHardware, CreateOptions, KeyLevel, PwdHardware, SealedSk, Session, Vault,
};

const PASSWORD: &str = "correct horse battery staple 42";
const OTHER_PASSWORD: &str = "a completely different passphrase 7";
const NOW: u64 = 1_760_000_000;
const CARD_ID: [u8; 16] = [0x11; 16];
const NUMBER: &str = "4111111111111234";

// ---------- fake hardware ----------

/// Plays `K_pwd`: an HMAC key that never leaves the hardware, used once per password attempt.
struct FakePwd {
    key: [u8; 32],
    level: KeyLevel,
    calls: AtomicU32,
}

impl FakePwd {
    fn new(level: KeyLevel) -> FakePwd {
        FakePwd {
            key: [0x5a; 32],
            level,
            calls: AtomicU32::new(0),
        }
    }
    fn calls(&self) -> u32 {
        self.calls.load(Ordering::SeqCst)
    }
}

impl PwdHardware for FakePwd {
    fn level(&self) -> KeyLevel {
        self.level
    }
    fn hmac(&self, hw_in: &[u8; 32]) -> vault_core::Result<[u8; 32]> {
        self.calls.fetch_add(1, Ordering::SeqCst);
        Ok(prims::hmac_sha256(&self.key, hw_in))
    }
}

/// Plays `K_bio`: seals the slot key at creation and releases it after a fingerprint. Once
/// "invalidated" (a new fingerprint was enrolled), it never releases it again.
struct FakeBio {
    key: [u8; 32],
    level: KeyLevel,
    invalidated: AtomicBool,
}

impl FakeBio {
    fn new(level: KeyLevel) -> FakeBio {
        FakeBio {
            key: [0xb1; 32],
            level,
            invalidated: AtomicBool::new(false),
        }
    }
    fn keystream(&self, iv: &[u8; 12]) -> [u8; 32] {
        prims::hmac_sha256(&self.key, iv)
    }
    fn tag(&self, iv: &[u8; 12], ct: &[u8]) -> Vec<u8> {
        let mut msg = iv.to_vec();
        msg.extend_from_slice(ct);
        prims::hmac_sha256(&self.key, &msg)
            .iter()
            .take(16)
            .copied()
            .collect()
    }
}

impl BioHardware for FakeBio {
    fn level(&self) -> KeyLevel {
        self.level
    }
    fn seal_sk(&self, sk: &[u8; 32]) -> vault_core::Result<SealedSk> {
        let iv = [0x07; 12];
        let ks = self.keystream(&iv);
        let mut blob: Vec<u8> = sk.iter().zip(ks.iter()).map(|(a, b)| a ^ b).collect();
        let tag = self.tag(&iv, &blob);
        blob.extend_from_slice(&tag);
        Ok(SealedSk { iv, blob })
    }
    fn open_sk(&self, sealed: &SealedSk) -> vault_core::Result<[u8; 32]> {
        if self.invalidated.load(Ordering::SeqCst) {
            return Err(VaultError::UnlockFailed);
        }
        let (ct, tag) = sealed.blob.split_at(32);
        if tag != self.tag(&sealed.iv, ct).as_slice() {
            return Err(VaultError::UnlockFailed);
        }
        let ks = self.keystream(&sealed.iv);
        let mut sk = [0u8; 32];
        for ((out, c), k) in sk.iter_mut().zip(ct).zip(ks.iter()) {
            *out = c ^ k;
        }
        Ok(sk)
    }
}

// ---------- a place on disk ----------

/// A fresh directory for one test; removed when the test ends.
struct Place {
    dir: PathBuf,
}

impl Place {
    fn new(name: &str) -> Place {
        let dir = std::env::temp_dir().join(format!(
            "vaultaire-test-{name}-{}-{}",
            std::process::id(),
            NOW
        ));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).expect("temp dir");
        Place { dir }
    }
    fn vault(&self) -> PathBuf {
        self.dir.join("vault.db")
    }
    /// Every byte of every file the vault left in its directory (database, WAL, journal).
    fn all_bytes(&self) -> Vec<u8> {
        let mut all = Vec::new();
        for entry in std::fs::read_dir(&self.dir).expect("read dir").flatten() {
            if let Ok(bytes) = std::fs::read(entry.path()) {
                all.extend(bytes);
            }
        }
        all
    }
}

impl Drop for Place {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

fn contains(haystack: &[u8], needle: &[u8]) -> bool {
    haystack.windows(needle.len()).any(|w| w == needle)
}

// ---------- entries ----------

fn text(s: &str) -> Value {
    Value::Text(s.to_string())
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

/// A valid SBI credit card: index and secret envelopes (vault-format §5.2, §5.3, §5.10).
fn card(bank_id: &str, revision: u64) -> (Value, Value) {
    let index = Value::map(vec![
        (1, Value::Uint(2)),
        (2, Value::Uint(1)),
        (3, Value::Uint(revision)),
        (4, text("SBI Cashback")),
        (7, Value::Bool(true)),
        (8, Value::Uint(NOW)),
        (9, Value::Uint(NOW)),
        (12, Value::Uint(1)),
        (13, Value::Bool(false)),
        (
            14,
            Value::Array(vec![
                field(1, "card.product", 1, 1, Some("SBI Cashback")),
                field(2, "card.holder", 1, 1, Some("ASHA RAO")),
                field(3, "card.number", 3, 3, None),
                field(4, "card.expiry", 4, 1, Some("08/29")),
            ]),
        ),
        (16, text(bank_id)),
        (17, Value::Uint(2)),
        (18, text("1234")),
    ]);
    let secret = Value::map(vec![
        (1, Value::Uint(revision)),
        (
            2,
            Value::Array(vec![Value::Array(vec![
                Value::Bytes(vec![3; 16]),
                text(NUMBER),
            ])]),
        ),
    ]);
    (index, secret)
}

const NUMBER_FIELD: [u8; 16] = [3; 16];

fn options() -> CreateOptions {
    CreateOptions {
        allow_software_keys: false,
        now: NOW,
    }
}

fn create(place: &Place, pwd: &FakePwd, bio: &FakeBio) -> (Session, String) {
    let created = Vault::create(&place.vault(), PASSWORD, pwd, Some(bio), &options())
        .expect("create the vault");
    (created.session, created.recovery_key)
}

fn titles(session: &Session) -> Vec<String> {
    session
        .items()
        .iter()
        .filter_map(|item| match item.index.get(4) {
            Some(Value::Text(t)) => Some(t.clone()),
            _ => None,
        })
        .collect()
}

// ---------- creating ----------

#[test]
fn create_gives_a_recovery_key_and_an_empty_open_vault() {
    let place = Place::new("create");
    let pwd = FakePwd::new(KeyLevel::Tee);
    let bio = FakeBio::new(KeyLevel::StrongBox);
    let (session, rk) = create(&place, &pwd, &bio);

    assert!(
        recovery_key::decode(&rk).is_ok(),
        "a well-formed Recovery Key: {rk}"
    );
    assert_eq!(rk.len(), 34, "28 symbols in 7 groups of 4");
    assert!(session.items().is_empty());
    assert_eq!(session.organisation(), vault_core::organisation::empty());
    assert!(place.vault().exists());
}

#[test]
fn create_refuses_to_overwrite_a_vault() {
    let place = Place::new("overwrite");
    let pwd = FakePwd::new(KeyLevel::Tee);
    let bio = FakeBio::new(KeyLevel::Tee);
    let (session, _) = create(&place, &pwd, &bio);
    session.lock();
    assert_eq!(
        Vault::create(&place.vault(), PASSWORD, &pwd, Some(&bio), &options()).err(),
        Some(VaultError::VaultExists)
    );
}

#[test]
fn software_keys_only_where_the_build_allows_them() {
    let place = Place::new("software");
    let pwd = FakePwd::new(KeyLevel::Software);
    let bio = FakeBio::new(KeyLevel::Software);
    assert_eq!(
        Vault::create(&place.vault(), PASSWORD, &pwd, Some(&bio), &options()).err(),
        Some(VaultError::InsecureDevice),
        "a release build refuses software keys"
    );
    assert!(!place.vault().exists(), "nothing is written when refused");

    let debug = CreateOptions {
        allow_software_keys: true,
        now: NOW,
    };
    let created = Vault::create(&place.vault(), PASSWORD, &pwd, Some(&bio), &debug)
        .expect("a debug build (the emulator) may use software keys");
    created.session.lock();
    assert_eq!(
        Vault::unlock_with_password(&place.vault(), PASSWORD, &pwd, false).err(),
        Some(VaultError::InsecureDevice),
        "a release build refuses to open a vault made with software keys"
    );
    assert!(Vault::unlock_with_password(&place.vault(), PASSWORD, &pwd, true).is_ok());
}

// ---------- unlocking ----------

#[test]
fn unlock_with_fingerprint() {
    let place = Place::new("bio");
    let pwd = FakePwd::new(KeyLevel::Tee);
    let bio = FakeBio::new(KeyLevel::Tee);
    let (mut session, _) = create(&place, &pwd, &bio);
    let (index, secret) = card("in.sbi", 1);
    session
        .put_item(CARD_ID, &index, &secret)
        .expect("save card");
    session.lock();

    let calls_before = pwd.calls();
    let session = Vault::unlock_with_biometric(&place.vault(), &bio, false).expect("unlock");
    assert_eq!(titles(&session), vec!["SBI Cashback"]);
    assert_eq!(pwd.calls(), calls_before, "no password hardware used");
}

#[test]
fn a_new_fingerprint_on_the_phone_disables_fingerprint_unlock_only() {
    let place = Place::new("invalidated");
    let pwd = FakePwd::new(KeyLevel::Tee);
    let bio = FakeBio::new(KeyLevel::Tee);
    let (session, _) = create(&place, &pwd, &bio);
    session.lock();

    bio.invalidated.store(true, Ordering::SeqCst);
    assert_eq!(
        Vault::unlock_with_biometric(&place.vault(), &bio, false).err(),
        Some(VaultError::UnlockFailed)
    );
    assert!(
        Vault::unlock_with_password(&place.vault(), PASSWORD, &pwd, false).is_ok(),
        "the password still works"
    );
}

#[test]
fn password_only_vault_has_no_fingerprint_unlock() {
    let place = Place::new("pwonly");
    let pwd = FakePwd::new(KeyLevel::Tee);
    let bio = FakeBio::new(KeyLevel::Tee);
    let created = Vault::create(&place.vault(), PASSWORD, &pwd, None, &options()).expect("create");
    created.session.lock();
    assert_eq!(
        Vault::unlock_with_biometric(&place.vault(), &bio, false).err(),
        Some(VaultError::UnlockFailed)
    );
    assert!(Vault::unlock_with_password(&place.vault(), PASSWORD, &pwd, false).is_ok());
}

#[test]
fn unlock_with_password_costs_one_hardware_operation_per_attempt() {
    let place = Place::new("password");
    let pwd = FakePwd::new(KeyLevel::Tee);
    let bio = FakeBio::new(KeyLevel::Tee);
    let (session, _) = create(&place, &pwd, &bio);
    session.lock();

    let before = pwd.calls();
    assert_eq!(
        Vault::unlock_with_password(&place.vault(), OTHER_PASSWORD, &pwd, false).err(),
        Some(VaultError::UnlockFailed)
    );
    assert_eq!(
        pwd.calls(),
        before + 1,
        "a wrong guess needs the hardware too"
    );

    let other_phone = FakePwd {
        key: [0x99; 32],
        level: KeyLevel::Tee,
        calls: AtomicU32::new(0),
    };
    assert_eq!(
        Vault::unlock_with_password(&place.vault(), PASSWORD, &other_phone, false).err(),
        Some(VaultError::UnlockFailed),
        "the right password on another phone's hardware opens nothing"
    );

    assert!(Vault::unlock_with_password(&place.vault(), PASSWORD, &pwd, false).is_ok());
    assert_eq!(pwd.calls(), before + 2);
}

#[test]
fn unlock_with_password_and_recovery_key() {
    let place = Place::new("recovery");
    let pwd = FakePwd::new(KeyLevel::Tee);
    let bio = FakeBio::new(KeyLevel::Tee);
    let (session, rk) = create(&place, &pwd, &bio);
    session.lock();

    let calls_before = pwd.calls();
    assert!(
        Vault::unlock_with_recovery_key(&place.vault(), PASSWORD, &rk.to_lowercase(), false)
            .is_ok(),
        "case, spaces and dashes in the Recovery Key do not matter"
    );
    assert_eq!(
        pwd.calls(),
        calls_before,
        "works without this phone's hardware"
    );

    assert_eq!(
        Vault::unlock_with_recovery_key(&place.vault(), OTHER_PASSWORD, &rk, false).err(),
        Some(VaultError::UnlockFailed),
        "the Recovery Key alone is not enough"
    );

    let other_rk = recovery_key::encode(&[0x42; 16]);
    assert_eq!(
        Vault::unlock_with_recovery_key(&place.vault(), PASSWORD, &other_rk, false).err(),
        Some(VaultError::UnlockFailed),
        "the password with another Recovery Key is not enough"
    );

    // Change the last symbol, which carries only checksum bits: always caught.
    let mut typo: Vec<char> = rk.chars().collect();
    if let Some(c) = typo.last_mut() {
        *c = if *c == 'A' { 'B' } else { 'A' };
    }
    let typo: String = typo.into_iter().collect();
    assert_eq!(
        Vault::unlock_with_recovery_key(&place.vault(), PASSWORD, &typo, false).err(),
        Some(VaultError::RkChecksum),
        "a typo is caught before any slow work"
    );
}

#[test]
fn unlocking_something_that_is_not_a_vault_fails() {
    let place = Place::new("missing");
    let pwd = FakePwd::new(KeyLevel::Tee);
    assert!(Vault::unlock_with_password(&place.vault(), PASSWORD, &pwd, false).is_err());
    std::fs::write(place.vault(), b"not a database").expect("write");
    assert!(Vault::unlock_with_password(&place.vault(), PASSWORD, &pwd, false).is_err());
}

// ---------- entries ----------

#[test]
fn entries_are_checked_saved_listed_and_revealed() {
    let place = Place::new("items");
    let pwd = FakePwd::new(KeyLevel::Tee);
    let bio = FakeBio::new(KeyLevel::Tee);
    let (mut session, _) = create(&place, &pwd, &bio);

    let (index, secret) = card("in.sbi", 1);
    session
        .put_item(CARD_ID, &index, &secret)
        .expect("save card");

    let items = session.items();
    assert_eq!(items.len(), 1);
    let first = items.first().expect("one item");
    assert_eq!(first.id, CARD_ID);
    assert_eq!(
        first.index, index,
        "the list holds the index envelope as saved"
    );

    assert_eq!(
        session
            .reveal(&CARD_ID, &NUMBER_FIELD)
            .expect("reveal")
            .as_str(),
        NUMBER
    );
    assert_eq!(
        session.reveal(&CARD_ID, &[9; 16]).err(),
        Some(VaultError::NotFound),
        "no such field"
    );
    assert_eq!(
        session.reveal(&[0x77; 16], &NUMBER_FIELD).err(),
        Some(VaultError::NotFound),
        "no such entry"
    );
}

#[test]
fn invalid_entries_are_refused_and_nothing_changes() {
    let place = Place::new("invalid");
    let pwd = FakePwd::new(KeyLevel::Tee);
    let bio = FakeBio::new(KeyLevel::Tee);
    let (mut session, _) = create(&place, &pwd, &bio);

    let (index, _) = card("in.sbi", 1);
    let wrong_number = Value::map(vec![
        (1, Value::Uint(1)),
        (
            2,
            Value::Array(vec![Value::Array(vec![
                Value::Bytes(vec![3; 16]),
                text("4111111111119999"), // does not end in last4 "1234"
            ])]),
        ),
    ]);
    assert_eq!(
        session.put_item(CARD_ID, &index, &wrong_number),
        Err(VaultError::SchemaViolation)
    );

    let (index, secret) = card("u:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 1);
    assert_eq!(
        session.put_item(CARD_ID, &index, &secret),
        Err(VaultError::SchemaViolation),
        "a card in a custom bank the vault does not have"
    );
    assert!(session.items().is_empty());
}

#[test]
fn custom_banks_and_their_cards() {
    let place = Place::new("custom");
    let pwd = FakePwd::new(KeyLevel::Tee);
    let bio = FakeBio::new(KeyLevel::Tee);
    let (mut session, _) = create(&place, &pwd, &bio);

    let org = Value::map(vec![(
        1,
        Value::Array(vec![Value::map(vec![
            (1, text("u:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")),
            (2, text("Sunrise Co-op")),
        ])]),
    )]);
    session.set_organisation(&org).expect("save organisation");
    let (index, secret) = card("u:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 1);
    session
        .put_item(CARD_ID, &index, &secret)
        .expect("save card");

    assert_eq!(
        session.set_organisation(&vault_core::organisation::empty()),
        Err(VaultError::SchemaViolation),
        "a bank that still has cards cannot be removed"
    );
    assert_eq!(
        session.set_organisation(&Value::Uint(0)),
        Err(VaultError::SchemaViolation)
    );
    session.lock();

    let session =
        Vault::unlock_with_password(&place.vault(), PASSWORD, &pwd, false).expect("unlock");
    assert_eq!(session.organisation(), org);
}

#[test]
fn edits_and_deletes_persist() {
    let place = Place::new("persist");
    let pwd = FakePwd::new(KeyLevel::Tee);
    let bio = FakeBio::new(KeyLevel::Tee);
    let (mut session, _) = create(&place, &pwd, &bio);

    let (index, secret) = card("in.sbi", 1);
    session.put_item(CARD_ID, &index, &secret).expect("save");
    let (edited, edited_secret) = card("in.hdfc", 2);
    session
        .put_item(CARD_ID, &edited, &edited_secret)
        .expect("edit");
    session.lock();

    let mut session = Vault::unlock_with_biometric(&place.vault(), &bio, false).expect("unlock");
    let items = session.items();
    assert_eq!(items.len(), 1, "an edit replaces, it does not duplicate");
    assert_eq!(items.first().map(|i| &i.index), Some(&edited));

    assert_eq!(session.delete_item(&CARD_ID), Ok(()));
    assert_eq!(session.delete_item(&CARD_ID), Err(VaultError::NotFound));
    session.lock();
    let session = Vault::unlock_with_biometric(&place.vault(), &bio, false).expect("unlock");
    assert!(session.items().is_empty());
}

// ---------- nothing readable on disk; tampering fails closed ----------

#[test]
fn nothing_readable_is_written_to_disk() {
    let place = Place::new("canary");
    let pwd = FakePwd::new(KeyLevel::Tee);
    let bio = FakeBio::new(KeyLevel::Tee);
    let (mut session, rk) = create(&place, &pwd, &bio);
    let (index, secret) = card("in.sbi", 1);
    session
        .put_item(CARD_ID, &index, &secret)
        .expect("save card");
    session.lock();

    let disk = place.all_bytes();
    assert!(!disk.is_empty());
    for canary in [
        NUMBER,
        "SBI Cashback",
        "ASHA RAO",
        "in.sbi",
        "card.number",
        "08/29",
        PASSWORD,
        rk.as_str(),
    ] {
        assert!(
            !contains(&disk, canary.as_bytes()),
            "{canary:?} must never be written in the clear"
        );
    }
}

#[test]
fn a_tampered_vault_fails_closed() {
    let place = Place::new("tamper");
    let pwd = FakePwd::new(KeyLevel::Tee);
    let bio = FakeBio::new(KeyLevel::Tee);
    let (mut session, _) = create(&place, &pwd, &bio);
    let (index, secret) = card("in.sbi", 1);
    session
        .put_item(CARD_ID, &index, &secret)
        .expect("save card");
    session.lock();

    // Flip one bit of the card's encrypted index envelope (vault-format §4.2 `item.idx_ct`).
    let path = place.vault();
    {
        let db = rusqlite::Connection::open(&path).expect("open database");
        let mut ct: Vec<u8> = db
            .query_row("SELECT idx_ct FROM item", [], |row| row.get(0))
            .expect("one item row");
        let middle = ct.len() / 2;
        if let Some(b) = ct.get_mut(middle) {
            *b ^= 0x01;
        }
        db.execute("UPDATE item SET idx_ct = ?1", [&ct])
            .expect("write tampered row");
    }

    assert_eq!(
        Vault::unlock_with_biometric(&path, &bio, false).err(),
        Some(VaultError::IntegrityFailed),
        "a changed vault must not open: nothing is shown and nothing is changed"
    );
    assert_eq!(
        Vault::unlock_with_password(&path, PASSWORD, &pwd, false).err(),
        Some(VaultError::IntegrityFailed),
        "whichever way it is unlocked"
    );
}

// ---------- changing the password ----------

#[test]
fn change_password_keeps_the_recovery_key() {
    let place = Place::new("change");
    let pwd = FakePwd::new(KeyLevel::Tee);
    let bio = FakeBio::new(KeyLevel::Tee);
    let (mut session, rk) = create(&place, &pwd, &bio);

    assert_eq!(
        session.change_password(OTHER_PASSWORD, "new passphrase that is long enough", &pwd),
        Err(VaultError::UnlockFailed),
        "the current password is required"
    );
    session
        .change_password(PASSWORD, OTHER_PASSWORD, &pwd)
        .expect("change password");
    session.lock();

    let vault = place.vault();
    assert_eq!(
        Vault::unlock_with_password(&vault, PASSWORD, &pwd, false).err(),
        Some(VaultError::UnlockFailed),
        "the old password no longer opens"
    );
    assert!(Vault::unlock_with_password(&vault, OTHER_PASSWORD, &pwd, false).is_ok());
    assert!(
        Vault::unlock_with_recovery_key(&vault, OTHER_PASSWORD, &rk, false).is_ok(),
        "the same Recovery Key works with the new password"
    );
    assert!(
        Vault::unlock_with_biometric(&vault, &bio, false).is_ok(),
        "fingerprint unlock is unaffected"
    );
}

// ---------- backup and restore onto a new phone ----------

#[test]
fn backup_restores_on_a_new_phone_with_the_same_recovery_key() {
    let old_phone = Place::new("old-phone");
    let pwd = FakePwd::new(KeyLevel::Tee);
    let bio = FakeBio::new(KeyLevel::Tee);
    let (mut session, rk) = create(&old_phone, &pwd, &bio);
    let (index, secret) = card("in.sbi", 1);
    session
        .put_item(CARD_ID, &index, &secret)
        .expect("save card");
    let backup = session.export_backup(PASSWORD).expect("export");
    session.lock();

    assert_eq!(
        Vault::restore(
            &old_phone.vault(),
            &backup,
            PASSWORD,
            &rk,
            &pwd,
            Some(&bio),
            &options()
        )
        .err(),
        Some(VaultError::VaultExists),
        "restore never overwrites a vault"
    );

    let new_phone = Place::new("new-phone");
    let new_pwd = FakePwd {
        key: [0x33; 32],
        level: KeyLevel::Tee,
        calls: AtomicU32::new(0),
    };
    let new_bio = FakeBio {
        key: [0x44; 32],
        level: KeyLevel::Tee,
        invalidated: AtomicBool::new(false),
    };
    assert_eq!(
        Vault::restore(
            &new_phone.vault(),
            &backup,
            OTHER_PASSWORD,
            &rk,
            &new_pwd,
            Some(&new_bio),
            &options()
        )
        .err(),
        Some(VaultError::AuthFailed),
        "a wrong password restores nothing"
    );
    assert!(!new_phone.vault().exists(), "and writes nothing");

    let restored = Vault::restore(
        &new_phone.vault(),
        &backup,
        PASSWORD,
        &rk,
        &new_pwd,
        Some(&new_bio),
        &options(),
    )
    .expect("restore");
    assert_eq!(titles(&restored), vec!["SBI Cashback"]);
    assert_eq!(
        restored
            .reveal(&CARD_ID, &NUMBER_FIELD)
            .expect("reveal")
            .as_str(),
        NUMBER
    );
    restored.lock();

    assert!(
        Vault::unlock_with_recovery_key(&new_phone.vault(), PASSWORD, &rk, false).is_ok(),
        "the paper Recovery Kit keeps working on the new phone"
    );
    assert!(Vault::unlock_with_biometric(&new_phone.vault(), &new_bio, false).is_ok());
    assert_eq!(
        Vault::unlock_with_biometric(&new_phone.vault(), &bio, false).err(),
        Some(VaultError::UnlockFailed),
        "the old phone's fingerprint key does not move to the new phone"
    );
}
