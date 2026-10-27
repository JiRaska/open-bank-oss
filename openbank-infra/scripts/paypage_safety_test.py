# SPDX-License-Identifier: Apache-2.0
"""Run the served paypage regression in the existing governance unit-test gate."""

import re
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class PaypageSafetyTest(unittest.TestCase):
    def test_served_payment_scripts(self):
        result = subprocess.run(
            ["node", "--test", "--test-reporter=tap", "openbank-infra/web/tests/paypage.test.cjs"],
            cwd=ROOT,
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
        output = result.stdout + result.stderr
        self.assertEqual(result.returncode, 0, output)
        count = re.search(r"^# tests (\d+)$", result.stdout, re.MULTILINE)
        self.assertIsNotNone(count, output)
        self.assertGreaterEqual(int(count.group(1)), 19, output)
        self.assertRegex(result.stdout, r"(?m)^# skipped 0$", output)


if __name__ == "__main__":
    unittest.main()
