"""Tests for frozen_tests.py, the test-freeze guard (component 1, product plan §7).

Run: python -m unittest discover -s tools -p "test_*.py"

The guard keeps a checksum list of frozen test files in tests/FROZEN.sha256 and fails CI
when a frozen file changes. Only the owner adds or removes lines in that list.
"""
import os
import tempfile
import unittest

import frozen_tests

MANIFEST = 'tests/FROZEN.sha256'
HELLO_SHA = '5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03'  # sha256(b'hello\n')
EMPTY_SHA = 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'  # sha256(b'')


class Repo:
    """A throwaway repository root with helpers to write files and the manifest."""

    def __init__(self):
        self._dir = tempfile.TemporaryDirectory()
        self.root = self._dir.name

    def close(self):
        self._dir.cleanup()

    def write(self, rel, data):
        path = os.path.join(self.root, *rel.split('/'))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, 'wb') as f:
            f.write(data if isinstance(data, bytes) else data.encode('utf-8'))

    def read(self, rel):
        with open(os.path.join(self.root, *rel.split('/')), 'rb') as f:
            return f.read().decode('utf-8')

    def exists(self, rel):
        return os.path.exists(os.path.join(self.root, *rel.split('/')))

    def manifest(self, text):
        self.write(MANIFEST, text)


