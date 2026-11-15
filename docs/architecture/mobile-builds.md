# 웹과 스마트폰 앱 빌드

저장소는 `https://github.com/eowoo-dev/Routy`입니다. `Web and mobile builds` 워크플로는 main push, 관련 pull request 및 수동 실행을 지원합니다.

| 작업 | 실행 환경 | 결과 |
| --- | --- | --- |
| Next.js server | Ubuntu 24.04, Node 26.12.1 | standalone 서버 압축 파일 |
| Capacitor web assets | Ubuntu 24.04, Node 26.12.1 | API와 서버 코드를 제외한 정적 웹 |
| Android APK and AAB | Ubuntu 24.04, JDK 21 | debug APK, unsigned release APK와 AAB |
| iOS simulator and unsigned device | macOS 26의 Xcode | simulator 앱과 서명하지 않은 기기용 앱 ZIP |

pnpm은 11.2.2, Capacitor core/CLI/Android/iOS는 8.6.0으로 고정하고 lockfile 설치를 사용합니다. API 주소는 `https://eowoo.org/api`, 앱 ID는 `org.eowoo.routy`입니다. 차량용 `apps/pleos`와 스마트폰용 `apps/web/android`는 별도 앱입니다.

## 생성물과 소스

`apps/web/android`와 `apps/web/ios`는 버전 관리합니다. iOS는 Swift Package Manager를 사용하며 생성된 로컬 플러그인 참조는 각 빌드의 `cap sync ios`가 갱신합니다. 정적 출력, 복사된 웹 자원, 생성된 Capacitor 설정과 Cordova 연결 폴더, 서명 키 및 빌드 결과는 커밋하지 않습니다.

Actions는 정적 웹을 한 번 빌드해 Android와 iOS 작업에 전달합니다. 각 플랫폼은 같은 자원을 동기화한 뒤 네이티브 빌드를 수행합니다. 결과는 해당 실행의 Artifacts에서 7일 동안 내려받을 수 있습니다.

## 서명과 실제 이용 범위

Android debug APK는 테스트 설치용입니다. release APK와 AAB 및 iOS 기기용 ZIP은 서명하지 않은 빌드 검증 결과이며 스토어 제출용 서명 결과가 아닙니다. iOS 검증에는 Apple 계정이나 개인 Mac이 필요하지 않습니다. 실제 기기 배포와 App Store 제출에는 별도의 인증서와 프로비저닝 구성이 필요합니다.

빌드 성공은 운영 서버 배포나 모바일 로그인 완성을 의미하지 않습니다. eowoo.org의 서버와 HTTPS 배포, Capacitor와 서버 사이의 교차 출처 인증 연결은 별도로 검증해야 합니다.

## 로컬 Android 빌드

```powershell
docker compose -f compose.dev.yaml exec -T -e NEXT_PUBLIC_API_BASE_URL=https://eowoo.org/api web pnpm --filter @routy/web build:app
docker compose -f compose.dev.yaml exec -T web pnpm --filter @routy/web exec cap sync android
```

이후 JDK 21과 Android SDK가 있는 환경에서 `apps/web/android`의 Gradle wrapper로 `assembleDebug assembleRelease bundleRelease`를 실행합니다. Windows 호스트에 Android Studio를 설치할 필요는 없습니다. iOS 빌드는 Actions의 macOS 작업에서 검증합니다.
