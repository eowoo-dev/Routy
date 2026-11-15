# 회원 탈퇴와 개인정보처리방침

## 사용자 경로

- 개인정보처리방침: `https://routy.eowoo.org/privacy`
- 앱 내 및 앱 외부 회원 탈퇴: `https://routy.eowoo.org/account/delete`
- 로그인 후 상단 계정 이름과 모든 화면의 하단 링크에서 접근합니다. 가입 화면에도 처리방침을 연결합니다.
- 외부 브라우저에서 앱 설치 없이 로그인하고 같은 탈퇴 절차를 이용할 수 있습니다. 로그인할 수 없는 이용자는 공개된 문의 이메일로 연락할 수 있습니다.

탈퇴 화면은 현재 비밀번호와 삭제 범위 동의를 요구하며 되돌릴 수 없음을 명확히 안내합니다. 비밀번호 확인 실패는 403으로 처리하므로 앱의 정상 세션을 불필요하게 지우지 않습니다. 동일 계정의 시도는 15분당 5회로 제한합니다.

## 실제 삭제 범위

`POST /api/account/delete`는 요청에서 계정 ID를 받지 않고 로그인 세션의 계정만 처리합니다. 브라우저는 기존 쿠키와 출처 검사를, 앱은 허용된 Origin과 Bearer 세션을 사용합니다.

하나의 DB 트랜잭션에서 다음을 처리합니다.

1. 계정별 잠금 후 연결된 차량 설치를 잠급니다.
2. 연결 차량의 완료된 기록, 상세 파일 조각, 미완료 업로드 조각과 연결 코드를 삭제합니다.
3. 차량 서버 등록을 삭제하고 이전 설치 ID의 해시를 차단 목록에 등록합니다.
4. 계정별 요청 제한 정보와 계정 및 모든 로그인 세션을 삭제합니다.
5. 백업 복구 후 재삭제를 위한 계정 ID 해시와 삭제 시각을 최대 90일 동안 보관합니다.

다른 계정이나 연결되지 않은 게스트 차량의 데이터는 삭제하지 않습니다. 차량 등록, 업로드 및 연결 처리도 같은 잠금 순서를 사용하고 삭제 후 설치 활성 여부를 다시 확인합니다. 오래된 인증으로 설치를 재등록하거나 차량의 기존 outbox를 재업로드할 수 없습니다. 단순히 ownerId를 비우고 기존 인증을 유지하는 방식이 아닙니다.

`RevokedInstallation`에는 임의 설치 ID의 단방향 해시만 보관하며 계정 연결, 이메일, 삭제 시각이나 주행 내용을 포함하지 않습니다. 동일 설치의 재전송을 막기 위해 서비스 종료 때까지 유지합니다. `AccountErasure`는 계정 ID 해시와 삭제 및 만료 시각만 보관합니다.

차량 기기에 저장된 원본과 이용자가 외부로 내보낸 사본은 서버가 원격 삭제하지 않습니다. 탈퇴 화면에서 별도 삭제 및 차량 앱 초기화를 안내합니다. 스마트폰 앱은 성공 응답 후 보안 저장소의 세션 토큰을 제거합니다. 다음 로그인 세션 조회에서도 삭제 계정은 복원되지 않습니다.

## 백업 복구 절차

운영 DB에서 삭제했다고 이전 NAS 스냅샷이 즉시 사라지는 것은 아닙니다. 삭제 원장과 설치 차단 목록을 DB 백업과 별도로 보관하고, 복구 시 외부 서비스를 닫은 상태에서 최신 원장을 재적용해야 합니다. 복구할 백업의 생성 시각보다 나중의 탈퇴 기록이 포함되어 있어야 합니다.

개발 환경에서 내보내는 예:

```sh
mkdir -p .ops-backups
docker compose -f compose.dev.yaml exec -T web pnpm --filter @routy/web exec tsx scripts/account-erasures.ts export > .ops-backups/erasures.json
```

운영 NAS에서 내보내는 예:

```sh
mkdir -p .ops-backups
docker compose --env-file .env.prod -f compose.prod.yaml run --rm --no-deps migrate pnpm --filter @routy/web exec tsx scripts/account-erasures.ts export > .ops-backups/erasures.json
```

