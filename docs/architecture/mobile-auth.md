# 스마트폰 로그인과 차량 연결

## 인증 방식

- 브라우저: 기존 `routy_session` HttpOnly / SameSite=Lax 쿠키를 사용합니다. 운영에서는 Secure도 적용합니다. 로그인 응답에 토큰을 노출하지 않습니다.
- Android: `https://localhost` 출처에서 HTTPS API로 요청합니다.
- iOS: `capacitor://localhost` 출처에서 HTTPS API로 요청합니다.
- 앱은 로그인 또는 가입 응답의 `accessToken`을 보안 저장소에 보관하고 `Authorization: Bearer …`로 전송합니다. 쿠키는 전송하지 않습니다.
- 앱 세션은 기존 DB의 해시 저장과 30일 절대 만료를 사용합니다. 자동 갱신은 없으며 만료 시 재로그인이 필요합니다. 서버 세션 조회는 토큰을 재발급하거나 노출하지 않습니다.

`@aparajita/capacitor-secure-storage` 8.0.1은 iOS Keychain과 Android Keystore 기반 저장소를 제공합니다. iOS 저장은 iCloud 동기화를 끄고 잠금 해제 및 해당 기기에 한정합니다. API origin별로 키를 구분하며 웹의 localStorage나 Preferences로 인증 토큰을 대체 저장하지 않습니다. 보안 저장 실패 시 새 서버 세션을 폐기하도록 시도하고 로그인을 완료하지 않습니다.

## 출처와 세션 격리

Next.js Proxy가 `/api/auth/*`, `/api/account/*`에만 앱 CORS와 OPTIONS 처리를 적용합니다. 허용 출처는 위 두 주소로 고정되어 있으며 임의 출처, `null`, 유사 도메인을 허용하지 않습니다. GET 및 POST와 Content-Type 및 Authorization 헤더만 허용하며 쿠키용 교차 출처 권한은 열지 않습니다. 응답은 캐시하지 않고 Origin에 따라 구분합니다.

허용된 출처라는 사실 자체는 로그인 증명이 아닙니다. 실제 인증은 비밀번호 또는 유효한 세션 토큰으로 수행합니다. 앱 출처의 요청은 브라우저 쿠키로 인증하거나 브라우저 세션을 로그아웃할 수 없습니다. 차량 설치용 `UUID.secret` 인증과 계정 토큰도 별개입니다.

로그아웃은 서버 세션을 폐기한 뒤 보안 저장소를 비웁니다. 통신 실패 시에는 재시도할 수 있도록 기존 토큰을 유지합니다. 만료 또는 폐기된 토큰은 비우며, 오래된 요청의 401 응답이 새 로그인 토큰을 지우지 않도록 비교합니다.

## 차량 연결과 기록

앱에서 로그인하고 차량 화면의 8자리 코드를 입력한 뒤 확인 및 동의하면 기존 계정 소유권 연결 API를 호출합니다. 만료 코드와 이미 사용한 코드는 재사용할 수 없습니다. 앱을 다시 실행하면 저장된 세션을 조회하고 연결 차량 및 기록을 불러옵니다. QR의 HTTPS 링크는 브라우저에서도 사용할 수 있으며 앱 전용 QR 스캔이나 Universal Links는 이번 범위에 포함하지 않습니다.

전체 상세 평가는 앱에서 Bearer 헤더를 포함한 네이티브 파일 다운로드 후 공유 화면으로 전달합니다. 토큰을 링크의 쿼리에 넣지 않으며, 공유 작업 후 임시 파일을 정리합니다. 브라우저는 기존 쿠키 기반 다운로드를 유지합니다.

## 배포

추가 DB 마이그레이션이나 신규 비밀 환경변수는 필요하지 않습니다. NAS의 기존 설정은 아래 주소를 사용합니다.

```dotenv
ROUTY_PUBLIC_WEB_ORIGIN=https://routy.eowoo.org
ROUTY_API_BASE_URL=https://routy.eowoo.org
```

```sh
git pull --ff-only
docker compose --env-file .env.prod -f compose.prod.yaml up -d --build
```

앱은 이 커밋 이후의 Android / iOS Actions 결과로 교체합니다. 정적 빌드 API 주소는 `https://routy.eowoo.org/api`입니다. `cap sync`가 보안 저장소 플러그인을 네이티브 프로젝트에 연결하므로 웹 자원만 교체하는 것으로는 부족합니다. Cloudflare의 별도 로그인이나 대화형 챌린지가 API 및 OPTIONS를 가로막지 않도록 구성합니다.

## 자동 검증과 범위

```sh
pnpm --filter @routy/web test:mobile-auth
```

- 전송 계층: 웹 쿠키 격리, 토큰 저장 및 재실행 복원, 로그아웃과 네트워크 실패, 만료 정리, 오래된 요청 경쟁, 보안 저장 실패 및 HTTPS 강제.
- 실제 HTTP와 DB: 두 앱 출처의 preflight, 가입과 로그인, 세션 조회, 차량 연결 및 재사용 거부, 소유 차량의 기록 및 상세 파일 조회, 다른 계정 접근 거부, 토큰 만료와 폐기, 쿠키 및 차량 인증키 격리, 허용하지 않은 출처와 헤더 차단.
- GitHub Actions의 API 워크플로에서 기존 웹 기록 및 계정 연결 회귀 검사와 함께 실행합니다. 모바일 워크플로는 Android와 iOS의 플러그인 동기화 및 네이티브 빌드를 검증합니다.

HTTP 테스트는 앱의 Origin과 요청 헤더를 재현합니다. 실제 기기의 Keychain / Keystore 및 공유 화면을 조작하는 테스트를 대신하지는 않습니다.

참고: [Next.js Proxy CORS](https://nextjs.org/docs/app/api-reference/file-conventions/proxy), [보안 저장소 구현](https://github.com/aparajita/capacitor-secure-storage).
