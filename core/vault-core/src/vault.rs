//! The encrypted vault on disk and its unlocked session (crypto spec §4, §5, §6, §7.1, §9, §10;
//! vault-format §3, §4, §5, §7, §10.4).
//!
//! The phone's hardware keys stay on the Kotlin side behind two small interfaces:
//! [`PwdHardware`] (`K_pwd`, one HMAC per password attempt, gated by the device credential) and
//! [`BioHardware`] (`K_bio`, which seals the slot key at creation and releases it after a strong
//! biometric). Only the values listed in crypto spec §5.1 cross them; VK and its subkeys never do.
//!
//! The session also keeps the security log (vault-format §5.7), the security settings
//! (§5.5: stricter freely, weaker only with the vault password) and the periodic password check
//! (crypto spec §10).
//!
//! Not yet built: the manifest (crypto spec §7.2, PROPOSED pending reviewer question Q3).

use std::collections::BTreeMap;
use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};

use rusqlite::{Connection, OpenFlags, OptionalExtension, params};
use zeroize::Zeroizing;

use crate::backup::{self, ExportRandom};
use crate::cbor::{self, Limits, Value};
use crate::error::{Result, VaultError};
use crate::integrity::{self, KeyslotRow};
use crate::item;
use crate::keyslot::{self, HwLevel, KdfParams, SlotParams, SlotType};
use crate::organisation;
use crate::password_policy;
use crate::prims::{self, Key32};
use crate::record::{self, EnvelopeKind, RecordKind, Subkeys};
use crate::recovery_key;

/// `PRAGMA application_id`: ASCII `VLT1` (vault-format §4.1).
const APPLICATION_ID: i64 = 0x564C_5431;
const USER_VERSION: i64 = 1;
const NO_ID: [u8; 16] = [0; 16];

/// Argon2id for the device slots: 64 MiB, t = 3, p = 1 (crypto spec §4.6).
const SLOT_M: u64 = 65_536;
const SLOT_T: u64 = 3;
/// Argon2id for backups: 128 MiB, t = 4, p = 1 (crypto spec §4.6).
const EXPORT_M: u64 = 131_072;
const EXPORT_T: u64 = 4;
/// The security log keeps the newest this many events (vault-format §5.7).
pub const LOG_MAX: usize = 500;
const DAY_S: u64 = 86_400;

const SCHEMA: &str = "
CREATE TABLE vault_meta (
  id          INTEGER PRIMARY KEY CHECK (id = 1),
  header      BLOB NOT NULL,
  header_mac  BLOB NOT NULL CHECK (length(header_mac) = 32)
);
CREATE TABLE keyslot (
  slot_id     BLOB PRIMARY KEY CHECK (length(slot_id) = 16),
  slot_type   INTEGER NOT NULL CHECK (slot_type IN (1, 2, 3)),
  params      BLOB NOT NULL,
  nonce       BLOB NOT NULL CHECK (length(nonce) = 24),
  wrapped_vk  BLOB NOT NULL CHECK (length(wrapped_vk) = 48)
) WITHOUT ROWID;
CREATE TABLE item (
  id          BLOB PRIMARY KEY CHECK (length(id) = 16),
  idx_nonce   BLOB NOT NULL CHECK (length(idx_nonce) = 24),
  idx_ct      BLOB NOT NULL,
  sec_nonce   BLOB NOT NULL CHECK (length(sec_nonce) = 24),
  sec_ct      BLOB NOT NULL
) WITHOUT ROWID;
CREATE TABLE item_history (
  id          BLOB PRIMARY KEY CHECK (length(id) = 16),
  item_id     BLOB NOT NULL REFERENCES item(id) ON DELETE CASCADE,
  nonce       BLOB NOT NULL CHECK (length(nonce) = 24),
  ct          BLOB NOT NULL
) WITHOUT ROWID;
CREATE TABLE meta_record (
  kind        INTEGER PRIMARY KEY CHECK (kind IN (3, 4, 5, 6, 7)),
  nonce       BLOB NOT NULL CHECK (length(nonce) = 24),
  ct          BLOB NOT NULL
);
";

/// How well the phone protects a hardware key.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KeyLevel {
    /// A software Keystore, such as the emulator's: debug builds only.
    Software,
    /// A Trusted Execution Environment.
    Tee,
    /// A separate secure chip.
    StrongBox,
}

impl KeyLevel {
    fn hw_level(self) -> HwLevel {
        match self {
            KeyLevel::Software => HwLevel::Software,
            KeyLevel::Tee => HwLevel::Tee,
            KeyLevel::StrongBox => HwLevel::StrongBox,
        }
    }
}

/// The slot key as sealed by `K_bio`: Keystore's AES-GCM IV and the 48-byte ciphertext.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SealedSk {
    pub iv: [u8; 12],
    pub blob: Vec<u8>,
}

/// `K_pwd` (crypto spec §4.3): an HMAC-SHA256 key in hardware, usable after the device credential.
pub trait PwdHardware: Send + Sync {
    fn level(&self) -> KeyLevel;
    /// `HMAC(K_pwd, hw_in)`, computed inside the hardware.
    fn hmac(&self, hw_in: &[u8; 32]) -> Result<[u8; 32]>;
    /// The slot id this key was created for: its Keystore alias is `vlt.v1.pwd.<hex(slot_id)>`
    /// (vault-format §4.4). `None` lets the core choose one.
    fn slot_id(&self) -> Option<[u8; 16]> {
        None
    }
}

/// `K_bio` (crypto spec §4.2): seals the slot key at creation; releases it only after a strong
/// biometric, as a key release, never as a yes/no answer (threat-model I7).
pub trait BioHardware: Send + Sync {
    fn level(&self) -> KeyLevel;
    fn seal_sk(&self, sk: &[u8; 32]) -> Result<SealedSk>;
    fn open_sk(&self, sealed: &SealedSk) -> Result<[u8; 32]>;
    /// The slot id this key was created for: its Keystore alias is `vlt.v1.bio.<hex(slot_id)>`.
    /// `None` lets the core choose one.
    fn slot_id(&self) -> Option<[u8; 16]> {
        None
    }
}

/// What the app needs before unlocking, read from the keyslot table without opening anything:
/// which Keystore keys to use, and the fingerprint slot's sealed key. None of it is secret
/// (vault-format §4.4).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SlotInfo {
    pub pwd_alias: String,
    pub bio: Option<BioSlotInfo>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BioSlotInfo {
    pub alias: String,
    pub sealed: SealedSk,
}

/// A fresh slot id from the OS random source, for the app to name a new Keystore key before the
/// slot is written.
pub fn new_slot_id() -> Result<[u8; 16]> {
    prims::random()
}

/// Choices made when a vault is created or restored.
#[derive(Debug, Clone)]
pub struct CreateOptions {
    /// True only in debug builds (the emulator): accept software keys (crypto spec §5.2).
    pub allow_software_keys: bool,
    /// Seconds since the epoch, for `created_at`.
    pub now: u64,
}

/// A new vault: its open session and the Recovery Key to show the user once.
#[derive(Debug)]
pub struct Created {
    pub session: Session,
    pub recovery_key: String,
}

/// One entry as listed: its id and decrypted index envelope (never its secrets).
#[derive(Debug, Clone, PartialEq)]
pub struct ItemEntry {
    pub id: [u8; 16],
    pub index: Value,
}

