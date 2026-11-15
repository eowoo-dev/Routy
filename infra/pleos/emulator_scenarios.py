"""Fixed Pleos emulator integration test. Python standard library only; never targets real cars."""
import http.client
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
from disk_trace import disk_trace

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / "apps/pleos/driving-evaluation/src/main/resources/scenarios/demo-v1.json"
PACKAGE = "org.eowoo.routy.pleos.debug"


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


def is_new_trace(value, previous, attempt_floor, now_ms):
    # Persisted files survive emulator reboots; elapsedRealtime does not.
    # Both a changed document and this boot's acquisition window are required.
    start, end = value.get("startMs"), value.get("endMs")
    return (value != previous and type(start) is int and type(end) is int
            and attempt_floor < start < end <= now_ms+1000)


def verify_environment_trace(trace):
    rows = trace.get("environment", [])
    errors = []
    if trace.get("engineVersion") != "0.7.0" or len(rows) < 50:
        errors.append("Expected an integrated 0.7.0 trace with at least 50 environment observations")
    if any(not trace["startMs"] <= row["sessionAtMs"] <= trace["endMs"] or row.get("serverAtMs") is None or row["frame"].get("source") != "SIMULATED_ENVIRONMENT" for row in rows):
        errors.append("Environment provenance or aligned timestamps are invalid")
    keys = [(row["frame"].get("runId"), row["frame"].get("sequence")) for row in rows]
    if len(set(keys)) != len(keys) or any(b["sessionAtMs"] <= a["sessionAtMs"] for a, b in zip(rows, rows[1:])):
        errors.append("Environment observations contain duplicates or reversed session time")
    analysis = trace.get("analysis", {})
    if analysis.get("environment", {}).get("source") != "SIMULATED_ENVIRONMENT":
        errors.append("Integrated report lost simulated environment provenance")
    categories = {a["category"]: a for a in analysis.get("assessments", [])}
    quality = {q["channel"]: q for q in analysis.get("environment", {}).get("review", {}).get("quality", [])}
    for category in ("LANE_KEEPING", "TRAFFIC_SIGNAL_RESPONSE", "FOLLOWING_DISTANCE"):
        q = quality.get(category, {})
        values = [q.get(key) for key in ("totalMs", "eligibleMs", "noOpportunityMs", "excludedMs")]
        if not all(type(value) is int and value >= 0 for value in values):
            errors.append(f"{category}: missing or invalid environment quality partition")
            continue
        total, eligible, no_opportunity, excluded = values
        if total != trace["endMs"]-trace["startMs"] or eligible+no_opportunity+excluded != total:
            errors.append(f"{category}: inconsistent quality duration")
        # The SDK acquisition fixture may never approach the stop line. Valid observations
        # without a target are evidence of integration, not time spent evaluating a target.
        if eligible+no_opportunity < 1000:
            errors.append(f"{category}: insufficient aligned environment coverage")
        if categories.get(category, {}).get("eligibleMs") != eligible or categories.get(category, {}).get("excludedMs") != excluded:
            errors.append(f"{category}: assessment and environment quality differ")
    return errors


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
        low = timing.get("startMs",trace["startMs"] + timing["startSeconds"] * 1000)
        high = timing.get("endMs",trace["startMs"] + timing["endSeconds"] * 1000)
        settled = timing.get("inputAtMs",low) + plan["settleSeconds"] * 1000
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
        retryable=(method=="GET" and path=="/vehicle/state") or (method=="PUT" and path in ("/vehicle/gear","/vehicle/controls","/vehicle/turn-signal")) or (method=="POST" and path=="/browser/session" and bool(params.get("client")))
        attempts=3 if retryable else 1
        for attempt in range(attempts):
            try:
                with urllib.request.urlopen(urllib.request.Request(self.base + path + suffix, method=method), timeout=4) as response:
                    return json.load(response)
            except urllib.error.HTTPError:
                raise
            except (http.client.RemoteDisconnected,urllib.error.URLError,TimeoutError,ConnectionResetError):
                if attempt+1==attempts:raise
                self.transport_retries=getattr(self,'transport_retries',0)+1
                time.sleep(.15*(attempt+1))

    def uptime_ms(self):
        return int(float(self.shell("cat","/proc/uptime").split()[0])*1000)

    def trace(self):
        text = self.shell("run-as", PACKAGE, "--user", self.user, "cat", "files/sensor-traces/latest.json", check=False)
        index=json.loads(text)
        if index.get('storage')=='SEGMENTED' and index.get('segments')==1:
            return json.loads(self.shell('run-as',PACKAGE,'--user',self.user,'cat',f"files/sensor-traces/{index['sessionId']}/evaluation-0.json"))
        return disk_trace(index,lambda path:self.shell('run-as',PACKAGE,'--user',self.user,'cat',path))

    def start_capture(self):
        # The launcher is singleTask. A previous driving screen can remain above
        # the diagnostic Activity; begin this isolated fixture with a fresh task.
        # Never interrupt an existing practice or its durable-save phase.
        try:
            journal=json.loads(self.shell('run-as',PACKAGE,'--user',self.user,'cat','no_backup/active-trip.json',check=False))
        except json.JSONDecodeError:journal={}
        if journal.get('phase') in ('RECORDING','PARK_WAIT','SAVING') and 'TripService' in self.shell('dumpsys','activity','services',PACKAGE):
            raise RuntimeError('An existing practice is active; finish it before the diagnostic fixture')
        self.shell('am','force-stop','--user',self.user,PACKAGE)
        for _ in range(6):
            # A newly bound UX/gear connection can still be conservative on the
            # first launch. Retry the parked entry, never override the restriction.
            output = self.shell("am", "start", "-W", "--user", self.user, "-n", PACKAGE + "/org.eowoo.routy.pleos.DiagnosticsActivity")
            if "Error" in output:raise RuntimeError(output)
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
                time.sleep(.5)
                continue
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", scroll.get("bounds")))
            self.shell("input", "swipe", (x1+x2)//2, y1+100, (x1+x2)//2, y2-100, 200)
        raise RuntimeError("Capture button not available. Install the debug APK, grant its sensor permissions once, and leave the emulator in Park")

    def reset(self):
        self.request("/vehicle/controls", "PUT", speed=0, accelerator=0, brake=0, steering=0)
        self.request("/vehicle/turn-signal", "PUT", mode="Off")
        self.request("/vehicle/gear", "PUT", gear="Park")


def fixture_plan(environment=False):
    plan = json.loads(FIXTURE.read_text(encoding="utf-8"))["emulator"]
    if environment:
        # The separate environment smoke test needs sustained SDK observations,
        # not three short contexts competing with HTTP/model sampling in 30s.
        plan['phases']=[p for p in plan['phases'] if p['id']==plan['environmentPhaseId']]
        plan['phaseSeconds']=plan['environmentPhaseSeconds']
    return plan


def run(args):
    plan=fixture_plan(getattr(args,'environment',False))
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
    environment_worker = None
    environment_pause=threading.Event()
    environment_idle=threading.Event()
    def environment_request(path,body=None):
        data=None if body is None else json.dumps(body).encode()
        request=urllib.request.Request('http://127.0.0.1:8090/routy/'+path,data=data,headers={'Content-Type':'application/json'},method='GET' if body is None else 'POST')
        with urllib.request.urlopen(request,timeout=2) as response:return json.load(response)
    try:
        emu = Emulator(args)
        require_exclusive_console(lease)
        state = emu.request("/vehicle/state")
        if state["gear"]["actual"]["value"] != "Park" or state["controls"]["speed_kph"] != 0:
            raise RuntimeError("Start the fixed test in Park at 0km/h")
        try:
            previous_trace = emu.trace()
        except (json.JSONDecodeError, KeyError):
            previous_trace = None
        worker = threading.Thread(target=heartbeat, daemon=True)
        worker.start()
        if getattr(args, "environment", False):
            if environment_request('scenarios/state').get('scenarioId') is not None:
                raise RuntimeError('Scenario controller owns the environment; release it before the SDK fixture')
            def environment_input():
                while not stopped.is_set():
                    if environment_pause.is_set():
                        environment_idle.set();stopped.wait(.05);continue
                    environment_idle.clear()
                    try:
                        state = emu.request("/vehicle/state")
                        controls = state["controls"]
                        value = {"speed": controls["speed_kph"] / 3.6 if state["gear"]["actual"]["value"] == "Drive" else 0,
                                 "steeringDeg": -controls["steering_deg"], "signal": "UNKNOWN"}
                        request = urllib.request.Request("http://127.0.0.1:8090/routy/input", data=json.dumps(value).encode(), headers={"Content-Type": "application/json"}, method="POST")
                        with urllib.request.urlopen(request, timeout=1) as response:
                            response.read()
                    except Exception as exc:
                        if len(heartbeat_errors) < 3:
                            heartbeat_errors.append("Environment input: " + str(exc))
                    stopped.wait(0.1)
            environment_worker = threading.Thread(target=environment_input, daemon=True)
            environment_worker.start()
        deadline = time.monotonic() + 12
        while not emu.request("/vehicle/state")["gear"]["bridge_ready"]:
            if time.monotonic() >= deadline:
                raise RuntimeError("Virtual Console DDS bridge did not become ready")
            time.sleep(0.25)
        emu.reset()
        time.sleep(2)
        # A previous incomplete/foreign recording cannot satisfy the current attempt's freshness.
        attempt_floor = int(float(emu.shell("cat", "/proc/uptime").split()[0]) * 1000) - 1000
        previous_start = attempt_floor
        emu.start_capture()
        began = time.monotonic()
        time.sleep(3)
        require_sole_console_writer(lease)
        emu.request("/vehicle/gear", "PUT", gear="Drive")
        emu.request("/vehicle/controls", "PUT", speed=plan["speedKph"], accelerator=0, brake=0, steering=0)
        time.sleep(max(0, plan["warmupSeconds"] - (time.monotonic() - began)))
        for phase in plan["phases"]:
            require_sole_console_writer(lease)
            if environment_worker is not None:
                # Each SDK gesture is a separate straight-road world. A partial
                # steering cycle must not carry residual yaw into the next case.
                environment_pause.set()
                if not environment_idle.wait(2):raise RuntimeError('Environment input did not pause')
                environment_request('scenarios/control',{'action':'release'})
            start = time.monotonic()
            device_start=emu.uptime_ms()
            emu.request("/vehicle/turn-signal", "PUT", mode=phase["mode"])
            emu.request("/vehicle/controls", "PUT", speed=plan["speedKph"], accelerator=0, brake=phase["brake"], steering=0)
            input_at=emu.uptime_ms()
            environment_pause.clear()
            time.sleep(plan["settleSeconds"])
            count = 0
            angles=plan["environmentSteeringDegrees"] if getattr(args,"environment",False) else plan["steeringDegrees"]
            while time.monotonic() - start < plan["phaseSeconds"]:
                angle = angles[count % len(angles)]; count += 1
                require_sole_console_writer(lease)
                emu.request("/vehicle/controls", "PUT", speed=plan["speedKph"], accelerator=0, brake=phase["brake"], steering=angle)
                time.sleep(plan["steeringIntervalSeconds"])
            emu.request("/vehicle/controls", "PUT", speed=plan["speedKph"], accelerator=0, brake=phase["brake"], steering=0)
            phases.append({"id": phase["id"], "startSeconds": start - began, "endSeconds": time.monotonic() - began,
                           "startMs":device_start,"inputAtMs":input_at,"endMs":emu.uptime_ms()})
            print("INPUT", phase["id"], flush=True)
        emu.reset()
        deadline = began + plan["captureSeconds"] + 15
        while time.monotonic() < deadline:
            try:
                value = emu.trace()
                now_ms = int(float(emu.shell("cat", "/proc/uptime").split()[0])*1000)
                if is_new_trace(value,previous_trace,attempt_floor,now_ms):
                    trace = value
                    break
            except (json.JSONDecodeError, KeyError):
                pass  # File may not exist yet or the recorder may be finishing its write.
            time.sleep(0.5)
        if trace is None:
            raise RuntimeError("No new completed trace. Check sensor permissions and app lifecycle; old trace is never accepted")
        require_sole_console_writer(lease)
        (output / "latest.json").write_text(json.dumps(trace, ensure_ascii=False, indent=2), encoding="utf-8")
        if trace.get('storage')=='SEGMENTED':
            windows=output/'windows';windows.mkdir(exist_ok=True)
            for i in range(trace['segments']):
                raw=emu.shell('run-as',PACKAGE,'--user',emu.user,'cat',f"files/sensor-traces/{trace['sessionId']}/evaluation-{i}.json")
                (windows/f'window-{i}.json').write_text(raw,encoding='utf-8')
        errors.extend(verify_trace(trace, plan, phases, previous_start))
        if getattr(args, "environment", False):
            if trace.get('storage')=='SEGMENTED':
                # Quality belongs to each replay window, including its lookback.
                # Do not pretend the bounded parent preview is a full batch report.
                for i in range(trace['segments']):
                    document=json.loads((output/'windows'/f'window-{i}.json').read_text(encoding='utf-8'))
                    errors.extend(verify_environment_trace(document))
            else:errors.extend(verify_environment_trace(trace))
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
                if environment_worker is not None:
                    environment_worker.join(timeout=5)
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
    parser.add_argument("--environment", action="store_true", help="Feed the local environment model and verify integrated SDK/environment capture")
    parser.add_argument("--output", default=str(ROOT / "apps/pleos/driving-evaluation/build/scenarios/emulator"))
    sys.exit(run(parser.parse_args()))
