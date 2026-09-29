# Vaultaire vault format

| | |
|---|---|
| Status | **Draft 0.1, not yet reviewed.** Input to Gate G0 (spec freeze). |
| Scope | `format_version = 1`: the on-device database, the encoding of everything inside it, plaintext preferences, and the `.vaultbackup` file |
| Related | [`crypto-spec.md`](crypto-spec.md) (how each piece is encrypted and authenticated), [`threat-model.md`](threat-model.md) |
| Last updated | 2026-09-29 |

This document is normative, using the RFC 2119 keywords as in the crypto spec. The crypto spec defines keys, associated data (AD) and padding. This document defines **bytes and schemas**. Where the two seem to disagree, stop and report it; do not guess.

---

## 1. Design rules

1. **Plaintext on disk is limited to what the crypto needs:** random ids, nonces, ciphertext, keyslot parameters and the header. Titles, types, timestamps, counts per type and tags are all encrypted.
2. **Deterministic encoding.** Everything that is hashed, MACed or bound into AD has exactly one valid encoding (§2).
3. **Strict decoding.** A decoder rejects anything it does not fully understand. Format version 1 has no "ignore unknown fields" behaviour, because migrations are eager (§9), so an older reader never meets a newer file.
4. **Big-endian integers** wherever raw integers appear outside CBOR.

## 2. CBOR profile

All structured data uses CBOR (RFC 8949), restricted as follows. The same rules apply to encoding and decoding.

- **Deterministic encoding** (RFC 8949 §4.2.1): shortest-form integers and lengths; map keys sorted by the bytewise order of their encodings; definite lengths only.
- **Allowed major types:** unsigned integers, byte strings, UTF-8 text strings, arrays, maps, and the simple values `true` and `false`. **Not allowed:** negative integers, floats, tags, `null`, `undefined`, indefinite lengths.
- **Map keys** are small unsigned integers, as listed in each schema. Duplicate keys, unknown keys, a missing required key, or a value of the wrong type are all errors.
- **Text** must be valid UTF-8. Text is stored as typed; only passwords are NFC-normalised, and only as KDF input (crypto spec §3.2).
- **Timestamps** are unsigned integers: whole seconds since 1970-01-01T00:00:00Z.
- **Ids** are 16-byte byte strings.

Decoding limits (each context may set tighter ones):

| Limit | Value |
|---|---|
| Nesting depth | 4 for headers and keyslot parameters; 6 for records and payloads |
| Entries in one map or array | 16 in headers; 1 000 elsewhere, except the item list in a backup (10 000) and the manifest (20 000) |
| Text string | 64 KiB |
| Byte string | 64 KiB |

## 3. Files on the device

| Path | Contents | Protection |
|---|---|---|
| `noBackupFilesDir/vault/vault.db` (plus `-wal` and `-shm`) | The vault (§4) | Credential-encrypted storage; excluded from all OS backups; records encrypted by `vault-core` |
| App preferences file | Non-sensitive state (§8) | Plaintext. Must never hold anything security-relevant. |
| Android Keystore | `K_bio`, `K_pwd` | Hardware; non-exportable |

Nothing else is written. There are **no temporary files**: exports stream straight to the location the user picks through the Storage Access Framework, and SQLite runs with `temp_store = MEMORY`.

## 4. Database (`vault.db`)

### 4.1 Connection settings

Applied on every open, before any other statement:

```sql
PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;
PRAGMA secure_delete = ON;
PRAGMA temp_store = MEMORY;
```

- On creation, `PRAGMA application_id = 0x564C5431` (ASCII `VLT1`) identifies the file type, and `PRAGMA user_version = 1` records the schema version.
- Opening a file whose `application_id` or `user_version` is unexpected fails before any other read.
- On lock, the core runs `PRAGMA wal_checkpoint(TRUNCATE)`, so the WAL does not keep old ciphertext longer than needed.

### 4.2 Schema version 1

