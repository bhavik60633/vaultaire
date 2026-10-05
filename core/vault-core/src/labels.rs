//! Label registry (crypto spec §3.3). `vlt` is a fixed protocol identifier, not the product name.

pub const BIO_BLOB: &[u8] = b"vlt/v1/bio-blob";
pub const KEK_BIO: &[u8] = b"vlt/v1/kek/bio";
pub const HW_INPUT: &[u8] = b"vlt/v1/hw-input";
pub const KEK_PWD: &[u8] = b"vlt/v1/kek/pwd";
pub const KEK_REC: &[u8] = b"vlt/v1/kek/rec";
pub const SLOT: &[u8] = b"vlt/v1/slot";
pub const SUB_INDEX: &[u8] = b"vlt/v1/subkey/index";
pub const SUB_SECRET: &[u8] = b"vlt/v1/subkey/secret";
pub const SUB_META: &[u8] = b"vlt/v1/subkey/meta";
pub const SUB_HEADER: &[u8] = b"vlt/v1/subkey/header";
pub const RECORD: &[u8] = b"vlt/v1/record";
pub const HEADER_MAC: &[u8] = b"vlt/v1/header-mac";
pub const RS_ID: &[u8] = b"vlt/v1/rs-id";
pub const RK_CHECKSUM: &[u8] = b"vlt/v1/rk-checksum";
pub const KEK_EXPORT: &[u8] = b"vlt/v1/kek/export";
pub const EXPORT_WRAP: &[u8] = b"vlt/v1/export/wrap";
pub const EXPORT_PAYLOAD: &[u8] = b"vlt/v1/export/payload";

/// Every label, for the registry test.
pub const ALL: &[&[u8]] = &[
    BIO_BLOB,
    KEK_BIO,
    HW_INPUT,
    KEK_PWD,
    KEK_REC,
    SLOT,
    SUB_INDEX,
    SUB_SECRET,
    SUB_META,
    SUB_HEADER,
    RECORD,
    HEADER_MAC,
    RS_ID,
    RK_CHECKSUM,
    KEK_EXPORT,
    EXPORT_WRAP,
    EXPORT_PAYLOAD,
];

/// Protocol constants shared by several sections.
pub const FORMAT_VERSION: u16 = 1;
pub const SCHEMA_VERSION: u16 = 1;
