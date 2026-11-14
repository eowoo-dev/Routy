# 주행 평가 시연 시나리오 자동화

`demo-v1`은 고정 입력과 기대 결과를 함께 버전 관리하는 시연용 회귀 테스트입니다. 합성 엔진 테스트와 실제 SDK를 거치는 에뮬레이터 테스트를 구분합니다.

## 실행

독립 합성 시나리오는 에뮬레이터 없이도 실행할 수 있습니다. Node 26 컨테이너에서 고정 간격 입력을 만들고 Kotlin 평가기로 별도 기대 이벤트를 검사합니다. 각 실행은 새 실행 ID와 빈 모델 상태에서 시작합니다.

```powershell
.\infra\pleos\test-environment-scenarios.ps1
.\infra\pleos\test-environment-scenarios.ps1 -Scenario lane-change
```

시각 시연은 `http://127.0.0.1:8090/routy/scenarios`에서 선택·시작·정지·초기화합니다. 이 화면은 SDK 차량 속성을 제어하지 않습니다. 완료 시 입력의 기하 조건을 표시하며, Kotlin 이벤트 검사 결과와 구분합니다.

| 시나리오 | 필수 이벤트 | 입력 기하 조건 |
|---|---|---|
| lane-change | LANE_CHANGE_LEFT, LANE_CHANGE_SIGNAL_OBSERVED | 왼쪽 3.7m 차로에서 위치·헤딩 안정 |
| lane-boundary | LANE_BOUNDARY_APPROACH | 차로 경계 안에서 접근 후 중앙 복귀 |
| red-stop | STOPPED_BEFORE_RED | 적색 정지선 앞 0~5m에서 정차, 통과 없음 |
| following-gap | SHORT_FOLLOWING_HEADWAY, LOW_CLOSING_TTC | 앞차 간격 감소, 범퍼 간 거리 양수 유지 |

추가 실패·경계 사례는 총 9개입니다. 모두 독립 실행·기하 검사·필수/금지 이벤트 검사·제외 프레임 0개·재평가 일치를 확인합니다.

| ID | 핵심 기대 결과 |
|---|---|
| lane-change-right | 오른쪽 변경 완료, 유지 침범 중복 없음 |
| lane-change-no-signal | 방향지시등 미사용 |
| lane-change-interrupted | LEFT→OFF→LEFT를 중간 해제로 검출, 정상/늦은 사용 금지 |
| lane-change-opposite | 일시적 반대 신호를 복원 후에도 기억 |
| lane-change-unknown | 일시적 관측 불가에서 신호 사용 평가 보류 |
| lane-return | 차체 경계 침범과 원래 차로 복귀, 변경 완료 금지 |
| red-cross | 적색 정지선 통과, 정지 성공 금지 |
| lead-cut-in | 끼어들기 맥락과 시간 간격/TTC, 운전자 책임 단정 없음 |
| vehicle-overlap | 전방 차체 영역 겹침을 별도 관측, 유효 THW/TTC로 환산 금지 |

JVM 단위 테스트는 추가로 연속 다차로 변경, 앞차 ID 교체 전후의 짧은 이벤트 합치기 금지, 신호 전환과 정지선 통과가 겹치는 경우, 변경 완료와 차로 유지 침범의 중복 방지도 검사합니다. 새 규칙은 엔진 0.6.0 / environment-0.2이며 0.5.0 기록의 판정은 동결된 이전 평가기로 재생합니다.

검사는 각 시나리오의 금지 이벤트·제외 프레임 0개·같은 입력의 재평가 일치도 확인합니다. 결과 JSON은 `apps/pleos/driving-evaluation/build/reports/environment-scenarios.json`에 생성합니다. 기존 SDK 테스트와 병행하려면 시나리오 화면에서 ‘수동 환경으로 전환’으로 환경 제어권을 해제하세요.

```powershell
# Docker의 JDK로 엔진 테스트와 고정 시나리오 실행
.\infra\pleos\test-scenarios.ps1

# 위 검사 + Windows Pleos 에뮬레이터 입력/SDK 수집/JSON 검사/엔진 재생
.\infra\pleos\test-scenarios.ps1 -Emulator

# 실제 SDK와 가상 환경의 시각 정합·통합 보고서·전체 재생까지 검사
.\infra\pleos\test-scenarios.ps1 -Environment
```

에뮬레이터 테스트의 준비 조건:

