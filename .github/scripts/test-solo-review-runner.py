#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""No provider requests: falsify public-source, execution and approval boundaries."""
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import sys
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("runner", Path(__file__).with_name("solo-review-runner.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
fixtures = runner.load("fixtures", "test-solo-review-proof.py")


def pull():
    return dict(number=12, state="open", head=dict(sha="a" * 40, repo=dict(private=False)),
                base=dict(sha="b" * 40, ref="main", repo=dict(private=False)))


def stream(*, content=None, error=False, result=None):
    return "\n".join(json.dumps(e) for e in [
        dict(type="system", subtype="init", tools=[]),
        dict(type="assistant", message=dict(model="claude-sonnet-test", content=content or [])),
        dict(type="result", subtype="success", is_error=error, session_id="fresh-session",
             result="", total_cost_usd=0.25,
             usage=dict(input_tokens=100, output_tokens=20, cache_read_input_tokens=0,
                        cache_creation_input_tokens=0),
             structured_output=result or dict(verdict="NO_FINDINGS", findings=[], coverage=[])),
    ])


class SupportingContextTest(unittest.TestCase):
    def test_effective_rules_are_complete_and_other_sources_remain_full(self):
        root = Path(runner.__file__).resolve().parents[2]
        supporting = {item["path"]: item for item in runner.supporting_context()}
        self.assertEqual(json.loads(supporting[runner.guard.RULES]["content"]),
                         runner.guard.load_rules(root / runner.guard.RULES))
        for path in (runner.proof.POLICY_INPUTS[0], ".github/scripts/run-gates.py"):
            self.assertEqual(supporting[path]["content"], (root / path).read_text())
        self.assertLess(len(json.dumps(list(supporting.values())).encode()), 150_000)


class DriverTest(unittest.TestCase):
    def setUp(self):
        credentials = patch.dict(os.environ, AGENT_REVIEW_API_ENABLED="true", ANTHROPIC_API_KEY="api-fixture")
        credentials.start()
        self.addCleanup(credentials.stop)

    def test_disabled_or_missing_api_never_invokes_provider(self):
        for enabled, key in (("", "api-fixture"), ("false", "api-fixture"), ("true", ""), ("true", "  ")):
            with self.subTest(enabled=enabled, key_present=bool(key.strip())), \
                    patch.dict(os.environ, AGENT_REVIEW_API_ENABLED=enabled, ANTHROPIC_API_KEY=key), \
                    patch.object(runner.subprocess, "run") as invoke, \
                    patch.object(runner, "anchored") as anchor, \
                    self.assertRaisesRegex(ValueError, "no provider invocation"):
                runner.review("missing-input.json", "unused.json", "correctness", "/trusted/claude")
            invoke.assert_not_called()
            anchor.assert_not_called()

    def test_usage_is_numeric_allowlisted_and_unknown_is_not_zero(self):
        event = dict(type="result", is_error=True, total_cost_usd=1.25,
                     usage=dict(input_tokens=120, output_tokens=30, cache_read_input_tokens=0,
                                cache_creation_input_tokens=9, secret="private-fixture"),
                     result="private-fixture", errors=["private-fixture"])
        summary = runner.usage_summary(json.dumps(event))
        self.assertEqual(summary["tokens"]["input_tokens"], 120)
        self.assertEqual(summary["tokens"]["cache_read_input_tokens"], 0)
        self.assertEqual(summary["cost_usd"], 1.25)
        self.assertNotIn("private-fixture", json.dumps(summary))
        for raw in (None, "partial {", "[]", json.dumps(event) + "\n" + json.dumps(event)):
            unknown = runner.usage_summary(raw)
            self.assertIsNone(unknown["cost_usd"])
            self.assertTrue(all(value is None for value in unknown["tokens"].values()))
        for invalid in (True, -1, "private-fixture", float("nan"), float("inf"), 10 ** 400):
            event["total_cost_usd"] = invalid
            event["usage"]["input_tokens"] = invalid
            summary = runner.usage_summary(json.dumps(event))
            self.assertIsNone(summary["cost_usd"])
            if type(invalid) is not int or invalid < 0:
                self.assertIsNone(summary["tokens"]["input_tokens"])

    def test_failed_invocations_preserve_accounting_without_admission(self):
        data = dict(payload={}, subject=dict(repo="example/bank", pr=12,
                    input_digest=runner.proof.digest({})))
        raw = json.dumps(dict(type="result", is_error=True, total_cost_usd=0.5,
                              usage=dict(input_tokens=100)))
        outcomes = [subprocess.CompletedProcess([], 1, stdout=raw, stderr="private-fixture"),
                    subprocess.CompletedProcess([], 0, stdout="invalid", stderr=""),
                    subprocess.TimeoutExpired("cli", 900, output=raw.encode()),
                    OSError("private-fixture")]
        for outcome in outcomes:
            with self.subTest(outcome=type(outcome).__name__), tempfile.TemporaryDirectory() as directory:
                source, output = Path(directory) / "in.json", Path(directory) / "out.json"
                source.write_text(json.dumps(data))
                with patch.object(runner, "anchored", return_value=("example/bank", "d" * 40)), \
                        patch.object(runner, "public_subject", return_value=pull()), \
                        patch.object(runner.proof, "validate_subject"), \
                        patch.object(runner.proof, "validate_policy_snapshot"), \
                        patch.object(runner.subprocess, "run") as invoke:
                    if isinstance(outcome, Exception):
                        invoke.side_effect = outcome
                    else:
                        invoke.return_value = outcome
                    with self.assertRaises((ValueError, subprocess.TimeoutExpired, OSError)):
                        runner.review(source, output, "correctness", "/trusted/cli")
                self.assertFalse(output.exists())
                accounting = json.loads(Path(str(output) + ".usage.json").read_text())
                self.assertNotIn("private-fixture", json.dumps(accounting))
                if isinstance(outcome, subprocess.TimeoutExpired) or getattr(outcome, "returncode", None) == 1:
                    self.assertEqual(accounting["cost_usd"], 0.5)
                else:
                    self.assertIsNone(accounting["cost_usd"])

    def test_success_without_complete_bounded_accounting_cannot_produce_admission(self):
        data = dict(payload={}, subject=dict(repo="example/bank", pr=12,
                    input_digest=runner.proof.digest({})))
        mutations = [
            {"total_cost_usd": None}, {"total_cost_usd": True},
            {"total_cost_usd": float("nan")}, {"total_cost_usd": 1.01},
            {"usage": {}}, {"usage": dict(input_tokens=100, output_tokens=20)},
        ]
        for mutation in mutations:
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                events = [json.loads(line) for line in stream().splitlines()]
                events[-1].update(mutation)
                raw = "\n".join(json.dumps(event) for event in events)
                source, output = Path(directory) / "in.json", Path(directory) / "out.json"
                source.write_text(json.dumps(data))
                with patch.object(runner, "anchored", return_value=("example/bank", "d" * 40)), \
                        patch.object(runner, "public_subject", return_value=pull()), \
                        patch.object(runner.proof, "validate_subject"), \
                        patch.object(runner.proof, "validate_policy_snapshot"), \
                        patch.object(runner.subprocess, "run", return_value=
                                     subprocess.CompletedProcess([], 0, stdout=raw, stderr="")):
                    with self.assertRaisesRegex(ValueError, "accounting|cost exceeded"):
                        runner.review(source, output, "correctness", "/trusted/cli")
                self.assertFalse(output.exists())
                accounting = json.loads(Path(str(output) + ".usage.json").read_text())
                self.assertEqual(accounting["execution"], "exited")
                if mutation.get("total_cost_usd") == 1.01:
                    self.assertEqual(accounting["cost_usd"], 1.01)

    def test_policy_failures_are_classified_without_exposing_details(self):
        for error in (runner.guard.Undetermined("private fixture"), ImportError("private fixture")):
            with patch.object(sys, "argv", ["runner", "prepare", "--pr", "12", "--output", "unused"]), \
                    patch.object(runner, "prepare", side_effect=error), \
                    patch.object(sys, "stderr", new_callable=io.StringIO) as stderr:
                self.assertEqual(runner.main(), 2)
                self.assertIn("classification policy unavailable", stderr.getvalue())
                self.assertNotIn("private fixture", stderr.getvalue())

    def test_malformed_stream_shapes_raise_controlled_errors(self):
        events = [json.loads(line) for line in stream().splitlines()]
        for value in (None, [], "private-fixture"):
            with self.subTest(event=value), self.assertRaisesRegex(ValueError, "malformed model stream event"):
                runner.parse_stream(json.dumps(value), "correctness", {})
        for message in (None, [], "private-fixture", {"model": "m", "content": "text"},
                        {"model": "m", "content": [None]}, {"model": "m"}):
            events[1]["message"] = message
            with self.subTest(message=message), self.assertRaisesRegex(ValueError, "malformed assistant"):
                runner.parse_stream("\n".join(json.dumps(e) for e in events), "correctness", {})

    def test_missing_or_executable_tool_inventory_rejected(self):
        events = [json.loads(line) for line in stream().splitlines()]
        for inventory in (None, ["Bash"], ["StructuredOutput", "WebFetch"]):
            events[0]["tools"] = inventory
            with self.subTest(inventory=inventory), self.assertRaisesRegex(ValueError, "tool inventory"):
                runner.parse_stream("\n".join(json.dumps(e) for e in events), "correctness", {})
        with self.assertRaisesRegex(ValueError, "tool inventory"):
            runner.parse_stream("\n".join(json.dumps(e) for e in events[1:]), "correctness", {})

    def test_provider_failure_diagnostics_never_echo_output(self):
        for message, category in (("authentication_error", "AUTHENTICATION"),
                                  ("rate_limit_error", "RATE_LIMIT"),
                                  ("prompt is too long", "CONTEXT_LIMIT"),
                                  ("ENOTFOUND", "PROVIDER_UNAVAILABLE"),
                                  ("unexpected response", "UNKNOWN")):
            proc = subprocess.CompletedProcess([], 1, stdout=json.dumps(dict(type="result", is_error=True, errors=["secret-fixture " + message])),
                                               stderr="private-source-fixture")
            with self.subTest(category=category):
                self.assertEqual(runner.invocation_failure(proc),
                                 f"model invocation failed: category={category}, exit_code=1; no admission produced")

    def test_provider_quota_status_survives_changed_error_wording(self):
        proc = subprocess.CompletedProcess([], 1, stdout=json.dumps(dict(
            type="result", is_error=True, api_error_status=429, result="provider-specific-private-message")), stderr="")
        self.assertEqual(runner.invocation_failure(proc),
                         "model invocation failed: category=RATE_OR_USAGE_LIMIT, exit_code=1; no admission produced")

    def test_provider_limits_are_distinguished_without_automatic_retry(self):
        cases = (
            (429, "credit balance is too low", "SPEND_LIMIT"),
            (429, "insufficient_quota", "SPEND_LIMIT"),
            (None, "max budget exceeded", "SPEND_LIMIT"),
            (429, "hit your weekly limit", "USAGE_LIMIT"),
            (429, "rate_limit_error", "RATE_LIMIT"),
            (529, "overloaded_error", "PROVIDER_OVERLOADED"),
            (529, "provider-specific-private-message", "PROVIDER_OVERLOADED"),
            (503, "provider-specific-private-message", "PROVIDER_UNAVAILABLE"),
            (401, "provider-specific-private-message", "AUTHENTICATION"),
            (429, "provider-specific-private-message", "RATE_OR_USAGE_LIMIT"),
        )
        for status, message, category in cases:
            proc = subprocess.CompletedProcess([], 1, stdout=json.dumps(dict(
                type="result", is_error=True, api_error_status=status,
                errors=["secret-fixture " + message])), stderr="")
            with self.subTest(status=status, message=message):
                self.assertEqual(runner.invocation_failure(proc),
                                 f"model invocation failed: category={category}, exit_code=1; no admission produced")

    def test_finished_stream_observes_model_and_session(self):
        report = runner.parse_stream(stream(), "correctness", fixtures.bundle()["subject"])
        self.assertEqual(report["model"], "claude-sonnet-test")
        self.assertEqual(report["session_id"], "fresh-session")

    def test_error_tool_use_malformed_and_missing_events(self):
        for raw in (stream(error=True), stream(content=[dict(type="tool_use", name="Bash")]),
                    "not JSON", "", json.dumps(dict(type="result", subtype="success", is_error=False))):
            with self.subTest(raw=raw), self.assertRaises((ValueError, KeyError)):
                runner.parse_stream(raw, "correctness", {})

    def test_structured_output_transport_is_not_an_execution_tool(self):
        response = dict(verdict="NO_FINDINGS", findings=[], coverage=[])
        report = runner.parse_stream(stream(content=[dict(type="tool_use", name="StructuredOutput",
                                     input=response)], result=response), "correctness", {})
        self.assertEqual(report["tool_uses"], 0)
        self.assertEqual(report["structured_output_uses"], 1)
        self.assertEqual(report["response"], response)

    def test_output_transport_cannot_hide_tools_or_replace_final_result(self):
        response = dict(verdict="NO_FINDINGS", findings=[], coverage=[])
        output = dict(type="tool_use", name="StructuredOutput", input=response)
        for blocks in ([output, dict(type="tool_use", name="Bash", input={})],
                       [dict(output, input={})], [dict(output, name="mcp__StructuredOutput")],
                       [dict(type="server_tool_use", name="web_search", input={})]):
            with self.subTest(blocks=blocks), self.assertRaises(ValueError):
                runner.parse_stream(stream(content=blocks, result=response), "security", {})

    def test_repeated_output_keeps_every_attempt_and_never_erases_findings(self):
        for finding in (False, True):
            data = fixtures.bundle()
            response = data["reports"][0]["response"]
            earlier = dict(response, verdict="FINDINGS" if finding else "NO_FINDINGS",
                           findings=[dict(path="src/a.py", line=1, severity="high", explanation="Defect")] if finding else [])
            blocks = [dict(type="tool_use", name="StructuredOutput", id="first", input=earlier),
                      dict(type="tool_use", name="StructuredOutput", id="last", input=response)]
            report = runner.parse_stream(stream(content=blocks, result=response), "correctness", data["subject"])
            self.assertEqual(report["structured_output_attempts"], [earlier, response])
            data["reports"][0] = report
            if finding:
                with self.assertRaisesRegex(ValueError, "earlier output"):
                    runner.proof.validate_reports(data)
            else:
                runner.proof.validate_reports(data)

    def test_activity_after_final_result_is_rejected(self):
        for kind in ("assistant", "user", "tool", "tool_result"):
            event = dict(type=kind, message=dict(model="claude-sonnet-test", content=[]))
            with self.subTest(kind=kind), self.assertRaisesRegex(ValueError, "after final"):
                runner.parse_stream(stream() + "\n" + json.dumps(event), "correctness", {})

    def test_wrapped_last_attempt_correlates_without_rewriting_evidence(self):
        response = dict(verdict="NO_FINDINGS", findings=[], coverage=[])
        wrapped = {"$PARAMETER_VALUE": json.dumps(response)}
        block = dict(type="tool_use", name="StructuredOutput", input=wrapped)
        report = runner.parse_stream(stream(content=[block], result=response), "correctness", {})
        self.assertEqual(report["structured_output_attempts"], [wrapped])
        wrong = dict(response, verdict="FINDINGS")
        with self.assertRaisesRegex(ValueError, "last structured output"):
            runner.parse_stream(stream(content=[block], result=wrong), "correctness", {})

    def test_oversized_valid_input_stops_before_provider_call(self):
        payload = {"diff": "x" * runner.MAX_INPUT}
        data = dict(payload=payload, subject=dict(input_digest=runner.proof.digest(payload)))
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "input.json"
            source.write_text(json.dumps(data))
            with patch.object(runner, "anchored", return_value=("example/bank", "d" * 40)), \
                    patch.object(runner, "public_subject") as api, \
                    patch.object(runner.subprocess, "run") as invoke:
                with self.assertRaisesRegex(ValueError, "input exceeds budget"):
                    runner.review(source, Path(directory) / "out.json", "correctness", "/never/call")
                api.assert_not_called()
                invoke.assert_not_called()

    def test_input_tampering_stops_before_invocation(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "input.json"
            source.write_text(json.dumps(dict(payload={}, subject=dict(input_digest="wrong"))))
            with patch.object(runner, "anchored", return_value=("example/bank", "d" * 40)), \
                    patch.object(runner.subprocess, "run") as invoke:
                with self.assertRaisesRegex(ValueError, "digest"):
                    runner.review(source, Path(directory) / "out.json", "correctness", "/never/call")
                invoke.assert_not_called()

    def test_other_repository_rejected_before_api_or_provider_call(self):
        data = fixtures.bundle()
        data["payload"] = {"diff": "public fixture", "files": []}
        data["subject"]["input_digest"] = runner.proof.digest(data["payload"])
        data["subject"]["repo"] = "other/public-bank"
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory) / "input.json", Path(directory) / "out.json"
            source.write_text(json.dumps(data))
            with patch.object(runner, "anchored", return_value=("example/bank", "d" * 40)), \
                    patch.object(runner, "public_subject") as api, \
                    patch.object(runner.subprocess, "run") as invoke:
                with self.assertRaisesRegex(ValueError, "repository differs"):
                    runner.review(source, output, "correctness", "/never/call")
                api.assert_not_called()
                invoke.assert_not_called()
                self.assertFalse(output.exists())

    def test_unstructured_text_cannot_substitute_for_validated_output(self):
        events = [json.loads(line) for line in stream().splitlines()]
        result = events[-1]
        result["result"] = json.dumps(result.pop("structured_output"))
        for missing in (None, "{}", [], True):
            with self.subTest(value=missing):
                result["structured_output"] = missing
                with self.assertRaisesRegex(ValueError, "no structured review"):
                    runner.parse_stream("\n".join(json.dumps(e) for e in events), "correctness", {})

    def test_structured_findings_preserved_without_reclassification(self):
        response = dict(verdict="FINDINGS", findings=[dict(path="a", line=1, severity="high",
                        explanation="Concrete regression")], coverage=[])
        self.assertEqual(runner.parse_stream(stream(result=response), "security", {})["response"], response)

    @patch.object(runner.proof, "validate_policy_snapshot")
    def test_cli_isolated_and_has_no_repository_token(self, policy_check):
        data = fixtures.bundle()
        data["payload"] = {"diff": "public fixture", "files": []}
        data["subject"]["input_digest"] = runner.proof.digest(data["payload"])
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory) / "input.json", Path(directory) / "out.json"
            source.write_text(json.dumps(data))
            observed = []

            def invoke(args, **kwargs):
                observed.append((args, kwargs))
                return subprocess.CompletedProcess(args, 0, stdout=stream(), stderr="")

            with patch.object(runner, "anchored", return_value=("example/bank", "d" * 40)), \
                    patch.object(runner, "public_subject", return_value=pull()), \
                    patch.dict(os.environ, GITHUB_SHA="d" * 40, GH_TOKEN="fixture", GITHUB_TOKEN="fixture",
                               ACTIONS_RUNTIME_TOKEN="artifact-secret", FUTURE_JOB_SECRET="unknown-secret",
                               CLAUDE_CODE_OAUTH_TOKEN="provider-fixture"), \
                    patch.object(runner.subprocess, "run", side_effect=invoke), \
                    patch.object(runner, "invocation_failure", side_effect=AssertionError("success must not classify failure")):
                runner.review(source, output, "correctness", "/trusted/claude")
            policy_check.assert_called_once_with("example/bank", "main", "d" * 40, "a" * 40)
            args, kwargs = observed[0]
            self.assertEqual(args[args.index("--tools") + 1], "")
            self.assertIn("--strict-mcp-config", args)
            self.assertIn("--safe-mode", args)
            self.assertEqual(args[args.index("--max-budget-usd") + 1], "1.00")
            self.assertEqual(kwargs["env"]["CLAUDE_CODE_MAX_OUTPUT_TOKENS"], "16384")
            self.assertEqual(json.loads(args[args.index("--json-schema") + 1]), runner.RESPONSE_SCHEMA)
            self.assertEqual(args[args.index("--setting-sources") + 1], "")
            self.assertNotIn("GH_TOKEN", kwargs["env"])
            self.assertNotIn("GITHUB_TOKEN", kwargs["env"])
            self.assertNotIn("ACTIONS_RUNTIME_TOKEN", kwargs["env"])
            self.assertNotIn("FUTURE_JOB_SECRET", kwargs["env"])
            self.assertNotIn("CLAUDE_CODE_OAUTH_TOKEN", kwargs["env"])
            self.assertEqual(kwargs["env"]["ANTHROPIC_API_KEY"], "api-fixture")
            self.assertEqual(kwargs["env"]["HOME"], kwargs["cwd"])
            self.assertEqual(kwargs["env"]["CLAUDE_CONFIG_DIR"], str(Path(kwargs["cwd"]) / ".claude"))
            self.assertEqual(kwargs["env"]["XDG_CONFIG_HOME"], str(Path(kwargs["cwd"]) / ".config"))
            self.assertNotEqual(kwargs["cwd"], str(source.parent))
            self.assertEqual(json.loads(kwargs["input"]), data)
            self.assertTrue(output.exists())


class SourceAndAcceptanceTest(unittest.TestCase):
    def test_prepare_preserves_literal_metacharacter_filenames_with_real_git(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            def git(*args):
                if args[0] == "fetch":
                    return b""  # Both fixture commits already exist locally.
                return subprocess.run(["git", *args], cwd=root, check=True,
                                      capture_output=True,
                                      env=dict(os.environ, GIT_INDEX_FILE=str(root / ".git" / "index"))).stdout

            git("init", "-q")
            names = ("a[b].kt", "x*.kt", "q?.kt")
            for name in (*names, "ab.kt", "xyz.kt", "qa.kt"):
                (root / name).write_text("before " + name)
                git("--literal-pathspecs", "add", "--", name)
            tree = git("write-tree").decode().strip()
            identity = dict(GIT_AUTHOR_NAME="Fixture", GIT_AUTHOR_EMAIL="fixture@example.invalid",
                            GIT_COMMITTER_NAME="Fixture", GIT_COMMITTER_EMAIL="fixture@example.invalid")
            with patch.dict(os.environ, identity):
                base = git("commit-tree", tree, "-m", "fixture base").decode().strip()
                for name in names:
                    (root / name).write_text("after " + name)
                    git("--literal-pathspecs", "add", "--", name)
                tree = git("write-tree").decode().strip()
                head = git("commit-tree", tree, "-p", base, "-m", "fixture head").decode().strip()
            subject = pull()
            subject["base"]["sha"], subject["head"]["sha"] = base, head
            with patch.object(runner, "anchored", return_value=("example/bank", "d" * 40)), \
                    patch.object(runner, "public_subject", return_value=subject), \
                    patch.object(runner.proof, "validate_policy_snapshot"), \
                    patch.object(runner, "git", side_effect=git), \
                    patch.object(runner.guard, "protected_reasons", return_value=[]), \
                    patch.dict(os.environ, GITHUB_OUTPUT=str(root / "outputs"),
                               GITHUB_STEP_SUMMARY=str(root / "summary")):
                runner.prepare(12, root / "input.json")
            data = json.loads((root / "input.json").read_text())
            self.assertEqual(set(data["subject"]["files"]), set(names))
            self.assertEqual({entry["path"]: (entry["before"], entry["after"])
                              for entry in data["payload"]["files"]},
                             {name: ("before " + name, "after " + name) for name in names})

    @patch.object(runner.proof, "validate_policy_snapshot")
    def test_prepare_distinguishes_binary_header_from_source_literal(self, policy_check):
        text = b'check = b"GIT binary patch"\n'
        for binary in (False, True):
            diff = (b"diff --git a/check.py b/check.py\nGIT binary patch\nliteral 1\n" if binary else
                    b"diff --git a/check.py b/check.py\n@@ -0,0 +1 @@\n+" + text)

            def git(*args, diff=diff):
                if args[0] == "--literal-pathspecs":
                    args = args[1:]
                if args[0] == "fetch":
                    return b""
                if args[0] == "merge-base":
                    return b"c" * 40
                if args[0] == "diff":
                    return b"M\0check.py\0" if "--name-status" in args else diff
                if args[0] == "ls-tree":
                    return b"100644 blob " + b"a" * 40 + b"\tcheck.py\0"
                if args[0] == "cat-file":
                    return str(len(text)).encode()
                if args[0] == "show":
                    return text
                self.fail(f"unexpected git call: {args}")

            with self.subTest(binary=binary), tempfile.TemporaryDirectory() as directory, \
                    patch.object(runner, "anchored", return_value=("example/bank", "d" * 40)), \
                    patch.object(runner, "public_subject", return_value=pull()), \
                    patch.object(runner, "git", side_effect=git), \
                    patch.object(runner.guard, "protected_reasons", return_value=[]), \
                    patch.dict(os.environ, GITHUB_OUTPUT=str(Path(directory) / "outputs"),
                               GITHUB_STEP_SUMMARY=str(Path(directory) / "summary")):
                output = Path(directory) / "input.json"
                if binary:
                    with self.assertRaisesRegex(ValueError, "binary change"):
                        runner.prepare(12, output)
                    self.assertFalse(output.exists())
                else:
                    runner.prepare(12, output)
                    self.assertEqual(json.loads(output.read_text())["payload"]["files"][0]["after"], text.decode())

    def test_workflow_enforces_readonly_models_and_acceptance_order(self):
        import yaml
        workflow = Path(__file__).resolve().parents[1] / "workflows" / "agent-review.yml"
        config = yaml.safe_load(workflow.read_text())
        self.assertEqual(config["permissions"], {})
        self.assertEqual(config["concurrency"], dict(group="agent-provider-budget", **{"cancel-in-progress": False}))
        self.assertNotIn("CLAUDE_CODE_OAUTH_TOKEN", workflow.read_text())
        self.assertIn("secrets.AGENT_REVIEW_ANTHROPIC_API_KEY", workflow.read_text())
        jobs = config["jobs"]
        self.assertNotIn("review", jobs)
        for name in ("solo-prepare", "solo-review", "solo-proof", "solo-seal"):
            self.assertTrue(jobs[name]["permissions"])
            self.assertTrue(all(value == "read" for value in jobs[name]["permissions"].values()))
            self.assertNotIn("environment", jobs[name], "environment variables must not override the anchor")
        self.assertEqual(set(jobs["solo-review"]["strategy"]["matrix"]["slot"]), {"correctness", "security"})
        self.assertIn("solo-proof", jobs["solo-owner-acceptance"]["needs"])
        self.assertIn("solo-owner-acceptance", jobs["solo-seal"]["needs"])
        self.assertTrue(jobs["solo-proof"]["if"].startswith("always()"))
        self.assertEqual(jobs["solo-owner-acceptance"]["permissions"], {})

    def test_private_or_unknown_repository_never_sent(self):
        for value in (True, None):
            with patch.object(runner.proof, "gh", return_value={"private": value}), self.assertRaisesRegex(ValueError, "public"):
                runner.public_subject("example/bank", 12)
        data = pull()
        data["head"]["repo"]["private"] = True
        with patch.object(runner.proof, "gh", side_effect=[{"private": False}, data]), self.assertRaisesRegex(ValueError, "public"):
            runner.public_subject("example/bank", 12)

    def test_deleted_fork_fails_with_classified_public_source_error(self):
        data = pull()
        data["head"]["repo"] = None
        with patch.object(runner.proof, "gh", side_effect=[{"private": False}, data]), \
                self.assertRaisesRegex(ValueError, "PR source repository is not public"):
            runner.public_subject("example/bank", 12)

    def test_missing_anchor_wrong_source_and_rerun(self):
        env = dict(GITHUB_REPOSITORY="example/bank", GITHUB_SHA="d" * 40,
                   GITHUB_EVENT_NAME="workflow_dispatch", GITHUB_RUN_ATTEMPT="1", SOLO_REVIEW_POLICY_SHA="d" * 40)
        with patch.dict(os.environ, env), patch.object(runner, "git", return_value=("d" * 40).encode()):
            self.assertEqual(runner.anchored(), ("example/bank", "d" * 40))
            for key, value in (("SOLO_REVIEW_POLICY_SHA", ""), ("GITHUB_SHA", "f" * 40),
                               ("GITHUB_RUN_ATTEMPT", "2"), ("GITHUB_EVENT_NAME", "pull_request")):
                with self.subTest(key=key), patch.dict(os.environ, {key: value}), self.assertRaises(ValueError):
                    runner.anchored()

    @patch.object(runner.proof, "validate_policy_snapshot")
    def test_gitlink_to_existing_commit_rejected(self, policy_check):
        commit = subprocess.check_output(["git", "rev-parse", "HEAD"]).strip()

        def git(*args):
            if args[0] == "--literal-pathspecs":
                args = args[1:]
            if args[0] == "fetch":
                return b""
            if args[0] == "merge-base":
                return b"c" * 40
            if args[0] == "diff":
                return b"M\0module\0" if "--name-status" in args else b"diff --git a/module b/module\n"
            if args[0] == "ls-tree":
                return b"160000 commit " + commit + b"\tmodule\0"
            self.fail(f"must reject gitlink before reading it as file content: {args}")

        with patch.object(runner, "anchored", return_value=("example/bank", "d" * 40)), \
                patch.object(runner, "public_subject", return_value=pull()), patch.object(runner, "git", side_effect=git):
            with self.assertRaisesRegex(ValueError, "non-blob"):
                runner.prepare(12, "/must-not-write")

    @patch.object(runner.proof, "validate_policy_snapshot")
    def test_seal_requires_real_approval_not_boolean(self, policy_check):
        bundle = fixtures.bundle()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runner.write(root / "input.json", {"subject": bundle["subject"]})
            reports = [root / "correctness.json", root / "security.json"]
            for path, data in zip(reports, bundle["reports"], strict=True):
                runner.write(path, data)
            with patch.object(runner, "anchored", return_value=("example/bank", "d" * 40)), \
                    patch.object(runner, "public_subject", return_value=pull()), \
                    patch.object(runner, "protected_environment", return_value=({"id": 7}, 42)), \
                    patch.dict(os.environ, GITHUB_RUN_ID="99"), patch.object(runner.proof, "gh", return_value=[]):
                with self.assertRaisesRegex(ValueError, "GitHub has no owner approval"):
                    runner.seal(root / "input.json", reports, root / "out.json", True)
                self.assertFalse((root / "out.json").exists())


if __name__ == "__main__":
    program = unittest.main(exit=False)
    if program.result.testsRun == 0:
        sys.exit("No tests collected; review proof is unverified")
    sys.exit(0 if program.result.wasSuccessful() else 1)
