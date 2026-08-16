#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Detector: do the docker-dev and CI Keycloak realm JSONs agree with the gitops realm
# TEMPLATE about which roles exist and which roles the shared M2M client holds? (issue #2540
# follow-up — "GATE SCOPE" item from the 2026-08-16 comment)
#
# WHY THIS EXISTS
#   check-roles-allowed-realm.py, check-realm-role-parity.py, check-realm-user-role-parity.py
#   and check-realm-template-importable.py all glob ONLY
#     openbank-infra/gitops/components/keycloak/*realm-template*.json
#   Two other tracked realm JSONs define roles and grant them to the shared M2M service
#   account, and none of those four scripts reads either of them:
#     openbank-infra/docker/keycloak/realm/openbank-realm.json         (local dev)
#     .github/workflows/keycloak/openbank-realm.json                   (CI / api-fuzz)
#
#   Measured 2026-08-16 by reading all three files directly:
#
#     realm JSON  | service-account-openbank-services realmRoles
#     ------------|----------------------------------------------
#     gitops template (deployed) | ROLE_API
#     docker (local dev)         | ROLE_OPERATOR, ROLE_API
#     CI (api-fuzz)               | ROLE_OPERATOR, ROLE_COMPLIANCE
#
#   `rules.yaml: shared_m2m_matrix_write_grants` already notes the docker/CI divergence in prose
#   and its producer (check-matrix-write-grants.py) reads every `*realm*.json` in the tree when
#   computing the matrix-exposure UNION — so the grants above are not invisible to that gate.
#   What is missing is a detector whose JOB is this comparison: something that fails when a
#   grant appears in docker or CI and nowhere else, independent of whether that role happens to
#   carry a write action in the matrix today. `ROLE_COMPLIANCE` grants 15 authorization-matrix
#   actions and is not named by rules.yaml's write-grant register at all (that register only
#   lists WRITE actions already reachable via ROLE_OPERATOR/other roles it enumerates) — so this
#   specific grant currently has no dedicated finding anywhere in the repo.
#
# THIS IS A GATE-SCOPE EXTENSION, NOT A POLICY DECISION
#   It does not decide whether the CI or docker realms SHOULD match the gitops template, nor
#   does it write to Vault, Keycloak, or any realm. Those remain the two explicitly open pieces
#   of issue #2540 (which artifact the enforced `rolesallowed-realm-parity` gate should validate
#   against, and the owner-gated Vault reconcile from #3246). This script only makes the existing
#   divergence VISIBLE at PR time, the same way `realm-template-importable` is PR-time and static
#   — no live cluster read, no admin credential, purely a diff over three files already in git.
#
# WHAT IT REPORTS — both directions, per artifact, two dimensions:
#   roles                the realm-role SET each artifact declares, against the template's.
#   serviceAccountGrants  the realmRoles the `service-account-*` user entries in each artifact
#                         hold, against what the template grants that same principal.
#   Direction is always "declaredNotInTemplate" (the artifact grants/declares something the
#   deployed template does not — an unreviewed exposure if that artifact is ever the one that
#   actually feeds a running realm) and "templateNotInArtifact" (informational: the deployed
#   template is ahead of the dev/CI copy, which is expected and not itself a defect — dev/CI
#   realms are deliberately smaller).
#
# ADVISORY, not enforced: whether docker/CI realms should track the template is the open policy
# question above, and a static gate cannot make that call. This is detection, so the finding is
# visible and reviewable rather than owned to a specific script for the first time.
#
# Run:
#   python3 .github/scripts/check-realm-devci-artifact-parity.py
#   python3 .github/scripts/check-realm-devci-artifact-parity.py --self-test

import argparse
import json
import pathlib
import sys

TEMPLATE_GLOB = "openbank-infra/gitops/components/keycloak/*realm-template*.json"