- Windows Pleos 에뮬레이터 실행, 기본 ADB 식별자 `emulator-5554`.
- 디버그 APK 설치. 필요하면 `install-windows.ps1` 실행.
- 앱의 차량 속도·위치 권한을 최초 한 번 허용. 스크립트는 권한을 강제 부여하지 않습니다.
- Virtual Console HTTP API가 `http://127.0.0.1:8088`에서 접근 가능해야 합니다. 기본 상태는 P단·0km/h입니다.
- **Virtual Console 브라우저 탭은 모두 닫습니다.** 페이지의 주행 물리 루프가 속도·페달 값을 계속 전송해 자동 입력을 덮어씁니다. 테스트가 자체 연결을 유지하므로 브라우저는 필요하지 않습니다. 닫은 탭의 세션 만료는 최대 4초 기다립니다.
- Driving Mode는 기본 `ready` 상태로 둡니다. 크루즈를 활성화하면 기대 맥락이 달라져 검사가 실패합니다.
- Python과 Docker 실행 환경. 스크립트에는 외부 Python 패키지가 필요하지 않습니다.
- 에뮬레이터 테스트 중에는 별도 수집이나 Virtual Console 입력을 동시에 시작하지 않습니다.

`-Environment`는 Docker의 `virtual-console` 서비스와 `adb reverse tcp:8090 tcp:8090`도 필요합니다. 최신 `install-windows.ps1`이 역방향 포트를 설정합니다. 테스트 실행기가 차량 제어를 단독으로 맡고 같은 입력 상태를 환경 모델에 공급하므로 브라우저 없이 검사합니다. 최소 50개 환경 관측, 시각·출처·순번, 세 환경 카테고리의 유효 구간을 검사하고 새 APK의 0.7.0 통합 보고서를 저장합니다. 기존 0.5.0/0.6.0 기록은 동결된 이전 환경 규칙으로, 환경이 없는 0.3/0.4 기록은 기존 경로로 재생합니다. 에뮬레이터 입력 검증과 실제 3D 화면 확인은 별개입니다.

ADB 식별자를 바꾸려면 `-Serial emulator-5556`을 전달합니다. 개별 에뮬레이터 실행은 `python -X utf8 infra/pleos/emulator_scenarios.py --serial emulator-5554`로도 가능합니다. 물리 Android 기기의 ADB 식별자는 받지 않습니다.

## 엔진 시나리오 34개

정의 파일은 `apps/pleos/driving-evaluation/src/main/resources/scenarios/demo-v1.json`입니다. 기대값은 엔진 출력에서 자동 생성하지 않고 파일에 명시합니다. 각 시나리오는 전체 엔진을 실행하고 이벤트 종류, 복합 맥락, 회전 평가, 평가하면 안 되는 항목을 확인합니다. 같은 입력을 두 번 평가해 결정성도 검사합니다.

아래 표는 기존 16개입니다. v0.4에서는 `practice-v1.json`의 18개를 추가했습니다. 새 시나리오는 회전을 주입하지 않고 합성 좌표·헤딩·지도에서 회전을 검출해 신호 사용과 학습 피드백까지 검증합니다. 자세한 입력과 한계는 [v0.4 안내](driving-practice-analysis.md)를 참고합니다.

| 종류 | 기대 결과 |
|---|---|
| 일정 속도·일정 조향 | 복습 이벤트 없음 |
| 정차 중 반복 조향 | 정차 중 조작 맥락 |
| 후진 기어에서 반복 조향 | 후진 맥락; 전진 가감속 평가는 보류 |
| 이동 중 무신호 반복 조향 | 반복 보정 후보와 원인 미확인 급조향 |
| 좌/우 방향지시등 동반 조향 | 신호 동반 조향; 확정 회전으로 승격하지 않음 |
| 브레이크 동반 조향 | 제동 동반 조작 |
| 크루즈 동작 중 조향 | 크루즈 맥락; 횡방향 보조로 단정하지 않음 |
| 브레이크 결측·속도 공백·중복 속도 시각 | 근거 부족으로 맥락 미확인 |
| 조향 전체 공백 | 조향 이벤트 없음, 관측 가능 시간 0 |
| P단인데 이동 속도 입력 | 입력 모순 |
| 독립적으로 확인된 좌/우 회전 | 방향지시등 준비 조건 충족 |
| 확인된 회전에서 방향지시등 OFF | 방향지시등 미사용 복습 이벤트 |

확정 회전 시나리오에는 합성된 도로 전환·헤딩 변화 근거를 명시적으로 주입합니다. 이것은 실제 회전 검출기를 구현하거나 에뮬레이터에서 회전을 검출했다는 의미가 아닙니다. 모든 시나리오에서 차로 유지·차간거리·신호 대응은 미평가이고, 차로 위치를 안다고 표시하지 않는지도 검사합니다.

