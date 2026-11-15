import copy
import datetime
import importlib.util
from pathlib import Path
import unittest
import os
import shutil
import subprocess
import tempfile
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("ios_signing", Path(__file__).with_name("ios-signing.py"))
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)


class ProfileValidationTest(unittest.TestCase):
    def setUp(self):
        self.team = "ABCDEFGHIJ"
        self.package = "org.eowoo.routy"
        self.now = datetime.datetime(2026, 11, 15, tzinfo=datetime.timezone.utc)
        self.profile = {
            "UUID": "12345678-1234-1234-1234-123456789abc",
            "ExpirationDate": datetime.datetime(2027, 11, 15),
            "TeamIdentifier": [self.team], "ApplicationIdentifierPrefix": ["OLDPREFIX0"],
            "Entitlements": {"application-identifier": "OLDPREFIX0." + self.package,
                             "com.apple.developer.team-identifier": self.team,
                             "beta-reports-active": True, "get-task-allow": False},
        }

    def validate(self, profile):
        return signing.validate_profile(profile, self.team, self.package, self.now)

    def test_valid_store_profile_including_legacy_app_prefix(self):
        self.assertEqual(self.validate(self.profile), self.profile["UUID"])

    def test_expired_and_wrong_team_profiles_fail(self):
        for key, value in [("ExpirationDate", datetime.datetime(2026, 1, 1)), ("TeamIdentifier", ["OTHERTEAM0"]), ("UUID", "../../escape")]:
            with self.subTest(key=key):
                profile = copy.deepcopy(self.profile)
                profile[key] = value
                with self.assertRaises(ValueError):
                    self.validate(profile)

    def test_wrong_bundle_or_wildcard_profiles_fail(self):
        for identifier in ["OLDPREFIX0.org.eowoo.other", "OLDPREFIX0.*", "WRONG.org.eowoo.routy"]:
            profile = copy.deepcopy(self.profile)
            profile["Entitlements"]["application-identifier"] = identifier
            with self.subTest(identifier=identifier), self.assertRaises(ValueError):
                self.validate(profile)

    def test_development_adhoc_and_enterprise_profiles_fail(self):
        variants = [dict(self.profile, ProvisionedDevices=[]), dict(self.profile, ProvisionsAllDevices=True)]
        for key, value in [("get-task-allow", True), ("beta-reports-active", False)]:
            profile = copy.deepcopy(self.profile)
            profile["Entitlements"][key] = value
            variants.append(profile)
        for profile in variants:
            with self.assertRaises(ValueError):
                self.validate(profile)


@unittest.skipUnless(shutil.which("openssl"), "OpenSSL CLI is required")
class CertificateCompatibilityTest(unittest.TestCase):
    def test_modern_pkcs12_is_repacked_without_changing_certificate_or_writing_pem(self):
        with tempfile.TemporaryDirectory(prefix="routy-p12-test-") as folder:
            root = Path(folder)
            key, cert, source, target = [root / name for name in ["key.pem", "cert.pem", "modern.p12", "compatible.p12"]]
            environment = dict(os.environ, IOS_CERTIFICATE_PASSWORD="temporary-test-password")
            subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", str(key), "-out", str(cert), "-days", "1", "-subj", "/CN=Disposable Test"], check=True, capture_output=True)
            subprocess.run(["openssl", "pkcs12", "-export", "-inkey", str(key), "-in", str(cert), "-out", str(source), "-passout", "env:IOS_CERTIFICATE_PASSWORD"], check=True, capture_output=True, env=environment)
            with patch.dict(os.environ, environment, clear=True):
                signing.normalize_certificate(source, target)
            info = subprocess.run(["openssl", "pkcs12", "-in", str(target), "-info", "-noout", "-passin", "env:IOS_CERTIFICATE_PASSWORD"], check=True, capture_output=True, env=environment).stderr.decode()
            self.assertIn("MAC: sha1", info)
            self.assertIn("pbeWithSHA1And3-KeyTripleDES-CBC", info)
            decoded = subprocess.run(["openssl", "pkcs12", "-in", str(target), "-clcerts", "-nokeys", "-passin", "env:IOS_CERTIFICATE_PASSWORD"], check=True, capture_output=True, env=environment).stdout
            self.assertIn(cert.read_bytes().strip(), decoded)
            self.assertEqual(sorted(path.name for path in root.iterdir()), ["cert.pem", "compatible.p12", "key.pem", "modern.p12"])
            if os.name != "nt":
                self.assertEqual(target.stat().st_mode & 0o777, 0o600)

    def test_invalid_container_is_redacted_and_does_not_leave_output(self):
        with tempfile.TemporaryDirectory(prefix="routy-p12-invalid-") as folder:
            root = Path(folder)
            source, target = root / "invalid.p12", root / "compatible.p12"
            source.write_bytes(b"not-a-pkcs12")
            with patch.dict(os.environ, {"IOS_CERTIFICATE_PASSWORD": "sensitive-test-password"}):
                with self.assertRaises(RuntimeError) as error:
                    signing.normalize_certificate(source, target)
            self.assertNotIn("sensitive-test-password", str(error.exception))
            self.assertIn("openssl pkcs12", str(error.exception))
            self.assertFalse(target.exists())


if __name__ == "__main__":
    unittest.main()