/// What a security-log event records (vault-format §5.7).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LogEvent {
    UnlockBiometric = 1,
    UnlockPassword = 2,
    UnlockRecovery = 3,
    FailedAttempts = 4,
    BiometricInvalidated = 5,
    PasswordChanged = 6,
    RecoveryKeyRegenerated = 7,
    VaultKeyRotated = 8,
    BackupExported = 9,
    BackupImported = 10,
    SettingWeakened = 11,
    PeriodicCheckPassed = 12,
    PeriodicCheckFailed = 13,
}

impl LogEvent {
    const ALL: [LogEvent; 13] = [
        LogEvent::UnlockBiometric,
        LogEvent::UnlockPassword,
        LogEvent::UnlockRecovery,
        LogEvent::FailedAttempts,
        LogEvent::BiometricInvalidated,
        LogEvent::PasswordChanged,
        LogEvent::RecoveryKeyRegenerated,
        LogEvent::VaultKeyRotated,
        LogEvent::BackupExported,
        LogEvent::BackupImported,
        LogEvent::SettingWeakened,
        LogEvent::PeriodicCheckPassed,
        LogEvent::PeriodicCheckFailed,
    ];

    fn from_code(code: u64) -> Option<LogEvent> {
        LogEvent::ALL.into_iter().find(|e| *e as u64 == code)
    }
}

/// One security-log event: when (seconds since the epoch), what, and for failed attempts how many.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LogEntry {
    pub at: u64,
    pub event: LogEvent,
    pub count: Option<u64>,
}

/// The security settings a user can change (vault-format §5.5).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Settings {
    pub auto_lock_s: u64,
    pub background_lock_s: u64,
    pub clipboard_ttl_s: u64,
    pub critical_window_s: u64,
    pub password_check_days: u64,
    pub password_only: bool,
    pub erase_after_failures: bool,
    pub backup_reminder_days: u64,
}

impl Settings {
    fn from_value(v: &Value) -> Settings {
        let uint = |k: u64, default: u64| v.get(k).and_then(Value::as_uint).unwrap_or(default);
        let flag = |k: u64| matches!(v.get(k), Some(Value::Bool(true)));
        Settings {
            auto_lock_s: uint(1, 120),
            background_lock_s: uint(2, 30),
            clipboard_ttl_s: uint(3, 30),
            critical_window_s: uint(4, 60),
            password_check_days: uint(5, 14),
            password_only: flag(6),
            erase_after_failures: flag(7),
            backup_reminder_days: uint(10, 30),
        }
    }

    fn in_range(&self) -> bool {
        (30..=900).contains(&self.auto_lock_s)
            && self.background_lock_s <= 300
            && [30, 60, 120].contains(&self.clipboard_ttl_s)
            && self.critical_window_s <= 300
            && [7, 14, 30].contains(&self.password_check_days)
            && (7..=90).contains(&self.backup_reminder_days)
    }

    /// True if any change lowers protection (vault-format §5.5, "Weakening").
    fn weakens(&self, old: &Settings) -> bool {
        self.auto_lock_s > old.auto_lock_s
            || self.background_lock_s > old.background_lock_s
            || self.clipboard_ttl_s > old.clipboard_ttl_s
            || self.critical_window_s > old.critical_window_s
            || self.password_check_days > old.password_check_days
            || (old.password_only && !self.password_only)
            || (old.erase_after_failures && !self.erase_after_failures)
    }

    /// The settings record with these values written over it; every other key is kept.
    fn apply_to(&self, record: &Value) -> Value {
        let mut entries: Vec<(u64, Value)> = match record {
            Value::Map(entries) => entries
                .iter()
                .filter(|(k, _)| ![1, 2, 3, 4, 5, 6, 7, 10].contains(k))
                .cloned()
                .collect(),
            _ => Vec::new(),
        };
        entries.extend([
            (1, Value::Uint(self.auto_lock_s)),
            (2, Value::Uint(self.background_lock_s)),
            (3, Value::Uint(self.clipboard_ttl_s)),
            (4, Value::Uint(self.critical_window_s)),
            (5, Value::Uint(self.password_check_days)),
            (6, Value::Bool(self.password_only)),
            (7, Value::Bool(self.erase_after_failures)),
            (10, Value::Uint(self.backup_reminder_days)),
        ]);
        Value::map(entries)
    }
}

/// Opens and creates vaults.
#[derive(Debug)]
pub struct Vault;

impl Vault {
    /// Creates a vault at `path` (crypto spec §10, "Create vault"). Refuses software keys unless
    /// the build allows them, and refuses to overwrite anything.
    pub fn create(
        path: &Path,
        password: &str,
        pwd: &dyn PwdHardware,
        bio: Option<&dyn BioHardware>,
        options: &CreateOptions,
    ) -> Result<Created> {
        password_policy::check(password)?;
        let rs: [u8; 16] = prims::random()?;
        let session = create_vault(
            path,
            password,
            pwd,
            bio,
            options,
            &rs,
            &organisation::empty(),
            None,
        )?;
        Ok(Created {
            session,
            recovery_key: recovery_key::encode(&rs),
        })
    }

    /// Unlocks with the fingerprint: `K_bio` releases the slot key (KS-BIO).
    pub fn unlock_with_biometric(
        path: &Path,
        bio: &dyn BioHardware,
        allow_software_keys: bool,
    ) -> Result<Session> {
        let stored = Stored::open(path, allow_software_keys)?;
        let slot = stored.slot(SlotType::Bio).ok_or(VaultError::UnlockFailed)?;
        let (iv, blob) = match &slot.params {
            SlotParams::Bio { iv, bio_blob, .. } => (*iv, bio_blob.to_vec()),
            _ => return Err(VaultError::IntegrityFailed),
        };
        let sk = Zeroizing::new(bio.open_sk(&SealedSk { iv, blob })?);
        let kek = keyslot::kek_bio(&sk, &stored.vault_id);
        let vk = slot.unwrap(&kek, &stored.vault_id)?;
        let session = stored.into_session(vk)?;
        session.append_log(LogEvent::UnlockBiometric, None)?;
        Ok(session)
    }

    /// Unlocks with the vault password and this phone's `K_pwd` (KS-PWD): one hardware HMAC per
    /// attempt, so guesses cannot leave the phone (threat-model T14).
    pub fn unlock_with_password(
        path: &Path,
        password: &str,
        pwd: &dyn PwdHardware,
        allow_software_keys: bool,
    ) -> Result<Session> {
        let stored = Stored::open(path, allow_software_keys)?;
        let slot = stored
            .slot(SlotType::Pwd)
            .ok_or(VaultError::IntegrityFailed)?;
        let kdf = match &slot.params {
            SlotParams::Pwd { kdf, .. } => kdf.clone(),
            _ => return Err(VaultError::IntegrityFailed),
        };
        prims::check_kdf_params(kdf.m, kdf.t, kdf.p)?;
        let (h_pwd, hw_in) = keyslot::pwd_hw_input(password, &kdf, &stored.vault_id)?;
        let hw_pwd = Zeroizing::new(pwd.hmac(&hw_in)?);
        let kek = keyslot::kek_pwd(&h_pwd, &hw_pwd, &stored.vault_id);
        let vk = slot.unwrap(&kek, &stored.vault_id)?;
        let session = stored.into_session(vk)?;
        session.append_log(LogEvent::UnlockPassword, None)?;
        Ok(session)
    }

