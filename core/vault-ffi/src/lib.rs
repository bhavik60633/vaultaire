//! The bridge from the Android app to `vault-core` (ADR-001, ADR-008; crypto spec §5.1).
//!
//! UniFFI generates the Kotlin side from this file. The app never calls it directly: the
//! hand-written `dev.vaultaire.core.VaultEngine` wraps it, wipes passwords after each call and
//! turns every error into one `VaultException` with a short code.
//!
//! Only the values in crypto spec §5.1 cross the bridge: the slot key after a fingerprint, the
//! password-hardware input and output, one revealed value at a time, and the Recovery Key once at
//! creation. The vault key and its subkeys never do.
//!
//! **Unaudited. Do not store real credentials** (Gate G0 owner override).

uniffi::setup_scaffolding!();

use std::fmt;
use std::path::Path;
use std::sync::{Arc, Mutex, MutexGuard};

use vault_core::VaultError;
use vault_core::password_policy;
use vault_core::vault::{
    self, BioHardware, CreateOptions, KeyLevel, LogEntry, PwdHardware, SealedSk, Session, Settings,
    Vault,
};
use zeroize::{Zeroize, Zeroizing};

// ---------- errors ----------

/// Every failure, as the short code of crypto spec §11 (`unlock_failed`, `weak_password`, …).
/// The code never contains a secret.
#[derive(Debug, uniffi::Error)]
pub enum FfiError {
    Failed { code: String },
}

impl fmt::Display for FfiError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            FfiError::Failed { code } => f.write_str(code),
        }
    }
}

impl From<VaultError> for FfiError {
    fn from(e: VaultError) -> FfiError {
        FfiError::Failed {
            code: e.to_string(),
        }
    }
}

impl From<uniffi::UnexpectedUniFFICallbackError> for FfiError {
    fn from(_: uniffi::UnexpectedUniFFICallbackError) -> FfiError {
        FfiError::Failed {
            code: "unlock_failed".into(),
        }
    }
}

fn code(code: &str) -> FfiError {
    FfiError::Failed { code: code.into() }
}

type FfiResult<T> = Result<T, FfiError>;

// ---------- hardware keys, implemented in Kotlin ----------

#[derive(Debug, Clone, Copy, uniffi::Enum)]
pub enum FfiKeyLevel {
    Software,
    Tee,
    StrongBox,
}

impl From<FfiKeyLevel> for KeyLevel {
    fn from(level: FfiKeyLevel) -> KeyLevel {
        match level {
            FfiKeyLevel::Software => KeyLevel::Software,
            FfiKeyLevel::Tee => KeyLevel::Tee,
            FfiKeyLevel::StrongBox => KeyLevel::StrongBox,
        }
    }
}

/// The slot key as sealed by `K_bio`.
#[derive(Debug, uniffi::Record)]
pub struct FfiSealed {
    pub iv: Vec<u8>,
    pub blob: Vec<u8>,
}

/// `K_pwd` (crypto spec §4.3), in Android Keystore.
#[uniffi::export(callback_interface)]
pub trait PwdKeyCallback: Send + Sync {
    fn level(&self) -> FfiKeyLevel;
    fn hmac(&self, input: Vec<u8>) -> FfiResult<Vec<u8>>;
    /// The id from [`new_slot_id`] this key's alias was made from, if the app chose one.
    fn slot_id(&self) -> Option<Vec<u8>>;
}

/// `K_bio` (crypto spec §4.2), in Android Keystore behind the fingerprint prompt.
#[uniffi::export(callback_interface)]
pub trait BioKeyCallback: Send + Sync {
    fn level(&self) -> FfiKeyLevel;
    fn seal(&self, slot_key: Vec<u8>) -> FfiResult<FfiSealed>;
    fn open(&self, sealed: FfiSealed) -> FfiResult<Vec<u8>>;
    fn slot_id(&self) -> Option<Vec<u8>>;
}

