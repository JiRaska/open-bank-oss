# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Tests for cloud-finops-collect.py (ADR-0316).

Every rule has a KNOWN-POSITIVE (a resource it must flag) and a KNOWN-NEGATIVE (the fixed
shape it must not flag), so a rule that silently stops matching — a renamed infracost
component, a regex that no longer fires — turns this suite red instead of turning the
dashboard green. The module has a hyphenated filename, so it is loaded via importlib.
"""
from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

_HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("cloud_finops_collect", _HERE / "cloud-finops-collect.py")
cfc = importlib.util.module_from_spec(_spec)
assert _spec.loader is not None
_spec.loader.exec_module(cfc)

RULES = cfc.load_rules(_HERE.parent / "aws" / "finops" / "cloud-finops-rules.json")
TAGS = {"Project": "openbank", "ManagedBy": "opentofu", "Env": "sandbox"}


def res(name, rtype, cost, components, tags=TAGS, subresources=None):
    return {
        "name": name,
        "resourceType": rtype,
        "monthlyCost": str(cost),
        "tags": tags,
        "costComponents": [{"name": n, "monthlyCost": str(c)} for n, c in components],
        "subresources": subresources or [],
    }


def doc(*resources):
    return {"projects": [{"name": "openbank-infra/aws/envs/x", "breakdown": {"resources": list(resources)}}]}


# Known-positive: one resource per rule, shaped like real `infracost breakdown --format json`.
POSITIVE = doc(
    res("aws_instance.legacy", "aws_instance", 40,
        [("Instance usage (Linux/UNIX, on-demand, t2.medium)", 33.87)],
        subresources=[{"name": "root_block_device", "costComponents": [
            {"name": "Storage (general purpose SSD, gp2)", "monthlyCost": "10"}]}]),
    res("aws_db_instance.main", "aws_db_instance", 60,
        [("Database instance (on-demand, Single-AZ, db.m5.large)", 50.0)]),
    res("aws_s3_bucket.untagged", "aws_s3_bucket", 1, [("Standard storage", 1.0)], tags={"Project": "openbank"}),
    res("aws_nat_gateway.a", "aws_nat_gateway", 32.85, [("NAT gateway", 32.85)]),
    res("aws_nat_gateway.b", "aws_nat_gateway", 32.85, [("NAT gateway", 32.85)]),
)

# Known-negative: the remediated shape of every resource above.
NEGATIVE = doc(
    res("aws_instance.modern", "aws_instance", 30,
        [("Instance usage (Linux/UNIX, on-demand, t4g.medium)", 24.53)],
        subresources=[{"name": "root_block_device", "costComponents": [
            {"name": "Storage (general purpose SSD, gp3)", "monthlyCost": "8"}]}]),
    res("aws_db_instance.main", "aws_db_instance", 45,
        [("Database instance (on-demand, Single-AZ, db.m7g.large)", 45.0)]),
    res("aws_s3_bucket.tagged", "aws_s3_bucket", 1, [("Standard storage", 1.0)]),
    res("aws_iam_role.untaggable", "aws_iam_role", 0, [], tags=None),
    res("aws_nat_gateway.only", "aws_nat_gateway", 32.85, [("NAT gateway", 32.85)]),
)


def rule_ids(findings):
    return sorted((f["resource"], f["rule_id"]) for f in findings)


class EvaluateTest(unittest.TestCase):
    def test_known_positive_flags_every_rule(self):
        got = rule_ids(cfc.evaluate(cfc.flatten(POSITIVE), RULES))
        self.assertEqual(got, [
            ("aws_db_instance.main", "non-graviton-instance"),
            ("aws_instance.legacy", "ebs-gp2-to-gp3"),
            ("aws_instance.legacy", "previous-generation-instance"),
            ("aws_nat_gateway.b", "nat-gateway-per-az"),
            ("aws_s3_bucket.untagged", "missing-required-tags"),
        ])
        # Every rule in the versioned file is exercised — a new rule without a fixture fails here.
        self.assertEqual({r for _, r in got}, set(RULES["rules"]))

    def test_known_negative_flags_nothing(self):
        self.assertEqual(cfc.evaluate(cfc.flatten(NEGATIVE), RULES), [])

    def test_savings_are_ratio_of_matched_component(self):
        f = {x["rule_id"]: x for x in cfc.evaluate(cfc.flatten(POSITIVE), RULES)}
        self.assertAlmostEqual(f["ebs-gp2-to-gp3"]["est_monthly_saving"], 2.0)       # 10 * 0.2
        self.assertAlmostEqual(f["non-graviton-instance"]["est_monthly_saving"], 10.0)  # 50 * 0.2
        self.assertAlmostEqual(f["nat-gateway-per-az"]["est_monthly_saving"], 32.85)

    def test_compliance(self):
        pos = cfc.flatten(POSITIVE)
        # 5 resources, 4 distinct flagged (legacy, main, untagged, nat b) -> 0.2
        self.assertAlmostEqual(cfc.compliance(pos, cfc.evaluate(pos, RULES)), 0.2)
        neg = cfc.flatten(NEGATIVE)
        self.assertEqual(cfc.compliance(neg, cfc.evaluate(neg, RULES)), 1.0)


class RefusesSilentSuccessTest(unittest.TestCase):
    def test_empty_breakdown_is_an_error_not_zero_cost(self):
        with tempfile.TemporaryDirectory() as d:
            for env in RULES["environments"]:
                Path(d, f"{env}.json").write_text(json.dumps(doc()))
            with self.assertRaises(cfc.CollectError):
                cfc.load_env_breakdowns(Path(d), RULES["environments"])

    def test_missing_env_output_is_an_error(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(cfc.CollectError):
                cfc.load_env_breakdowns(Path(d), RULES["environments"])

    def test_populated_breakdowns_load(self):
        with tempfile.TemporaryDirectory() as d:
            for env in RULES["environments"]:
                Path(d, f"{env}.json").write_text(json.dumps(POSITIVE))
            self.assertEqual(len(cfc.load_env_breakdowns(Path(d), RULES["environments"])), len(RULES["environments"]))

    def test_empty_cost_explorer_is_an_error(self):
        with self.assertRaises(cfc.CollectError):
            cfc.parse_cost_explorer({"ResultsByTime": [{"TimePeriod": {"Start": "2026-09-01"}, "Groups": []}]})

    def test_cost_explorer_rows(self):
        rows = cfc.parse_cost_explorer({"ResultsByTime": [{"TimePeriod": {"Start": "2026-09-01"}, "Groups": [
            {"Keys": ["EC2 - Other"], "Metrics": {"UnblendedCost": {"Amount": "1.5", "Unit": "USD"}}},
            {"Keys": ["Amazon Elastic Compute Cloud - Compute"], "Metrics": {"UnblendedCost": {"Amount": "3", "Unit": "USD"}}},
        ]}]})
        self.assertEqual([(r["service"], r["category"], r["amount"]) for r in rows], [
            ("EC2 - Other", "EC2-Other", 1.5),
            ("Amazon Elastic Compute Cloud - Compute", "EC2", 3.0),
        ])


class CategoryTest(unittest.TestCase):
    def test_both_sides_share_categories(self):
        self.assertEqual(cfc.iac_category("aws_nat_gateway"), cfc.ce_category("EC2 - Other"))
        self.assertEqual(cfc.iac_category("aws_db_instance"), cfc.ce_category("Amazon Relational Database Service"))
        self.assertEqual(cfc.iac_category("aws_eks_cluster"), cfc.ce_category("Amazon Elastic Kubernetes Service"))


if __name__ == "__main__":
    unittest.main()
