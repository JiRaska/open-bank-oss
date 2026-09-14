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
        dict(type="assistant", message=dict(model="claude-sonnet-test", content=content or [])),
        dict(type="result", subtype="success", is_error=error, session_id="fresh-session",
             result=json.dumps(result or dict(verdict="NO_FINDINGS", findings=[], coverage=[]))),
    ])


class DriverTest(unittest.TestCase):
    def test_finished_stream_observes_model_and_session(self):
        report = runner.parse_stream(stream(), "correctness", fixtures.bundle()["subject"])
        self.assertEqual(report["model"], "claude-sonnet-test")
        self.assertEqual(report["session_id"], "fresh-session")

    def test_error_tool_use_malformed_and_missing_events(self):
        for raw in (stream(error=True), stream(content=[dict(type="tool_use", name="Bash")]),
                    "not JSON", "", json.dumps(dict(type="result", subtype="success", is_error=False))):
            with self.subTest(raw=raw), self.assertRaises((ValueError, KeyError)):
                runner.parse_stream(raw, "correctness", {})

    def test_input_tampering_stops_before_invocation(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "input.json"
            source.write_text(json.dumps(dict(payload={}, subject=dict(input_digest="wrong"))))
            with patch.object(runner, "anchored"), patch.object(runner.subprocess, "run") as invoke:
                with self.assertRaisesRegex(ValueError, "digest"):
                    runner.review(source, Path(directory) / "out.json", "correctness", "/never/call")
                invoke.assert_not_called()

    def test_cli_isolated_and_has_no_repository_token(self):
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

            with patch.object(runner, "anchored"), patch.object(runner, "public_subject", return_value=pull()), \
                    patch.dict(os.environ, GITHUB_SHA="d" * 40, GH_TOKEN="fixture", GITHUB_TOKEN="fixture"), \
                    patch.object(runner.subprocess, "run", side_effect=invoke):
                runner.review(source, output, "correctness", "/trusted/claude")
            args, kwargs = observed[0]
            self.assertEqual(args[args.index("--tools") + 1], "")
            self.assertIn("--strict-mcp-config", args)
            self.assertIn("--safe-mode", args)
            self.assertEqual(args[args.index("--setting-sources") + 1], "")
            self.assertNotIn("GH_TOKEN", kwargs["env"])
            self.assertNotIn("GITHUB_TOKEN", kwargs["env"])
            self.assertNotEqual(kwargs["cwd"], str(source.parent))
            self.assertEqual(json.loads(kwargs["input"]), data)
            self.assertTrue(output.exists())


class SourceAndAcceptanceTest(unittest.TestCase):
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

    def test_missing_anchor_wrong_source_and_rerun(self):
        env = dict(GITHUB_REPOSITORY="example/bank", GITHUB_SHA="d" * 40,
                   GITHUB_EVENT_NAME="workflow_dispatch", GITHUB_RUN_ATTEMPT="1", SOLO_REVIEW_POLICY_SHA="d" * 40)
        with patch.dict(os.environ, env), patch.object(runner, "git", return_value=("d" * 40).encode()):
            self.assertEqual(runner.anchored(), ("example/bank", "d" * 40))
            for key, value in (("SOLO_REVIEW_POLICY_SHA", ""), ("GITHUB_SHA", "f" * 40),
                               ("GITHUB_RUN_ATTEMPT", "2"), ("GITHUB_EVENT_NAME", "pull_request")):
                with self.subTest(key=key), patch.dict(os.environ, {key: value}), self.assertRaises(ValueError):
                    runner.anchored()

    def test_gitlink_to_existing_commit_rejected(self):
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

    def test_seal_requires_real_approval_not_boolean(self):
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
