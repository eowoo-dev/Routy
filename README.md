# 루티 (Routy)

개발 팀: **어쩌다우리 (EoWoo)** · 공식 도메인: **eowoo.org**

운영 서비스: **https://routy.eowoo.org**

차량에서 연습하고 스마트폰에서 계획·복습하는 운전 학습 서비스입니다.

## 공모전 제출 및 실행 안내

- 제출 대상: **Pleos Connect 차량용 앱** (`org.eowoo.routy.pleos`, 버전 `0.1.0`). 스마트폰용 Android 앱과는 별도입니다.
- 실행 가능한 서명된 release APK: [apk/어쩌다우리_루티_release.apk](apk/어쩌다우리_루티_release.apk)
- 파일 무결성 확인: [apk/SHA256SUMS](apk/SHA256SUMS)
- 스마트폰 또는 노트북의 연결 및 기록 조회 화면: **https://routy.eowoo.org**

### 제출 APK 실행 방법

1. Pleos Connect 에뮬레이터를 부팅하고 Virtual Console에서 기어를 **P**, 속도를 **0**으로 설정합니다. 콘솔이 연결되어 있어야 이미지의 차량 시뮬레이션 브리지가 준비됩니다.
2. Platform Tools의 ADB로 APK를 설치하고 실행합니다. 다음 명령은 저장소 루트의 PowerShell에서 실행하며, ADB가 PATH에 등록되어 있어야 합니다.

```powershell
adb -s emulator-5554 install -r "apk/어쩌다우리_루티_release.apk"
adb -s emulator-5554 shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n org.eowoo.routy.pleos/org.eowoo.routy.pleos.feature.trip.DrivingActivity
```

3. 차량 홈의 **계정 연결**에서 연결 코드를 발급합니다. 웹에서 계정을 생성하거나 로그인한 뒤 **차량 연결**에 코드를 입력합니다. 제출 APK는 운영 서버를 사용하므로 에뮬레이터와 웹 접속 기기 모두 인터넷 연결이 필요합니다. 로컬 서버 실행이나 ADB 포트 역방향 연결은 필요하지 않습니다.
4. 주차 상태에서 차량 앱의 **자동 기록** 설정을 켭니다. 초기값은 꺼짐입니다. 필요한 차량 속도 권한을 허용한 후, 앱이 실행된 상태에서 D단으로 전환하면 기록을 시작합니다. R단과 N단에서는 유지하며 P단을 10초 유지하면 평가 및 로컬 저장을 수행하고, 연결된 계정으로 업로드합니다. 자세한 기록은 웹의 **주행 기록**에서 확인합니다.

### 테스트 환경과 구현 범위

| 항목 | 개발 및 검증 환경 |
| --- | --- |
| Pleos Connect 에뮬레이터 이미지 | **2.1.0**, Android Automotive OS API **34**, x86_64 |
| 화면 규격 | **2560 × 1440 px**, 가로 화면, 밀도 **160 dpi** |
| 호스트 및 실행기 | Windows 11, Android Emulator **37.2.13**, WHPX, 호스트 GPU |
| 에뮬레이터 할당 | CPU 4개, RAM 4GB, cold boot |
| 차량 SDK | Pleos Vehicle SDK **2.3.2** |
| 앱 빌드 | JDK 21, Gradle 8.13, compileSdk 36, targetSdk 34, minSdk 28 |
| 서버 | Next.js / PostgreSQL, HTTPS `https://routy.eowoo.org` |

제출 release APK에는 사용자 화면, 계정 연결, SDK 기반 주행 수집, 평가, 장시간 기록의 분할 저장과 복구, 업로드 기능이 포함됩니다. D단에서는 제한된 기록 화면을 사용하고 계정 연결과 상세 조작은 주차 상태에서 수행합니다. 서버에 연결할 수 없는 동안 완료 기록은 로컬에 보관하고 이후 전송을 재시도합니다.

**차선, 신호등, 앞차 정보는 실제 센서로 탐지한 데이터가 아닙니다.** 해당 환경 평가 엔진과 독립 시나리오 실행기는 소스에 구현되어 있으나, Virtual Console 확장의 합성 입력과 개발 진단 화면은 debug 전용입니다. 제출 release APK에서는 이 입력 경로를 사용하지 않습니다. 입력이 없거나 신뢰할 수 없는 항목은 미평가로 처리합니다. 실도로 정확도와 실제 차량 호환성은 검증 범위에 포함되지 않습니다.

