# Vaultaire cryptographic specification

| | |
|---|---|
| Status | **Draft 0.1, not yet reviewed.** Input to Gate G0 (spec freeze). Values marked **PROVISIONAL** are fixed at G0 after the Phase-0 measurements. |
| Scope | Format version 1 (`format_version = 1`): key hierarchy, keyslots, record encryption, integrity, Recovery Key encoding and backup encryption |
| Related | [`threat-model.md`](threat-model.md) (why), `vault-format.md` (byte layouts and schemas; to be written) |
| Last updated | 2026-09-29 |

This document is normative. The words **must**, **must not** and **should** carry their usual RFC 2119 meaning. An implementation that differs from this document is wrong, even if it "works".

**Everything cryptographic lives in `vault-core` (Rust).** The Android layer handles only the hardware keys (§5.1, §5.2) and passes opaque byte buffers across the FFI.

---

## 1. Principles

1. **No custom primitives.** Every cipher, MAC, hash, KDF and RNG comes from an audited or widely reviewed library (§2). Only the **composition** is ours, which is why it is written down here, given test vectors (§12) and reviewed externally.
2. **One algorithm per purpose, no negotiation.** Algorithm identifiers come from a fixed registry (§2.2). An unknown identifier or version is rejected. No code path can select a weaker option.
3. **Authenticate before use.** No decrypted byte is used, parsed or shown before its tag has been verified (threat-model I4, I8).
4. **Fail closed, uniformly.** Every verification failure aborts the operation with no partial output. Failures that could be told apart would create an oracle, so they share one error (§11).
5. **Domain separation everywhere.** Every derived key and every associated-data string carries a distinct ASCII label (§3.3).
6. **Fixed-length fields in every concatenation.** Nothing is concatenated unless every part has a fixed length or a length prefix, so no two inputs can encode to the same bytes.

## 2. Primitives

| Purpose | Algorithm | Reference | Library (Rust) |
|---|---|---|---|
| AEAD | **XChaCha20-Poly1305**: 32-byte key, 24-byte nonce, 16-byte tag | draft-irtf-cfrg-xchacha-03; RFC 8439 | `chacha20poly1305` (RustCrypto; audited by NCC Group, 2020) |
| Password hashing | **Argon2id**, version 0x13 | RFC 9106 | `argon2` (RustCrypto). No independent audit known; see §12.3. |
| Key derivation | **HKDF-SHA256** | RFC 5869 | `hkdf` + `sha2` |
| MAC | **HMAC-SHA256** | RFC 2104 | `hmac` + `sha2` |
| Hash | SHA-256 | FIPS 180-4 | `sha2` |
| Randomness | Operating-system CSPRNG | `getrandom(2)` on Android | `getrandom` |
| Constant-time equality | — | — | `subtle` |
| Zeroization | — | — | `zeroize`, `secrecy` |
| Hardware wrap (Android) | AES-256-GCM and HMAC-SHA256 **inside Android Keystore** | Keystore docs | Platform (Kotlin) |

If the Phase-0 spike finds a problem with RustCrypto, libsodium (`crypto_aead_xchacha20poly1305_ietf`, `crypto_pwhash`) is the alternative provider. Switching providers must not change a single byte of output; the vectors in §12 prove it.

### 2.1 Randomness rules

- Every key, salt, nonce and identifier in this document comes from the OS CSPRNG. Nothing is derived from time, counters or a seeded PRNG.
- If the CSPRNG fails, the operation aborts. There is no fallback source.

| Value | Size | Notes |
|---|---|---|
| VK (vault key) | 32 bytes | Created once per vault; replaced only by key rotation |
| SK (biometric slot key) | 32 bytes | Replaced whenever the biometric key is re-created |
| RS (Recovery Secret) | 16 bytes (128 bits) | Shown to the user as the Recovery Key (§8) |
| FK (backup file key) | 32 bytes | Fresh for every export |
| Salts (`salt_pwd`, `salt_rec`, `salt_exp`) | 16 bytes each | Fresh on creation and on every password change |
| AEAD nonces | 24 bytes | Fresh for **every** encryption (§6.3) |
| `vault_id`, `slot_id`, record ids | 16 bytes | Random; formatted as UUIDv4 only for display |

