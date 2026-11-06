#!/usr/bin/env python3
"""Focused tests for the admin-ui-evidence saved-plan boundary."""

import importlib.util
import json
import re
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("check-admin-ui-evidence-plan.py")
SPEC = importlib.util.spec_from_file_location("admin_ui_evidence_plan_guard", SCRIPT)
GUARD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GUARD)

REPO_ROOT = Path(__file__).resolve().parents[2]


def hcl_block(source, heading):
    match = re.search(rf'^{re.escape(heading)}\s*\{{(.*?)^\}}', source, re.MULTILINE | re.DOTALL)
    if not match:
        raise AssertionError(f"missing HCL block {heading}")
    return match.group(1)


def policy_statement(source, sid):
    match = re.search(rf'^  statement\s*\{{\s*sid\s*=\s*"{re.escape(sid)}"(.*?)^  \}}', source, re.MULTILINE | re.DOTALL)
    if not match:
        raise AssertionError(f"missing IAM policy statement {sid}")
    return match.group(1)


def resource(address, actions, mode="managed"):
    return {"address": address, "mode": mode, "change": {"actions": actions}}


class EvidencePlanGuardTest(unittest.TestCase):
    def test_allows_target_creates_updates_and_noops_plus_data_reads(self):
        plan = {"resource_changes": [
            resource("aws_iam_role.admin_ui_evidence", ["create"]),
            resource("aws_iam_role_policy.admin_ui_evidence", ["update"]),
            resource("aws_s3_bucket_policy.test_evidence", ["no-op"]),
            resource("data.aws_iam_policy_document.evidence", ["read"], mode="data"),
        ]}
        self.assertEqual(len(GUARD.validate_plan(plan)), 4)

    def test_rejects_managed_change_outside_allowlist(self):
        plan = {"resource_changes": [resource("aws_eks_cluster.platform", ["update"])]}
        with self.assertRaisesRegex(ValueError, "outside the admin-ui-evidence allowlist"):
            GUARD.validate_plan(plan)

    def test_rejects_delete_and_replacement_even_for_allowed_resources(self):
        for actions in (["delete"], ["delete", "create"], ["create", "delete"]):
            with self.subTest(actions=actions):
                plan = {"resource_changes": [resource("aws_iam_role.admin_ui_evidence", actions)]}
                with self.assertRaisesRegex(ValueError, "destructive action rejected"):
                    GUARD.validate_plan(plan)

    def test_rejects_malformed_or_missing_plan_structures(self):
        malformed = [None, {}, {"resource_changes": {}}, {"resource_changes": [None]},
                      {"resource_changes": [{"address": "aws_iam_role.admin_ui_evidence"}]},
                      {"resource_changes": [resource("aws_iam_role.admin_ui_evidence", [])]}]
        for plan in malformed:
            with self.subTest(plan=plan), self.assertRaises(ValueError):
                GUARD.validate_plan(plan)


def ui_change(address="aws_iam_role_policy.admin_ui_evidence", action="update"):
    return {"type": "planned_change", "change": {"resource": {"addr": address}, "action": action}}


def ui_events(changes=None, summary=None):
    return [{"type": "version", "ui": "1.2"}, *(changes if changes is not None else [ui_change()]),
            {"type": "change_summary", "changes": summary if summary is not None else
             {"operation": "plan", "add": 0, "change": 1, "remove": 0}}]


def ui_source(events):
    return "\n".join(json.dumps(event) for event in events)


