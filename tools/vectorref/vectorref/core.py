"""Constructions of the crypto spec and vault format, written from the spec text alone.

Every function cites the section it implements. Randomness is always an explicit argument, so
the same inputs always give the same bytes (test-vector plan §4).
"""
from . import cbor
from .errors import VaultError
from .prims import (argon2id, ct_eq, encode_password, hkdf, hmac_sha256, open_, seal, sha256)

FORMAT_VERSION = 1
SCHEMA_VERSION = 1

# §3.3 Label registry
L_BIO_BLOB = b'vlt/v1/bio-blob'  # AAD inside Keystore (§4.2); not computed by the core
L_KEK_BIO = b'vlt/v1/kek/bio'
L_HW_INPUT = b'vlt/v1/hw-input'
L_KEK_PWD = b'vlt/v1/kek/pwd'
L_KEK_REC = b'vlt/v1/kek/rec'
L_SLOT = b'vlt/v1/slot'
L_SUB_INDEX = b'vlt/v1/subkey/index'
L_SUB_SECRET = b'vlt/v1/subkey/secret'
L_SUB_META = b'vlt/v1/subkey/meta'
L_SUB_HEADER = b'vlt/v1/subkey/header'
L_RECORD = b'vlt/v1/record'
L_HEADER_MAC = b'vlt/v1/header-mac'
L_RS_ID = b'vlt/v1/rs-id'
L_RK_CHECKSUM = b'vlt/v1/rk-checksum'
L_KEK_EXPORT = b'vlt/v1/kek/export'
L_EXPORT_WRAP = b'vlt/v1/export/wrap'
L_EXPORT_PAYLOAD = b'vlt/v1/export/payload'
LABELS = {k: v for k, v in globals().items() if k.startswith('L_')}

SLOT_BIO, SLOT_PWD, SLOT_REC = 1, 2, 3
REC_ITEM, REC_HISTORY, REC_SETTINGS, REC_RECOVERY, REC_LOG, REC_MANIFEST = 1, 2, 3, 4, 5, 6
ENV_INDEX, ENV_SECRET, ENV_META = 1, 2, 3
BUCKET = {ENV_INDEX: 64, ENV_SECRET: 256, ENV_META: 256}
BACKUP_BUCKET = 4096

# §4.6 accepted range, checked before any Argon2id work
M_MIN, M_MAX, T_MIN, T_MAX, P_MIN, P_MAX = 65536, 524288, 3, 16, 1, 4


def u8(x):
    return x.to_bytes(1, 'big')


def u16(x):
    return x.to_bytes(2, 'big')


def u32(x):
    return x.to_bytes(4, 'big')


def check_kdf_params(m, t, p):
    """§4.6: reject out-of-range parameters before any Argon2id work."""
    if not (M_MIN <= m <= M_MAX and T_MIN <= t <= T_MAX and P_MIN <= p <= P_MAX):
        raise VaultError('params_out_of_range')


# ---------- §4 Keyslots ----------

def kek_bio(sk, vault_id):
    """§4.2"""
    return hkdf(sk, vault_id, L_KEK_BIO, 32)


def kek_pwd(password, salt_pwd, vault_id, m, t, p, hw_hmac):
    """§4.3. `hw_hmac(hw_in)` stands in for the Keystore HMAC with K_pwd. Returns (kek, intermediates)."""
    check_kdf_params(m, t, p)
    h_pwd = argon2id(encode_password(password), salt_pwd, m, t, p)
    hw_in = hkdf(h_pwd, vault_id, L_HW_INPUT, 32)
    hw_pwd = hw_hmac(hw_in)
    kek = hkdf(h_pwd + hw_pwd, vault_id, L_KEK_PWD, 32)
    return kek, {'h_pwd': h_pwd, 'hw_in': hw_in, 'hw_pwd': hw_pwd}


def kek_rec(password, salt_rec, rs, vault_id, m, t, p):
    """§4.4"""
    check_kdf_params(m, t, p)
    h_rec = argon2id(encode_password(password), salt_rec, m, t, p)
    return hkdf(h_rec + rs, vault_id, L_KEK_REC, 32), {'h_rec': h_rec}


def ad_slot(vault_id, slot_type, slot_id, params_bytes):
    """§4.5"""
    return L_SLOT + u16(FORMAT_VERSION) + vault_id + u8(slot_type) + slot_id + sha256(params_bytes)


def wrap_vk(kek, nonce, vk, ad):
    """§4.1: wrapped_vk = Seal(KEK_slot, nonce, VK, AD_slot)"""
    return seal(kek, nonce, vk, ad)


def unwrap_vk(kek, nonce, wrapped, ad):
    return open_(kek, nonce, wrapped, ad, error='unlock_failed')