fn slot_id(id: Option<Vec<u8>>) -> Option<[u8; 16]> {
    id.and_then(|id| id.as_slice().try_into().ok())
}

struct Pwd<'a>(&'a dyn PwdKeyCallback);

impl PwdHardware for Pwd<'_> {
    fn level(&self) -> KeyLevel {
        self.0.level().into()
    }
    fn hmac(&self, hw_in: &[u8; 32]) -> vault_core::Result<[u8; 32]> {
        let out = Zeroizing::new(
            self.0
                .hmac(hw_in.to_vec())
                .map_err(|_| VaultError::UnlockFailed)?,
        );
        out.as_slice()
            .try_into()
            .map_err(|_| VaultError::UnlockFailed)
    }
    fn slot_id(&self) -> Option<[u8; 16]> {
        slot_id(self.0.slot_id())
    }
}

struct Bio<'a>(&'a dyn BioKeyCallback);

impl BioHardware for Bio<'_> {
    fn level(&self) -> KeyLevel {
        self.0.level().into()
    }
    fn seal_sk(&self, sk: &[u8; 32]) -> vault_core::Result<SealedSk> {
        let sealed = self
            .0
            .seal(sk.to_vec())
            .map_err(|_| VaultError::UnlockFailed)?;
        Ok(SealedSk {
            iv: sealed
                .iv
                .as_slice()
                .try_into()
                .map_err(|_| VaultError::UnlockFailed)?,
            blob: sealed.blob,
        })
    }
    fn open_sk(&self, sealed: &SealedSk) -> vault_core::Result<[u8; 32]> {
        let sk = Zeroizing::new(
            self.0
                .open(FfiSealed {
                    iv: sealed.iv.to_vec(),
                    blob: sealed.blob.clone(),
                })
                .map_err(|_| VaultError::UnlockFailed)?,
        );
        sk.as_slice()
            .try_into()
            .map_err(|_| VaultError::UnlockFailed)
    }
    fn slot_id(&self) -> Option<[u8; 16]> {
        slot_id(self.0.slot_id())
    }
}

/// A password from Kotlin (UTF-8 bytes), held in memory that is wiped when dropped.
fn text(mut bytes: Vec<u8>) -> FfiResult<Zeroizing<String>> {
    let result = std::str::from_utf8(&bytes)
        .map(|s| Zeroizing::new(s.to_owned()))
        .map_err(|_| code("password_length"));
    bytes.zeroize();
    result
}

// ---------- opening and creating ----------

/// Creates a vault (crypto spec §10). The Recovery Key is taken once with
/// [`FfiSession::take_recovery_key`].
#[uniffi::export]
pub fn create_vault(
    path: String,
    password: Vec<u8>,
    pwd: Box<dyn PwdKeyCallback>,
    bio: Option<Box<dyn BioKeyCallback>>,
    allow_software_keys: bool,
    now: u64,
) -> FfiResult<Arc<FfiSession>> {
    let password = text(password)?;
    let bio = bio.as_deref().map(Bio);
    let created = Vault::create(
        Path::new(&path),
        &password,
        &Pwd(pwd.as_ref()),
        bio.as_ref().map(|b| b as &dyn BioHardware),
        &CreateOptions {
            allow_software_keys,
            now,
        },
    )?;
    Ok(FfiSession::new(
        created.session,
        Some(Zeroizing::new(created.recovery_key)),
    ))
}

#[uniffi::export]
pub fn unlock_with_password(
    path: String,
    password: Vec<u8>,
    pwd: Box<dyn PwdKeyCallback>,
    allow_software_keys: bool,
) -> FfiResult<Arc<FfiSession>> {
    let password = text(password)?;
    let session = Vault::unlock_with_password(
        Path::new(&path),
        &password,
        &Pwd(pwd.as_ref()),
        allow_software_keys,
    )?;
    Ok(FfiSession::new(session, None))
}

