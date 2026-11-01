# SPDX-License-Identifier: Apache-2.0
import importlib.util
import io
import json
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "check-loki-rules.py"
spec = importlib.util.spec_from_file_location("check_loki_rules", SCRIPT)
subject = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = subject
spec.loader.exec_module(subject)

REAL_LOKI_DIAGNOSTIC = (
    'level=error caller=ruler.go:579 msg="unable to list rules" '
    'err="failed to list rule groups for user fake: failed to list rule group for user fake '
    'and namespace bad.yaml: error parsing /rules/fake/bad.yaml: /rules/fake/bad.yaml: '
    "could not parse expression for alert 'SelfTestBad' in group 'selftest.bad': "
    'parse error at line 1, col 19: syntax error: unexpected (, expecting , or )"'
)


class FakeClock:
    def __init__(self):
        self.value = 0

    def __call__(self):
        self.value += 1
        return self.value


def valid_rule():
    return "groups:\n- name: fixture\n  rules:\n  - alert: Fixture\n    expr: up == 0\n"


class ParseEvidenceTests(unittest.TestCase):
    def test_parser_evidence_requires_all_markers_and_exact_mounted_filename(self):
        line = ('msg="unable to list rules" err="error parsing /rules/fake/bad.yaml: '
                'could not parse expression for alert Fixture"')
        marker = subject._confirmed_parse_error(line, {"bad"})
        self.assertIn(f"{subject.PARSE_REJECTION_MARKER} [/rules/fake/bad.yaml]:", marker)
        for other in (
            line.replace("bad.yaml", "other.yaml"),
            line.replace("bad.yaml", "bad.yaml.backup"),
            line.replace("could not parse expression", "temporary storage error"),
            line.replace("unable to list rules", "ruler warning"),
            ('msg="unable to list rules" err="error parsing /rules/fake/other.yaml: '
             'expression references /rules/fake/bad.yaml; could not parse expression"'),
        ):
            with self.subTest(other=other):
                self.assertIsNone(subject._confirmed_parse_error(other, {"bad"}))

    def test_captured_pinned_loki_diagnostic_matches_typed_evidence(self):
        marker = subject._confirmed_parse_error(REAL_LOKI_DIAGNOSTIC, {"bad"})
        self.assertIn(f"{subject.PARSE_REJECTION_MARKER} [/rules/fake/bad.yaml]:", marker)

    def run_load(self, logs, rule_response):
        run_result = SimpleNamespace(returncode=0, stdout="", stderr="")

        def docker_run(args, **kwargs):
            if args[:2] == ["docker", "logs"]:
                return SimpleNamespace(returncode=0, stdout=logs, stderr="")
            return run_result

        responses = iter(rule_response) if isinstance(rule_response, list) else None

        def get(url, timeout=5.0):
            if url.endswith("/ready"):
                return "ready\n"
            return next(responses) if responses is not None else rule_response

        with (
            patch.object(subject, "CONTAINER", "test-loki-rules"),
            patch.object(subject, "PORT", 39189),
            patch.object(subject.subprocess, "run", side_effect=docker_run),
            patch.object(subject, "_get", side_effect=get),
            patch.object(subject.time, "sleep"),
            patch.object(subject.time, "monotonic", FakeClock()),
        ):
            return subject.load_into_ruler({"bad": valid_rule()}, quiet=True)

    def test_matching_mounted_parse_error_fails_early_with_explicit_marker(self):
        logs = ('msg="unable to list rules" err="error parsing /rules/fake/bad.yaml: '
                'could not parse expression for alert Fixture"')
        ok, detail = self.run_load(logs, json.dumps({"data": {"groups": []}}))
        self.assertFalse(ok)
        self.assertIn(subject.PARSE_REJECTION_MARKER, detail)

    def test_mismatched_or_unrelated_logs_do_not_trigger_early_parse_rejection(self):
        logs = ('msg="unable to list rules" err="error parsing /rules/fake/other.yaml: '
                'could not parse expression for alert Other"')
        ok, detail = self.run_load(logs, json.dumps({"data": {"groups": []}}))
        self.assertFalse(ok)
        self.assertNotIn(subject.PARSE_REJECTION_MARKER, detail)

    def test_unrelated_parse_error_continues_poll_and_later_accepts_healthy_groups(self):
        logs = ('msg="unable to list rules" err="error parsing /rules/fake/other.yaml: '
                'could not parse expression for alert Other"')
        responses = [
            json.dumps({"data": {"groups": []}}),
            json.dumps({"data": {"groups": [{"name": "fixture"}]}}),
        ]
        ok, detail = self.run_load(logs, responses)
        self.assertTrue(ok, detail)

    def test_healthy_loaded_groups_are_accepted_without_reading_logs(self):
        run_result = SimpleNamespace(returncode=0, stdout="", stderr="")
        calls = []

        def docker_run(args, **kwargs):
            calls.append(args)
            return run_result

        def get(url, timeout=5.0):
            if url.endswith("/ready"):
                return "ready\n"
            return json.dumps({"data": {"groups": [{"name": "fixture"}]}})

        with (
            patch.object(subject, "CONTAINER", "test-loki-rules"),
            patch.object(subject, "PORT", 39189),
            patch.object(subject.subprocess, "run", side_effect=docker_run),
            patch.object(subject, "_get", side_effect=get),
            patch.object(subject.time, "monotonic", FakeClock()),
        ):
            ok, detail = subject.load_into_ruler({"bad": valid_rule()}, quiet=True)
        self.assertTrue(ok, detail)
        self.assertFalse(any(args[:2] == ["docker", "logs"] for args in calls))


class NegativeSelfTestTests(unittest.TestCase):
    def run_self_test(self, bad_detail):
        output = io.StringIO()
        with (
            patch.object(subject.shutil, "which", return_value="/usr/bin/docker"),
            patch.object(subject, "check_ruler_wiring", side_effect=[["expected defect"], []]),
            patch.object(subject, "load_into_ruler", side_effect=[(True, ""), (False, bad_detail)]),
            patch("sys.stdout", output),
        ):
            code = subject.self_test()
        return code, output.getvalue()

    def test_startup_readiness_timeout_or_other_failure_is_not_parser_proof(self):
        for detail in (
            "could not start grafana/loki:3.7.8: daemon unavailable",
            "Loki never became ready within 120s",
            "ruler loaded 0 group(s), expected 1. (no parse error logged)",
            (f"{subject.PARSE_REJECTION_MARKER} [/rules/fake/good.yaml]: "
             "error parsing /rules/fake/good.yaml mentions /rules/fake/bad.yaml"),
        ):
            with self.subTest(detail=detail):
                code, output = self.run_self_test(detail)
                self.assertEqual(code, 1)
                self.assertIn("invalid LogQL was not specifically rejected", output)

    def test_matching_parse_proof_satisfies_negative_self_test(self):
        detail = (f"{subject.PARSE_REJECTION_MARKER} [/rules/fake/bad.yaml]: "
                  "error parsing /rules/fake/bad.yaml")
        code, output = self.run_self_test(detail)
        self.assertEqual(code, 0, output)
        self.assertIn("a file with unparseable LogQL is rejected", output)


if __name__ == "__main__":
    unittest.main()
