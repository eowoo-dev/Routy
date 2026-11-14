# 주행 중 평가 수집과 화면 제한

## 수집 수명과 저장

`TripService`는 사용자가 주차 상태에서 시작한 연습의 수집기를 소유합니다. `EvaluationController`는 Activity나 ViewModel 수명과 분리되어 있으며, 서비스의 Main coroutine scope에서 수집 상태를 직렬화합니다. 홈 이동·화면 중지·Activity 재생성은 실제 주행 수집을 취소하지 않습니다. 진단용 합성 시나리오 감시만 기존처럼 진단 화면이 중지될 때 해제합니다. 사용자 홈과 진단 화면은 제한이 활성화되면 주행 화면으로 이동하고 주차용 내용을 즉시 숨깁니다.

- 사용자 홈의 **운전 연습 시작** → 간단한 주행 화면 → 주차 후 **연습 마치고 저장** 순서입니다.
- 사용자 연습은 최대 10분이며, 기존 진단 버튼의 30초 수집도 같은 서비스에서 실행됩니다.
- 명시적 시작 알림과 `specialUse` 포그라운드 서비스 유형·용도 설명을 선언했습니다. 다른 유형으로 수집 목적을 위장하지 않습니다.
- 속도 권한과 플랫폼 UX 상태·P단을 확인한 뒤 시작합니다. 중복 시작 요청은 기존 수집을 교체하지 않습니다.
- 종료 시 센서 구독을 해제하고 평가한 뒤 원본을 `files/sensor-traces/<sessionId>.json`과 호환용 `latest.json`에 원자적으로 저장합니다. 동일 세션의 평가를 기존 durable outbox에 저장한 후에만 `SAVED` 상태를 표시합니다.
- 저장 실패는 완료로 표시하지 않습니다. 업로드 성공 여부는 로컬 저장 완료와 별개이며, 기존 WorkManager 재시도·설치 인증·중복 방지 정책을 유지합니다.
- 서비스 종료 후 구독·coroutine·알림을 해제합니다. 화면 복귀가 저장 조건은 아닙니다.

현재 범위는 프로세스가 살아 있는 동안의 수집 연속성입니다. 강제 종료·프로세스 사망·재부팅 후 원시 입력 복원이나 끊긴 주행 자동 재개는 포함하지 않습니다. 중단된 세션을 완료 평가로 꾸미지 않으며, 이미 완료되어 outbox에 저장된 기록은 기존 방식으로 유지됩니다. 10분 상한과 원시 데이터 상한은 장시간 기록 정책을 대신하지 않습니다.

## 주차용·주행용 화면

`DrivingAccess`는 공식 `CarUxRestrictionsManager` 구독과 현재 기어를 함께 읽습니다. 제한 여부를 속도만으로 추정하지 않습니다. UX 서비스 미연결·기어 미확인·주행 제한·개별 제한 플래그가 있으면 설정을 닫는 보수적인 정책입니다. D단 정지 상태도 P단으로 취급하지 않습니다.

- `MainActivity`: 주차 시에만 진입하는 계정 연결·QR·연습 준비 화면. DO를 선언하지 않습니다. 앱 내부에서도 주차 확인 전에는 기능을 숨깁니다.
- `DiagnosticsActivity`: debug 전용, DO를 선언하지 않아 주행 중 플랫폼 제한을 유지합니다.
- `DrivingActivity`: 앱 런처의 단일 작업 진입점이며 주차 상태에서는 사용자 홈으로 연결합니다. Pleos 런처의 작업 단위 제한에 맞춰 주행용 화면을 작업 진입점으로 사용합니다. 이 Activity에만 `distractionOptimized=true`를 선언합니다. 주행 중에는 기록 상태와 주차 후 확인 안내 두 줄만 표시합니다. 점수·센서 수치·스크롤·QR·설정·개발 버튼을 표시하지 않습니다. 주차가 확인되면 종료·저장 또는 홈 복귀 버튼을 제공합니다.

이는 에뮬레이터에서의 제한 연동 구현입니다. 실제 Pleos Market의 앱 분류·심사 승인, 양산 차량별 적합성 확인까지 완료했다는 의미는 아닙니다.

## 자동 검증

