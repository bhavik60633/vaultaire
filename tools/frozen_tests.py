#!/usr/bin/env python3
"""Test-freeze guard: fail the build if a frozen test file changes (product plan §7).

The owner freezes each component's tests before its code is written. Their checksums live in
tests/FROZEN.sha256, one per line in `sha256sum` format:

    <64 lowercase hex>  <path relative to the repository root, with forward slashes>

Lines starting with `#` are comments. Only the owner adds or removes lines; removing one is an
unfreeze and needs a reason in its commit message.

    python tools/frozen_tests.py check                     # CI: exit 1 if any frozen file changed
    python tools/frozen_tests.py freeze tools/test_x.py    # owner: add files to the list

CRLF line endings are read as LF, so a Windows checkout of a frozen file still matches.
Exit codes: 0 all good, 1 problems found (each one printed), 2 usage error.
"""
import hashlib
import os
import re
import sys

MANIFEST = 'tests/FROZEN.sha256'

DEFAULT_HEADER = [
    '# Frozen tests (product plan §7). CI fails if any file below changes.',
    '# Only the owner adds or removes lines here; a removed line is an unfreeze and needs a reason in its commit.',
]

_ENTRY = re.compile(r'^([0-9a-f]{64})  (.+)$')
_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def digest(path):
    """SHA-256 of the file, hex, with CRLF read as LF."""
    with open(path, 'rb') as f:
        data = f.read()
    return hashlib.sha256(data.replace(b'\r\n', b'\n')).hexdigest()


def _valid_path(path):
    """A relative path inside the repository, written with forward slashes and no `.` or `..`."""
    if not path or path.startswith('/') or '\\' in path or ':' in path:
        return False
    return all(part not in ('', '.', '..') for part in path.split('/'))


def _not_inside(path):
    return f'{path} is not a relative path inside the repository'


def _local(root, path):
    return os.path.join(root, *path.split('/'))


def _parse(text):
    """Returns (header lines, [(line number, sha, path)], problems), all in line order."""
    lines = text.replace('\r\n', '\n').split('\n')
    if lines and lines[-1] == '':
        lines.pop()
    header, entries, problems = [], [], []
    seen = set()
    in_header = True
    for number, line in enumerate(lines, start=1):
        if line.startswith('#') or line == '':
            if in_header and line.startswith('#'):
                header.append(line)
            else:
                in_header = False
            continue
        in_header = False
        match = _ENTRY.match(line)
        if not match:
            problems.append((number, f'{MANIFEST} line {number}: malformed'))
            continue
        sha, path = match.groups()
        if not _valid_path(path):
            problems.append((number, f'{MANIFEST} line {number}: {_not_inside(path)}'))
            continue
        if path in seen:
            problems.append((number, f'{MANIFEST} line {number}: {path} listed twice'))
            continue
        seen.add(path)
        entries.append((number, sha, path))
    return header, entries, problems


def _read_manifest(root):
    path = _local(root, MANIFEST)
    if not os.path.isfile(path):
        return None
    with open(path, 'rb') as f:
        return f.read().decode('utf-8')


def check(root):
    """Every problem with the frozen files under `root`, in manifest order; empty when all match."""
    text = _read_manifest(root)
    if text is None:
        return [f'{MANIFEST}: not found']
    _, entries, problems = _parse(text)
    found = list(problems)
    for number, sha, path in entries:
        local = _local(root, path)
        if not os.path.isfile(local):
            found.append((number, f'{path}: missing'))
        elif digest(local) != sha:
            found.append((number, f'{path}: changed since it was frozen'))
    return [message for _, message in sorted(found, key=lambda item: item[0])]


def freeze(root, paths):
    """Adds `paths` to the manifest, all or nothing. Never re-freezes a file whose contents changed."""
    text = _read_manifest(root)
    if text is None:
        header, entries = list(DEFAULT_HEADER), []
    else:
        header, entries, damaged = _parse(text)
        if damaged:
            return [message for _, message in damaged]
    frozen = {path: sha for _, sha, path in entries}

    problems, additions = [], {}
    for path in paths:
        if not _valid_path(path):
            problems.append(_not_inside(path))
            continue
        local = _local(root, path)
        if not os.path.isfile(local):
            problems.append(f'{path}: not found')
            continue
        sha = digest(local)
        if path in frozen and frozen[path] != sha:
            problems.append(f'{path}: already frozen with different contents; only the owner may unfreeze it')
            continue
        additions[path] = sha
    if problems:
        return problems

    frozen.update(additions)
    lines = header + [f'{frozen[path]}  {path}' for path in sorted(frozen)]
    target = _local(root, MANIFEST)
    os.makedirs(os.path.dirname(target), exist_ok=True)
    with open(target, 'wb') as f:
        f.write(('\n'.join(lines) + '\n').encode('utf-8'))
    return []


def main(argv):
    usage = 'usage: frozen_tests.py check [--root DIR] | freeze [--root DIR] PATH...'
    if not argv or argv[0] not in ('check', 'freeze'):
        print(usage, file=sys.stderr)
        return 2
    command, rest = argv[0], argv[1:]
    root, paths = _REPO_ROOT, []
    i = 0
    while i < len(rest):
        arg = rest[i]
        if arg == '--root':
            if i + 1 >= len(rest):
                print(usage, file=sys.stderr)
                return 2
            root = rest[i + 1]
            i += 2
            continue
        if arg.startswith('-'):
            print(usage, file=sys.stderr)
            return 2
        paths.append(arg)
        i += 1
    if (command == 'check') == bool(paths):
        print(usage, file=sys.stderr)
        return 2

    problems = check(root) if command == 'check' else freeze(root, paths)
    for problem in problems:
        print(problem, file=sys.stderr)
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
