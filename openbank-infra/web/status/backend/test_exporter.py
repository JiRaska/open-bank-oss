"""Guard the only payload permitted across the private/public monitoring boundary."""
import io
import json
import os
import unittest
from pathlib import Path
from unittest.mock import patch

import yaml


class ExporterContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = Path(__file__).parents[3] / "gitops/components/observability/public-status-export.yaml"
        documents = list(yaml.safe_load_all(source.read_text()))
        namespace = {"__name__": "status_export_test"}
        exec(compile(documents[1]["data"]["export.py"], str(source), "exec"), namespace)
        cls.collect = staticmethod(namespace["collect"])
        cls.verdict = staticmethod(namespace["verdict"])

    def test_only_allowlisted_aggregate_verdicts_leave_prometheus(self):
        core = ("ledger-service", "account-service", "balance-service")
        payments = ("transaction-service", "domestic-payment", "sepa-payment", "sepa-instant", "settlement-service")
        batches = [core, payments]

        def fake_open(url, timeout):
            names = batches.pop(0)
            payload = {"status": "success", "data": {"resultType": "vector", "result": [{"metric": {"container": name, "namespace": "private"}, "value": [1, "1"]} for name in names]}}
            return io.BytesIO(json.dumps(payload).encode())

        with patch.dict(os.environ, {"KUBE_PROMETHEUS_STACK_PROMETHEUS_SERVICE_HOST": "127.0.0.1"}), patch("urllib.request.urlopen", side_effect=fake_open):
            report = self.collect()
        self.assertEqual(report["components"], {"core_services": "operational", "payment_services": "operational"})
        self.assertEqual(set(report), {"schemaVersion", "checkedAt", "components"})
        self.assertNotIn("container", json.dumps(report))
        self.assertNotIn("namespace", json.dumps(report))

    def test_missing_or_down_service_never_reports_green(self):
        self.assertEqual(self.verdict({"a", "b"}, {"a": 1}), "unknown")
        self.assertEqual(self.verdict({"a", "b"}, {"a": 1, "b": 0}), "outage")


if __name__ == "__main__":
    unittest.main()
