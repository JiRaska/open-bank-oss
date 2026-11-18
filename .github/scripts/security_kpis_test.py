# SPDX-License-Identifier: Apache-2.0
"""Run the canonical offline KPI regressions in the required governance test suite."""

import contextlib
import importlib.util
import io
import json
import subprocess
from pathlib import Path
import unittest
from unittest.mock import patch


class SecurityKpisRegressionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        path = Path(__file__).with_name("security-kpis.py")
        spec = importlib.util.spec_from_file_location("security_kpis_regression", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        cls.collector = module

    def test_canonical_offline_self_test(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            result = self.collector.self_test()
        self.assertEqual(result, 0, output.getvalue())


    def test_remediation_aggregates_every_page(self):
        alerts = [[
            {"state": "fixed", "created_at": "2025-01-01T00:00:00Z", "fixed_at": "2025-01-03T00:00:00Z",
             "security_advisory": {"severity": "critical"}},
        ], [
            {"state": "fixed", "created_at": "2025-01-01T00:00:00Z", "fixed_at": "2025-01-07T00:00:00Z",
             "security_advisory": {"severity": "high"}},
            {"state": "open", "created_at": "2025-01-01T00:00:00Z", "security_advisory": {"severity": "high"}},
        ]]
        with patch.dict("os.environ", {"MTTR_GH_TOKEN": "fixture"}), patch(
            "subprocess.run", return_value=subprocess.CompletedProcess([], 0, stdout=json.dumps(alerts).encode()),
        ) as run:
            result = self.collector.collect_mttr()
        self.assertIn("--paginate", run.call_args.args[0])
        self.assertEqual(result["fixedCount"], 2)
        self.assertEqual(result["medianFixDays"], 4.0)
        self.assertEqual(result["openCount"], 1)

    def test_failure_mid_pagination_does_not_publish_partial_evidence(self):
        error = subprocess.CalledProcessError(1, ["gh", "api"], output=b"[[{}]]", stderr=b"gh: Forbidden (HTTP 403)")
        with patch.dict("os.environ", {"MTTR_GH_TOKEN": "fixture"}), patch("subprocess.run", side_effect=error):
            result = self.collector.collect_mttr()
        self.assertEqual(result, {"available": False, "reason": "dependabot_access_unavailable"})


if __name__ == "__main__":
    unittest.main()
