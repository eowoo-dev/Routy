"""Install ephemeral Apple signing material on a GitHub-hosted macOS runner."""
import datetime
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import shlex
import shutil
import subprocess
import sys


def validate_profile(profile, team, package, now=None):
    now = now or datetime.datetime.now(datetime.timezone.utc)
    expiry = profile.get("ExpirationDate")
    if not isinstance(expiry, datetime.datetime) or expiry.replace(tzinfo=datetime.timezone.utc) <= now:
        raise ValueError("Provisioning profile has expired")
    uuid = profile.get("UUID", "")
    if not re.fullmatch(r"[A-Fa-f0-9-]{36}", uuid):
        raise ValueError("Invalid provisioning profile UUID")
    entitlements = profile.get("Entitlements", {})
    if team not in profile.get("TeamIdentifier", []) or entitlements.get("com.apple.developer.team-identifier") != team:
        raise ValueError("Provisioning profile team mismatch")
    identifier = entitlements.get("application-identifier", "")
    if identifier.partition(".")[2] != package or identifier.partition(".")[0] not in profile.get("ApplicationIdentifierPrefix", []):
        raise ValueError("Provisioning profile bundle identifier mismatch")
    if profile.get("ProvisionedDevices") is not None or profile.get("ProvisionsAllDevices") or entitlements.get("get-task-allow") or not entitlements.get("beta-reports-active"):
        raise ValueError("An App Store distribution provisioning profile is required")
    return uuid


def command(*args, input_data=None):
    try:
        return subprocess.run(args, input=input_data, check=True, capture_output=True).stdout
    except subprocess.CalledProcessError as error:
        # Never include a command line containing a certificate password in logs.
        operation = " ".join(args[:2])
        detail = (error.stderr or b"").decode(errors="replace")
        for name, value in os.environ.items():
            if value and any(part in name for part in ["PASSWORD", "PRIVATE_KEY", "CERTIFICATE_BASE64", "PROFILE_BASE64"]):
                detail = detail.replace(value, "[redacted]")
        detail = re.sub(r"-----BEGIN.*?-----END[^\n]+-----", "[redacted PEM]", detail, flags=re.S)
        detail = detail[:1200]
        raise RuntimeError(f"Apple signing failed at {operation} (exit {error.returncode}): {detail.strip()}") from None


def normalize_certificate(source, destination):
    # Apple Keychain does not reliably import OpenSSL 3's default PBES2 containers.
    # Keep the original secret unchanged. Plaintext key material exists only in pipes.
    read_args = ["openssl", "pkcs12", "-in", str(source), "-nodes", "-passin", "env:IOS_CERTIFICATE_PASSWORD"]
    try:
        material = command(*read_args)
    except RuntimeError:
        if b"OpenSSL 3" not in command("openssl", "version"):
            raise
        material = command(*read_args, "-legacy")
    descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    os.close(descriptor)
    public_certificate = destination.with_suffix(".certificate.pem")
    encrypted_key = destination.with_suffix(".encrypted-key.pem")
    try:
        certificate = b"\n".join(re.findall(b"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", material, re.S)) + b"\n"
        encrypted = command("openssl", "pkey", "-aes-256-cbc", "-passout", "env:IOS_CERTIFICATE_PASSWORD", input_data=material)
        if b"-----BEGIN ENCRYPTED PRIVATE KEY-----" not in encrypted:
            raise ValueError("Encrypted certificate conversion failed")
        descriptor = os.open(public_certificate, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "wb") as file:
            file.write(certificate)
        descriptor = os.open(encrypted_key, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "wb") as file:
            file.write(encrypted)
        command("openssl", "pkcs12", "-export", "-out", str(destination),
                "-in", str(public_certificate), "-inkey", str(encrypted_key), "-passin", "env:IOS_CERTIFICATE_PASSWORD",
                "-keypbe", "PBE-SHA1-3DES", "-certpbe", "PBE-SHA1-3DES", "-macalg", "sha1",
                "-passout", "env:IOS_CERTIFICATE_PASSWORD", input_data=material)
    except Exception:
        destination.unlink(missing_ok=True)
        raise
    finally:
        public_certificate.unlink(missing_ok=True)
        encrypted_key.unlink(missing_ok=True)