```sql
CREATE TABLE vault_meta (
  id          INTEGER PRIMARY KEY CHECK (id = 1),
  header      BLOB NOT NULL,                  -- §4.3, deterministic CBOR
  header_mac  BLOB NOT NULL CHECK (length(header_mac) = 32)
);

CREATE TABLE keyslot (
  slot_id     BLOB PRIMARY KEY CHECK (length(slot_id) = 16),
  slot_type   INTEGER NOT NULL CHECK (slot_type IN (1, 2, 3)),   -- 1 BIO, 2 PWD, 3 REC
  params      BLOB NOT NULL,                  -- §4.4, deterministic CBOR
  nonce       BLOB NOT NULL CHECK (length(nonce) = 24),
  wrapped_vk  BLOB NOT NULL CHECK (length(wrapped_vk) = 48)      -- 32-byte VK + 16-byte tag
) WITHOUT ROWID;

CREATE TABLE item (
  id          BLOB PRIMARY KEY CHECK (length(id) = 16),
  idx_nonce   BLOB NOT NULL CHECK (length(idx_nonce) = 24),
  idx_ct      BLOB NOT NULL,                  -- index envelope, §5.2
  sec_nonce   BLOB NOT NULL CHECK (length(sec_nonce) = 24),
  sec_ct      BLOB NOT NULL                   -- secret envelope, §5.3
) WITHOUT ROWID;

CREATE TABLE item_history (
  id          BLOB PRIMARY KEY CHECK (length(id) = 16),
  item_id     BLOB NOT NULL REFERENCES item(id) ON DELETE CASCADE,
  nonce       BLOB NOT NULL CHECK (length(nonce) = 24),
  ct          BLOB NOT NULL                   -- history envelope, §5.4
) WITHOUT ROWID;

CREATE TABLE meta_record (
  kind        INTEGER PRIMARY KEY CHECK (kind IN (3, 4, 5, 6)),  -- = record_kind, §4.5
  nonce       BLOB NOT NULL CHECK (length(nonce) = 24),
  ct          BLOB NOT NULL
);
```

- Every table keyed by an id is `WITHOUT ROWID`, so rows are stored in the order of their **random** ids. With ordinary rowid tables, the file would leak the order in which items were added.
- There are no indexes other than the primary keys. **Never create a full-text or other search index**; search runs only over the decrypted index held in memory (threat-model I1).
- `item_history.item_id` is plaintext: it links a history entry to its item. This reveals how many earlier versions each item has, and nothing about their content. The same fact is also bound into the entry's AD (`parent_id`).

### 4.3 Vault header

`vault_meta.header` is the deterministic CBOR map:

| Key | Name | Type | Value in v1 |
|---|---|---|---|
| 1 | `format_version` | uint | `1` |
| 2 | `vault_id` | bytes(16) | random |
| 3 | `aead_id` | uint | `1` (XChaCha20-Poly1305) |
| 4 | `kdf_id` | uint | `1` (Argon2id v0x13) |
| 5 | `mac_id` | uint | `1` (HMAC-SHA256) |

`header_mac` covers these exact bytes together with the keyslot table (crypto spec §7.1). The `keyslot_table_bytes` input is the deterministic CBOR **array** of every keyslot row, sorted by `slot_id` in ascending bytewise order, each row encoded as:

```
[ slot_id (bytes 16), slot_type (uint), params (bytes: the exact stored params), nonce (bytes 24), wrapped_vk (bytes 48) ]
```

### 4.4 Keyslot parameters

`keyslot.params` is a deterministic CBOR map. Its exact stored bytes are what the slot AD hashes (crypto spec §4.5).

| Key | Name | Type | BIO | PWD | REC |
|---|---|---|---|---|---|
| 1 | `slot_type` | uint (must equal the column) | 1 | 2 | 3 |
| 2 | `hw_level` | uint: 1 = TEE, 2 = StrongBox | required | required | — |
| 3 | `keystore_alias` | text, ≤ 128 bytes | required | required | — |
| 4 | `iv` | bytes(12): Keystore AES-GCM IV | required | — | — |
| 5 | `bio_blob` | bytes(48): SK encrypted by `K_bio` (32 + 16-byte tag) | required | — | — |
| 6 | `kdf` | map (below) | — | required | required |

`kdf` map:

| Key | Name | Type | Accepted range |
|---|---|---|---|
| 1 | `kdf_id` | uint | `1` only |
| 2 | `m` | uint (KiB) | 65 536 – 524 288 |
| 3 | `t` | uint | 3 – 16 |
| 4 | `p` | uint | 1 – 4 |
| 5 | `salt` | bytes(16) | — |

- A key marked "—" must be absent. A present key that does not belong to the slot type is an error.
- Keystore aliases have the form `vaultaire.v1.bio.<hex(slot_id)>` or `vaultaire.v1.pwd.<hex(slot_id)>`, where `hex` is lowercase and 32 characters long. They carry no user data.
- A vault has exactly one slot of each type, except that KS-BIO is absent in password-only mode, or after biometric invalidation until the user re-enrols.

