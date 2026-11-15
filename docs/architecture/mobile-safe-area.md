# 스마트폰 앱 안전영역

## 원인과 처리

기존 주 화면에는 상단 안전영역 여백이 없었습니다. 고정된 하단 메뉴는 `env(safe-area-inset-bottom)`만 사용했고, 개인정보 및 탈퇴 화면만 별도로 상하 여백을 적용했습니다.

Capacitor 8.6의 Android SystemBars는 WebView 버전과 키보드 상태에 따라 네이티브 패딩 및 `--safe-area-inset-*` 값을 보정합니다. 구형 WebView에서는 이미 네이티브 패딩을 적용한 다음 보정 값을 0으로 전달하므로, 이 0도 유효한 값으로 사용해야 합니다. 최신 WebView와 iOS WKWebView에서는 웹 화면이 시스템 영역까지 확장되며 CSS에서 여백을 처리합니다.

`src/shared/safe-area.ts`가 Capacitor의 보정 변수를 먼저 읽고, 없으면 `env()`를 사용합니다. 루트의 `.safe-area-screen` 한 곳에서 상하좌우 패딩을 적용하고 자식 화면의 최소 높이에서는 해당 여백을 뺍니다. 개인정보 및 탈퇴 화면의 중복 패딩은 제거합니다.

고정 하단 메뉴는 뷰포트를 기준으로 배치되므로 별도로 좌우 및 하단 패딩을 적용합니다. 버튼 아래의 기본 여백 10px에 실제 안전영역을 더합니다. 페이지 맨 아래 내용도 메뉴 아래로 가려지지 않도록 기존 하단 공간과 루트의 안전영역을 함께 유지합니다. 스크롤 중 상태바와 홈 표시줄 아래에 텍스트가 비치지 않도록 시스템 영역만 배경색으로 덮습니다.

Android는 `EdgeToEdge.enable`과 `SystemBars.insetsHandling=css`를 명시하고 키보드는 `adjustResize`를 사용합니다. iOS는 `contentInset=never`로 CSS와 UIKit의 이중 여백을 피하고 어두운 상태바 글자를 사용합니다. 기기별 픽셀 높이를 제품 코드에 고정하지 않습니다.

## 검증

- 공통 정적 앱 빌드, TypeScript 및 ESLint 검사
- Android 계측 테스트: 실제 네이티브 시스템 및 키보드 inset과 WebView 컨트롤의 화면 좌표를 비교
- Android API 36 에뮬레이터: 세로, 가로, 키보드 표시를 제스처 및 3버튼 방식에서 각각 실행
- iOS: macOS CI에서 iPhone Pro와 iPad Pro 시뮬레이터에 실제 앱을 설치하고 실행 화면을 생성하여 육안 확인

Android 결과와 화면은 `routy-safe-area-android`, iOS 화면은 `routy-safe-area-ios` Actions 산출물에 저장합니다. iOS 시뮬레이터 확인은 실제 iPhone 및 iPad의 모든 버전과 키보드 조합에 대한 실기기 확인을 대신하지 않습니다.

네이티브 설정과 CSS가 함께 바뀌므로 기존 TestFlight 및 Play 빌드에는 새 앱 빌드로 반영해야 합니다.

참고: [Capacitor 8 SystemBars](https://capacitorjs.com/docs/apis/system-bars), [Capacitor 설정](https://capacitorjs.com/docs/config).
