"""Test the integration oracle without an emulator, to ensure failures cannot look like passes."""
import copy
from contextlib import redirect_stdout
import io
import json
import unittest
from argparse import Namespace
from pathlib import Path
import tempfile
from unittest.mock import patch
import emulator_scenarios
from emulator_scenarios import FIXTURE, verify_trace, require_exclusive_console, require_sole_console_writer


class IntegrationOracleTest(unittest.TestCase):
    def test_freshness_survives_reboot_without_accepting_an_old_document(self):
        previous={"startMs":20000,"endMs":50000}
        current={"startMs":2000,"endMs":32000}
        self.assertTrue(emulator_scenarios.is_new_trace(current,previous,1000,33000))
        self.assertFalse(emulator_scenarios.is_new_trace(previous,previous,1000,33000))
        self.assertFalse(emulator_scenarios.is_new_trace(previous,None,1000,33000))
        self.assertFalse(emulator_scenarios.is_new_trace(current,None,2500,33000))
    def setUp(self):
        self.plan = json.loads(FIXTURE.read_text(encoding="utf-8"))["emulator"]
        self.phases = [{"id":p["id"], "startSeconds":7 + i*6, "endSeconds":13 + i*6} for i,p in enumerate(self.plan["phases"])]
        self.trace = {"origin":"LIVE", "startMs":1000, "endMs":31000, "truncated":False,
                      "speed":[{"atMs":1000+i*500,"value":10,"available":True} for i in range(50)],
                      "steering":[{"atMs":1000+i*500,"value":30,"available":True} for i in range(20)],
                      "readings":[], "contextFindings":[]}
        for phase,timing in zip(self.plan["phases"],self.phases):
            at = 1000 + (timing["startSeconds"] + 3)*1000
            self.trace["readings"] += [
                {"signal":"Vehicle.Brake.getCurrentBrakePedalStatus","atMs":at,"flag":phase["expectedBrake"],"available":True},
                {"signal":"Vehicle.TurnSignal.getCurrentTurnSignalState","atMs":at,"state":phase["expectedState"],"available":True},
                {"signal":"Vehicle.Brake.getCurrentBrakePedalStatus","atMs":at+500,"flag":phase["expectedBrake"],"available":True},
                {"signal":"Vehicle.TurnSignal.getCurrentTurnSignalState","atMs":at+500,"state":phase["expectedState"],"available":True}]
            self.trace["contextFindings"].append({"context":phase["expectedContext"],"startMs":at-500,"endMs":at+500,"lanePositionKnown":False,
                "evidence":{"gear":"DRIVE","turnSignal":phase["expectedState"],"brakePressed":str(phase["expectedBrake"]).lower(),"speedMps":"10.0"}})

    def errors(self, trace=None, phases=None, previous=0):
        return verify_trace(trace if trace is not None else self.trace, self.plan,
                            phases if phases is not None else self.phases, previous)

    def test_complete_expected_phases_pass(self):
        self.assertEqual([], self.errors())

    def test_old_trace_and_synthetic_data_fail(self):
        self.assertTrue(self.errors(previous=1000))
        self.trace["origin"] = "SYNTHETIC"
        self.assertTrue(self.errors())

    def test_missing_phases_and_missing_data_fail(self):
        self.assertTrue(self.errors(phases=[]))
        for key in ("speed", "steering", "readings", "contextFindings"):
            trace = copy.deepcopy(self.trace); trace[key] = []
            self.assertTrue(self.errors(trace), key)
        trace = copy.deepcopy(self.trace)
        for r in trace["speed"]:
            r["available"] = False
        self.assertTrue(self.errors(trace))

    def test_right_context_in_wrong_phase_fails(self):
        trace = copy.deepcopy(self.trace)
        trace["contextFindings"][0]["startMs"] += 6000
        trace["contextFindings"][0]["endMs"] += 6000
        self.assertTrue(self.errors(trace))

    def test_incorrect_or_nonfinite_evidence_fails(self):
        for key,value in (("gear","PARK"),("turnSignal","RIGHT"),("brakePressed","true"),("speedMps","nan"),("speedMps","0")):
            trace = copy.deepcopy(self.trace); trace["contextFindings"][0]["evidence"][key] = value
            self.assertTrue(self.errors(trace), key)

    def test_truncation_unavailable_values_and_invented_lane_fail(self):
        trace = copy.deepcopy(self.trace); trace["truncated"] = True
        self.assertTrue(self.errors(trace))
        trace = copy.deepcopy(self.trace); trace["readings"][0]["available"] = False
        self.assertTrue(self.errors(trace))
        trace = copy.deepcopy(self.trace); trace["contextFindings"][0]["lanePositionKnown"] = True
        self.assertTrue(self.errors(trace))


    def test_speed_drift_and_brief_brake_blip_cannot_pass(self):
        trace = copy.deepcopy(self.trace)
        for row in trace["speed"]:
            if row["atMs"] >= 10_000:
                row["value"] = 8.0
        self.assertTrue(self.errors(trace))
        trace = copy.deepcopy(self.trace)
        brakes = [r for r in trace["readings"] if r["signal"] == "Vehicle.Brake.getCurrentBrakePedalStatus" and r["flag"] is True]
        brakes[-1]["flag"] = False
        self.assertTrue(any("not held" in e for e in self.errors(trace)))


