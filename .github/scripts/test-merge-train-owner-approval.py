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
        with patch.object(gh, "pr", return_value=pr), patch.object(gh, "app_statuses", return_value={}), patch.object(gh, "unverified", return_value=None), patch.object(train, "api") as api:
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
                       "group: merge-train-owner-approval-${{ github.event_name }}-${{ inputs.pr || github.ref }}",
                       "cancel-in-progress: false"]:
            with self.subTest(needle=needle):
                self.assertTrue(train.owner_approval_declaration_errors(code.replace(needle, "removed")))
        # Negative: the old shared group (one pending slot for ALL PRs) must fail the check.
        shared = code.replace("${{ inputs.pr || github.ref }}", "${{ github.ref }}")
        self.assertNotEqual(code, shared)
        self.assertTrue(train.owner_approval_declaration_errors(shared))
        for unsafe in ["\n      - run: gh pr merge 1", "\n      - uses: actions/download-artifact@unsafe",
                       "\n      - run: python3 .github/unsafe.py"]:
            self.assertTrue(train.owner_approval_declaration_errors(code + unsafe))



class SourceIntentBoundaryTest(unittest.TestCase):
    """Real decision functions; only API, Git, and bundle transport are local stand-ins."""

    def setUp(self):
        self.head, self.base, self.tip = "a" * 40, "b" * 40, "c" * 40
        self.pr = {"number": 7, "state": "open", "draft": False, "auto_merge": {"enabled_by": "owner"},
                   "head": {"sha": self.head, "ref": "agent/example", "repo": {"full_name": "owner/repo"}},
                   "base": {"ref": "main", "repo": {"full_name": "owner/repo"}}}
        self.initial_pr = copy.deepcopy(self.pr)
        self.approval = {train.OWNER_APPROVAL_CONTEXT: ("success", "owner-approved PR #7")}

    def publish(self, change=None, error=None):
        gh = Mock(remote="local", branch="main", repo="owner/repo")
        gh.open_prs.return_value = []
        gh.pr.side_effect = error if error else lambda _n: copy.deepcopy(self.pr)
        gh.app_statuses.return_value = self.approval
        member = train.Member(7, "source", "", self.head, self.pr["head"]["ref"], "2026-01-01")
        def plan(*_args):
            # The actual admission predicate sees ON first, as publisher40499011947 did.
            view = train.pr_view("owner/repo", self.pr, ["service/A.kt"])
            self.assertTrue(train.qualifies(view, ["all-green"], {"all-green": "success"}, self.approval)[0])
            return [], [member], {}
        def verified(*_args):
            if change:
                change()
            return train.Verified(self.tip, "d" * 40, [7], [])
        git = Mock(return_value=Mock(returncode=0, stdout=self.tip))
        with tempfile.TemporaryDirectory() as root:
            Path(root, train.BUNDLE_FILE).write_text("fixture transport")
            Path(root, train.MANIFEST_FILE).write_text(f"7 {self.head}\n")
            with patch.object(train, "live_base", return_value=self.base), patch.object(train, "plan_train", side_effect=plan), \
                 patch.object(train, "derived_output_patterns", return_value=["docs/generated/*"]), \
                 patch.object(train, "verify_bundle_train", side_effect=verified), patch.object(train, "Pusher") as pusher, \
                 patch.object(train, "record_deferral", return_value=[]):
                if error:
                    with self.assertRaises(type(error)):
                        train.mode_publish(gh, git, "owner/repo", root, "1", "1", 8)
                    result = None
                else:
                    result = train.mode_publish(gh, git, "owner/repo", root, "1", "1", 8)
                return result, gh, pusher

    def test_publish_rechecks_source_after_verification_for_every_prefix(self):
        for ref in ["agent/example", "fix/example", "dependabot/npm/example", "codex/example"]:
            for change in [{"auto_merge": None}, {"draft": True}, {"state": "closed"},
                           {"head": {**self.pr["head"], "sha": "e" * 40}}]:
                with self.subTest(ref=ref, change=change):
                    self.pr = copy.deepcopy(self.initial_pr)
                    original = copy.deepcopy(self.pr)
                    self.pr["head"]["ref"] = ref
                    result, gh, pusher = self.publish(lambda change=change: self.pr.update(change))
                    self.assertEqual("rejected", result)
                    pusher.assert_not_called()
                    gh.create_carrier.assert_not_called()
                    gh.post_status.assert_not_called()
                    self.pr = original

    def test_publish_keeps_on_source_and_draft_off_carrier_protocol(self):
        result, gh, pusher = self.publish()
        self.assertEqual("published", result)
        pusher.return_value.push.assert_called_once_with(self.tip)
        gh.create_carrier.assert_called_once_with("train/1-1")
        gh.pr.assert_called_once_with(7)  # only the source; never impose source arming on a carrier
        real = train.GitHub("owner/repo", "main", "")
        with patch.object(train, "api", side_effect=[(201, {"number": 99}), (200, {})]) as api:
            self.assertEqual(99, real.create_carrier("train/1-1"))
        payload = api.call_args_list[0].args[2]
        self.assertIs(payload["draft"], True)
        self.assertNotIn("auto_merge", payload)

    def test_publish_unresolved_source_read_aborts_without_write(self):
        _result, gh, pusher = self.publish(error=train.ApiAbort("quota", "fixture"))
        pusher.assert_not_called()
        gh.create_carrier.assert_not_called()
        gh.post_status.assert_not_called()

    def test_final_source_merge_rechecks_after_verdict_for_every_prefix(self):
        real = train.GitHub("owner/repo", "main", "")
        for ref in ["agent/example", "fix/example", "dependabot/npm/example", "codex/example"]:
            for change in [{"auto_merge": None}, {"draft": True}, {"state": "closed"},
                           {"head": {**self.pr["head"], "sha": "e" * 40}}]:
                with self.subTest(ref=ref, change=change):
                    self.pr = copy.deepcopy(self.initial_pr)
                    original = copy.deepcopy(self.pr)
                    self.pr["head"]["ref"] = ref
                    def verdict(_sha, guard, change=change):
                        self.assertTrue(guard)
                        self.pr.update(change)  # changes during last CI/ruleset read
                    with patch.object(real, "pr", side_effect=lambda _n: copy.deepcopy(self.pr)), \
                         patch.object(real, "app_statuses", return_value=self.approval), \
                         patch.object(real, "unverified", side_effect=verdict), \
                         patch.object(train, "api", return_value=(200, {"merged": True, "sha": self.tip})) as api:
                        self.assertIsNone(real.merge(7, self.head))
                        api.assert_not_called()
                    self.pr = original

    def test_final_merge_malformed_unresolved_or_unapproved_fails_closed(self):
        real = train.GitHub("owner/repo", "main", "")
        for data in [None, {}, {**self.pr, "number": 8}, {**self.pr, "draft": None},
                     {**self.pr, "auto_merge": "enabled"}, {**self.pr, "head": {}},
                     {**self.pr, "head": {**self.pr["head"], "ref": "codex/example"}}]:
            with self.subTest(data=data), patch.object(real, "pr", return_value=data), \
                 patch.object(real, "app_statuses", return_value={}), \
                 patch.object(real, "unverified", return_value=None), patch.object(train, "api") as api:
                self.assertIsNone(real.merge(7, self.head))
                api.assert_not_called()
        with patch.object(real, "pr", side_effect=train.ApiAbort("quota", "fixture")), \
             patch.object(real, "unverified", return_value=None), patch.object(train, "api") as api:
            with self.assertRaises(train.ApiAbort):
                real.merge(7, self.head)
            api.assert_not_called()

    def test_intent_boundary_bypasses_planner_approval_cache_only_at_decision(self):
        real = train.GitHub("owner/repo", "main", "")
        self.pr["head"]["ref"] = "codex/example"
        status = {"id": 1, "creator": {"login": train.BOT_LOGIN}, "context": train.OWNER_APPROVAL_CONTEXT,
                  "state": "success", "description": "owner-approved PR #7"}
        calls = []
        def http(req, _timeout):
            calls.append(req.full_url)
            payload = self.pr if req.full_url.endswith("/pulls/7") else [status]
            return 200, train.json.dumps(payload).encode(), {}
        with patch.dict(os.environ, {"GH_TOKEN": "fixture"}), patch.object(train, "_http_open", side_effect=http), \
             train.planning_reads():
            self.assertEqual(self.approval, real.app_statuses(self.head))
            status.update(state="failure", description="owner-revoked PR #7")
            self.assertFalse(train.live_source_intent(real, 7, self.head))
            self.assertEqual(self.approval, real.app_statuses(self.head))  # planner memo itself is unchanged
        self.assertEqual(3, len(calls))  # initial statuses, fresh PR, fresh statuses; no write

    def test_strict_lane_disarm_after_planning_never_posts_success_or_merges(self):
        gh = Mock(remote="local", branch="main", repo="owner/repo")
        gh.main_sha.return_value = self.base
        gh.pr.return_value = {**self.pr, "auto_merge": None}
        gh.app_statuses.return_value = self.approval
        git = Mock(return_value=Mock(returncode=0))
        view = {"number": 7, "head_sha": self.head}
        summary = {"merged": []}
        train.strict_lane(gh, git, self.base, [("green", view)], False, False, summary)
        gh.post_status.assert_not_called()
        gh.merge.assert_not_called()
        self.assertEqual([], summary["merged"])

    def test_settle_rechecks_source_but_accepts_draft_off_carrier(self):
        for armed in [False, True]:
            with self.subTest(armed=armed):
                gh = Mock(remote="local", branch="main", repo="owner/repo")
                gh.merge_guard.return_value = None
                gh.required.return_value = ["all-green", train.STATUS_CONTEXT]
                carrier = {"number": 99, "state": "open", "draft": True, "auto_merge": None,
                           "user": {"login": train.BOT_LOGIN},
                           "head": {"sha": self.tip, "ref": "train/1-1", "repo": {"full_name": "owner/repo"}}}
                gh.open_prs.return_value = [carrier]
                gh.pr.return_value = {**self.pr, "auto_merge": {"x": 1} if armed else None}
                gh.main_sha.return_value = self.base
                gh.app_statuses.return_value = {train.RECORD_CONTEXT: ("success", "tree=" + "d" * 40)}
                gh.merge.return_value = "e" * 40
                git = Mock(return_value=Mock(returncode=0, stdout=self.tip))
                git.tree.return_value = "d" * 40
                with patch.object(train, "settle_view", return_value=(self.base, "", [], [], [], {}, {}, {})), \
                     patch.object(train, "train_extra_gates", return_value={}), \
                     patch.object(train, "pinned_checks", return_value={"all-green": "success"}), \
                     patch.object(train, "parse_own_chain", return_value=(self.base, [(7, self.head)])), \
                     patch.object(train, "train_turn", return_value=None), patch.object(train, "Pusher"):
                    summary = train.mode_settle(gh, git, "owner/repo", False)
                self.assertEqual([7] if armed else [], summary["merged"])
                if armed:
                    gh.merge.assert_called_once_with(7, self.head)
                    gh.post_status.assert_called_once_with(
                        self.head, "success", f"verified on train over main {self.base[:12]}", source_number=7)
                else:
                    gh.merge.assert_not_called()
                    gh.post_status.assert_not_called()
                gh.close_carrier.assert_called_once_with(99)  # carrier disposal semantics are unchanged


    def test_source_success_rechecks_after_verdict_before_native_auto_merge_signal(self):
        real = train.GitHub("owner/repo", "main", "fixture")
        cases = [("fix/example", {"auto_merge": None}), ("fix/example", {"draft": True}),
                 ("fix/example", {"state": "closed"}),
                 ("fix/example", {"head": {**self.pr["head"], "sha": "e" * 40}}),
                 ("codex/example", "revoke")]
        for ref, change in cases:
            with self.subTest(ref=ref, change=change):
                self.pr = copy.deepcopy(self.initial_pr)
                self.pr["head"]["ref"] = ref
                status = dict(self.approval)
                def verdict(_sha, guard, change=change, status=status):
                    if change == "revoke":
                        status[train.OWNER_APPROVAL_CONTEXT] = ("failure", "owner-revoked PR #7")
                    else:
                        self.pr.update(change)
                    return None
                with patch.object(real, "pr", side_effect=lambda _n: copy.deepcopy(self.pr)), \
                     patch.object(real, "app_statuses", side_effect=lambda _sha, status=status: dict(status)), \
                     patch.object(real, "main_sha", return_value=self.base), \
                     patch.object(real, "unverified", side_effect=verdict), \
                     patch.object(train, "api", return_value=(201, {})) as api:
                    summary = {"merged": [], "strict": []}
                    train.strict_lane(real, Mock(return_value=Mock(returncode=0)), self.base,
                                      [("green", {"number": 7, "head_sha": self.head})], False, False, summary)
                    self.assertEqual([], summary["merged"])
                    api.assert_not_called()  # no required SUCCESS, which native auto-merge could consume

    def test_source_status_requires_bound_live_intent_but_other_contexts_keep_protocol(self):
        real = train.GitHub("owner/repo", "main", "fixture")
        with patch.object(real, "pr", return_value=self.pr) as pr, \
             patch.object(real, "unverified", return_value=None) as verdict, \
             patch.object(train, "api", return_value=(201, {})) as api:
            real.post_status(self.head, "success", "verified", source_number=7)
            pr.assert_called_once_with(7)
            verdict.assert_called_once_with(self.head, guard=False)
            self.assertEqual("success", api.call_args.args[2]["state"])
            for state, context in [("success", train.RECORD_CONTEXT), ("success", train.OWNER_APPROVAL_CONTEXT),
                                   ("success", train.DEFER_CONTEXT), ("pending", train.STATUS_CONTEXT)]:
                pr.reset_mock(); verdict.reset_mock(); api.reset_mock()
                real.post_status(self.tip, state, "protocol", context=context)
                pr.assert_not_called(); verdict.assert_not_called(); api.assert_called_once()
            api.reset_mock()
            with self.assertRaises(SystemExit):
                real.post_status(self.head, "success", "missing source identity")
            api.assert_not_called()
        with patch.object(real, "unverified", return_value=None), \
             patch.object(real, "pr", side_effect=train.ApiAbort("quota", "fixture")), \
             patch.object(train, "api") as api:
            with self.assertRaises(train.ApiAbort):
                real.post_status(self.head, "success", "unresolved", source_number=7)
            api.assert_not_called()

    def test_fast_lane_source_success_obeys_final_status_boundary(self):
        real = train.GitHub("owner/repo", "main", "fixture")
        member = train.Member(7, "source", "", self.head, "agent/example", "2026-01-01")
        for armed in [False, True]:
            with self.subTest(armed=armed):
                self.pr = copy.deepcopy(self.initial_pr)
                def verdict(_sha, guard, armed=armed):
                    if not armed:
                        self.pr["auto_merge"] = None
                    return None
                with patch.object(real, "merge_guard", return_value=None), \
                     patch.object(real, "required", return_value=["all-green", train.STATUS_CONTEXT]), \
                     patch.object(real, "open_prs", return_value=[]), \
                     patch.object(real, "pr", side_effect=lambda _n: copy.deepcopy(self.pr)), \
                     patch.object(real, "unverified", side_effect=verdict), \
                     patch.object(train, "settle_view", return_value=(self.base, "", [], [], [member], {}, {}, {})), \
                     patch.object(train, "partition", return_value=([member], [])), \
                     patch.object(train, "train_turn", return_value=None), \
                     patch.object(train, "api", return_value=(201, {})) as api:
                    train.mode_settle(real, Mock(), "owner/repo", False)
                    if armed:
                        api.assert_called_once()
                        self.assertEqual("success", api.call_args.args[2]["state"])
                    else:
                        api.assert_not_called()

    def test_live_source_preserves_planner_main_and_same_repository_eligibility(self):
        real = train.GitHub("owner/repo", "main", "")
        valid = {**self.pr, "base": {"ref": "main", "repo": {"full_name": "owner/repo"}}}
        for change in [{"base": {"ref": "release", "repo": {"full_name": "owner/repo"}}},
                       {"base": {"ref": "main", "repo": {"full_name": "fork/repo"}}},
                       {"base": None}, {"base": {"ref": "main", "repo": None}},
                       {"head": {**self.pr["head"], "repo": {"full_name": "fork/repo"}}},
                       {"head": {**self.pr["head"], "repo": None}}]:
            with self.subTest(change=change), patch.object(real, "pr", return_value={**valid, **change}):
                self.assertFalse(train.live_source_intent(real, 7, self.head))
        with patch.object(real, "pr", return_value=valid):
            self.assertTrue(train.live_source_intent(real, 7, self.head))


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
