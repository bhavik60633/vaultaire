"""Component 19 (milestone M5, ADR-009): the manifest rules for the card scanner.

The scanner needs CAMERA, and only that: the app still has no way onto the network, and it still
installs on a phone without a camera. Run: python -m unittest discover -s tools -p "test_*.py"
"""
import unittest

import test_manifest_assert as base

CAMERA = '<uses-permission android:name="android.permission.CAMERA"/>\n  '
OPTIONAL_CAMERA = '<uses-feature android:name="android.hardware.camera" android:required="false"/>\n  '


def with_lines(*lines):
    return base.GOOD.replace('<application', ''.join(lines) + '<application')


class CameraManifestTest(unittest.TestCase):
    def test_camera_with_an_optional_camera_passes(self):
        self.assertEqual(base.run(with_lines(CAMERA, OPTIONAL_CAMERA)), [])

    def test_camera_without_saying_the_camera_is_optional_fails(self):
        self.assertIn('camera must be optional: android.hardware.camera required="false"', base.run(with_lines(CAMERA)))

    def test_a_required_camera_fails(self):
        required = OPTIONAL_CAMERA.replace('"false"', '"true"')
        self.assertIn('camera must be optional: android.hardware.camera required="false"', base.run(with_lines(CAMERA, required)))

    def test_network_permissions_still_fail_next_to_the_camera(self):
        problems = base.run(with_lines(
            CAMERA, OPTIONAL_CAMERA,
            '<uses-permission android:name="android.permission.INTERNET"/>\n  ',
            '<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>\n  ',
        ))
        self.assertIn('permission not allowed: android.permission.INTERNET', problems)
        self.assertIn('permission not allowed: android.permission.ACCESS_NETWORK_STATE', problems)


if __name__ == '__main__':
    unittest.main()