### 4.5 Record kinds and envelope kinds

These codes are shared with the record AD (crypto spec §6.4):

| `record_kind` | Record | Stored in | `envelope_kind` | Key |
|---|---|---|---|---|
| `0x01` | Item | `item.idx_ct` | `0x01` index | K_idx |
| `0x01` | Item | `item.sec_ct` | `0x02` secret | K_sec |
| `0x02` | History entry | `item_history.ct` | `0x02` secret | K_sec |
| `0x03` | Settings | `meta_record` (kind 3) | `0x03` meta | K_meta |
| `0x04` | Recovery | `meta_record` (kind 4) | `0x03` meta | K_meta |
| `0x05` | Security log | `meta_record` (kind 5) | `0x03` meta | K_meta |
| `0x06` | Manifest | `meta_record` (kind 6) | `0x03` meta | K_meta |

All records in this version use `schema_version = 1`.

## 5. Record schemas (plaintext, before padding)

### 5.1 Registries

**Item types**

| Code | Type | Code | Type |
|---|---|---|---|
| 1 | Login | 5 | Secure note |
| 2 | Card | 6 | Developer key |
| 3 | Bank account | 7 | Membership |
| 4 | Identity document | 8 | Other |

**Field kinds**

| Code | Kind | Code | Kind |
|---|---|---|---|
| 1 | text | 8 | phone |
| 2 | concealed | 9 | iban |
| 3 | pan (card number) | 10 | account_number |
| 4 | expiry | 11 | routing_code |
| 5 | date | 12 | multiline |
| 6 | url | 13 | totp_secret (reserved for V1.1; rejected in v1) |
| 7 | email | 14 | **cvv** |

**Sensitivity:** 1 = normal (shown; value lives in the index envelope), 2 = concealed (masked; tap to reveal), 3 = critical (masked; recent biometric to reveal or copy). A field's sensitivity may be raised by the user but never lowered below its template default.

**Card finishes:** 1 Obsidian, 2 Titanium, 3 Platinum, 4 Ivory, 5 Champagne.

### 5.2 Index envelope (item)

Decrypted for every item at unlock, into memory only.

| Key | Name | Type | Notes |
|---|---|---|---|
| 1 | `type` | uint | Item type |
| 2 | `template_version` | uint | `1` |
| 3 | `revision` | uint | Must equal the secret envelope's `revision` and the manifest entry |
| 4 | `title` | text | Required, ≤ 256 bytes |
| 5 | `subtitle` | text | Derived from normal fields, for example "Visa · •••• 4821" |
| 6 | `tags` | array of text | ≤ 32 tags, each ≤ 64 bytes |
| 7 | `favorite` | bool | |
| 8 | `created_at` | uint | |
| 9 | `updated_at` | uint | |
| 10 | `last_used_at` | uint | Optional |
| 11 | `url_hosts` | array of text | Hosts only, for display and future autofill matching |
| 12 | `card_finish` | uint | Cards only |
| 13 | `has_cvv` | bool | Cards only. Drives the "Stored at your request" label and the Settings count without opening the secret envelope. |
| 14 | `fields` | array of field descriptors | Order is display order; ≤ 100 |
| 15 | `linked_items` | array of bytes(16) | For example, a bank account linked to its login |

Field descriptor (a map):

| Key | Name | Type | Notes |
|---|---|---|---|
| 1 | `field_id` | bytes(16) | Random; stable across edits |
| 2 | `key` | text | Template key such as `card.number`, or `custom` |
| 3 | `label` | text | ≤ 128 bytes |
| 4 | `kind` | uint | Field kind |
| 5 | `sensitivity` | uint | 1, 2 or 3 |
| 6 | `copyable` | bool | |
| 7 | `value` | text | **Present only when `sensitivity = 1`.** Concealed and critical values live in the secret envelope. |

### 5.3 Secret envelope (item)

Opened only on an explicit reveal or copy (crypto spec §6.2).

| Key | Name | Type | Notes |
|---|---|---|---|
| 1 | `revision` | uint | Must equal the index envelope's `revision` |
| 2 | `values` | map: bytes(16) `field_id` → text | One entry for each field with `sensitivity` 2 or 3. Every such field in the index must have an entry here, and no other entries are allowed. |
| 3 | `notes` | text | Optional, concealed |

