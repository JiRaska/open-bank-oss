#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Falsification suite for .github/scripts/check-realm-role-parity.py (issue #2540).

A gate that has only ever passed is unfalsified. Every case below feeds the detector an input it
MUST classify a particular way, in BOTH directions — a role declared and not live, a role live and
not declared, and (the case that actually costs money) a role no realm issues that the code names.
The negative cases matter just as much: a detector that fires on Keycloak's own built-ins, or that
reads an auth failure as "the realm has no roles", is one nobody reads by week two.

Run: python3 -m unittest discover -s openbank-infra/scripts -p '*_test.py'
"""

import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

REPO = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = REPO / ".github" / "scripts" / "check-realm-role-parity.py"
TEMPLATE = REPO / "openbank-infra/gitops/components/keycloak/realm-template.json"

BUILTINS = ["offline_access", "uma_authorization", "default-roles-openbank"]


def declared_roles() -> list:
    doc = json.loads(TEMPLATE.read_text())
    names = [r["name"] for r in doc["roles"]["realm"]]
    # The detector flattens client roles into the same name set as realm roles — the synthetic
    # live snapshot must do the same, or any client-role addition reads as declared-not-live.
    for client_roles in (doc["roles"].get("client", {}) or {}).values():
        names += [r["name"] for r in client_roles or []]
    return sorted(names)


def run(live_roles, realm="openbank", extra=()):
    """Run the detector against the REAL repo templates and a synthetic live snapshot."""
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as fh:
        json.dump([{"name": n} for n in live_roles], fh)
        path = fh.name
    # The customers realm has a template too; supply it verbatim so it never colours the result.
    customers = json.loads(
        (REPO / "openbank-infra/gitops/components/keycloak/customers-realm-template.json").read_text(),
    )
    cust_names = [r["name"] for r in (customers.get("roles", {}).get("realm") or [])]
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as fh:
        json.dump([{"name": n} for n in cust_names + ["offline_access"]], fh)
        cust_path = fh.name
    cmd = [
        sys.executable, str(SCRIPT), "--root", str(REPO),
        "--live", f"{realm}={path}",
        "--live", f"{customers['realm']}={cust_path}",
        *extra,
    ]
    return subprocess.run(cmd, capture_output=True, text=True)


class RealmRoleParityTest(unittest.TestCase):
    # --- the case it must NOT flag ------------------------------------------------------------
    def test_identical_sets_pass(self):
        """Template == live (plus Keycloak's built-ins) is clean. Built-ins are not findings."""
        r = run(declared_roles() + BUILTINS)
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertNotIn("::error", r.stderr)

    def test_builtins_alone_are_not_live_not_declared(self):
        """default-roles-<realm>/offline_access/uma_authorization exist in every realm and in no
        template. If they were reported, the detector would fire forever and be ignored."""
        r = run(declared_roles() + BUILTINS)
        self.assertNotIn("offline_access", r.stderr)
        self.assertNotIn("default-roles-openbank", r.stderr)

    def test_uma_protection_is_a_builtin_not_a_finding(self):
        """CLIENT-scoped built-in. Keycloak adds uma_protection to any of OUR clients that turns
        on authorization services, so it appears in the live capture and in no template. It only
        became reachable once the capture started including client roles (2026-08-02); before
        that the realm-roles-only capture reported every client role as declared-not-live, which
        is what kept `mcp-caller` permanently red and the CronJob failing on its own output."""
        r = run(declared_roles() + BUILTINS + ["uma_protection"])
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertNotIn("uma_protection", r.stderr)

    def test_client_scoped_declared_role_is_matched_by_the_live_capture(self):
        """`mcp-caller` is declared under roles.client, not roles.realm. template_roles() flattens
        both into one name set, so a live snapshot that carries it must read as in-sync — and one
        that omits it must flag it. Asserting only the first half would pass against a capture
        that never looked at client roles at all, which was the actual defect."""
        self.assertIn("mcp-caller", declared_roles())
        r = run(declared_roles() + BUILTINS)
        self.assertNotIn("mcp-caller", r.stderr)

        without = [n for n in declared_roles() if n != "mcp-caller"] + BUILTINS
        r2 = run(without)
        self.assertNotEqual(0, r2.returncode)
        self.assertIn("mcp-caller", r2.stderr)

    # --- direction 1: declared in the template, absent from the live realm ---------------------
    def test_role_missing_from_live_realm_is_flagged(self):
        """The real #2540 defect: ROLE_KYC_REVIEWER declared, never created live."""
        live = [n for n in declared_roles() if n != "ROLE_KYC_REVIEWER"] + BUILTINS
        r = run(live)
        self.assertEqual(1, r.returncode, "a declared-but-absent role must fail the detector")
        self.assertIn("ROLE_KYC_REVIEWER", r.stderr)
        self.assertIn("NOT issued by the live realm", r.stderr)

    def test_missing_role_named_by_code_reports_its_call_sites(self):
        """Highest-severity direction: the live realm cannot mint a token the code demands. The
        finding has to name where, or it is a puzzle rather than a ticket."""
        live = [n for n in declared_roles() if n != "ROLE_KYC_REVIEWER"] + BUILTINS
        r = run(live)
        self.assertIn("@RolesAllowed site(s) name it", r.stderr)
        self.assertIn(".kt:", r.stderr)
        report = json.loads(r.stdout)
        self.assertIn("ROLE_KYC_REVIEWER", report["realms"]["openbank"]["namedByCodeButNotLive"])

    # --- direction 2: live in the realm, declared nowhere in git -------------------------------
    def test_undeclared_live_role_is_flagged(self):
        """The other real half of #2540: ROLE_DEMO existed live and in no template. It works
        today and a cold-started cluster silently loses it."""
        r = run(declared_roles() + BUILTINS + ["ROLE_SOMETHING_UNDECLARED"])
        self.assertEqual(1, r.returncode, "a live-but-undeclared role must fail the detector")
        self.assertIn("ROLE_SOMETHING_UNDECLARED", r.stderr)
        self.assertIn("declared nowhere in git", r.stderr)

    # --- capture failures must not read as data ------------------------------------------------
    def test_missing_snapshot_is_unchecked_not_clean(self):
        """A realm with a template and no snapshot must fail as UNCHECKED. Reporting it clean is
        how a detector certifies the thing it never looked at."""
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as fh:
            json.dump([{"name": n} for n in declared_roles() + BUILTINS], fh)
            path = fh.name
        r = subprocess.run(
            [sys.executable, str(SCRIPT), "--root", str(REPO), "--live", f"openbank={path}"],
            capture_output=True, text=True,
        )
        self.assertEqual(1, r.returncode)
        self.assertIn("UNCHECKED, not clean", r.stderr)

    def test_empty_snapshot_is_a_capture_failure(self):
        """An empty role list means the admin call failed (bad credential, wrong realm) — Keycloak
        always issues its own built-ins. Treating it as data would report the whole template
        missing and bury the real signal."""
        r = run([])
        self.assertNotEqual(0, r.returncode)
        self.assertIn("failed capture", r.stderr)

    # --- the detector must be reading the real repo, not a fixture -----------------------------
    def test_reads_the_committed_template(self):
        self.assertIn("ROLE_KYC_REVIEWER", declared_roles(), "template lost the four-eyes roles")


if __name__ == "__main__":
    unittest.main()
