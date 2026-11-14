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

다음 단계는 별도 시계의 이동·환경 모델을 추가하고 동일 상태를 화면과 관측에 공급하는 것입니다. 이후 공유 관측 계약과 Pleos debug 입력 어댑터를 통해 Kotlin 엔진에 연결합니다. 현재는 앱이나 기존 엔진 입력을 변경하지 않습니다. 이 도구는 prod compose에 포함하지 않습니다.

차로 변경·차로 유지·신호등 대응·차간거리의 입력, 검출 방식, 구현 순서와 자동 검증 범위는 [환경 평가 구현 계획](../../docs/architecture/driving-environment-plan.md)을 참고합니다.

## 검증

```powershell
docker compose -f compose.dev.yaml exec -T virtual-console node --test infra/virtual-console/patches/highway-view.test.mjs infra/virtual-console/proxy.test.mjs
```
