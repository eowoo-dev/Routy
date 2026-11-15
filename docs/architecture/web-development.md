# 웹 개발 환경

## 기준

- Next.js 화면과 서버 API를 `apps/web`에서 함께 관리합니다.
- Tuti `151a60acc4c841da94d50d2ed49094f446e9698d`의 의존성, 디렉터리 구분, Emotion SSR 처리, API 주소 처리 및 앱 빌드 분기를 참고했습니다.
- Node 26.12.1, pnpm 11.2.2를 Docker 안에서 사용합니다. 호스트 Node는 변경하지 않습니다.
- `compose.dev.yaml`과 `compose.prod.yaml`은 독립된 설정입니다. 공통 Compose나 override 병합은 사용하지 않습니다.
- Pleos 에뮬레이터는 Windows에서 직접 실행합니다.

## 개발 시작

레포 루트에서 실행합니다.

```powershell
# 최초 설치 또는 의존성 변경 시: lockfile 생성·갱신
docker compose -f compose.dev.yaml run --rm --no-deps web pnpm install --no-frozen-lockfile

# API 계약 타입 생성
docker compose -f compose.dev.yaml run --rm --no-deps web pnpm contracts:generate

# 개발 서버와 PostgreSQL 시작
docker compose -f compose.dev.yaml up -d --build
docker compose -f compose.dev.yaml logs -f web
```

- 화면: `http://localhost:3000`
- API 프로세스 상태: `http://localhost:3000/api/health`
- PostgreSQL: 호스트에서는 `localhost:5433`, 컨테이너에서는 `db:5432`
- 개발 DB 계정: `routy` / `routy_local`, 데이터베이스: `routy`
- 포트 변경은 `--env-file .env.dev` 옵션과 `.env.dev.example`을 참고합니다.

소스는 bind mount하고 Linux용 node_modules와 Next.js 캐시는 Docker volume에 보관합니다. Windows 파일 변경 감지는 polling을 사용합니다. 개발 서버 시작 시 lockfile을 고정한 설치를 수행합니다.

```powershell
docker compose -f compose.dev.yaml exec web node --version
docker compose -f compose.dev.yaml exec web pnpm lint
docker compose -f compose.dev.yaml exec web pnpm typecheck
docker compose -f compose.dev.yaml exec web pnpm db:validate
docker compose -f compose.dev.yaml stop
```

`down`도 DB 볼륨을 보존합니다. 데이터를 유지하려면 `down -v`를 사용하지 마세요.

## 서버 배포용 빌드

```powershell
Copy-Item .env.prod.example .env.prod
# .env.prod에 URL-safe 문자로 구성한 충분히 긴 POSTGRES_PASSWORD를 입력합니다.
docker compose --env-file .env.prod -f compose.prod.yaml up -d --build
```

운영 설정은 소스를 마운트하지 않고 Next.js standalone 결과를 비관리자 계정으로 실행합니다. PostgreSQL 포트는 호스트에 공개하지 않고, 웹 포트는 localhost에만 바인딩합니다. 실제 외부 서비스 시 HTTPS 역방향 프록시·도메인·인증·CORS 정책은 배포 환경에 맞게 추가해야 합니다.

개발과 운영은 Compose 프로젝트 이름과 DB 볼륨이 분리되어 있습니다. 동시에 실행할 때는 운영의 `WEB_PORT`를 다른 값으로 설정합니다.

## Capacitor 앱 빌드

서버의 실제 HTTPS API 주소를 빌드 환경에 지정합니다.

```powershell
docker compose -f compose.dev.yaml exec -e NEXT_PUBLIC_API_BASE_URL=https://eowoo.org/api web pnpm build:app
```

운영 도메인은 `eowoo.org`이며, 실제 서버 배포와 DNS 및 HTTPS 설정 후 사용합니다. 빌드 스크립트는 `.app-build`에 소스를 복사하면서 `src/app/api`, `src/app/admin`, `src/server`, `src/generated`, `src/proxy.ts`를 제외합니다. `ROUTY_TARGET=app`으로 정적 export를 수행해 `apps/web/out`을 생성하고 임시 디렉터리를 정리합니다. 원본 서버 소스는 변경하지 않습니다.

웹은 기본 `/api`, 모바일은 `NEXT_PUBLIC_API_BASE_URL`로 요청합니다. 앱에 포함되는 `NEXT_PUBLIC_*` 값에는 비밀 키를 넣지 않습니다. Capacitor의 Android·iOS 네이티브 프로젝트와 배포 Actions는 후속 작업입니다. 스마트폰 앱 ID는 `org.eowoo.routy`이며 개발 팀은 어쩌다우리(EoWoo)입니다.

## API 계약과 DB

- API 계약 원본: `packages/contracts/openapi/routy.yaml`
- TypeScript 생성: `pnpm contracts:generate`
- 공유 타입: `@routy/contracts`
- Kotlin 클라이언트는 Pleos Gradle의 `:contracts:openApiGenerate`로 생성합니다. [차량용 앱 개발 환경](pleos-development.md)을 참고하세요.
- `/api/health`는 API 프로세스의 생존 여부만 확인하며 DB 연결 상태를 의미하지 않습니다.
- Prisma는 PostgreSQL에 계정·차량 연결·주행 기록 모델과 마이그레이션을 제공합니다. 모델을 변경한 뒤 `pnpm --filter @routy/web db:generate`와 `db:migrate`를 실행합니다.

## 코드 구성 원칙

- TypeScript strict, 2칸 들여쓰기, 큰따옴표와 세미콜론을 사용합니다.
- 컴포넌트는 PascalCase, 훅·함수·유틸리티는 camelCase를 사용합니다.
- 기능별 코드는 `src/features`, 서버 전용 코드는 `src/server`, 공통 코드는 `src/shared`, 상태는 `src/store`에 둡니다.
- 서버 전용 진입점에는 `server-only`를 사용합니다.
- UI는 Emotion, 서버 데이터 캐시는 TanStack Query, 클라이언트 상태는 Zustand를 기준으로 합니다.
- Tuti의 도메인 기능·화면·계정 설정을 복사하지 않고 개발 기반만 가져왔습니다.

## 초기 구성 검증

- Docker의 Node 26.12.1에서 의존성 설치, API 타입 생성, ESLint, TypeScript 검사와 Prisma 스키마 검증을 통과했습니다.
- 서버용 standalone 빌드와 Capacitor 정적 빌드를 검증했습니다. 정적 결과에 API 경로가 없고 원본 서버 소스가 유지되는 것도 확인했습니다.
- 정적 빌드 검증에는 `https://routy.test/api`를 사용했습니다. 실제 API 서버 주소는 아직 설정하지 않았습니다.
- 개발 서버의 `/`와 `/api/health`가 HTTP 200을 반환하고, 개발 PostgreSQL의 쿼리가 정상 실행되는 것을 확인했습니다.
- 운영 Docker 이미지를 빌드하고 비관리자 `node` 계정으로 실행하여 `/`와 `/api/health`의 HTTP 200 응답을 확인했습니다. 검증용 운영 컨테이너는 종료했습니다.

참고: [Tuti 저장소](https://github.com/noon-maru/Tuti/tree/151a60acc4c841da94d50d2ed49094f446e9698d)
