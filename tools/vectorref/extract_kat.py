"""Extract the published known-answer vectors (crypto spec §12.1) from the official documents.

Parses the RFC texts instead of copying hex by hand, and records each document's URL and SHA-256
so the provenance can be checked. Writes tests/vectors/v1/kat/*.json.

Usage: python extract_kat.py [--offline DIR]   (DIR holds previously downloaded copies)
"""
import hashlib
import json
import pathlib
import re
import sys
import urllib.request

SOURCES = {
    'rfc5869': 'https://www.rfc-editor.org/rfc/rfc5869.txt',
    'rfc4231': 'https://www.rfc-editor.org/rfc/rfc4231.txt',
    'rfc9106': 'https://www.rfc-editor.org/rfc/rfc9106.txt',
    'xchacha': 'https://www.ietf.org/archive/id/draft-irtf-cfrg-xchacha-03.txt',
}
OUT = pathlib.Path(__file__).resolve().parents[2] / 'tests' / 'vectors' / 'v1' / 'kat'


def fetch(name, offline):
    if offline:
        return (pathlib.Path(offline) / f'{name}.txt').read_bytes()
    with urllib.request.urlopen(SOURCES[name], timeout=60) as r:
        return r.read()


def strip_pages(text):
    """Remove RFC page headers and footers, which can interrupt a hex block."""
    return re.sub(r'\n[^\n]*\[Page \d+\]\n\f?\n?[^\n]*\n', '\n', text)


def hex_after(block, name, sep):
    """Collect the hex that follows `name<sep>` up to the next field or blank line."""
    # The separator is optional: RFC 4231 test case 3 prints "Key" with no "=".
    m = re.search(re.escape(name) + r'\s*(?:' + re.escape(sep) + r')?\s*(.*?)(?:\n\s*\n|\n\s*[A-Za-z][\w -]*\s*[=:]|\Z)', block, re.S)
    if not m:
        raise ValueError(f'{name} not found')
    raw = re.sub(r'\([^)]*\)', ' ', m.group(1))  # drop annotations such as (20 bytes) or ("Hi There")
    raw = raw.replace('0x', ' ')
    return ''.join(re.findall(r'\b[0-9a-f]+\b', raw.lower()))


def hkdf_cases(text):
    cases = []
    for num in (1, 2, 3):
        block = re.split(r'\nA\.\d', text.split(f'A.{num}.  Test Case {num}')[-1])[0]
        cases.append({
            'id': f'rfc5869-a{num}',
            'ikm': hex_after(block, 'IKM', '='),
            'salt': '' if 'salt = (0 octets)' in block else hex_after(block, 'salt', '='),
            'info': '' if 'info = (0 octets)' in block else hex_after(block, 'info', '='),
            'length': int(re.search(r'L\s*=\s*(\d+)', block).group(1)),
            'prk': hex_after(block, 'PRK', '='),
            'okm': hex_after(block, 'OKM', '='),
        })
    return cases


def hmac_cases(text):
    cases = []
    for num in (1, 2, 3, 4, 6, 7):  # case 5 truncates the output, which the vault never does
        block = text.split(f'4.{num + 1}.  Test Case {num}')[-1].split('\n4.')[0]
        cases.append({
            'id': f'rfc4231-case{num}',
            'key': hex_after(block, 'Key', '='),
            'data': hex_after(block, 'Data', '='),
            'hmac_sha256': hex_after(block, 'HMAC-SHA-256', '='),
        })
    return cases


def argon2id_case(text):
    block = text.split('5.3.  Argon2id Test Vectors')[-1]

    def hexbytes(name):
        m = re.search(re.escape(name) + r'\[\d+\]:\s*((?:[0-9a-f]{2}\s+)+)', block)
        return ''.join(m.group(1).split())

    tag = re.search(r'Tag:\s*((?:[0-9a-f]{2}\s+)+)', block).group(1)
    return [{
        'id': 'rfc9106-5.3',
        'memory_kib': int(re.search(r'Memory: (\d+) KiB', block).group(1)),
        'passes': int(re.search(r'Passes: (\d+)', block).group(1)),
        'lanes': int(re.search(r'Parallelism: (\d+) lanes', block).group(1)),
        'password': hexbytes('Password'), 'salt': hexbytes('Salt'),
        'secret': hexbytes('Secret'), 'ad': hexbytes('Associated data'),
        'tag': ''.join(tag.split()),
    }]


def xchacha_case(text):
    block = text.split('A.3.1.  AEAD_XCHACHA20_POLY1305')[-1].split('A.3.2.')[0]
    return [{
        'id': 'xchacha-03-a.3.1',
        'plaintext': hex_after(block, 'Plaintext', ':'),
        'aad': hex_after(block, 'AAD', ':'),
        'key': hex_after(block, 'Key', ':'),
        'nonce': hex_after(block, 'IV', ':'),
        'ciphertext': hex_after(block, 'Ciphertext', ':'),
        'tag': hex_after(block, 'Tag', ':'),
    }]


def main(argv):
    offline = argv[argv.index('--offline') + 1] if '--offline' in argv else None
    OUT.mkdir(parents=True, exist_ok=True)
    jobs = [('rfc5869', 'hkdf-sha256', 'RFC 5869 appendix A.1-A.3', hkdf_cases),
            ('rfc4231', 'hmac-sha256', 'RFC 4231 test cases 1-4, 6, 7', hmac_cases),
            ('rfc9106', 'argon2id', 'RFC 9106 section 5.3', argon2id_case),
            ('xchacha', 'xchacha20-poly1305', 'draft-irtf-cfrg-xchacha-03 appendix A.3.1', xchacha_case)]
    for name, construction, where, parse in jobs:
        raw = fetch(name, offline)
        doc = {'construction': f'kat/{construction}', 'spec': 'crypto-spec §12.1',
               'source': {'document': where, 'url': SOURCES[name], 'sha256': hashlib.sha256(raw).hexdigest()},
               'vectors': parse(strip_pages(raw.decode('ascii', 'replace')))}
        path = OUT / f'{construction}.json'
        path.write_text(json.dumps(doc, indent=2) + '\n', encoding='utf-8', newline='\n')
        print(f'{path.name}: {len(doc["vectors"])} vectors')


if __name__ == '__main__':
    main(sys.argv)
