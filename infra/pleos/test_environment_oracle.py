import copy
import unittest
from emulator_scenarios import verify_environment_trace


class EnvironmentOracleTest(unittest.TestCase):
    def fixture(self):
        return {"engineVersion": "0.7.0", "startMs": 1000, "endMs": 8000,
                "environment": [{"sessionAtMs": 1000+i*100, "serverAtMs": i*100,
                                 "frame": {"source": "SIMULATED_ENVIRONMENT", "runId": "run", "sequence": i}} for i in range(50)],
                "analysis": {"environment": {"source": "SIMULATED_ENVIRONMENT", "review": {"quality": [
                    {"channel": c, "totalMs": 7000, "eligibleMs": 2000, "noOpportunityMs": 0, "excludedMs": 5000}
                    for c in ("LANE_KEEPING", "TRAFFIC_SIGNAL_RESPONSE", "FOLLOWING_DISTANCE")]}},
                             "assessments": [{"category": c, "eligibleMs": 2000, "excludedMs": 5000} for c in
                                             ("LANE_KEEPING", "TRAFFIC_SIGNAL_RESPONSE", "FOLLOWING_DISTANCE")]}}

    def test_complete_capture_passes(self):
        self.assertEqual([], verify_environment_trace(self.fixture()))

    def test_valid_observations_without_target_are_not_missing_data(self):
        trace=self.fixture()
        trace["analysis"]["environment"]["review"]["quality"][1].update(eligibleMs=0,noOpportunityMs=2000)
        trace["analysis"]["assessments"][1].update(eligibleMs=0)
        self.assertEqual([],verify_environment_trace(trace))

    def test_missing_and_inconsistent_quality_fail(self):
        for change in (dict(eligibleMs=0,noOpportunityMs=0,excludedMs=7000),dict(totalMs=6999),dict(noOpportunityMs=-1)):
            trace=self.fixture()
            trace["analysis"]["environment"]["review"]["quality"][1].update(change)
            self.assertTrue(verify_environment_trace(trace))

    def test_missing_provenance_coverage_and_duplicates_fail(self):
        for mutate in (lambda t: t.update(environment=[]),
                       lambda t: t["analysis"].update(environment={}),
                       lambda t: t["analysis"]["assessments"][0].update(eligibleMs=0),
                       lambda t: t["environment"].append(copy.deepcopy(t["environment"][0]))):
            trace = self.fixture()
            mutate(trace)
            self.assertTrue(verify_environment_trace(trace))
