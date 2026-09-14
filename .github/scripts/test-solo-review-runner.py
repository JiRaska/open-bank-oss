#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""No provider requests: falsify public-source, execution and approval boundaries."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
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
             result="", structured_output=result or dict(verdict="NO_FINDINGS", findings=[], coverage=[])),
    ])


class DriverTest(unittest.TestCase):
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
                                  ("rate_limit_error", "RATE_OR_USAGE_LIMIT"),
                                  ("prompt is too long", "CONTEXT_LIMIT"),
                                  ("ENOTFOUND", "PROVIDER_UNAVAILABLE"),
                                  ("unexpected response", "UNKNOWN")):
            proc = subprocess.CompletedProcess([], 1, stdout=json.dumps(dict(type="result", is_error=True, errors=["secret-fixture " + message])),
                                               stderr="private-source-fixture")
            with self.subTest(category=category):
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
                       [output, output], [dict(type="server_tool_use", name="web_search", input={})]):
            with self.subTest(blocks=blocks), self.assertRaises(ValueError):
                runner.parse_stream(stream(content=blocks, result=response), "security", {})

    def test_ambiguous_output_diagnostics_disclose_only_counts(self):
        response = dict(verdict="FINDINGS", findings=["untrusted-private-text"], coverage=[])
        output = dict(type="tool_use", name="StructuredOutput", id="untrusted-id", input=response)
        with self.assertRaises(ValueError) as caught:
            runner.parse_stream(stream(content=[output, output], result=response), "correctness", {})
        self.assertEqual(str(caught.exception),
                         "ambiguous structured output calls: count=2, distinct_ids=1, matching_final=2")

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
                    patch.object(runner.subprocess, "run", side_effect=invoke):
                runner.review(source, output, "correctness", "/trusted/claude")
            args, kwargs = observed[0]
            self.assertEqual(args[args.index("--tools") + 1], "")
            self.assertIn("--strict-mcp-config", args)
            self.assertIn("--safe-mode", args)
            self.assertEqual(json.loads(args[args.index("--json-schema") + 1]), runner.RESPONSE_SCHEMA)
            self.assertEqual(args[args.index("--setting-sources") + 1], "")
            self.assertNotIn("GH_TOKEN", kwargs["env"])
            self.assertNotIn("GITHUB_TOKEN", kwargs["env"])
            self.assertNotIn("ACTIONS_RUNTIME_TOKEN", kwargs["env"])
            self.assertNotIn("FUTURE_JOB_SECRET", kwargs["env"])
            self.assertEqual(kwargs["env"]["CLAUDE_CODE_OAUTH_TOKEN"], "provider-fixture")
            self.assertNotEqual(kwargs["cwd"], str(source.parent))
            self.assertEqual(json.loads(kwargs["input"]), data)
            self.assertTrue(output.exists())


class SourceAndAcceptanceTest(unittest.TestCase):
    @patch.object(runner.proof, "validate_policy_snapshot")
    def test_prepare_distinguishes_binary_header_from_source_literal(self, policy_check):
        text = b'check = b"GIT binary patch"\n'
        for binary in (False, True):
            diff = (b"diff --git a/check.py b/check.py\nGIT binary patch\nliteral 1\n" if binary else
                    b"diff --git a/check.py b/check.py\n@@ -0,0 +1 @@\n+" + text)

            def git(*args, diff=diff):
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
        jobs = yaml.safe_load(workflow.read_text())["jobs"]
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
    unittest.main()
