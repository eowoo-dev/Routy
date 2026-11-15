import copy
import datetime
import importlib.util
from pathlib import Path
import unittest

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


if __name__ == "__main__":
    unittest.main()
