#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Detector: is the realm JSON Keycloak WOULD IMPORT on a cold start the same realm the
# committed template describes? (issue #3246)
#
# WHY THIS EXISTS — THE THIRD COMPARISON
#   Three artifacts describe each realm, and until this script only two of the three pairs
#   were ever compared:
#
#     repo template  --(check-roles-allowed-realm.py, PR-time, enforced)-->  @RolesAllowed names
#     repo template  --(check-realm-role-parity.py,   CronJob)------------>  the LIVE realm
#     repo template  --(THIS SCRIPT)-------------------------------------->  the IMPORT artifact
#
#   The import artifact is the one that rebuilds the realm. keycloak.yaml's `realm-import`
#   volume projects the Secrets `keycloak-realm-import` / `keycloak-customers-realm-import`,
#   which ExternalSecrets fills from Vault KV. The committed template feeds NOTHING. So the
#   file the enforced gate validates and the file Keycloak reads are two unrelated objects,
#   and no observation of the live realm can tell them apart — `--import-realm` skips a realm
#   that already exists, so the import artifact has had no effect since the realm first came up.
#
# MEASURED 2026-08-03 (issue #3246), sandbox, both realms:
#
#     realm                | artifact         | roles | clients | users
#     ---------------------|------------------|-------|---------|------
#     openbank             | repo template    |    14 |      10 |     6
#     openbank             | import Secret    |     4 |       2 |     1
#     openbank             | LIVE             |    14 |      10 |     4 (+2 service accounts,
#                          |                  |       |         |       which /users never returns)
#     openbank-customers   | repo template    |     2 |       3 |     0
#     openbank-customers   | import Secret    |     1 |       1 |     0
#     openbank-customers   | LIVE             |     2 |       3 |     0
#
#   The shape that decides everything downstream: the import artifact is a strict ANCESTOR of
#   the template, not a divergent fork. Every name in it is also in the template, in both
#   realms, in all three dimensions. Nothing live would be dropped by making the import
#   artifact equal the template — which is why the convergence direction is Vault-to-repo and
#   the repo template is authoritative. The reverse (scoping the enforced gate down to the
#   import artifact) would make the gate honest about a 4-role blob and instantly fail ten
#   roles' worth of `@RolesAllowed` sites that the running system serves correctly today.
#
# WHY THE BASELINE, AND WHY IT IS NOT A CHEAT
#   #2540 makes the ordering point that this script has to respect: a detector shipped before
#   the reconcile fires on its FIRST run, on a condition only a Vault write can clear. That is
#   an alert which is the resting state from minute one — the failure mode this repo has
#   already paid for. Only a Vault write clears it, and a Vault write is not something a PR can
#   do.
#
#   So today's measured gap is declared in KNOWN_STALE, the same shape check-kafka-dotted-keys
#   and check-pact-provider-replay use. That buys the property that matters: a NEW divergence —
#   an eleventh role added to the template, a client removed from the import artifact — is red
#   on the next run instead of disappearing into a gap that was already red. And the baseline
#   is checked in BOTH directions, so the day the owner runs the reconcile the entry becomes
#   stale and this script says so, which is what turns "someone should do the Vault write" into
#   a signal rather than a note in an issue.
#
# WHAT IT DELIBERATELY DOES NOT DO
#   It does not compare secret VALUES, and it must not: the template carries `__PLACEHOLDER__`
#   tokens where the import artifact carries real client secrets and passwords. Only names are
#   read — role names, clientIds, usernames — so this script's output is safe to publish into a
#   ConfigMap. Anything that diffed the documents wholesale would leak credentials into the
#   drift report.
#
# Run:
#   python3 .github/scripts/check-realm-import-parity.py \
#       --import openbank=/work/openbank-import.json \
#       --import openbank-customers=/work/openbank-customers-import.json
#   python3 .github/scripts/check-realm-import-parity.py --self-test
#
# A realm with no --import snapshot is reported as unchecked, never as clean.

import argparse
import json
import pathlib
import sys

