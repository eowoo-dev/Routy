# Pleos 실행 환경과 비교 기록

현재 개발 환경은 **Windows 네이티브 실행**을 사용합니다. 레포 루트에서 `.\infra\pleos\run-windows.ps1 -Gpu host`로 실행할 수 있습니다. 아래 Docker 구성은 비교 실험 기록이며 기본 실행 경로가 아닙니다. Docker는 Next.js 서버·DB 등 서비스용으로 사용합니다.

차량 앱의 **빌드 도구는 루트 Compose의 `pleos` 서비스**에서 실행합니다. 이 문서의 에뮬레이터 Docker 실험과 별개입니다. 빌드·설치 명령은 [차량용 앱 개발 환경](../../docs/architecture/pleos-development.md)을 참고하세요.

## Docker 실행 실험

Android Studio나 Windows용 JDK/SDK 없이, Linux 컨테이너 안에서 Pleos Connect 2.1.0(API 34)을 실행하는 실험 환경입니다. Google의 에뮬레이터 컨테이너는 Windows Docker Desktop을 공식 지원하지 않습니다. 이 구성은 WSL2의 중첩 가상화와 KVM에 의존합니다.

## 준비

- Linux 컨테이너 모드의 Docker Desktop을 실행합니다.
- Docker 엔진에서 `/dev/kvm`을 전달할 수 있어야 합니다. Ubuntu WSL에서 KVM이 동작하더라도 Docker Desktop의 장치가 없을 수 있습니다. Docker/WSL 재시작 후 다시 확인하세요.
- Pleos 공식 저장소에서 받은 `pleos-2.1.0.zip`을 다운로드 폴더에 둡니다. 현재 사용한 파일의 SHA-1은 `b4f858d5bcc6c71506d7edfc872bcb9e5a11acaa`입니다.
- 이미지 압축 해제에 약 18.3GiB가 필요하며, SDK·컨테이너·쓰기 가능한 가상 디스크 공간도 추가로 필요합니다.

## 실행

레포 루트의 PowerShell에서 실행합니다.

```powershell
$env:PLEOS_DOWNLOAD_DIR = "$env:LOCALAPPDATA/Routy/downloads"
docker compose -f infra/pleos/compose.yaml build
docker compose -f infra/pleos/compose.yaml run --rm emulator check
docker compose -f infra/pleos/compose.yaml up -d
docker compose -f infra/pleos/compose.yaml logs -f
```

첫 실행에서 압축을 Docker volume에 풉니다. 재실행 시 저장된 이미지와 AVD 및 ADB 인증 키를 재사용합니다. KVM CPU 가속과 SwiftShader 소프트웨어 그래픽을 사용하며, cold boot / RAM 4GB / CPU 4개 / 2560×1440으로 설정합니다. 현재 테스트는 별도 창을 띄우지 않는 headless 방식입니다.

## 확인

```powershell
docker exec routy-pleos adb devices
docker exec routy-pleos adb shell getprop sys.boot_completed
docker exec routy-pleos adb shell getprop ro.build.version.sdk
docker exec routy-pleos adb -a forward tcp:8088 tcp:8088
```

부팅 완료 값은 `1`, Android API 값은 `34`여야 합니다. 시작 스크립트가 부팅 후 포트 포워딩을 자동으로 설정합니다. Virtual Console 주소는 `http://127.0.0.1:8088/`입니다. PC 외부에는 포트를 공개하지 않습니다. Virtual Console은 차량 상태 조작 도구이며 에뮬레이터 화면 스트리밍 도구는 아닙니다.

화면 캡처도 호스트 ADB 없이 컨테이너에서 수행할 수 있습니다.

```powershell
docker exec routy-pleos sh -c 'adb exec-out screencap -p > /tmp/pleos.png'
docker cp routy-pleos:/tmp/pleos.png ./pleos.png
```

종료 시 `docker compose -f infra/pleos/compose.yaml stop`을 사용합니다. 데이터를 보존하려면 `down -v`는 사용하지 마세요.

## 공식 자료

- [Pleos 에뮬레이터 설치](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)
- [Virtual Console 설정](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/use-vehicle-emulator)
- [Google 컨테이너 지원 조건](https://github.com/google/android-emulator-container-scripts/blob/master/REGISTRY.MD)

## 로컬 검증 범위 (2026-11-13)

- Windows 11 / Docker Desktop / WSL2에서 KVM API 12, 가상 머신 생성 및 `emulator -accel-check` 통과.
- JDK 21, Android Emulator 37.2.13, Platform Tools 37.0.1, API 34 및 Build Tools 34.0.0 설치.
- Pleos Connect 2.1.0 이미지의 `sys.boot_completed=1`, API 34 및 ADB 연결 확인.
- 호스트의 Virtual Console HTTP 200 응답 및 Pleos 상·하단 시스템 UI 화면 캡처 확인.
- 일부 Pleos 네이티브 서비스(profile, vehicle_ext, ADAS 등)의 비정상 종료 로그가 관찰됨. 기본 부팅 성공과 SDK·전체 화면·차량 데이터 기능의 정상 동작은 구분해야 하며, 후자는 아직 검증하지 않음.
- 하드웨어 GPU 가속과 대화형 화면 스트리밍은 이번 구성에 포함하지 않음.

## Windows 직접 실행 비교 (2026-11-13)

Docker 컨테이너를 종료한 뒤 같은 Pleos 2.1.0 이미지와 Android Emulator 37.2.13을 Windows에서 실행했습니다. WHPX 가속 검사와 NVIDIA GTX 1650 SUPER의 host 그래픽 초기화가 통과했습니다. RAM 4GB, CPU 4개, 2560×1440, cold boot 설정으로 비교했습니다.

- Android 부팅 완료, ADB 연결, Virtual Console HTTP 200 응답 확인.
- Windows에서 설정 앱, 상·하단 시스템 UI, 3D 차량 표시를 화면 캡처로 확인.
- Docker와 마찬가지로 profile, ADAS, vehicle_ext, vehicle_diagnostic, diagnostic_report, placesense 네이티브 서비스의 반복 종료 로그가 관찰됨.
- 공통 오류의 원인이 Docker에만 있다고 볼 수 없음. 같은 이미지·에뮬레이터 버전·수동 AVD 설정을 사용하므로, 이 결과만으로 PC 사양 문제라고 확정할 수도 없음.
- 그래픽 방식과 표시 환경이 다르며, Docker 화면 전체와의 동등성은 확인하지 않음. Windows 비교용 파일을 유지하고 Docker는 정지 상태로 둠.

현재 PC에 준비된 Windows 설치 파일을 이용한 재실행:

```powershell
docker stop routy-pleos
.\infra\pleos\run-windows.ps1 -Gpu host
```

이 스크립트는 설치 프로그램이 아닙니다. `%LOCALAPPDATA%\Android\Sdk`의 Emulator·Platform Tools와 `%LOCALAPPDATA%\Routy\windows\system-image`의 이미지가 필요합니다. Windows 로그·화면 캡처·비교용 AVD는 `%LOCALAPPDATA%\Routy\windows`에 있습니다. Windows 에뮬레이터 종료 시 해당 SDK의 `adb.exe emu kill`을 사용할 수 있습니다.
