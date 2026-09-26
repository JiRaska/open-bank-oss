#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Detector: do the users in the LIVE Keycloak realm hold the roles the realm template
# assigns them — and only those? (issue #2540 follow-up)
#
# WHY THIS EXISTS
#   check-realm-role-parity.py compares role EXISTENCE, template vs live, and says so itself:
#   its published report carries
#       "doesNotVerify": "role ASSIGNMENTS to users/service accounts (#2540 follow-up)"
#   That is this script. The gap matters because the two drifts fail in opposite directions and
#   only one of them is visible:
#
#     - a MISSING role is loud downstream. Nothing can mint it, so a caller is denied and someone
#       eventually notices.
#     - a MISPLACED role is silent by construction. Every role involved exists in both the
#       template and the live realm, so the existence check is green; what changed is only WHO
#       holds it. Nothing 403s. The account simply does more than the repo says it may.
#
#   The second direction is the dangerous one, and its worst case is specific: an account whose
#   credentials are deliberately published (a demo or guided-tour login) accumulating roles the
#   template never granted it. That is not a hypothetical shape — every realm here ships such an
#   account on purpose, and `--import-realm` runs on COLD START ONLY, so any grant made after the
#   realm first came up lives outside git forever with ArgoCD reporting Synced/Healthy throughout.
#
#   It also breaks separation of duties without touching a single role definition. A four-eyes
#   control needs two DIFFERENT principals; if one identity quietly accumulates both halves'
#   roles, the control still exists, still passes every gate, and can be satisfied by one person.
#
# WHY IT IS NOT A PR-TIME GATE
#   Same reason as its sibling: the live realm is unreachable from a PR runner (admin API blocked
#   at the edge, no workflow holds cluster access, the credential is out-of-band). So this is a
#   pure function over two snapshots — the templates in the repo, and user role mappings captured
#   in-cluster by the keycloak-realm-drift CronJob — which keeps the COMPARISON as ordinary,
#   reviewable, self-tested repo code.
#
# WHAT IT REPORTS — three kinds, because they are different defects:
#   over-granted    live holds a role the template does not assign that user. The silent
#                   direction above. Severity is raised for accounts flagged `published` (see
#                   PUBLISHED_CREDENTIAL_USERS): those credentials are handed out on purpose, so
#                   an extra role there is an extra role for everybody.
#   under-granted   the template assigns a role the live user does not hold. The grant never
#                   applied; anything relying on it degrades to whatever else the caller has.
#   undeclared-user a live user no template describes. It works today and a cold-started realm
#                   loses it — the same disaster-recovery landmine the sibling names.
#
# Keycloak's own built-ins are excluded by name; they are server-managed, appear in no template,
# and a detector that always fires is a detector nobody reads.
#
# Run:
#   python3 .github/scripts/check-realm-user-role-parity.py \
#       --live openbank=/tmp/openbank-user-roles.json
#   where the file is {"user@example": ["ROLE_A", ...]} or [{"username": ..., "realmRoles": [...]}]
#
#   python3 .github/scripts/check-realm-user-role-parity.py --self-test
#
# A realm with no --live snapshot is reported as unchecked, never as clean.

import argparse
import json
import pathlib
import sys

REALM_GLOB = "openbank-infra/gitops/components/keycloak/*realm-template*.json"

# Accounts whose credentials are published on purpose. An extra role on one of these is an extra
# role for anyone who reads the docs, so it is reported at higher severity than ordinary drift.
# Matched on the username as the template spells it.
PUBLISHED_CREDENTIAL_USERS = {"demo@openbank.local"}

# Keycloak returns service-account principals from /clients/<id>/service-account-user,
# never from /users — so a snapshot that did not ask for them simply has none.
SERVICE_ACCOUNT_PREFIX = "service-account-"

# Realm roles that make an M2M identity an operator. `rest.rego`'s `matrix-allows` turns every
# role_action_matrix entry into a permit for any HUMAN holding the role, and Keycloak
# service-accounts are classified HUMAN — so one of these on a service account is a fleet-wide
# grant to a machine. #10486: `service-account-openbank-services` held ROLE_OPERATOR live while
# the template grants only ROLE_API, and this script reported it as a ::warning:: that left the
# job green, so the drift detector read as parity. A privileged grant the template does not make
# is now an ::error:: (job fails -> KeycloakRealmDrift), for declared AND undeclared accounts.
PRIVILEGED_REALM_ROLES = {"ROLE_OPERATOR", "ROLE_ADMIN"}