    /// Unlocks with the vault password and the Recovery Key (KS-REC), without this phone's hardware.
    pub fn unlock_with_recovery_key(
        path: &Path,
        password: &str,
        recovery_key: &str,
        allow_software_keys: bool,
    ) -> Result<Session> {
        let rs = Zeroizing::new(recovery_key::decode(recovery_key)?);
        let stored = Stored::open(path, allow_software_keys)?;
        let slot = stored
            .slot(SlotType::Rec)
            .ok_or(VaultError::IntegrityFailed)?;
        let vk = open_rec_slot(slot, password, &rs, &stored.vault_id)?;
        let session = stored.into_session(vk)?;
        session.append_log(LogEvent::UnlockRecovery, None)?;
        Ok(session)
    }

    /// The keyslots' Keystore aliases and the fingerprint slot's sealed key (see [`SlotInfo`]).
    pub fn slot_info(path: &Path, allow_software_keys: bool) -> Result<SlotInfo> {
        let stored = Stored::open(path, allow_software_keys)?;
        let mut info = SlotInfo {
            pwd_alias: String::new(),
            bio: None,
        };
        for slot in &stored.slots {
            match &slot.params {
                SlotParams::Pwd { alias, .. } => info.pwd_alias = alias.clone(),
                SlotParams::Bio {
                    alias,
                    iv,
                    bio_blob,
                    ..
                } => {
                    info.bio = Some(BioSlotInfo {
                        alias: alias.clone(),
                        sealed: SealedSk {
                            iv: *iv,
                            blob: bio_blob.to_vec(),
                        },
                    })
                }
                SlotParams::Rec { .. } => {}
            }
        }
        if info.pwd_alias.is_empty() {
            return Err(VaultError::IntegrityFailed);
        }
        Ok(info)
    }

    /// Restores a backup onto this phone (vault-format §10.4): a new vault key, the same Recovery
    /// Secret (so the paper Recovery Kit keeps working) and the same item ids. Nothing is written
    /// unless the backup opens and every entry passes the item rules.
    pub fn restore(
        path: &Path,
        backup_file: &[u8],
        password: &str,
        recovery_key: &str,
        pwd: &dyn PwdHardware,
        bio: Option<&dyn BioHardware>,
        options: &CreateOptions,
    ) -> Result<Session> {
        if path.exists() {
            return Err(VaultError::VaultExists);
        }
        check_levels(pwd, bio, options.allow_software_keys)?;
        let rs = Zeroizing::new(recovery_key::decode(recovery_key)?);
        let payload = backup::import(backup_file, password, &rs, None)?;

        let organisation = payload.get(4).cloned().unwrap_or_else(organisation::empty);
        let settings = payload.get(3).cloned();
        let result = create_vault(
            path,
            password,
            pwd,
            bio,
            options,
            &rs,
            &organisation,
            settings.as_ref(),
        )
        .and_then(|mut session| {
            if let Some(Value::Array(items)) = payload.get(2) {
                for entry in items {
                    let id: [u8; 16] = entry
                        .get(1)
                        .and_then(Value::as_bytes)
                        .and_then(|b| b.try_into().ok())
                        .ok_or(VaultError::SchemaViolation)?;
                    let index = entry.get(2).ok_or(VaultError::SchemaViolation)?;
                    let secret = entry.get(3).ok_or(VaultError::SchemaViolation)?;
                    session.put_item(id, index, secret)?;
                }
            }
            session.append_log(LogEvent::BackupImported, None)?;
            Ok(session)
        });
        if result.is_err() {
            remove_vault_files(path);
        }
        result
    }
}

/// An unlocked vault. VK and its subkeys live here, in Rust memory only, and are zeroized when the
/// session is locked or dropped (threat-model I2).
pub struct Session {
    conn: Connection,
    vault_id: [u8; 16],
    vk: Key32,
    keys: Subkeys,
    rs: Zeroizing<[u8; 16]>,
    settings: Value,
    organisation: Value,
    items: BTreeMap<[u8; 16], Value>,
    allow_software_keys: bool,
}

impl core::fmt::Debug for Session {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.debug_struct("Session")
            .field("items", &self.items.len())
            .field("keys", &"<redacted>")
            .finish()
    }
}

impl Session {
    /// Every entry's id and index envelope, by id. Secrets are never included.
    pub fn items(&self) -> Vec<ItemEntry> {
        self.items
            .iter()
            .map(|(id, index)| ItemEntry {
                id: *id,
                index: index.clone(),
            })
            .collect()
    }

    /// Adds or replaces an entry after checking it against the item rules (vault-format §5).
    pub fn put_item(&mut self, id: [u8; 16], index: &Value, secret: &Value) -> Result<()> {
        item::validate(index, secret)?;
        if let Some(Value::Text(bank)) = index.get(16)
            && bank.starts_with("u:")
            && !organisation::custom_bank_ids(&self.organisation)?.contains(bank)
        {
            return Err(VaultError::SchemaViolation);
        }
        let (idx_nonce, idx_ct) = self.seal_item(&id, EnvelopeKind::Index, index)?;
        let (sec_nonce, sec_ct) = self.seal_item(&id, EnvelopeKind::Secret, secret)?;
        self.conn
            .execute(
                "INSERT OR REPLACE INTO item (id, idx_nonce, idx_ct, sec_nonce, sec_ct) VALUES (?1, ?2, ?3, ?4, ?5)",
                params![id.as_slice(), idx_nonce.as_slice(), idx_ct, sec_nonce.as_slice(), sec_ct],
            )
            .map_err(storage)?;
        self.items.insert(id, index.clone());
        Ok(())
    }

    /// Deletes an entry and its history.
    pub fn delete_item(&mut self, id: &[u8; 16]) -> Result<()> {
        if !self.items.contains_key(id) {
            return Err(VaultError::NotFound);
        }
        self.conn
            .execute("DELETE FROM item WHERE id = ?1", params![id.as_slice()])
            .map_err(storage)?;
        self.items.remove(id);
        Ok(())
    }

