"""The suite runner retains every result, including when one discovery root fails."""

import contextlib
import io
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from run_governance_script_tests import run_suites  # noqa: E402


class GovernanceSuiteRunnerTest(unittest.TestCase):
    def test_concurrent_suites_all_report_even_when_one_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory) / "second-started"
            first = (
                sys.executable, "-c",
                "import pathlib,time; p=pathlib.Path(__import__('sys').argv[1]); "
                "deadline=time.monotonic()+3; "
                "exec('while not p.exists() and time.monotonic()<deadline: time.sleep(0.01)'); "
                "assert p.exists(); print('first completed')",
                str(marker),
            )
            second = (sys.executable, "-c", "import pathlib,sys; pathlib.Path(sys.argv[1]).touch(); "
                      "print('second failed'); sys.exit(2)", str(marker))
            third = (sys.executable, "-c", "print('third completed')")
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                result = run_suites((("first", first), ("second", second), ("third", third)))
            self.assertEqual(result, 1)
            self.assertIn("first completed", output.getvalue())
            self.assertIn("second failed", output.getvalue())
            self.assertIn("third completed", output.getvalue())
            self.assertLess(output.getvalue().index("first completed"), output.getvalue().index("second failed"))


if __name__ == "__main__":
    unittest.main()
