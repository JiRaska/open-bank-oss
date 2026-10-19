#!/usr/bin/env python3
"""Focused tests for the admin-ui-evidence saved-plan boundary."""

import importlib.util
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


class EvidencePolicyScopeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.role = (REPO_ROOT / "openbank-infra/aws/envs/sandbox-platform/admin-ui-evidence.tf").read_text(encoding="utf-8")
        cls.bucket = (REPO_ROOT / "openbank-infra/aws/envs/sandbox-platform/test-evidence-store.tf").read_text(encoding="utf-8")

    def test_role_policy_is_limited_to_the_four_named_datasets_and_exact_prefixes(self):
        locals_block = hcl_block(self.role, "locals")
        datasets = re.search(r"admin_ui_evidence_datasets\s*=\s*\[(.*?)\]", locals_block, re.DOTALL)
        self.assertIsNotNone(datasets)
        self.assertEqual(re.findall(r'"([a-z-]+)"', datasets.group(1)), [
            "gate-health", "readiness", "test-intelligence", "security-kpis",
        ])

        policy = hcl_block(self.role, 'data "aws_iam_policy_document" "admin_ui_evidence"')
        self.assertRegex(policy, r'actions\s*=\s*\["s3:GetObject",\s*"s3:PutObject"\]')
        self.assertRegex(policy, r'resources\s*=\s*concat\(local\.admin_ui_evidence_selectors,\s*\[for dataset in local\.admin_ui_evidence_datasets : "\$\{aws_s3_bucket\.test_evidence\.arn\}/admin-ui/\$\{dataset\}/snapshots/\*"\]\)')
        self.assertRegex(policy, r'actions\s*=\s*\["s3:ListBucket"\]')
        self.assertRegex(policy, r'values\s*=\s*\[for dataset in local\.admin_ui_evidence_datasets : "admin-ui/\$\{dataset\}/current\.json"\]')
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