def install():
    directory = Path(os.environ["RUNNER_TEMP"]).resolve() / "routy-mobile-release"
    profile = plistlib.loads(command("security", "cms", "-D", "-i", os.environ["IOS_PROFILE_PATH"]))
    identity = json.loads((Path(__file__).resolve().parents[2] / "packages/contracts/identity.json").read_text())
    package = identity["applicationIds"]["mobile"]
    team = os.environ["APPLE_TEAM_ID"]
    uuid = validate_profile(profile, team, package)
    compatible_certificate = directory / "distribution-keychain.p12"
    normalize_certificate(os.environ["IOS_CERTIFICATE_PATH"], compatible_certificate)
    keychain = directory / "signing.keychain-db"
    password = os.urandom(32).hex()
    command("security", "create-keychain", "-p", password, str(keychain))
    command("security", "set-keychain-settings", "-lut", "21600", str(keychain))
    command("security", "unlock-keychain", "-p", password, str(keychain))
    command("security", "import", str(compatible_certificate), "-P", os.environ["IOS_CERTIFICATE_PASSWORD"], "-k", str(keychain), "-T", "/usr/bin/codesign", "-T", "/usr/bin/security")
    command("security", "set-key-partition-list", "-S", "apple-tool:,apple:", "-k", password, str(keychain))
    found = command("security", "find-identity", "-v", "-p", "codesigning", str(keychain)).decode()
    if not any(hashlib.sha1(cert).hexdigest().upper() in found for cert in profile.get("DeveloperCertificates", [])):
        raise ValueError("Distribution certificate does not match the provisioning profile")
    existing = shlex.split(command("security", "list-keychains", "-d", "user").decode())
    command("security", "list-keychains", "-d", "user", "-s", str(keychain), *existing)
    # Xcode 16+ uses UserData; also install the legacy documented cache location.
    for folder in ["Library/Developer/Xcode/UserData/Provisioning Profiles", "Library/MobileDevice/Provisioning Profiles"]:
        target = Path.home() / folder / f"{uuid}.mobileprovision"
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(os.environ["IOS_PROFILE_PATH"], target)
        target.chmod(0o600)
    options = {
        "method": "app-store-connect", "destination": "export", "teamID": team,
        "signingStyle": "manual", "signingCertificate": "Apple Distribution",
        "provisioningProfiles": {package: uuid}, "manageAppVersionAndBuildNumber": False,
        "uploadSymbols": True,
    }
    with (directory / "ExportOptions.plist").open("wb") as file:
        plistlib.dump(options, file)
    with open(os.environ["GITHUB_ENV"], "a") as file:
        file.write(f"IOS_PROFILE_UUID={uuid}\n")
    print("Installed matching App Store profile and distribution certificate")


def cleanup():
    directory = Path(os.environ["RUNNER_TEMP"]).resolve() / "routy-mobile-release"
    keychain = directory / "signing.keychain-db"
    if keychain.exists():
        command("security", "delete-keychain", str(keychain))
    uuid = os.environ.get("IOS_PROFILE_UUID", "")
    if re.fullmatch(r"[A-Fa-f0-9-]{36}", uuid):
        for folder in ["Library/Developer/Xcode/UserData/Provisioning Profiles", "Library/MobileDevice/Provisioning Profiles"]:
            (Path.home() / folder / f"{uuid}.mobileprovision").unlink(missing_ok=True)


if __name__ == "__main__":
    try:
        if sys.argv[1] == "install":
            install()
        elif sys.argv[1] == "cleanup":
            cleanup()
        else:
            raise ValueError("Unknown signing command")
    except (ValueError, RuntimeError, KeyError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