### 2.2 Algorithm registry (format version 1)

| Field | Value | Meaning |
|---|---|---|
| `aead_id` | `0x01` | XChaCha20-Poly1305 |
| `kdf_id` | `0x01` | Argon2id v0x13 |
| `mac_id` | `0x01` | HMAC-SHA256 |

Any other value **must** be rejected. A new algorithm requires a new `format_version`.

## 3. Conventions

### 3.1 Notation

- `a ‖ b`: byte concatenation.
- `u8(x)`, `u16(x)`, `u32(x)`: unsigned integers, **big-endian**, of 1, 2 or 4 bytes.
- `ascii("…")`: the ASCII bytes of a string, with no terminator and no length prefix. Every label in this document has a fixed, distinct value (§3.3), so labels need no length prefix.
- `zeros(n)`: `n` zero bytes.
- `HKDF(ikm, salt, info, L)` = `HKDF-Expand(HKDF-Extract(salt, ikm), info, L)` with SHA-256 (RFC 5869).
- `Seal(K, N, P, AD)` and `Open(K, N, C, AD)`: XChaCha20-Poly1305. `Seal` returns ciphertext ‖ 16-byte tag. `Open` returns the plaintext **only if** the tag verifies.
- `Argon2id(P, S, m, t, p)`: 32-byte output. `m` is in KiB. No secret key (`K`) and no associated data (`X`).

### 3.2 Password encoding

Before **every** Argon2id call on a password:

1. Normalise to **Unicode NFC**. The same visible password typed on different keyboards can produce different code-point sequences. Without normalisation, restoring a backup on another device could fail with "wrong password".
2. Encode as UTF-8.
3. Reject an empty result, or one longer than **1024 bytes**.

Nothing else is changed: no trimming, no case folding.

The strength policy (at least 12 characters **and** a zxcvbn estimate of ≥ 10¹² guesses) is enforced when a password is **set or changed**, never when it is checked. A policy change must never lock out an existing user.

### 3.3 Label registry

Every label is ASCII and starts with `vaultaire/v1/`.

| Label | Used in |
|---|---|
| `vaultaire/v1/kek/bio` | §4.2 KEK for KS-BIO |
| `vaultaire/v1/hw-input` | §4.3 input to the hardware HMAC |
| `vaultaire/v1/kek/pwd` | §4.3 KEK for KS-PWD |
| `vaultaire/v1/kek/rec` | §4.4 KEK for KS-REC |
| `vaultaire/v1/slot` | §4.5 associated data for keyslot wraps |
| `vaultaire/v1/subkey/index` | §6.1 K_idx |
| `vaultaire/v1/subkey/secret` | §6.1 K_sec |
| `vaultaire/v1/subkey/meta` | §6.1 K_meta |
| `vaultaire/v1/subkey/header` | §6.1 K_hdr |
| `vaultaire/v1/record` | §6.4 associated data for records |
| `vaultaire/v1/header-mac` | §7.1 header MAC input |
| `vaultaire/v1/rs-id` | §9.2 Recovery Secret identifier |
| `vaultaire/v1/rk-checksum` | §8 Recovery Key checksum |
| `vaultaire/v1/kek/export` | §9.2 backup KEK |
| `vaultaire/v1/export/wrap` | §9.3 associated data for the FK wrap |
| `vaultaire/v1/export/payload` | §9.3 associated data for the backup payload |

## 4. Key hierarchy and keyslots

```
                ┌──────────── bound to this device's hardware ────────────┐
KS-BIO:  K_bio (strong biometric, per use) ──releases──► SK ──HKDF──► KEK_bio ─┐
KS-PWD:  Argon2id(pw, salt_pwd) ─┬──────────────────────────────┐              │
                                 └─HKDF─► hw_in ──HMAC(K_pwd)──► HW_pwd         │
                                  (device credential)            │              ├─ Open ─► VK
                                 H_pwd ‖ HW_pwd ──HKDF──► KEK_pwd ┘              │
KS-REC:  Argon2id(pw, salt_rec) ‖ RS ──HKDF──► KEK_rec ─────────────────────────┘

VK ──HKDF──► K_idx (index envelopes) · K_sec (secret envelopes) · K_meta (settings, RS, log, manifest) · K_hdr (header MAC)
```

