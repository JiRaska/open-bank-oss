# SPDX-License-Identifier: Apache-2.0
"""Run the canonical offline KPI regressions in the required governance test suite."""

import contextlib
import importlib.util
import io
from pathlib import Path
import unittest


class SecurityKpisRegressionTest(unittest.TestCase):
    def test_canonical_offline_self_test(self):
        path = Path(__file__).with_name("security-kpis.py")
        spec = importlib.util.spec_from_file_location("security_kpis_regression", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            result = module.self_test()
        self.assertEqual(result, 0, output.getvalue())


if __name__ == "__main__":
    unittest.main()
