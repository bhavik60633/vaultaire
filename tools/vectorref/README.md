# vectorref: independent reference implementation

Dev-only test tooling, **never part of the app**. It implements the constructions of [`docs/crypto-spec.md`](../../docs/crypto-spec.md) and [`docs/vault-format.md`](../../docs/vault-format.md) from the spec text alone, and writes the test vectors in [`tests/vectors/v1/`](../../tests/vectors/v1/) that the Rust core must reproduce byte for byte ([test-vector plan](../../docs/test-vector-plan.md)).

It deliberately uses **different libraries** from the Rust core: libsodium (PyNaCl) for XChaCha20-Poly1305, the reference C Argon2 (argon2-cffi), OpenSSL (`cryptography`, `hashlib`) for HKDF, HMAC and SHA-256, and a hand-written encoder and decoder for the vault's CBOR subset.

## Run

```sh
cd tools/vectorref
python -m venv .venv
.venv/bin/python -m pip install --require-hashes -r requirements.txt   # Windows: .venv\Scripts\python
.venv/bin/python -m unittest -v        # published vectors, reproducibility, label use, round trips
.venv/bin/python generate.py           # rewrites tests/vectors/v1/*.json (identical output every run)
.venv/bin/python extract_kat.py        # re-extracts tests/vectors/v1/kat/ from the official RFC texts
```

## Files

| File | Purpose |
|---|---|
| `vectorref/prims.py` | The primitives, each a thin call into an independent library |
| `vectorref/cbor.py` | The CBOR profile (vault-format §2): deterministic encoder, strict decoder, canonical check |
| `vectorref/core.py` | Every construction, citing its spec section: keyslots, subkeys, padding, record AD, header MAC, Recovery Key, backup export and import |
| `vectorref/errors.py` | Error names (test-vector plan §5.1) |
| `generate.py` | Writes the composition vectors from fixed seeds |
| `extract_kat.py` | Parses the published vectors out of RFC 5869, RFC 4231, RFC 9106 and draft-irtf-cfrg-xchacha-03 |
| `test_vectorref.py` | Checks the primitives against the published vectors, the reproducibility of the files, that every label is used, and round trips |
| `requirements.txt` | Pinned with hashes (`pip-compile --generate-hashes` from `requirements.in`) |

## Honest limit on independence

The plan's defence works best when the spec, the reference and the product are written by different people. Here, the same developer (with AI assistance) writes all three. The reference still catches composition bugs in the Rust code, because it is written separately and uses different libraries. It **cannot** catch a mistake in the spec itself that both implementations faithfully copy. That is why the independent reviewer (G0) reads the spec, the vectors and [the findings](../../docs/spec-findings-vectorref.md).
