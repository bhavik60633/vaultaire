//! Error names (crypto spec §11). An error may distinguish only failures that do not depend on
//! a secret: every failure that does collapses into `AuthFailed` (import) or `UnlockFailed` (unlock).

/// Every way a core operation can fail. The string form is the name used by the test vectors.
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum VaultError {
    #[error("too_large")]
    TooLarge,
    #[error("truncated")]
    Truncated,
    #[error("not_a_backup")]
    NotABackup,
    #[error("unsupported_version")]
    UnsupportedVersion,
    #[error("malformed_header")]
    MalformedHeader,
    #[error("params_out_of_range")]
    ParamsOutOfRange,
    #[error("insufficient_memory")]
    InsufficientMemory,
    #[error("wrong_recovery_key")]
    WrongRecoveryKey,
    #[error("rk_invalid")]
    RkInvalid,
    #[error("rk_checksum")]
    RkChecksum,
    #[error("password_length")]
    PasswordLength,
    #[error("auth_failed")]
    AuthFailed,
    #[error("unlock_failed")]
    UnlockFailed,
    #[error("non_canonical")]
    NonCanonical,
    #[error("malformed")]
    Malformed,
    #[error("schema_violation")]
    SchemaViolation,
    /// The operating system's random generator failed; the operation is abandoned (crypto spec §2.1).
    #[error("random_unavailable")]
    RandomUnavailable,
    /// A vault already exists where one was to be created or restored.
    #[error("vault_exists")]
    VaultExists,
    /// The device cannot protect the keys: no secure lock screen, or software-only keys outside a
    /// debug build (crypto spec §5.2).
    #[error("insecure_device")]
    InsecureDevice,
    /// The vault opened, but its data could not be verified; nothing was shown or changed
    /// (vault-format §7).
    #[error("integrity_failed")]
    IntegrityFailed,
    /// No such entry or field.
    #[error("not_found")]
    NotFound,
    /// The vault file could not be read or written.
    #[error("storage_failed")]
    StorageFailed,
    /// A new vault password fails the strength policy (crypto spec §3.2).
    #[error("weak_password")]
    WeakPassword,
    /// A settings change that weakens protection, sent without the vault password.
    #[error("password_required")]
    PasswordRequired,
    /// A setting outside its allowed values (vault-format §5.5).
    #[error("invalid_setting")]
    InvalidSetting,
}

/// Result type used throughout the core.
pub type Result<T> = core::result::Result<T, VaultError>;
