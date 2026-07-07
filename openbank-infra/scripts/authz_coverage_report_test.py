#!/usr/bin/env python3
"""Unit tests for authz-coverage-report.py's service-local ext rego extraction.

Focused on the discover_ext_rego() / _parse_ext_rego() / _extract_block() path added for
ADR-0034 Phase 5 (issues #263/#266): parsing the `allowed_reasons` heredoc that
gen-<svc>-opa-bundle.sh generators inline, and the failure handling around it. The
module under test has a hyphenated filename (not import-able as a normal module), so it
is loaded via importlib — the same technique CI/dev use to run it as a script.

Run:  python3 -m unittest openbank-infra/scripts/authz_coverage_report_test.py -v
  or: python3 openbank-infra/scripts/authz_coverage_report_test.py
"""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

_SCRIPT_PATH = Path(__file__).parent / "authz-coverage-report.py"
_spec = importlib.util.spec_from_file_location("authz_coverage_report", _SCRIPT_PATH)
authz = importlib.util.module_from_spec(_spec)
assert _spec.loader is not None
_spec.loader.exec_module(authz)


class ParseExtRegoHappyPathTest(unittest.TestCase):
    """The extraction logic on a well-formed ext rego body (mirrors pid_rest_ext.rego)."""

    def test_exact_action_rule(self) -> None:
        rego = """
package openbank.rest

allowed_reasons contains "operator-pid-resolve" if {
    input.principal.type == "HUMAN"
    input.action == "pid.resolve"
}
"""
        rules = authz._parse_ext_rego(rego, "pid_rest_ext.rego")
        self.assertIn(("exact", "pid.resolve", "operator-pid-resolve", "pid_rest_ext.rego"), rules)

    def test_action_in_set_rule_expands_each_member(self) -> None:
        rego = """
allowed_reasons contains "customer-eudi-request" if {
    input.principal.type == "HUMAN"
    input.action in {"identity.eudi.request", "identity.eudi.poll", "identity.eudi.verify"}
}
"""
        rules = authz._parse_ext_rego(rego, "pid_rest_ext.rego")
        actions = {value for kind, value, _, _ in rules if kind == "exact"}
        self.assertEqual(
            actions, {"identity.eudi.request", "identity.eudi.poll", "identity.eudi.verify"}
        )

    def test_prefix_rule(self) -> None:
        rego = """
allowed_reasons contains "operator-identity-write" if {
    input.principal.type == "HUMAN"
    startswith(input.action, "identity.")
}
"""
        rules = authz._parse_ext_rego(rego, "pid_rest_ext.rego")
        self.assertIn(("prefix", "identity.", "operator-identity-write", "pid_rest_ext.rego"), rules)

    def test_nested_braces_do_not_truncate_block(self) -> None:
        # allowed_reasons bodies routinely contain their own braces (the `in {...}` set);
        # _extract_block must match the OUTER closing brace, not the first one it sees.
        rego = """
allowed_reasons contains "multi" if {
    input.action in {"a.b", "c.d"}
    input.action == "e.f"
}
"""
        rules = authz._parse_ext_rego(rego, "x_rest_ext.rego")
        actions = {value for _, value, _, _ in rules}
        self.assertEqual(actions, {"a.b", "c.d", "e.f"})

    def test_ext_covered_matches_exact_and_prefix(self) -> None:
        rules: list[authz.ExtRule] = [
            ("exact", "pid.resolve", "operator-pid-resolve", "pid_rest_ext.rego"),
            ("prefix", "identity.", "operator-identity-write", "pid_rest_ext.rego"),
        ]
        self.assertEqual(
            authz.ext_covered(rules, "pid.resolve"),
            ("operator-pid-resolve", "pid_rest_ext.rego"),
        )
        self.assertEqual(
            authz.ext_covered(rules, "identity.eudi.issue"),
            ("operator-identity-write", "pid_rest_ext.rego"),
        )
        self.assertIsNone(authz.ext_covered(rules, "ledger.create"))


class ParseExtRegoMalformedInputTest(unittest.TestCase):
    """The review feedback: malformed generator output must not silently produce wrong
    coverage numbers or crash the whole report — it must fail loudly/locally instead."""

    def test_unbalanced_braces_raises_value_error(self) -> None:
        # A hand-edited generator that drops the closing brace of an allowed_reasons
        # block. This must not be swallowed into an empty (silently wrong) result.
        rego = """
allowed_reasons contains "broken" if {
    input.action == "a.b"
"""
        with self.assertRaises(ValueError):
            authz._parse_ext_rego(rego, "broken_rest_ext.rego")

    def test_no_allowed_reasons_block_returns_empty_not_error(self) -> None:
        # A heredoc that legitimately has no allowed_reasons (e.g. comment-only ext rego)
        # is not malformed — it just contributes no rules.
        rego = "package openbank.rest\n# nothing here yet\n"
        self.assertEqual(authz._parse_ext_rego(rego, "x_rest_ext.rego"), [])

    def test_discover_ext_rego_skips_malformed_generator_without_crashing(self) -> None:
        """End-to-end: discover_ext_rego() must not raise when one generator script has
        an unbalanced-brace ext rego heredoc — it should skip that generator (with a
        warning) and still return results for the others, matching the existing
        skip-with-warning convention used for the other malformed-input branches in
        this function (filename/heredoc count mismatch, unmappable component)."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            good_dir = root / "openbank-infra/gitops/components/pid"
            bad_dir = root / "openbank-infra/gitops/components/badsvc"
            good_dir.mkdir(parents=True)
            bad_dir.mkdir(parents=True)

            (good_dir / "gen-pid-opa-bundle.sh").write_text(
                """PID_REST_EXT=$(cat << 'REGO'
allowed_reasons contains "operator-pid-resolve" if {
    input.action == "pid.resolve"
}
REGO
)
echo "pid_rest_ext.rego: |"
"""
            )
            (bad_dir / "gen-badsvc-opa-bundle.sh").write_text(
                """BADSVC_REST_EXT=$(cat << 'REGO'
allowed_reasons contains "broken" if {
    input.action == "a.b"
REGO
)
echo "badsvc_rest_ext.rego: |"
"""
            )

            module_dirs = {"openbank-pid-service", "openbank-badsvc-service"}
            # Must not raise.
            result = authz.discover_ext_rego(root, module_dirs)

            self.assertIn("openbank-pid-service", result)
            self.assertNotIn("openbank-badsvc-service", result)
            pid_actions = {value for _, value, _, _ in result["openbank-pid-service"]}
            self.assertEqual(pid_actions, {"pid.resolve"})

    def test_extract_block_raises_on_missing_close_brace(self) -> None:
        text = 'allowed_reasons contains "x" if {\n    input.action == "a.b"\n'
        brace_start = text.index("{")
        with self.assertRaises(ValueError):
            authz._extract_block(text, brace_start)


if __name__ == "__main__":
    sys.exit(unittest.main())