    /// One field's value, on an explicit reveal or copy (crypto spec §6.2). The secret envelope is
    /// opened, the one value taken, and the rest of the plaintext dropped at once.
    pub fn reveal(&self, id: &[u8; 16], field_id: &[u8; 16]) -> Result<Zeroizing<String>> {
        let index = self.items.get(id).ok_or(VaultError::NotFound)?;
        let (nonce, ct): (Vec<u8>, Vec<u8>) = self
            .conn
            .query_row(
                "SELECT sec_nonce, sec_ct FROM item WHERE id = ?1",
                params![id.as_slice()],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .map_err(storage)?;
        let secret = self.open_item(id, EnvelopeKind::Secret, &nonce, &ct)?;
        if let Some(Value::Array(pairs)) = secret.get(2) {
            for pair in pairs {
                if let Value::Array(kv) = pair
                    && let [Value::Bytes(fid), Value::Text(value)] = kv.as_slice()
                    && fid.as_slice() == field_id
                {
                    return Ok(Zeroizing::new(value.clone()));
                }
            }
        }
        // A normal-tier field keeps its value in the index envelope.
        if let Some(Value::Array(fields)) = index.get(14) {
            for field in fields {
                if field.get(1).and_then(Value::as_bytes) == Some(field_id.as_slice())
                    && let Some(Value::Text(value)) = field.get(7)
                {
                    return Ok(Zeroizing::new(value.clone()));
                }
            }
        }
        Err(VaultError::NotFound)
    }

    /// The Organisation record (custom banks, last card view).
    pub fn organisation(&self) -> Value {
        self.organisation.clone()
    }

    /// Replaces the Organisation record. A custom bank that entries still use cannot be removed.
    pub fn set_organisation(&mut self, org: &Value) -> Result<()> {
        organisation::validate(org)?;
        let defined = organisation::custom_bank_ids(org)?;
        for index in self.items.values() {
            if let Some(Value::Text(bank)) = index.get(16)
                && bank.starts_with("u:")
                && !defined.contains(bank)
            {
                return Err(VaultError::SchemaViolation);
            }
        }
        self.write_meta(RecordKind::Organisation, org)?;
        self.organisation = org.clone();
        Ok(())
    }

    /// Changes the vault password (crypto spec §10): new salts, KS-PWD and KS-REC re-wrapped, VK
    /// unchanged, so fingerprint unlock and the Recovery Key keep working.
    pub fn change_password(
        &mut self,
        current: &str,
        new: &str,
        pwd: &dyn PwdHardware,
    ) -> Result<()> {
        self.verify_password(current)?;
        password_policy::check(new)?;
        let rows = read_slots(&self.conn)?;
        let mut updated = Vec::new();
        for row in &rows {
            let slot = parse_slot(row)?;
            let replacement = match &slot.params {
                SlotParams::Pwd {
                    hw_level,
                    alias,
                    kdf,
                } => {
                    let kdf = KdfParams {
                        salt: prims::random()?,
                        ..kdf.clone()
                    };
                    let params = SlotParams::Pwd {
                        hw_level: *hw_level,
                        alias: alias.clone(),
                        kdf: kdf.clone(),
                    };
                    let (h_pwd, hw_in) = keyslot::pwd_hw_input(new, &kdf, &self.vault_id)?;
                    let hw_pwd = Zeroizing::new(pwd.hmac(&hw_in)?);
                    let kek = keyslot::kek_pwd(&h_pwd, &hw_pwd, &self.vault_id);
                    Some(wrap_slot(
                        &self.vault_id,
                        row.slot_id,
                        &params,
                        &kek,
                        &self.vk,
                    )?)
                }
                SlotParams::Rec { kdf } => {
                    let kdf = KdfParams {
                        salt: prims::random()?,
                        ..kdf.clone()
                    };
                    let params = SlotParams::Rec { kdf: kdf.clone() };
                    let (kek, _) = keyslot::kek_rec(new, &kdf, &self.rs, &self.vault_id)?;
                    Some(wrap_slot(
                        &self.vault_id,
                        row.slot_id,
                        &params,
                        &kek,
                        &self.vk,
                    )?)
                }
                SlotParams::Bio { .. } => None,
            };
            updated.push(replacement.unwrap_or_else(|| row.clone()));
        }

        let header = integrity::vault_header(&self.vault_id);
        let mac = integrity::header_mac(
            &self.keys.k_hdr,
            &header,
            &integrity::keyslot_table(&updated),
        );
        let tx = self.conn.transaction().map_err(storage)?;
        for row in &updated {
            tx.execute(
                "UPDATE keyslot SET params = ?2, nonce = ?3, wrapped_vk = ?4 WHERE slot_id = ?1",
                params![
                    row.slot_id.as_slice(),
                    row.params,
                    row.nonce.as_slice(),
                    row.wrapped_vk
                ],
            )
            .map_err(storage)?;
        }
        tx.execute(
            "UPDATE vault_meta SET header_mac = ?1 WHERE id = 1",
            params![mac.as_slice()],
        )
        .map_err(storage)?;
        tx.commit().map_err(storage)?;
        self.append_log(LogEvent::PasswordChanged, None)
    }

    /// An encrypted backup (crypto spec §9) of every entry, the settings and the custom banks.
    /// The password is checked first, so a backup is never made with a mistyped password.
    pub fn export_backup(&self, password: &str) -> Result<Vec<u8>> {
        self.verify_password(password)?;
        let mut entries = Vec::with_capacity(self.items.len());
        for (id, index) in &self.items {
            let (nonce, ct): (Vec<u8>, Vec<u8>) = self
                .conn
                .query_row(
                    "SELECT sec_nonce, sec_ct FROM item WHERE id = ?1",
                    params![id.as_slice()],
                    |row| Ok((row.get(0)?, row.get(1)?)),
                )
                .map_err(storage)?;
            let secret = self.open_item(id, EnvelopeKind::Secret, &nonce, &ct)?;
            entries.push(Value::map(vec![
                (1, Value::Bytes(id.to_vec())),
                (2, index.clone()),
                (3, secret),
                (4, Value::Array(Vec::new())),
            ]));
        }
        let payload = backup::build_payload(entries, &self.settings, &self.organisation)?;
        let kdf = KdfParams {
            m: EXPORT_M,
            t: EXPORT_T,
            p: 1,
            salt: prims::random()?,
        };
        let file = backup::export(&payload, password, &self.rs, &kdf, &ExportRandom::fresh()?)?;
        self.append_log(LogEvent::BackupExported, None)?;
        Ok(file)
    }

    /// The security log, oldest first (vault-format §5.7).
    pub fn security_log(&self) -> Vec<LogEntry> {
        self.read_log().unwrap_or_default()
    }

    /// Moves the wrong-password count kept while locked (plaintext preferences, vault-format §8)
    /// into the log as one event. Zero adds nothing.
    pub fn record_failed_attempts(&mut self, count: u64) -> Result<()> {
        if count == 0 {
            return Ok(());
        }
        self.append_log(LogEvent::FailedAttempts, Some(count))
    }

    /// The security settings.
    pub fn settings(&self) -> Settings {
        Settings::from_value(&self.settings)
    }

    /// Saves new settings. Values outside their ranges are refused; a weaker value needs the
    /// vault password and is logged (threat-model I6). Turning on password-only mode deletes the
    /// fingerprint slot.
    pub fn update_settings(&mut self, new: &Settings, password: Option<&str>) -> Result<()> {
        if !new.in_range() {
            return Err(VaultError::InvalidSetting);
        }
        let old = self.settings();
        let weaker = new.weakens(&old);
        if weaker {
            self.verify_password(password.ok_or(VaultError::PasswordRequired)?)?;
        }
        let record = new.apply_to(&self.settings);
        let drop_bio = new.password_only && !old.password_only;
        let tx = self.conn.transaction().map_err(storage)?;
        if drop_bio {
            let rows: Vec<KeyslotRow> = read_slots(&tx)?
                .into_iter()
                .filter(|r| r.slot_type != SlotType::Bio as u8)
                .collect();
            tx.execute(
                "DELETE FROM keyslot WHERE slot_type = ?1",
                params![SlotType::Bio as u8],
            )
            .map_err(storage)?;
            update_header_mac(&tx, &self.keys, &self.vault_id, &rows)?;
        }
        write_meta(
            &tx,
            &self.keys,
            &self.vault_id,
            RecordKind::Settings,
            &record,
        )?;
        tx.commit().map_err(storage)?;
        self.settings = record;
        if weaker {
            self.append_log(LogEvent::SettingWeakened, None)?;
        }
        Ok(())
    }

    /// True when the periodic password check is due at `now` (crypto spec §10).
    pub fn periodic_check_due(&self, now: u64) -> bool {
        let last = self.settings.get(8).and_then(Value::as_uint).unwrap_or(0);
        let days = self.settings().password_check_days;
        now >= last.saturating_add(days.saturating_mul(DAY_S))
    }

    /// The periodic password check: the typed password must open the recovery slot. Either way
    /// the result is logged; a pass restarts the interval from `now`.
    pub fn periodic_check(&mut self, password: &str, now: u64) -> Result<()> {
        if let Err(e) = self.verify_password(password) {
            self.append_log(LogEvent::PeriodicCheckFailed, None)?;
            return Err(e);
        }
        let mut entries: Vec<(u64, Value)> = match &self.settings {
            Value::Map(entries) => entries.iter().filter(|(k, _)| *k != 8).cloned().collect(),
            _ => Vec::new(),
        };
        entries.push((8, Value::Uint(now)));
        let record = Value::map(entries);
        self.write_meta(RecordKind::Settings, &record)?;
        self.settings = record;
        self.append_log(LogEvent::PeriodicCheckPassed, None)
    }

    /// Adds a fingerprint slot, or replaces it after a new fingerprint was enrolled on the phone
    /// (crypto spec §10, "Re-create biometric key"). Needs the vault password.
    pub fn enrol_biometric(&mut self, bio: &dyn BioHardware, password: &str) -> Result<()> {
        self.verify_password(password)?;
        if self.settings().password_only {
            return Err(VaultError::InvalidSetting);
        }
        if bio.level() == KeyLevel::Software && !self.allow_software_keys {
            return Err(VaultError::InsecureDevice);
        }
        let new_row = bio_slot(bio, &self.vault_id, &self.vk)?;
        let rows = read_slots(&self.conn)?;
        let replaced = rows.iter().any(|r| r.slot_type == SlotType::Bio as u8);
        let mut updated: Vec<KeyslotRow> = rows
            .into_iter()
            .filter(|r| r.slot_type != SlotType::Bio as u8)
            .collect();
        updated.push(new_row.clone());

        let tx = self.conn.transaction().map_err(storage)?;
        tx.execute(
            "DELETE FROM keyslot WHERE slot_type = ?1",
            params![SlotType::Bio as u8],
        )
        .map_err(storage)?;
        insert_slot(&tx, &new_row)?;
        update_header_mac(&tx, &self.keys, &self.vault_id, &updated)?;
        tx.commit().map_err(storage)?;
        if replaced {
            self.append_log(LogEvent::BiometricInvalidated, None)?;
        }
        Ok(())
    }

    /// The decrypted index envelope of one entry (crate-internal, for `entries`).
    pub(crate) fn index_of(&self, id: &[u8; 16]) -> Option<&Value> {
        self.items.get(id)
    }

    /// Every entry's id and index envelope, in id order (crate-internal, for `entries`).
    pub(crate) fn indexes(&self) -> impl Iterator<Item = (&[u8; 16], &Value)> {
        self.items.iter()
    }

    /// One entry's secret envelope, decrypted (crate-internal, for edits that keep secrets).
    pub(crate) fn secret_of(&self, id: &[u8; 16]) -> Result<Value> {
        if !self.items.contains_key(id) {
            return Err(VaultError::NotFound);
        }
        let (nonce, ct): (Vec<u8>, Vec<u8>) = self
            .conn
            .query_row(
                "SELECT sec_nonce, sec_ct FROM item WHERE id = ?1",
                params![id.as_slice()],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .map_err(storage)?;
        self.open_item(id, EnvelopeKind::Secret, &nonce, &ct)
    }

    fn read_log(&self) -> Result<Vec<LogEntry>> {
        match read_meta(
            &self.conn,
            &self.keys,
            &self.vault_id,
            RecordKind::SecurityLog,
        )? {
            Some(value) => parse_log(&value),
            None => Ok(Vec::new()),
        }
    }

    fn append_log(&self, event: LogEvent, count: Option<u64>) -> Result<()> {
        let mut entries = self.read_log()?;
        entries.push(LogEntry {
            at: now_s(),
            event,
            count,
        });
        let excess = entries.len().saturating_sub(LOG_MAX);
        entries.drain(..excess);
        self.write_meta(RecordKind::SecurityLog, &log_value(&entries))
    }

    /// Locks the vault: the write-ahead log is folded back and every key is zeroized.
    pub fn lock(self) {
        // Best effort: the vault stays consistent either way (vault-format §4.1).
        let _ = self
            .conn
            .query_row("PRAGMA wal_checkpoint(TRUNCATE)", [], |_| Ok(()));
        drop(self);
    }

    fn verify_password(&self, password: &str) -> Result<()> {
        let rows = read_slots(&self.conn)?;
        let row = rows
            .iter()
            .find(|r| r.slot_type == SlotType::Rec as u8)
            .ok_or(VaultError::IntegrityFailed)?;
        let slot = parse_slot(row)?;
        let vk = open_rec_slot(&slot, password, &self.rs, &self.vault_id)?;
        if prims::ct_eq(vk.as_ref(), self.vk.as_ref()) {
            Ok(())
        } else {
            Err(VaultError::UnlockFailed)
        }
    }

    fn seal_item(
        &self,
        id: &[u8; 16],
        env: EnvelopeKind,
        value: &Value,
    ) -> Result<([u8; 24], Vec<u8>)> {
        let nonce: [u8; 24] = prims::random()?;
        let ad = record::ad_record(&self.vault_id, RecordKind::Item, id, &NO_ID, env);
        Ok((
            nonce,
            record::seal_record(self.keys.for_envelope(env), &nonce, value, env, &ad),
        ))
    }

    fn open_item(
        &self,
        id: &[u8; 16],
        env: EnvelopeKind,
        nonce: &[u8],
        ct: &[u8],
    ) -> Result<Value> {
        open_item(&self.keys, &self.vault_id, id, env, nonce, ct)
    }

    fn write_meta(&self, kind: RecordKind, value: &Value) -> Result<()> {
        write_meta(&self.conn, &self.keys, &self.vault_id, kind, value)
    }
}

// ---------- creating ----------

#[allow(clippy::too_many_arguments)] // one internal entry point for create and restore
fn create_vault(
    path: &Path,
    password: &str,
    pwd: &dyn PwdHardware,
    bio: Option<&dyn BioHardware>,
    options: &CreateOptions,
    rs: &[u8; 16],
    organisation_record: &Value,
    settings: Option<&Value>,
) -> Result<Session> {
    if path.exists() {
        return Err(VaultError::VaultExists);
    }
    check_levels(pwd, bio, options.allow_software_keys)?;
    organisation::validate(organisation_record)?;

    let vault_id: [u8; 16] = prims::random()?;
    let vk: Key32 = Zeroizing::new(prims::random()?);
    let keys = Subkeys::derive(&vk, &vault_id);

    // Every key is derived, and every hardware call made, before anything touches the disk.
    let mut rows = Vec::new();
    if let Some(bio) = bio {
        rows.push(bio_slot(bio, &vault_id, &vk)?);
    }
    {
        let slot_id: [u8; 16] = match pwd.slot_id() {
            Some(id) => id,
            None => prims::random()?,
        };
        let kdf = slot_kdf()?;
        let params = SlotParams::Pwd {
            hw_level: pwd.level().hw_level(),
            alias: keyslot::keystore_alias(SlotType::Pwd, &slot_id),
            kdf: kdf.clone(),
        };
        let (h_pwd, hw_in) = keyslot::pwd_hw_input(password, &kdf, &vault_id)?;
        let hw_pwd = Zeroizing::new(pwd.hmac(&hw_in)?);
        let kek = keyslot::kek_pwd(&h_pwd, &hw_pwd, &vault_id);
        rows.push(wrap_slot(&vault_id, slot_id, &params, &kek, &vk)?);
    }
    {
        let slot_id: [u8; 16] = prims::random()?;
        let kdf = slot_kdf()?;
        let params = SlotParams::Rec { kdf: kdf.clone() };
        let (kek, _) = keyslot::kek_rec(password, &kdf, rs, &vault_id)?;
        rows.push(wrap_slot(&vault_id, slot_id, &params, &kek, &vk)?);
    }

    let header = integrity::vault_header(&vault_id);
    let mac = integrity::header_mac(&keys.k_hdr, &header, &integrity::keyslot_table(&rows));
    let settings = match settings {
        Some(Value::Map(entries)) => {
            Value::map(entries.iter().filter(|(k, _)| *k != 12).cloned().collect())
        }
        _ => default_settings(options.now),
    };
    let recovery = Value::map(vec![
        (1, Value::Bytes(rs.to_vec())),
        (2, Value::Bytes(backup::rs_id(rs).to_vec())),
        (3, Value::Uint(options.now)),
    ]);

    let written = (|| -> Result<Connection> {
        let mut conn = Connection::open_with_flags(
            path,
            OpenFlags::SQLITE_OPEN_READ_WRITE
                | OpenFlags::SQLITE_OPEN_CREATE
                | OpenFlags::SQLITE_OPEN_NO_MUTEX,
        )
        .map_err(storage)?;
        configure(&conn)?;
        conn.pragma_update(None, "application_id", APPLICATION_ID)
            .map_err(storage)?;
        conn.pragma_update(None, "user_version", USER_VERSION)
            .map_err(storage)?;
        let tx = conn.transaction().map_err(storage)?;
        tx.execute_batch(SCHEMA).map_err(storage)?;
        tx.execute(
            "INSERT INTO vault_meta (id, header, header_mac) VALUES (1, ?1, ?2)",
            params![header, mac.as_slice()],
        )
        .map_err(storage)?;
        for row in &rows {
            insert_slot(&tx, row)?;
        }
        write_meta(&tx, &keys, &vault_id, RecordKind::Settings, &settings)?;
        write_meta(&tx, &keys, &vault_id, RecordKind::Recovery, &recovery)?;
        write_meta(
            &tx,
            &keys,
            &vault_id,
            RecordKind::SecurityLog,
            &Value::Array(Vec::new()),
        )?;
        write_meta(
            &tx,
            &keys,
            &vault_id,
            RecordKind::Organisation,
            organisation_record,
        )?;
        tx.commit().map_err(storage)?;
        Ok(conn)
    })();
    let conn = match written {
        Ok(conn) => conn,
        Err(e) => {
            remove_vault_files(path);
            return Err(e);
        }
    };

    Ok(Session {
        conn,
        vault_id,
        vk,
        keys,
        rs: Zeroizing::new(*rs),
        settings,
        organisation: organisation_record.clone(),
        items: BTreeMap::new(),
        allow_software_keys: options.allow_software_keys,
    })
}

fn check_levels(
    pwd: &dyn PwdHardware,
    bio: Option<&dyn BioHardware>,
    allow_software_keys: bool,
) -> Result<()> {
    let software =
        pwd.level() == KeyLevel::Software || bio.is_some_and(|b| b.level() == KeyLevel::Software);
    if software && !allow_software_keys {
        return Err(VaultError::InsecureDevice);
    }
    Ok(())
}

/// A KS-BIO slot: a fresh slot key, sealed by `K_bio`, wraps VK (crypto spec §4.2).
fn bio_slot(bio: &dyn BioHardware, vault_id: &[u8; 16], vk: &[u8; 32]) -> Result<KeyslotRow> {
    let slot_id: [u8; 16] = match bio.slot_id() {
        Some(id) => id,
        None => prims::random()?,
    };
    let sk: Key32 = Zeroizing::new(prims::random()?);
    let sealed = bio.seal_sk(&sk)?;
    let bio_blob: [u8; 48] = sealed
        .blob
        .as_slice()
        .try_into()
        .map_err(|_| VaultError::InsecureDevice)?;
    let params = SlotParams::Bio {
        hw_level: bio.level().hw_level(),
        alias: keyslot::keystore_alias(SlotType::Bio, &slot_id),
        iv: sealed.iv,
        bio_blob,
    };
    wrap_slot(
        vault_id,
        slot_id,
        &params,
        &keyslot::kek_bio(&sk, vault_id),
        vk,
    )
}

fn insert_slot(conn: &Connection, row: &KeyslotRow) -> Result<()> {
    conn.execute(
        "INSERT INTO keyslot (slot_id, slot_type, params, nonce, wrapped_vk) VALUES (?1, ?2, ?3, ?4, ?5)",
        params![row.slot_id.as_slice(), row.slot_type, row.params, row.nonce.as_slice(), row.wrapped_vk],
    )
    .map_err(storage)?;
    Ok(())
}

/// Recomputes the header MAC over `rows`, the complete new keyslot table (crypto spec §7.1).
fn update_header_mac(
    conn: &Connection,
    keys: &Subkeys,
    vault_id: &[u8; 16],
    rows: &[KeyslotRow],
) -> Result<()> {
    let header = integrity::vault_header(vault_id);
    let mac = integrity::header_mac(&keys.k_hdr, &header, &integrity::keyslot_table(rows));
    conn.execute(
        "UPDATE vault_meta SET header_mac = ?1 WHERE id = 1",
        params![mac.as_slice()],
    )
    .map_err(storage)?;
    Ok(())
}

fn now_s() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

fn log_value(entries: &[LogEntry]) -> Value {
    Value::Array(
        entries
            .iter()
            .map(|e| {
                let mut fields = vec![(1, Value::Uint(e.at)), (2, Value::Uint(e.event as u64))];
                if let Some(count) = e.count {
                    fields.push((3, Value::Uint(count)));
                }
                Value::map(fields)
            })
            .collect(),
    )
}

/// vault-format §5.7, decoded strictly.
fn parse_log(value: &Value) -> Result<Vec<LogEntry>> {
    let Value::Array(items) = value else {
        return Err(VaultError::SchemaViolation);
    };
    if items.len() > LOG_MAX {
        return Err(VaultError::SchemaViolation);
    }
    items
        .iter()
        .map(|item| {
            let event = item
                .get(2)
                .and_then(Value::as_uint)
                .and_then(LogEvent::from_code)
                .ok_or(VaultError::SchemaViolation)?;
            let count = item.get(3).and_then(Value::as_uint);
            let keys_ok = match count {
                Some(_) => event == LogEvent::FailedAttempts && item.keys() == [1, 2, 3],
                None => item.keys() == [1, 2],
            };
            let at = item.get(1).and_then(Value::as_uint);
            match (keys_ok, at) {
                (true, Some(at)) => Ok(LogEntry { at, event, count }),
                _ => Err(VaultError::SchemaViolation),
            }
        })
        .collect()
}

fn slot_kdf() -> Result<KdfParams> {
    Ok(KdfParams {
        m: SLOT_M,
        t: SLOT_T,
        p: 1,
        salt: prims::random()?,
    })
}

/// vault-format §5.5 defaults.
fn default_settings(now: u64) -> Value {
    // Home sections: every type except Card, in the default order (Password, API key, Bank
    // account, PIN, Identity, Note, Membership, Other), all shown.
    let sections = [1u64, 6, 3, 9, 4, 5, 7, 8]
        .iter()
        .map(|t| Value::Array(vec![Value::Uint(*t), Value::Bool(true)]))
        .collect();
    Value::map(vec![
        (1, Value::Uint(120)),
        (2, Value::Uint(30)),
        (3, Value::Uint(30)),
        (4, Value::Uint(60)),
        (5, Value::Uint(14)),
        (6, Value::Bool(false)),
        (7, Value::Bool(false)),
        (8, Value::Uint(now)),
        (10, Value::Uint(30)),
        (11, Value::Array(sections)),
    ])
}

fn wrap_slot(
    vault_id: &[u8; 16],
    slot_id: [u8; 16],
    params: &SlotParams,
    kek: &[u8; 32],
    vk: &[u8; 32],
) -> Result<KeyslotRow> {
    let params_bytes = params.encode();
    let nonce: [u8; 24] = prims::random()?;
    let ad = keyslot::ad_slot(vault_id, params.slot_type(), &slot_id, &params_bytes);
    Ok(KeyslotRow {
        slot_id,
        slot_type: params.slot_type() as u8,
        params: params_bytes,
        nonce,
        wrapped_vk: keyslot::wrap_vk(kek, &nonce, vk, &ad),
    })
}

// ---------- opening ----------

/// A vault file opened but still locked: its id and keyslots, checked for shape.
struct Stored {
    conn: Connection,
    vault_id: [u8; 16],
    header: Vec<u8>,
    header_mac: Vec<u8>,
    rows: Vec<KeyslotRow>,
    slots: Vec<Slot>,
    allow_software_keys: bool,
}

struct Slot {
    row: KeyslotRow,
    params: SlotParams,
}

impl Slot {
    fn unwrap(&self, kek: &[u8; 32], vault_id: &[u8; 16]) -> Result<Key32> {
        let ad = keyslot::ad_slot(
            vault_id,
            self.params.slot_type(),
            &self.row.slot_id,
            &self.row.params,
        );
        keyslot::unwrap_vk(kek, &self.row.nonce, &self.row.wrapped_vk, &ad)
    }
}

impl Stored {
    fn open(path: &Path, allow_software_keys: bool) -> Result<Stored> {
        if !path.is_file() {
            return Err(VaultError::NotFound);
        }
        let conn = Connection::open_with_flags(
            path,
            OpenFlags::SQLITE_OPEN_READ_WRITE | OpenFlags::SQLITE_OPEN_NO_MUTEX,
        )
        .map_err(|_| VaultError::IntegrityFailed)?;
        // A file that is not this app's vault is refused before any other read (vault-format §4.1).
        let app_id: i64 = conn
            .query_row("PRAGMA application_id", [], |r| r.get(0))
            .map_err(|_| VaultError::IntegrityFailed)?;
        let version: i64 = conn
            .query_row("PRAGMA user_version", [], |r| r.get(0))
            .map_err(|_| VaultError::IntegrityFailed)?;
        if app_id != APPLICATION_ID || version != USER_VERSION {
            return Err(VaultError::IntegrityFailed);
        }
        configure(&conn)?;

        let (header, header_mac): (Vec<u8>, Vec<u8>) = conn
            .query_row(
                "SELECT header, header_mac FROM vault_meta WHERE id = 1",
                [],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .map_err(|_| VaultError::IntegrityFailed)?;
        let h = cbor::decode(&header, Limits::HEADER).map_err(|_| VaultError::IntegrityFailed)?;
        if h.keys() != [1, 2, 3, 4, 5]
            || h.get(1).and_then(Value::as_uint) != Some(1)
            || [3, 4, 5]
                .iter()
                .any(|k| h.get(*k).and_then(Value::as_uint) != Some(1))
        {
            return Err(VaultError::IntegrityFailed);
        }
        let vault_id: [u8; 16] = h
            .get(2)
            .and_then(Value::as_bytes)
            .and_then(|b| b.try_into().ok())
            .ok_or(VaultError::IntegrityFailed)?;

        let rows = read_slots(&conn)?;
        let slots = rows.iter().map(parse_slot).collect::<Result<Vec<_>>>()?;
        let software = slots.iter().any(|s| match &s.params {
            SlotParams::Bio { hw_level, .. } | SlotParams::Pwd { hw_level, .. } => {
                *hw_level == HwLevel::Software
            }
            SlotParams::Rec { .. } => false,
        });
        if software && !allow_software_keys {
            return Err(VaultError::InsecureDevice);
        }
        Ok(Stored {
            conn,
            vault_id,
            header,
            header_mac,
            rows,
            slots,
            allow_software_keys,
        })
    }

    fn slot(&self, slot_type: SlotType) -> Option<&Slot> {
        self.slots
            .iter()
            .find(|s| s.params.slot_type() == slot_type)
    }

    /// vault-format §7: verify the header MAC, then decrypt the meta records and every index
    /// envelope. Any failure keeps the vault locked: nothing is shown and nothing is changed.
    fn into_session(self, vk: Key32) -> Result<Session> {
        let keys = Subkeys::derive(&vk, &self.vault_id);
        let table = integrity::keyslot_table(&self.rows);
        if !integrity::verify_header_mac(&keys.k_hdr, &self.header, &table, &self.header_mac) {
            return Err(VaultError::IntegrityFailed);
        }
        let read = |kind: RecordKind| -> Result<Option<Value>> {
            read_meta(&self.conn, &keys, &self.vault_id, kind)
                .map_err(|_| VaultError::IntegrityFailed)
        };
        let settings = read(RecordKind::Settings)?.ok_or(VaultError::IntegrityFailed)?;
        let recovery = read(RecordKind::Recovery)?.ok_or(VaultError::IntegrityFailed)?;
        let log = read(RecordKind::SecurityLog)?.ok_or(VaultError::IntegrityFailed)?;
        parse_log(&log).map_err(|_| VaultError::IntegrityFailed)?;
        let rs: [u8; 16] = recovery
            .get(1)
            .and_then(Value::as_bytes)
            .and_then(|b| b.try_into().ok())
            .ok_or(VaultError::IntegrityFailed)?;
        let organisation = match read(RecordKind::Organisation)? {
            Some(org) => {
                organisation::validate(&org).map_err(|_| VaultError::IntegrityFailed)?;
                org
            }
            None => organisation::empty(),
        };

        let mut items = BTreeMap::new();
        {
            let mut stmt = self
                .conn
                .prepare("SELECT id, idx_nonce, idx_ct FROM item")
                .map_err(|_| VaultError::IntegrityFailed)?;
            let rows = stmt
                .query_map([], |r| {
                    Ok((
                        r.get::<_, Vec<u8>>(0)?,
                        r.get::<_, Vec<u8>>(1)?,
                        r.get::<_, Vec<u8>>(2)?,
                    ))
                })
                .map_err(|_| VaultError::IntegrityFailed)?;
            for row in rows {
                let (id, nonce, ct) = row.map_err(|_| VaultError::IntegrityFailed)?;
                let id: [u8; 16] = id
                    .as_slice()
                    .try_into()
                    .map_err(|_| VaultError::IntegrityFailed)?;
                let index = open_item(&keys, &self.vault_id, &id, EnvelopeKind::Index, &nonce, &ct)
                    .map_err(|_| VaultError::IntegrityFailed)?;
                items.insert(id, index);
            }
        }

        Ok(Session {
            conn: self.conn,
            vault_id: self.vault_id,
            vk,
            keys,
            rs: Zeroizing::new(rs),
            settings,
            organisation,
            items,
            allow_software_keys: self.allow_software_keys,
        })
    }
}

fn open_rec_slot(slot: &Slot, password: &str, rs: &[u8; 16], vault_id: &[u8; 16]) -> Result<Key32> {
    let kdf = match &slot.params {
        SlotParams::Rec { kdf } => kdf,
        _ => return Err(VaultError::IntegrityFailed),
    };
    prims::check_kdf_params(kdf.m, kdf.t, kdf.p)?;
    let (kek, _) = keyslot::kek_rec(password, kdf, rs, vault_id)?;
    slot.unwrap(&kek, vault_id)
}

fn read_slots(conn: &Connection) -> Result<Vec<KeyslotRow>> {
    let mut stmt = conn
        .prepare(
            "SELECT slot_id, slot_type, params, nonce, wrapped_vk FROM keyslot ORDER BY slot_id",
        )
        .map_err(|_| VaultError::IntegrityFailed)?;
    let rows = stmt
        .query_map([], |r| {
            Ok((
                r.get::<_, Vec<u8>>(0)?,
                r.get::<_, u8>(1)?,
                r.get::<_, Vec<u8>>(2)?,
                r.get::<_, Vec<u8>>(3)?,
                r.get::<_, Vec<u8>>(4)?,
            ))
        })
        .map_err(|_| VaultError::IntegrityFailed)?;
    let mut out = Vec::new();
    for row in rows {
        let (slot_id, slot_type, params, nonce, wrapped_vk) =
            row.map_err(|_| VaultError::IntegrityFailed)?;
        out.push(KeyslotRow {
            slot_id: slot_id
                .as_slice()
                .try_into()
                .map_err(|_| VaultError::IntegrityFailed)?,
            slot_type,
            params,
            nonce: nonce
                .as_slice()
                .try_into()
                .map_err(|_| VaultError::IntegrityFailed)?,
            wrapped_vk,
        });
    }
    Ok(out)
}

/// vault-format §4.4: a slot's stored parameters, decoded strictly.
fn parse_slot(row: &KeyslotRow) -> Result<Slot> {
    let bad = || VaultError::IntegrityFailed;
    let v = cbor::decode(&row.params, Limits::HEADER).map_err(|_| bad())?;
    let slot_type = v.get(1).and_then(Value::as_uint).ok_or_else(bad)?;
    if slot_type != u64::from(row.slot_type) {
        return Err(bad());
    }
    let hw_level = || -> Result<HwLevel> {
        match v.get(2).and_then(Value::as_uint) {
            Some(0) => Ok(HwLevel::Software),
            Some(1) => Ok(HwLevel::Tee),
            Some(2) => Ok(HwLevel::StrongBox),
            _ => Err(bad()),
        }
    };
    let alias = || -> Result<String> {
        match v.get(3) {
            Some(Value::Text(a)) if a.len() <= 128 => Ok(a.clone()),
            _ => Err(bad()),
        }
    };
    let params = match slot_type {
        1 if v.keys() == [1, 2, 3, 4, 5] => SlotParams::Bio {
            hw_level: hw_level()?,
            alias: alias()?,
            iv: v
                .get(4)
                .and_then(Value::as_bytes)
                .and_then(|b| b.try_into().ok())
                .ok_or_else(bad)?,
            bio_blob: v
                .get(5)
                .and_then(Value::as_bytes)
                .and_then(|b| b.try_into().ok())
                .ok_or_else(bad)?,
        },
        2 if v.keys() == [1, 2, 3, 6] => SlotParams::Pwd {
            hw_level: hw_level()?,
            alias: alias()?,
            kdf: backup::parse_kdf(v.get(6).ok_or_else(bad)?).map_err(|_| bad())?,
        },
        3 if v.keys() == [1, 6] => SlotParams::Rec {
            kdf: backup::parse_kdf(v.get(6).ok_or_else(bad)?).map_err(|_| bad())?,
        },
        _ => return Err(bad()),
    };
    // The stored bytes must be the one canonical encoding, since they are hashed into the AD.
    if params.encode() != row.params {
        return Err(bad());
    }
    Ok(Slot {
        row: row.clone(),
        params,
    })
}

// ---------- records ----------

fn open_item(
    keys: &Subkeys,
    vault_id: &[u8; 16],
    id: &[u8; 16],
    env: EnvelopeKind,
    nonce: &[u8],
    ct: &[u8],
) -> Result<Value> {
    let nonce: [u8; 24] = nonce.try_into().map_err(|_| VaultError::IntegrityFailed)?;
    let ad = record::ad_record(vault_id, RecordKind::Item, id, &NO_ID, env);
    record::open_record(keys.for_envelope(env), &nonce, ct, env, &ad)
        .map_err(|_| VaultError::IntegrityFailed)
}

fn write_meta(
    conn: &Connection,
    keys: &Subkeys,
    vault_id: &[u8; 16],
    kind: RecordKind,
    value: &Value,
) -> Result<()> {
    let nonce: [u8; 24] = prims::random()?;
    let ad = record::ad_record(vault_id, kind, &NO_ID, &NO_ID, EnvelopeKind::Meta);
    let ct = record::seal_record(&keys.k_meta, &nonce, value, EnvelopeKind::Meta, &ad);
    conn.execute(
        "INSERT OR REPLACE INTO meta_record (kind, nonce, ct) VALUES (?1, ?2, ?3)",
        params![kind as u8, nonce.as_slice(), ct],
    )
    .map_err(storage)?;
    Ok(())
}

fn read_meta(
    conn: &Connection,
    keys: &Subkeys,
    vault_id: &[u8; 16],
    kind: RecordKind,
) -> Result<Option<Value>> {
    let row: Option<(Vec<u8>, Vec<u8>)> = conn
        .query_row(
            "SELECT nonce, ct FROM meta_record WHERE kind = ?1",
            params![kind as u8],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .optional()
        .map_err(|_| VaultError::IntegrityFailed)?;
    let Some((nonce, ct)) = row else {
        return Ok(None);
    };
    let nonce: [u8; 24] = nonce
        .as_slice()
        .try_into()
        .map_err(|_| VaultError::IntegrityFailed)?;
    let ad = record::ad_record(vault_id, kind, &NO_ID, &NO_ID, EnvelopeKind::Meta);
    record::open_record(&keys.k_meta, &nonce, &ct, EnvelopeKind::Meta, &ad).map(Some)
}

// ---------- the file ----------

/// vault-format §4.1, applied on every open.
fn configure(conn: &Connection) -> Result<()> {
    conn.pragma_update(None, "foreign_keys", "ON")
        .map_err(storage)?;
    conn.pragma_update(None, "journal_mode", "WAL")
        .map_err(storage)?;
    conn.pragma_update(None, "secure_delete", "ON")
        .map_err(storage)?;
    conn.pragma_update(None, "temp_store", "MEMORY")
        .map_err(storage)?;
    Ok(())
}

fn remove_vault_files(path: &Path) {
    let _ = std::fs::remove_file(path);
    for suffix in ["-wal", "-shm", "-journal"] {
        let mut p = path.as_os_str().to_owned();
        p.push(suffix);
        let _ = std::fs::remove_file(std::path::PathBuf::from(p));
    }
}

fn storage(_: rusqlite::Error) -> VaultError {
    VaultError::StorageFailed
}