- An item with no concealed or critical fields still has a secret envelope, with an empty `values` map. So the presence of a secret envelope reveals nothing, and the 256-byte padding bucket hides whether it is empty.
- **CVV** is stored only if the user switched it on for that card (threat-model I11). It is a field with `key = card.cvv`, `kind = 14`, `sensitivity = 3` and `copyable = true`; its descriptor sets `has_cvv = true` in the index. **There is no card-PIN key**, and a decoder must reject `card.pin`.

### 5.4 History envelope

One row per earlier value of a concealed or critical field. At most 10 are kept per item; the oldest is deleted first.

| Key | Name | Type |
|---|---|---|
| 1 | `field_id` | bytes(16) |
| 2 | `value` | text |
| 3 | `replaced_at` | uint |

### 5.5 Settings (meta, kind 3)

Only security-relevant settings live here, so that weakening them requires the vault password (threat-model I6). Cosmetic preferences live in plaintext preferences (§8).

| Key | Name | Type | Default | Allowed |
|---|---|---|---|---|
| 1 | `auto_lock_s` | uint | 120 | 30 – 900 |
| 2 | `background_lock_s` | uint | 30 | 0 – 300 |
| 3 | `clipboard_ttl_s` | uint | 60 | 30, 60, 120 |
| 4 | `critical_window_s` | uint | 60 | 0 – 300 (0 = every time) |
| 5 | `password_check_days` | uint | 14 | 7, 14, 30 |
| 6 | `password_only` | bool | false | |
| 7 | `erase_after_failures` | bool | false | |
| 8 | `last_password_check` | uint | | |
| 9 | `last_backup_at` | uint | | Optional |
| 10 | `backup_reminder_days` | uint | 30 | 7 – 90 |

"Never auto-lock" is not a representable value.

### 5.6 Recovery (meta, kind 4)

| Key | Name | Type |
|---|---|---|
| 1 | `rs` | bytes(16): the Recovery Secret |
| 2 | `rs_id` | bytes(8) (crypto spec §9.2) |
| 3 | `created_at` | uint |

RS is kept inside the vault so that exports never require the user to type the Recovery Key, and so the periodic password check can open KS-REC (crypto spec §10).

### 5.7 Security log (meta, kind 5)

An array of at most 500 events, oldest first; the oldest is dropped when full.

| Key | Name | Type |
|---|---|---|
| 1 | `at` | uint |
| 2 | `event` | uint (below) |
| 3 | `count` | uint, optional |

Events: 1 unlock (biometric), 2 unlock (password), 3 unlock (recovery), 4 failed password attempts (with `count`), 5 biometric invalidated, 6 password changed, 7 Recovery Key regenerated, 8 vault key rotated, 9 backup exported, 10 backup imported, 11 setting weakened, 12 periodic check passed, 13 periodic check failed.

Failures that happen while the vault is locked cannot be written into the encrypted log at that moment. They are counted in plaintext preferences (§8) and moved into the log as event 4 at the next successful unlock. That move is what produces the "3 failed password attempts since your last unlock" banner.

### 5.8 Manifest (meta, kind 6) — PROPOSED

As defined in crypto spec §7.2:

| Key | Name | Type |
|---|---|---|
| 1 | `vault_revision` | uint (u64 range) |
| 2 | `entries` | array of `[record_kind (uint), record_id (bytes 16), revision (uint)]`, sorted by `record_id` |

History entries are listed with `revision = 1`; they are never edited, only added or deleted.

## 6. Limits

| Limit | Value |
|---|---|
| Items per vault | 10 000 |
| Fields per item | 100 |
| History entries per item | 10 |
| Any text value | 64 KiB |
| Any envelope, before padding | 1 MiB |
| Security-log events | 500 |

Writes that would exceed a limit are refused with a clear message. Reads that find a limit exceeded are verification failures.

## 7. Integrity checks at unlock

After a keyslot opens (crypto spec §7):

1. Verify `header_mac`.
2. Decrypt the manifest; check that it matches the set of `item` and `item_history` ids (§5.8).
3. Decrypt every index envelope; check each `revision` against the manifest.
4. Decrypt the settings and recovery records.

Secret envelopes are **not** opened at unlock. Their `revision` is checked each time one is opened. Any failure means "This vault's data could not be verified. Nothing was changed." The vault stays locked.

## 8. Plaintext preferences

The app's preferences file may contain **only** these keys. A CI check fails the build if any other key is written.