class EvidencePlanUIStreamTest(unittest.TestCase):
    def test_accepts_same_resource_boundary_with_complete_accounting(self):
        events = ui_events([ui_change(), ui_change("aws_s3_bucket_policy.test_evidence")],
                           {"operation": "plan", "add": 0, "change": 2, "remove": 0, "import": 0})
        events.insert(1, {"type": "diagnostic", "diagnostic": {"severity": "warning", "summary": "legacy unrelated state"}})
        events.insert(2, ui_change("data.aws_iam_policy_document.evidence", "read"))
        self.assertEqual(len(GUARD.validate_ui_stream(ui_source(events))), 3)

    def test_accepts_real_plan_data_read_hooks_and_lock_release(self):
        events = ui_events()
        for kind in ["apply_start", "apply_progress", "apply_complete"]:
            events.insert(1, {"type": kind, "hook": {"resource": {"addr": "data.aws_iam_policy_document.evidence"}, "action": "read"}})
        events.append({"type": "state_lock_release"})
        self.assertEqual(len(GUARD.validate_ui_stream(ui_source(events))), 1)
        for address, action in [("aws_iam_role.admin_ui_evidence", "read"),
                                ("data.aws_iam_policy_document.evidence", "update")]:
            bad = ui_events() + [{"type": "apply_complete", "hook": {"resource": {"addr": address}, "action": action}}]
            with self.subTest(address=address, action=action), self.assertRaises(ValueError):
                GUARD.validate_ui_stream(ui_source(bad))

    def test_accepts_complete_no_change_plan(self):
        self.assertEqual(GUARD.validate_ui_stream(ui_source(ui_events([], {
            "operation": "plan", "add": 0, "change": 0, "remove": 0,
        }))), [])

    def test_rejects_outside_resources_and_unsafe_actions(self):
        cases = [ui_change("aws_eks_cluster.platform"), ui_change("data.aws_iam_policy_document.evidence")]
        cases += [ui_change(action=action) for action in ["delete", "replace", "forget", "import", "move", "unknown"]]
        for change in cases:
            with self.subTest(change=change), self.assertRaises(ValueError):
                GUARD.validate_ui_stream(ui_source(ui_events([change])))

    def test_rejects_truncated_and_mismatched_or_apply_summaries(self):
        cases = [ui_events()[:-1], ui_events(summary={}), ui_events(summary={
            "operation": "apply", "add": 0, "change": 1, "remove": 0,
        })]
        for field, value in [("add", 1), ("change", 0), ("change", True), ("remove", 1),
                             ("import", 1), ("forget", 1), ("unknown", 0)]:
            summary = {"operation": "plan", "add": 0, "change": 1, "remove": 0, field: value}
            cases.append(ui_events(summary=summary))
        for events in cases:
            with self.subTest(events=events), self.assertRaises(ValueError):
                GUARD.validate_ui_stream(ui_source(events))

    def test_rejects_duplicates_moves_imports_errors_and_unknown_events(self):
        cases = [ui_events([ui_change(), ui_change()]), ui_events() + [ui_change()],
                 ui_events() + [ui_events()[-1]], ui_events() + [{"type": "version", "ui": "1.2"}],
                 ui_events() + [{"type": "apply_start"}], ui_events() + [{"type": "unknown"}],
                 ui_events() + [{"type": "log", "@level": "error"}],
                 ui_events() + [{"type": "diagnostic", "diagnostic": {"severity": "error"}}]]
        for key in ["previous_resource", "importing"]:
            change = ui_change()
            change["change"][key] = {"id": "unexpected"}
            cases.append(ui_events([change]))
        for events in cases:
            with self.subTest(events=events), self.assertRaises(ValueError):
                GUARD.validate_ui_stream(ui_source(events))

    def test_rejects_malformed_version_json_or_events(self):
        cases = ["", "not json", '{"type":"version","type":"log"}', ui_source([None]),
                 ui_source(ui_events()[1:]), ui_source([{"type": "version", "ui": "2.0"}]),
                 ui_source(ui_events() + [None])]
        for source in cases:
            with self.subTest(source=source), self.assertRaises(ValueError):
                GUARD.validate_ui_stream(source)

    def test_workflow_keeps_saved_plan_lock_and_digest_verification(self):
        workflow = (REPO_ROOT / ".github/workflows/platform-tofu.yml").read_text()
        self.assertIn('tofu plan -input=false -lock-timeout=300s -json -out=tfplan', workflow)
        self.assertIn('sha256sum tfplan > tfplan.sha256', workflow)
        self.assertIn('--ui-stream "$plan_json"', workflow)
        self.assertIn('sha256sum --check tfplan.sha256', workflow)
        self.assertIn('tofu apply -input=false -no-color -lock-timeout=300s tfplan', workflow)
        self.assertNotIn('-lock=false', workflow.split('  apply:', 1)[1])


class EvidencePolicyScopeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.role = (REPO_ROOT / "openbank-infra/aws/envs/sandbox-platform/admin-ui-evidence.tf").read_text(encoding="utf-8")
        cls.bucket = (REPO_ROOT / "openbank-infra/aws/envs/sandbox-platform/test-evidence-store.tf").read_text(encoding="utf-8")

    def test_role_policy_is_limited_to_five_datasets_and_the_versioned_content_prefix(self):
        locals_block = hcl_block(self.role, "locals")
        datasets = re.search(r"admin_ui_evidence_datasets\s*=\s*\[(.*?)\]", locals_block, re.DOTALL)
        self.assertIsNotNone(datasets)
        self.assertEqual(re.findall(r'"([a-z-]+)"', datasets.group(1)), [
            "gate-health", "readiness", "test-intelligence", "security-kpis", "content",
        ])

        self.assertRegex(locals_block, r'admin_ui_evidence_prefixes\s*=\s*concat\(local\.admin_ui_evidence_datasets,\s*\["content/domains-v1"\]\)')
        self.assertRegex(locals_block, r'admin_ui_evidence_selectors\s*=\s*\[for prefix in local\.admin_ui_evidence_prefixes : "\$\{aws_s3_bucket\.test_evidence\.arn\}/admin-ui/\$\{prefix\}/current\.json"\]')

        policy = hcl_block(self.role, 'data "aws_iam_policy_document" "admin_ui_evidence"')
        self.assertRegex(policy, r'actions\s*=\s*\["s3:GetObject",\s*"s3:PutObject"\]')
        self.assertRegex(policy, r'resources\s*=\s*concat\(local\.admin_ui_evidence_selectors,\s*\[for prefix in local\.admin_ui_evidence_prefixes : "\$\{aws_s3_bucket\.test_evidence\.arn\}/admin-ui/\$\{prefix\}/snapshots/\*"\]\)')
        self.assertRegex(policy, r'actions\s*=\s*\["s3:ListBucket"\]')
        self.assertRegex(policy, r'values\s*=\s*\[for prefix in local\.admin_ui_evidence_prefixes : "admin-ui/\$\{prefix\}/current\.json"\]')
        self.assertNotRegex(policy, r'actions\s*=\s*\[[^\]]*"s3:DeleteObject"')

    def test_bucket_policy_keeps_snapshots_immutable_and_selectors_conditional(self):
        overwrite = policy_statement(self.bucket, "DenyOverwrite")
        self.assertIn('not_resources = local.admin_ui_evidence_selectors', overwrite)
        self.assertIn('variable = "s3:if-none-match"', overwrite)
        self.assertIn('values   = ["true"]', overwrite)

        conditional = policy_statement(self.bucket, "DenyUnconditionalAdminUiPointer")
        self.assertIn('resources = local.admin_ui_evidence_selectors', conditional)
        self.assertIn('variable = "s3:if-match"', conditional)
        self.assertIn('variable = "s3:if-none-match"', conditional)
        self.assertEqual(conditional.count('test     = "Null"'), 2)

    def test_writer_trust_remains_pinned_to_main_and_the_publisher_workflow(self):
        trust = hcl_block(self.role, 'data "aws_iam_policy_document" "admin_ui_evidence_assume"')
        self.assertIn('repo:${local.ci_github_repo}:ref:refs/heads/main', trust)
        self.assertIn('${local.ci_github_repo}/.github/workflows/admin-ui-evidence.yml@refs/heads/main', trust)


if __name__ == "__main__":
    unittest.main()