### 4.1 Vault key

- **VK** is 32 bytes from the CSPRNG, created inside `vault-core` when the vault is created.
- VK is **never** derived from user input and **never** stored in plaintext.
- VK is stored only as three wrapped copies (`wrapped_vk`), one per keyslot. Each is `Seal(KEK_slot, nonce, VK, AD_slot)`, with a fresh nonce and the associated data of §4.5.

### 4.2 KS-BIO: biometric slot

Hardware key (Android Keystore, created by the Kotlin SecurityModule):

```
K_bio : AES-256-GCM, PURPOSE_ENCRYPT | PURPOSE_DECRYPT
        setUserAuthenticationRequired(true)
        setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)   // every use needs a fresh Class 3 biometric
        setInvalidatedByBiometricEnrollment(true)
        setUnlockedDeviceRequired(true)
        StrongBox first; on StrongBoxUnavailableException, TEE. Record which one in the slot parameters.
```

- On creation, `vault-core` generates **SK** (32 bytes). Keystore encrypts it: `bio_blob = AES-GCM(K_bio, iv, SK, AAD = vault_id ‖ ascii("KS-BIO"))`. Keystore picks the 12-byte IV. The IV and `bio_blob` go in the slot parameters.
- SK is then zeroized in both Kotlin and Rust.
- To unlock, Keystore decrypts `bio_blob` inside `BiometricPrompt.authenticate(CryptoObject(cipher))`. **The biometric result is the key release itself, never a boolean** (threat-model I7).
- `KEK_bio = HKDF(ikm = SK, salt = vault_id, info = ascii("vaultaire/v1/kek/bio"), L = 32)`

`K_bio` **must not** accept `DEVICE_CREDENTIAL` or any Class 2 biometric. Either one would let the device passcode alone open the vault.

### 4.3 KS-PWD: password slot (fallback)

Hardware key:

```
K_pwd : HMAC-SHA256
        setUserAuthenticationRequired(true)
        setUserAuthenticationParameters(30, AUTH_DEVICE_CREDENTIAL)  // one credential prompt covers retries for 30 s
        setUnlockedDeviceRequired(true), StrongBox preferred
```

Derivation:

```
H_pwd   = Argon2id(password, salt_pwd, m, t, p)                        // §4.6 parameters
hw_in   = HKDF(ikm = H_pwd, salt = vault_id, info = ascii("vaultaire/v1/hw-input"), L = 32)
HW_pwd  = HMAC-SHA256(K_pwd, hw_in)                                    // computed inside Keystore
KEK_pwd = HKDF(ikm = H_pwd ‖ HW_pwd, salt = vault_id, info = ascii("vaultaire/v1/kek/pwd"), L = 32)
```

- Every password guess needs one HMAC operation inside the device's hardware. That operation needs the device credential. So even an attacker who copies `vault.db` cannot run guesses anywhere else (threat-model T14).
- `hw_in` rather than `H_pwd` goes to Keystore, so the Argon2id output never leaves `vault-core` in a form that could stand in for the password hash.
- `H_pwd`, `hw_in` and `HW_pwd` are zeroized after use.

### 4.4 KS-REC: recovery slot

```
H_rec   = Argon2id(password, salt_rec, m, t, p)
KEK_rec = HKDF(ikm = H_rec ‖ RS, salt = vault_id, info = ascii("vaultaire/v1/kek/rec"), L = 32)
```

- KS-REC is not bound to any device. It needs **both** the vault password and the Recovery Secret: neither secret alone is enough (threat-model I3).
- `salt_rec` differs from `salt_pwd`, so the two slots never share an Argon2id output.

### 4.5 Associated data for keyslot wraps

```
AD_slot = ascii("vaultaire/v1/slot")
        ‖ u16(format_version)
        ‖ vault_id                      (16)
        ‖ u8(slot_type)                 (0x01 BIO, 0x02 PWD, 0x03 REC)
        ‖ slot_id                       (16)
        ‖ SHA-256(slot_params_bytes)    (32)
```

`slot_params_bytes` is the exact stored encoding of the slot's parameters: KDF id, Argon2id m/t/p, salt, hardware security level, IV and `bio_blob`. The layout is defined in `vault-format.md`. Binding their hash into the AD means a slot's parameters cannot be swapped, downgraded (for example lower Argon2id cost) or moved to another vault without `Open` failing.