# Client roles are namespaced `<clientId>/<role>` on both sides so a client role can never
# collide with a realm role of the same name.
CLIENT_ROLE_SEP = "/"

# Marker put into a principal's live set when its capture included client-role mappings.
CLIENT_CAPTURED = "\x00client-roles-captured"


def keycloak_builtins(realm: str) -> set:
    """Roles the server assigns itself. Never in a template; excluded from both sides."""
    return {
        "offline_access",
        "uma_authorization",
        "uma_protection",
        f"default-roles-{realm.lower()}",
    }


def template_users(root: pathlib.Path) -> tuple:
    """({realm: {username: {roles}}}, {realms that allow self-registration}).

    The registration flag matters: in a realm where anyone may sign up, a live user the template
    does not name is the PRODUCT WORKING, not drift. Reporting each retail customer would bury the
    one finding that matters under dozens that never will — the failure the sibling script calls
    out as "a detector that always fires is a detector nobody reads".
    """
    out, open_registration = {}, set()
    for p in sorted(root.glob(REALM_GLOB)):
        doc = json.loads(p.read_text())
        realm = doc.get("realm")
        if not realm:
            raise SystemExit(f"{p}: no `realm` key — cannot tell which realm it describes")
        if doc.get("registrationAllowed"):
            open_registration.add(realm)
        users = {}
        for u in doc.get("users", []) or []:
            name = u.get("username")
            if not name:
                raise SystemExit(f"{p}: a user entry has no `username`")
            roles = set(u.get("realmRoles") or [])
            for client, client_roles in (u.get("clientRoles", {}) or {}).items():
                roles |= {f"{client}{CLIENT_ROLE_SEP}{r}" for r in client_roles or []}
            users[name] = roles
        out.setdefault(realm, {}).update(users)
    return out, open_registration


def load_live(spec: str) -> tuple:
    """Parse a `realm=path` snapshot of user role mappings."""
    if "=" not in spec:
        raise SystemExit(f"--live expects realm=path, got {spec!r}")
    realm, _, path = spec.partition("=")
    raw = sys.stdin.read() if path == "-" else pathlib.Path(path).read_text()
    doc = json.loads(raw)
    users = {}
    if isinstance(doc, dict):
        for name, roles in doc.items():
            if isinstance(roles, dict):
                # Structured capture (#10486): {"realmRoles": [...], "clientRoles": {client: [...]}}.
                # Only this shape carries client-role mappings; the flat list shape means the
                # capture did not look at them, and the comparison then leaves them unchecked.
                s = {r if isinstance(r, str) else r.get("name") for r in roles.get("realmRoles") or []}
                for client, crs in (roles.get("clientRoles") or {}).items():
                    s |= {f"{client}{CLIENT_ROLE_SEP}{r if isinstance(r, str) else r.get('name')}"
                          for r in crs or []}
                s.add(CLIENT_CAPTURED)
                users[name] = s
                continue
            users[name] = {r if isinstance(r, str) else r.get("name") for r in roles or []}
    elif isinstance(doc, list):
        for entry in doc:
            if not isinstance(entry, dict) or not entry.get("username"):
                raise SystemExit(f"{path}: unrecognised user entry {entry!r}")
            roles = entry.get("realmRoles") or entry.get("roles") or []
            users[entry["username"]] = {r if isinstance(r, str) else r.get("name") for r in roles}
    else:
        raise SystemExit(f"{path}: expected an object or a list, got {type(doc).__name__}")
    if not users:
        # An empty snapshot is a capture failure (bad credential, wrong realm), not a realm with
        # no users — every realm here ships at least an admin. Refusing to treat it as data is
        # what stops this printing "everything is missing" on an auth error.
        raise SystemExit(f"{path}: no users parsed — treating as a failed capture, not an empty realm")
    users = {k: {r for r in v if r} for k, v in users.items()}
    return realm, users


