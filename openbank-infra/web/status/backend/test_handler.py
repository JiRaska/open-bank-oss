import unittest
import json
import sys
from io import BytesIO
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import yaml

import handler


class StatusStateTests(unittest.TestCase):
    def setUp(self):
        self.state = handler.empty_state()
        self.now = 1_780_000_000
        self.all_good = {key: True for key in handler.COMPONENTS}

    def step(self, observed, offset):
        self.state = handler.update_state(self.state, observed, self.now + offset * 120)
        return handler.public_state(self.state, self.now + offset * 120)

    def test_failure_needs_three_samples_and_recovery_needs_two(self):
        self.assertEqual(self.step(self.all_good, 0)["status"], "operational")
        failing = {**self.all_good, "website": False}
        self.assertEqual(self.step(failing, 1)["status"], "unknown")
        self.assertEqual(self.step(failing, 2)["status"], "unknown")
        public = self.step(failing, 3)
        self.assertEqual(public["status"], "partial_outage")
        self.assertEqual(len(public["incidents"]), 1)
        self.assertIsNone(public["incidents"][0]["resolvedAt"])
        self.assertEqual(self.step(self.all_good, 4)["status"], "partial_outage")
        public = self.step(self.all_good, 5)
        self.assertEqual(public["status"], "operational")
        self.assertIsNotNone(public["incidents"][0]["resolvedAt"])

    def test_stale_data_is_unknown(self):
        self.step(self.all_good, 0)
        public = handler.public_state(self.state, self.now + handler.STALE_SECONDS + 1)
        self.assertEqual(public["status"], "unknown")
        self.assertTrue(all(component["status"] == "unknown" for component in public["components"]))

    def test_missing_samples_are_not_counted_as_success(self):
        self.step(self.all_good, 0)
        self.step({**self.all_good, "website": False}, 1)
        window = handler.history(self.state["samples"], self.now + 120, 86400, 48)
        self.assertEqual(window["totalSamples"], 2)
        self.assertEqual(window["successfulSamples"], 1)
        self.assertEqual(window["availabilityPercent"], 50)
        self.assertTrue(any(bucket["total"] == 0 for bucket in window["buckets"]))


class PublicApiContractTests(unittest.TestCase):
    def setUp(self):
        self.now = 1_780_000_000
        self.state = handler.update_state(handler.empty_state(), {key: True for key in handler.COMPONENTS}, self.now)
        self.spec = yaml.safe_load((Path(__file__).parent.parent / "openapi.yaml").read_text())

    def call(self, path, state=None):
        snapshot = self.state if state is None else state
        fake_s3 = SimpleNamespace(get_object=lambda **_: {"Body": BytesIO(json.dumps(snapshot).encode())})
        with patch.dict(sys.modules, {"boto3": SimpleNamespace(client=lambda _: fake_s3)}), patch.object(handler.time, "time", return_value=self.now):
            return handler.handler({"rawPath": path}, None)

    def test_all_documented_routes_return_declared_status_and_fields(self):
        for path, route in self.spec["paths"].items():
            with self.subTest(path=path):
                result = self.call(path)
                self.assertIn(str(result["statusCode"]), route["get"]["responses"])
                body = json.loads(result["body"])
                self.assertIsInstance(body, dict)
                if path == "/api/v1/health":
                    self.assertEqual(body["status"], "operational")
                    self.assertEqual(len(body["components"]), len(handler.COMPONENTS))
                if path == "/api/v1/status":
                    self.assertIn("history", body)
                    self.assertIn("incidents", body)

    def test_health_is_503_for_confirmed_impact_and_stale_data(self):
        failing = {"website": False, "customer_login": True, "api_edge": True}
        state = self.state
        for i in range(3):
            state = handler.update_state(state, failing, self.now + (i + 1) * 120)
        self.now += 360
        self.assertEqual(self.call("/api/v1/health", state)["statusCode"], 503)
        self.assertEqual(self.call("/api/v1/freshness", state)["statusCode"], 200)
        self.now += handler.STALE_SECONDS + 1
        body = json.loads(self.call("/api/v1/health", state)["body"])
        self.assertEqual(body["status"], "unknown")
        self.assertEqual(self.call("/api/v1/freshness", state)["statusCode"], 503)


if __name__ == "__main__":
    unittest.main()