### 4.6 Argon2id parameters — PROVISIONAL

| Use | m (memory) | t (passes) | p (lanes) | Output |
|---|---|---|---|---|
| KS-PWD and KS-REC (on device) | 65 536 KiB (64 MiB) | 3 | 1 | 32 bytes |
| Backup export (§9) | 131 072 KiB (128 MiB) | 4 | 1 | 32 bytes |

- **Accepted range** when opening a slot or importing a backup: m ∈ [65 536, 524 288] KiB, t ∈ [3, 16], p ∈ [1, 4]. Values outside this range **must** be rejected **before** any Argon2id work. This blocks both memory-exhaustion attacks and downgrades (threat-model T17).
- Final values are fixed at G0 from the Phase-0 measurements on the lowest supported device (threat-model R5). Target: ≤ 1 s for unlock on that device.

## 5. Hardware boundary (Android)

### 5.1 What crosses the FFI

| Direction | Value | Lifetime |
|---|---|---|
| Kotlin → core | SK, after biometric release | Zeroized right after `KEK_bio` is derived |
| Core → Kotlin → core | `hw_in` out; `HW_pwd` back | Zeroized right after `KEK_pwd` is derived |
| Core → Kotlin | One decrypted field value, on explicit reveal or copy | Until re-mask, navigation or lock (threat-model I10) |

VK and its subkeys **never** cross the FFI.

### 5.2 Refusals

`vault-core` **must** refuse to create a vault, and the app shows why, when:

- the device has no secure lock screen (`KeyguardManager.isDeviceSecure() == false`), or
- Keystore reports that `K_bio` or `K_pwd` is software-backed.

The emulator's Keystore is software-backed. It is allowed only in debug builds for CI.

## 6. Record encryption

### 6.1 Subkeys

```
K_idx  = HKDF(ikm = VK, salt = vault_id, info = ascii("vaultaire/v1/subkey/index"),  L = 32)
K_sec  = HKDF(ikm = VK, salt = vault_id, info = ascii("vaultaire/v1/subkey/secret"), L = 32)
K_meta = HKDF(ikm = VK, salt = vault_id, info = ascii("vaultaire/v1/subkey/meta"),   L = 32)
K_hdr  = HKDF(ikm = VK, salt = vault_id, info = ascii("vaultaire/v1/subkey/header"), L = 32)
```

All four are derived at unlock and zeroized, together with VK, at lock.

### 6.2 Envelopes

| Envelope kind | `envelope_kind` | Key | Holds | Padding bucket |
|---|---|---|---|---|
| Index | `0x01` | K_idx | Title, type, tags, favorite flag, subtitle (for example "•••• 4821"), URL hosts, timestamps, card finish, "has CVV" flag, record revision | 64 bytes |
| Secret | `0x02` | K_sec | Every concealed and critical field of one item (passwords, card number, CVV if opted in, account numbers, document numbers, notes, custom fields) | 256 bytes |
| Meta | `0x03` | K_meta | Security settings, RS, security log, recent-usage data, the manifest (§7.2) | 256 bytes |

- Each item has **one index envelope and one secret envelope**. Password-history entries are separate records with a secret envelope each.
- **Per-field access.** The secret envelope is opened only when the user reveals or copies a field. The core extracts that one field, hands only it across the FFI, and zeroizes the rest of the plaintext at once. *Reviewer question Q1: is one secret envelope per item acceptable, compared with one envelope per field (which would reveal field counts through row counts)?*
- Plaintext is `pad(CBOR(record))`. The CBOR schemas are in `vault-format.md`.

### 6.3 Nonces

- Every `Seal` uses a fresh 24-byte random nonce, stored next to the ciphertext.
- Nonces are never counters, never derived and never reused. Re-encrypting a record, even with an unchanged plaintext, uses a new nonce.
- With 192-bit random nonces, the chance of a collision stays negligible far beyond any realistic number of encryptions. No per-key message limit applies.

### 6.4 Associated data for records

