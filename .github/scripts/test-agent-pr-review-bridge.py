#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""The guard must consume verified proof, never a caller's approval flag."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import types
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).parent))
spec = importlib.util.spec_from_file_location("guard", Path(__file__).with_name("check-agent-pr-guard.py"))
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class BridgeTest(unittest.TestCase):
    def setUp(self):
        env = patch.dict(guard.os.environ, {}, clear=True)
        env.start()
        self.addCleanup(env.stop)

    def test_renaming_protected_source_cannot_escape_classification(self):
        pull = dict(user=dict(login="author", type="User"), head=dict(ref="agent/change", sha="a" * 40),
                    base=dict(ref="main", sha="b" * 40), changed_files=1)
        files = [[dict(filename="docs/retired-ci.yml", previous_filename=".github/workflows/ci.yml", status="renamed")]]
        with patch.object(guard, "fetch_event_pr", return_value=None), \
                patch.object(guard, "_gh", side_effect=[pull, files, pull]):
            author, bot, branch, paths, added = guard.fetch_pr(12)
        self.assertIn(".github/workflows/ci.yml", paths)
        self.assertEqual(guard.verdict(author, bot, branch, paths, guard.FIXTURE, added)[0], 1)

    def test_incomplete_duplicate_and_missing_rename_source_are_unresolved(self):
        pull = dict(user=dict(login="author", type="User"), head=dict(ref="agent/change"), changed_files=2)
        examples = [[dict(filename="README.md")],
                    [dict(filename="README.md"), dict(filename="README.md")],
                    [dict(filename="README.md"), dict(filename="docs/a.yml", status="renamed")]]
        for entries in examples:
            with self.subTest(entries=entries), patch.object(guard, "fetch_event_pr", return_value=None), \
                    patch.object(guard, "_gh", side_effect=[pull, [entries]]), self.assertRaises(guard.Undetermined):
                guard.fetch_pr(12)

    def test_classification_rejects_push_during_enumeration(self):
        pull = dict(user=dict(login="author", type="User"), head=dict(ref="agent/change", sha="a" * 40),
                    changed_files=1)
        final = dict(pull, head=dict(ref="agent/change", sha="b" * 40))
        with patch.object(guard, "fetch_event_pr", return_value=None), \
                patch.object(guard, "_gh", side_effect=[pull, [[dict(filename="README.md")]], final]), \
                self.assertRaises(guard.Undetermined):
            guard.fetch_pr(12)

    def test_trusted_policy_refuses_drift_before_any_clean_verdict(self):
        reader = Mock()
        reader.validate_policy_snapshot.side_effect = ValueError("new protected service")
        with patch.dict(guard.os.environ, {"SOLO_REVIEW_POLICY_SHA": "d" * 40}), \
                patch.object(guard.importlib.util, "spec_from_file_location", return_value=types.SimpleNamespace(loader=Mock())), \
                patch.object(guard.importlib.util, "module_from_spec", return_value=reader), \
                patch.object(guard, "_gh", return_value={"base": {"ref": "main"}, "head": {"sha": "a" * 40}}), \
                self.assertRaises(guard.Undetermined):
            guard.validate_controller_policy(12)

    def test_locator_reads_current_head_and_all_status_pages(self):
        head = "a" * 40
        responses = [{"head": {"sha": head}}, [[], [{"context": "solo-review/evidence", "state": "success",
                      "target_url": f"https://github.com/{guard.REPO}/actions/runs/99"}]]]
        with patch.object(guard, "_gh", side_effect=responses) as api:
            self.assertEqual(guard.review_run_for_pr(12), 99)
            self.assertIn(head, api.call_args.args[0][-1])

    def test_newer_bad_locator_cannot_fall_back_to_old_success(self):
        good = {"context": "solo-review/evidence", "state": "success",
                "target_url": f"https://github.com/{guard.REPO}/actions/runs/99"}
        for change in ({"state": "failure"}, {"target_url": "https://example.com/actions/runs/99"},
                       {"target_url": f"https://github.com/{guard.REPO}/actions/runs/99?other=1"}):
            with self.subTest(change=change), patch.object(guard, "_gh", side_effect=[
                    {"head": {"sha": "a" * 40}}, [[dict(good, **change), good]]]), \
                    self.assertRaises(guard.Undetermined):
                guard.review_run_for_pr(12)

    def test_ci_without_repository_anchor_cannot_accept_proof(self):
        with patch.dict(guard.os.environ, {"GITHUB_ACTIONS": "true"}), self.assertRaises(guard.Undetermined):
            self.invoke()

    def invoke(self, error=None):
        verifier = Mock()
        verifier.verify.side_effect = error
        loader = Mock()
        with patch.object(guard.importlib.util, "spec_from_file_location", return_value=types.SimpleNamespace(loader=loader)), \
                patch.object(guard.importlib.util, "module_from_spec", return_value=verifier):
            result = guard.reviewed_verdict(1, "protected", 12, 99)
        verifier.verify.assert_called_once_with(guard.REPO, 12, 99, protected=True)
        return result

    def test_verified_owner_and_model_evidence_allows_protected_change(self):
        self.assertEqual(self.invoke()[0], 0)

    def test_absent_evidence_retains_refusal(self):
        self.assertEqual(guard.reviewed_verdict(1, "protected", 12, None), (1, "protected"))

    def test_review_cannot_rescue_undetermined_classification(self):
        with patch.object(guard.importlib.util, "spec_from_file_location") as load:
            self.assertEqual(guard.reviewed_verdict(2, "unknown author", 12, 99), (2, "unknown author"))
            load.assert_not_called()

    def test_out_of_scope_does_not_require_model_calls(self):
        with patch.object(guard.importlib.util, "spec_from_file_location") as load:
            self.assertEqual(guard.reviewed_verdict(0, "automation", 12, 99), (0, "automation"))
            load.assert_not_called()

    def test_invalid_run_ids_cannot_be_approval_flags(self):
        for run in (0, -1, True, "99"):
            with self.subTest(run=run), self.assertRaises(guard.Undetermined):
                guard.reviewed_verdict(1, "protected", 12, run)

    def test_bridge_uses_real_proof_reader_for_complete_and_missing_acceptance(self):
        fixture_spec = importlib.util.spec_from_file_location(
            "proof_fixtures", Path(__file__).with_name("test-solo-review-proof.py"))
        fixtures = importlib.util.module_from_spec(fixture_spec)
        fixture_spec.loader.exec_module(fixtures)
        for accepted in (True, False):
            def change(path, count, data, accepted=accepted):
                if not accepted and path.endswith("/approvals"):
                    return []
                return data
            fake_api = fixtures.LiveReadSequenceTest().fixture(change)
            with self.subTest(accepted=accepted), patch.object(guard, "REPO", "example/bank"), \
                    patch.object(fixtures.proof, "gh", fake_api), \
                    patch.object(guard.importlib.util, "spec_from_file_location",
                                 return_value=types.SimpleNamespace(loader=Mock())), \
                    patch.object(guard.importlib.util, "module_from_spec", return_value=fixtures.proof):
                if accepted:
                    self.assertEqual(guard.reviewed_verdict(1, "protected", 12, 99)[0], 0)
                else:
                    with self.assertRaises(guard.Undetermined):
                        guard.reviewed_verdict(1, "protected", 12, 99)

    def test_invalid_or_unavailable_evidence_never_passes(self):
        for error in (ValueError("stale head"), ValueError("owner missing"),
                      ValueError("findings"), KeyError("subject"), TypeError("malformed"),
                      OSError("unavailable"), subprocess.CalledProcessError(1, "gh")):
            with self.subTest(error=error), self.assertRaises(guard.Undetermined):
                self.invoke(error)


if __name__ == "__main__":
    unittest.main()
