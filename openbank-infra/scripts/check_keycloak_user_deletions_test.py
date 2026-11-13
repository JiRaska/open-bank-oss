#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Falsification suite for .github/scripts/check-keycloak-user-deletions.py.

Each case feeds the detector an input it MUST classify a particular way, in both directions: a
customer deletion and a staff bulk pass must fail it; a role-mapping change, an old event, a
single staff fixture removal and an empty realm must not. Disabled admin events must fail it,
because a blind detector reading as clean is the failure this exists to prevent.

Run: python3 -m unittest discover -s openbank-infra/scripts -p '*_test.py'
"""

import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

REPO = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = REPO / ".github" / "scripts" / "check-keycloak-user-deletions.py"

NOW = 1_800_000_000_000
ON = {"adminEventsEnabled": True}


def ev(minutes_ago, path="users/11111111-1111-4111-8111-111111111111", op="DELETE", rtype="USER"):
    return {"time": NOW - minutes_ago * 60_000, "operationType": op, "resourceType": rtype,
            "resourcePath": path,
            "authDetails": {"userId": "actor", "clientId": "admin-cli-id", "ipAddress": "127.0.0.1"}}


def user(i):
    return f"users/00000000-0000-4000-8000-{i:012d}"


class Run:
    def __init__(self, events, config=None):
        self.dir = tempfile.TemporaryDirectory()
        d = pathlib.Path(self.dir.name)
        config = config if config is not None else {r: ON for r in events}
        self.args = []
        for realm, items in events.items():
            (d / f"{realm}-ev.json").write_text(json.dumps(items))
            self.args += ["--events", f"{realm}={d / f'{realm}-ev.json'}"]
        for realm, cfg in config.items():
            (d / f"{realm}-cfg.json").write_text(json.dumps(cfg))
            self.args += ["--config", f"{realm}={d / f'{realm}-cfg.json'}"]

    def __call__(self):
        p = subprocess.run([sys.executable, str(SCRIPT), "--now-ms", str(NOW),
                            "--window-seconds", "1200", *self.args],
                           capture_output=True, text=True)
        self.dir.cleanup()
        return p.returncode, json.loads(p.stdout), p.stderr


class CustomerRealm(unittest.TestCase):
    def test_single_customer_deletion_fails(self):
        rc, rep, err = Run({"openbank-customers": [ev(2)]})()
        self.assertEqual(rc, 1)
        self.assertEqual(rep["realms"]["openbank-customers"]["userDeletionCount"], 1)
        self.assertIn("CUSTOMER", err)

    def test_bulk_customer_deletion_reports_every_user(self):
        rc, rep, _ = Run({"openbank-customers": [ev(1, user(i)) for i in range(41)]})()
        self.assertEqual(rc, 1)
        self.assertEqual(rep["realms"]["openbank-customers"]["userDeletionCount"], 41)

    def test_deletion_outside_window_is_ignored(self):
        rc, rep, _ = Run({"openbank-customers": [ev(21)]})()
        self.assertEqual(rc, 0)
        self.assertEqual(rep["findings"], [])

    def test_role_mapping_delete_is_not_a_user_deletion(self):
        rc, _, _ = Run({"openbank-customers": [
            ev(1, "users/11111111-1111-4111-8111-111111111111/role-mappings/realm",
               rtype="REALM_ROLE_MAPPING"),
            ev(1, "users/11111111-1111-4111-8111-111111111111/role-mappings/realm"),
        ]})()
        self.assertEqual(rc, 0)

    def test_user_create_is_not_a_deletion(self):
        rc, _, _ = Run({"openbank-customers": [ev(1, op="CREATE")]})()
        self.assertEqual(rc, 0)

    def test_report_carries_no_username(self):
        _, rep, _ = Run({"openbank-customers": [ev(1)]})()
        self.assertNotIn("username", json.dumps(rep))


class StaffRealm(unittest.TestCase):
    def test_single_staff_deletion_is_routine(self):
        rc, _, _ = Run({"openbank": [ev(1)]})()
        self.assertEqual(rc, 0)

    def test_staff_bulk_deletion_fails(self):
        rc, _, err = Run({"openbank": [ev(1, user(i)) for i in range(3)]})()
        self.assertEqual(rc, 1)
        self.assertIn("bulk threshold", err)


class Blindness(unittest.TestCase):
    def test_disabled_admin_events_fail(self):
        rc, _, err = Run({"openbank-customers": []},
                         {"openbank-customers": {"adminEventsEnabled": False}})()
        self.assertEqual(rc, 1)
        self.assertIn("DISABLED", err)

    def test_missing_config_capture_fails(self):
        rc, _, _ = Run({"openbank-customers": []}, {})()
        self.assertEqual(rc, 1)

    def test_clean_realms_pass(self):
        rc, rep, _ = Run({"openbank": [], "openbank-customers": []})()
        self.assertEqual(rc, 0)
        self.assertEqual(rep["findings"], [])

    def test_non_array_capture_refuses(self):
        d = tempfile.TemporaryDirectory()
        p = pathlib.Path(d.name)
        (p / "e.json").write_text(json.dumps({"error": "unauthorized"}))
        (p / "c.json").write_text(json.dumps(ON))
        r = subprocess.run([sys.executable, str(SCRIPT), "--now-ms", str(NOW),
                            "--events", f"openbank-customers={p / 'e.json'}",
                            "--config", f"openbank-customers={p / 'c.json'}"],
                           capture_output=True, text=True)
        d.cleanup()
        self.assertNotEqual(r.returncode, 0)


if __name__ == "__main__":
    unittest.main()