### 소스 빌드 및 자동 테스트

Docker Desktop의 Linux 컨테이너 모드로 차량 앱을 빌드할 수 있습니다. Android Studio 및 호스트 JDK는 필요하지 않습니다.

```powershell
docker compose -f compose.dev.yaml build pleos
# 제출용 release 생성 및 서명 검증
docker compose -f compose.dev.yaml run --rm --no-deps --entrypoint sh pleos /workspace/infra/pleos/package-submission.sh
# Android 에뮬레이터 없이 주행 평가 엔진 테스트
.\infra\pleos\test-scenarios.ps1
```

제출 패키징은 운영 HTTPS 주소를 명시해 빌드하고, 전용 키로 서명한 후 서명과 정렬 및 debug 비활성화를 확인합니다. 처음 실행하면 전용 서명 키와 비밀번호를 Git에서 제외된 `.local-signing/`에 생성합니다. **재빌드 및 업데이트 설치를 위해 이 파일들을 별도 보관하고 공개하지 마세요.** 기존 제출본과 동일한 서명이 필요한 경우 원래 키를 사용해야 합니다.

개발용 웹, Virtual Console 확장, 에뮬레이터 입력 자동화는 아래 가이드와 [시나리오 자동화 가이드](docs/architecture/driving-evaluation-testing.md)를 참고하세요. 합성 환경 평가 시연에는 debug 빌드와 별도 개발용 콘솔이 필요합니다.

팀 표기와 패키지 정책은 [프로젝트 식별자 안내](docs/architecture/project-identity.md)를 참고하세요.

## 모노레포 구성

```text
Routy/
├─ apps/
│  ├─ web/                       # Next.js 스마트폰 UI + 서버 API
│  │  ├─ src/
│  │  │  ├─ app/api/             # 서버 Route Handlers
│  │  │  ├─ features/            # 기능별 UI와 클라이언트 로직
│  │  │  ├─ lib/api/             # API 클라이언트·주소 처리
│  │  │  ├─ server/              # 서버 전용 서비스·데이터 접근
│  │  │  ├─ shared/              # 프로젝트 내부 공통 코드
│  │  │  ├─ store/               # 클라이언트 상태
│  │  │  └─ styles/
│  │  ├─ public/
│  │  ├─ assets/capacitor/
│  │  ├─ prisma/migrations/
│  │  ├─ scripts/               # 서버용·앱용 빌드 등
│  │  ├─ test/
│  │  └─ distribution/          # 모바일 배포 메타데이터
│  └─ pleos/                     # Kotlin + Pleos SDK, 독립 Gradle 프로젝트
│     └─ app/src/
│        ├─ main/{kotlin,res}/
│        ├─ test/kotlin/
│        └─ androidTest/kotlin/
├─ packages/contracts/           # 서버·스마트폰·차량 간 API 계약
│  ├─ openapi/                   # 공통 API 명세 원본
│  ├─ generated/typescript/
│  ├─ build.gradle.kts           # Kotlin 클라이언트 생성·컴파일
│  └─ scripts/                   # 계약 검증·코드 생성 도구
├─ .github/workflows/            # 서버·모바일·Pleos CI 및 배포
├─ docs/architecture/
└─ infra/                        # 서버 배포·운영 설정
```

## 빌드와 통신 방향

- `apps/web`은 Tuti의 디렉터리 구성을 참고합니다. 서버 배포 시 Next.js 서버와 API를 포함하고, 모바일 배포 시 서버 전용 경로를 제외한 정적 파일을 Capacitor로 패키징합니다.
- Capacitor 초기화 시 스마트폰용 `android/`, `ios/` 프로젝트는 `apps/web/` 아래에 생성합니다. 차량용 Android 프로젝트는 `apps/pleos/`에 둡니다.
- 스마트폰 앱과 차량 앱은 같은 Next.js 서버에 HTTPS로 접속합니다. 직접 통신은 향후 확장으로 둡니다.
- API 계약은 OpenAPI를 공통 원본으로 관리하고 TypeScript와 Kotlin 코드를 생성하는 방향입니다. 언어별 모델을 따로 수작업으로 유지하지 않습니다.
- pnpm workspace는 `apps/web`, `packages/contracts`를 관리합니다. `apps/pleos`는 Gradle로 빌드합니다.
- Android·iOS 앱 빌드 및 배포는 GitHub Actions를 사용합니다. iOS 빌드는 macOS 러너에서 수행합니다.

