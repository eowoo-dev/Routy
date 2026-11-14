"""Fixed Pleos emulator integration test. Python standard library only; never targets real cars."""
import argparse
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / "apps/pleos/driving-evaluation/src/main/resources/scenarios/demo-v1.json"
PACKAGE = "com.noonmaru.routy.pleos.debug"


def console_client_count(response):
    count = response.get("clients")
    if type(count) is not int or count < 0:
        raise RuntimeError("Cannot verify Virtual Console session count; input isolation is required")
    return count


def require_exclusive_console(lease, timeout=4):
    # Closed tabs can remain registered for the console's 3-second heartbeat timeout.
    deadline = time.monotonic() + timeout
    while True:
        # GET /browser/session ALSO registers an anonymous client on this emulator.
        # Only refresh our explicit, stable client ID and inspect that response.
        count = console_client_count(lease())
        if count == 1:
            return
        if time.monotonic() >= deadline:
            raise RuntimeError(f"Virtual Console has {max(0,count-1)} other active session(s). Close ALL Virtual Console browser tabs before testing; browser physics overwrites speed and brake. The test maintains its own connection")
        time.sleep(0.25)


def require_sole_console_writer(lease):
    count = console_client_count(lease())
    if count != 1:
        raise RuntimeError(f"Virtual Console input conflict: expected the test's single session, got {count}. Keep all Virtual Console browser tabs closed until the test finishes")


def verify_trace(trace, plan, phases, previous_start):
    """Oracle uses commanded phase windows and expected outcomes, not the engine's own rules."""
    errors = []
    def expect(condition, message):
        if not condition:
            errors.append(message)
    expect(trace["origin"] == "LIVE", "Expected LIVE SDK data")
    expect(trace["startMs"] > previous_start, "Old trace was reused")
    expect(28_000 <= trace["endMs"] - trace["startMs"] <= 40_000, "Unexpected capture duration")
    expect(not trace["truncated"], "Capture was truncated")
    def usable(row):
        value = row.get("value")
        return row.get("available") is True and isinstance(value, (float, int)) and not isinstance(value, bool) and math.isfinite(value) and trace["startMs"] <= row.get("atMs", -1) <= trace["endMs"]
    speed_samples = [r for r in trace["speed"] if usable(r)]
    steering_samples = [r for r in trace["steering"] if usable(r)]
    expect(len(speed_samples) >= 50 and len(steering_samples) >= 20, "Insufficient usable speed/steering samples")
    expect(bool(speed_samples) and max(r["atMs"] for r in speed_samples) - min(r["atMs"] for r in speed_samples) >= 20_000, "Actual speed coverage is too short")
    expect(len(phases) == len(plan["phases"]), "Not all input phases completed")
    expect(all(not c["lanePositionKnown"] for c in trace["contextFindings"]), "Lane position was invented")
    expect(all(trace["startMs"] <= c["startMs"] < c["endMs"] <= trace["endMs"] for c in trace["contextFindings"]), "Finding outside the capture window")
    for phase, timing in zip(plan["phases"], phases):
        low = trace["startMs"] + timing["startSeconds"] * 1000
        high = trace["startMs"] + timing["endSeconds"] * 1000
        settled = low + plan["settleSeconds"] * 1000
        rows = [r for r in trace["readings"] if r["available"] and settled <= r["atMs"] <= high]
        brakes = [r for r in rows if r["signal"] == "Vehicle.Brake.getCurrentBrakePedalStatus"]
        signals = [r for r in rows if r["signal"] == "Vehicle.TurnSignal.getCurrentTurnSignalState"]
        expect(len(brakes) >= 2 and all(r["flag"] is phase["expectedBrake"] for r in brakes), f"{phase['id']}: brake state was missing or not held after settling")
        expect(len(signals) >= 2 and all(r["state"] == phase["expectedState"] for r in signals), f"{phase['id']}: indicator state was missing or not held after settling")
        speeds = [r for r in speed_samples if settled <= r["atMs"] <= high]
        expect(len(speeds) >= 5 and all(abs(r["value"] - plan["speedKph"] / 3.6) < 1 for r in speeds), f"{phase['id']}: actual SDK speed was not held at {plan['speedKph']}km/h")
        candidates = [c for c in trace["contextFindings"] if c["context"] == phase["expectedContext"]
                      and low - 500 <= (c["startMs"] + c["endMs"]) / 2 <= high + 500]
        expect(bool(candidates), f"{phase['id']}: expected {phase['expectedContext']} in its own time window")
        for c in candidates:
            evidence = c["evidence"]
            expect(evidence.get("gear") == "DRIVE", f"{phase['id']}: wrong gear evidence")
            expect(evidence.get("turnSignal") == phase["expectedState"], f"{phase['id']}: wrong indicator evidence")
            expect(evidence.get("brakePressed") == str(phase["expectedBrake"]).lower(), f"{phase['id']}: wrong brake evidence")
            speed = float(evidence.get("speedMps", "nan"))
            expect(math.isfinite(speed) and abs(speed - plan["speedKph"] / 3.6) < 1, f"{phase['id']}: speed evidence expected={plan['speedKph']/3.6:.3f}m/s actual={speed}m/s")
    return errors


