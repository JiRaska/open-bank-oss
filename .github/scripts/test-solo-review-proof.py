#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Negative proofs: no network, credentials, or model calls."""
import copy
import importlib.util
import io
import json
from pathlib import Path
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location("proof", Path(__file__).with_name("solo-review-proof.py"))
proof = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proof)


def bundle():
    subject = dict(repo="example/bank", pr=12, head="a" * 40, base="b" * 40,
                   merge_base="c" * 40, policy_sha="d" * 40, base_ref="main",
                   files=["src/a.py"], complete=True, protected=True, input_digest="e" * 64)
    reports = [dict(slot=slot, session_id=f"session-{slot}", model=f"model-{slot}",
                    subject_digest=proof.digest(subject), tool_uses=0, driver_success=True,
                    response=dict(verdict="NO_FINDINGS", findings=[], coverage=[dict(
                        path="src/a.py", analysis="Checked missing inputs, boundaries and exception propagation.")]))
               for slot in ("correctness", "security")]
    return dict(schema=1, subject=subject, reports=reports)


class ReportsTest(unittest.TestCase):
    def test_complete_independent_reports(self):
        proof.validate_reports(bundle())

    def test_missing_and_duplicate_review(self):
        for reports in ([], bundle()["reports"][:1], [bundle()["reports"][0]] * 2):
            data = bundle()
            data["reports"] = reports
            with self.subTest(reports=reports), self.assertRaises(ValueError):
                proof.validate_reports(data)

    def test_same_model_or_session(self):
        for key in ("model", "session_id"):
            data = bundle()
            data["reports"][1][key] = data["reports"][0][key]
            with self.subTest(key=key), self.assertRaises(ValueError):
                proof.validate_reports(data)

    def test_stale_report_tool_use_driver_failure(self):
        for key, value in (("subject_digest", "f" * 64), ("tool_uses", 1),
                           ("tool_uses", False), ("driver_success", False), ("model", ""), ("session_id", None)):
            data = bundle()
            data["reports"][0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                proof.validate_reports(data)

    def test_findings_inconsistent_verdict_and_missing_reasoning(self):
        for key, value in (("verdict", "FINDINGS"), ("findings", [{"severity": "high"}]),
                           ("coverage", []), ("coverage", [{"path": "src/a.py", "analysis": "ok"}]),
                           ("coverage", [{"path": "other.py", "analysis": "x" * 50}])):
            data = bundle()
            data["reports"][0]["response"][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                proof.validate_reports(data)

    def test_incomplete_or_malformed_subject(self):
        for key, value in (("complete", False), ("head", "main"), ("input_digest", ""),
                           ("files", []), ("files", ["src/a.py", "src/a.py"])):
            data = bundle()
            data["subject"][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                proof.validate_reports(data)


class ProvenanceTest(unittest.TestCase):
    run_fixture = dict(repository=dict(full_name="example/bank"), event="workflow_dispatch",
               path=proof.WORKFLOW + "@main", workflow_id=17, head_sha="d" * 40,
               run_attempt=1, status="completed", conclusion="success")
    workflow = dict(path=proof.WORKFLOW, id=17)

    def test_anchored_dispatch(self):
        proof.validate_run(self.run_fixture, self.workflow, "d" * 40, "example/bank")

    def test_forged_source_rerun_and_incomplete_run(self):
        for key, value in (("repository", {"full_name": "attacker/bank"}), ("event", "pull_request"),
                           ("path", ".github/workflows/other.yml"), ("workflow_id", 18),
                           ("head_sha", "a" * 40), ("run_attempt", 2),
                           ("status", "in_progress"), ("conclusion", "skipped")):
            run = copy.deepcopy(self.run_fixture)
            run[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                proof.validate_run(run, self.workflow, "d" * 40, "example/bank")

    def test_subject_head_and_base_branch_fence(self):
        subject = bundle()["subject"]
        pull = dict(number=12, state="open", head=dict(sha="a" * 40), base=dict(ref="main"))
        proof.validate_subject(subject, pull, "example/bank", "d" * 40)
        for key, value in (("head", {"sha": "f" * 40}), ("state", "closed"),
                           ("number", 13), ("base", {"ref": "other"})):
            changed = copy.deepcopy(pull)
            changed[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                proof.validate_subject(subject, changed, "example/bank", "d" * 40)

    def test_owner_environment_and_missing_policy(self):
        env = dict(name=proof.ENVIRONMENT, can_admins_bypass=False, protection_rules=[dict(
            type="required_reviewers", reviewers=[dict(type="User", reviewer=dict(id=42))])])
        proof.validate_environment(env, 42)
        for key, value in (("can_admins_bypass", True), ("can_admins_bypass", None),
                           ("protection_rules", []), ("name", "unprotected")):
            changed = copy.deepcopy(env)
            changed[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                proof.validate_environment(changed, 42)
        with self.assertRaises(ValueError):
            proof.validate_environment(env, 43)

    def test_archive_is_read_without_extraction(self):
        for name in ("admission.json", "../admission.json", "report.json"):
            buffer = io.BytesIO()
            with zipfile.ZipFile(buffer, "w") as archive:
                archive.writestr(name, json.dumps(bundle()))
            if name == "admission.json":
                self.assertEqual(proof.read_bundle(buffer.getvalue()), bundle())
            else:
                with self.subTest(name=name), self.assertRaises(ValueError):
                    proof.read_bundle(buffer.getvalue())


class LiveReadSequenceTest(unittest.TestCase):
    """Drive the whole verifier, including mutable state changing between API reads."""

    def fixture(self, change=None):
        data = bundle()
        data.update(run_id=99, run_attempt=1, owner_accepted=True)
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr("admission.json", json.dumps(data))
        env = dict(id=7, name=proof.ENVIRONMENT, can_admins_bypass=False, protection_rules=[dict(
            type="required_reviewers", reviewers=[dict(type="User", reviewer=dict(id=42))])])
        pull = dict(number=12, state="open", head=dict(sha="a" * 40),
                    base=dict(ref="main", sha="b" * 40), changed_files=1)
        replies = {
            "repos/example/bank": {"owner": {"id": 42}},
            "repos/example/bank/actions/variables/SOLO_REVIEW_POLICY_SHA": {"value": "d" * 40},
            "repos/example/bank/actions/runs/99": ProvenanceTest.run_fixture,
            "repos/example/bank/actions/workflows/agent-review.yml": ProvenanceTest.workflow,
            "repos/example/bank/actions/runs/99/artifacts?per_page=100&page=1": {
                "artifacts": [dict(id=88, name="solo-review-admission-1", expired=False)]},
            "repos/example/bank/actions/artifacts/88/zip": buffer.getvalue(),
            "repos/example/bank/pulls/12": pull,
            "repos/example/bank/pulls/12/files?per_page=100&page=1": [{"filename": "src/a.py"}],
            f"repos/example/bank/compare/{'b' * 40}...{'a' * 40}": {"merge_base_commit": {"sha": "c" * 40}},
            "repos/example/bank/actions/runs/99/attempts/1/jobs?per_page=100&page=1": {
                "jobs": [dict(name=name, conclusion="success") for name in
                         ("solo-correctness", "solo-security", "solo-seal", "solo-owner-acceptance")]},
            f"repos/example/bank/environments/{proof.ENVIRONMENT}": env,
            "repos/example/bank/actions/runs/99/approvals": [dict(state="approved", user=dict(id=42),
                                                                environments=[dict(id=7)])],
        }
        counts = {}

        def fake(path, *, binary=False):
            counts[path] = counts.get(path, 0) + 1
            response = copy.deepcopy(replies[path])
            return change(path, counts[path], response) if change else response

        return fake

    def test_complete_read_sequence(self):
        with patch.object(proof, "gh", self.fixture()):
            proof.verify("example/bank", 12, 99, protected=True)

    def test_subject_cannot_drop_owner_approval_by_omitting_cli_flag(self):
        def change(path, count, data):
            return [] if path.endswith("/approvals") else data
        with patch.object(proof, "gh", self.fixture(change)), self.assertRaisesRegex(ValueError, "acceptance missing"):
            proof.verify("example/bank", 12, 99, protected=False)

    def test_rerun_started_between_reads(self):
        def change(path, count, data):
            if path.endswith("/runs/99") and count == 2:
                data["run_attempt"] = 2
            return data
        with patch.object(proof, "gh", self.fixture(change)), self.assertRaisesRegex(ValueError, "rerun"):
            proof.verify("example/bank", 12, 99, protected=True)

    def test_diff_base_changed_same_head_and_paths(self):
        def change(path, count, data):
            if "/compare/" in path:
                data["merge_base_commit"]["sha"] = "f" * 40
            return data
        with patch.object(proof, "gh", self.fixture(change)), self.assertRaisesRegex(ValueError, "diff base"):
            proof.verify("example/bank", 12, 99, protected=True)

    def test_mutable_inputs_change_during_reads(self):
        for field in ("head", "base", "anchor", "environment"):
            def change(path, count, data, field=field):
                if count == 2:
                    if path.endswith("/pulls/12") and field in ("head", "base"):
                        data[field]["sha"] = "f" * 40
                    if path.endswith("/SOLO_REVIEW_POLICY_SHA") and field == "anchor":
                        data["value"] = "f" * 40
                    if "/environments/" in path and field == "environment":
                        data["can_admins_bypass"] = True
                return data
            with self.subTest(field=field), patch.object(proof, "gh", self.fixture(change)), self.assertRaises(ValueError):
                proof.verify("example/bank", 12, 99, protected=True)

    def test_failed_model_job_and_incomplete_file_enumeration(self):
        for field in ("jobs", "files"):
            def change(path, count, data, field=field):
                if field == "jobs" and "/jobs?" in path:
                    data["jobs"][0]["conclusion"] = "skipped"
                if field == "files" and "/files?" in path:
                    return []
                return data
            with self.subTest(field=field), patch.object(proof, "gh", self.fixture(change)), self.assertRaises(ValueError):
                proof.verify("example/bank", 12, 99, protected=True)


if __name__ == "__main__":
    unittest.main()
