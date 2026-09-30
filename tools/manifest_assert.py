#!/usr/bin/env python3
"""Fail the build if the merged Android manifest breaks the vault's rules.

Run it on the *merged* release manifest (what actually ships), for example:
    python tools/manifest_assert.py android/app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml

Rules (threat model; blueprint §26):
  1. Permissions: only those in ALLOWED_PERMISSIONS. No network permission, ever.
  2. <application>: allowBackup="false", dataExtractionRules set, not debuggable, no cleartext traffic.
  3. Exported components: only the launcher activity, plus anything in ALLOWED_EXPORTED guarded by
     the permission listed for it.
  4. Every activity sets taskAffinity="" (stops task hijacking).
Exit code 0 when every rule holds, 1 otherwise; each violation is printed.
"""
import sys
import xml.etree.ElementTree as ET

A = '{http://schemas.android.com/apk/res/android}'

ALLOWED_PERMISSIONS = {
    'android.permission.USE_BIOMETRIC',
    'android.permission.VIBRATE',  # haptics
}
# AndroidX adds a signature-level permission named <applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION.
ALLOWED_PERMISSION_SUFFIXES = ('.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION',)

# Exported components that are acceptable only when guarded by the given permission.
ALLOWED_EXPORTED = {
    # AndroidX baseline-profile installer; only the shell (adb, DUMP permission) can reach it.
    'androidx.profileinstaller.ProfileInstallReceiver': 'android.permission.DUMP',
}

COMPONENTS = ('activity', 'activity-alias', 'service', 'receiver', 'provider')


def attr(el, name):
    return el.get(A + name)


def is_launcher(el):
    for f in el.findall('intent-filter'):
        actions = {attr(a, 'name') for a in f.findall('action')}
        cats = {attr(c, 'name') for c in f.findall('category')}
        if 'android.intent.action.MAIN' in actions and 'android.intent.category.LAUNCHER' in cats:
            return True
    return False


def check(path):
    root = ET.parse(path).getroot()
    problems = []

    for p in root.findall('uses-permission') + root.findall('uses-permission-sdk-23'):
        name = attr(p, 'name') or ''
        if name not in ALLOWED_PERMISSIONS and not name.endswith(ALLOWED_PERMISSION_SUFFIXES):
            problems.append(f'permission not allowed: {name}')

    app = root.find('application')
    if app is None:
        return problems + ['no <application> element']
    if attr(app, 'allowBackup') != 'false':
        problems.append('application must set android:allowBackup="false"')
    if not attr(app, 'dataExtractionRules'):
        problems.append('application must set android:dataExtractionRules (Android 12+ backup and transfer rules)')
    if attr(app, 'debuggable') == 'true':
        problems.append('application must not be debuggable')
    if attr(app, 'usesCleartextTraffic') == 'true':
        problems.append('application must not allow cleartext traffic')

    launchers = 0
    for kind in COMPONENTS:
        for el in app.findall(kind):
            name = attr(el, 'name') or '?'
            exported = attr(el, 'exported')
            if kind in ('activity', 'activity-alias') and is_launcher(el):
                launchers += 1
                if attr(el, 'taskAffinity') != '':
                    problems.append(f'launcher activity {name} must set android:taskAffinity=""')
                continue
            if kind == 'activity' and attr(el, 'taskAffinity') != '':
                problems.append(f'activity {name} must set android:taskAffinity=""')
            if exported == 'true':
                guard = ALLOWED_EXPORTED.get(name)
                if guard is None or attr(el, 'permission') != guard:
                    problems.append(f'{kind} {name} is exported; only the launcher may be')
    if launchers != 1:
        problems.append(f'expected exactly one launcher activity, found {launchers}')
    return problems


def main(argv):
    if len(argv) != 2:
        print(__doc__)
        return 2
    problems = check(argv[1])
    for p in problems:
        print(f'FAIL: {p}')
    if not problems:
        print(f'OK: {argv[1]} meets every manifest rule')
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