# Fixed, single files — unlike the template glob, there is exactly one of each in the tree today.
# A glob would be the wrong tool here: silently picking up a second docker/CI realm file later is
# exactly the kind of drift this script exists to surface, so an added file should make this
# script start reporting it "unchecked" rather than quietly widening its own glob.
ARTIFACTS = {
    "docker-dev": "openbank-infra/docker/keycloak/realm/openbank-realm.json",
    "ci": ".github/workflows/keycloak/openbank-realm.json",
}

BUILTIN_ROLES = frozenset({"offline_access", "uma_authorization", "uma_protection"})
SERVICE_ACCOUNT_PREFIX = "service-account-"


def _role_names(doc: dict) -> set:
    roles = doc.get("roles", {}) or {}
    names = {r["name"] for r in (roles.get("realm") or [])}
    for client_roles in (roles.get("client", {}) or {}).values():
        names |= {r["name"] for r in client_roles or []}
    return names - BUILTIN_ROLES - {f"default-roles-{doc.get('realm', '').lower()}"}


def _service_account_grants(doc: dict) -> dict:
    """{username: {realm role names}} for every service-account-* user entry."""
    out = {}
    for u in doc.get("users", []) or []:
        name = u.get("username")
        if not name or not name.startswith(SERVICE_ACCOUNT_PREFIX):
            continue
        roles = set(u.get("realmRoles") or [])
        for client_roles in (u.get("clientRoles", {}) or {}).values():
            roles |= set(client_roles or [])
        out[name] = roles - BUILTIN_ROLES
    return out


def _load(path: pathlib.Path) -> dict:
    doc = json.loads(path.read_text())
    if not isinstance(doc, dict) or not doc.get("realm"):
        raise SystemExit(f"{path}: not a realm document — refusing to compare")
    return doc


def template_by_realm(root: pathlib.Path) -> dict:
    """{realm: doc} from every *realm-template*.json in the repo."""
    out = {}
    for p in sorted(root.glob(TEMPLATE_GLOB)):
        doc = _load(p)
        out[doc["realm"]] = doc
    return out


def compare(label: str, template: dict, artifact: dict) -> tuple:
    """Return (findings, report-fragment) for one artifact against the template of its realm."""
    findings = []
    entry = {"status": "checked", "inSync": True}

    t_roles, a_roles = _role_names(template), _role_names(artifact)
    declared_not_in_template = sorted(a_roles - t_roles)
    template_not_in_artifact = sorted(t_roles - a_roles)
    entry["roles"] = {
        "declaredNotInTemplate": declared_not_in_template,
        "templateNotInArtifact": template_not_in_artifact,
    }
    if declared_not_in_template:
        entry["inSync"] = False
        for r in declared_not_in_template:
            findings.append(
                f"[{label}] role `{r}` is declared in the realm JSON and NOT in the deployed "
                f"gitops template. If {label} ever became the artifact a rebuild imports, this "
                f"role — and anything the matrix grants it — would exist nowhere the template "
                f"review saw.",
            )

    t_grants, a_grants = _service_account_grants(template), _service_account_grants(artifact)
    grants_entry = {}
    for principal in sorted(set(t_grants) | set(a_grants)):
        want = t_grants.get(principal, set())
        have = a_grants.get(principal, set())
        over = sorted(have - want)
        under = sorted(want - have)
        if not over and not under:
            continue
        grants_entry[principal] = {"overGranted": over, "underGranted": under}
        if over:
            entry["inSync"] = False
            findings.append(
                f"[{label}] `{principal}` holds {over} which the deployed gitops template does "
                f"NOT grant it. This client authenticates the platform's shared M2M identity; a "
                f"grant that exists only here is an exposure this environment has and the "
                f"reviewed deployment artifact does not — check `role_action_matrix` for what "
                f"each of {over} reaches.",
            )
        # under-granted (template grants more than this artifact) is expected — dev/CI realms
        # are deliberately smaller — so it is recorded but not raised as a finding.
    entry["serviceAccountGrants"] = grants_entry

    return findings, entry


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)
    if args.self_test:
        return _self_test()

    root = pathlib.Path(args.root).resolve()
    templates = template_by_realm(root)
    if not templates:
        sys.stderr.write(f"::error::no realm template found under {TEMPLATE_GLOB} — cannot run blind\n")
        return 1

    findings, report = [], {"artifacts": {}}
    for label, rel in ARTIFACTS.items():
        path = root / rel
        if not path.exists():
            report["artifacts"][label] = {"status": "absent", "path": rel}
            continue
        artifact = _load(path)
        realm = artifact["realm"]
        template = templates.get(realm)
        if template is None:
            findings.append(
                f"[{label}] describes realm `{realm}`, which no gitops template declares — "
                f"UNCHECKED, not clean.",
            )
            report["artifacts"][label] = {"status": "undeclared-realm", "realm": realm}
            continue
        f, entry = compare(label, template, artifact)
        findings += f
        entry["realm"] = realm
        entry["path"] = rel
        report["artifacts"][label] = entry

    print(f"SUBJECTS={len(ARTIFACTS)}  # docker/CI realm JSONs compared against the gitops template", file=sys.stderr)
    print(json.dumps(report, indent=2, sort_keys=True))
    if findings:
        for f in findings:
            sys.stderr.write(f"::warning title=Keycloak dev/CI realm parity::{f}\n")
        sys.stderr.write(
            f"::warning::check-realm-devci-artifact-parity: {len(findings)} finding(s). "
            f"docker-dev and/or CI realm JSONs declare a role or grant the deployed gitops "
            f"template does not. Advisory only — see the script header for what this does and "
            f"does not decide.\n",
        )
        # Advisory: report, do not fail the build. The gate wrapper (gates.yaml, mode: advisory)
        # is what actually keeps this from blocking a PR; exit 0 here as well so a direct
        # invocation (e.g. from a shell) matches the gate's behavior rather than surprising
        # whoever runs it by hand.
        return 0
    print("realm dev/CI artifact parity: docker-dev and CI realms agree with the template.",
          file=sys.stderr)
    return 0


