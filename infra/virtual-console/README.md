# Routy Virtual Console 확장

Pleos 공식 플러그인이 아닌 개발용 프록시입니다. 원본 에뮬레이터 파일은 수정하지 않습니다.

## 독립 시나리오 실행

http://127.0.0.1:8090/routy/scenarios 에서 기본 4종과 실패·경계 9종, 총 13개 시나리오를 선택합니다. 중간 해제 후 재점등, 반대 신호 후 복원, 신호 관측 단절, 무신호 변경, 오른쪽 변경, 차선 침범 후 복귀, 적색 통과, 끼어들기, 차체 영역 겹침을 포함합니다. 이 화면은 합성 환경만 제어하며 Pleos SDK 차량 속성이나 원본 콘솔 세션을 쓰지 않습니다. 3D 화면에는 기존 Highway 모듈을 재사용합니다.

- 선택하면 새 실행 ID의 준비 상태가 됩니다. 실행 중에는 선택을 바꿀 수 없습니다.
- 시작은 준비 상태에서 실행하고, 정지 상태에서는 이어서 실행합니다.
- 정지는 시간과 원시 기록을 그대로 보존합니다. 초기화는 실행 중에도 멈춘 뒤 새 ID와 빈 기록으로 돌아갑니다.
- 완료된 시나리오를 다시 실행하려면 초기화합니다. 다른 시나리오를 선택해도 이전 상태는 이어지지 않습니다.
- 수동 환경으로 전환하면 시나리오 제어권을 해제합니다. 기존 수동 콘솔 또는 `-Environment` 에뮬레이터 테스트를 사용하기 전에 해제해야 합니다.

선택된 동안 `/routy/input`은 409로 수동 입력을 거부합니다. 관찰 화면은 서버 상태의 같은 프레임으로 3D와 수치를 표시하며 입력 POST를 보내지 않습니다. 서버 프로세스가 재시작되면 메모리의 실행 상태와 기록은 사라집니다. 원시 기록은 화면의 다운로드 링크 또는 `/routy/trace`로 내보냅니다.

화면의 자동 검증은 완료된 입력 궤적의 기하 조건입니다. Kotlin 엔진 검증은 아래 명령으로 각 시나리오의 필수·금지 이벤트, 제외 프레임 0개, 같은 입력의 결정론적 재평가를 확인합니다. 엔진 판정을 시뮬레이터 입력에 주입하지 않습니다.

```powershell
# 모든 기본·실패·경계 사례 생성 → 기하 검사 → JVM 테스트 → Kotlin 이벤트 검사
.\infra\pleos\test-environment-scenarios.ps1

# 하나만 독립 검증
.\infra\pleos\test-environment-scenarios.ps1 -Scenario red-stop

# 방향지시등 중간 해제 후 재점등 회귀 검사
.\infra\pleos\test-environment-scenarios.ps1 -Scenario lane-change-interrupted
```

입력 묶음은 `apps/pleos/driving-evaluation/build/environment-scenarios.json`, 검증 결과는 `build/reports/environment-scenarios.json`에 저장됩니다. 두 파일은 Git에서 제외합니다. Node 단위·제어 API 검사와 Node→Kotlin 검사는 GitHub Actions에도 포함합니다. 실제 SDK 수집 검증인 `test-scenarios.ps1 -Environment`와 합성 시나리오 검증은 별도 경로입니다.

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

새 환경 기록은 엔진 0.6.0, 환경 규칙 `environment-0.2`로 보고서·한국어 피드백과 `files/sensor-traces/latest.json`에 포함됩니다. 0.5.0 기록은 고정된 기존 `environment-0.1` 평가기로 재생하고 환경이 없는 기록은 0.4.0 경로를 유지합니다. 기본 규칙 버전은 experimental-0.4입니다. 저장한 환경 원본, 서버 생성 시각, 앱 세션 시각, 불확실성을 재생에서도 보존합니다. 비상등과 신호 의미가 확인된 SDK 신호만 변경 후 신호 사용 평가에 연결합니다.

환경 규칙 0.2는 변경 구간의 모든 신호 관측을 추적합니다. LEFT→OFF→LEFT는 정상 또는 단순한 늦은 사용이 아닌 중간 해제로, 일시적인 UNKNOWN은 평가 보류로 처리합니다. 시작 시점의 준비 시간을 보존하며, 시작 뒤 처음 신호를 켜면 미사용 대신 늦은 사용으로 분류합니다. 완료된 정상 차로 변경은 차로 유지 침범으로 중복 집계하지 않습니다. 침범 후 복귀·미완료 침범, 연속 다차로 이동, 전방 차체 겹침과 끼어들기 맥락도 구분합니다. 차체 겹침은 가상 기하 관측이며 실제 사고나 운전자 책임을 판정하지 않습니다.

재부팅 후 실제 브라우저에서 좌우 이동·차체 회전·선행차·적색/녹색 신호·정지선을 확인했습니다. 화면 좌표의 좌우 반전을 수정했고 1인칭 시선도 모델 헤딩에 연결했습니다. 기본 장면의 시각 확인과 모든 거리·신호 경계 시나리오의 검증은 구분합니다. 자세한 결과는 환경 평가 구현 계획의 재부팅 후 화면 확인 절을 참고합니다.

## 자동 테스트

```powershell
docker compose -f compose.dev.yaml exec -T virtual-console node --test infra/virtual-console/patches/highway-view.test.mjs infra/virtual-console/proxy.test.mjs infra/virtual-console/environment/simulation.test.mjs infra/virtual-console/scenarios/runner.test.mjs
```
