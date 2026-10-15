//! Component 8 (milestone M2): the vault's own security rules, kept inside the Rust core
//! (crypto spec §3.2, §10; vault-format §5.5, §5.7; threat-model I3, I6, T02, T14, T27).
//!
//! Proposed for the owner to freeze. Once frozen it moves, unchanged, to
//! `core/vault-core/tests/session_security.rs`, and the code is written to pass it.
//!
//! What these tests fix in place:
//! - a weak vault password is refused when it is set or changed (at least 12 characters, and not
//!   a common or patterned password); a good passphrase is accepted;
//! - the security log records every unlock and how it happened, password changes, backups,
//!   restores, weakened settings, periodic checks and failed attempts, and keeps the newest 500;
//! - the security settings start at the documented defaults, refuse values outside their ranges,
//!   and can be made stricter freely but weaker only with the vault password;
//! - the periodic password check falls due after the chosen number of days;
//! - a fingerprint can be added, or replaced after a new enrolment, only with the vault password.
//!
//! The phone's hardware keys are played by the same kind of fakes as in `vault_store.rs`.

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

use vault_core::VaultError;
use vault_core::password_policy;
use vault_core::prims;
use vault_core::vault::{
    BioHardware, CreateOptions, KeyLevel, LogEntry, LogEvent, PwdHardware, SealedSk, Session,
    Settings, Vault,
};

const PASSWORD: &str = "correct horse battery staple 42";
const WRONG_PASSWORD: &str = "a completely different passphrase 7";
const NOW: u64 = 1_760_000_000;
const DAY: u64 = 86_400;

// ---------- fake hardware ----------

struct FakePwd;

impl PwdHardware for FakePwd {
    fn level(&self) -> KeyLevel {
        KeyLevel::Tee
    }
    fn hmac(&self, hw_in: &[u8; 32]) -> vault_core::Result<[u8; 32]> {
        Ok(prims::hmac_sha256(&[0x5a; 32], hw_in))
    }
}

/// A fingerprint key. Each `seed` is a different key, as after a new enrolment.
struct FakeBio {
    key: [u8; 32],
    invalidated: AtomicBool,
}

impl FakeBio {
    fn new(seed: u8) -> FakeBio {
        FakeBio {
            key: [seed; 32],
            invalidated: AtomicBool::new(false),
        }
    }
    fn mask(&self, iv: &[u8; 12]) -> [u8; 32] {
        prims::hmac_sha256(&self.key, iv)
    }
    fn check(&self, iv: &[u8; 12], ct: &[u8]) -> [u8; 32] {
        let mut msg = b"check".to_vec();
        msg.extend_from_slice(iv);
        msg.extend_from_slice(ct);
        prims::hmac_sha256(&self.key, &msg)
    }
}

impl BioHardware for FakeBio {
    fn level(&self) -> KeyLevel {
        KeyLevel::Tee
    }
    fn seal_sk(&self, sk: &[u8; 32]) -> vault_core::Result<SealedSk> {
        let iv = [0x07; 12];
        let mut blob: Vec<u8> = sk
            .iter()
            .zip(self.mask(&iv).iter())
            .map(|(a, b)| a ^ b)
            .collect();
        let check = self.check(&iv, &blob);
        blob.extend(check.iter().take(16));
        Ok(SealedSk { iv, blob })
    }
    fn open_sk(&self, sealed: &SealedSk) -> vault_core::Result<[u8; 32]> {
        if self.invalidated.load(Ordering::SeqCst) || sealed.blob.len() != 48 {
            return Err(VaultError::UnlockFailed);
        }
        let (ct, check) = sealed.blob.split_at(32);
        let expected = self.check(&sealed.iv, ct);
        if !prims::ct_eq(check, expected.get(..16).unwrap_or_default()) {
            return Err(VaultError::UnlockFailed);
        }
        let mut sk = [0u8; 32];
        for ((out, c), m) in sk.iter_mut().zip(ct).zip(self.mask(&sealed.iv).iter()) {
            *out = c ^ m;
        }
        Ok(sk)
    }
}

