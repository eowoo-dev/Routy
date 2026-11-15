# 차량용 앱 개발 환경

## 실행 구성

- `apps/pleos`: Kotlin·Jetpack Compose Android 앱, 독립 Gradle 프로젝트.
- `compose.dev.yaml`의 `pleos`: JDK 21, Android SDK, Gradle을 실행하는 빌드 도구 서비스.
- `compose.prod.yaml`의 `pleos`: 같은 도구로 release APK를 만드는 독립 설정. 공모전 제출 서명은 별도 패키징 스크립트를 사용하며 차량용 스토어 배포는 아직 구성하지 않았습니다.
- Pleos Connect 2.1.0 에뮬레이터와 ADB는 Windows에서 실행합니다. Docker에서 에뮬레이터를 실행하지 않습니다.
- `pleos` 서비스는 `tools` 프로필에 두었습니다. 상주 서버가 아니므로 `run --rm`으로 빌드할 때만 실행하며, 일반 `up`은 웹·DB만 시작합니다.

Android Studio와 호스트 JDK는 기본 작업 흐름에 필요하지 않습니다. 호스트에는 Docker Desktop과 기존 Windows 에뮬레이터·Platform Tools가 필요합니다. Android SDK 라이선스는 빌드 이미지 생성 중 수락합니다.

## 시작하기

레포 루트 PowerShell에서 실행합니다.

```powershell
# 웹·DB 시작
docker compose -f compose.dev.yaml up -d --build

# 최초 또는 빌드 도구 변경 시
docker compose -f compose.dev.yaml build pleos

# Windows 에뮬레이터 실행 (이미 실행 중이면 생략)
.\infra\pleos\run-windows.ps1 -Gpu host

# Docker에서 APK 빌드하고 Windows 에뮬레이터에 설치·실행
.\infra\pleos\install-windows.ps1
```

설치 스크립트는 `adb reverse tcp:3000 tcp:3000`으로 에뮬레이터의 `127.0.0.1:3000`을 Windows 개발 서버에 연결합니다. ADB 서버를 외부로 공개하거나 Docker에 USB를 전달하지 않습니다. 기기가 여러 개라면 `-Serial emulator-5554`로 선택합니다.

