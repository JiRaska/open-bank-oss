#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Tests for netpol-coverage.py.

The failure this guards against is one-directional and quiet: a selector bug
that OVER-matches reports a pod as covered, i.e. reports that a default-deny is
safe for it. So every case below asserts the uncovered side too, never only the
covered one.
"""

import importlib.util
import os
import unittest

_spec = importlib.util.spec_from_file_location(
    "netpol_coverage",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "netpol-coverage.py"),
)
nc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(nc)


def pod(ns, name, labels, phase="Running"):
    return {"metadata": {"namespace": ns, "name": name, "labels": labels},
            "status": {"phase": phase}}


def policy(ns, name, selector, types=("Ingress",)):
    return {"metadata": {"namespace": ns, "name": name},
            "spec": {"podSelector": selector, "policyTypes": list(types)}}


class SelectsTest(unittest.TestCase):
    def test_empty_selector_selects_every_pod(self):
        # This is the default-deny shape; if it did not match, the tool would
        # report a protected namespace as unprotected.
        self.assertTrue(nc.selects({}, {"a": "b"}))
        self.assertTrue(nc.selects(None, {}))

    def test_match_labels_are_anded_and_exact(self):
        self.assertTrue(nc.selects({"matchLabels": {"a": "b"}}, {"a": "b", "c": "d"}))
        self.assertFalse(nc.selects({"matchLabels": {"a": "b"}}, {"a": "z"}))
        self.assertFalse(nc.selects({"matchLabels": {"a": "b"}}, {}))
        self.assertFalse(
            nc.selects({"matchLabels": {"a": "b", "c": "d"}}, {"a": "b"}))

    def test_match_expressions(self):
        In = {"matchExpressions": [{"key": "k", "operator": "In", "values": ["x"]}]}
        self.assertTrue(nc.selects(In, {"k": "x"}))
        self.assertFalse(nc.selects(In, {"k": "y"}))
        self.assertFalse(nc.selects(In, {}))
        Ex = {"matchExpressions": [{"key": "k", "operator": "Exists"}]}
        self.assertTrue(nc.selects(Ex, {"k": ""}))
        self.assertFalse(nc.selects(Ex, {"other": "1"}))


class CoverageTest(unittest.TestCase):
    def test_cnpg_pod_is_uncovered_when_policies_select_only_the_service(self):
        """The fleet's actual shape: a namespace full of allow-lists whose
        Postgres instances no policy selects. Reporting this namespace as
        covered is the exact mistake that would sever every service from its
        database."""
        pods = [
            pod("accounts", "account-service-1",
                {"app.kubernetes.io/name": "account-service"}),
            pod("accounts", "accounts-db-1",
                {"app.kubernetes.io/name": "postgresql",
                 "cnpg.io/cluster": "accounts-db"}),
        ]
        pols = [policy("accounts", "account-service-ingress-allow-list",
                       {"matchLabels": {"app.kubernetes.io/name": "account-service"}})]
        cov = nc.coverage(pods, pols)
        self.assertEqual(cov["accounts"]["covered"], ["account-service"])
        self.assertEqual(cov["accounts"]["uncovered"], ["postgresql"])

    def test_policy_direction_is_honoured(self):
        pods = [pod("platform", "copilot-1",
                    {"app.kubernetes.io/name": "copilot-service"})]
        pols = [policy("platform", "copilot-egress",
                       {"matchLabels": {"app.kubernetes.io/name": "copilot-service"}},
                       types=("Egress",))]
        self.assertEqual(nc.coverage(pods, pols, "Egress")["platform"]["covered"],
                         ["copilot-service"])
        self.assertEqual(nc.coverage(pods, pols, "Ingress")["platform"]["uncovered"],
                         ["copilot-service"])

    def test_a_policy_does_not_leak_across_namespaces(self):
        pods = [pod("aml", "aml-service-1", {"app.kubernetes.io/name": "x"})]
        pols = [policy("accounts", "p", {})]
        self.assertEqual(nc.coverage(pods, pols)["aml"]["uncovered"], ["x"])

    def test_completed_pods_are_ignored(self):
        pods = [pod("aml", "job-1", {"app.kubernetes.io/name": "j"}, phase="Succeeded")]
        self.assertEqual(nc.coverage(pods, [])["aml"]["uncovered"], [])


if __name__ == "__main__":
    unittest.main()