REALM_GLOB = "openbank-infra/gitops/components/keycloak/*realm-template*.json"

# Server-managed names. Keycloak creates these itself, so their presence or absence in an
# import artifact says nothing about drift. `default-roles-<realm>` is declared explicitly by
# the customers template, so it has to be subtracted from BOTH sides or it is a permanent
# finding in whichever direction it is missing.
BUILTIN_CLIENTS = frozenset(
    {"account", "account-console", "admin-cli", "broker", "realm-management",
     "security-admin-console"},
)
BUILTIN_ROLES = frozenset({"offline_access", "uma_authorization", "uma_protection"})

# ---------------------------------------------------------------------------
# The measured 2026-08-03 gap (#3246). Every entry is a name the committed template declares
# and the deployed import artifact does not carry, i.e. a name a cold-started cluster would
# LOSE. Cleared by the Vault reconcile in docs/runbooks/0009-keycloak-realm-import-reconcile.md
# — after which this dict must be emptied, and this script fails until it is.
#
# There is deliberately no baseline for the other direction (a name in the import artifact that
# git does not declare): none exists today, and one appearing is exactly the review-nobody-did
# case a cold start would materialise.
# ---------------------------------------------------------------------------
KNOWN_STALE = {
    "openbank": {
        "roles": {
            "ROLE_AUDITOR", "ROLE_COMPLIANCE", "ROLE_CREDIT_RISK", "ROLE_DEMO", "ROLE_KYC",
            "ROLE_KYC_OPENER", "ROLE_KYC_REVIEWER", "ROLE_LENDING_OFFICER", "ROLE_PAYMENTS",
            "ROLE_SUPERVISOR", "mcp-caller",
        },
        "clients": {
            "apicurio-registry", "argocd", "goalert", "openbank-edge", "openbank-glitchtip",
            "openbank-grafana", "openbank-mcp-service", "openbao",
        },
        "users": {
            "compliance2@openbank.local", "compliance@openbank.local", "demo@openbank.local",
            "service-account-openbank-edge", "service-account-openbank-services",
        },
    },
    "openbank-customers": {
        "roles": set(),
        "clients": {"customer-edge-admin", "openbank-edge-webauthn"},
        "users": set(),
    },
}

DIMENSIONS = ("roles", "clients", "users")


def _names(doc: dict) -> dict:
    """{dimension: {names}} for one realm document — template or import artifact alike.

    Both sides are read by the SAME function on purpose. Two readers would drift, and the
    whole defect class this script exists for is two artifacts nobody compared.
    """
    roles = doc.get("roles", {}) or {}
    role_names = {r["name"] for r in (roles.get("realm") or [])}
    for client_roles in (roles.get("client", {}) or {}).values():
        role_names |= {r["name"] for r in client_roles or []}
    return {
        "roles": role_names - BUILTIN_ROLES - {f"default-roles-{doc.get('realm', '').lower()}"},
        "clients": {c["clientId"] for c in (doc.get("clients") or [])} - BUILTIN_CLIENTS,
        "users": {u["username"] for u in (doc.get("users") or [])},
    }


def template_names(root: pathlib.Path) -> dict:
    """{realm: {dimension: {names}}} from every *realm-template*.json in the repo."""
    out = {}
    for p in sorted(root.glob(REALM_GLOB)):
        doc = json.loads(p.read_text())
        realm = doc.get("realm")
        if not realm:
            raise SystemExit(f"{p}: no `realm` key — cannot tell which realm it describes")
        out[realm] = _names(doc)
    return out


