"""Experimental headless Pleos boot test; image and AVD live in a Docker volume."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import signal
import threading
import time
import zipfile

sdk = Path(os.environ["ANDROID_HOME"])
image = Path("/data/system-image/crp_avd_out_x86_64")
archive = Path("/images/pleos-2.1.0.zip")
os.environ["HOME"] = "/data/home"
Path(os.environ["HOME"]).mkdir(parents=True, exist_ok=True)

if sys.argv[1:] == ["check"]:
    import fcntl

    with open("/dev/kvm", "rb+") as kvm:
        print("KVM API:", fcntl.ioctl(kvm, 0xAE00, 0))
        vm = fcntl.ioctl(kvm, 0xAE01, 0)
        os.close(vm)
        print("KVM VM creation: OK")
    sys.exit(subprocess.call(["emulator", "-accel-check"]))

if not (image / ".extracted").exists():
    print("Extracting Pleos image into Docker volume (about 18.3 GiB)...")
    target = image.parent.resolve()
    target.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as z:
        for item in z.infolist():
            path = (target / item.filename).resolve()
            if not path.is_relative_to(target):
                raise RuntimeError("Unsafe archive path")
        z.extractall(target)
    (image / ".extracted").touch()

avd_root = Path(os.environ["ANDROID_AVD_HOME"])
avd_name = "Routy_Pleos_Docker"
avd = avd_root / f"{avd_name}.avd"
avd.mkdir(parents=True, exist_ok=True)
Path(os.environ["ANDROID_USER_HOME"]).mkdir(parents=True, exist_ok=True)
(avd_root / f"{avd_name}.ini").write_text(
    f"avd.ini.encoding=UTF-8\npath={avd}\ntarget=android-34\n"
)
(avd / "config.ini").write_text(
    f"""avd.ini.encoding=UTF-8
AvdId={avd_name}
avd.ini.displayname=Routy Pleos Connect 2.1.0
abi.type=x86_64
hw.cpu.arch=x86_64
hw.cpu.ncore=4
hw.ramSize=4096
hw.lcd.width=2560
hw.lcd.height=1440
hw.lcd.density=160
hw.keyboard=yes
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader
hw.mainKeys=no
hw.device.name=Pleos_Connect_v2_0
hw.device.manufacturer=42dot
hw.sensors.orientation=no
hw.accelerometer=no
disk.dataPartition.size=8G
image.sysdir.1={image}/
tag.id=android-automotive
tag.display=Pleos Connect
PlayStore.enabled=false
fastboot.forceColdBoot=yes
fastboot.forceFastBoot=no
"""
)
if (image / "advancedFeatures.ini").exists():
    shutil.copyfile(image / "advancedFeatures.ini", avd / "advancedFeatures.ini")

subprocess.run(["adb", "-a", "start-server"], check=True)
print("Starting cold boot with KVM and software graphics...")
emulator = subprocess.Popen([
    "emulator", "-avd", avd_name, "-no-window", "-no-audio",
    "-no-snapshot", "-no-boot-anim", "-gpu", "swiftshader",
    "-accel", "on", "-memory", "4096", "-cores", "4",
    "-ports", "5554,5555",
    *sys.argv[1:],
])


def prepare_console():
    deadline = time.monotonic() + 600
    while emulator.poll() is None and time.monotonic() < deadline:
        try:
            result = subprocess.run(
                ["adb", "shell", "getprop", "sys.boot_completed"],
                capture_output=True, text=True, timeout=10,
            )
            if result.stdout.strip() == "1":
                subprocess.run(["adb", "forward", "tcp:8088", "tcp:8088"], check=True)
                subprocess.run(["adb", "shell", "input", "keyevent", "KEYCODE_WAKEUP"])
                print("Android boot completed; Virtual Console forwarded on port 8088.")
                return
        except (subprocess.TimeoutExpired, subprocess.CalledProcessError) as error:
            print("Waiting for ADB:", error)
        time.sleep(3)


def stop_emulator(signum, frame):
    emulator.terminate()


signal.signal(signal.SIGTERM, stop_emulator)
signal.signal(signal.SIGINT, stop_emulator)
threading.Thread(target=prepare_console, daemon=True).start()
sys.exit(emulator.wait())
