"""Primitives for the reference implementation (crypto spec §2, §3.1).

Deliberately built on libraries *different* from the Rust core (test-vector plan §3):
libsodium via PyNaCl, the reference Argon2 C code via argon2-cffi, and OpenSSL via
`cryptography` and `hashlib`.
"""
import hashlib
import hmac as _hmac
import unicodedata

import nacl.bindings as sodium
import nacl.exceptions
from argon2.low_level import Type, core, ffi, hash_secret_raw
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.kdf.hkdf import HKDF as _HKDF

from .errors import VaultError


def sha256(data: bytes) -> bytes:
    return hashlib.sha256(data).digest()


def hmac_sha256(key: bytes, msg: bytes) -> bytes:
    return _hmac.new(key, msg, hashlib.sha256).digest()


def hkdf(ikm: bytes, salt: bytes, info: bytes, length: int) -> bytes:
    """HKDF(ikm, salt, info, L) = Expand(Extract(salt, ikm), info, L) with SHA-256 (RFC 5869).

    An empty salt (zeros(0)) is passed through as b"": HMAC pads a short key with zeros,
    so it gives the same PRK as RFC 5869's default of HashLen zero bytes.
    """
    return _HKDF(algorithm=hashes.SHA256(), length=length, salt=salt, info=info).derive(ikm)


def seal(key: bytes, nonce: bytes, plaintext: bytes, ad: bytes) -> bytes:
    """XChaCha20-Poly1305: returns ciphertext ‖ 16-byte tag."""
    assert len(key) == 32 and len(nonce) == 24
    return sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(plaintext, ad, nonce, key)


def open_(key: bytes, nonce: bytes, ciphertext: bytes, ad: bytes, error: str = 'auth_failed') -> bytes:
    """XChaCha20-Poly1305: returns the plaintext only if the tag verifies."""
    try:
        return sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(ciphertext, ad, nonce, key)
    except nacl.exceptions.CryptoError:
        raise VaultError(error) from None


def argon2id(password: bytes, salt: bytes, m_kib: int, t: int, p: int) -> bytes:
    """Argon2id v0x13, 32-byte output, no secret key and no associated data (crypto spec §3.1)."""
    return hash_secret_raw(secret=password, salt=salt, time_cost=t, memory_cost=m_kib,
                           parallelism=p, hash_len=32, type=Type.ID, version=19)


def argon2id_full(password, salt, secret, ad, m_kib, t, p, tag_len):
    """Argon2id with the optional secret and associated data, for the RFC 9106 known-answer test only."""
    bufs = [ffi.new('uint8_t[]', b or b'\0') for b in (password, salt, secret, ad)]
    out = ffi.new('uint8_t[]', tag_len)
    ctx = ffi.new('argon2_context *', dict(
        version=19, out=out, outlen=tag_len,
        pwd=bufs[0], pwdlen=len(password), salt=bufs[1], saltlen=len(salt),
        secret=bufs[2], secretlen=len(secret), ad=bufs[3], adlen=len(ad),
        t_cost=t, m_cost=m_kib, lanes=p, threads=p,
        allocate_cbk=ffi.NULL, free_cbk=ffi.NULL, flags=0))
    rc = core(ctx, Type.ID.value)
    assert rc == 0, f'argon2 core returned {rc}'
    return bytes(ffi.buffer(out, tag_len))


def encode_password(password: str) -> bytes:
    """Crypto spec §3.2: NFC, UTF-8, reject empty or longer than 1024 bytes. Nothing else changes."""
    data = unicodedata.normalize('NFC', password).encode('utf-8')
    if not 1 <= len(data) <= 1024:
        raise VaultError('password_length')
    return data


def ct_eq(a: bytes, b: bytes) -> bool:
    return _hmac.compare_digest(a, b)