위 예시는 NAS 등의 POSIX 셸 기준입니다. 원장에는 비밀번호나 원본 계정 ID, 이메일 및 주행 자료가 들어가지 않습니다. 원장 파일도 보호되는 별도 경로에 보관해야 하며 저장소의 `.ops-backups/`는 Git에서 제외합니다. DB와 함께 과거 시점으로 복원되지 않는 별도 위치에 최신 원장을 복제합니다. `pnpm run`의 안내 문구가 JSON에 섞이지 않도록 위의 `exec tsx` 형태를 사용합니다.

DB 백업을 복원한 뒤 최신 원장을 적용하는 운영 명령:

```sh
cat .ops-backups/erasures.json | docker compose --env-file .env.prod -f compose.prod.yaml run --rm --no-deps -T migrate pnpm --filter @routy/web exec tsx scripts/account-erasures.ts apply
```

이 명령은 삭제 대상 계정과 차량 자료를 실제로 삭제하므로, 복구 작업 중에만 의도한 DB를 대상으로 실행합니다. 검증 완료 후 웹을 다시 공개합니다. 별도 원장 내보내기와 NAS 스냅샷 만료 스케줄은 운영자가 구성해야 하며, 이 커밋이 NAS 작업을 자동 등록하지는 않습니다.

원장을 내보낼 때 만료된 계정 삭제 이력도 제거합니다. 별도 내보내기 주기와 관계없이 아래 명령을 NAS 작업 스케줄러에서 매일 실행하고, 외부 원장 파일도 최신본으로 교체하여 90일이 지난 계정 삭제 이력을 남기지 않습니다. 설치 차단 해시는 유지합니다.

```sh
docker compose --env-file .env.prod -f compose.prod.yaml run --rm --no-deps migrate pnpm --filter @routy/web exec tsx scripts/account-erasures.ts prune
```

## 처리방침과 운영 설정

- 운영자: 어쩌다우리(EoWoo)
- 담당자: 정연한 / `yt4307@gmail.com`
- NAS 설치 국가: 대한민국
- 계정 및 연결된 주행 기록: 탈퇴 시 운영 DB에서 삭제
- DB 백업: 생성일로부터 최대 90일
- 운영자가 관리하는 접속 로그: 최대 365일

위 기간은 운영자가 선택한 정책입니다. NAS의 실제 백업, 스냅샷 및 접속 로그 삭제 설정도 이에 맞춰야 합니다. 현재 애플리케이션에는 NAS 백업이나 로그 보관 스케줄을 관리하는 권한과 기능이 없습니다. 처리방침을 게시하기 전에 운영자가 설정을 확인해야 합니다.

Cloudflare 글로벌 네트워크의 통신 처리를 국내 NAS 저장과 구분해서 안내합니다. 제공자에 적용되는 계약, 처리 국가와 로그 조건은 계정의 실제 설정에 맞춰 확인해야 하며, 한국 NAS라는 이유로 국외 처리가 없다고 설명하지 않습니다. 법적 고지와 스토어의 개인정보 설문은 실제 운영 및 SDK 사용 현황과 함께 최종 검토해야 합니다.

## 배포와 검증

```sh
git pull --ff-only
docker compose --env-file .env.prod -f compose.prod.yaml up -d --build
```

운영 Compose의 migrate 서비스가 `RevokedInstallation`, `AccountErasure` 테이블을 생성합니다. 별도 비밀 환경변수는 필요하지 않습니다. 기존 앱의 서명과 패키지명은 바뀌지 않으며, 앱 내 새 링크와 화면을 사용하려면 최신 앱으로 빌드 및 업데이트해야 합니다.

```sh
pnpm --filter @routy/web test:account-deletion
pnpm --filter @routy/web test:mobile-auth
```

새 자동 테스트는 브라우저와 두 앱 Origin의 탈퇴, 전체 데이터 및 미완료 조각 삭제, 세션 폐기, 예전 차량 재등록 차단, 비밀번호와 동의 및 출처 검사, 시도 제한, 동시 업로드 및 연결, 다른 계정과 게스트 차량 보호, 백업 재삭제 도구와 공개 URL을 검증합니다. 테스트가 만든 임시 계정만 삭제하며 실제 사용자 계정을 탈퇴시키지 않습니다.

참고: [Google 계정 삭제 요구사항](https://support.google.com/googleplay/android-developer/answer/13327111?hl=en), [Apple 계정 삭제 안내](https://developer.apple.com/support/offering-account-deletion-in-your-app/), [개인정보보호위원회 작성지침](https://www.privacy.go.kr/front/bbs/bbsView.do?bbsNo=BBSMSTR_000000000049&bbscttNo=20885).
