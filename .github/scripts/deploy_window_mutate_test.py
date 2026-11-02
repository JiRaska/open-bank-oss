#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Offline execution of the real deployment arm helper and its current train guard."""
import argparse
from contextlib import redirect_stdout
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "deploy_window_mutate", Path(__file__).with_name("deploy-window-mutate.py"))
mutator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(mutator)

REPO = "example/open-bank"
HEAD = "a" * 40
BASE = "b" * 40
APP_ID = 12345
BRANCH = "chore/gitops-auto-deploy-example"
# Current mode: strict is OFF and the required merge-train context is App-pinned.
RULES = [{"type": "required_status_checks", "parameters": {
    "strict_required_status_checks_policy": False,
    "required_status_checks": [{"context": "all-green", "integration_id": None},
                               {"context": "merge-train", "integration_id": APP_ID}]}}]


def pull():
    return {"number": 7, "node_id": "PR_seven", "state": "open", "draft": False,
            "labels": [], "head": {"sha": HEAD, "ref": BRANCH,
                                     "repo": {"full_name": REPO}},
            "base": {"sha": BASE, "ref": "main", "repo": {"full_name": REPO}}}


def inventory():
    return [{"number": 7, "head": BRANCH, "head_sha": HEAD, "created_at": "2026-01-01",
             "armed": False, "state": "open", "repair_priority": False}]


