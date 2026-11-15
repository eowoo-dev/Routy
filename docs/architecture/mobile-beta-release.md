# TestFlight와 Google Play 비공개 테스트 업로드

## 실행 범위

`Mobile beta release` 워크플로를 GitHub Actions에서 수동 실행합니다. 실행 한 번으로 공유 웹 자원 빌드, 네이티브 동기화, 배포 서명, 테스트 서비스 업로드를 진행합니다. 기존 `Web and mobile builds`는 키 없이도 계속 빌드를 검증합니다.

- `platform`: `android`, `ios`, `both` 중 선택합니다.
- `operation`: `build-only`는 서명된 파일만 생성하고, `upload`는 생성 후 테스트 서비스에 올립니다.
- `build_number`: 비우면 기본 `1000 + 해당 워크플로 실행 번호`입니다. Android versionCode와 iOS CFBundleVersion에 같은 값을 씁니다. 기존 스토어 번호보다 큰 미사용 번호를 명시할 수도 있습니다.
- `android_release_status`: 초기 설정 중인 앱에는 `draft`, 비공개 테스터에게 제공할 준비가 끝난 트랙에는 `completed`를 사용합니다. 상태가 completed여도 Google의 검토나 게시 보류가 있으면 즉시 설치 가능하다는 뜻은 아닙니다.

main 브랜치에서만 실행하며 동시 배포를 제한합니다. 이미 업로드한 번호는 재사용하지 않습니다. 일부 플랫폼만 성공했다면 실패한 작업만 다시 실행하거나 새 실행과 새 번호를 사용합니다.

## 트랙 확인 결과

2026-11-15 Play Console에서 확인한 비공개 트랙 이름은 **`0.1.0`**입니다. 브라우저 URL의 숫자 ID를 API 트랙 이름으로 복사하지 않습니다. 이 이름을 기본값으로 사용하며, 실제 업로드 직전에 API의 트랙 목록과 일치하는지 다시 검사합니다. 존재하지 않으면 목록을 표시하고 업로드를 중단합니다.

Google Play 서비스 계정 인증이 아직 연결되지 않았다면 API의 정확한 트랙 식별자는 최종 검증되지 않은 상태입니다. 콘솔에 보이는 트랙 제목과 API 목록이 다르면 목록의 실제 식별자를 `PLAY_CLOSED_TRACK`에 설정합니다. production, beta 및 internal 기본 트랙은 이 워크플로에서 거부합니다.

## GitHub 설정

저장소 Settings → Secrets and variables → Actions에 등록합니다. 인증 파일과 비밀번호는 채팅이나 Git에 넣지 않습니다.

Variables:

| 이름 | 값 |
| --- | --- |
| `PLAY_CLOSED_TRACK` | `0.1.0` (미설정 시에도 이 값 사용) |
| `APPLE_TEAM_ID` | Apple Developer의 10자리 Team ID |
| `MOBILE_BUILD_NUMBER_BASE` | 선택 사항. 기본 1000. 이미 사용한 번호보다 높은 범위로 조정 가능 |

Android Secrets:

| 이름 | 내용 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 업로드 키가 포함된 PKCS12 또는 JKS 파일의 Base64 |
| `ANDROID_KEYSTORE_PASSWORD` | 키 저장소 비밀번호 |
| `ANDROID_KEY_ALIAS` | 업로드 키 별칭 |
| `ANDROID_KEY_PASSWORD` | 해당 키 비밀번호 |
| `PLAY_SERVICE_ACCOUNT_JSON` | Android Publisher API 서비스 계정 JSON 원문. upload 실행에서만 필요 |

iOS Secrets:

| 이름 | 내용 |
| --- | --- |
| `IOS_CERTIFICATE_BASE64` | 개인 키가 포함된 Apple Distribution `.p12`의 Base64 |
| `IOS_CERTIFICATE_PASSWORD` | `.p12` 내보내기 비밀번호 |
| `IOS_PROVISION_PROFILE_BASE64` | `org.eowoo.routy`용 App Store 배포 프로파일의 Base64 |
| `ASC_KEY_ID` | App Store Connect API Key ID. upload 실행에서만 필요 |
| `ASC_ISSUER_ID` | API 키 Issuer ID. upload 실행에서만 필요 |
| `ASC_PRIVATE_KEY_BASE64` | 해당 API 키 `.p8`의 Base64. upload 실행에서만 필요 |

누락된 키는 빌드 전에 이름만 알려주고 중단합니다. 임시 인증 파일은 runner 임시 디렉터리에만 저장하고 작업 종료 시 정리합니다. 서명 파일과 계정 JSON을 빌드 산출물에 포함하지 않습니다.

## Android 최초 설정

1. Play Console에 스마트폰 앱 `org.eowoo.routy`를 생성하고 Play App Signing을 구성합니다. 차량 앱 `org.eowoo.routy.pleos`와 혼동하지 않습니다.
2. 사용할 업로드 키가 이미 있다면 그대로 사용합니다. 새로운 앱이라면 업로드 키를 한 번 생성하고 백업합니다. 매 실행마다 키를 새로 만들지 않습니다.
3. Android 서명 Secrets 네 개를 등록하고 `platform=android`, `operation=build-only`로 실행합니다.
4. Artifacts의 `routy-android-beta-번호`에서 서명된 AAB를 내려받아 최초 빌드는 Play Console에 직접 등록합니다. 최초 앱 등록에는 이 수동 단계가 필요할 수 있습니다.
5. Google Cloud에서 Google Play Android Developer API를 활성화하고 서비스 계정을 만듭니다. Play Console에 이 계정을 초대하고 루티 앱의 조회 및 테스트 트랙 출시 권한을 부여합니다. 프로덕션 출시 권한은 필요하지 않습니다.
6. 서비스 계정 JSON을 `PLAY_SERVICE_ACCOUNT_JSON`에 등록합니다. 비공개 트랙의 국가/지역과 테스터 목록도 지정합니다. 확인 당시에는 이 설정이 남아 있었습니다.
7. 다음 빌드부터 `operation=upload`로 실행합니다. 초기 앱이면 draft, 트랙 준비가 끝났다면 completed를 선택합니다. 등록 정보나 스크린샷은 덮어쓰지 않습니다.