def load_import(spec: str) -> tuple:
    """Parse a `realm=path` snapshot of the projected import artifact."""
    if "=" not in spec:
        raise SystemExit(f"--import expects realm=path, got {spec!r}")
    realm, _, path = spec.partition("=")
    raw = sys.stdin.read() if path == "-" else pathlib.Path(path).read_text()
    doc = json.loads(raw)
    if not isinstance(doc, dict) or not doc.get("realm"):
        raise SystemExit(f"{path}: not a realm document — treating as a failed capture")
    if doc["realm"] != realm:
        raise SystemExit(
            f"{path}: describes realm {doc['realm']!r}, supplied as {realm!r} — refusing to "
            f"compare a realm against another realm's template",
        )
    return realm, _names(doc)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument(
        "--import",
        dest="imports",
        action="append",
        default=[],
        metavar="REALM=PATH",
        help="projected import artifact for a realm; PATH may be - for stdin. Repeatable.",
    )
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)
    if args.self_test:
        return _self_test()

    root = pathlib.Path(args.root).resolve()
    declared = template_names(root)
    if not declared:
        sys.stderr.write(f"::error::no realm template found under {REALM_GLOB} — cannot run blind\n")
        return 1
    imported = dict(load_import(s) for s in args.imports)

    findings, report = [], {"realms": {}}
    for realm in sorted(set(declared) | set(imported)):
        want = declared.get(realm)
        have = imported.get(realm)
        if want is None:
            findings.append(
                f"realm `{realm}` has an import artifact but no template in git declares it — "
                f"a cold start would create a realm nobody reviewed.",
            )
            report["realms"][realm] = {"status": "undeclared-realm"}
            continue
        if have is None:
            findings.append(
                f"realm `{realm}` has a template in git but no import artifact was supplied — "
                f"UNCHECKED, not clean.",
            )
            report["realms"][realm] = {"status": "unchecked"}
            continue

        baseline = KNOWN_STALE.get(realm, {})
        entry = {"status": "checked", "inSync": True}
        for dim in DIMENSIONS:
            missing = want[dim] - have[dim]
            extra = have[dim] - want[dim]
            known = set(baseline.get(dim, set()))
            new_missing = sorted(missing - known)
            healed = sorted(known - missing)
            entry[f"declaredNotImported/{dim}"] = sorted(missing)
            entry[f"importedNotDeclared/{dim}"] = sorted(extra)
            entry[f"newSince3246/{dim}"] = new_missing
            if missing or extra:
                entry["inSync"] = False
            for n in new_missing:
                findings.append(
                    f"realm `{realm}`: {dim[:-1]} `{n}` is declared in the committed template "
                    f"and is NOT in the import artifact Keycloak would read. A cold-started "
                    f"cluster would not have it. This is NEW since #3246 — it is not in "
                    f"KNOWN_STALE. Reconcile the Vault copy "
                    f"(docs/runbooks/0009-keycloak-realm-import-reconcile.md).",
                )
            for n in sorted(extra):
                findings.append(
                    f"realm `{realm}`: {dim[:-1]} `{n}` is in the import artifact and declared "
                    f"NOWHERE in git. A cold start would create it and no review ever saw it. "
                    f"Add it to the template, or remove it from the Vault copy.",
                )
            for n in healed:
                findings.append(
                    f"realm `{realm}`: {dim[:-1]} `{n}` is listed in KNOWN_STALE but the import "
                    f"artifact now carries it — the #3246 reconcile has happened. Delete this "
                    f"entry from KNOWN_STALE in check-realm-import-parity.py; a baseline that "
                    f"outlives its gap is a gate that is green about nothing.",
                )
        report["realms"][realm] = entry

    print(json.dumps(report, indent=2, sort_keys=True))
    if findings:
        for f in findings:
            sys.stderr.write(f"::error title=Keycloak realm import parity::{f}\n")
        sys.stderr.write(
            f"::error::check-realm-import-parity: {len(findings)} finding(s). The committed "
            f"template and the artifact Keycloak would import disagree beyond the #3246 "
            f"baseline.\n",
        )
        return 1
    checked = ", ".join(sorted(imported))
    print(
        f"realm import parity: no drift beyond the #3246 baseline for {checked}.",
        file=sys.stderr,
    )
    return 0


# ---------------------------------------------------------------------------
# Self-test. Every case here was run against a DELIBERATELY broken input first — a checker
# that has only ever seen the correct file is unfalsified.
# ---------------------------------------------------------------------------