`주행 중에는 사용할 수 없습니다`가 표시되면 [Virtual Console](http://127.0.0.1:8088/)을 열고 Motion에서 기어를 Park, 속도를 0으로 설정합니다. 현재 이미지는 콘솔 세션이 활성화되어야 차량 시뮬레이션 브리지가 준비됩니다. 진단 화면에 주행 중 사용 가능 선언을 추가해 제한을 해제하지 않습니다.

```powershell
# 빌드만 수행
.\infra\pleos\gradle.ps1 :app:assembleDebug

# 이미 빌드한 APK 설치
.\infra\pleos\install-windows.ps1 -SkipBuild

# Android Lint 및 API 계약 생성
.\infra\pleos\gradle.ps1 :app:lintDebug
.\infra\pleos\gradle.ps1 :contracts:openApiGenerate

# 같은 작업을 Compose로 직접 실행
docker compose -f compose.dev.yaml run --rm --no-deps pleos :app:assembleDebug
```

APK는 `apps/pleos/app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다. Gradle 캐시와 debug 서명 키(`/root/.android`)는 Docker volume에 보관하고 APK·검증 보고서는 워크스페이스에 생성합니다. 디버그 패키지는 `org.eowoo.routy.pleos.debug`, release 패키지는 `org.eowoo.routy.pleos`입니다. `down -v`로 서명 키 볼륨까지 지우면 기존 설치본과 서명이 달라지므로 보존해야 합니다.

## API 계약과 서버 주소

웹과 차량 앱은 `packages/contracts/openapi/routy.yaml`을 공통 원본으로 사용합니다. 차량 앱 빌드의 `:contracts:openApiGenerate`가 Kotlin HTTP 클라이언트와 모델을 `packages/contracts/build/generated/openapi`에 생성하고 `:contracts` JVM 모듈로 컴파일합니다. 생성 코드를 직접 수정하지 않습니다.

서버 주소는 Gradle의 `routyApiBaseUrl` 또는 Compose의 `ROUTY_API_BASE_URL`로 지정합니다. **차량 앱에는 `/api`를 제외한 origin을 지정**합니다. `/api/health` 등 경로는 생성된 클라이언트가 관리합니다. 스마트폰 앱의 `NEXT_PUBLIC_API_BASE_URL`은 `/api`를 포함하므로 구분해야 합니다.

```powershell
docker compose -f compose.dev.yaml run --rm --no-deps -e ROUTY_API_BASE_URL=https://routy.eowoo.org pleos :app:assembleDebug
```

debug만 `127.0.0.1`에 대한 HTTP 접속을 허용하며, release는 HTTPS만 허용합니다. debug 기본 주소는 에뮬레이터 개발용이고, 독립 release 빌드의 기본 주소는 `https://routy.eowoo.org`입니다. 명시적으로 재정의한 release 주소도 HTTPS origin 검사를 통과해야 합니다. 도메인의 실제 접속에는 별도의 서버 배포와 DNS 및 인증서 설정이 필요합니다.

```powershell
# .env.prod의 서버 설정과 함께 ROUTY_API_BASE_URL=https://routy.eowoo.org 설정
docker compose --env-file .env.prod -f compose.prod.yaml build pleos
docker compose --env-file .env.prod -f compose.prod.yaml run --rm --no-deps pleos :app:assembleRelease
```

위 Gradle release 결과는 서명되지 않은 APK입니다. 공모전 제출용 서명된 APK는 루트 [README의 제출 안내](../../README.md#공모전-제출-및-실행-안내)의 `package-submission.sh` 명령으로 생성합니다. 전용 키는 `.local-signing/`에 보존하며 저장소에는 포함하지 않습니다. 운영 웹·DB 컨테이너에는 빌드 도구나 앱 소스를 마운트하지 않습니다.

## 버전과 현재 범위

주행 평가 모듈과 AAOS 속도 입력 진단이 추가되었습니다. 구현 범위와 테스트는 [주행 평가 엔진](driving-evaluation.md)을 참고하세요. 아래 초기 구성 검증은 2026-11-13 시점의 기록입니다.

- JDK 21, Gradle 8.13, Android Gradle Plugin 8.13.2, Kotlin 2.2.21.
- compileSdk 36, targetSdk 34, minSdk 28. 빌드 플랫폼과 에뮬레이터의 Android API는 서로 다를 수 있습니다.
- Jetpack Compose BOM 2025.10.01, Pleos Vehicle SDK 2.3.2, OpenAPI Generator 7.17.0. 버전은 `apps/pleos/gradle/libs.versions.toml`에서 관리합니다.
- 공식 Maven에 공개된 Vehicle 2.3.2를 사용합니다. 문서 일부의 2.3.1 예제와 차이가 있으므로 실제 의존성은 버전 카탈로그를 기준으로 확인합니다.
- 진단 화면에서 서버 `/api/health` 호출과 Vehicle SDK 초기화 요청 결과를 확인합니다. SDK 초기화 요청 성공은 차량 속성 조회·구독의 정상 동작을 의미하지 않습니다.
- 차량 속성 권한, 위치 수집, 내비게이션, 주행 중 사용 가능 선언은 기능 구현 시 추가합니다. 현재 진단 화면은 주행 중 사용 가능한 내비게이션 앱으로 선언하지 않았습니다.
- 기존 이미지에서 관찰된 Pleos 네이티브 서비스 오류는 앱 빌드 성공과 별도로 추적해야 합니다.

Windows 최신 Android CLI가 앱 제어 정책으로 차단되는 현상이 있어 호스트 SDK Manager에 의존하지 않습니다. 기본 빌드는 Linux 컨테이너에서 실행하며 Windows 보안 설정을 변경하지 않습니다.

## 초기 구성 검증 (2026-11-13)

- Docker에서 APK 생성과 Kotlin 계약 생성·컴파일을 통과했습니다.
- Android Lint는 오류 0건입니다. 버전 업데이트 안내, targetSdk 34 및 백업 설정 관련 경고 13건은 보고서에 남아 있습니다.
- Windows Pleos API 34 에뮬레이터에 설치·실행하고, 생성된 Kotlin 클라이언트의 `/api/health` 응답 역직렬화와 서버 연결 성공 로그를 확인했습니다.
- P 기어·속도 0 상태에서 진단 화면과 서버 연결 성공 표시를 화면 캡처로 확인했습니다.
- 빌드 컨테이너를 재생성한 뒤 캐시 재사용과 기존 앱에 대한 업데이트 설치 성공을 확인했습니다. debug 서명 키는 Docker volume에서 유지됩니다.
- Vehicle SDK 초기화 요청은 성공했으며, 개별 차량 속성 조회·구독 검증과 release 서명·배포는 포함하지 않았습니다.

## 공식 참고 자료

- [Pleos 개발 시작하기](https://document.pleos.ai/docs/pleos-only/getting-started)
- [공식 Vehicle SDK 샘플](https://github.com/Pleos-Playground/pleos-sample-vehicle-app)
- [Maps/Navigation 개발 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/navigation-app-development-guide)
- [AGP 8.13 호환성](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
- [OpenAPI Kotlin 생성기](https://openapi-generator.tech/docs/generators/kotlin/)

사용자 홈·계정 연결과 debug 전용 진단 화면 분리는 [사용자 화면과 차량 연결](user-experience.md)을 참고하세요.
