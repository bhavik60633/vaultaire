"""Tests for the reference implementation. Run from tools/vectorref: python -m unittest -v"""
import json
import pathlib
import shutil
import tempfile
import unittest
from unittest import mock

import generate
from vectorref import cbor, core, prims
from vectorref.errors import VaultError

VECTORS = pathlib.Path(__file__).resolve().parents[2] / 'tests' / 'vectors' / 'v1'


def load(name):
    return json.loads((VECTORS / name).read_text(encoding='utf-8'))


def h(s):
    return bytes.fromhex(s)


class PublishedVectors(unittest.TestCase):
    """The primitives must reproduce the published known-answer vectors (crypto spec §12.1)."""

    def test_hkdf_rfc5869(self):
        for v in load('kat/hkdf-sha256.json')['vectors']:
            with self.subTest(v['id']):  # A.3 uses a zero-length salt, like the spec's zeros(0)
                self.assertEqual(prims.hkdf(h(v['ikm']), h(v['salt']), h(v['info']), v['length']), h(v['okm']))

    def test_hmac_rfc4231(self):
        for v in load('kat/hmac-sha256.json')['vectors']:
            with self.subTest(v['id']):
                self.assertEqual(prims.hmac_sha256(h(v['key']), h(v['data'])), h(v['hmac_sha256']))

    def test_argon2id_rfc9106(self):
        v = load('kat/argon2id.json')['vectors'][0]
        tag = prims.argon2id_full(h(v['password']), h(v['salt']), h(v['secret']), h(v['ad']),
                                  v['memory_kib'], v['passes'], v['lanes'], 32)
        self.assertEqual(tag, h(v['tag']))

    def test_xchacha20poly1305_draft(self):
        v = load('kat/xchacha20-poly1305.json')['vectors'][0]
        sealed = prims.seal(h(v['key']), h(v['nonce']), h(v['plaintext']), h(v['aad']))
        self.assertEqual(sealed, h(v['ciphertext']) + h(v['tag']))
        self.assertEqual(prims.open_(h(v['key']), h(v['nonce']), sealed, h(v['aad'])), h(v['plaintext']))
        with self.assertRaises(VaultError):
            prims.open_(h(v['key']), h(v['nonce']), sealed[:-1] + bytes([sealed[-1] ^ 1]), h(v['aad']))


class Reproducible(unittest.TestCase):
    def test_generate_writes_identical_files(self):
        tmp = pathlib.Path(tempfile.mkdtemp())
        try:
            with mock.patch.object(generate, 'OUT', tmp):
                generate.main()
            for f in tmp.glob('*.json'):
                with self.subTest(f.name):
                    self.assertEqual(f.read_bytes(), (VECTORS / f.name).read_bytes(),
                                     'committed vectors differ from a fresh generation')
            # No file the generator did not write may sit in the vectors directory: gitleaks exempts
            # it, so this is what stops a real secret from hiding there.
            self.assertEqual(sorted(p.name for p in VECTORS.glob('*.json')), sorted(p.name for p in tmp.glob('*.json')))
            expected_kat = {'hkdf-sha256.json', 'hmac-sha256.json', 'argon2id.json', 'xchacha20-poly1305.json'}
            self.assertEqual({p.name for p in (VECTORS / 'kat').iterdir()}, expected_kat)
            self.assertEqual({p.name for p in VECTORS.iterdir() if p.is_dir()}, {'kat'})
        finally:
            shutil.rmtree(tmp)


class LabelsAreUsed(unittest.TestCase):
    """Test-vector plan §8: changing one byte of any label must change the outputs that use it."""

    def outputs(self):
        vid, vk, rs = b'\x01' * 16, b'\x02' * 32, b'\x03' * 16
        keys = core.subkeys(vk, vid)
        return {
            'kek_bio': core.kek_bio(b'\x04' * 32, vid),
            'ad_slot': core.ad_slot(vid, 1, b'\x05' * 16, b'params'),
            'subkeys': b''.join(keys.values()),
            'ad_record': core.ad_record(vid, 1, b'\x06' * 16, bytes(16), 1),
            'header_mac': core.header_mac(keys['k_hdr'], b'hdr', b'tbl'),
            'rs_id': core.rs_id(rs),
            'rk': core.rk_encode(rs).encode(),
        }

    def test_each_label_changes_something(self):
        # Argon2id-based labels (hw-input, kek/pwd, kek/rec, kek/export, export/*) are covered by the
        # slot and backup vectors; these are the cheap ones.
        cheap = ['L_KEK_BIO', 'L_SLOT', 'L_SUB_INDEX', 'L_SUB_SECRET', 'L_SUB_META', 'L_SUB_HEADER',
                 'L_RECORD', 'L_HEADER_MAC', 'L_RS_ID', 'L_RK_CHECKSUM']
        base = self.outputs()
        for name in cheap:
            with self.subTest(name):
                label = getattr(core, name)
                with mock.patch.object(core, name, label[:-1] + bytes([label[-1] ^ 1])):
                    changed = self.outputs()
                self.assertNotEqual(base, changed, f'{name} does not affect any output')


class RoundTrips(unittest.TestCase):
    def test_recovery_key(self):
        for rs in (bytes(16), b'\xff' * 16, bytes(range(16))):
            self.assertEqual(core.rk_decode(core.rk_encode(rs)), rs)

    def test_cbor_rejects_non_canonical_everywhere(self):
        for v in load('cbor.json')['vectors']:
            with self.subTest(v['id']):
                if v['expect'] == 'ok':
                    self.assertEqual(cbor.encode(cbor.decode(h(v['output']['cbor']))), h(v['output']['cbor']))
                else:
                    with self.assertRaises(VaultError) as e:
                        cbor.decode(h(v['inputs']['cbor']))
                    self.assertEqual(f'error:{e.exception.code}', v['expect'])


if __name__ == '__main__':
    unittest.main()
