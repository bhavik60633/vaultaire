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
}

/// Result type used throughout the core.
pub type Result<T> = core::result::Result<T, VaultError>;
