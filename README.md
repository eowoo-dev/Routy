# Routy

차량에서 연습하고 스마트폰에서 계획·복습하는 운전 학습 서비스입니다.

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

웹은 Next.js·Emotion·TanStack Query·Zustand·Prisma·Capacitor를 기반으로 구성했습니다. `/api/health`와 OpenAPI 기반 TypeScript 계약 생성, 서버용 standalone 빌드와 앱용 정적 빌드를 제공합니다. 차량용 앱은 Kotlin·Compose·Vehicle SDK 기반 Gradle 프로젝트이며 같은 OpenAPI에서 Kotlin 클라이언트를 생성합니다. 도메인 DB 모델, 스마트폰 네이티브 프로젝트와 배포 CI는 후속 작업입니다.

## 웹 개발 시작

호스트 Node 설치 없이 Docker Desktop에서 실행합니다. Node 26.12.1과 pnpm 11.2.2를 컨테이너에서 사용합니다.

```powershell
docker compose -f compose.dev.yaml up -d --build
```

웹은 `http://localhost:3000`, API 상태 확인은 `http://localhost:3000/api/health`입니다. 최초 의존성 설치·API 계약 생성·검증·모바일 빌드·운영 실행 방법은 [웹 개발 환경 가이드](docs/architecture/web-development.md)를 참고하세요.

## 개발 환경 실행 방식

차량용 앱의 빌드 도구도 Compose의 `pleos` 서비스에서 실행합니다. `docker compose -f compose.dev.yaml build pleos` 후 `.\infra\pleos\install-windows.ps1`로 Docker 빌드 → Windows 에뮬레이터 설치·실행을 진행합니다. 자세한 내용은 [차량용 앱 개발 가이드](docs/architecture/pleos-development.md)를 참고하세요.

- Pleos 에뮬레이터는 Android Studio 없이 Windows에서 직접 실행합니다. WHPX와 호스트 GPU를 사용합니다.
- Docker는 Next.js 서버·데이터베이스 등 서비스 실행에 사용합니다. 개발은 `compose.dev.yaml`, 운영은 `compose.prod.yaml`을 각각 단독으로 사용합니다.
- Pleos Docker 구성은 비교 실험 기록으로 보존하며, 기본 개발 환경에서는 실행하지 않습니다.
- Windows 실행 방법과 두 환경의 검증 결과는 [infra/pleos/README.md](infra/pleos/README.md)를 참고하세요.