Windows에서 업로드 키를 처음 만들 때의 예입니다. 비밀번호는 명령줄에 넣지 않고 프롬프트에서 입력합니다.

```powershell
New-Item -ItemType Directory -Force .local-signing
docker compose -f compose.dev.yaml run --rm --no-deps --entrypoint keytool pleos -genkeypair -storetype PKCS12 -keystore /workspace/.local-signing/routy-upload.p12 -alias routy-upload -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=EoWoo Routy"
```

## iOS 최초 설정

1. Apple Developer에 App ID `org.eowoo.routy`, App Store Connect에 해당 앱을 생성합니다.
2. 개인 키를 포함한 Apple Distribution 인증서 `.p12`와 그 인증서가 선택된 App Store 배포 프로파일을 준비합니다. 개발용, Ad Hoc 및 Enterprise 프로파일은 거부됩니다.
3. Team ID와 iOS 서명 Secrets 세 개를 등록하면 build-only 실행이 가능합니다.
4. App Store Connect의 빌드 업로드 권한을 가진 API 키를 발급해 Key ID, Issuer ID, `.p8`을 등록합니다.
5. `platform=ios`, `operation=upload`로 실행하면 서명된 IPA를 TestFlight로 올립니다. 개인 Mac 대신 Actions의 macOS runner에서 처리합니다.

인증서를 처음 만드는 경우에도 Windows와 Docker를 사용할 수 있습니다. 이미 보유한 인증서와 개인 키가 있다면 재발급할 필요가 없습니다.

```powershell
New-Item -ItemType Directory -Force .local-signing
docker compose -f compose.dev.yaml exec web openssl req -new -newkey rsa:2048 -nodes -keyout /workspace/.local-signing/apple-distribution.key -out /workspace/.local-signing/apple-distribution.csr -subj "/CN=EoWoo Routy"
```

생성한 CSR을 Apple Developer의 Apple Distribution 인증서 발급 화면에 올리고, 받은 인증서를 `.local-signing/distribution.cer`에 저장한 뒤 다음을 실행합니다. `.p12` 비밀번호는 프롬프트에서 입력합니다.

```powershell
docker compose -f compose.dev.yaml exec web openssl x509 -inform DER -in /workspace/.local-signing/distribution.cer -out /workspace/.local-signing/distribution.pem
docker compose -f compose.dev.yaml exec web openssl pkcs12 -export -inkey /workspace/.local-signing/apple-distribution.key -in /workspace/.local-signing/distribution.pem -out /workspace/.local-signing/distribution.p12
```

인증 파일을 Base64로 복사할 때는 PowerShell에서 아래 형태를 사용합니다. 출력 대신 클립보드로 복사하고 GitHub Secret 입력란에 붙여넣습니다.

```powershell
Set-Clipboard -Value ([Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path '.local-signing/distribution.p12').Path)))
```

TestFlight 업로드 성공과 Apple의 빌드 처리 완료는 다릅니다. 업로드 후 App Store Connect에서 처리 상태와 암호화 관련 질문을 확인하고 테스트 그룹에 배정합니다. 외부 테스터에게 제공하려면 별도 Beta App Review가 필요할 수 있습니다. 이 워크플로는 정식 App Store 출시나 외부 테스트 심사를 자동 제출하지 않습니다.

## 자동 검증과 실제 업로드의 구분

- Node 테스트는 버전 증가, 배포 대상 제한, 누락된 키, 잘못된 인증 JSON 및 임시 파일 정리를 확인합니다.
- Python 테스트는 만료, 팀과 앱 식별자 불일치, 개발용 및 Ad Hoc 프로파일을 거부하는지 확인합니다.
- 기존 빌드 CI는 키 없이 Android와 iOS 앱을 빌드하고, 별도의 일회용 CI 키로 Android AAB를 서명한 뒤 서명을 검증합니다. 이 키로 만든 AAB는 스토어 배포용이 아닙니다.
- Fastlane 의존성은 잠금 파일로 고정하고 업로드 lane을 인증 없이 로드해 검사합니다.
- 실제 배포용 인증서 및 스토어 권한을 검증하고 업로드하려면 위 Secrets가 필요합니다. Secrets 등록 전에는 스토어 전송 성공을 검증한 것이 아닙니다.

참고: [Google Play 트랙 API](https://developers.google.com/android-publisher/tracks), [Fastlane Play 초기 설정](https://docs.fastlane.tools/actions/upload_to_play_store/), [GitHub의 Apple 서명 구성](https://docs.github.com/en/actions/how-tos/deploy/deploy-to-third-party-platforms/sign-xcode-applications), [TestFlight 외부 테스트](https://developer.apple.com/help/app-store-connect/test-a-beta-version/invite-external-testers).
