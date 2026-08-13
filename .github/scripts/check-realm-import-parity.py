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
#   So the gap is baselined, the same shape check-kafka-dotted-keys and check-pact-provider-replay
#   use. WHAT is baselined changed on 2026-08-13, and the reason is the whole point of this block.
#
#   The first version (#3655) baselined the DIFFERENCE — the set of names the template declared
#   and the artifact lacked. A difference is a function of BOTH sides, and only one of them is
#   frozen. So every legitimate addition to the template landed as `missing`, was absent from the
#   baseline, and was reported as "NEW since #3246" — new drift, on a run where nothing about the
#   drift had changed. #4028 (2026-08-07) did exactly that: a correct, additive, template-only PR
#   declaring `service-account-openbank-mcp-service`, which this thread had asked for. The CronJob
#   has been red every night since, on a finding whose only remedy was to hand-append the name
#   here — i.e. the detector fired on the wrong event, and the response it trained was "append a
#   name to silence a red", which is how a baseline rots.
#
#   So the baseline is now IMPORT_BASELINE: the names the import artifact ACTUALLY CARRIES, the
#   side that is frozen. The gap itself (`declaredNotImported`) is reported, always, and is not a
#   finding while the artifact matches its baseline — it is #3246's one known defect, and it grows
#   with every template merge by construction. What IS a finding is the artifact moving:
#
#     * names GAINED  -> a Vault write happened. Either the #3246 reconcile (empty this realm's
#                        entry; parity is then enforced in full) or an out-of-band write nobody
#                        reviewed. Both need a human, and neither was distinguishable before.
#     * names LOST    -> a Vault write REMOVED something the artifact used to carry. This is the
#                        direction that makes a rebuild worse, and the old shape could not see it
#                        at all: shrinking the artifact only ever GREW the difference, which read
#                        as ordinary template drift.
#     * a realm with NO baseline entry -> full parity required, no exceptions. That is the
#                        post-reconcile steady state the runbook's close-out PR creates.
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
# What the deployed import artifact CARRIES — not what it lacks. Measured 2026-08-13 by decoding
# the two projected Secrets, i.e. the exact bytes keycloak.yaml's `realm-import` volume mounts:
#
#   kubectl -n iam get secret keycloak-realm-import           -o jsonpath='{.data}'   (1951 B)
#   kubectl -n iam get secret keycloak-customers-realm-import -o jsonpath='{.data}'   (6333 B)
#
# Byte-identical to the artifact #3246 measured on 2026-08-02, so this records a frozen object,
# which is the property that makes it a usable baseline. Every name here is also declared by the
# committed template — the artifact is a strict ANCESTOR — and check_realm_import_parity_test.py
# asserts that, so an entry cannot drift into naming something git never reviewed.
#
# A realm listed here is in the #3246 gap: its `declaredNotImported` set is expected and is not a
# finding. A realm NOT listed here is held to full parity. The reconcile in
# docs/runbooks/0009-keycloak-realm-import-reconcile.md is what moves a realm from the first state
# to the second: after the Vault write the artifact no longer matches this record, the run goes red
# saying so, and the close-out PR DELETES that realm's entry rather than emptying its dimensions.
# (An entry with empty sets is not the same statement — it would assert an empty artifact.)
# ---------------------------------------------------------------------------
IMPORT_BASELINE = {
    "openbank": {
        "roles": {"ROLE_ADMIN", "ROLE_API", "ROLE_OPERATOR", "ROLE_VIEWER"},
        "clients": {"openbank-admin-ui", "openbank-services"},
        "users": {"admin@openbank.local"},
    },
    "openbank-customers": {
        # `defaultRoles` (the flat Keycloak <=12 spelling this artifact still uses) is not a role
        # DECLARATION and _names does not read it; ROLE_CUSTOMER is declared under roles.realm.
        "roles": {"ROLE_CUSTOMER"},
        "clients": {"openbank-app"},
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

        baseline = IMPORT_BASELINE.get(realm)
        entry = {"status": "checked", "inSync": True}
        entry["importArtifactBaselined"] = baseline is not None
        for dim in DIMENSIONS:
            missing = want[dim] - have[dim]
            extra = have[dim] - want[dim]
            entry[f"declaredNotImported/{dim}"] = sorted(missing)
            entry[f"importedNotDeclared/{dim}"] = sorted(extra)
            if missing or extra:
                entry["inSync"] = False

            # A name the artifact carries and git declares nowhere. Never baselined, in either
            # shape: a cold start would create it and no review ever saw it.
            for n in sorted(extra):
                findings.append(
                    f"realm `{realm}`: {dim[:-1]} `{n}` is in the import artifact and declared "
                    f"NOWHERE in git. A cold start would create it and no review ever saw it. "
                    f"Add it to the template, or remove it from the Vault copy.",
                )

            if baseline is None:
                # No recorded gap for this realm: full parity, which is the post-reconcile state.
                entry[f"importArtifactGained/{dim}"] = []
                entry[f"importArtifactLost/{dim}"] = []
                for n in sorted(missing):
                    findings.append(
                        f"realm `{realm}`: {dim[:-1]} `{n}` is declared in the committed template "
                        f"and is NOT in the import artifact Keycloak would read. A cold-started "
                        f"cluster would not have it, and this realm has no #3246 baseline entry, "
                        f"so it is held to full parity. Reconcile the Vault copy "
                        f"(docs/runbooks/0009-keycloak-realm-import-reconcile.md).",
                    )
                continue

            # Baselined realm: the gap is #3246's known defect and grows with every template
            # merge, so it is reported and not raised. What is raised is the ARTIFACT moving.
            base = set(baseline.get(dim, set()))
            gained = sorted(have[dim] - base)
            lost = sorted(base - have[dim])
            entry[f"importArtifactGained/{dim}"] = gained
            entry[f"importArtifactLost/{dim}"] = lost
            for n in gained:
                if n in extra:
                    continue  # already reported above, and more precisely
                findings.append(
                    f"realm `{realm}`: the import artifact now carries {dim[:-1]} `{n}`, which "
                    f"the baseline recorded it did not — something has written to the Vault "
                    f"copy. If this is the #3246 reconcile, delete this realm's entry from "
                    f"IMPORT_BASELINE so parity is enforced in full "
                    f"(docs/runbooks/0009-keycloak-realm-import-reconcile.md). If it is not, "
                    f"an unreviewed write reached the artifact a rebuild would import.",
                )
            for n in lost:
                findings.append(
                    f"realm `{realm}`: the import artifact NO LONGER carries {dim[:-1]} `{n}`, "
                    f"which the baseline recorded it did. A write to the Vault copy removed it, "
                    f"so a cold-started cluster now reproduces strictly less than it did before.",
                )
        report["realms"][realm] = entry

    print(json.dumps(report, indent=2, sort_keys=True))
    if findings:
        for f in findings:
            sys.stderr.write(f"::error title=Keycloak realm import parity::{f}\n")
        sys.stderr.write(
            f"::error::check-realm-import-parity: {len(findings)} finding(s). The artifact "
            f"Keycloak would import has moved away from its recorded baseline, or carries a "
            f"name git declares nowhere.\n",
        )
        return 1
    checked = ", ".join(sorted(imported))
    print(
        f"realm import parity: the import artifact matches its recorded baseline for {checked}; "
        f"the #3246 gap itself is unchanged (see declaredNotImported/* in the report).",
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
            """baseline=None models a realm with no IMPORT_BASELINE entry (full parity)."""
            (comp / "realm-template.json").write_text(json.dumps(tmpl))
            p = root / "imp.json"
            p.write_text(json.dumps(imp))
            saved = dict(IMPORT_BASELINE)
            IMPORT_BASELINE.clear()
            if baseline is not None:
                IMPORT_BASELINE.update(baseline)
            try:
                return main(["--root", str(root), "--import", f"{tmpl['realm']}={p}"])
            finally:
                IMPORT_BASELINE.clear()
                IMPORT_BASELINE.update(saved)

        def base(roles=(), clients=(), users=()):
            return {"openbank": {"roles": set(roles), "clients": set(clients),
                                 "users": set(users)}}

        # 1. identical documents, no baseline -> clean. The negative case: without it a checker
        #    that always reports drift would pass every other case here.
        check("identical sets must be clean",
              run(_doc("openbank", ["R"], ["c"], ["u"]),
                  _doc("openbank", ["R"], ["c"], ["u"]), None) == 0)

        # 2. an unbaselined realm is held to full parity — a role the artifact lacks is red.
        check("declared-not-imported role must be red without a baseline",
              run(_doc("openbank", ["R", "S"]), _doc("openbank", ["R"]), None) == 1)

        # 3. the same gap, with the artifact matching its baseline -> green. This is what keeps
        #    the first run off the alert while #3246's Vault write is outstanding.
        check("baselined gap must be green",
              run(_doc("openbank", ["R", "S"]), _doc("openbank", ["R"]), base(roles=["R"])) == 0)

        # 4. THE REGRESSION THIS SHAPE EXISTS FOR (#4028): the template gains a name while the
        #    artifact is unchanged. That is the same one gap, one entry larger — it must NOT be
        #    reported as new drift. Under the old difference-keyed baseline this returned 1, and
        #    the CronJob was red nightly from 2026-08-07 because of it.
        check("template growth against an unchanged artifact must stay green",
              run(_doc("openbank", ["R", "S", "T"]), _doc("openbank", ["R"]),
                  base(roles=["R"])) == 0)

        # 5. the artifact GAINS a template-declared name -> red. This is the reconcile landing
        #    (or an unreviewed Vault write); either way the baseline must not outlive it.
        check("artifact gaining a name must be red",
              run(_doc("openbank", ["R", "S"]), _doc("openbank", ["R", "S"]),
                  base(roles=["R"])) == 1)

        # 6. the artifact LOSES a name it used to carry -> red. The old shape could not see this
        #    at all: a shrinking artifact only ever grew the difference, which read as template
        #    drift and was silenced by baselining the name.
        check("artifact losing a name must be red",
              run(_doc("openbank", ["R", "S"]), _doc("openbank", []),
                  base(roles=["R"])) == 1)

        # 7. a name in the import artifact that git does not declare -> red, never baselined.
        check("imported-not-declared must be red",
              run(_doc("openbank", ["R"]), _doc("openbank", ["R", "GHOST"]), None) == 1)

        # 8. same for clients and users, since each dimension is compared separately and a
        #    loop that dropped one would still pass the cases above.
        check("client dimension must be compared",
              run(_doc("openbank", ["R"], ["a"]), _doc("openbank", ["R"], []), None) == 1)
        check("user dimension must be compared",
              run(_doc("openbank", ["R"], [], ["u"]), _doc("openbank", ["R"], [], []), None) == 1)
        check("client dimension must be compared against the baseline",
              run(_doc("openbank", ["R"], ["a"]), _doc("openbank", ["R"], ["a"]),
                  base(roles=["R"], clients=[])) == 1)

        # 9. built-ins on the live/import side must never register as drift.
        check("builtin clients must be ignored",
              run(_doc("openbank", ["R"], ["a"]),
                  _doc("openbank", ["R"], ["a", "account", "admin-cli"]), None) == 0)
        check("builtin roles must be ignored",
              run(_doc("openbank", ["R"]),
                  _doc("openbank", ["R", "offline_access", "default-roles-openbank"]), None) == 0)

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
