#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Negative authorization proofs for exact-head owner decisions (no network or credentials)."""
import copy
import importlib.util
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location("merge_train_owner_test", Path(__file__).with_name("merge-train.py"))
train = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = train
spec.loader.exec_module(train)


class OwnerApprovalTest(unittest.TestCase):
    def setUp(self):
        self.sha = "a" * 40
        self.env = {"GITHUB_REPOSITORY": "owner/repo", "GITHUB_EVENT_NAME": "workflow_dispatch",
                    "GITHUB_REF": "refs/heads/main", "GITHUB_ACTOR": "owner", "GITHUB_TRIGGERING_ACTOR": "owner",
                    "GITHUB_ACTOR_ID": "42", "GITHUB_RUN_ATTEMPT": "1"}
        self.metadata = {"full_name": "owner/repo", "owner": {"type": "User", "login": "owner", "id": 42}}
        self.pr = {"number": 7, "state": "open", "draft": False,
                   "head": {"sha": self.sha, "ref": "codex/example", "repo": {"full_name": "owner/repo"}},
                   "base": {"ref": "main", "repo": {"full_name": "owner/repo"}}}
        self.view = {"number": 7, "head_ref": "codex/example", "state": "open", "draft": False,
                     "auto_merge": {"enabled_by": "owner"}, "same_repo": True, "files": ["service/A.kt"]}
        self.required = ["all-green", train.STATUS_CONTEXT]
        self.checks = {"all-green": "success", "Trivy": "success"}
        self.approval = {train.OWNER_APPROVAL_CONTEXT: ("success", "owner-approved PR #7")}

    def error(self, **kw):
        args = dict(repo="owner/repo", number=7, head=self.sha, decision="approve", env=self.env,
                    metadata=self.metadata, pr=self.pr)
        args.update(kw)
        return train.owner_approval_error(**args)

    def qualifies(self, **kw):
        args = dict(pr=self.view, required=self.required, checks=self.checks, app=self.approval,
                    untrainable=lambda _: False, extra={"Trivy": ".github/workflows/security.yml"})
        args.update(kw)
        return train.qualifies(**args)[0]

    def test_owner_exact_head_is_valid(self):
        self.assertIsNone(self.error())
        self.assertTrue(self.qualifies())

    def test_event_ref_both_actors_id_and_rerun_fail_closed(self):
        for key, bad in [("GITHUB_REPOSITORY", "other/repo"), ("GITHUB_EVENT_NAME", "pull_request_target"),
                         ("GITHUB_REF", "refs/heads/codex/attack"), ("GITHUB_ACTOR", "collaborator"),
                         ("GITHUB_TRIGGERING_ACTOR", "collaborator"), ("GITHUB_ACTOR_ID", "99"),
                         ("GITHUB_RUN_ATTEMPT", "2")]:
            with self.subTest(key=key):
                self.assertIsNotNone(self.error(env={**self.env, key: bad}))
                env = dict(self.env)
                del env[key]
                self.assertIsNotNone(self.error(env=env))

    def test_live_owner_metadata_required(self):
        for metadata in [{}, {**self.metadata, "full_name": "other/repo"},
                         {**self.metadata, "owner": {"type": "Organization", "login": "owner", "id": 42}}]:
            with self.subTest(metadata=metadata):
                self.assertIsNotNone(self.error(metadata=metadata))

    def test_invalid_inputs(self):
        for kw in [dict(number=0), dict(number=True), dict(head="a" * 39), dict(head="A" * 40),
                   dict(head="main"), dict(decision="merge")]:
            with self.subTest(kw=kw):
                self.assertIsNotNone(self.error(**kw))

    def test_live_pr_bound_to_number_head_base_repository_and_state(self):
        bads = [dict(number=8), dict(state="closed"), dict(draft=True),
                dict(head={**self.pr["head"], "sha": "b" * 40}),
                dict(head={**self.pr["head"], "repo": {"full_name": "fork/repo"}}),
                dict(head={**self.pr["head"], "ref": "fix/not-codex"}),
                dict(base={**self.pr["base"], "ref": "release"}),
                dict(base={**self.pr["base"], "repo": {"full_name": "fork/repo"}})]
        for bad in bads:
            with self.subTest(bad=bad):
                self.assertIsNotNone(self.error(pr={**self.pr, **bad}))

    def test_revoke_remains_possible_after_draft_or_close(self):
        for change in [dict(draft=True), dict(state="closed")]:
            self.assertIsNone(self.error(pr={**self.pr, **change}, decision="revoke"))

    def test_missing_wrong_pr_failed_or_noncanonical_approval_refused(self):
        for app in [None, {}, {train.OWNER_APPROVAL_CONTEXT: ("success", "owner-approved PR #8")},
                    {train.OWNER_APPROVAL_CONTEXT: ("failure", "owner-revoked PR #7")},
                    {train.OWNER_APPROVAL_CONTEXT: ("pending", "owner-approved PR #7")},
                    {train.OWNER_APPROVAL_CONTEXT: ("success", "owner-approved PR #7 extra")},
                    {train.STATUS_CONTEXT: ("success", "owner-approved PR #7")}]:
            with self.subTest(app=app):
                self.assertFalse(self.qualifies(app=app))

    def test_approval_never_overrides_existing_guards(self):
        for changes in [dict(state="closed"), dict(draft=True), dict(auto_merge=None), dict(same_repo=False),
                        dict(files=["service/unsafe\n.kt"])]:
            with self.subTest(changes=changes):
                self.assertFalse(self.qualifies(pr={**self.view, **changes}))
        for checks in [{"all-green": "failure", "Trivy": "success"},
                       {"all-green": None, "Trivy": "success"}, {"Trivy": "success"},
                       {"all-green": "success", "Trivy": "failure"},
                       {"all-green": "success", "Trivy": None},
                       {**self.checks, "gates / agent-pr-guard": "failure"}]:
            with self.subTest(checks=checks):
                self.assertFalse(self.qualifies(checks=checks))
        self.assertFalse(self.qualifies(required=[]))
        self.assertFalse(self.qualifies(app={**self.approval, train.STATUS_CONTEXT: ("failure", "culprit")}))

    def test_governance_still_needs_strict_lane(self):
        view = {**self.view, "files": [".github/scripts/change.py"]}
        protected = lambda _: True
        self.assertFalse(self.qualifies(pr=view, untrainable=protected))
        state, _ = train.strict_lane_state(view, self.required, self.checks, self.approval, protected, [], extra={"Trivy": ".github/workflows/security.yml"})
        self.assertEqual("green", state)
        state, _ = train.strict_lane_state(view, self.required, self.checks, {}, protected, [], extra={"Trivy": ".github/workflows/security.yml"})
        self.assertIsNone(state)

    def test_app_reader_authenticates_creator_latest_decision_and_sha(self):
        gh = train.GitHub("owner/repo", "main", "")
        records = [{"id": 1, "creator": {"login": train.BOT_LOGIN}, "context": train.OWNER_APPROVAL_CONTEXT,
                    "state": "success", "description": "owner-approved PR #7"},
                   {"id": 2, "creator": {"login": "owner"}, "context": train.OWNER_APPROVAL_CONTEXT,
                    "state": "success", "description": "owner-approved PR #7"},
                   {"id": 3, "creator": {"login": train.BOT_LOGIN}, "context": train.OWNER_APPROVAL_CONTEXT,
                    "state": "failure", "description": "owner-revoked PR #7"}]
        def statuses(path, key=None):
            return copy.deepcopy(records) if f"/{self.sha}/" in path else []
        with patch.object(train, "paginate", side_effect=statuses):
            self.assertFalse(self.qualifies(app=gh.app_statuses(self.sha)))
            self.assertFalse(self.qualifies(app=gh.app_statuses("b" * 40)))
            records.pop()
            self.assertTrue(self.qualifies(app=gh.app_statuses(self.sha)))
            records.pop(0)
            self.assertFalse(self.qualifies(app=gh.app_statuses(self.sha)))

    def test_writer_only_records_decision_context_and_refuses_stale_head(self):
        gh = Mock(repo="owner/repo")
        gh.pr.return_value = self.pr
        with patch.dict(os.environ, self.env, clear=True), patch.object(train, "get", return_value=self.metadata):
            train.mode_owner_approval(gh, 7, self.sha, "approve")
            gh.post_status.assert_called_once_with(self.sha, "success", "owner-approved PR #7", context=train.OWNER_APPROVAL_CONTEXT)
            gh.merge.assert_not_called()
            gh.post_status.reset_mock()
            train.mode_owner_approval(gh, 7, self.sha, "revoke")
            gh.post_status.assert_called_once_with(self.sha, "failure", "owner-revoked PR #7", context=train.OWNER_APPROVAL_CONTEXT)
            gh.post_status.reset_mock()
            with self.assertRaises(SystemExit):
                train.mode_owner_approval(gh, 7, "b" * 40, "approve")
            gh.post_status.assert_not_called()

    def test_merge_rechecks_approval_before_api_write(self):
        gh = train.GitHub("owner/repo", "main", "")
        pr = {**self.pr, "auto_merge": {"x": 1}}
        with patch.object(gh, "pr", return_value=pr), patch.object(gh, "app_statuses", return_value={}), patch.object(train, "api") as api:
            self.assertIsNone(gh.merge(7, self.sha))
            api.assert_not_called()
        # The live verdict re-read (cache safety, #13988) has its own tests in merge-train.py --self-test.
        with patch.object(gh, "pr", return_value=pr), patch.object(gh, "app_statuses", return_value=self.approval), patch.object(gh, "unverified", return_value=None), patch.object(train, "api", return_value=(200, {"merged": True, "sha": "c" * 40})) as api:
            self.assertEqual("c" * 40, gh.merge(7, self.sha))
            api.assert_called_once_with("PUT", "/repos/owner/repo/pulls/7/merge", {"merge_method": "squash", "sha": self.sha})

    def test_declaration_guards_credentials_and_main_only_code(self):
        code = train.OWNER_APPROVAL_WORKFLOW.read_text()
        self.assertEqual([], train.owner_approval_declaration_errors(code))
        for needle in ["github.actor == github.repository_owner", "github.triggering_actor == github.repository_owner",
                       "github.run_attempt == 1", "github.ref == 'refs/heads/main'", "ref: main",
                       "python3 -I -S", "persist-credentials: false",
                       "group: merge-train-owner-approval-${{ github.event_name }}-${{ github.ref }}",
                       "cancel-in-progress: false"]:
            with self.subTest(needle=needle):
                self.assertTrue(train.owner_approval_declaration_errors(code.replace(needle, "removed")))
        for unsafe in ["\n      - run: gh pr merge 1", "\n      - uses: actions/download-artifact@unsafe",
                       "\n      - run: python3 .github/unsafe.py"]:
            self.assertTrue(train.owner_approval_declaration_errors(code + unsafe))