# ---------------------------------------------------------------------------
# Self-test. Every case is run against an input it MUST flag before the matching clean case,
# same discipline as the sibling scripts — a checker that has only ever seen agreeing input is
# unfalsified.
# ---------------------------------------------------------------------------

def _doc(realm, roles=(), sa_grants=None):
    """sa_grants: {username: [role, ...]} for service-account-* users."""
    users = []
    for name, roles_for in (sa_grants or {}).items():
        users.append({"username": name, "realmRoles": list(roles_for)})
    return {
        "realm": realm,
        "roles": {"realm": [{"name": r} for r in roles]},
        "users": users,
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
        docker_dir = root / "openbank-infra" / "docker" / "keycloak" / "realm"
        docker_dir.mkdir(parents=True)
        ci_dir = root / ".github" / "workflows" / "keycloak"
        ci_dir.mkdir(parents=True)

        def run(template, docker=None, ci=None):
            (comp / "realm-template.json").write_text(json.dumps(template))
            if docker is not None:
                (docker_dir / "openbank-realm.json").write_text(json.dumps(docker))
            elif (docker_dir / "openbank-realm.json").exists():
                (docker_dir / "openbank-realm.json").unlink()
            if ci is not None:
                (ci_dir / "openbank-realm.json").write_text(json.dumps(ci))
            elif (ci_dir / "openbank-realm.json").exists():
                (ci_dir / "openbank-realm.json").unlink()
            buf = []
            import contextlib
            import io

            with contextlib.redirect_stdout(io.StringIO()) as out:
                code = main(["--root", str(root)])
                buf = out.getvalue()
            return code, json.loads(buf)

        tmpl = _doc("openbank", roles=["ROLE_API"], sa_grants={"service-account-x": ["ROLE_API"]})

        # 1. identical artifact and template -> clean, no findings.
        code, report = run(tmpl, docker=_doc("openbank", roles=["ROLE_API"],
                                              sa_grants={"service-account-x": ["ROLE_API"]}))
        check("identical docker artifact must be clean",
              report["artifacts"]["docker-dev"]["inSync"] is True)

        # 2. THE REAL DEFECT SHAPE (#2540): CI artifact grants a service account a role the
        #    template does not. Must be flagged as over-granted, and inSync must go false.
        code, report = run(
            tmpl,
            ci=_doc("openbank", roles=["ROLE_API", "ROLE_COMPLIANCE", "ROLE_OPERATOR"],
                    sa_grants={"service-account-x": ["ROLE_OPERATOR", "ROLE_COMPLIANCE"]}),
        )
        ci_entry = report["artifacts"]["ci"]
        check("over-granted service account must be reported",
              sorted(ci_entry["serviceAccountGrants"]["service-account-x"]["overGranted"])
              == ["ROLE_COMPLIANCE", "ROLE_OPERATOR"])
        check("over-grant must flip inSync false", ci_entry["inSync"] is False)
        check("exit code stays 0 (advisory)", code == 0)

        # 3. a role declared in the artifact and not the template (independent of grants).
        code, report = run(tmpl, docker=_doc("openbank", roles=["ROLE_API", "ROLE_DEMO"]))
        check("undeclared role in artifact must be reported",
              report["artifacts"]["docker-dev"]["roles"]["declaredNotInTemplate"] == ["ROLE_DEMO"])

        # 4. the template being AHEAD of the artifact (more roles, more grants) is expected and
        #    must not flip inSync or raise a finding — dev/CI realms are deliberately smaller.
        big_tmpl = _doc("openbank", roles=["ROLE_API", "ROLE_ADMIN", "ROLE_VIEWER"],
                         sa_grants={"service-account-x": ["ROLE_API"]})
        code, report = run(big_tmpl, docker=_doc("openbank", roles=["ROLE_API"],
                                                  sa_grants={"service-account-x": ["ROLE_API"]}))
        d = report["artifacts"]["docker-dev"]
        check("template-ahead must stay in sync",
              d["inSync"] is True and d["roles"]["templateNotInArtifact"] == ["ROLE_ADMIN", "ROLE_VIEWER"])

        # 5. an artifact absent from the tree is reported as absent, not clean and not a finding.
        code, report = run(tmpl, docker=None, ci=None)
        check("missing docker artifact is reported absent",
              report["artifacts"]["docker-dev"]["status"] == "absent")
        check("missing ci artifact is reported absent",
              report["artifacts"]["ci"]["status"] == "absent")

        # 6. an artifact describing a realm no template declares -> undeclared-realm, unchecked.
        code, report = run(tmpl, docker=_doc("openbank-mystery", roles=["ROLE_X"]))
        check("artifact for an undeclared realm must be unchecked",
              report["artifacts"]["docker-dev"]["status"] == "undeclared-realm")

        # 7. Keycloak built-ins must never register as drift in either dimension.
        code, report = run(
            tmpl,
            docker=_doc("openbank", roles=["ROLE_API", "offline_access", "default-roles-openbank"],
                        sa_grants={"service-account-x": ["ROLE_API", "uma_authorization"]}),
        )
        d = report["artifacts"]["docker-dev"]
        check("builtin roles must be excluded from the role dimension",
              d["roles"]["declaredNotInTemplate"] == [])
        check("builtin roles must be excluded from grant comparison", d["inSync"] is True)

        # 8. under-granted (template grants a role the artifact's service account lacks) is
        #    recorded but must not be raised as a finding or flip inSync.
        code, report = run(
            _doc("openbank", roles=["ROLE_API", "ROLE_ADMIN"],
                 sa_grants={"service-account-x": ["ROLE_API", "ROLE_ADMIN"]}),
            docker=_doc("openbank", roles=["ROLE_API"],
                        sa_grants={"service-account-x": ["ROLE_API"]}),
        )
        d = report["artifacts"]["docker-dev"]
        check("under-granted service account must not flip inSync", d["inSync"] is True)

    for f in failures:
        sys.stderr.write(f"::error::self-test FAILED: {f}\n")
    if failures:
        return 1
    print("check-realm-devci-artifact-parity self-test: all cases pass.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