| Key | Type | Purpose |
|---|---|---|
| `vault_present` | bool | Show the lock screen, not onboarding |
| `onboarding_step` | int | Resume interrupted onboarding |
| `theme` | string: `system`, `light` or `dark` | Appearance |
| `haptics` | bool | Appearance |
| `failed_attempts` | int | Password throttling (crypto spec §10) |
| `delay_started_elapsed_ms` | long | `SystemClock.elapsedRealtime()` when the current delay began |
| `delay_started_boot_count` | int | `Settings.Global.BOOT_COUNT` when the current delay began. If it differs at the next check, the **full** delay starts again, so rebooting never shortens it. |
| `failures_since_unlock` | int | Moved into the security log at the next unlock (§5.7) |

A rooted attacker can reset these counters (threat-model T08). Guessing then still needs this device's hardware for each attempt, plus a full Argon2id.

## 9. Versions and migration

- `format_version` (header) and `user_version` (SQLite) change together.
- The app migrates eagerly: on the first unlock after an update, inside **one** transaction, re-encrypting where needed with fresh nonces. If migration fails, the transaction rolls back and the old format stays intact.
- An app never writes an older format. An app that meets a **newer** format refuses to open it and asks the user to update. It never tries a best-effort read.

## 10. Backup file (`.vaultbackup`)

### 10.1 Layout

| Offset | Size | Field |
|---|---|---|
| 0 | 8 | `magic` = `56 4C 54 42 4B 00 01 00` (ASCII `VLTBK`, then `00 01 00`) |
| 8 | 4 | `hdr_len`, u32 big-endian, ≤ 4096 |
| 12 | `hdr_len` | `header`, deterministic CBOR (§10.2) |
| … | 24 | `wrap_nonce` |
| … | 48 | `wrapped_FK` (32-byte FK + 16-byte tag) |
| … | 24 | `payload_nonce` |
| … | to end of file | `payload_ct` (padded payload + 16-byte tag) |

- Total size ≤ 64 MiB. The file name suggested to the user is `Vaultaire backup <YYYY-MM-DD>.vaultbackup`; the MIME type is `application/octet-stream`.
- The crypto spec's `u32(len(header))` in the AD is the same big-endian value as `hdr_len`. *The blueprint drafted `hdr_len` as little-endian; big-endian is used throughout to avoid a mismatch.*

### 10.2 Backup header

| Key | Name | Type | Value |
|---|---|---|---|
| 1 | `v` | uint | `1` |
| 2 | `kdf` | map | As keyslot `kdf` (§4.4); export defaults m = 131 072, t = 4, p = 1 |
| 3 | `rs_id` | bytes(8) | |
| 4 | `aead_id` | uint | `1` |

The header holds no device name, app version, timestamp or item count.

### 10.3 Payload

Deterministic CBOR, padded to a multiple of 4096 bytes (crypto spec §9.3):

| Key | Name | Type | Notes |
|---|---|---|---|
| 1 | `export_format` | uint | `1` |
| 2 | `items` | array (≤ 10 000) of item maps | |
| 3 | `settings` | map | As §5.5; restored so the user keeps their protection choices |

Item map:

| Key | Name | Type |
|---|---|---|
| 1 | `id` | bytes(16) |
| 2 | `index` | map, as §5.2 |
| 3 | `secret` | map, as §5.3 |
| 4 | `history` | array (≤ 10) of maps, as §5.4 |

The backup deliberately **leaves out** the Recovery Secret (the user types the Recovery Key to restore, and it stays valid), the security log (it describes this device), the manifest (rebuilt on import) and all keyslots (a restore creates a new VK and new slots).

### 10.4 Restore and merge

- **Restore** (new device): after import validation (crypto spec §9.4), the app creates a new vault with a **new VK** and the **same RS**, so the user's paper Recovery Kit stays valid. Items keep their ids.
- **Merge** (existing vault): every imported item gets a **new id**, so it can never overwrite an existing record, and a tag "Imported YYYY-MM-DD".

## 11. Questions for the G0 reviewer

| ID | Question |
|---|---|
| F1 | Is plaintext `item_history.item_id` (§4.2) acceptable, compared with storing history inside the item's secret envelope? |
| F2 | Should the backup include the security log? (Proposed: no.) |
| F3 | Are the settings ranges (§5.5) right, in particular allowing `critical_window_s = 300`? |
| F4 | Normal-tier field values live in the index envelope and are decrypted at unlock (§5.2). Is that the right line, given that bank names and card expiry dates are then in memory for the whole session? |
