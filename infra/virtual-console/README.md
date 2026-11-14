# Routy Virtual Console 확장

Pleos 공식 플러그인이 아닌 개발용 프록시입니다. 원본 에뮬레이터 파일은 수정하지 않습니다.

## 실행

윈도우에서 에뮬레이터를 실행하고 `adb forward tcp:8088 tcp:8088`을 설정합니다.

```powershell
docker compose -f compose.dev.yaml up -d virtual-console
```

브라우저에서 http://127.0.0.1:8090 을 엽니다. 원본 8088 탭과 확장 탭을 동시에 열지 않습니다. 자동 에뮬레이터 테스트 시에는 두 콘솔 탭을 모두 닫습니다. 기존 콘솔의 차량 속성 쓰기 루프와 세션 보호는 그대로 유지됩니다.

`GET /routy/health`는 프록시 생존 확인입니다. 원본 연결 확인은 `/health`를 사용합니다.

## 동작

원본 HTML·에셋·차량 API·세션 요청을 전달하고 `/highway-view.js`만 SHA-256 검증 후 메모리에서 패치합니다. `patches/`는 변경 코드만 관리하며 원본 JS나 이미지 파일은 저장하지 않습니다. 원본 버전이 바뀌면 모듈 제공을 거부합니다. 변경 내용을 검토하고 패치 및 manifest를 함께 갱신해야 합니다.

패치는 렌더링 직전 차선·자차·주변 차량의 화면 좌표를 관측해 최대 10Hz로 게시합니다. `GET /routy/environment`는 마지막 프레임과 수신 후 경과 시간을 반환합니다. 아직 프레임이 없거나 1.5초 이상 갱신되지 않으면 HTTP 503과 `available: false`를 반환합니다. 탭이 숨겨지거나 Forest 테마를 선택하면 Highway 렌더링이 멈추므로 관측도 만료됩니다.

현재 프레임은 `SIMULATED_ENVIRONMENT / CONSOLE_VISUALIZATION`입니다. 자차 횡이동은 원본 시각 효과이며 실제 주행 궤적이 아닙니다. 주변 차량 좌표는 차체 중심 기준이고 범퍼 간 거리가 아닙니다. 신호·정지선은 미구현이라 null입니다. 이 프레임을 확정 평가의 근거로 사용하면 안 됩니다.

별도 시계의 버전 2 이동 모델과 Kotlin 환경 평가기는 아래 절을 참고합니다. 버전 1 관측과 기존 SDK 평가 보고서는 별도로 유지합니다. 이 도구는 prod compose에 포함하지 않습니다.

차로 변경·차로 유지·신호등 대응·차간거리의 입력, 검출 방식, 구현 순서와 자동 검증 범위는 [환경 평가 구현 계획](../../docs/architecture/driving-environment-plan.md)을 참고합니다.

## 환경 모델과 별도 평가 모듈

`environment/simulation.mjs`는 후륜축 기준 bicycle model을 50ms 간격으로 실행합니다. 브라우저가 보내는 입력이 500ms 끊기면 진행을 멈추고, 다음 입력은 새로운 실행 ID로 시작합니다. 직진 신호는 15초마다 적색/녹색으로 전환하고 정지선은 s=150m, 중앙 선행차는 s=80m부터 8m/s로 이동합니다. 이는 시연용 설정입니다.

`GET /routy/trace`는 최대 12,000개의 버전 2 환경 프레임을 반환합니다. 기존 `/routy/environment`의 버전 1 화면 관측과 분리되어 있습니다. 앱의 debug 버튼 ‘가상 환경 기록 분석’은 이 기록을 저장하고 별도 Kotlin `EnvironmentEvaluation`으로 네 기능의 기본 이벤트를 검출합니다. 앱 실행 전 `adb reverse tcp:8090 tcp:8090`이 필요합니다. 저장 위치는 앱 전용 `files/environment-trace.json`입니다.

저장한 JSON은 JVM에서도 동일하게 재생합니다.

```powershell
.\infra\pleos\gradle.ps1 '--project-dir=driving-evaluation' run '--args=--environment build/environment-trace.json --output build/environment-result.json'
```

Highway 화면은 버전 2 모델이 유효할 때 해당 자차·선행차 위치와 신호·정지선을 표시합니다. 브라우저 입력의 방향지시등은 UNKNOWN으로 보내고 별도 재생에서는 신호 사용 평가를 보류합니다. 종합 점수는 제공하지 않습니다.

## SDK 기록과 환경 평가 통합

공유 계약은 `packages/contracts/openapi/routy.yaml`의 EnvironmentFrame·EnvironmentTrace·EnvironmentClock으로 정의하고 TypeScript와 Kotlin 모델을 생성합니다. `GET /routy/clock`은 서버 단조 시각을 반환하고 프레임의 `generatedAtMs`도 같은 시계를 사용합니다.

debug 앱의 기존 ‘실제 센서 전체 30초 수집·분석’은 환경 API도 함께 조회합니다. 클록 요청의 송수신 중간 시각으로 앱 단조 시계에 대응시키고 전체 왕복 시간을 불확실성 상한으로 기록합니다. 같은 실행·순번의 중복 프레임을 추가하지 않으며 500ms 이상 지난 프레임은 수집하지 않습니다. 시각 오차가 100ms를 넘거나 SDK 속도와 모델 속도가 2m/s 넘게 다르면 해당 환경 관측을 평가에서 제외합니다. 이 값들은 실험 설정입니다.

환경이 있는 기록은 엔진 0.5.0의 보고서·한국어 피드백과 `files/sensor-traces/latest.json`에 포함됩니다. 환경이 없는 기록은 0.4.0 경로를 유지합니다. 기존 기본 규칙 버전은 experimental-0.4이며, 환경 기능은 실험적 추가 모듈입니다. 저장한 환경 원본, 서버 생성 시각, 앱 세션 시각, 불확실성을 재생에서도 보존합니다. 비상등과 신호 의미가 확인된 SDK 신호만 변경 후 신호 사용 평가에 연결합니다.

재부팅 후 실제 브라우저에서 좌우 이동·차체 회전·선행차·적색/녹색 신호·정지선을 확인했습니다. 화면 좌표의 좌우 반전을 수정했고 1인칭 시선도 모델 헤딩에 연결했습니다. 기본 장면의 시각 확인과 모든 거리·신호 경계 시나리오의 검증은 구분합니다. 자세한 결과는 환경 평가 구현 계획의 재부팅 후 화면 확인 절을 참고합니다.

## 자동 테스트

```powershell
docker compose -f compose.dev.yaml exec -T virtual-console node --test infra/virtual-console/patches/highway-view.test.mjs infra/virtual-console/proxy.test.mjs infra/virtual-console/environment/simulation.test.mjs
```
