#!/usr/bin/env python3
"""Focused tests for the admin-ui-evidence saved-plan boundary."""

import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("check-admin-ui-evidence-plan.py")
SPEC = importlib.util.spec_from_file_location("admin_ui_evidence_plan_guard", SCRIPT)
GUARD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GUARD)


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


if __name__ == "__main__":
    unittest.main()
