# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations

import importlib.util
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

SCRIPT = Path(__file__).with_name("reconcile-test-evidence.py")
SPEC = importlib.util.spec_from_file_location("reconcile_test_evidence", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
RECONCILE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RECONCILE)


STUB_GH = """#!/bin/sh
set -eu
if [ "${1:-}" = api ] && [ "${2:-}" = rate_limit ]; then
  printf '0\\n'
  exit 0
fi
printf '%s\\n' "$*" >> "$GH_STUB_ARGS"
count=$(cat "$GH_STUB_COUNT" 2>/dev/null || echo 0)
count=$((count + 1))
printf '%s\\n' "$count" > "$GH_STUB_COUNT"
case "${GH_STUB_MODE}:${count}" in
  transient:1) printf '[{"workflow_runs":[{"id":0}]}' ; printf 'gh: Gateway Timeout (HTTP 504)\\n' >&2; exit 1 ;;
  rate_limit:1) printf 'gh: API rate limit exceeded (HTTP 403)\\n' >&2; exit 1 ;;
  not_found:1) printf 'gh: Not Found (HTTP 404)\\n' >&2; exit 1 ;;
  invalid_json:1) printf '{invalid\\n'; exit 0 ;;
  timeout:1) sleep 30 ;;
esac
printf '%s\\n' '[{"workflow_runs":[{"id":1}]}]'
"""


class GhJsonTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.stub = self.root / "gh"
        self.stub.write_text(STUB_GH, encoding="utf-8")
        self.stub.chmod(0o755)
        self.count = self.root / "count"
        self.args = self.root / "args"
        self.env = mock.patch.dict(os.environ, {
            "PATH": f"{self.root}{os.pathsep}{os.defpath}",
            "GH_STUB_COUNT": str(self.count),
            "GH_STUB_ARGS": str(self.args),
            "GH_STUB_MODE": "success",
            "GH_RETRY_MAX_WAIT_SECONDS": "0",
        })
        self.env.start()
        self.addCleanup(self.env.stop)
        self.wait_cap = mock.patch.object(RECONCILE, "GH_RETRY_MAX_WAIT_SECONDS", 0)
        self.wait_cap.start()
        self.addCleanup(self.wait_cap.stop)
        self.addCleanup(self.temp.cleanup)

    def count_calls(self):
        return int(self.count.read_text(encoding="utf-8")) if self.count.exists() else 0

    def test_returns_full_slurped_pages(self):
        payload = RECONCILE.gh_json("repos/org/repo/actions/runs")
        self.assertEqual(payload, [{"workflow_runs": [{"id": 1}]}])
        self.assertEqual(self.count_calls(), 1)
        args = self.args.read_text(encoding="utf-8")
        self.assertIn("--paginate", args)
        self.assertIn("--slurp", args)

    def test_retries_shared_vocabulary_transient(self):
        with mock.patch.dict(os.environ, {"GH_STUB_MODE": "transient"}):
            payload = RECONCILE.gh_json("repos/org/repo/actions/runs")
        self.assertEqual(payload, [{"workflow_runs": [{"id": 1}]}])
        self.assertEqual(self.count_calls(), 2)

    def test_retries_rate_limit_using_bounded_shared_helper_wait(self):
        with mock.patch.dict(os.environ, {"GH_STUB_MODE": "rate_limit"}):
            payload = RECONCILE.gh_json("repos/org/repo/actions/runs")
        self.assertEqual(payload, [{"workflow_runs": [{"id": 1}]}])
        self.assertEqual(self.count_calls(), 2)

    def test_final_404_fails_without_retry(self):
        with mock.patch.dict(os.environ, {"GH_STUB_MODE": "not_found"}), \
                self.assertRaisesRegex(RuntimeError, "HTTP 404"):
            RECONCILE.gh_json("repos/org/repo/actions/runs")
        self.assertEqual(self.count_calls(), 1)

    def test_invalid_json_fails_without_retry(self):
        with mock.patch.dict(os.environ, {"GH_STUB_MODE": "invalid_json"}), \
                self.assertRaisesRegex(RuntimeError, "returned invalid JSON"):
            RECONCILE.gh_json("repos/org/repo/actions/runs")
        self.assertEqual(self.count_calls(), 1)

    def test_total_timeout_terminates_wrapper_and_children(self):
        with (
            mock.patch.dict(os.environ, {"GH_STUB_MODE": "timeout"}),
            mock.patch.object(RECONCILE, "GH_API_TOTAL_TIMEOUT_SECONDS", 1),
            self.assertRaisesRegex(RuntimeError, "exceeded its 1s total budget"),
        ):
            RECONCILE.gh_json("repos/org/repo/actions/runs")
        self.assertEqual(self.count_calls(), 1)


if __name__ == "__main__":
    unittest.main()
