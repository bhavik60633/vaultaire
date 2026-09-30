"""Tests for manifest_assert.py. Run: python -m unittest discover -s tools -p "test_*.py" """
import os
import tempfile
import unittest

import manifest_assert

HEAD = '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.example.vault">'

GOOD = HEAD + '''
  <uses-permission android:name="android.permission.USE_BIOMETRIC"/>
  <permission android:name="dev.example.vault.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" android:protectionLevel="signature"/>
  <uses-permission android:name="dev.example.vault.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"/>
  <application android:allowBackup="false" android:dataExtractionRules="@xml/data_extraction_rules">
    <activity android:name=".MainActivity" android:exported="true" android:taskAffinity="">
      <intent-filter>
        <action android:name="android.intent.action.MAIN"/>
        <category android:name="android.intent.category.LAUNCHER"/>
      </intent-filter>
    </activity>
    <receiver android:name="androidx.profileinstaller.ProfileInstallReceiver" android:exported="true"
              android:permission="android.permission.DUMP"/>
    <provider android:name="androidx.startup.InitializationProvider" android:exported="false"/>
  </application>
</manifest>'''


def run(xml):
    with tempfile.NamedTemporaryFile('w', suffix='.xml', delete=False, encoding='utf-8') as f:
        f.write(xml)
        path = f.name
    try:
        return manifest_assert.check(path)
    finally:
        os.unlink(path)


class ManifestAssertTest(unittest.TestCase):
    def test_good_manifest_passes(self):
        self.assertEqual(run(GOOD), [])

    def test_internet_permission_fails(self):
        bad = GOOD.replace('<application', '<uses-permission android:name="android.permission.INTERNET"/>\n  <application')
        self.assertIn('permission not allowed: android.permission.INTERNET', run(bad))

    def test_backup_allowed_fails(self):
        bad = GOOD.replace('android:allowBackup="false"', 'android:allowBackup="true"')
        self.assertTrue(any('allowBackup' in p for p in run(bad)))

    def test_missing_data_extraction_rules_fails(self):
        bad = GOOD.replace(' android:dataExtractionRules="@xml/data_extraction_rules"', '')
        self.assertTrue(any('dataExtractionRules' in p for p in run(bad)))

    def test_debuggable_fails(self):
        bad = GOOD.replace('<application ', '<application android:debuggable="true" ')
        self.assertIn('application must not be debuggable', run(bad))

    def test_extra_exported_component_fails(self):
        bad = GOOD.replace('</application>', '<service android:name=".Sync" android:exported="true"/>\n  </application>')
        self.assertIn('service .Sync is exported; only the launcher may be', run(bad))

    def test_profile_installer_without_dump_permission_fails(self):
        bad = GOOD.replace(' android:permission="android.permission.DUMP"', '')
        self.assertTrue(any('ProfileInstallReceiver is exported' in p for p in run(bad)))

    def test_launcher_without_task_affinity_fails(self):
        bad = GOOD.replace(' android:taskAffinity=""', '')
        self.assertTrue(any('taskAffinity' in p for p in run(bad)))

    def test_two_launchers_fail(self):
        extra = '''<activity android:name=".Second" android:exported="true" android:taskAffinity="">
      <intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter>
    </activity>
  </application>'''
        bad = GOOD.replace('</application>', extra)
        self.assertIn('expected exactly one launcher activity, found 2', run(bad))


if __name__ == '__main__':
    unittest.main()