def compare(realm: str, declared: dict, live: dict, open_registration: bool = False) -> tuple:
    """Return (findings, report-fragment) for one realm. Pure — the whole point of the split."""
    builtins = keycloak_builtins(realm)
    findings, over, under, undeclared, unchecked = [], {}, {}, [], []
    # Does this snapshot cover service accounts at all? Derived from the snapshot itself rather
    # than assumed, so an older capture degrades to "unchecked" instead of to a false finding.
    saw_service_accounts = any(u.startswith(SERVICE_ACCOUNT_PREFIX) for u in live)

    privileged = []
    for user in sorted(set(declared) | set(live)):
        want = (declared.get(user) or set()) - builtins
        have = live.get(user)
        if have is not None:
            if CLIENT_CAPTURED not in have:
                # Client mappings were not captured for this principal: compare realm roles only
                # rather than report every template client role as a missing grant.
                want = {r for r in want if CLIENT_ROLE_SEP not in r}
            have = have - {CLIENT_CAPTURED}
        if have is None:
            # Declared but absent live. The sibling reports missing ROLES; a missing USER is the
            # same class and equally invisible until someone tries to log in as them.
            #
            # EXCEPT for service accounts: Keycloak does not return them from /users, so their
            # absence from a snapshot means the CAPTURE did not look, not that the principal is
            # gone. Reporting it anyway would be a detector inventing a defect out of its own
            # blind spot — worse than silence, because it trains the reader to skip the output.
            if user.startswith(SERVICE_ACCOUNT_PREFIX) and not saw_service_accounts:
                unchecked.append(user)
                continue
            findings.append(
                f"::warning::[{realm}] user `{user}` is declared in the realm template but does "
                f"not exist in the live realm — its grants {sorted(want)} never applied",
            )
            under[user] = sorted(want)
            continue
        have = have - builtins
        is_sa = user.startswith(SERVICE_ACCOUNT_PREFIX)
        bad = sorted((have - want) & PRIVILEGED_REALM_ROLES) if is_sa else []
        if bad:
            privileged.append(user)
            findings.append(
                f"::error::[{realm}] service account `{user}` holds {bad} which the realm "
                f"template {'does not grant it' if user in declared else 'does not declare it at all'}"
                f" (template: {sorted(want)}). A service account is classified HUMAN by the authz "
                f"interceptor, so every role_action_matrix entry for {bad} is a grant to this "
                f"machine identity — remove the live mapping or declare it in the template",
            )
        if user not in declared:
            undeclared.append(user)
            # In a self-registration realm this is the product working. Still counted in the
            # report (so the number is visible) but not raised as a finding.
            if not open_registration and not user.startswith(SERVICE_ACCOUNT_PREFIX):
                findings.append(
                    f"::warning::[{realm}] user `{user}` exists live but no realm template "
                    f"declares it — a cold-started realm loses it, and its roles {sorted(have)} "
                    f"are outside git",
                )
            continue
        extra, missing = sorted(have - want), sorted(want - have)
        if extra:
            over[user] = extra
            if bad and not set(extra) - set(bad):
                pass  # already reported above as ::error::
            elif user in PUBLISHED_CREDENTIAL_USERS:
                findings.append(
                    f"::error::[{realm}] user `{user}` has PUBLISHED credentials and holds "
                    f"{extra} which the realm template does not grant it — anyone holding those "
                    f"credentials holds those roles. Template grants: {sorted(want)}",
                )
            else:
                findings.append(
                    f"::warning::[{realm}] user `{user}` holds {extra} which the realm template "
                    f"does not grant it (template: {sorted(want)})",
                )
        if missing:
            under[user] = missing
            findings.append(
                f"::warning::[{realm}] user `{user}` is missing {missing} which the realm "
                f"template grants it — that grant never applied",
            )

    return findings, {
        "status": "checked",
        "overGranted": over,
        "underGranted": under,
        "undeclaredUsers": sorted(undeclared),
        "undeclaredUsersReported": not open_registration,
        "uncheckedPrincipals": sorted(unchecked),
        "privilegedServiceAccountDrift": privileged,
    }


def rego_service_account_clients(root: pathlib.Path) -> set:
    """Clients whose service account a non-test policy names (`service-account-<client>`)."""
    import re

    pat = re.compile(r"service-account-([a-z0-9][a-z0-9-]*[a-z0-9])")
    out = set()
    for p in root.rglob("*.rego"):
        if p.name.endswith("_test.rego") or {".git", "node_modules", "build"} & set(p.parts):
            continue
        out |= set(pat.findall(p.read_text()))
    return out