class FrozenTestsTest(unittest.TestCase):
    def setUp(self):
        self.repo = Repo()

    def tearDown(self):
        self.repo.close()

    # ---- the default location of the checksum list ----

    def test_manifest_location(self):
        self.assertEqual(frozen_tests.MANIFEST, MANIFEST)

    # ---- digest ----

    def test_digest_is_sha256_of_the_file(self):
        self.repo.write('a.txt', b'hello\n')
        self.assertEqual(frozen_tests.digest(os.path.join(self.repo.root, 'a.txt')), HELLO_SHA)

    def test_digest_of_empty_file(self):
        self.repo.write('a.txt', b'')
        self.assertEqual(frozen_tests.digest(os.path.join(self.repo.root, 'a.txt')), EMPTY_SHA)

    def test_digest_ignores_windows_line_endings(self):
        # A Windows checkout may turn LF into CRLF; that must not count as a change.
        self.repo.write('a.txt', b'hello\r\n')
        self.assertEqual(frozen_tests.digest(os.path.join(self.repo.root, 'a.txt')), HELLO_SHA)

    def test_digest_sees_any_other_change(self):
        self.repo.write('a.txt', b'hello \n')
        self.assertNotEqual(frozen_tests.digest(os.path.join(self.repo.root, 'a.txt')), HELLO_SHA)

    # ---- check ----

    def test_unchanged_files_pass(self):
        self.repo.write('core/tests/a.rs', b'hello\n')
        self.repo.write('tools/test_b.py', b'')
        self.repo.manifest(f'# frozen\n\n{HELLO_SHA}  core/tests/a.rs\n{EMPTY_SHA}  tools/test_b.py\n')
        self.assertEqual(frozen_tests.check(self.repo.root), [])

    def test_empty_manifest_passes(self):
        self.repo.manifest('# nothing frozen yet\n')
        self.assertEqual(frozen_tests.check(self.repo.root), [])

    def test_missing_manifest_fails(self):
        self.assertEqual(frozen_tests.check(self.repo.root), ['tests/FROZEN.sha256: not found'])

    def test_changed_file_fails(self):
        self.repo.write('core/tests/a.rs', b'hello, changed\n')
        self.repo.manifest(f'{HELLO_SHA}  core/tests/a.rs\n')
        self.assertEqual(frozen_tests.check(self.repo.root), ['core/tests/a.rs: changed since it was frozen'])

    def test_missing_file_fails(self):
        self.repo.manifest(f'{HELLO_SHA}  core/tests/a.rs\n')
        self.assertEqual(frozen_tests.check(self.repo.root), ['core/tests/a.rs: missing'])

    def test_every_problem_is_reported_in_manifest_order(self):
        self.repo.write('b.py', b'changed\n')
        self.repo.write('c.py', b'hello\n')
        self.repo.manifest(f'{HELLO_SHA}  a.py\n{HELLO_SHA}  b.py\n{HELLO_SHA}  c.py\n')
        self.assertEqual(frozen_tests.check(self.repo.root), [
            'a.py: missing',
            'b.py: changed since it was frozen',
        ])

    def test_windows_checkout_of_a_frozen_file_passes(self):
        self.repo.write('a.py', b'hello\r\n')
        self.repo.manifest(f'{HELLO_SHA}  a.py\n')
        self.assertEqual(frozen_tests.check(self.repo.root), [])

    def test_manifest_with_windows_line_endings_passes(self):
        self.repo.write('a.py', b'hello\n')
        self.repo.manifest(f'# frozen\r\n{HELLO_SHA}  a.py\r\n')
        self.assertEqual(frozen_tests.check(self.repo.root), [])

    # ---- a damaged checksum list ----

    def test_malformed_lines_fail(self):
        self.repo.write('a.py', b'hello\n')
        bad = [
            f'{HELLO_SHA} a.py',            # one space instead of two
            f'{HELLO_SHA[:-1]}  a.py',      # 63 hex characters
            f'{HELLO_SHA.upper()}  a.py',   # uppercase hex
            f'{HELLO_SHA}  ',               # no path
            'not a checksum line',
        ]
        for line in bad:
            with self.subTest(line=line):
                self.repo.manifest(f'# frozen\n{line}\n')
                self.assertEqual(frozen_tests.check(self.repo.root), ['tests/FROZEN.sha256 line 2: malformed'])

    def test_path_outside_the_repository_fails(self):
        for path in ['../a.py', '/etc/passwd', 'tools/../../a.py', 'tools\\a.py', 'C:/a.py', './a.py', 'tools//a.py']:
            with self.subTest(path=path):
                self.repo.manifest(f'{HELLO_SHA}  {path}\n')
                self.assertEqual(frozen_tests.check(self.repo.root), [
                    f'tests/FROZEN.sha256 line 1: {path} is not a relative path inside the repository',
                ])

    def test_path_listed_twice_fails(self):
        self.repo.write('a.py', b'hello\n')
        self.repo.manifest(f'{HELLO_SHA}  a.py\n{HELLO_SHA}  a.py\n')
        self.assertEqual(frozen_tests.check(self.repo.root), ['tests/FROZEN.sha256 line 2: a.py listed twice'])

    # ---- freeze ----

    def test_freeze_creates_the_manifest(self):
        self.repo.write('tools/test_b.py', b'hello\n')
        self.assertEqual(frozen_tests.freeze(self.repo.root, ['tools/test_b.py']), [])
        text = self.repo.read(MANIFEST)
        self.assertTrue(text.startswith('#'), 'a new manifest starts with a comment explaining it')
        self.assertIn(f'{HELLO_SHA}  tools/test_b.py\n', text)
        self.assertEqual(frozen_tests.check(self.repo.root), [])

    def test_freeze_adds_entries_sorted_by_path_and_keeps_the_header(self):
        self.repo.write('z.py', b'hello\n')
        self.repo.write('a.py', b'')
        self.repo.write('m.py', b'hello\n')
        self.repo.manifest(f'# Owner: frozen tests\n# second header line\n{HELLO_SHA}  m.py\n')
        self.assertEqual(frozen_tests.freeze(self.repo.root, ['z.py', 'a.py']), [])
        self.assertEqual(self.repo.read(MANIFEST), (
            '# Owner: frozen tests\n'
            '# second header line\n'
            f'{EMPTY_SHA}  a.py\n'
            f'{HELLO_SHA}  m.py\n'
            f'{HELLO_SHA}  z.py\n'
        ))

    def test_freezing_an_unchanged_frozen_file_is_a_no_op(self):
        self.repo.write('a.py', b'hello\n')
        self.repo.manifest(f'# frozen\n{HELLO_SHA}  a.py\n')
        self.assertEqual(frozen_tests.freeze(self.repo.root, ['a.py']), [])
        self.assertEqual(self.repo.read(MANIFEST), f'# frozen\n{HELLO_SHA}  a.py\n')

    def test_freeze_refuses_to_refreeze_changed_contents(self):
        # Re-freezing would silently accept an edited test. Only the owner unfreezes, by hand.
        self.repo.write('a.py', b'hello, edited\n')
        before = f'# frozen\n{HELLO_SHA}  a.py\n'
        self.repo.manifest(before)
        self.assertEqual(frozen_tests.freeze(self.repo.root, ['a.py']), [
            'a.py: already frozen with different contents; only the owner may unfreeze it',
        ])
        self.assertEqual(self.repo.read(MANIFEST), before)

    def test_freeze_of_a_missing_file_fails(self):
        self.repo.manifest('# frozen\n')
        self.assertEqual(frozen_tests.freeze(self.repo.root, ['nope.py']), ['nope.py: not found'])
        self.assertEqual(self.repo.read(MANIFEST), '# frozen\n')

    def test_freeze_of_a_path_outside_the_repository_fails(self):
        self.assertEqual(frozen_tests.freeze(self.repo.root, ['../a.py']), [
            '../a.py is not a relative path inside the repository',
        ])
        self.assertFalse(self.repo.exists(MANIFEST))

    def test_freeze_is_all_or_nothing(self):
        self.repo.write('a.py', b'hello\n')
        self.repo.manifest('# frozen\n')
        self.assertEqual(frozen_tests.freeze(self.repo.root, ['a.py', 'nope.py']), ['nope.py: not found'])
        self.assertEqual(self.repo.read(MANIFEST), '# frozen\n')

    def test_freeze_refuses_a_damaged_manifest(self):
        self.repo.write('a.py', b'hello\n')
        self.repo.manifest('# frozen\ngarbage\n')
        self.assertEqual(frozen_tests.freeze(self.repo.root, ['a.py']), ['tests/FROZEN.sha256 line 2: malformed'])
        self.assertEqual(self.repo.read(MANIFEST), '# frozen\ngarbage\n')

    # ---- command line ----

    def test_cli_check_exit_codes(self):
        self.repo.write('a.py', b'hello\n')
        self.repo.manifest(f'{HELLO_SHA}  a.py\n')
        self.assertEqual(frozen_tests.main(['check', '--root', self.repo.root]), 0)
        self.repo.write('a.py', b'edited\n')
        self.assertEqual(frozen_tests.main(['check', '--root', self.repo.root]), 1)

    def test_cli_freeze_exit_codes(self):
        self.repo.write('a.py', b'hello\n')
        self.assertEqual(frozen_tests.main(['freeze', '--root', self.repo.root, 'a.py']), 0)
        self.assertEqual(frozen_tests.check(self.repo.root), [])
        self.assertEqual(frozen_tests.main(['freeze', '--root', self.repo.root, 'nope.py']), 1)

    def test_cli_usage_errors(self):
        for argv in [[], ['unfreeze', 'a.py'], ['freeze', '--root', self.repo.root]]:
            with self.subTest(argv=argv):
                self.assertEqual(frozen_tests.main(argv), 2)


if __name__ == '__main__':
    unittest.main()