#[uniffi::export]
pub fn unlock_with_biometric(
    path: String,
    bio: Box<dyn BioKeyCallback>,
    allow_software_keys: bool,
) -> FfiResult<Arc<FfiSession>> {
    let session =
        Vault::unlock_with_biometric(Path::new(&path), &Bio(bio.as_ref()), allow_software_keys)?;
    Ok(FfiSession::new(session, None))
}

#[uniffi::export]
pub fn unlock_with_recovery_key(
    path: String,
    password: Vec<u8>,
    recovery_key: Vec<u8>,
    allow_software_keys: bool,
) -> FfiResult<Arc<FfiSession>> {
    let password = text(password)?;
    let recovery_key = text(recovery_key).map_err(|_| code("rk_invalid"))?;
    let session = Vault::unlock_with_recovery_key(
        Path::new(&path),
        &password,
        &recovery_key,
        allow_software_keys,
    )?;
    Ok(FfiSession::new(session, None))
}

/// A fresh slot id, to name a new Keystore key before its slot is written (vault-format §4.4).
#[uniffi::export]
pub fn new_slot_id() -> FfiResult<Vec<u8>> {
    Ok(vault::new_slot_id()?.to_vec())
}

/// The Keystore aliases of the vault's slots, and the fingerprint slot's sealed key, read without
/// unlocking. Nothing here is secret.
#[derive(Debug, uniffi::Record)]
pub struct FfiSlotInfo {
    pub pwd_alias: String,
    pub bio_alias: Option<String>,
    pub bio_sealed: Option<FfiSealed>,
}

#[uniffi::export]
pub fn slot_info(path: String, allow_software_keys: bool) -> FfiResult<FfiSlotInfo> {
    let info = Vault::slot_info(Path::new(&path), allow_software_keys)?;
    let (bio_alias, bio_sealed) = match info.bio {
        Some(bio) => (
            Some(bio.alias),
            Some(FfiSealed {
                iv: bio.sealed.iv.to_vec(),
                blob: bio.sealed.blob,
            }),
        ),
        None => (None, None),
    };
    Ok(FfiSlotInfo {
        pwd_alias: info.pwd_alias,
        bio_alias,
        bio_sealed,
    })
}

/// The strength policy alone, so a screen can explain a weak password before any work is done.
#[uniffi::export]
pub fn check_password_strength(password: Vec<u8>) -> FfiResult<()> {
    let password = text(password)?;
    password_policy::check(&password)?;
    Ok(())
}

// ---------- the session ----------

/// One security-log event (vault-format §5.7). `event` is the registry number (1–13).
#[derive(Debug, uniffi::Record)]
pub struct FfiLogEntry {
    pub at: u64,
    pub event: u32,
    pub count: Option<u64>,
}

impl From<LogEntry> for FfiLogEntry {
    fn from(e: LogEntry) -> FfiLogEntry {
        FfiLogEntry {
            at: e.at,
            event: e.event as u32,
            count: e.count,
        }
    }
}

/// The security settings (vault-format §5.5).
#[derive(Debug, uniffi::Record)]
pub struct FfiSettings {
    pub auto_lock_s: u64,
    pub background_lock_s: u64,
    pub clipboard_ttl_s: u64,
    pub critical_window_s: u64,
    pub password_check_days: u64,
    pub password_only: bool,
    pub erase_after_failures: bool,
    pub backup_reminder_days: u64,
}

impl From<Settings> for FfiSettings {
    fn from(s: Settings) -> FfiSettings {
        FfiSettings {
            auto_lock_s: s.auto_lock_s,
            background_lock_s: s.background_lock_s,
            clipboard_ttl_s: s.clipboard_ttl_s,
            critical_window_s: s.critical_window_s,
            password_check_days: s.password_check_days,
            password_only: s.password_only,
            erase_after_failures: s.erase_after_failures,
            backup_reminder_days: s.backup_reminder_days,
        }
    }
}