class ExclusiveSessionTest(unittest.TestCase):
    def test_open_browser_is_rejected_before_any_writes(self):
        calls = []
        def lease():
            calls.append("owned POST heartbeat"); return {"clients":2}
        with self.assertRaisesRegex(RuntimeError, "Close ALL Virtual Console"):
            require_exclusive_console(lease,timeout=0)
        self.assertEqual(["owned POST heartbeat"],calls)

    def test_closed_tab_heartbeat_can_expire(self):
        results=iter([{"clients":2},{"clients":1}])
        with patch.object(emulator_scenarios.time,"sleep"):
            require_exclusive_console(lambda:next(results))

    def test_midrun_interference_and_unknown_session_counts_fail(self):
        require_sole_console_writer(lambda:{"clients":1})
        for count in (0,2,None,"1",True):
            with self.assertRaises(RuntimeError):
                require_sole_console_writer(lambda:{"clients":count})


class RunnerFailureTest(unittest.TestCase):
    def test_preflight_conflict_closes_only_the_tests_own_lease(self):
        calls = []
        class ForeignClientEmulator:
            def request(self, path, method="GET", **params):
                self_check = path == "/browser/session" and method == "POST" and params.get("client")
                if not self_check:
                    raise AssertionError("Preflight must not write controls or create anonymous clients")
                calls.append(params)
                return {"clients":1 if params.get("closing") else 2}
        def reject_immediately(lease):
            require_exclusive_console(lease,timeout=0)
        with tempfile.TemporaryDirectory() as folder:
            with patch.object(emulator_scenarios,"Emulator",return_value=ForeignClientEmulator()), patch.object(emulator_scenarios,"require_exclusive_console",side_effect=reject_immediately), redirect_stdout(io.StringIO()):
                code = emulator_scenarios.run(Namespace(output=folder))
            self.assertEqual(1,code)
            self.assertEqual(2,len(calls))
            self.assertEqual(calls[0]["client"],calls[1]["client"])
            self.assertEqual(1,calls[-1]["closing"])

    def test_capture_failure_resets_and_reports_failure_without_old_artifacts(self):
        class FakeEmulator:
            reset_count = 0
            closed = False
            clients = 0
            def request(self, path, method="GET", **params):
                if params.get("closing"):
                    self.closed = True
                if path == "/browser/session":
                    if method != "POST" or not params.get("client"):
                        raise AssertionError("Anonymous GET creates an interfering console client")
                    if method == "POST":
                        self.clients = 0 if self.closed else 1
                    return {"clients":self.clients}
                return {"gear":{"actual":{"value":"Park"},"bridge_ready":True},
                        "controls":dict(speed_kph=0,accelerator_pct=0,brake_pct=0,steering_deg=0),
                        "lights":{"turn_signal_mode":"Off"}}
            def trace(self): return {"startMs":0}
            def reset(self): self.reset_count += 1
            def shell(self, *args): return "5 5"
            def start_capture(self): raise RuntimeError("injected capture failure")
        emu = FakeEmulator()
        with tempfile.TemporaryDirectory() as folder:
            (Path(folder)/"latest.json").write_text("old trace")
            with patch.object(emulator_scenarios, "Emulator", return_value=emu), patch.object(emulator_scenarios.time,"sleep"), redirect_stdout(io.StringIO()):
                code = emulator_scenarios.run(Namespace(output=folder))
            result = json.loads((Path(folder)/"result.json").read_text())
            self.assertEqual(1, code)
            self.assertFalse(result["passed"])
            self.assertTrue(any("injected capture failure" in e for e in result["errors"]))
            self.assertGreaterEqual(emu.reset_count,2)
            self.assertTrue(emu.closed)
            self.assertFalse((Path(folder)/"latest.json").exists())

    def test_preflight_failure_also_writes_a_failed_result(self):
        with tempfile.TemporaryDirectory() as folder:
            with patch.object(emulator_scenarios, "Emulator", side_effect=RuntimeError("missing ADB")), redirect_stdout(io.StringIO()):
                code = emulator_scenarios.run(Namespace(output=folder))
            self.assertEqual(1,code)
            self.assertFalse(json.loads((Path(folder)/"result.json").read_text())["passed"])


if __name__ == "__main__":
    unittest.main()