class Emulator:
    def __init__(self, args):
        if not re.fullmatch(r"emulator-\d+", args.serial):
            raise ValueError("Only an emulator-NNNN ADB serial is accepted")
        url = urllib.parse.urlparse(args.console_url)
        if url.scheme != "http" or url.hostname not in ("127.0.0.1", "localhost", "::1") or url.path not in ("", "/") or url.username or url.query or url.fragment:
            raise ValueError("Virtual Console must be a loopback HTTP origin")
        sdk = os.environ.get("ANDROID_HOME") or str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk")
        candidate = Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
        self.adb = args.adb or (str(candidate) if candidate.is_file() else shutil.which("adb"))
        if not self.adb:
            raise ValueError("ADB not found; set ANDROID_HOME or --adb")
        self.serial, self.base = args.serial, args.console_url.rstrip("/")
        self.user = self.shell("am", "get-current-user").strip()
        if not self.user.isdigit():
            raise ValueError("Cannot identify current Android user")

    def shell(self, *args, check=True, timeout=30):
        result = subprocess.run([self.adb, "-s", self.serial, "shell", *map(str, args)], capture_output=True, text=True, encoding="utf-8", timeout=timeout)
        if check and result.returncode:
            raise RuntimeError(f"ADB {args[0]} failed: {result.stderr.strip()}")
        return result.stdout

    def request(self, path, method="GET", **params):
        suffix = "?" + urllib.parse.urlencode(params) if params else ""
        with urllib.request.urlopen(urllib.request.Request(self.base + path + suffix, method=method), timeout=4) as response:
            return json.load(response)

    def trace(self):
        text = self.shell("run-as", PACKAGE, "--user", self.user, "cat", "files/sensor-traces/latest.json", check=False)
        return json.loads(text)

    def start_capture(self):
        output = self.shell("am", "start", "-W", "--user", self.user, "-n", PACKAGE + "/com.noonmaru.routy.pleos.MainActivity")
        if "Error" in output:
            raise RuntimeError(output)
        for _ in range(4):
            self.shell("uiautomator", "dump", "/sdcard/routy-scenario-window.xml")
            root = ET.fromstring(self.shell("cat", "/sdcard/routy-scenario-window.xml"))
            for node in root.iter("node"):
                if node.get("package") == PACKAGE and node.get("text", "").startswith("실제 센서 전체 30초"):
                    points = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
                    if len(points) == 4 and points[2] > points[0] and points[3] > points[1] and node.get("enabled") == "true":
                        self.shell("input", "tap", (points[0] + points[2]) // 2, (points[1] + points[3]) // 2)
                        return
            scroll = next((n for n in root.iter("node") if n.get("scrollable") == "true"), None)
            if scroll is None:
                break
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", scroll.get("bounds")))
            self.shell("input", "swipe", (x1+x2)//2, y1+100, (x1+x2)//2, y2-100, 200)
        raise RuntimeError("Capture button not available. Install the debug APK, grant its sensor permissions once, and leave the emulator in Park")

    def reset(self):
        self.request("/vehicle/controls", "PUT", speed=0, accelerator=0, brake=0, steering=0)
        self.request("/vehicle/turn-signal", "PUT", mode="Off")
        self.request("/vehicle/gear", "PUT", gear="Park")


def run(args):
    plan = json.loads(FIXTURE.read_text(encoding="utf-8"))["emulator"]
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    for name in ("latest.json", "replayed.json", "result.json"):
        (output / name).unlink(missing_ok=True)
    emu = None
    client = "routy-test-" + uuid.uuid4().hex
    stopped = threading.Event()
    heartbeat_errors = []
    lease_started = False
    def lease():
        nonlocal lease_started
        lease_started = True
        return emu.request("/browser/session", "POST", client=client)
    def heartbeat():
        while not stopped.is_set():
            try:
                lease()
            except Exception as exc:
                heartbeat_errors.append(str(exc))
            stopped.wait(0.6)
    phases, errors, trace = [], [], None
    worker = None
    try:
        emu = Emulator(args)
        require_exclusive_console(lease)
        state = emu.request("/vehicle/state")
        if state["gear"]["actual"]["value"] != "Park" or state["controls"]["speed_kph"] != 0:
            raise RuntimeError("Start the fixed test in Park at 0km/h")
        try:
            previous_start = emu.trace()["startMs"]
        except (json.JSONDecodeError, KeyError):
            previous_start = -1
        worker = threading.Thread(target=heartbeat, daemon=True)
        worker.start()
        deadline = time.monotonic() + 12
        while not emu.request("/vehicle/state")["gear"]["bridge_ready"]:
            if time.monotonic() >= deadline:
                raise RuntimeError("Virtual Console DDS bridge did not become ready")
            time.sleep(0.25)
        emu.reset()
        time.sleep(2)
        # A previous incomplete/foreign recording cannot satisfy the current attempt's freshness.
        attempt_floor = int(float(emu.shell("cat", "/proc/uptime").split()[0]) * 1000) - 1000
        previous_start = max(previous_start, attempt_floor)
        emu.start_capture()
        began = time.monotonic()
        time.sleep(3)
        require_sole_console_writer(lease)
        emu.request("/vehicle/gear", "PUT", gear="Drive")
        emu.request("/vehicle/controls", "PUT", speed=plan["speedKph"], accelerator=0, brake=0, steering=0)
        time.sleep(max(0, plan["warmupSeconds"] - (time.monotonic() - began)))
        for phase in plan["phases"]:
            require_sole_console_writer(lease)
            start = time.monotonic()
            emu.request("/vehicle/turn-signal", "PUT", mode=phase["mode"])
            emu.request("/vehicle/controls", "PUT", speed=plan["speedKph"], accelerator=0, brake=phase["brake"], steering=0)
            time.sleep(plan["settleSeconds"])
            count = 0
            while time.monotonic() - start < plan["phaseSeconds"]:
                angle = plan["steeringDegrees"][count % len(plan["steeringDegrees"])]; count += 1
                require_sole_console_writer(lease)
                emu.request("/vehicle/controls", "PUT", speed=plan["speedKph"], accelerator=0, brake=phase["brake"], steering=angle)
                time.sleep(plan["steeringIntervalSeconds"])
            emu.request("/vehicle/controls", "PUT", speed=plan["speedKph"], accelerator=0, brake=phase["brake"], steering=0)
            phases.append({"id": phase["id"], "startSeconds": start - began, "endSeconds": time.monotonic() - began})
            print("INPUT", phase["id"], flush=True)
        emu.reset()
        deadline = began + plan["captureSeconds"] + 15
        while time.monotonic() < deadline:
            try:
                value = emu.trace()
                if value["startMs"] > previous_start:
                    trace = value
                    break
            except (json.JSONDecodeError, KeyError):
                pass  # File may not exist yet or the recorder may be finishing its write.
            time.sleep(0.5)
        if trace is None:
            raise RuntimeError("No new completed trace. Check sensor permissions and app lifecycle; old trace is never accepted")
        require_sole_console_writer(lease)
        (output / "latest.json").write_text(json.dumps(trace, ensure_ascii=False, indent=2), encoding="utf-8")
        errors.extend(verify_trace(trace, plan, phases, previous_start))
    except (Exception, KeyboardInterrupt) as exc:
        errors.append(f"{type(exc).__name__}: {exc}")
    finally:
        if worker is not None:
            try:
                emu.reset()
                time.sleep(2)  # Keep heartbeat alive until the restored values reach VHAL.
                restored = emu.request("/vehicle/state")
                if restored["gear"]["actual"]["value"] != "Park" or any(restored["controls"][k] != 0 for k in ("speed_kph", "accelerator_pct", "brake_pct", "steering_deg")) or restored["lights"]["turn_signal_mode"] != "Off":
                    errors.append("Emulator reset verification failed")
            except Exception as exc:
                errors.append(f"Cleanup failed: {exc}")
            finally:
                stopped.set(); worker.join(timeout=5)
        if lease_started:
            try:
                emu.request("/browser/session", "POST", client=client, closing=1)
            except Exception as exc:
                errors.append(f"Heartbeat close failed: {exc}")
        if heartbeat_errors:
            errors.append("Heartbeat failures: " + "; ".join(heartbeat_errors[:3]))
        result = {"suite":"demo-v1", "recordedAt":datetime.now(timezone.utc).isoformat(), "passed":not errors,
                  "phases":phases, "errors":errors, "traceStartMs":trace["startMs"] if trace else None}
        (output / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print("PASS" if not errors else "FAIL", json.dumps(errors, ensure_ascii=False), flush=True)
    return 0 if not errors else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--console-url", default="http://127.0.0.1:8088")
    parser.add_argument("--adb")
    parser.add_argument("--output", default=str(ROOT / "apps/pleos/driving-evaluation/build/scenarios/emulator"))
    sys.exit(run(parser.parse_args()))
