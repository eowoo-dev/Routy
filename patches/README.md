# Next.js 개발 서버 초기 주소 탐색 보완

Windows bind mount + Docker의 `next dev --webpack` 재시작에서 일부 중첩 API가 HTML 404를 반환했습니다. 파일은 존재하지만 초기 라우트 표/타입에는 누락됐고, 같은 설치에서 주소가 잡힌 실행은 정상 동작했습니다.

`next@16.2.6.patch`는 현재 버전을 유지하면서 [공식 canary의 startWatcher](https://github.com/vercel/next.js/blob/canary/packages/next/src/server/lib/router-utils/setup-dev-bundler.ts)에서 확인한 초기 파일 스냅샷과 기존 파일 검사 방식을 CJS/ESM 배포 파일에 적용합니다. [관련 공식 이슈](https://github.com/vercel/next.js/issues/96139)도 참고할 수 있습니다. 범용 Next.js 업그레이드를 대신하는 일시적 백포트입니다.

초기 파일 목록을 수집하고 Watchpack의 일부 탐색만 끝난 상태에서 라우트 표를 공개하지 않도록 합니다. 관측 메타데이터가 아직 없어도 디스크에 존재하는 일반 라우트 파일인지 확인합니다. 초기 대기는 30초 범위로 제한합니다. pnpm의 patchedDependencies와 잠금 파일로 재현하며, 서버의 API/인증·프로덕션 라우트 의미를 바꾸지 않습니다.

`infra/services/check-api-routes.mjs`는 자격 증명 없이 보호된 중첩 API에 요청해 JSON 401을 확인합니다. HTML 404를 정상 기동으로 오인하지 않으며 CI의 서버 시작 단계에도 포함합니다. 패치 없이 재현된 실행에서는 조각 업로드가 HTML 404로 실패했으며 원본/outbox는 보존됐습니다.

Next.js 업데이트 시 공식 수정 포함 여부와 해당 검증을 확인한 후 패치를 제거합니다. 패치 생성에는 Git이 필요하지만 `pnpm install --frozen-lockfile`의 적용에는 별도 수동 수정이 필요하지 않습니다.