def _doc(realm, roles=(), clients=(), users=()):
    return {
        "realm": realm,
        "roles": {"realm": [{"name": r} for r in roles]},
        "clients": [{"clientId": c} for c in clients],
        "users": [{"username": u} for u in users],
    }


def _self_test() -> int:
    import tempfile

    failures = []

    def check(label, cond):
        if not cond:
            failures.append(label)

    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        comp = root / "openbank-infra" / "gitops" / "components" / "keycloak"
        comp.mkdir(parents=True)

        def run(tmpl, imp, baseline):
            (comp / "realm-template.json").write_text(json.dumps(tmpl))
            p = root / "imp.json"
            p.write_text(json.dumps(imp))
            saved = dict(KNOWN_STALE)
            KNOWN_STALE.clear()
            KNOWN_STALE.update(baseline)
            try:
                return main(["--root", str(root), "--import", f"{tmpl['realm']}={p}"])
            finally:
                KNOWN_STALE.clear()
                KNOWN_STALE.update(saved)

        empty = {"openbank": {d: set() for d in DIMENSIONS}}

        # 1. identical documents, empty baseline -> clean. The negative case: without it a
        #    checker that always reports drift would pass every other case here.
        check("identical sets must be clean",
              run(_doc("openbank", ["R"], ["c"], ["u"]),
                  _doc("openbank", ["R"], ["c"], ["u"]), empty) == 0)

        # 2. a role in the template and not the import artifact, unbaselined -> red.
        check("declared-not-imported role must be red",
              run(_doc("openbank", ["R", "S"]), _doc("openbank", ["R"]), empty) == 1)

        # 3. the same gap, baselined -> green. This is what keeps the first run off the alert.
        check("baselined gap must be green",
              run(_doc("openbank", ["R", "S"]), _doc("openbank", ["R"]),
                  {"openbank": {"roles": {"S"}, "clients": set(), "users": set()}}) == 0)

        # 4. a baseline entry whose gap has closed -> red. Without this the baseline would
        #    silently become permanent once the reconcile lands.
        check("stale baseline entry must be red",
              run(_doc("openbank", ["R", "S"]), _doc("openbank", ["R", "S"]),
                  {"openbank": {"roles": {"S"}, "clients": set(), "users": set()}}) == 1)

        # 5. a name in the import artifact that git does not declare -> red, never baselined.
        check("imported-not-declared must be red",
              run(_doc("openbank", ["R"]), _doc("openbank", ["R", "GHOST"]), empty) == 1)

        # 6. same for clients and users, since each dimension is compared separately and a
        #    loop that dropped one would still pass cases 1-5.
        check("client dimension must be compared",
              run(_doc("openbank", ["R"], ["a"]), _doc("openbank", ["R"], []), empty) == 1)
        check("user dimension must be compared",
              run(_doc("openbank", ["R"], [], ["u"]), _doc("openbank", ["R"], [], []), empty) == 1)

        # 7. built-ins on the live/import side must never register as drift.
        check("builtin clients must be ignored",
              run(_doc("openbank", ["R"], ["a"]),
                  _doc("openbank", ["R"], ["a", "account", "admin-cli"]), empty) == 0)
        check("builtin roles must be ignored",
              run(_doc("openbank", ["R"]),
                  _doc("openbank", ["R", "offline_access", "default-roles-openbank"]), empty) == 0)

        # 8. a realm supplied under the wrong name must abort, not compare.
        (comp / "realm-template.json").write_text(json.dumps(_doc("openbank", ["R"])))
        p = root / "other.json"
        p.write_text(json.dumps(_doc("openbank-customers", ["R"])))
        try:
            main(["--root", str(root), "--import", f"openbank={p}"])
            failures.append("mismatched realm name must abort")
        except SystemExit:
            pass

    for f in failures:
        sys.stderr.write(f"::error::self-test FAILED: {f}\n")
    if failures:
        return 1
    print("check-realm-import-parity self-test: all cases pass.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
