#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Exercise the required Services CI job-roster read with real shell semantics."""

import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import unittest


class JobRosterTest(unittest.TestCase):
    def test_partial_502_is_retried_without_accepting_partial_or_duplicate_jobs(self):
        workflow = (Path(__file__).parents[1] / "workflows" / "services-ci.yml").read_text()
        match = re.search(
            r"(?ms)^      - name: Require every selected service build to pass\n.*?^        run: \|\n(.*?)(?=^  #|^  [a-z][a-z-]*:|\Z)",
            workflow,
        )
        self.assertIsNotNone(match)
        script = textwrap.dedent(match.group(1))
        for mode, expected_success, expected_calls in [
            ("recover", True, 2),
            ("persistent", False, 3),
            ("final", False, 1),
        ]:
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / "gh").write_text(textwrap.dedent("""\
                    #!/usr/bin/env python3
                    import os
                    from pathlib import Path
                    import sys

                    counter = Path(os.environ["COUNT_FILE"])
                    n = int(counter.read_text()) + 1 if counter.exists() else 1
                    counter.write_text(str(n))
                    account = '{"name":"build (openbank-account-service) / openbank-account-service (build)","conclusion":"success"}'
                    treasury = '{"name":"build (openbank-treasury-service) / openbank-treasury-service (build)","conclusion":"success"}'
                    if os.environ["GH_STUB_MODE"] == "final":
                        print("gh: Validation Failed (HTTP 422)", file=sys.stderr)
                        sys.exit(1)
                    if os.environ["GH_STUB_MODE"] == "persistent" or n == 1:
                        print(account)  # A failed page may already have emitted data.
                        print("gh: Server Error (HTTP 502)", file=sys.stderr)
                        sys.exit(1)
                    print(account)
                    print(treasury)
                    """))
                (root / "gh").chmod(0o755)
                (root / "sleep").write_text("#!/bin/sh\nexit 0\n")
                (root / "sleep").chmod(0o755)
                jobs = root / "jobs.ndjson"
                env = dict(os.environ, PATH=f"{root}:{os.environ['PATH']}", COUNT_FILE=str(root / "calls"),
                           GH_STUB_MODE=mode, JOB_FILE=str(jobs), GITHUB_REPOSITORY="example/repo",
                           GITHUB_RUN_ID="123", GITHUB_RUN_ATTEMPT="1",
                           SERVICES_JSON=json.dumps(["openbank-account-service", "openbank-treasury-service"]))
                result = subprocess.run(["bash", "-c", script], env=env, capture_output=True, text=True, check=False)
                self.assertEqual(result.returncode == 0, expected_success, result.stderr)
                self.assertEqual((root / "calls").read_text(), str(expected_calls))
                if expected_success:
                    self.assertEqual(len(jobs.read_text().splitlines()), 2)
                else:
                    self.assertFalse(jobs.exists())


if __name__ == "__main__":
    unittest.main()