class DeployWindowMutateTest(unittest.TestCase):
    def execute(self, *, rules=None, final_rules=None, first=None, latest=None,
                prs=None, commits=None, main=None, inventory_main=BASE, source_main=BASE,
                source_error=False, inventory_error=False, policy_error=False,
                event=None, branch=BRANCH, app_id=str(APP_ID), extra_env=None):
        first = copy.deepcopy(first if first is not None else pull())
        first["head"]["ref"] = branch
        latest = copy.deepcopy(latest if latest is not None else first)
        policies = [copy.deepcopy(RULES if rules is None else rules),
                    copy.deepcopy(RULES if final_rules is None and rules is None else
                                  rules if final_rules is None else final_rules)]
        pr_reads = [first, latest]
        mains = list(main or [BASE, BASE])
        rows = copy.deepcopy(inventory() if prs is None else prs)
        if prs is None:
            rows[0]["head"] = branch
        calls, source_calls = [], []

        def fake_gh(*args):
            calls.append(args)
            if args[:2] == ("api", "graphql"):
                self.assertIn("enablePullRequestAutoMerge", args[3])
                self.assertNotIn("mergePullRequest", args[3])
                return {"data": {"enablePullRequestAutoMerge": {"pullRequest": {"number": 7}}}}
            self.assertEqual(args[0], "api")
            self.assertEqual(len(args), 2, "every non-GraphQL API call must be read-only")
            path = args[1]
            if path.endswith("/git/ref/heads/main"):
                return {"object": {"sha": mains.pop(0)}}
            if path.endswith("/rules/branches/main"):
                if policy_error:
                    raise subprocess.CalledProcessError(1, "gh api rules")
                return policies.pop(0)
            if path.endswith("/pulls/7"):
                return pr_reads.pop(0)
            self.fail(f"unexpected endpoint, including any merge/status write: {args}")

        def fake_run(*args, input_text=None):
            source_calls.append((args, input_text))
            if args[:2] == ("git", "fetch") or args[:2] == ("git", "reset"):
                return ""
            if args[:2] == ("git", "rev-parse"):
                return source_main
            if args[:3] == ("gh", "pr", "diff"):
                return "exact deployment diff"
            if args[0] == "bash":
                if inventory_error:
                    raise subprocess.CalledProcessError(1, "inventory")
                directory = Path(args[2])
                (directory / "main.sha").write_text(inventory_main + "\n")
                (directory / "prs.json").write_text(json.dumps(rows))
                (directory / "commits.json").write_text(json.dumps(commits or []))
                return ""
            if args[1:3] == (str(mutator.SCRIPTS / "deploy-window.py"), "verify"):
                self.assertEqual(input_text, "exact deployment diff")
                if source_error:
                    raise subprocess.CalledProcessError(1, "source verifier")
                return ""
            self.fail(f"unexpected process: {args}")

        with tempfile.TemporaryDirectory() as directory:
            event_file = Path(directory) / "event.json"
            event_file.write_text(json.dumps(event or {}))
            environment = {"GITHUB_REPOSITORY": REPO, "GITOPS_APP_ID": app_id,
                           "GITHUB_EVENT_PATH": str(event_file), "DEPLOY_WINDOW_SECONDS": "1800"}
            environment.update(extra_env or {})
            error = None
            with patch.dict(os.environ, environment, clear=True), patch.object(mutator, "gh", fake_gh), \
                    patch.object(mutator, "run", fake_run), patch.object(mutator.time, "time", return_value=5000):
                try:
                    mutator.main(argparse.Namespace(number=7, selected_main=BASE, selected_head=HEAD))
                except (ValueError, subprocess.CalledProcessError) as exception:
                    error = exception
        return error, calls, source_calls

    def assert_refused(self, **options):
        error, calls, _ = self.execute(**options)
        self.assertIsNotNone(error)
        self.assertFalse(any(args[:2] == ("api", "graphql") for args in calls))
        return str(error)

    def test_actual_workflow_arm_block_uses_current_policy_and_only_queues(self):
        workflow = Path(__file__).parents[1] / "workflows/deploy-window-flush.yml"
        # The arming step is the final workflow step; execute its actual Bash block.
        block = textwrap.dedent(workflow.read_text().rsplit("        run: |\n", 1)[1])
        fake_gh = r"""#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
args = sys.argv[1:]
pull = json.loads(os.environ['FIXTURE_PR'])
rules = json.loads(os.environ['FIXTURE_RULES'])
if args[:2] == ['api', 'graphql']:
    assert 'enablePullRequestAutoMerge' in args[3] and 'mergePullRequest' not in args[3]
    with Path(os.environ['FIXTURE_WRITES']).open('a') as out:
        out.write(json.dumps(args) + '\n')
    print(json.dumps({'data': {'enablePullRequestAutoMerge': {'pullRequest': {'number': 7}}}}))
elif args[:3] == ['pr', 'diff', '7']:
    print('')
elif args[0] == 'api':
    assert '-X' not in args and '--method' not in args
    path = next(arg for arg in args[1:] if arg.startswith('repos/'))
    if '/rules/branches/main' in path: print(json.dumps(rules))
    elif '/git/ref/heads/main' in path:
        print(os.environ['SELECTED_MAIN'] if '--jq' in args else json.dumps({'object': {'sha': os.environ['SELECTED_MAIN']}}))
    elif '/commits?' in path: print('[]')
    elif '/pulls?state=open' in path:
        assert '--paginate' in args and '--slurp' in args
        rows = [pull]
        if os.environ.get('FIXTURE_REPAIR') == '1':
            repair = dict(pull, number=8, labels=[{'name': 'merge-priority:repair'}])
            repair['head'] = dict(pull['head'], ref='fix/repair')
            rows.append(repair)
        print(json.dumps([rows]))
    elif path.endswith('/pulls/7'): print(json.dumps(pull))
    else: raise AssertionError(args)
else: raise AssertionError(args)
"""
        fake_git = r"""#!/usr/bin/env python3
import os, sys
assert sys.argv[1] in ('fetch', 'rev-parse', 'reset'), sys.argv
if sys.argv[1] == 'rev-parse': print(os.environ['SELECTED_MAIN'])
"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, source in (("gh", fake_gh), ("git", fake_git)):
                (root / name).write_text(source)
                (root / name).chmod(0o755)
            event = root / "event.json"
            event.write_text('{}')
            pr = pull()
            # Admin UI follows main's producer/contract checks; this fixture proves wiring,
            # not GitOps image ancestry. The service verifier has separate failure cases.
            pr["head"]["ref"] = "chore/admin-ui-deploy-example"
            pr.update(created_at="2026-01-01T00:00:00Z", auto_merge=None)
            env = {"PATH": str(root) + os.pathsep + os.environ.get("PATH", os.defpath),
                   "GITHUB_REPOSITORY": REPO, "GITOPS_APP_ID": str(APP_ID),
                   "GITHUB_EVENT_PATH": str(event), "ARM": "7", "SELECTED_MAIN": BASE,
                   "SELECTED_HEAD": HEAD, "FIXTURE_PR": json.dumps(pr),
                   "FIXTURE_WRITES": str(root / "writes.jsonl")}
            for valid, repair in ((True, False), (False, False), (True, True), (False, True)):
                rules = copy.deepcopy(RULES)
                if not valid:
                    rules[0]["parameters"]["required_status_checks"][1]["integration_id"] += 1
                env["FIXTURE_RULES"] = json.dumps(rules)
                env["FIXTURE_REPAIR"] = "1" if repair else "0"
                (root / "writes.jsonl").write_text("")
                result = subprocess.run(["bash", "-c", block], env=env,
                                        cwd=Path(__file__).parents[2], text=True,
                                        capture_output=True, check=False)
                writes = (root / "writes.jsonl").read_text().splitlines()
                if valid:
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertEqual(len(writes), 0 if repair else 1)
                    if repair:
                        self.assertIn("::notice::", result.stdout)
                else:
                    self.assertNotEqual(result.returncode, 0)
                    self.assertEqual(writes, [])

    def test_current_policy_arms_without_merging(self):
        error, calls, source_calls = self.execute()
        self.assertIsNone(error)
        self.assertEqual(sum(args[:2] == ("api", "graphql") for args in calls), 1)
        self.assertTrue(any(args[1:3] == (str(mutator.SCRIPTS / "deploy-window.py"), "verify")
                            for args, _ in source_calls))

    def test_clean_and_pending_statuses_only_arm(self):
        # Neither a green PR nor a pending one gives the flusher authority to merge.
        for status in ("clean", "blocked", "unstable", "unknown"):
            with self.subTest(status=status):
                pr = pull()
                pr["mergeable_state"] = status
                self.assertIsNone(self.execute(first=pr)[0])

    def test_strict_or_wrong_missing_app_pin_refused(self):
        for strict, pin, app in ((True, APP_ID, str(APP_ID)), (False, APP_ID + 1, str(APP_ID)),
                                 (False, None, str(APP_ID)), (False, APP_ID, "")):
            with self.subTest(strict=strict, pin=pin, app=app):
                rules = copy.deepcopy(RULES)
                rules[0]["parameters"]["strict_required_status_checks_policy"] = strict
                rules[0]["parameters"]["required_status_checks"][1]["integration_id"] = pin
                self.assert_refused(rules=rules, app_id=app)

    def test_policy_unreadable_or_changed_refused(self):
        self.assert_refused(policy_error=True)
        self.assert_refused(rules=[])
        changed = copy.deepcopy(RULES)
        changed[0]["parameters"]["required_status_checks"][1]["integration_id"] += 1
        self.assert_refused(final_rules=changed)

    def test_changed_main_at_every_observed_boundary_refused(self):
        self.assert_refused(main=["c" * 40])
        self.assert_refused(source_main="c" * 40)
        self.assert_refused(inventory_main="c" * 40)
        self.assert_refused(main=[BASE, "c" * 40])

    def test_changed_head_base_identity_or_repository_refused(self):
        for field, value in (("head", "c" * 40), ("base", "c" * 40), ("node_id", "PR_other")):
            with self.subTest(field=field):
                pr = pull()
                if field == "node_id":
                    pr[field] = value
                else:
                    pr[field]["sha"] = value
                self.assert_refused(latest=pr)
        pr = pull()
        pr["head"]["repo"]["full_name"] = "fork/open-bank"
        self.assert_refused(latest=pr)

    def test_live_draft_blocked_closed_or_unknown_hold_refused(self):
        for update in ({"draft": True}, {"draft": None}, {"state": "closed"},
                       {"labels": [{"name": "blocked"}]}, {"labels": None}, {"labels": [{}]}):
            with self.subTest(update=update):
                self.assert_refused(latest={**pull(), **update})

    def assert_deferred(self, **options):
        output = io.StringIO()
        with redirect_stdout(output):
            error, calls, _ = self.execute(**options)
        self.assertIsNone(error)
        self.assertFalse(any(args[:2] == ("api", "graphql") for args in calls))
        self.assertIn("::notice::", output.getvalue())
        self.assertIn("repair priority", output.getvalue())
        self.assertEqual(sum(args[-1].endswith("/pulls/7") for args in calls), 2)
        self.assertEqual(sum(args[-1].endswith("/git/ref/heads/main") for args in calls), 2)

    def test_window_change_remains_failure(self):
        self.assert_refused(commits=[{"message": "chore(gitops): auto-deploy example", "epoch": 4900}])

    def test_new_repair_priority_is_successful_deferral_without_arming(self):
        repair = {"number": 8, "head": "fix/repair", "state": "open", "repair_priority": True}
        self.assert_deferred(prs=inventory() + [repair])
        # The marked PR may itself be the deployment; never remove it to infer a choice.
        self.assert_deferred(prs=[{**inventory()[0], "repair_priority": True}])
        for update in ({"state": "closed"}, {"repair_priority": False}):
            error, calls, _ = self.execute(prs=inventory() + [{**repair, **update}])
            self.assertIsNone(error)
            self.assertEqual(sum(args[:2] == ("api", "graphql") for args in calls), 1)

    def test_repair_deferral_never_masks_other_failures(self):
        repair = {"number": 8, "head": "fix/repair", "state": "open", "repair_priority": True}
        prs = inventory() + [repair]
        for options in ({"policy_error": True}, {"source_error": True}, {"inventory_error": True},
                        {"inventory_main": "c" * 40}, {"main": [BASE, "c" * 40]},
                        {"latest": {**pull(), "draft": True}},
                        {"latest": {**pull(), "labels": [{"name": "blocked"}]}},
                        {"latest": {**pull(), "head": {**pull()["head"], "sha": "c" * 40}}},
                        {"commits": [{"message": "chore(gitops): auto-deploy example", "epoch": 4900}]}):
            with self.subTest(options=options):
                self.assert_refused(prs=prs, **options)
        for changed in ({**inventory()[0], "head_sha": "c" * 40},
                        {**inventory()[0], "armed": True}):
            self.assert_refused(prs=[changed, repair])
        self.assert_refused(prs=[repair])

    def test_manual_producer_never_overrides_repair_priority(self):
        branch = "chore/gitops-auto-deploy-" + HEAD + "-run101"
        event = {"action": "completed", "repository": {"full_name": REPO},
                 "workflow_run": {"name": "Auto deploy", "event": "workflow_dispatch",
                                  "conclusion": "success", "head_sha": HEAD, "id": 101,
                                  "path": ".github/workflows/auto-deploy.yml",
                                  "head_repository": {"full_name": REPO}}}
        commits = [{"message": "chore(gitops): auto-deploy example", "epoch": 4900}]
        self.assertIsNone(self.execute(branch=branch, event=event, commits=commits)[0])
        rows = inventory()
        rows[0]["head"] = branch
        repair = {"number": 8, "head": "fix/repair", "state": "open", "repair_priority": True}
        self.assert_deferred(branch=branch, prs=rows + [repair], event=event, commits=commits)

    def test_other_armed_or_newer_selected_candidate_refused(self):
        other = {**inventory()[0], "number": 8, "head": "chore/gitops-auto-deploy-newer", "armed": True}
        self.assert_refused(prs=inventory() + [other])
        other.update(armed=False, created_at="2026-01-02")
        self.assert_refused(prs=inventory() + [other])
        changed = inventory()
        changed[0]["head_sha"] = "c" * 40
        self.assert_refused(prs=changed)

    def test_owner_held_kind_excluded_arms_the_other_kind(self):
        # #14237: the pick step refused the older gitops kind for an owner hold and fell through
        # to admin-ui. The recheck must honour that exclusion, or it refuses the fallthrough.
        held = {"number": 8, "head": "chore/gitops-auto-deploy-held", "head_sha": "c" * 40,
                "created_at": "2025-12-31", "armed": False, "state": "open",
                "repair_priority": False}
        branch = "chore/admin-ui-deploy-example"
        rows = inventory()
        rows[0]["head"] = branch
        self.assert_refused(branch=branch, prs=rows + [held])
        error, calls, _ = self.execute(branch=branch, prs=rows + [held],
                                       extra_env={"EXCLUDE_KINDS": "chore/gitops-auto-deploy-"})
        self.assertIsNone(error)
        self.assertEqual(sum(args[:2] == ("api", "graphql") for args in calls), 1)
        self.assert_refused(branch=branch, prs=rows + [held], extra_env={"EXCLUDE_KINDS": "bogus/"})

    def test_source_or_inventory_failure_never_arms(self):
        self.assert_refused(source_error=True)
        self.assert_refused(inventory_error=True)

    def test_admin_ui_keeps_main_behavior_without_old_blanket_hold(self):
        error, _, source_calls = self.execute(branch="chore/admin-ui-deploy-example")
        self.assertIsNone(error)
        self.assertFalse(any(args[1:3] == (str(mutator.SCRIPTS / "deploy-window.py"), "verify")
                             for args, _ in source_calls))


if __name__ == "__main__":
    unittest.main()
