# 팀과 서비스 식별자

- 개발 팀 한글명: **어쩌다우리**
- 개발 팀 영문명: **EoWoo**
- 공식 도메인: **eowoo.org**
- 운영 웹 주소와 차량 API origin: `https://routy.eowoo.org`
- 모바일 앱 API 주소: `https://routy.eowoo.org/api`
- 서비스명: **루티 / Routy**

공통 원본은 `packages/contracts/identity.json`입니다. 웹 메타데이터와 하단 팀 표시, Capacitor 앱 ID, 차량 앱 ID와 팀 문자열 및 release 기본 주소가 이 파일을 사용합니다. OpenAPI 연락처, 패키지 메타데이터와 운영 환경 예제도 같은 팀과 주소를 표기합니다. 제삼자 SDK와 글꼴 라이선스, 참고한 외부 저장소 주소는 실제 출처를 유지합니다.

`team.website`는 팀 홈페이지 `https://eowoo.org`, `service.webOrigin`은 루티 서비스 `https://routy.eowoo.org`입니다. 웹 메타데이터의 기준 주소와 차량 release 기본 API는 서비스 주소를 사용합니다. 스마트폰 Android와 iOS의 GitHub Actions 빌드는 `https://routy.eowoo.org/api`를 정적 웹 자원에 포함합니다. 앱 패키지명은 변경하지 않습니다.

## 패키지

| 대상 | 식별자 |
| --- | --- |
| 스마트폰 Capacitor 앱 | `org.eowoo.routy` |
| Pleos release 앱 및 코드 namespace | `org.eowoo.routy.pleos` |
| Pleos debug 앱 | `org.eowoo.routy.pleos.debug` |
| 평가 엔진 코드 | `org.eowoo.routy.evaluation` |
| 생성 Kotlin API 클라이언트 | `org.eowoo.routy.contracts` |

Kotlin 소스는 `src/<변형>/kotlin/org/eowoo/routy/`에 있습니다. 실행 및 자동 테스트 스크립트도 새 패키지를 사용합니다. Kotlin API 생성 작업은 출력 경로가 모듈 build 폴더 안인지 검사한 뒤 이전 생성물을 제거하므로 이전 namespace 클래스가 함께 컴파일되지 않습니다.

## 개발 환경과 운영 환경

개발 Compose와 debug APK는 기존 loopback 주소를 사용합니다. 서버가 배포되지 않은 도메인으로 개발 요청을 전환하지 않습니다. 운영 설정 예제는 루트 `.env.prod.example`, 모바일 앱 빌드 예제는 `apps/web/.env.production.example`입니다.

독립 release 빌드의 기본 API 주소는 `https://routy.eowoo.org`이며 `-ProutyApiBaseUrl`로 재정의할 수 있습니다. 개발 Compose는 debug 개발 주소를 명시적으로 전달하므로 그 환경에서 release를 빌드할 때는 운영 origin을 전달해야 합니다. 운영 도메인 설정은 DNS, 인증서 발급이나 실제 서버 배포를 수행하지 않습니다.

## 기존 설치와 기록

이번 패키지 변경은 Android에서 별도 앱으로 취급됩니다. 기존 설치를 덮어쓰거나 제거하지 않으며, 기존 앱의 내부 파일과 설치 인증 키가 새 앱으로 자동 이동하지 않습니다. 새 앱은 새 설치로 등록되므로 기존 계정 연결과 서버 기록을 계속 사용하려면 별도의 데이터 이전 절차가 필요합니다. 현재 작업은 기존 설치, 주행 원본과 서버 DB를 삭제하거나 계정 연결을 변경하지 않습니다.

## 팀 식별자 변경 당시 검증 (2026-11-15)

아래는 운영 주소를 하위 도메인으로 변경하기 전의 검증 기록입니다. 현재 서비스 주소는 `https://routy.eowoo.org`이며, 팀 홈페이지는 `https://eowoo.org`로 유지합니다.

- 새 namespace에서 엔진 167개와 차량 앱 40개, 총 Kotlin 단위 테스트 207개 통과. Python 실행 도구 회귀 테스트 70개와 웹 기록/API/복습 검증 18개 통과.
- 웹 타입 검사와 ESLint 통과. 실제 개발 서버가 반환한 HTML에서 팀 한글명과 영문명, 홈페이지 링크 및 author/publisher 메타데이터를 확인했습니다.
- Capacitor CLI가 새 공통 설정을 읽는 것을 확인했고 앱 ID는 `org.eowoo.routy`입니다. `NEXT_PUBLIC_API_BASE_URL=https://eowoo.org/api`로 모바일 정적 빌드가 통과했으며 출력 HTML과 JavaScript의 팀 표시 및 API 주소를 검사했습니다.
- debug APK 빌드와 Android Lint 통과(오류 0건, 기존 경고 17건). Android aapt로 `org.eowoo.routy.pleos.debug`와 새 DrivingActivity 진입점을 확인했고, 최종 DEX에 이전 프로젝트 namespace가 남지 않은 것을 검사했습니다.
- release manifest의 `org.eowoo.routy.pleos`와 기본 BuildConfig API 주소 `https://eowoo.org`를 확인했습니다. 새 JVM mainClass를 통한 고정 시나리오 34개도 통과했습니다.
- Kotlin API 생성 결과는 `org.eowoo.routy.contracts`이며 기본 서버는 `https://eowoo.org`입니다. 이전 생성 패키지 트리는 정리됐고 프로젝트 소스에서 이전 소유 namespace 참조가 없는 것을 확인했습니다.

APK는 기존과 동일한 `apps/pleos/app/build/outputs/apk/debug/app-debug.apk`, 모바일 정적 출력은 `apps/web/out/`입니다. 이번 검증은 새 식별자의 빌드와 로컬 서비스 검증이며 새 도메인으로의 배포나 기존 설치 데이터의 이전을 수행한 결과는 아닙니다.
