"""Generate the composition vectors (test-vector plan §5–§7) into tests/vectors/v1/.

All "random" inputs are derived from fixed seeds, so every run writes identical files.
Usage: python generate.py
"""
import hashlib
import json
import pathlib
import sys

from vectorref import cbor, core
from vectorref.errors import VaultError
from vectorref.prims import encode_password, hmac_sha256, seal

OUT = pathlib.Path(__file__).resolve().parents[2] / 'tests' / 'vectors' / 'v1'
DEVICE = {'m': 65536, 't': 3, 'p': 1}
EXPORT = {'m': 131072, 't': 4, 'p': 1}


def rnd(label, n):
    """Deterministic stand-in for CSPRNG output: SHA-256 blocks of a fixed label."""
    out, i = b'', 0
    while len(out) < n:
        out += hashlib.sha256(f'vectorref/{label}/{i}'.encode()).digest()
        i += 1
    return out[:n]


def hx(value):
    """Hex-encode bytes recursively for JSON."""
    if isinstance(value, (bytes, bytearray)):
        return bytes(value).hex()
    if isinstance(value, dict):
        return {k: hx(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [hx(v) for v in value]
    return value


def expect_error(fn):
    try:
        fn()
    except VaultError as e:
        return f'error:{e.code}'
    return 'ok'


BASE_NOTE = ('A vector with "base" takes every input from the named vector and overrides only the inputs it '
             'lists; its "expect" is the result of that one change.')


def write(name, construction, spec, vectors, notes=None):
    doc = {'construction': construction, 'spec': spec, 'format_version': 1}
    notes = list(notes or [])
    if any('base' in v for v in vectors):
        notes.insert(0, BASE_NOTE)
    if notes:
        doc['notes'] = notes
    doc['vectors'] = vectors
    path = OUT / f'{name}.json'
    path.write_text(json.dumps(hx(doc), indent=2, ensure_ascii=True) + '\n', encoding='utf-8', newline='\n')
    print(f'{path.name}: {len(vectors)} vectors')


VAULT_ID = rnd('vault_id', 16)
VK = rnd('vk', 32)
HW_KEY = rnd('k_pwd stand-in', 32)  # software stand-in for the Keystore HMAC key (crypto spec §12.2)
PASSWORD = 'correct horse battery staple'


def hw_hmac(hw_in):
    return hmac_sha256(HW_KEY, hw_in)


def gen_labels():
    write('labels', 'labels', 'crypto-spec §3.3',
          [{'id': name[2:].lower(), 'ascii': value.decode(), 'bytes': value} for name, value in core.LABELS.items()])


def gen_passwords():
    cases = [
        ('ascii', PASSWORD),
        ('nfc-input', 'café au lait 1234'),
        ('nfd-input-normalises-to-nfc', 'café au lait 1234'),
        ('emoji', 'vault \U0001f510 key \U0001f5dd 2026'),
        ('right-to-left', 'סיסמה חזקה 42'),
        ('spaces-kept', '  leading and trailing  '),
        ('max-1024-bytes', 'a' * 1024),
        ('too-long-1025-bytes', 'a' * 1025),
        ('empty', ''),
    ]
    vectors = []
    for cid, pw in cases:
        v = {'id': cid, 'input_utf8': pw.encode('utf-8')}
        try:
            v['output'] = encode_password(pw)
            v['expect'] = 'ok'
        except VaultError as e:
            v['expect'] = f'error:{e.code}'
        vectors.append(v)
    write('password-normalisation', 'password-encoding', 'crypto-spec §3.2', vectors)


def slot_row(slot_type, slot_id, params, nonce, wrapped):
    return {'slot_id': slot_id, 'slot_type': slot_type, 'params': params, 'nonce': nonce, 'wrapped_vk': wrapped}


def gen_slots():
    rows = []
    # KS-BIO
    sk, slot_id, nonce = rnd('sk', 32), rnd('slot bio', 16), rnd('nonce slot bio', 24)
    params = core.keyslot_params(core.SLOT_BIO, hw_level=1, alias=core.keystore_alias('bio', slot_id),
                                 iv=rnd('keystore iv', 12), bio_blob=rnd('bio_blob stand-in', 48))
    kek = core.kek_bio(sk, VAULT_ID)
    ad = core.ad_slot(VAULT_ID, core.SLOT_BIO, slot_id, params)
    wrapped = core.wrap_vk(kek, nonce, VK, ad)
    rows.append(slot_row(core.SLOT_BIO, slot_id, params, nonce, wrapped))
    write('slot-bio', 'slot/bio', 'crypto-spec §4.2, §4.5; vault-format §4.4', [{
        'id': 'bio-001', 'comment': 'SK stands in for the value Keystore releases after a biometric',
        'inputs': {'sk': sk, 'vault_id': VAULT_ID, 'slot_id': slot_id, 'vk': VK, 'nonce': nonce},
        'intermediates': {'params_cbor': params, 'kek_bio': kek, 'ad_slot': ad},
        'output': {'wrapped_vk': wrapped}, 'expect': 'ok'},
        {'id': 'bio-002-wrong-sk', 'base': 'bio-001', 'inputs': {'sk': rnd('other sk', 32), 'vault_id': VAULT_ID, 'slot_id': slot_id,
                                              'params_cbor': params, 'nonce': nonce, 'wrapped_vk': wrapped},
         'expect': expect_error(lambda: core.unwrap_vk(core.kek_bio(rnd('other sk', 32), VAULT_ID), nonce, wrapped, ad))},
    ])

    # KS-PWD
    salt, slot_id, nonce = rnd('salt_pwd', 16), rnd('slot pwd', 16), rnd('nonce slot pwd', 24)
    kdf = dict(DEVICE, salt=salt)
    params = core.keyslot_params(core.SLOT_PWD, hw_level=1, alias=core.keystore_alias('pwd', slot_id), kdf=kdf)
    kek, mid = core.kek_pwd(PASSWORD, salt, VAULT_ID, DEVICE['m'], DEVICE['t'], DEVICE['p'], hw_hmac)
    ad = core.ad_slot(VAULT_ID, core.SLOT_PWD, slot_id, params)
    wrapped = core.wrap_vk(kek, nonce, VK, ad)
    rows.append(slot_row(core.SLOT_PWD, slot_id, params, nonce, wrapped))
    kek_wrong, _ = core.kek_pwd(PASSWORD + '!', salt, VAULT_ID, DEVICE['m'], DEVICE['t'], DEVICE['p'], hw_hmac)
    raised = core.keyslot_params(core.SLOT_PWD, hw_level=1, alias=core.keystore_alias('pwd', slot_id),
                                 kdf=dict(DEVICE, m=131072, salt=salt))
    write('slot-pwd', 'slot/pwd', 'crypto-spec §4.3, §4.5; vault-format §4.4', [{
        'id': 'pwd-001', 'comment': 'hw_hmac_key stands in for K_pwd inside Keystore',
        'inputs': {'password_utf8': PASSWORD.encode(), 'salt_pwd': salt, 'vault_id': VAULT_ID, 'argon2': DEVICE,
                   'hw_hmac_key': HW_KEY, 'slot_id': slot_id, 'vk': VK, 'nonce': nonce},
        'intermediates': dict(mid, params_cbor=params, kek_pwd=kek, ad_slot=ad),
        'output': {'wrapped_vk': wrapped}, 'expect': 'ok'},
        {'id': 'pwd-002-wrong-password', 'base': 'pwd-001', 'inputs': {'password_utf8': (PASSWORD + '!').encode()},
         'expect': expect_error(lambda: core.unwrap_vk(kek_wrong, nonce, wrapped, ad))},
        {'id': 'pwd-003-params-changed-within-range',
         'comment': 'Stored m raised to 131072: still in range, but the slot AD no longer matches',
         'base': 'pwd-001', 'inputs': {'params_cbor': raised},
         'expect': expect_error(lambda: core.unwrap_vk(kek, nonce, wrapped, core.ad_slot(VAULT_ID, core.SLOT_PWD, slot_id, raised)))},
        {'id': 'pwd-004-slot-type-swapped', 'base': 'pwd-001', 'comment': 'The same wrap presented as a REC slot', 'inputs': {'slot_type': core.SLOT_REC},
         'expect': expect_error(lambda: core.unwrap_vk(kek, nonce, wrapped, core.ad_slot(VAULT_ID, core.SLOT_REC, slot_id, params)))},
    ])

    # KS-REC
    rs, salt = rnd('rs', 16), rnd('salt_rec', 16)
    slot_id, nonce = rnd('slot rec', 16), rnd('nonce slot rec', 24)
    kdf = dict(DEVICE, salt=salt)
    params = core.keyslot_params(core.SLOT_REC, kdf=kdf)
    kek, mid = core.kek_rec(PASSWORD, salt, rs, VAULT_ID, DEVICE['m'], DEVICE['t'], DEVICE['p'])
    ad = core.ad_slot(VAULT_ID, core.SLOT_REC, slot_id, params)
    wrapped = core.wrap_vk(kek, nonce, VK, ad)
    rows.append(slot_row(core.SLOT_REC, slot_id, params, nonce, wrapped))
    kek_wrong_rs, _ = core.kek_rec(PASSWORD, salt, rnd('other rs', 16), VAULT_ID, DEVICE['m'], DEVICE['t'], DEVICE['p'])
    write('slot-rec', 'slot/rec', 'crypto-spec §4.4, §4.5; vault-format §4.4', [{
        'id': 'rec-001',
        'inputs': {'password_utf8': PASSWORD.encode(), 'salt_rec': salt, 'rs': rs, 'vault_id': VAULT_ID,
                   'argon2': DEVICE, 'slot_id': slot_id, 'vk': VK, 'nonce': nonce},
        'intermediates': dict(mid, params_cbor=params, kek_rec=kek, ad_slot=ad),
        'output': {'wrapped_vk': wrapped}, 'expect': 'ok'},
        {'id': 'rec-002-wrong-recovery-secret', 'base': 'rec-001', 'inputs': {'rs': rnd('other rs', 16)},
         'expect': expect_error(lambda: core.unwrap_vk(kek_wrong_rs, nonce, wrapped, ad))},
    ])
    return rows


def gen_param_range():
    cases = [('floor', 65536, 3, 1), ('ceiling', 524288, 16, 4),
             ('m-below', 65535, 3, 1), ('m-above', 524289, 3, 1),
             ('t-below', 65536, 2, 1), ('t-above', 65536, 17, 1),
             ('p-below', 65536, 3, 0), ('p-above', 65536, 3, 5)]
    write('slot-params-range', 'argon2id-parameter-range', 'crypto-spec §4.6', [
        {'id': cid, 'inputs': {'m': m, 't': t, 'p': p},
         'comment': 'Checked before any Argon2id work',
         'expect': expect_error(lambda m=m, t=t, p=p: core.check_kdf_params(m, t, p))}
        for cid, m, t, p in cases])


def gen_subkeys():
    write('subkeys', 'subkeys', 'crypto-spec §6.1', [{
        'id': 'subkeys-001', 'inputs': {'vk': VK, 'vault_id': VAULT_ID}, 'output': core.subkeys(VK, VAULT_ID), 'expect': 'ok'}])


def gen_padding():
    vectors = []
    for bucket in (64, 256, 4096):
        for n in sorted({0, 1, bucket - 2, bucket - 1, bucket, bucket + 1}):
            data = rnd(f'pad {bucket} {n}', n)
            vectors.append({'id': f'pad-{bucket}-{n}', 'inputs': {'bucket': bucket, 'data': data},
                            'output': core.pad(data, bucket), 'expect': 'ok'})
    bad = [('no-0x80', b'\x00' * 64), ('wrong-marker-0x81', b'abc\x81' + b'\x00' * 60),
           ('marker-then-garbage', b'abc\x80\x01' + b'\x00' * 59), ('not-a-multiple', b'abc\x80')]
    for cid, data in bad:
        vectors.append({'id': f'unpad-{cid}', 'inputs': {'bucket': 64, 'padded': data},
                        'expect': expect_error(lambda d=data: core.unpad(d, 64))})
    write('padding', 'padding', 'crypto-spec §6.5', vectors)


def sample_records():
    """One record of each kind. Empty optional values are OMITTED (proposed rule; see the spec-findings report)."""
    item_id, f_holder, f_number, f_expiry, f_cvv = (rnd(x, 16) for x in ('item card', 'f holder', 'f number', 'f expiry', 'f cvv'))
    index = {1: 2, 2: 1, 3: 1, 4: 'Travel card', 5: 'Visa · •••• 4821', 7: True,
             8: 1790000000, 9: 1790000000, 12: 2, 13: True,
             14: [{1: f_holder, 2: 'card.holder', 3: 'Cardholder', 4: 1, 5: 1, 6: True, 7: 'Alex Morgan'},
                  {1: f_number, 2: 'card.number', 3: 'Card number', 4: 3, 5: 3, 6: True},
                  {1: f_expiry, 2: 'card.expiry', 3: 'Expiry', 4: 4, 5: 1, 6: True, 7: '08/29'},
                  {1: f_cvv, 2: 'card.cvv', 3: 'Security code', 4: 14, 5: 3, 6: True}]}
    # `values` as an array of [field_id, value] pairs sorted by field_id (proposed fix, spec finding S-2):
    # the format allows only unsigned-integer map keys, so a map keyed by field ids is not encodable.
    secret = {1: 1, 2: sorted([[f_number, '4929184577204821'], [f_cvv, '317']], key=lambda kv: kv[0])}
    history_id = rnd('history 1', 16)
    history = {1: f_number, 2: '4929184577209999', 3: 1780000000}
    settings = {1: 120, 2: 30, 3: 60, 4: 60, 5: 14, 6: False, 7: False, 8: 1790000000, 10: 30,
                11: [[1, True], [6, True], [3, True], [4, True], [5, True], [7, True], [8, True]]}
    rs = rnd('rs', 16)
    recovery = {1: rs, 2: core.rs_id(rs), 3: 1790000000}
    log = [{1: 1790000000, 2: 1}, {1: 1790000100, 2: 4, 3: 3}]
    manifest = {1: 7, 2: sorted([[core.REC_ITEM, item_id, 1], [core.REC_HISTORY, history_id, 1]], key=lambda e: e[1])}
    return item_id, history_id, [
        ('item-index', core.REC_ITEM, item_id, bytes(16), core.ENV_INDEX, index),
        ('item-secret', core.REC_ITEM, item_id, bytes(16), core.ENV_SECRET, secret),
        ('history', core.REC_HISTORY, history_id, item_id, core.ENV_SECRET, history),
        ('settings', core.REC_SETTINGS, bytes(16), bytes(16), core.ENV_META, settings),
        ('recovery', core.REC_RECOVERY, bytes(16), bytes(16), core.ENV_META, recovery),
        ('security-log', core.REC_LOG, bytes(16), bytes(16), core.ENV_META, log),
        ('manifest', core.REC_MANIFEST, bytes(16), bytes(16), core.ENV_META, manifest),
    ]


def gen_records():
    keys = core.subkeys(VK, VAULT_ID)
    key_for = {core.ENV_INDEX: keys['k_idx'], core.ENV_SECRET: keys['k_sec'], core.ENV_META: keys['k_meta']}
    item_id, _, recs = sample_records()
    vectors = []
    for name, kind, rid, parent, env, rec in recs:
        nonce = rnd(f'nonce {name}', 24)
        ad = core.ad_record(VAULT_ID, kind, rid, parent, env)
        plain = cbor.encode(rec)
        ct = core.seal_record(key_for[env], nonce, rec, env, ad)
        vectors.append({'id': f'record-{name}', 'inputs': {
            'vault_id': VAULT_ID, 'record_kind': kind, 'record_id': rid, 'parent_id': parent,
            'envelope_kind': env, 'key': key_for[env], 'nonce': nonce},
            'intermediates': {'cbor': plain, 'padded': core.pad(plain, core.BUCKET[env]), 'ad_record': ad},
            'output': {'ct': ct}, 'expect': 'ok'})
        if name == 'item-secret':
            other = rnd('item other', 16)
            vectors.append({'id': 'record-secret-moved-to-another-item', 'base': 'record-item-secret', 'inputs': {'record_id': other},
                            'expect': expect_error(lambda: core.open_record(key_for[env], nonce, ct, env,
                                                                            core.ad_record(VAULT_ID, kind, other, parent, env)))})
            vectors.append({'id': 'record-secret-presented-as-index', 'base': 'record-item-secret', 'inputs': {'envelope_kind': core.ENV_INDEX},
                            'expect': expect_error(lambda: core.open_record(key_for[env], nonce, ct, env,
                                                                            core.ad_record(VAULT_ID, kind, rid, parent, core.ENV_INDEX)))})
            # Authentic but non-canonical plaintext: the integer 1 encoded in two bytes (0x18 0x01).
            noncanon = b'\xa2\x01\x18\x01\x02\xa0'
            nn = rnd('nonce noncanonical', 24)
            ct2 = seal(key_for[env], nn, core.pad(noncanon, 256), ad)
            vectors.append({'id': 'record-non-canonical-cbor', 'base': 'record-item-secret', 'inputs': {'nonce': nn, 'ct': ct2, 'cbor': noncanon},
                            'expect': expect_error(lambda: core.open_record(key_for[env], nn, ct2, env, ad))})
    write('records', 'records', 'crypto-spec §6.2-§6.5; vault-format §4.5, §5', vectors,
          notes=['Empty optional values are omitted (proposed rule, spec finding S-1).',
                 'The manifest lists items and history entries only, as the spec is written today (reviewer question Q3 proposes adding settings and recovery).'])


def gen_header_mac(rows):
    keys = core.subkeys(VK, VAULT_ID)
    header = core.vault_header(VAULT_ID)
    table = core.keyslot_table(rows)
    mac = core.header_mac(keys['k_hdr'], header, table)
    vectors = [{'id': 'header-mac-001', 'inputs': {'k_hdr': keys['k_hdr'], 'header_cbor': header, 'keyslot_table_cbor': table},
                'output': {'header_mac': mac}, 'expect': 'ok'}]
    for i in (0, len(header) - 1):
        bad = bytearray(header)
        bad[i] ^= 1
        vectors.append({'id': f'header-mac-flip-header-byte-{i}', 'base': 'header-mac-001', 'inputs': {'header_cbor': bytes(bad)},
                        'output': {'header_mac': core.header_mac(keys['k_hdr'], bytes(bad), table)},
                        'expect': 'error:unlock_failed', 'comment': 'MAC differs from the stored one'})
    without_bio = core.keyslot_table(rows[1:])
    vectors.append({'id': 'header-mac-slot-removed', 'base': 'header-mac-001', 'inputs': {'keyslot_table_cbor': without_bio},
                    'output': {'header_mac': core.header_mac(keys['k_hdr'], header, without_bio)},
                    'expect': 'error:unlock_failed', 'comment': 'KS-BIO row removed'})
    write('header-mac', 'header-mac', 'crypto-spec §7.1; vault-format §4.3', vectors)


def gen_recovery_key():
    vectors = []
    for label in ('rk a', 'rk b'):
        rs = rnd(label, 16)
        vectors.append({'id': f'encode-{label[-1]}', 'inputs': {'rs': rs}, 'output': {'recovery_key': core.rk_encode(rs)},
                        'intermediates': {'checksum_10_bits': core.rk_checksum_bits(rs)}, 'expect': 'ok'})
    for rs in (bytes(16), b'\xff' * 16):
        vectors.append({'id': f'encode-{rs[:1].hex()}', 'inputs': {'rs': rs}, 'output': {'recovery_key': core.rk_encode(rs)}, 'expect': 'ok'})
    rs = rnd('rk a', 16)
    key = core.rk_encode(rs)
    variants = [('exact', key), ('lowercase', key.lower()), ('no-dashes', key.replace('-', '')),
                ('spaces', key.replace('-', ' ')), ('letter-O-for-zero', key.replace('0', 'O')),
                ('letters-I-L-for-one', key.replace('1', 'I', 1).replace('1', 'L'))]
    for cid, text in variants:
        vectors.append({'id': f'decode-{cid}', 'inputs': {'text': text},
                        'output': {'rs': core.rk_decode(text)}, 'expect': 'ok'})
    typo = key[:-1] + ('0' if key[-1] != '0' else '1')
    swapped = key[:5] + key[6] + key[5] + key[7:]
    bad = [('typo-in-checksum', typo), ('swapped-symbols', swapped), ('too-short', key[:-2]),
           ('letter-U', 'U' + key[1:]), ('first-symbol-above-7', '8' + key[1:])]
    for cid, text in bad:
        vectors.append({'id': f'decode-{cid}', 'inputs': {'text': text}, 'expect': expect_error(lambda t=text: core.rk_decode(t))})
    write('recovery-key', 'recovery-key', 'crypto-spec §8', vectors)


def sample_payload():
    _, _, recs = sample_records()
    index, secret, history = recs[0][5], recs[1][5], recs[2][5]
    return {1: 1, 2: [{1: rnd('item card', 16), 2: index, 3: secret, 4: [history]}], 3: recs[3][5]}


def gen_backup():
    rs = rnd('rs', 16)
    kdf = dict(EXPORT, salt=rnd('salt_exp', 16))
    fk, nw, npay = rnd('fk', 32), rnd('nonce wrap', 24), rnd('nonce payload', 24)
    payload = sample_payload()
    data, mid = core.backup_export(payload, PASSWORD, rs, kdf, fk, nw, npay)
    assert core.backup_import(data, PASSWORD, rs) == payload
    vectors = [{'id': 'backup-001', 'inputs': {
        'password_utf8': PASSWORD.encode(), 'rs': rs, 'argon2': {k: kdf[k] for k in ('m', 't', 'p')},
        'salt_exp': kdf['salt'], 'fk': fk, 'nonce_wrap': nw, 'nonce_payload': npay, 'payload_cbor': cbor.encode(payload)},
        'intermediates': mid, 'output': {'file': data}, 'expect': 'ok'}]

    hdr_len = len(mid['header'])
    body = 12 + hdr_len

    def tamper(cid, new, comment, **kw):
        pw, r, ram = kw.get('pw', PASSWORD), kw.get('rs', rs), kw.get('ram')
        vectors.append({'id': f'backup-{cid}', 'comment': comment,
                        'inputs': {'file': new, 'password_utf8': pw.encode(), 'rs': r, 'total_ram': ram},
                        'expect': expect_error(lambda: core.backup_import(new, pw, r, ram))})

    def flip(i):
        b = bytearray(data)
        b[i] ^= 1
        return bytes(b)

    tamper('wrong-magic', flip(0), 'first magic byte changed')
    tamper('header-too-long', data[:8] + (4097).to_bytes(4, 'big') + data[12:], 'hdr_len above 4096')
    bad_v = data[:12] + cbor.encode({1: 2, 2: cbor.decode(mid['header'], 4, 16)[2], 3: mid['rs_id'], 4: 1}) + data[body:]
    tamper('unknown-version', bad_v, 'header v = 2 (hdr_len unchanged: same encoded length)')
    low = cbor.decode(mid['header'], 4, 16)
    low[2][2] = 65535
    low_bytes = cbor.encode(low)
    tamper('params-out-of-range', data[:8] + len(low_bytes).to_bytes(4, 'big') + low_bytes + data[body:], 'm = 65535, below the floor')
    tamper('insufficient-memory', data, 'm = 128 MiB on a 400 MiB device (proposed Q8 cap: 25% of RAM)', ram=400 * 1024 * 1024)
    tamper('wrong-recovery-key', data, 'rs_id does not match the typed Recovery Key', rs=rnd('other rs', 16))
    tamper('wrong-password', data, 'same file, wrong password', pw=PASSWORD + '!')
    tamper('flip-in-header-salt', flip(12 + hdr_len - 20), 'bit flipped inside salt_exp: header still parses')
    tamper('flip-in-wrapped-fk', flip(body + 30), 'bit flipped inside wrapped_FK')
    tamper('flip-in-payload', flip(len(data) - 100), 'bit flipped inside payload_ct')
    tamper('truncated-in-header', data[:12 + hdr_len // 2], 'cut inside the header')
    tamper('truncated-payload', data[:-10], 'last 10 bytes cut')
    other, _ = core.backup_export(payload, PASSWORD, rs, kdf, rnd('fk2', 32), nw, npay)
    swapped = data[:body + 72] + other[body + 72:]
    tamper('payload-from-another-backup', swapped, 'wrapped_FK of one file with the payload of another')
    write('backup', 'backup', 'crypto-spec §9; vault-format §10', vectors)


def gen_cbor():
    good = [('uint-0', 0), ('uint-23', 23), ('uint-24', 24), ('uint-255', 255), ('uint-256', 256),
            ('uint-65536', 65536), ('uint-2^32', 1 << 32), ('bytes-empty', b''), ('text-utf8', 'café'),
            ('bool-true', True), ('bool-false', False), ('array', [1, [2, 3]]),
            ('map-sorted-keys', {10: 1, 2: 2, 1: 3, 24: 4})]
    vectors = [{'id': cid, 'expect': 'ok', 'output': {'cbor': cbor.encode(v)}} for cid, v in good]
    bad = [('uint-not-shortest', '1801'), ('length-not-shortest', '5800'), ('indefinite-array', '9f01ff'),
           ('negative-int', '20'), ('float', 'fa3fc00000'), ('tag', 'c101'), ('null', 'f6'),
           ('map-unsorted-keys', 'a202010101'), ('map-duplicate-keys', 'a201010102'), ('map-text-key', 'a1616101'),
           ('trailing-bytes', '0101'), ('invalid-utf8', '62c328')]
    for cid, h in bad:
        vectors.append({'id': cid, 'inputs': {'cbor': bytes.fromhex(h)},
                        'expect': expect_error(lambda h=h: cbor.decode(bytes.fromhex(h)))})
    write('cbor', 'cbor-profile', 'vault-format §2', vectors)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    gen_labels()
    gen_passwords()
    rows = gen_slots()
    gen_param_range()
    gen_subkeys()
    gen_padding()
    gen_records()
    gen_header_mac(rows)
    gen_recovery_key()
    gen_backup()
    gen_cbor()


if __name__ == '__main__':
    sys.exit(main())
