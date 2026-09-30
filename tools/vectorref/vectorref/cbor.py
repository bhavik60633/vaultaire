"""The vault's CBOR subset (vault-format §2): encoder and strict decoder, written from the spec.

Allowed: unsigned integers, byte strings, UTF-8 text, arrays, maps with unsigned-integer keys,
true and false. Everything else is rejected, as are non-shortest forms, indefinite lengths,
unsorted or duplicate map keys and trailing bytes.
"""
from .errors import VaultError

MT_UINT, MT_BYTES, MT_TEXT, MT_ARRAY, MT_MAP, MT_SIMPLE = 0, 2, 3, 4, 5, 7


def _head(major: int, n: int) -> bytes:
    if n < 24:
        return bytes([major << 5 | n])
    for ai, size in ((24, 1), (25, 2), (26, 4), (27, 8)):
        if n < 1 << (8 * size):
            return bytes([major << 5 | ai]) + n.to_bytes(size, 'big')
    raise ValueError('integer too large for CBOR')


def encode(value) -> bytes:
    if value is True:
        return b'\xf5'
    if value is False:
        return b'\xf4'
    if isinstance(value, int):
        if value < 0:
            raise ValueError('negative integers are not allowed')
        return _head(MT_UINT, value)
    if isinstance(value, (bytes, bytearray)):
        return _head(MT_BYTES, len(value)) + bytes(value)
    if isinstance(value, str):
        data = value.encode('utf-8')
        return _head(MT_TEXT, len(data)) + data
    if isinstance(value, (list, tuple)):
        return _head(MT_ARRAY, len(value)) + b''.join(encode(v) for v in value)
    if isinstance(value, dict):
        items = []
        for k, v in value.items():
            if not isinstance(k, int) or isinstance(k, bool) or k < 0:
                raise ValueError('map keys must be unsigned integers')
            items.append((encode(k), encode(v)))
        items.sort(key=lambda kv: kv[0])  # bytewise order of the encoded keys (RFC 8949 §4.2.1)
        return _head(MT_MAP, len(items)) + b''.join(k + v for k, v in items)
    raise ValueError(f'type not allowed in the vault CBOR profile: {type(value).__name__}')


class _Reader:
    def __init__(self, data: bytes, max_depth: int, max_entries: int, max_string: int):
        self.data, self.pos = data, 0
        self.max_depth, self.max_entries, self.max_string = max_depth, max_entries, max_string

    def _take(self, n: int) -> bytes:
        if self.pos + n > len(self.data):
            raise VaultError('malformed')
        chunk = self.data[self.pos:self.pos + n]
        self.pos += n
        return chunk

    def _arg(self, ai: int) -> int:
        if ai < 24:
            return ai
        sizes = {24: 1, 25: 2, 26: 4, 27: 8}
        if ai not in sizes:  # 28-30 reserved, 31 indefinite length
            raise VaultError('malformed')
        n = int.from_bytes(self._take(sizes[ai]), 'big')
        if _head(0, n)[1:] != n.to_bytes(sizes[ai], 'big') or n < 24 and ai >= 24:
            raise VaultError('non_canonical')  # not the shortest form
        return n

    def item(self, depth: int):
        """`depth` is the number of arrays and maps that enclose this item. Scalars do not count
        (proposed definition, spec finding S-3), so a limit of N allows N nested containers."""
        ib = self._take(1)[0]
        major, ai = ib >> 5, ib & 0x1f
        if major == MT_SIMPLE:
            if ib == 0xf5:
                return True
            if ib == 0xf4:
                return False
            raise VaultError('malformed')  # null, undefined, floats, other simple values
        if major not in (MT_UINT, MT_BYTES, MT_TEXT, MT_ARRAY, MT_MAP):
            raise VaultError('malformed')  # negative integers (1) and tags (6)
        n = self._arg(ai)
        if major == MT_UINT:
            return n
        if major in (MT_BYTES, MT_TEXT):
            if n > self.max_string:
                raise VaultError('too_large')
            raw = self._take(n)
            if major == MT_BYTES:
                return raw
            try:
                return raw.decode('utf-8')
            except UnicodeDecodeError:
                raise VaultError('malformed') from None
        if depth + 1 > self.max_depth:
            raise VaultError('malformed')  # too deeply nested
        if n > self.max_entries:
            raise VaultError('too_large')
        if major == MT_ARRAY:
            return [self.item(depth + 1) for _ in range(n)]
        out, last = {}, None
        for _ in range(n):
            start = self.pos
            key = self.item(depth + 1)
            key_bytes = self.data[start:self.pos]
            if not isinstance(key, int) or isinstance(key, bool):
                raise VaultError('malformed')
            if last is not None and key_bytes <= last:
                raise VaultError('non_canonical')  # unsorted or duplicate keys
            last = key_bytes
            out[key] = self.item(depth + 1)
        return out


def decode(data: bytes, max_depth: int = 6, max_entries: int = 1000, max_string: int = 65536):
    r = _Reader(data, max_depth, max_entries, max_string)
    value = r.item(0)
    if r.pos != len(data):
        raise VaultError('malformed')  # trailing bytes
    if encode(value) != data:  # canonical check (ADR-006)
        raise VaultError('non_canonical')
    return value
