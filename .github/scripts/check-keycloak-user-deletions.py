#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Detector: were Keycloak USERS deleted in the last window?
#
# WHY THIS EXISTS
#   A Keycloak user id is a join key other systems hold: customer-edge binds every passkey to it,
#   and party-service stores it as `parties.keycloak_sub`. A deleted user therefore cannot be
#   recreated — a new user gets a new id, and every passkey and party link still points at the old
#   one. The only way back is a point-in-time restore of the Keycloak database. That makes a user
#   deletion a one-way door, and nothing in the platform watched it: Keycloak records the admin
#   event and raises nothing, the realm-drift scan compares roles rather than people, and the first
#   signal is a customer reporting that sign-in no longer works.
#
# WHAT IT DECIDES
#   Customer realms (default `openbank-customers`): ANY user deletion is a finding. Customers are
#   never deleted by routine operation — offboarding closes the party, it does not remove the
#   identity — so there is no threshold to tune and no benign case to whitelist.
#   Staff realms (default `openbank`): a finding at `--bulk-threshold` deletions or more inside the
#   window. Removing one temporary fixture operator is routine; a scripted pass is not.
#   Either realm: admin events DISABLED is a finding. With them off this detector sees nothing, and
#   an absent signal must never read as a clean one.
#
# WHAT IT DELIBERATELY DOES NOT DO
#   It does not block, revert or restore anything — Keycloak has no veto hook for admin
#   operations, and a job that writes to the identity provider is a larger blast radius than the
#   defect. It also reads no usernames or e-mails: only event ids, resource paths (user UUIDs),
#   timestamps and the acting principal's ids, because the report is published into a ConfigMap.
#
# Pure function over captured snapshots, so it is unit-tested without a cluster:
#   openbank-infra/scripts/check_keycloak_user_deletions_test.py

import argparse
import json
import sys


def parse_pairs(values):
    out = {}
    for v in values or []:
        realm, _, path = v.partition("=")
        if not realm or not path:
            raise SystemExit(f"expected REALM=PATH, got {v!r}")
        out[realm] = path
    return out


def load(path):
    with open(path) as f:
        return json.load(f)


def deletions_in_window(events, since_ms):
    """User DELETE admin events at or after since_ms, oldest first."""
    hits = []
    for e in events:
        if e.get("operationType") != "DELETE" or e.get("resourceType") != "USER":
            continue
        path = e.get("resourcePath") or ""
        # users/<id> only — users/<id>/role-mappings/... is a role change, not a deletion.
        if not path.startswith("users/") or path.count("/") != 1:
            continue
        t = e.get("time")
        if not isinstance(t, int) or t < since_ms:
            continue
        auth = e.get("authDetails") or {}
        hits.append({
            "time": t,
            "userId": path.split("/", 1)[1],
            "actorUserId": auth.get("userId"),
            "actorClientId": auth.get("clientId"),
            "actorIp": auth.get("ipAddress"),
        })
    return sorted(hits, key=lambda h: h["time"])


def evaluate(events_by_realm, config_by_realm, customer_realms, now_ms, window_s, bulk_threshold):
    since_ms = now_ms - window_s * 1000
    report = {"windowSeconds": window_s, "sinceMs": since_ms, "realms": {}}
    findings = []
    for realm in sorted(set(events_by_realm) | set(config_by_realm)):
        cfg = config_by_realm.get(realm)
        entry = {"customerRealm": realm in customer_realms}
        if cfg is None or not cfg.get("adminEventsEnabled"):
            entry["adminEventsEnabled"] = False
            findings.append(
                f"{realm}: admin events are DISABLED (or their config was not captured) — "
                "user deletions in this realm are invisible to this detector")
        else:
            entry["adminEventsEnabled"] = True
        hits = deletions_in_window(events_by_realm.get(realm, []), since_ms)
        entry["userDeletions"] = hits
        entry["userDeletionCount"] = len(hits)
        if hits:
            if realm in customer_realms:
                findings.append(
                    f"{realm}: {len(hits)} CUSTOMER user(s) deleted in the last {window_s}s — "
                    "a deleted user cannot be recreated with the same id; restore from backup")
            elif len(hits) >= bulk_threshold:
                findings.append(
                    f"{realm}: {len(hits)} user(s) deleted in the last {window_s}s "
                    f"(bulk threshold {bulk_threshold})")
        report["realms"][realm] = entry
    report["findings"] = findings
    return report


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--events", action="append", required=True,
                    help="REALM=PATH to the JSON array from /admin/realms/REALM/admin-events")
    # Not required: a realm whose config was not captured is reported as blind, not skipped.
    ap.add_argument("--config", action="append", default=[],
                    help="REALM=PATH to the JSON object from /admin/realms/REALM/events/config")
    ap.add_argument("--customer-realm", action="append", default=None,
                    help="realm where ANY user deletion is a finding (default: openbank-customers)")
    ap.add_argument("--now-ms", type=int, required=True)
    ap.add_argument("--window-seconds", type=int, default=1200)
    ap.add_argument("--bulk-threshold", type=int, default=3)
    a = ap.parse_args(argv)

    events = {r: load(p) for r, p in parse_pairs(a.events).items()}
    for r, ev in events.items():
        if not isinstance(ev, list):
            raise SystemExit(f"{r}: admin-events capture is not a JSON array — refusing to call it clean")
    config = {r: load(p) for r, p in parse_pairs(a.config).items()}
    customers = set(a.customer_realm or ["openbank-customers"])

    report = evaluate(events, config, customers, a.now_ms, a.window_seconds, a.bulk_threshold)
    json.dump(report, sys.stdout, indent=2, sort_keys=True)
    sys.stdout.write("\n")
    for f in report["findings"]:
        print(f"::error::{f}", file=sys.stderr)
    return 1 if report["findings"] else 0


if __name__ == "__main__":
    sys.exit(main())