```
AD_record = ascii("vaultaire/v1/record")
          ‖ u16(format_version)
          ‖ vault_id              (16)
          ‖ u8(record_kind)       (0x01 item, 0x02 history entry, 0x03 settings, 0x04 recovery, 0x05 security log, 0x06 manifest)
          ‖ record_id             (16; zeros(16) for the single settings, recovery, log and manifest records)
          ‖ parent_id             (16; the owning item's id for a history entry, otherwise zeros(16))
          ‖ u8(envelope_kind)
          ‖ u16(schema_version)
```

This binding means an envelope cannot be moved to another item, another field class, another record kind or another vault without `Open` failing (threat-model T18).

### 6.5 Padding

- ISO/IEC 7816-4 style: append `0x80`, then the fewest `0x00` bytes that make the length a multiple of the bucket size (§6.2). At least one byte (`0x80`) is always added.
- After decryption, unpadding is strict: scan back over `0x00` bytes, then require exactly one `0x80`. Anything else is a verification failure (§11).
- Padding hides exact lengths, for example a 16-digit card number next to a 30-character password.
- Size limits before padding: 64 KiB per string, 1 MiB per envelope. Larger records are rejected when written.

## 7. Integrity

### 7.1 Header MAC

The vault header (format version, vault id, algorithm ids) and the **keyslot table** are covered by a MAC:

```
header_mac = HMAC-SHA256(K_hdr, ascii("vaultaire/v1/header-mac") ‖ u32(len(header_bytes)) ‖ header_bytes ‖ keyslot_table_bytes)
```

- `keyslot_table_bytes` is the canonical encoding of every slot, **sorted by `slot_id`**, as defined in `vault-format.md`.
- Unlock order: open a keyslot → derive `K_hdr` → verify `header_mac` in constant time → only then is the vault Unlocked. A mismatch is a verification failure.
- The MAC catches removed or added slots and header edits. The AD binding (§4.5) already catches a changed slot on its own.

### 7.2 Manifest — PROPOSED

Per-record AEAD cannot, on its own, detect a **deleted** record, or one **rolled back** to an older valid version. Both would keep valid tags. The manifest closes that gap:

- One meta record (`record_kind = 0x06`) holds `vault_revision` (u64) and, for every item and history entry, `(record_kind, record_id, revision)`.
- Every write transaction increments `vault_revision`, updates the changed entries and rewrites the manifest **in the same SQLite transaction**. Each index envelope also stores its own `revision`.
- At unlock, the core checks that every record id in the database appears in the manifest, and the reverse, and that each index envelope's revision matches. Any mismatch is a verification failure.
- **What remains undetected:** rolling back the *whole* database, manifest included, to an older consistent copy. That needs root access. It is listed as residual risk 8 in the threat model; a hardware-protected generation counter is planned for V1.1.

*Reviewer question Q3: is the manifest worth its cost (one rewrite per transaction, up to about 250 KiB at 10 000 items)?* If it is adopted, threat-model T18 must be updated to list it.

## 8. Recovery Key

The Recovery Key is RS written for humans: 28 Crockford Base32 characters in 7 groups of 4, for example `7KQM-4TXB-R9HF-2WZN-D3PV-8YCJ-G5EA`.

**Encoding:**

1. Treat RS (16 bytes) as a 128-bit big-endian integer. Prepend two zero bits, giving 130 bits.
2. Encode those 130 bits as 26 Crockford Base32 symbols (alphabet `0123456789ABCDEFGHJKMNPQRSTVWXYZ`), most significant first. The first symbol is therefore always `0`–`7`.
3. `c = HKDF(ikm = RS, salt = zeros(0), info = ascii("vaultaire/v1/rk-checksum"), L = 2)`. Take the **first 10 bits** of `c` and encode them as 2 more symbols.
4. Join the 28 symbols in groups of 4 with `-`.

**Decoding** (restore, reset and verification inputs):

1. Uppercase. Remove spaces and `-`. Map `O` → `0` and `I`, `L` → `1`. Reject `U` and any other character outside the alphabet.
2. Require exactly 28 symbols, and a first symbol of `0`–`7`.
3. Recompute the checksum and compare. **The checksum catches typos only; it adds no security.** On mismatch, show a typo message **before** any expensive work.
4. Decode to RS.

**Comparison:** when a Recovery Key is checked against the stored RS (password reset, §10), the comparison **must** be constant-time (`subtle`).

