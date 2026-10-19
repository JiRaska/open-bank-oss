# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Offline regression proof for threat-model evidence false greens; no GitHub calls."""
import copy
import importlib.util
import inspect
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

HERE = Path(os.environ.get("THREAT_EVIDENCE_SOURCE_ROOT", Path(__file__).parent))


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


claims = module("claims_regression", "check-threat-model-claims.py")
collector = module("collector_regression", "collect-test-run-evidence.py")


def trusted():
    repo = "JiRaska/open-bank-oss"
    return {"id": 42, "run_attempt": 2, "head_sha": "a" * 40, "head_branch": "main",
            "event": "schedule", "path": ".github/workflows/services-ci.yml", "name": "Services CI",
            "repository": {"full_name": repo}, "head_repository": {"full_name": repo},
            "html_url": f"https://github.com/{repo}/actions/runs/42", "status": "completed",
            "conclusion": "failure"}


def envelope(cases):
    run = trusted()
    return {"schemaVersion": 1, "component": "openbank-x", "run": {
        "id": "42", "attempt": 2, "commit": run["head_sha"], "branch": "main", "workflow": run["name"],
        "url": run["html_url"], "observedAt": "2026-10-17T03:00:00Z"}, "suites": [], "coverage": None,
        "testInfrastructure": {"declared": [], "observed": []}, "testCases": [
            {"fingerprint": f"{i:024x}", "kind": "unit", "name": "guards", "durationMs": 0, **c}
            for i, c in enumerate(cases)]}