## 에뮬레이터 통합 시나리오

UI 계층에서 실제 수집 버튼을 찾고 누른 뒤, Virtual Console 세션 heartbeat를 유지하며 D단·36km/h를 입력합니다. 초기 수집을 기다린 뒤 다음 세 구간을 실행합니다.

1. 방향지시등 OFF + 브레이크 OFF + 반복 조향 → `UNSIGNALLED_CORRECTION_CANDIDATE`.
2. 좌측 방향지시등 + 브레이크 OFF + 반복 조향 → `SIGNALLED_STEERING`.
3. 방향지시등 OFF + 브레이크 ON + 반복 조향 → `BRAKING_MANEUVER`.

구간 사이에 조향 정지 시간을 두어 사건이 이어지지 않게 합니다. 검사기는 각 구간에 실제 SDK 브레이크·방향지시등 응답이 존재하고, 그 구간 안의 이벤트가 기대 맥락 및 속도·기어 근거를 갖는지 확인합니다. 다른 구간에 나타난 같은 이름의 결과만으로 통과하지 않습니다.

샘플 개수·콜백 시각은 실행 환경에 따라 변하므로 정확한 개수 대신 유효 속도 표본 50개 이상, 조향 20개 이상, 속도 관측 범위 20초 이상을 요구합니다. 안정화 시간이 지난 각 구간에서 브레이크·방향지시등 응답이 2개 이상 일치하고, 유효 속도 표본 5개 이상이 목표 속도에서 1m/s 미만의 오차로 유지되어야 합니다. 세션이 새로 완료됐는지, 기록이 잘리지 않았는지, 합성 결과가 아닌지 확인합니다. 과거 JSON이나 미완료 수집으로 통과하지 않습니다. 실행 전 이전 출력 파일은 제거합니다.

연결의 독점 여부도 시작 전·입력 중에 확인합니다. 이 에뮬레이터의 `/browser/session`은 GET도 익명 연결을 등록하므로 상태 조회용 GET을 사용하지 않습니다. 테스트 UUID를 지정한 POST heartbeat 응답의 클라이언트 수가 1개인지 확인합니다. 외부 연결이 있으면 안내 후 실패하며 타인의 세션을 강제로 종료하지 않습니다. 시작 전 검사에 실패해도 테스트가 등록한 자신의 연결은 해제합니다.

성공·실패·Ctrl+C 시 모두 P단·속도/페달/조향 0·방향지시등 OFF로 복원합니다. 복원값이 VHAL로 전달될 시간을 확보한 뒤 heartbeat를 종료합니다. 실패와 복원 오류는 `result.json` 및 종료 코드 1로 보고합니다. 진단 Activity의 주행 중 화면 제한은 우회하지 않으며 P단에서 시작하고 결과를 확인합니다.

## 실제 기록 재생

```powershell
# 기록은 Docker에서 보이는 driving-evaluation/build 아래에 둡니다.
.\infra\pleos\gradle.ps1 '--project-dir=driving-evaluation' run '--args=--trace build/scenarios/emulator/latest.json --verify-captured --output build/scenarios/emulator/replayed.json'
```

재생 결과의 출처는 `RECORDED`입니다. 원래 시각, null/가용성, 중복 시각, 공백, 제공자 시각을 보존합니다. `0.4.0`/`experimental-0.4` 기본 설정과 기존 `0.3.0`/`experimental-0.3` 호환 경로를 지원합니다. 다른 버전/설정 조합은 명시적인 지원 없이 읽지 않습니다. 기록에 없는 도로·회전은 임의로 생성하지 않습니다.

`--verify-captured`는 복합 맥락을 재계산해 비교합니다. 0.4 기록은 행동·품질·피드백을 포함한 전체 분석도 비교합니다. 이는 재현성 확인이고 고정 기대값 검사는 별도의 시나리오 판정입니다.

출력은 모두 `apps/pleos/driving-evaluation/build` 아래에 생성되어 Git에서 제외됩니다.

- `reports/scenarios.json`: 시나리오별 통과 여부와 평가 결과.
- `test-results/test` 및 `reports/tests/test`: JVM 테스트 결과.
- `scenarios/emulator/result.json`: 실제 입력 구간·통과 여부·오류.
- `scenarios/emulator/latest.json`: 앱에서 읽은 실제 원시 입력과 캡처 결과.
- `scenarios/emulator/replayed.json`: 같은 입력을 JVM 엔진에 재생한 결과.