**Display:** the key is shown only on screens with FLAG_SECURE. V1 offers no PDF, print or copy output, because print spoolers and "Save as PDF" can leak to storage and cloud (threat-model T12, T26).

## 9. Backup encryption (`.vaultbackup`)

### 9.1 Principle

A backup needs **two independent secrets**: the vault password and the Recovery Secret. The file alone, the Recovery Key alone, or both together without the password, are not enough (threat-model T25, T29).

### 9.2 Keys

```
rs_id   = HKDF(ikm = RS, salt = zeros(0), info = ascii("vaultaire/v1/rs-id"), L = 8)
H_exp   = Argon2id(password, salt_exp, m_exp, t_exp, p_exp)            // §4.6 export parameters
KEK_exp = HKDF(ikm = H_exp ‖ RS, salt = salt_exp, info = ascii("vaultaire/v1/kek/export"), L = 32)
FK      = 32 random bytes, fresh for each export
```

- `rs_id` is stored in the backup header. It lets the app say "this backup was made with a different Recovery Key" before any expensive work. It reveals nothing about RS. It does let someone holding two backup files see whether they share a Recovery Key; *reviewer question Q5*.

### 9.3 File encryption

```
header      = strict CBOR { v, kdf: {id, m, t, p, salt_exp}, rs_id, aead_id }     (≤ 4 KiB; layout in vault-format.md)
AD_wrap     = ascii("vaultaire/v1/export/wrap")    ‖ magic ‖ u32(len(header)) ‖ header
AD_payload  = ascii("vaultaire/v1/export/payload") ‖ magic ‖ u32(len(header)) ‖ header
wrapped_FK  = Seal(KEK_exp, nonce_wrap, FK, AD_wrap)
payload_ct  = Seal(FK, nonce_payload, pad4096(CBOR(payload)), AD_payload)
```

- `pad4096` uses the padding of §6.5 with a 4096-byte bucket. It hides small differences in vault size.
- The plaintext header holds **no** device name, app version, timestamp or item count.
- The payload is one AEAD message (V1 has no attachments). Before parsing, the whole file is limited to 64 MiB.

### 9.4 Import order

Every step must pass before the next begins. Any failure aborts, and nothing is written:

1. File size ≤ 64 MiB, checked before reading.
2. Exact magic; known `v`.
3. Header length ≤ 4 KiB. Strict CBOR: depth ≤ 4, ≤ 16 entries, no indefinite lengths, no tags, unknown keys rejected.
4. KDF parameters within the range of §4.6.
5. `rs_id` matches the Recovery Key the user typed; otherwise a friendly error.
6. Argon2id → `KEK_exp` → `Open(wrapped_FK)`.
7. `Open(payload_ct)` → strict unpadding → parse under schema limits (≤ 10 000 items, ≤ 100 fields per item, ≤ 64 KiB per string).
8. Semantic checks: types, unique ids, valid dates.
9. Stage in memory → the user confirms a summary → re-encrypt every record under the current VK with fresh nonces → commit in one transaction.

Steps 6 and 7 fail with the same message: "Wrong password or Recovery Key, or the file is damaged."

## 10. Key lifecycle

| Operation | What changes | Requires |
|---|---|---|
| Create vault | New VK, SK, RS, salts, `vault_id`; all three slots written; manifest created | Secure lock screen; hardware-backed keys (§5.2) |
| Change password | New `salt_pwd` and `salt_rec`; KS-PWD and KS-REC re-wrapped. VK unchanged. Suggest a new backup (old backups still need the old password). | Current password |
| Reset forgotten password | As "change password" | An unlocked biometric session **and** the Recovery Key (constant-time compare, §8) |
| Regenerate Recovery Key | New RS; KS-REC re-wrapped; RS in K_meta replaced; new Recovery Kit shown. Old backups still need the old key. | Password |
| Rotate vault key | New VK′; every record re-encrypted and every slot re-wrapped in **one transaction**; old VK zeroized. If the process crashes, the old state stays valid until commit. | Password; offered after a restore or when the user suspects exposure |
| Re-create biometric key | New K_bio and SK; KS-BIO re-wrapped | Password, after K_bio is invalidated by a biometric enrolment change |
| Periodic password check | Nothing changes. `KEK_rec` is derived from the typed password and RS (held in K_meta), KS-REC is opened, and the result is compared to the in-memory VK in constant time. | Every 14 days after biometric unlock; one 24-hour deferral |
| Lock | VK, subkeys, the decrypted index and any revealed value are zeroized **before** any animation | Inactivity 2 min, background 30 s, screen off, manual lock, process death |