class EvidenceRegression(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        model = self.root / "docs/threat-models/openbank-x.md"
        model.parent.mkdir(parents=True)
        model.write_text("| ID | Threat | Mitigation |\n|---|---|---|\n| T1 | Replay | `GuardTest` asserts it |\n")
        self.corpus = type("Corpus", (), {"resolve": lambda _, sym: True, "blobs": {
            "openbank-x/src/test/kotlin/p/GuardTest.kt": "package p\nclass GuardTest {\n}"}})()

    def load(self, env, metadata=None):
        path = self.root / "env.json"
        path.write_text(json.dumps(env))
        if "trusted_run" in inspect.signature(claims.load_evidence).parameters:
            return claims.load_evidence([path], trusted() if metadata is None else metadata)
        return claims.load_evidence([path])

    def findings(self, cases):
        return claims.evidence_findings(self.root, ["openbank-x"], self.corpus, self.load(envelope(cases)))[0]

    def test_latest_failed_or_pending_run_never_selects_older_success(self):
        for status in ("completed", "in_progress"):
            selected = trusted()
            selected["status"] = status
            commands = []
            def fake(command, commands=commands, selected=selected, **kwargs):
                commands.append(command)
                if "list" in command:
                    return subprocess.CompletedProcess(command, 0, '[{"databaseId": 1}]')
                if "/runs?" in command[2]:
                    return subprocess.CompletedProcess(command, 0, json.dumps({"workflow_runs": [selected]}))
                return subprocess.CompletedProcess(command, 0, "")
            with patch.object(claims.subprocess, "run", side_effect=fake):
                result = claims.fetch_latest_evidence(self.root)
            self.assertFalse(any("--status" in c for c in commands), commands)
            if status == "in_progress":
                self.assertEqual(result, ([], None))
            else:
                self.assertEqual(result[1]["id"], 42)

    def test_wrong_source_future_timestamp_and_malformed_metadata_are_unknown(self):
        good = envelope([{"classname": "p.GuardTest", "state": "passed"}])
        self.assertTrue(self.load(good))  # scheduled source may be older than the reviewed PR
        for field, value in (("commit", "b" * 40), ("id", "41"), ("attempt", 1),
                             ("workflow", "Other CI"), ("branch", "feature"),
                             ("url", "https://github.com/other/fork/actions/runs/42"),
                             ("observedAt", "2099-01-01T00:00:00Z")):
            bad = copy.deepcopy(good)
            bad["run"][field] = value
            with self.subTest(field=field):
                self.assertEqual(self.load(bad), {})
        for field, value in (("event", "pull_request"), ("repository", {"full_name": "other/fork"}),
                             ("head_repository", None), ("path", ".github/workflows/other.yml"),
                             ("id", "42"), ("status", "queued")):
            bad = trusted()
            bad[field] = value
            with self.subTest(metadata=field):
                self.assertEqual(self.load(good, bad), {})
        for malformed in ({}, {"testCases": None}, {"run": []}, {"testCases": [None]}):
            bad = copy.deepcopy(good)
            bad.update(malformed)
            if not malformed:
                bad = {}
            self.assertEqual(self.load(bad), {})

    def test_explicit_skipped_citation_survives_other_passed_verifies(self):
        findings = self.findings([{"classname": "p.GuardTest", "state": "skipped"},
                                 {"classname": "p.OtherTest", "state": "passed", "verifies": ["x/T1"]}])
        self.assertTrue(findings)
        self.assertIn("skipped", findings[0][3])

    def test_wrong_package_does_not_satisfy_simple_citation(self):
        self.assertTrue(self.findings([{"classname": "wrong.GuardTest", "state": "passed"}]))
        self.assertEqual(self.findings([{"classname": "p.GuardTest", "state": "passed"}]), [])
        model = self.root / "docs/threat-models/openbank-x.md"
        model.write_text(model.read_text().replace("`GuardTest`", "`p.GuardTest`"))
        self.assertTrue(self.findings([{"classname": "wrong.GuardTest", "state": "passed"}]))
        self.assertEqual(self.findings([{"classname": "p.GuardTest", "state": "passed"}]), [])

    def test_commented_method_tag_does_not_credit_executed_method(self):
        source = self.root / "src/test/kotlin/p/GuardTest.kt"
        source.parent.mkdir(parents=True)
        source.write_text('class GuardTest {\n/* nested /* comment */\n'
                          '    @Test\n    @Tag("verifies:x/T1")\n    fun passes() {}\n*/\n'
                          '    @Test\n    fun passes() {}\n}\n')
        self.assertEqual(collector.verifies_ids(self.root, "p.GuardTest", "passes()"), [])
        source.write_text('class GuardTest {\n val example = """\n'
                          '    @Tag("verifies:x/T1")\n    fun passes() {}\n"""\n'
                          '    @Test\n    fun passes() {}\n}\n')
        self.assertEqual(collector.verifies_ids(self.root, "p.GuardTest", "passes()"), [])
        source.write_text('@Tag("verifies:x/T2")\nclass GuardTest {\n'
                          ' val url = "https://example.test/*text*/"\n'
                          '    @Test\n    @Tag("verifies:x/T1")\n    fun passes() {}\n}\n')
        self.assertEqual(collector.verifies_ids(self.root, "p.GuardTest", "passes()"), ["x/T1", "x/T2"])

    def test_annotation_string_lookalikes_do_not_supply_tag_ids(self):
        source = self.root / "src/test/kotlin/p/GuardTest.kt"
        source.parent.mkdir(parents=True)
        displays = [
            '@DisplayName("""example @Tag("verifies:x/T1")""")',
            r'@DisplayName("example @Tag(\"verifies:x/T1\")")',
            r'@DisplayName("escaped \\\" @Tag(\"verifies:x/T1\")")',
        ]
        for display in displays:
            for placement in ("method", "class"):
                with self.subTest(display=display, placement=placement):
                    if placement == "method":
                        text = 'class GuardTest {\n    @Test\n    ' + display + '\n    fun passes() {}\n}\n'
                    else:
                        text = display + '\nclass GuardTest {\n    @Test\n    fun passes() {}\n}\n'
                    source.write_text(text)
                    self.assertEqual(collector.verifies_ids(self.root, "p.GuardTest", "passes()"), [])
                    # A real class and method annotation beside the lookalike still count.
                    text = '@Tag("verifies:x/C1")\n' + text.replace(
                        '    @Test\n', '    @Test\n    @Tag("verifies:x/M1")\n')
                    source.write_text(text)
                    self.assertEqual(collector.verifies_ids(self.root, "p.GuardTest", "passes()"),
                                     ["x/C1", "x/M1"])

    def test_method_tags_belong_to_the_executed_nested_class(self):
        source = self.root / "src/test/kotlin/p/GuardTest.kt"
        source.parent.mkdir(parents=True)
        source.write_text('@Tag("verifies:x/C1")\nclass GuardTest {\n'
                          '    @Test\n    fun passes() {}\n'
                          '    @Nested\n    @Tag("verifies:x/C2")\n    inner class Inner {\n'
                          '        @Test\n        @Tag("verifies:x/T1")\n        fun passes() {}\n'
                          '    }\n}\n')
        self.assertEqual(collector.verifies_ids(self.root, "p.GuardTest", "passes()"), ["x/C1"])
        self.assertEqual(collector.verifies_ids(self.root, "p.GuardTest$Inner", "passes()"),
                         ["x/C1", "x/C2", "x/T1"])
        # A tag in the outer method must not migrate to an untagged nested method either.
        source.write_text('class GuardTest {\n'
                          '    @Test\n    @Tag("verifies:x/T1")\n    fun passes() {}\n'
                          '    @Nested\n    inner class Inner {\n'
                          '        @Test\n        fun passes() {}\n    }\n}\n')
        self.assertEqual(collector.verifies_ids(self.root, "p.GuardTest", "passes()"), ["x/T1"])
        self.assertEqual(collector.verifies_ids(self.root, "p.GuardTest$Inner", "passes()"), [])


if __name__ == "__main__":
    unittest.main()
