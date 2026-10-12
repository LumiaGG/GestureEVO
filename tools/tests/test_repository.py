import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_repository import contains_private_key, forbidden_path


class RepositoryPathTest(unittest.TestCase):
    def test_developer_state_is_rejected(self):
        for path in (".idea/misc.xml", ".kotlin/errors/x.log", "tools/__pycache__/x.pyc", "local.properties"):
            with self.subTest(path=path):
                self.assertTrue(forbidden_path(path))

    def test_credentials_and_outputs_are_rejected(self):
        for path in ("keys/release.jks", "keys/release.PFX", ".signing/keystore-password.txt", "app/release/output-metadata.json", "app/build/outputs/a.APK", ".env.local"):
            with self.subTest(path=path):
                self.assertTrue(forbidden_path(path))

    def test_migration_schemas_are_preserved(self):
        self.assertFalse(forbidden_path("app/schemas/database/1.json"))

    def test_required_binary_inputs_are_allowed(self):
        for path in ("gradle/wrapper/gradle-wrapper.jar", "third_party/litert/lib/arm64-v8a/libLiteRt.so", "app/src/main/modelAssets/models/ocr.tflite", ".env.example"):
            with self.subTest(path=path):
                self.assertFalse(forbidden_path(path))


class PrivateKeyMaterialTest(unittest.TestCase):
    def test_supported_private_key_formats_are_rejected(self):
        for kind in ("PRIVATE KEY", "RSA PRIVATE KEY", "EC PRIVATE KEY", "DSA PRIVATE KEY",
                     "OPENSSH PRIVATE KEY", "ENCRYPTED PRIVATE KEY", "PGP PRIVATE KEY BLOCK"):
            with self.subTest(kind=kind):
                content = ("-----BEGIN " + kind + "-----\nexample\n").encode()
                self.assertTrue(contains_private_key(content))

    def test_public_certificates_and_public_keys_are_allowed(self):
        for kind in ("CERTIFICATE", "PUBLIC KEY", "RSA PUBLIC KEY", "PGP PUBLIC KEY BLOCK"):
            with self.subTest(kind=kind):
                self.assertFalse(contains_private_key(("-----BEGIN " + kind + "-----").encode()))

    def test_private_key_embedded_in_configuration_is_rejected(self):
        content = ('{"key":"-----BEGIN ' + 'PRIVATE KEY-----\\nexample"}').encode()
        self.assertTrue(contains_private_key(content))


if __name__ == "__main__":
    unittest.main()