A successful periodic check proves two things: the user still knows the password, and the recovery slot still opens.

## 11. Errors and side channels

- **Unlock failures** show the same message whatever the cause: wrong password, wrong device credential, failed tag or failed header MAC. The app never reveals which factor failed.
- **Corrupted and tampered data look identical**: "This vault's data could not be verified. Nothing was changed." There is no "repair" path.
- **Timing:** Argon2id dominates the time of every password path, so how early a failure is detected is not observable at a useful resolution. Secret-dependent comparisons (tags inside the AEAD library, RS, VK in the periodic check) are constant-time.
- **Zeroization:** VK, subkeys, KEKs, SK, `H_*`, `HW_pwd`, RS, FK and decrypted plaintext buffers are held in `zeroize`-on-drop types, with best-effort `mlock`. On the Kotlin side, passwords are held in `CharArray` or `ByteArray` and cleared after use. **Residual risk:** Compose text fields produce immutable `String` copies that stay in memory until garbage collection (threat-model T10).

## 12. Test vectors

### 12.1 Primitive known-answer tests

Each primitive is checked against its published vectors on every build:

- XChaCha20-Poly1305: draft-irtf-cfrg-xchacha-03, appendix A.
- Argon2id: RFC 9106, section 5.3.
- HKDF-SHA256: RFC 5869, appendix A.
- HMAC-SHA256: RFC 4231.

### 12.2 Composition vectors

For every construction in this document there is a JSON vector: all inputs (fixed keys, salts, nonces and passwords, including non-NFC passwords), every intermediate value and the expected output. The set covers:

- the three slot derivations and wraps, with Keystore stand-ins (fixed SK and a software HMAC key standing in for K_pwd);
- the subkeys, record AD, padding and envelopes;
- the header MAC and the manifest;
- Recovery Key encoding and decoding, including every normalisation case and checksum failures;
- a complete backup file, plus tampered variants that must fail (one flipped bit per field, truncation, swapped envelopes, parameters out of range).

Vectors live in `tests/vectors/v1/`. They are **generated by the Rust implementation and cross-checked by an independent implementation**: a small dev-only Python script using libsodium bindings, `cryptography` and `argon2-cffi`. Two independent implementations that agree are the main defence against a composition bug that both a writer and a reviewer could miss.

### 12.3 The `argon2` crate

The `argon2` crate has no known independent audit. It is checked against the RFC 9106 vectors, differentially tested against the reference C implementation (a dev-only dependency), and included in the scope of the external audit.

## 13. Do not implement

- Any cipher, MAC, hash, KDF, AEAD, RNG or constant-time comparison written by hand.
- CBC, ECB or any mode with a padding oracle.
- Counter-based or derived nonces.
- Any key exchange, PAKE, OPRF or hash-to-curve construction (including the SE-ECDH OPRF idea rejected for iOS).
- "Secure delete" routines. File-based encryption plus AEAD is the deletion story.
- Algorithm or parameter negotiation.
- Parsing, displaying or acting on any byte before its tag has verified.
- Biometric unlock that treats a callback result as a boolean.

## 14. Questions for the G0 reviewer

| ID | Question |
|---|---|
| Q1 | One secret envelope per item (§6.2), or one per field? |
| Q2 | Are the provisional Argon2id parameters (§4.6) reasonable, pending device measurements? |
| Q3 | Adopt the manifest (§7.2)? |
| Q4 | Is `hw_in = HKDF(H_pwd, …)` as the hardware HMAC input sound (§4.3)? |
| Q5 | Is `rs_id` linkability between backups (§9.2) acceptable? |
| Q6 | Is binding `SHA-256(slot_params_bytes)` into the slot AD sufficient to stop parameter downgrade (§4.5), given the separate range check? |
| Q7 | Card security codes are now storable on an opt-in basis (threat-model §9). Is anything beyond critical-tier handling needed at the cryptographic level? |
