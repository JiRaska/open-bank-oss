#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Run the real bypass checker and gate runner against synthetic GitHub responses."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]
GATE_ID = "ruleset-bypass-actors"
SUMMARY = {"id": 1, "name": "fixture", "target": "branch", "enforcement": "active"}
CONDITIONS = {"ref_name": {"include": ["refs/heads/main"]}}
EMPTY = {"conditions": CONDITIONS, "bypass_actors": []}


class BypassVerdictTest(unittest.TestCase):
    def test_checker_runner_and_json_agree(self):
        gate = next(g for g in yaml.safe_load((ROOT / ".github/gates/gates.yaml").read_text())["gates"]
                    if g["id"] == GATE_ID)
        # This test is part of the gate's self-test; do not recursively invoke itself.
        gate.pop("selftest", None)
        gate.pop("selftest_expect", None)
        cases = [
            ("verified-empty", [SUMMARY], EMPTY, "ok", None, 1),
            ("missing-permission-field", [SUMMARY], {"conditions": CONDITIONS},
             "failed", "not_evaluated", "UNRESOLVED"),
            ("permission-denied", [SUMMARY], {"error": "Resource not accessible by integration (HTTP 403)"},
             "failed", "not_evaluated", "UNRESOLVED"),
            ("rate-limit", [SUMMARY], {"error": "API rate limit exceeded (HTTP 403)"},
             "failed", "not_evaluated", "UNRESOLVED"),
            ("bad-credentials", [SUMMARY], {"error": "Bad credentials (HTTP 401)"},
             "failed", "not_evaluated", "UNRESOLVED"),
            ("null-list", [SUMMARY], {"conditions": CONDITIONS, "bypass_actors": None},
             "failed", "not_evaluated", "UNRESOLVED"),
            ("object-instead-of-list", [SUMMARY], {"conditions": CONDITIONS, "bypass_actors": {}},
             "failed", "not_evaluated", "UNRESOLVED"),
            ("undeclared-actor", [SUMMARY], {"conditions": CONDITIONS, "bypass_actors": [
                {"actor_id": 999, "actor_type": "Team", "bypass_mode": "always"}]},
             "failed", "content", 1),
            ("no-rulesets", [], EMPTY, "failed", "content", 0),
            ("inactive-ruleset", [{**SUMMARY, "enforcement": "disabled"}], EMPTY,
             "failed", "content", 0),
            ("unrelated-ref", [SUMMARY], {**EMPTY, "conditions": {"ref_name": {"include": ["refs/heads/other"]}}},
             "failed", "content", 0),
            ("second-ruleset-unreadable", [SUMMARY, {**SUMMARY, "id": 2}], EMPTY,
             "failed", "not_evaluated", "UNRESOLVED"),
        ]
        with tempfile.TemporaryDirectory(prefix="bypass-verdict-test-") as tmp:
            directory = Path(tmp)
            manifest = directory / "gates.yaml"
            manifest.write_text("gates:\n" + textwrap.indent(yaml.safe_dump([gate], sort_keys=False), "  "))
            stub = directory / "gh"
            stub.write_text(f"#!{sys.executable}\n" + '''import json, os, sys
from pathlib import Path
responses = json.loads(Path(os.environ["BYPASS_TEST_RESPONSES"]).read_text())
key = sys.argv[-1].split("/rulesets", 1)[1]
value = responses[key]
if isinstance(value, dict) and "error" in value:
    print(value["error"], file=sys.stderr)
    sys.exit(1)
print(json.dumps(value))
''')
            stub.chmod(0o755)
            fixture = directory / "responses.json"
            evidence = directory / "evidence.json"
            for name, summaries, detail, status, kind, subjects in cases:
                with self.subTest(case=name):
                    fixture.write_text(json.dumps({"": summaries, "/1": detail,
                                                   "/2": {"conditions": CONDITIONS}}))
                    env = {**os.environ, "PATH": f"{tmp}:{os.environ['PATH']}", "CI": "false",
                           "BYPASS_TEST_RESPONSES": str(fixture)}
                    result = subprocess.run(
                        [sys.executable, str(ROOT / ".github/scripts/run-gates.py"),
                         "--root", str(ROOT), "--manifest", str(manifest), "--all",
                         "--jobs", "1", "--json", str(evidence)],
                        env=env, capture_output=True, text=True, timeout=30,
                    )
                    output = result.stdout + result.stderr
                    self.assertEqual(result.returncode, 0 if status == "ok" else 1, output)
                    record, = json.loads(evidence.read_text())
                    self.assertEqual((record["status"], record["failure_kind"], record["subjects"]),
                                     (status, kind, subjects), output)
                    verdicts = output.split("--- verdicts (1 gates) ---", 1)[1]
                    self.assertIn("PASS" if status == "ok" else "FAIL", verdicts)
                    if kind == "not_evaluated":
                        self.assertNotIn("PASS", verdicts)
                        self.assertIn("(not evaluated)", verdicts)
                    if name == "verified-empty":
                        self.assertIn("0 live bypass actor(s), 0 undeclared, 0 stale", output)


if __name__ == "__main__":
    unittest.main()