기록에는 위치 정보가 포함될 수 있으므로 실제 센서 기록을 저장소나 공개 CI 아티팩트에 올리지 않습니다. 현재 CI는 합성 시나리오 결과만 업로드합니다.

## GitHub Actions

`.github/workflows/driving-evaluation.yml`에서 Pleos/테스트 도구 변경 PR·main push 및 수동 실행에 JVM 테스트, 고정 시나리오, 통합 검사기 자체의 회귀 테스트를 실행하고 결과를 보관합니다. 엔진 하위 프로젝트에 독립 `settings.gradle.kts`를 두어 Android SDK·Pleos Maven·에뮬레이터 없이 JDK 21에서 검사합니다.

Docker 없이 같은 CI 명령을 재현하려면 JDK가 있는 환경에서 아래처럼 실행합니다.

```sh
cd apps/pleos/driving-evaluation
sh ../gradlew --no-daemon --console=plain test run --args='--scenarios --output build/reports/scenarios.json'
python3 -m unittest discover -s ../../../infra/pleos -p 'test_emulator_scenarios.py'
```

Pleos 에뮬레이터 통합 실행은 현재 Windows 개발 환경에서 수행합니다. 호스팅 CI의 Pleos 이미지·가속·라이선스 조건을 확인하기 전까지 이 작업이 GitHub에서도 실행됐다고 표시하지 않습니다. 고정 시연의 기능 회귀 검증과 실제 운전 평가 정확도 검증은 별도 범위입니다.

## 로컬 검증 결과 (2026-11-14)

- JVM 테스트 111개 통과: 기존 74개, 지도/위치에서 출발하는 행동·학습 시나리오 18개, 새 강건성·품질/공백·전체 기록 재생 검증 19개.
- Python 자동 검사기 테스트 13개 통과: 오래된 결과·결측·잘못된 구간·비정상 근거·입력 덮어쓰기 거부 및 단독 세션/실패 시 복원/연결 해제/실패 보고.
- 최신 디버그 APK 빌드·Lint 오류 0건 및 에뮬레이터 설치 확인.
- `test-scenarios.ps1 -Emulator` 전체 실행 통과: 고정 시나리오 → 검사기 테스트 → 실제 SDK 3구간 → 기록 재생 비교.
- v0.4 최종 실제 수집 679개 관측, 복합 맥락 13개 기록. 위치·품질·피드백을 포함한 전체 분석이 재생 결과와 일치함을 확인. 실제 샘플/이벤트 수는 성능에 따라 달라지므로 기대값으로 고정하지 않음.
- 실행 후 P단·0km/h 복원 확인. GitHub Actions 워크플로는 추가했으며 원격 실행은 아직 수행하지 않음.


## environment-0.3 자동 복습 검증

```powershell
# 13개 독립 시나리오 + 전체 분석 저장/재생 비교
.\infra\pleos\test-environment-scenarios.ps1
# 개별 실패 시나리오
.\infra\pleos\test-environment-scenarios.ps1 -Scenario lane-change-interrupted
```

출력 `build/reports/environment-scenarios.json`에는 개별 전체 분석과 버전 있는 합성 기록을 포함한다. 각 기록은 엔진 0.7.0에서 다시 재생하며 전체 보고서를 비교한다. JVM 단위 테스트는 품질 시간 분할, 대상 없음/관측 없음 구분, SDK/시각/지원 범위별 제외, 중복/역순/공백, 알려진 간격·THW·TTC, 준비·OFF 시간, 관련 사건 묶음, 대상 교체, 다운로드 중 초기화와 이전 버전 호환을 검사한다.

앱 확인 순서는 평가 화면에서 자동 분석 연결 → 콘솔에서 독립 시나리오 시작 → 완료 후 새 복습 결과 표시 → 원본 사건 펼치기 → 연결 해제 → 저장된 시나리오 복습이다. 도중 정지는 완료 분석을 만들지 않아야 하며, 다음 실행은 새 실행 ID로 결과를 교체해야 한다. 시나리오 콘솔의 관측 페이지는 실제 SDK 제어를 하지 않으므로 이 흐름에서 차량 기어나 속도 상태를 강제로 바꾸지 않는다.


검사기의 기록 신선도는 이전 JSON과의 변경 여부, 이번 시도의 최소 시작 시각, 현재 부팅의 uptime 안에서 끝난 기록인지를 함께 확인한다. 에뮬레이터 재부팅 전의 큰 elapsedRealtime 값을 새 부팅의 기준으로 재사용하지 않는다. 기존 파일의 시각이 현재 시각보다 크더라도 새 기록으로 받아들이지 않는다. 환경 통합 검사는 유효 관측 시간이 충분한지와 평가 가능/대상 없음/제외 시간의 합계 및 정식 항목과의 일치를 확인한다. 대상이 없는 시간을 평가 시간으로 요구하지 않는다.


