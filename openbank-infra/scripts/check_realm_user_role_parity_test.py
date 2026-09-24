#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""End-to-end falsification of .github/scripts/check-realm-user-role-parity.py (#10486).

Runs the detector exactly as the keycloak-realm-drift CronJob does — against the REAL realm
templates in this repo — with a synthetic live snapshot in the capture's own shape. The
known-positive is the drift that was live on 2026-09-21 and that the job reported as parity:
`service-account-openbank-services` holding ROLE_OPERATOR where the template grants only
ROLE_API. It must now fail the job (exit 1, ::error::). The control is the same snapshot with
that one mapping removed, which must pass — so the red is proven to come from the grant.

Run: python3 -m unittest discover -s openbank-infra/scripts -p '*_test.py'
"""

import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

REPO = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = REPO / ".github" / "scripts" / "check-realm-user-role-parity.py"
TEMPLATE = REPO / "openbank-infra/gitops/components/keycloak/realm-template.json"


def template_snapshot() -> dict:
    """The live capture shape for a realm that matches its template exactly."""
    out = {}
    for u in json.loads(TEMPLATE.read_text())["users"]:
        entry = {"realmRoles": sorted(set(u.get("realmRoles") or []) | {"default-roles-openbank"}),
                 "clientRoles": u.get("clientRoles") or {}}
        out[u["username"]] = entry
    return out


def run(openbank_live: dict, scopes: dict | None = None):
    with tempfile.TemporaryDirectory() as d:
        ob = pathlib.Path(d, "ob.json")
        ob.write_text(json.dumps(openbank_live))
        cu = pathlib.Path(d, "cu.json")
        cu.write_text(json.dumps({"service-account-customer-edge-admin": {"realmRoles": [],
                                                                           "clientRoles": {}}}))
        args = [sys.executable, str(SCRIPT), "--root", str(REPO),
                "--live", f"openbank={ob}", "--live", f"openbank-customers={cu}"]
        if scopes is not None:
            sc = pathlib.Path(d, "sc.json")
            sc.write_text(json.dumps(scopes))
            args += ["--live-client-scopes", f"openbank={sc}"]
        p = subprocess.run(args, capture_output=True, text=True)
        return p.returncode, p.stderr, json.loads(p.stdout or "{}")


class UserRoleParity(unittest.TestCase):
    def test_template_shaped_snapshot_is_clean(self):
        rc, err, rep = run(template_snapshot())
        self.assertEqual(rc, 0, err)
        self.assertEqual(rep["privilegedServiceAccountDriftCount"], 0)

    def test_known_positive_shared_client_operator_fails_the_job(self):
        live = template_snapshot()
        live["service-account-openbank-services"]["realmRoles"].append("ROLE_OPERATOR")
        rc, err, rep = run(live)
        self.assertEqual(rc, 1, err)
        self.assertIn("::error::[openbank] service account `service-account-openbank-services`", err)
        self.assertIn("ROLE_OPERATOR", err)
        self.assertEqual(rep["realms"]["openbank"]["privilegedServiceAccountDrift"],
                         ["service-account-openbank-services"])
        self.assertEqual(rep["privilegedServiceAccountDriftCount"], 1)

    def test_undeclared_service_account_with_operator_fails(self):
        live = template_snapshot()
        live["service-account-sandbox-e2e"] = {"realmRoles": ["ROLE_OPERATOR"], "clientRoles": {}}
        rc, err, _ = run(live)
        self.assertEqual(rc, 1, err)
        self.assertIn("does not declare it at all", err)

    def test_extra_client_role_is_reported(self):
        live = template_snapshot()
        live["service-account-openbank-interest"]["clientRoles"] = {"realm-management": ["manage-users"]}
        rc, err, rep = run(live)
        self.assertIn("realm-management/manage-users",
                      rep["realms"]["openbank"]["overGranted"]["service-account-openbank-interest"])

    def test_live_edge_without_profile_scope_fails(self):
        scopes = {"openbank-edge": ["openid", "roles"], "openbank-services": ["openid", "profile"],
                  "openbank-interest": ["openid", "profile"]}
        rc, err, rep = run(template_snapshot(), scopes)
        self.assertEqual(rc, 1, err)
        self.assertEqual(rep["clientScopes"]["openbank"]["missingProfileScope"], ["openbank-edge"])
        scopes["openbank-edge"].append("profile")
        rc, err, _ = run(template_snapshot(), scopes)
        self.assertEqual(rc, 0, err)


if __name__ == "__main__":
    unittest.main()