웹은 Next.js·Emotion·TanStack Query·Zustand·Prisma·Capacitor를 기반으로 구성했습니다. `/api/health`와 OpenAPI 기반 TypeScript 계약 생성, 서버용 standalone 빌드와 앱용 정적 빌드를 제공합니다. 차량용 앱은 Kotlin·Compose·Vehicle SDK 기반 Gradle 프로젝트이며 같은 OpenAPI에서 Kotlin 클라이언트를 생성합니다. 주행 기록 DB·API와 차량 앱의 완료 기록 업로드를 제공합니다. 스마트폰용 Android와 iOS 프로젝트 및 GitHub Actions 빌드는 [모바일 빌드 안내](docs/architecture/mobile-builds.md)를 참고하세요.

## 웹 개발 시작

호스트 Node 설치 없이 Docker Desktop에서 실행합니다. Node 26.12.1과 pnpm 11.2.2를 컨테이너에서 사용합니다.

```powershell
docker compose -f compose.dev.yaml up -d --build
```

웹은 `http://localhost:3000`, API 상태 확인은 `http://localhost:3000/api/health`입니다. 최초 의존성 설치·API 계약 생성·검증·모바일 빌드·운영 실행 방법은 [웹 개발 환경 가이드](docs/architecture/web-development.md)를 참고하세요.

## 개발 환경 실행 방식

주행 평가 엔진은 `apps/pleos/driving-evaluation`의 순수 Kotlin 모듈입니다. 속도 관리·가감속·확인된 회전의 방향지시등·조향 규칙과 복합 맥락 분석을 구현했습니다. 수집과 미평가 처리는 [주행 평가 엔진 가이드](docs/architecture/driving-evaluation.md), 고정 시나리오·에뮬레이터 자동화·기록 재생·CI 실행은 [시나리오 자동화 가이드](docs/architecture/driving-evaluation-testing.md)를 참고하세요.

v0.4는 위치·지도 기반 회전/커브·차로 변경 후보 검출, 입력 품질과 이벤트 묶음, 한국어 복습 피드백을 제공합니다. 실제 지도 입력의 조건과 합성 시연 범위는 [행동 분석·학습 피드백 가이드](docs/architecture/driving-practice-analysis.md)에 정리했습니다.

차량용 앱의 빌드 도구도 Compose의 `pleos` 서비스에서 실행합니다. `docker compose -f compose.dev.yaml build pleos` 후 `.\infra\pleos\install-windows.ps1`로 Docker 빌드 → Windows 에뮬레이터 설치·실행을 진행합니다. 자세한 내용은 [차량용 앱 개발 가이드](docs/architecture/pleos-development.md)를 참고하세요.

- Pleos 에뮬레이터는 Android Studio 없이 Windows에서 직접 실행합니다. WHPX와 호스트 GPU를 사용합니다.
- Docker는 Next.js 서버·데이터베이스 등 서비스 실행에 사용합니다. 개발은 `compose.dev.yaml`, 운영은 `compose.prod.yaml`을 각각 단독으로 사용합니다.
- Pleos Docker 구성은 비교 실험 기록으로 보존하며, 기본 개발 환경에서는 실행하지 않습니다.
- Windows 실행 방법과 두 환경의 검증 결과는 [infra/pleos/README.md](infra/pleos/README.md)를 참고하세요.

완료 주행 기록의 공통 API·DB·설치 인증·오프라인 전송 대기는 [주행 기록 업로드 안내](docs/architecture/driving-evaluation.md#주행-기록-api와-서버-업로드)를 참고하세요.


주행 중 화면 제한과 독립적인 센서 수집·저장, P/D 전환과 백그라운드 자동 검증은 [주행 수집 서비스](docs/architecture/driving-service.md)를 참고하세요.