impl From<FfiSettings> for Settings {
    fn from(s: FfiSettings) -> Settings {
        Settings {
            auto_lock_s: s.auto_lock_s,
            background_lock_s: s.background_lock_s,
            clipboard_ttl_s: s.clipboard_ttl_s,
            critical_window_s: s.critical_window_s,
            password_check_days: s.password_check_days,
            password_only: s.password_only,
            erase_after_failures: s.erase_after_failures,
            backup_reminder_days: s.backup_reminder_days,
        }
    }
}

/// An unlocked vault. After [`FfiSession::lock`] every call fails with `locked`.
#[derive(uniffi::Object)]
pub struct FfiSession {
    session: Mutex<Option<Session>>,
    recovery_key: Mutex<Option<Zeroizing<String>>>,
}

impl fmt::Debug for FfiSession {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("FfiSession(<redacted>)")
    }
}

impl FfiSession {
    fn new(session: Session, recovery_key: Option<Zeroizing<String>>) -> Arc<FfiSession> {
        Arc::new(FfiSession {
            session: Mutex::new(Some(session)),
            recovery_key: Mutex::new(recovery_key),
        })
    }

    fn guard(&self) -> MutexGuard<'_, Option<Session>> {
        self.session
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    fn with<T>(&self, f: impl FnOnce(&mut Session) -> vault_core::Result<T>) -> FfiResult<T> {
        let mut guard = self.guard();
        let session = guard.as_mut().ok_or_else(|| code("locked"))?;
        Ok(f(session)?)
    }
}

#[uniffi::export]
impl FfiSession {
    /// The Recovery Key of a newly created vault, once; afterwards nothing.
    pub fn take_recovery_key(&self) -> Option<Vec<u8>> {
        self.recovery_key
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .take()
            .map(|key| key.as_bytes().to_vec())
    }

    pub fn is_locked(&self) -> bool {
        self.guard().is_none()
    }

    /// Locks the vault: the session and every key in it are dropped and zeroized.
    pub fn lock(&self) {
        if let Some(session) = self.guard().take() {
            session.lock();
        }
        self.recovery_key
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .take();
    }

    pub fn security_log(&self) -> FfiResult<Vec<FfiLogEntry>> {
        self.with(|s| {
            Ok(s.security_log()
                .into_iter()
                .map(FfiLogEntry::from)
                .collect())
        })
    }

    pub fn record_failed_attempts(&self, count: u64) -> FfiResult<()> {
        self.with(|s| s.record_failed_attempts(count))
    }

    pub fn settings(&self) -> FfiResult<FfiSettings> {
        self.with(|s| Ok(s.settings().into()))
    }

    pub fn update_settings(
        &self,
        settings: FfiSettings,
        password: Option<Vec<u8>>,
    ) -> FfiResult<()> {
        let password = password.map(text).transpose()?;
        let settings: Settings = settings.into();
        self.with(|s| s.update_settings(&settings, password.as_deref().map(String::as_str)))
    }

    pub fn periodic_check_due(&self, now: u64) -> FfiResult<bool> {
        self.with(|s| Ok(s.periodic_check_due(now)))
    }

    pub fn periodic_check(&self, password: Vec<u8>, now: u64) -> FfiResult<()> {
        let password = text(password)?;
        self.with(|s| s.periodic_check(&password, now))
    }

    pub fn change_password(
        &self,
        current: Vec<u8>,
        new: Vec<u8>,
        pwd: Box<dyn PwdKeyCallback>,
    ) -> FfiResult<()> {
        let current = text(current)?;
        let new = text(new)?;
        self.with(|s| s.change_password(&current, &new, &Pwd(pwd.as_ref())))
    }

    pub fn enrol_biometric(
        &self,
        bio: Box<dyn BioKeyCallback>,
        password: Vec<u8>,
    ) -> FfiResult<()> {
        let password = text(password)?;
        self.with(|s| s.enrol_biometric(&Bio(bio.as_ref()), &password))
    }
}