def keyslot_params(slot_type, *, hw_level=None, alias=None, iv=None, bio_blob=None, kdf=None):
    """vault-format §4.4: the params map for one slot type."""
    m = {1: slot_type}
    if slot_type in (SLOT_BIO, SLOT_PWD):
        m[2], m[3] = hw_level, alias
    if slot_type == SLOT_BIO:
        m[4], m[5] = iv, bio_blob
    if slot_type in (SLOT_PWD, SLOT_REC):
        m[6] = {1: 1, 2: kdf['m'], 3: kdf['t'], 4: kdf['p'], 5: kdf['salt']}
    return cbor.encode(m)


def keystore_alias(kind, slot_id):
    """vault-format §4.4: vlt.v1.bio.<hex(slot_id)> or vlt.v1.pwd.<hex(slot_id)>"""
    return f'vlt.v1.{kind}.{slot_id.hex()}'


# ---------- §6 Records ----------

def subkeys(vk, vault_id):
    """§6.1"""
    return {
        'k_idx': hkdf(vk, vault_id, L_SUB_INDEX, 32),
        'k_sec': hkdf(vk, vault_id, L_SUB_SECRET, 32),
        'k_meta': hkdf(vk, vault_id, L_SUB_META, 32),
        'k_hdr': hkdf(vk, vault_id, L_SUB_HEADER, 32),
    }


def pad(data, bucket):
    """§6.5: append 0x80, then the fewest 0x00 bytes to reach a multiple of the bucket."""
    out = data + b'\x80'
    return out + b'\x00' * (-len(out) % bucket)


def unpad(data, bucket):
    """§6.5: strict. A wrong length, or anything but exactly one 0x80 after the zeros, fails."""
    if len(data) == 0 or len(data) % bucket:
        raise VaultError('auth_failed')
    i = len(data) - 1
    while i >= 0 and data[i] == 0:
        i -= 1
    if i < 0 or data[i] != 0x80:
        raise VaultError('auth_failed')
    return data[:i]


def ad_record(vault_id, record_kind, record_id, parent_id, envelope_kind):
    """§6.4"""
    return (L_RECORD + u16(FORMAT_VERSION) + vault_id + u8(record_kind) + record_id + parent_id
            + u8(envelope_kind) + u16(SCHEMA_VERSION))


def seal_record(key, nonce, record, envelope_kind, ad):
    """§6.2: plaintext is pad(CBOR(record))"""
    return seal(key, nonce, pad(cbor.encode(record), BUCKET[envelope_kind]), ad)


def open_record(key, nonce, ct, envelope_kind, ad):
    return cbor.decode(unpad(open_(key, nonce, ct, ad), BUCKET[envelope_kind]))


# ---------- §7 Integrity ----------

def vault_header(vault_id):
    """vault-format §4.3"""
    return cbor.encode({1: FORMAT_VERSION, 2: vault_id, 3: 1, 4: 1, 5: 1})


def keyslot_table(rows):
    """vault-format §4.3: CBOR array of [slot_id, slot_type, params, nonce, wrapped_vk], sorted by slot_id."""
    rows = sorted(rows, key=lambda r: r['slot_id'])
    return cbor.encode([[r['slot_id'], r['slot_type'], r['params'], r['nonce'], r['wrapped_vk']] for r in rows])


def header_mac(k_hdr, header_bytes, table_bytes):
    """§7.1"""
    return hmac_sha256(k_hdr, L_HEADER_MAC + u32(len(header_bytes)) + header_bytes + table_bytes)


# ---------- §8 Recovery Key ----------

ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'


def rk_checksum_bits(rs):
    """§8 step 3: the first 10 bits of HKDF(RS, zeros(0), label, 2), most significant first."""
    c = hkdf(rs, b'', L_RK_CHECKSUM, 2)
    return int.from_bytes(c, 'big') >> 6


def rk_encode(rs):
    """§8 encoding: 26 symbols for 2 zero bits ‖ RS, then 2 checksum symbols; groups of 4 with '-'."""
    assert len(rs) == 16
    n = int.from_bytes(rs, 'big')  # 128 bits; the two prepended zero bits make it 130
    body = ''.join(ALPHABET[(n >> (5 * i)) & 31] for i in range(25, -1, -1))
    cs = rk_checksum_bits(rs)
    symbols = body + ALPHABET[cs >> 5] + ALPHABET[cs & 31]
    return '-'.join(symbols[i:i + 4] for i in range(0, 28, 4))


def rk_decode(text):
    """§8 decoding. Raises `rk_invalid` (wrong characters or length) or `rk_checksum` (a typo)."""
    s = text.upper().replace(' ', '').replace('-', '')
    s = s.replace('O', '0').replace('I', '1').replace('L', '1')
    if len(s) != 28 or any(ch not in ALPHABET for ch in s) or s[0] not in '01234567':
        raise VaultError('rk_invalid')
    n = 0
    for ch in s[:26]:
        n = n << 5 | ALPHABET.index(ch)
    rs = n.to_bytes(16, 'big')
    cs = ALPHABET.index(s[26]) << 5 | ALPHABET.index(s[27])
    if cs != rk_checksum_bits(rs):
        raise VaultError('rk_checksum')
    return rs


