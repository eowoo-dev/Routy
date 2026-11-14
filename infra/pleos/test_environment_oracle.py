import copy
import unittest
from emulator_scenarios import verify_environment_trace


class EnvironmentOracleTest(unittest.TestCase):
    def fixture(self):
        return {"engineVersion": "0.5.0", "startMs": 1000, "endMs": 8000,
                "environment": [{"sessionAtMs": 1000+i*100, "serverAtMs": i*100,
                                 "frame": {"source": "SIMULATED_ENVIRONMENT", "runId": "run", "sequence": i}} for i in range(50)],
                "analysis": {"environment": {"source": "SIMULATED_ENVIRONMENT"},
                             "assessments": [{"category": c, "eligibleMs": 2000} for c in
                                             ("LANE_KEEPING", "TRAFFIC_SIGNAL_RESPONSE", "FOLLOWING_DISTANCE")]}}

    def test_complete_capture_passes(self):
        self.assertEqual([], verify_environment_trace(self.fixture()))

    def test_missing_provenance_coverage_and_duplicates_fail(self):
        for mutate in (lambda t: t.update(environment=[]),
                       lambda t: t["analysis"].update(environment={}),
                       lambda t: t["analysis"]["assessments"][0].update(eligibleMs=0),
                       lambda t: t["environment"].append(copy.deepcopy(t["environment"][0]))):
            trace = self.fixture()
            mutate(trace)
            self.assertTrue(verify_environment_trace(trace))