class SyntheticClockTest(unittest.TestCase):
    def test_runner_age_does_not_expire_fixtures_but_explicit_ci_age_does(self):
        epoch = 1767225600
        real_env = train.base_env
        # Model a suite taking longer than even the hard ceiling, without sleeping. Both git's
        # commit time and the settle clock must remain on the fixture's modeled instant.
        with patch.object(train.time, "time", return_value=epoch + train.RESERVATION_CEILING_S + 60) as wall:
            with train._synthetic_clock(epoch), tempfile.TemporaryDirectory() as root:
                git = train.Git(root)
                git("init", "-q", "-b", "main")
                git("commit", "-q", "--allow-empty", "-m", "synthetic reservation")
                head = git("rev-parse", "HEAD").stdout.strip()
                self.assertEqual(str(epoch), git("show", "-s", "--format=%ct", head).stdout.strip())
                self.assertIsNone(train.reservation_expired(git, head, train.time.time()))
                wall.assert_not_called()
                stalled = epoch + train.RESERVATION_STALL_S + 1
                self.assertIn("stalled", train.reservation_expired(git, head, stalled))
                self.assertIsNone(train.reservation_expired(git, head, stalled, lambda _: stalled - 60))
                self.assertIn("ceiling", train.reservation_expired(
                    git, head, epoch + train.RESERVATION_CEILING_S + 1,
                    lambda _: epoch + train.RESERVATION_CEILING_S))
                self.assertIn("future", train.reservation_expired(git, head, epoch - 1))
            self.assertIs(train.time.time, wall)
            self.assertIs(train.base_env, real_env)

    def test_fixture_clock_and_git_environment_restore_after_exception(self):
        real_time, real_env = train.time.time, train.base_env
        with self.assertRaisesRegex(RuntimeError, "fixture failure"), train._synthetic_clock():
            raise RuntimeError("fixture failure")
        self.assertIs(train.time.time, real_time)
        self.assertIs(train.base_env, real_env)


if __name__ == "__main__":
    unittest.main()