# ---------- §9 Backup ----------

MAGIC = bytes.fromhex('564c54424b000100')
MAX_FILE = 64 * 1024 * 1024
MAX_HEADER = 4096


def rs_id(rs):
    """§9.2 (as written; reviewer question Q5 proposes salting it with salt_exp)"""
    return hkdf(rs, b'', L_RS_ID, 8)


def backup_header(kdf, rsid):
    """vault-format §10.2"""
    return cbor.encode({1: 1, 2: {1: 1, 2: kdf['m'], 3: kdf['t'], 4: kdf['p'], 5: kdf['salt']}, 3: rsid, 4: 1})


def backup_export(payload, password, rs, kdf, fk, nonce_wrap, nonce_payload):
    """§9.3 and vault-format §10.1. Returns (file_bytes, intermediates)."""
    check_kdf_params(kdf['m'], kdf['t'], kdf['p'])
    header = backup_header(kdf, rs_id(rs))
    h_exp = argon2id(encode_password(password), kdf['salt'], kdf['m'], kdf['t'], kdf['p'])
    kek = hkdf(h_exp + rs, kdf['salt'], L_KEK_EXPORT, 32)
    ad_wrap = L_EXPORT_WRAP + MAGIC + u32(len(header)) + header
    ad_payload = L_EXPORT_PAYLOAD + MAGIC + u32(len(header)) + header
    wrapped_fk = seal(kek, nonce_wrap, fk, ad_wrap)
    payload_ct = seal(fk, nonce_payload, pad(cbor.encode(payload), BACKUP_BUCKET), ad_payload)
    data = MAGIC + u32(len(header)) + header + nonce_wrap + wrapped_fk + nonce_payload + payload_ct
    return data, {'header': header, 'rs_id': rs_id(rs), 'h_exp': h_exp, 'kek_exp': kek,
                  'ad_wrap': ad_wrap, 'ad_payload': ad_payload}


def _validate_backup_header(h):
    if not isinstance(h, dict) or set(h) != {1, 2, 3, 4}:
        raise VaultError('malformed_header')
    if h[1] != 1:
        raise VaultError('unsupported_version')
    k = h[2]
    if (not isinstance(k, dict) or set(k) != {1, 2, 3, 4, 5} or k[1] != 1
            or not all(isinstance(k[i], int) and not isinstance(k[i], bool) for i in (2, 3, 4))
            or not isinstance(k[5], bytes) or len(k[5]) != 16
            or not isinstance(h[3], bytes) or len(h[3]) != 8 or h[4] != 1):
        raise VaultError('malformed_header')


def backup_import(data, password, rs, total_ram=None):
    """§9.4 import order; each step must pass before the next. Returns the payload."""
    if len(data) > MAX_FILE:                                                  # 1
        raise VaultError('too_large')
    if len(data) < 12:
        raise VaultError('truncated')
    if data[:8] != MAGIC:                                                     # 2
        raise VaultError('not_a_backup')
    hdr_len = int.from_bytes(data[8:12], 'big')
    if hdr_len > MAX_HEADER:                                                  # 3
        raise VaultError('too_large')
    if len(data) < 12 + hdr_len + 24 + 48 + 24 + 16:
        raise VaultError('truncated')
    header = data[12:12 + hdr_len]
    try:
        h = cbor.decode(header, max_depth=4, max_entries=16)
    except VaultError:
        raise VaultError('malformed_header') from None
    _validate_backup_header(h)
    k = h[2]
    check_kdf_params(k[2], k[3], k[4])                                        # 4
    if total_ram is not None and k[2] * 1024 > min(512 * 1024 * 1024, total_ram // 4):
        raise VaultError('insufficient_memory')                              # Q8 (proposed)
    if not ct_eq(h[3], rs_id(rs)):                                            # 5
        raise VaultError('wrong_recovery_key')
    pos = 12 + hdr_len
    nonce_wrap, wrapped_fk = data[pos:pos + 24], data[pos + 24:pos + 72]
    nonce_payload, payload_ct = data[pos + 72:pos + 96], data[pos + 96:]
    h_exp = argon2id(encode_password(password), k[5], k[2], k[3], k[4])      # 6
    kek = hkdf(h_exp + rs, k[5], L_KEK_EXPORT, 32)
    fk = open_(kek, nonce_wrap, wrapped_fk, L_EXPORT_WRAP + MAGIC + u32(hdr_len) + header)
    plain = open_(fk, nonce_payload, payload_ct, L_EXPORT_PAYLOAD + MAGIC + u32(hdr_len) + header)  # 7
    payload = cbor.decode(unpad(plain, BACKUP_BUCKET), max_entries=10000)
    if not isinstance(payload, dict) or set(payload) != {1, 2, 3} or payload[1] != 1:  # 8 (partial)
        raise VaultError('schema_violation')
    return payload
