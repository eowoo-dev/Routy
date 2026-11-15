#!/bin/sh
# Run inside the Compose pleos build service. Never print or commit signing secrets.
set -eu
umask 077
cd /workspace/apps/pleos
signing=/workspace/.local-signing
key="$signing/pleos-submission.p12"
password="$signing/pleos-submission-password.txt"
mkdir -p "$signing" /workspace/apk
if [ -f "$key" ] && [ ! -s "$password" ]; then
    echo 'Submission key exists but its password file is missing.' >&2
    exit 1
fi
if [ ! -s "$password" ]; then
    head -c 48 /dev/urandom | base64 > "$password"
fi
if [ ! -f "$key" ]; then
    keytool -genkeypair -noprompt -storetype PKCS12 -keystore "$key" \
        -storepass:file "$password" -keypass:file "$password" \
        -alias routy-pleos-submission -keyalg RSA -keysize 3072 -validity 10000 \
        -dname 'CN=EoWoo Routy, O=EoWoo, C=KR'
fi
sh /opt/routy/gradle-entrypoint.sh :app:assembleRelease \
    -ProutyApiBaseUrl=https://routy.eowoo.org
tools="$ANDROID_HOME/build-tools/35.0.0"
aligned=app/build/outputs/apk/release/app-release-aligned.apk
apk=/workspace/apk/어쩌다우리_루티_release.apk
"$tools/zipalign" -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk "$aligned"
"$tools/apksigner" sign --ks "$key" --ks-key-alias routy-pleos-submission \
    --ks-pass "file:$password" --v4-signing-enabled false --out "$apk" "$aligned"
"$tools/apksigner" verify --verbose --print-certs "$apk"
"$tools/zipalign" -c -p 4 "$apk"
badging=$("$tools/aapt" dump badging "$apk")
printf '%s\n' "$badging" | grep -q "^package: name='org.eowoo.routy.pleos' "
if printf '%s\n' "$badging" | grep -q '^application-debuggable'; then
    echo 'Submission APK must not be debuggable.' >&2
    exit 1
fi
printf '%s\n' "$badging" | head -n 5
cd /workspace/apk
sha256sum 어쩌다우리_루티_release.apk > SHA256SUMS