def compare_client_scopes(realm: str, referenced: set, live_scopes: dict) -> tuple:
    """LIVE default client scopes of every rego-referenced client must include `profile`.

    #10486: principal.id reaches the policy from `preferred_username`, which a client_credentials
    token carries only through the `profile` scope. The template is held to this statically
    (check-rego-service-account-profile-scope.py); this is the same rule against the RUNNING
    realm, which `--import-realm` stopped feeding the day it first came up.
    """
    findings, missing = [], []
    for client in sorted(referenced & set(live_scopes)):
        if "profile" not in live_scopes[client]:
            missing.append(client)
            findings.append(
                f"::error::[{realm}] client `{client}` has no `profile` default scope in the live "
                f"realm ({sorted(live_scopes[client])}) — its tokens carry no preferred_username, "
                f"so every rego rule keyed on `service-account-{client}` never matches",
            )
    return findings, {"checkedClients": sorted(referenced & set(live_scopes)),
                      "missingProfileScope": missing}


def self_test() -> int:
    """Feed the comparison inputs it MUST flag, and one it must NOT.

    A detector that has only ever seen agreeing snapshots is unfalsified, and this one's whole
    value is a red on the silent direction — so the over-grant case, the published-credential
    escalation, and the clean case are all exercised here rather than assumed.
    """
    failures = []

    def check(name, cond):
        if not cond:
            failures.append(name)

    # 1. identical sets are clean, and built-ins on the live side are not a finding
    f, r = compare(
        "openbank",
        {"admin@x": {"ROLE_ADMIN"}},
        {"admin@x": {"ROLE_ADMIN", "offline_access", "default-roles-openbank"}},
    )
    check("identical sets must be clean", f == [] and r["overGranted"] == {})

    # 2. an extra live role is an over-grant
    f, r = compare("openbank", {"ops@x": {"ROLE_VIEWER"}}, {"ops@x": {"ROLE_VIEWER", "ROLE_ADMIN"}})
    check("extra live role must be reported", r["overGranted"] == {"ops@x": ["ROLE_ADMIN"]})
    check("ordinary over-grant is a warning", any("::warning::" in x for x in f))

    # 3. the same drift on a published-credential account escalates to error
    f, r = compare(
        "openbank",
        {"demo@openbank.local": {"ROLE_VIEWER"}},
        {"demo@openbank.local": {"ROLE_VIEWER", "ROLE_ADMIN"}},
    )
    check("published-credential over-grant must be an error", any("::error::" in x for x in f))
    check("published-credential error names the role", any("ROLE_ADMIN" in x for x in f))

    # 4. a declared grant that never applied
    f, r = compare("openbank", {"a@x": {"ROLE_KYC"}}, {"a@x": set()})
    check("missing live role must be reported", r["underGranted"] == {"a@x": ["ROLE_KYC"]})

    # 5. a live user nothing declares
    f, r = compare("openbank", {}, {"ghost@x": {"ROLE_ADMIN"}})
    check("undeclared live user must be reported", r["undeclaredUsers"] == ["ghost@x"])
    check("undeclared live user is a finding in a closed realm", any("ghost@x" in x for x in f))

    # 5b. the same user in a SELF-REGISTRATION realm is the product working, not drift
    f, r = compare("openbank-customers", {}, {"someone@x": {"ROLE_CUSTOMER"}}, open_registration=True)
    check("self-registered user must not be a finding", f == [])
    check("…but is still counted", r["undeclaredUsers"] == ["someone@x"])

    # 5c. a declared service account missing from a snapshot that never captured any is UNCHECKED,
    #     not a defect — a detector must not invent findings out of its own blind spot
    f, r = compare(
        "openbank",
        {"service-account-x": {"ROLE_API"}, "admin@x": {"ROLE_ADMIN"}},
        {"admin@x": {"ROLE_ADMIN"}},
    )
    check("absent service account is unchecked, not a finding",
          f == [] and r["uncheckedPrincipals"] == ["service-account-x"])

    # 7. #10486 known-positive, in the LIVE shape: the shared client's service account holds
    #    ROLE_OPERATOR the template does not grant. Must be an ::error:: (fails the job), not the
    #    ::warning:: that let the 2026-09-21 run complete green.
    f, r = compare(
        "openbank",
        {"service-account-openbank-services": {"ROLE_API"}},
        {"service-account-openbank-services": {"ROLE_API", "ROLE_OPERATOR", "default-roles-openbank"}},
    )
    check("privileged SA over-grant must be an error",
          any(x.startswith("::error::") and "ROLE_OPERATOR" in x for x in f))
    check("…and listed in privilegedServiceAccountDrift",
          r["privilegedServiceAccountDrift"] == ["service-account-openbank-services"])

    # 7b. an UNDECLARED service account holding ROLE_ADMIN is also an error
    f, r = compare("openbank", {}, {"service-account-ghost": {"ROLE_ADMIN"}})
    check("undeclared privileged SA must be an error", any(x.startswith("::error::") for x in f))

    # 7c. a non-privileged SA over-grant stays a warning; a HUMAN with ROLE_OPERATOR is not "SA"
    f, r = compare("openbank", {"service-account-x": {"ROLE_API"}},
                   {"service-account-x": {"ROLE_API", "ROLE_VIEWER"}})
    check("non-privileged SA over-grant is a warning", f and all("::warning::" in x for x in f))

    # 7d. client-role mappings, when captured, are compared; when not captured, not invented
    f, r = compare("openbank", {"service-account-x": {"ROLE_API"}},
                   {"service-account-x": {"ROLE_API", CLIENT_CAPTURED, "realm-management/manage-users"}})
    check("captured extra client role is an over-grant",
          r["overGranted"] == {"service-account-x": ["realm-management/manage-users"]})
    f, r = compare("openbank", {"service-account-x": {"ROLE_API", "c/r"}},
                   {"service-account-x": {"ROLE_API"}})
    check("uncaptured client roles are not reported missing", f == [])

    # 8. live default scopes: a referenced client without `profile` is an error
    f, r = compare_client_scopes("openbank", {"openbank-edge", "openbank-services"},
                                 {"openbank-edge": ["openid", "roles"],
                                  "openbank-services": ["openid", "profile", "roles"]})
    check("live client without profile must be an error",
          r["missingProfileScope"] == ["openbank-edge"] and f[0].startswith("::error::"))

    # 6. an empty capture is a failure, never a clean realm — the difference between "this realm
    #    has no users" and "the credential was wrong" is the difference between silence and alarm
    import tempfile

    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as fh:
        fh.write("{}")
        empty_snapshot = fh.name
    try:
        load_live(f"openbank={empty_snapshot}")
        check("empty snapshot must raise", False)
    except SystemExit:
        pass

    for name in failures:
        sys.stderr.write(f"::error::self-test FAILED: {name}\n")
    if failures:
        return 1
    print("self-test OK (17/17 cases)")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument(
        "--live",
        action="append",
        default=[],
        metavar="REALM=PATH",
        help="live user role-mapping snapshot; PATH may be - for stdin. Repeatable.",
    )
    ap.add_argument(
        "--live-client-scopes",
        action="append",
        default=[],
        metavar="REALM=PATH",
        help="live {clientId: [default scope names]} snapshot (#10486). Repeatable.",
    )
    ap.add_argument("--self-test", action="store_true", help="run the falsifiability harness")
    ap.add_argument(
        "--enforce",
        action="store_true",
        help="exit non-zero on any finding (default: report and exit 0, like its sibling's intro)",
    )
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    root = pathlib.Path(args.root).resolve()
    declared, open_registration = template_users(root)
    if not declared:
        sys.stderr.write(f"::error::no realm template found under {REALM_GLOB} — cannot run blind\n")
        return 1

    live = dict(load_live(s) for s in args.live)
    findings, report = [], {"realms": {}}

    for realm in sorted(set(declared) | set(live)):
        if realm not in live:
            findings.append(
                f"::warning::realm `{realm}` has a template in git but no live user snapshot was "
                f"supplied — UNCHECKED, not clean.",
            )
            report["realms"][realm] = {"status": "unchecked"}
            continue
        f, r = compare(realm, declared.get(realm, {}), live[realm], realm in open_registration)
        findings += f
        report["realms"][realm] = r

    if args.live_client_scopes:
        referenced = rego_service_account_clients(root)
        for spec in args.live_client_scopes:
            realm, _, path = spec.partition("=")
            f, r = compare_client_scopes(realm, referenced, json.loads(pathlib.Path(path).read_text()))
            findings += f
            report.setdefault("clientScopes", {})[realm] = r
    else:
        report["clientScopes"] = {"status": "unchecked — no --live-client-scopes snapshot"}

    report["privilegedServiceAccountDriftCount"] = sum(
        len(r.get("privilegedServiceAccountDrift", [])) for r in report["realms"].values())

    for line in findings:
        sys.stderr.write(line + "\n")
    print(json.dumps(report, indent=2, sort_keys=True))

    hard = [f for f in findings if f.startswith("::error::")]
    if args.enforce and findings:
        return 1
    return 1 if hard else 0


if __name__ == "__main__":
    raise SystemExit(main())