// ---------- helpers ----------

struct Place {
    dir: PathBuf,
}

impl Place {
    fn new(name: &str) -> Place {
        let dir =
            std::env::temp_dir().join(format!("vaultaire-security-{name}-{}", std::process::id()));
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

fn options() -> CreateOptions {
    CreateOptions {
        allow_software_keys: false,
        now: NOW,
    }
}

fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("clock")
        .as_secs()
}

/// A vault with a fingerprint slot (`bio`), plus its Recovery Key.
fn create(place: &Place, bio: Option<&FakeBio>) -> (Session, String) {
    let bio = bio.map(|b| b as &dyn BioHardware);
    let created =
        Vault::create(&place.vault(), PASSWORD, &FakePwd, bio, &options()).expect("create");
    (created.session, created.recovery_key)
}

fn unlock(place: &Place) -> Session {
    Vault::unlock_with_password(&place.vault(), PASSWORD, &FakePwd, false).expect("unlock")
}

fn events(log: &[LogEntry]) -> Vec<LogEvent> {
    log.iter().map(|e| e.event).collect()
}

// ---------- password strength (crypto spec §3.2) ----------

#[test]
fn passwords_shorter_than_twelve_characters_are_refused() {
    for weak in ["", "Zq7#mK2$vL9", "short pass"] {
        assert_eq!(
            password_policy::check(weak),
            Err(VaultError::WeakPassword),
            "{} characters is too short",
            weak.chars().count()
        );
    }
}

#[test]
fn common_and_patterned_passwords_are_refused() {
    for weak in [
        "password1234",
        "Password1234",
        "123456789012",
        "qwertyuiop12",
        "aaaaaaaaaaaaaaaa",
        "abcdefghijklmnop",
        "iloveyou12345",
    ] {
        assert_eq!(
            password_policy::check(weak),
            Err(VaultError::WeakPassword),
            "{weak} is easy to guess"
        );
    }
}

#[test]
fn good_passphrases_are_accepted() {
    for good in [
        PASSWORD,
        WRONG_PASSWORD,
        "new passphrase that is long enough",
        "Zq7#mK2$vL9!wR4p",
        "mango tractor velvet monsoon 1987",
    ] {
        assert_eq!(
            password_policy::check(good),
            Ok(()),
            "{good} is strong enough"
        );
    }
}

#[test]
fn a_vault_cannot_be_created_with_a_weak_password() {
    let place = Place::new("weak-create");
    let result = Vault::create(&place.vault(), "password1234", &FakePwd, None, &options());
    assert!(matches!(result, Err(VaultError::WeakPassword)));
    assert!(!place.vault().exists(), "nothing is written");
}

#[test]
fn the_password_cannot_be_changed_to_a_weak_one() {
    let place = Place::new("weak-change");
    let (mut session, _) = create(&place, None);
    assert_eq!(
        session.change_password(PASSWORD, "aaaaaaaaaaaa", &FakePwd),
        Err(VaultError::WeakPassword)
    );
    session.lock();
    unlock(&place);
}

// ---------- security log (vault-format §5.7) ----------

#[test]
fn a_new_vault_has_an_empty_log() {
    let place = Place::new("log-empty");
    let (session, _) = create(&place, None);
    assert!(session.security_log().is_empty());
}

#[test]
fn every_unlock_is_logged_with_how_it_happened() {
    let place = Place::new("log-unlocks");
    let bio = FakeBio::new(0xb1);
    let (session, recovery_key) = create(&place, Some(&bio));
    session.lock();

    let before = now();
    Vault::unlock_with_biometric(&place.vault(), &bio, false)
        .expect("fingerprint")
        .lock();
    unlock(&place).lock();
    let session = Vault::unlock_with_recovery_key(&place.vault(), PASSWORD, &recovery_key, false)
        .expect("recovery key");
    let after = now();

    let log = session.security_log();
    assert_eq!(
        events(&log),
        [
            LogEvent::UnlockBiometric,
            LogEvent::UnlockPassword,
            LogEvent::UnlockRecovery
        ]
    );
    for entry in &log {
        assert!(
            (before..=after).contains(&entry.at),
            "logged at the time it happened"
        );
        assert_eq!(entry.count, None);
    }
}

#[test]
fn failed_attempts_counted_while_locked_move_into_the_log() {
    let place = Place::new("log-failures");
    let (mut session, _) = create(&place, None);
    session.record_failed_attempts(0).expect("nothing to move");
    assert!(session.security_log().is_empty(), "zero adds nothing");

    session.record_failed_attempts(3).expect("move");
    session.lock();
    let session = unlock(&place);
    let log = session.security_log();
    let failures: Vec<&LogEntry> = log
        .iter()
        .filter(|e| e.event == LogEvent::FailedAttempts)
        .collect();
    assert_eq!(failures.len(), 1, "kept after locking");
    assert_eq!(failures.first().and_then(|e| e.count), Some(3));
}

#[test]
fn password_changes_backups_and_restores_are_logged() {
    let place = Place::new("log-actions");
    let (mut session, recovery_key) = create(&place, None);
    session
        .change_password(PASSWORD, WRONG_PASSWORD, &FakePwd)
        .expect("change");
    let backup = session.export_backup(WRONG_PASSWORD).expect("export");
    assert_eq!(
        events(&session.security_log()),
        [LogEvent::PasswordChanged, LogEvent::BackupExported]
    );

    let new_phone = Place::new("log-actions-new-phone");
    let restored = Vault::restore(
        &new_phone.vault(),
        &backup,
        WRONG_PASSWORD,
        &recovery_key,
        &FakePwd,
        None,
        &options(),
    )
    .expect("restore");
    assert!(
        events(&restored.security_log()).contains(&LogEvent::BackupImported),
        "the new phone's log shows the restore"
    );
}

#[test]
fn the_log_keeps_the_newest_500_events() {
    let place = Place::new("log-cap");
    let (mut session, _) = create(&place, None);
    for count in 1..=510 {
        session.record_failed_attempts(count).expect("record");
    }
    let log = session.security_log();
    assert_eq!(log.len(), 500);
    assert_eq!(
        log.first().and_then(|e| e.count),
        Some(11),
        "the oldest went first"
    );
    assert_eq!(log.last().and_then(|e| e.count), Some(510));
}

// ---------- security settings (vault-format §5.5; threat-model I6) ----------

#[test]
fn a_new_vault_starts_with_the_documented_settings() {
    let place = Place::new("settings-default");
    let (session, _) = create(&place, None);
    let s = session.settings();
    assert_eq!(s.auto_lock_s, 120);
    assert_eq!(s.background_lock_s, 30);
    assert_eq!(s.clipboard_ttl_s, 30);
    assert_eq!(s.critical_window_s, 60);
    assert_eq!(s.password_check_days, 14);
    assert!(!s.password_only);
    assert!(!s.erase_after_failures);
    assert_eq!(s.backup_reminder_days, 30);
}

#[test]
fn values_outside_the_allowed_ranges_are_refused() {
    let place = Place::new("settings-range");
    let (mut session, _) = create(&place, None);
    let base = session.settings();
    let bad = [
        Settings {
            auto_lock_s: 29,
            ..base.clone()
        },
        Settings {
            auto_lock_s: 901,
            ..base.clone()
        },
        Settings {
            background_lock_s: 301,
            ..base.clone()
        },
        Settings {
            clipboard_ttl_s: 45,
            ..base.clone()
        },
        Settings {
            clipboard_ttl_s: 15,
            ..base.clone()
        },
        Settings {
            critical_window_s: 301,
            ..base.clone()
        },
        Settings {
            password_check_days: 10,
            ..base.clone()
        },
        Settings {
            backup_reminder_days: 6,
            ..base.clone()
        },
        Settings {
            backup_reminder_days: 91,
            ..base.clone()
        },
    ];
    for settings in &bad {
        assert_eq!(
            session.update_settings(settings, Some(PASSWORD)),
            Err(VaultError::InvalidSetting),
            "{settings:?}"
        );
    }
    assert_eq!(session.settings(), base, "nothing changed");
}

#[test]
fn stricter_settings_need_no_password_and_are_kept() {
    let place = Place::new("settings-stricter");
    let (mut session, _) = create(&place, None);
    let stricter = Settings {
        auto_lock_s: 30,
        background_lock_s: 0,
        critical_window_s: 0,
        password_check_days: 7,
        password_only: true,
        erase_after_failures: true,
        ..session.settings()
    };
    session.update_settings(&stricter, None).expect("stricter");
    assert!(session.security_log().is_empty(), "nothing weakened");
    session.lock();
    assert_eq!(unlock(&place).settings(), stricter, "kept in the vault");
}

#[test]
fn each_weaker_setting_needs_the_vault_password() {
    let place = Place::new("settings-weaker");
    let (mut session, _) = create(&place, None);
    let strict = Settings {
        password_only: true,
        erase_after_failures: true,
        ..session.settings()
    };
    session
        .update_settings(&strict, None)
        .expect("stricter first");

    let weaker = [
        Settings {
            auto_lock_s: 900,
            ..strict.clone()
        },
        Settings {
            background_lock_s: 300,
            ..strict.clone()
        },
        Settings {
            clipboard_ttl_s: 120,
            ..strict.clone()
        },
        Settings {
            critical_window_s: 300,
            ..strict.clone()
        },
        Settings {
            password_check_days: 30,
            ..strict.clone()
        },
        Settings {
            password_only: false,
            ..strict.clone()
        },
        Settings {
            erase_after_failures: false,
            ..strict.clone()
        },
    ];
    for settings in &weaker {
        assert_eq!(
            session.update_settings(settings, None),
            Err(VaultError::PasswordRequired),
            "{settings:?}"
        );
        assert_eq!(
            session.update_settings(settings, Some(WRONG_PASSWORD)),
            Err(VaultError::UnlockFailed),
            "{settings:?}"
        );
        assert_eq!(session.settings(), strict, "unchanged");
    }

    session
        .update_settings(&weaker[0], Some(PASSWORD))
        .expect("with the password");
    assert_eq!(session.settings(), weaker[0]);
    assert_eq!(events(&session.security_log()), [LogEvent::SettingWeakened]);
}

#[test]
fn the_backup_reminder_is_not_a_security_setting() {
    let place = Place::new("settings-reminder");
    let (mut session, _) = create(&place, None);
    let later = Settings {
        backup_reminder_days: 90,
        ..session.settings()
    };
    session
        .update_settings(&later, None)
        .expect("no password needed");
    assert_eq!(session.settings().backup_reminder_days, 90);
    assert!(session.security_log().is_empty());
}

// ---------- periodic password check (crypto spec §10) ----------

#[test]
fn the_periodic_check_falls_due_after_the_chosen_days() {
    let place = Place::new("check-due");
    let (mut session, _) = create(&place, None);
    assert!(!session.periodic_check_due(NOW + 14 * DAY - 1));
    assert!(session.periodic_check_due(NOW + 14 * DAY));

    let weekly = Settings {
        password_check_days: 7,
        ..session.settings()
    };
    session.update_settings(&weekly, None).expect("stricter");
    assert!(session.periodic_check_due(NOW + 7 * DAY));
}

#[test]
fn passing_the_periodic_check_restarts_the_interval() {
    let place = Place::new("check-pass");
    let (mut session, _) = create(&place, None);
    let then = NOW + 20 * DAY;
    session
        .periodic_check(PASSWORD, then)
        .expect("right password");
    assert!(!session.periodic_check_due(then + 13 * DAY));
    assert!(session.periodic_check_due(then + 14 * DAY));
    assert_eq!(
        events(&session.security_log()),
        [LogEvent::PeriodicCheckPassed]
    );
    session.lock();
    assert!(
        !unlock(&place).periodic_check_due(then + 13 * DAY),
        "kept in the vault"
    );
}

#[test]
fn failing_the_periodic_check_is_logged_and_it_stays_due() {
    let place = Place::new("check-fail");
    let (mut session, _) = create(&place, None);
    let then = NOW + 20 * DAY;
    assert_eq!(
        session.periodic_check(WRONG_PASSWORD, then),
        Err(VaultError::UnlockFailed)
    );
    assert!(session.periodic_check_due(then));
    assert_eq!(
        events(&session.security_log()),
        [LogEvent::PeriodicCheckFailed]
    );
}

// ---------- adding or replacing the fingerprint (crypto spec §10; threat-model T27) ----------

#[test]
fn a_fingerprint_can_be_added_later_with_the_password() {
    let place = Place::new("bio-add");
    let bio = FakeBio::new(0xb1);
    let (mut session, _) = create(&place, None);
    assert!(Vault::unlock_with_biometric(&place.vault(), &bio, false).is_err());

    session.enrol_biometric(&bio, PASSWORD).expect("enrol");
    session.lock();
    Vault::unlock_with_biometric(&place.vault(), &bio, false).expect("fingerprint now works");
}

#[test]
fn enrolling_a_fingerprint_needs_the_right_password() {
    let place = Place::new("bio-wrong");
    let bio = FakeBio::new(0xb1);
    let (mut session, _) = create(&place, None);
    assert_eq!(
        session.enrol_biometric(&bio, WRONG_PASSWORD),
        Err(VaultError::UnlockFailed)
    );
    session.lock();
    assert!(
        Vault::unlock_with_biometric(&place.vault(), &bio, false).is_err(),
        "nothing was added"
    );
}

#[test]
fn a_new_enrolment_replaces_the_old_fingerprint_key() {
    let place = Place::new("bio-replace");
    let old = FakeBio::new(0xb1);
    let (session, _) = create(&place, Some(&old));
    session.lock();

    // A new fingerprint was enrolled on the phone: Keystore invalidates the old key.
    old.invalidated.store(true, Ordering::SeqCst);
    assert!(Vault::unlock_with_biometric(&place.vault(), &old, false).is_err());

    let mut session = unlock(&place);
    let new = FakeBio::new(0xc2);
    session.enrol_biometric(&new, PASSWORD).expect("re-enrol");
    assert!(
        events(&session.security_log()).contains(&LogEvent::BiometricInvalidated),
        "the replacement is logged"
    );
    session.lock();

    Vault::unlock_with_biometric(&place.vault(), &new, false).expect("the new key works");
    old.invalidated.store(false, Ordering::SeqCst);
    assert!(
        Vault::unlock_with_biometric(&place.vault(), &old, false).is_err(),
        "the old key's slot is gone"
    );
}

#[test]
fn password_only_mode_never_offers_the_fingerprint() {
    let place = Place::new("bio-password-only");
    let bio = FakeBio::new(0xb1);
    let (mut session, _) = create(&place, Some(&bio));
    let strict = Settings {
        password_only: true,
        ..session.settings()
    };
    session.update_settings(&strict, None).expect("stricter");
    session.lock();
    assert!(
        matches!(
            Vault::unlock_with_biometric(&place.vault(), &bio, false),
            Err(VaultError::UnlockFailed)
        ),
        "the fingerprint slot was removed"
    );
    unlock(&place);
}