## 주행 기록 서버 업로드 검증

```powershell
# 개발 서버/DB가 실행된 상태에서 API 및 스냅샷 검증
# 테스트가 만든 설치/기록만 종료 시 정리한다.
docker compose -f compose.dev.yaml exec -T web pnpm --filter @routy/web test:records

# 차량 앱에서 '시나리오 완료 자동 분석 연결'을 누른 뒤 실행
# 개발 web 서비스만 잠시 중지했다 복구한다. 에뮬레이터 전용이다.
python -X utf8 infra/pleos/verify-record-upload.py
```

API 검사는 등록 재시도/키 충돌, 동시 동일 세션 5회 업로드의 단일 저장, 다른 본문 409, 설치별 조회 격리, 목록 페이지 중복 방지, 인증/형식/시각/본문 크기 제한을 확인한다. pure validator를 포함해 10개이며 개발 서버와 Next.js standalone 서버에서 모두 검증한다. `.github/workflows/driving-records.yml`은 별도 PostgreSQL 서비스에서 동일 검사를 실행하도록 구성했다. 원격 실행 여부는 로컬 검증과 구분한다.

차량 전송 큐의 JVM 검사 6개는 파일 재개방, 생성된 Kotlin 계약 직렬화, 최초 시각 유지, 확인 완료 상태 유지, 세션 본문 충돌, 일시/확정 실패 후 재시도, 다른 세션의 서버 응답 거부, 잘못된 경로와 RECORDED 재생의 신규 업로드 방지를 확인한다.

에뮬레이터 업로드 검사는 온라인 완료 1건 저장 → 서버 중지 → 두 완료 기록 PENDING 유지 → 앱 프로세스 재시작 → 서버 복구 → 자동 SENT 처리 → 전체 보고서 일치 및 재전송 200/동일 서버 ID를 확인한다. 중간 방향지시등 해제 500ms도 서버 스냅샷에서 유지되는지 검사한다. 결과에는 인증키를 쓰지 않으며 `build/scenarios/upload/result.json`에 세션/서버 기록 ID와 성공 여부만 저장한다. 테스트 종료 시 개발 서버를 복구하고 자신이 실행한 시나리오 제어만 해제한다.


### 주행 기록 업로드 로컬 확인 (2026-11-14)

- API/입력 검증 10개: 개발 서버와 운영 standalone 실행에서 각각 통과. DB 마이그레이션·Prisma 스키마 검사·TypeScript 타입 검사·웹 Lint 통과.
- 차량 전송 큐 JVM 6개와 기존 평가 엔진 JVM 145개 통과. 디버그 APK 빌드·Android Lint 오류 0개(경고 15개), 에뮬레이터 설치 완료.
- Next.js 서버용 빌드와 서버 코드를 제외한 Capacitor 정적 빌드 모두 통과. 정적 빌드 검사는 외부에 연결하지 않는 테스트용 HTTPS API 주소로 수행.
- 실제 에뮬레이터에서 온라인 합성 1건과 서버 중단 중 합성 2건이 모두 저장됐다. 중단 중 PENDING 상태·앱 프로세스 재시작 후 보존·서버 복구 시 자동 SENT 전환을 확인했다. 전체 보고서와 방향지시등 해제 500ms 근거, 같은 세션 재전송 200/같은 서버 ID도 확인했다.
- 실제 SDK 3구간 재검증도 통과했으며 최신 수집 SDK 700개·환경 157개를 포함한 전체 분석과 서버 스냅샷이 일치했다. SDK/환경 관측 품질이 부족했던 앞선 수집도 제외 사유를 유지한 채 별도 LIVE 기록으로 저장됐고, 낮은 품질을 서버에서 성공 평가로 바꾸지 않았다.
- 최종 로컬 대기 목록은 합성 3건·실제 SDK 2건 모두 SENT, PENDING/FAILED 0건. 개발 서버·DB·콘솔은 실행 상태를 유지하고 차량 입력은 P단·속도/페달/조향 0·방향지시등 OFF로 복원했다. 원격 CI 실행 및 운영 배포는 수행하지 않았다.


## 주행 수집 서비스 검증

P→D 정지→D 주행→화면 중지→P 복귀와 오프라인 저장·서버 업로드 검증은 [주행 수집 서비스](driving-service.md)를 참고하세요. 기존 30초 SDK 진단도 같은 서비스에서 수집합니다.