```powershell
.\infra\pleos\test-driving-service.ps1
# 이미 최신 APK를 설치했다면:
.\infra\pleos\test-driving-service.ps1 -SkipBuild
```

Windows의 `emulator-NNNN`만 대상으로 합니다. 물리 차량은 거부합니다. 기본 Virtual Console(8088)의 다른 탭은 모두 닫고, P단·속도 0에서 시작합니다. 테스트가 자체 heartbeat 연결을 유지하며, 기존 지역/UX 정책을 끄거나 VHAL 제한을 강제 해제하지 않습니다.

테스트는 로컬 `compose.dev.yaml`의 web만 잠시 중지합니다. 실제 사용자 버튼으로 연습을 시작하고 다음을 확인합니다.

1. P단 → D단 정지 → D단 24km/h 주행 → HOME으로 Activity STOPPED → P단 복귀까지 동일한 세션과 증가하는 수신 수.
2. D단에서 플랫폼의 `requiresOptimization=true`, 제한 플래그, 기어가 실제로 관측되는지 확인.
3. 주행용 화면이 플랫폼에 차단되지 않으며 주차용 버튼을 노출하지 않는지 확인. 접근성 트리 외에도 캡처의 실제 주행 화면 바탕색을 확인해 런처의 차단 오버레이를 성공으로 오인하지 않습니다.
4. 화면 중지 구간의 원시 SDK 속도와 조향각 수신을 지시한 입력 시간대와 비교.
5. P단 복귀 후 종료 버튼으로 세션별 원본과 `PENDING` outbox가 저장되고 평가 내용이 같은지 확인.
6. 서버 복구 후 `SENT` 상태, 서버에 보존된 평가, 세션당 기록 한 건, 서비스 해제를 확인.

종료·실패·Ctrl+C 시 서버를 복구하고 기어·속도·페달·조향·방향지시등을 초기화합니다. 실패 시 테스트 소유 수집이 남아 있으면 debug 앱을 종료합니다. 기존 기록과 설치 인증 정보는 삭제하지 않습니다. 생성된 LIVE 평가 기록은 에뮬레이터 시연 기록으로 남습니다.

결과는 `apps/pleos/driving-evaluation/build/scenarios/driving-service/result.json`, 원본은 `latest.json`, 화면 증거는 `drive-restricted.png`와 `restricted-window.xml`, 화면 중지 증거는 `background-activities.txt`에 저장합니다. 설치 비밀 키는 결과에 출력하지 않습니다.

순수 회귀 검증은 기존 CI의 `test_*.py` 탐색에 포함됩니다. 실제 Pleos 에뮬레이터 통합 실행은 Windows 환경에서 별도로 실행합니다. 일반 CI에서 에뮬레이터 통합 검증을 수행한 것처럼 보고하지 않습니다.

## 근거 문서

- [Pleos 주행 제한 개발 가이드](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide)
- [Pleos 지역별 주행 제한](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy)
- [Pleos 앱 분류](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/app-category)
- [Android foreground service 유형](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)


## 확인한 결과

- Android 앱 단위 검증 14개 통과(주차/제한 경계 7개 포함), debug APK 생성과 Android Lint 오류 0건.
- Python 회귀 검증 30개 통과(새 연속성·화면 판독 검증 12개 포함).
- 에뮬레이터 수신 수: P단 13 → D단 정지 31 → 주행 114 → Activity 중지 후 153 → P 복귀 178. 전체 과정에서 동일 세션 유지.
- D단의 실제 최소 화면 표시·주차용 버튼 비노출, 화면 중지 중 속도/조향각 수신, 오프라인 저장, 서버 복구 후 원본 평가 일치·기록 한 건·서비스 해제 확인.
- 초기 화면 검증에서는 접근성 트리 뒤의 Pleos 런처 차단 오버레이를 발견했습니다. 주행 화면을 런처 진입점으로 분리한 뒤 실제 화면 캡처까지 확인했습니다. 시스템 제한을 해제하거나 allowlist를 조작하지 않았습니다.

- 기존 평가 엔진 JVM 검증 145개와 고정 시나리오 34개 통과. 기존 30초 에뮬레이터의 무신호 보정·좌측 신호 조향·제동 조향 구간 검증도 통과했습니다.
