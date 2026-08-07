#!/usr/bin/env python3
"""One OIDC client secret, one KV key: every ExternalSecret must read it from `account-service`.

WHY THIS IS A GATE AND NOT A COMMENT. Every service in the fleet authenticates as the SAME
Keycloak confidential client, `openbank-services` -- verified by reading `quarkus.oidc.client-id`
out of each service's own application.yaml, where it is the literal `openbank-services` (anacredit
writes it as `${QUARKUS_OIDC_CLIENT_ID:openbank-services}`, same value). There is no dedicated
realm client behind any per-service KV key, so the key NAME carries no meaning at all: it is pure
storage convention, and the two conventions in the tree are indistinguishable to a reader.

Getting it wrong is silent and expensive. `openbank-delegation-service` shipped with
`remoteRef.key: delegation-service`, an entry nobody had ever written. ESO answered
`Secret does not exist`, the target Secret was never created, and the pod sat in
CreateContainerConfigError for its entire life. Roughly a dozen alerts fired and not one of them
named the cause (SLOMetricAbsent x3, TargetDown, DeploymentNoAvailableReplicas, KubePodNotReady,
KubeContainerWaiting, KubeDeploymentReplicasMismatch, RolloutStuck, ArgoCDAppDegraded,
PostgresWALArchiveFailing, two Kyverno criticals). Fixed in #3471 by pointing at the shared entry.
Nothing in CI could have caught it: the YAML is valid, ArgoCD syncs the ExternalSecret happily,
and `gitops_ref_integrity` is satisfied because the ExternalSecret DOES declare the Secret -- it
just never materialises. The defect lives one layer further out, in the KV key the ref names.

THE RULE (rules.yaml: oidc_secret_convention). Under `openbank-infra/gitops/components/`, an
ExternalSecret data entry that projects the OIDC client secret -- `remoteRef.property` or
`secretKey` equal to `OIDC_CLIENT_SECRET` -- must use `remoteRef.key: account-service`. That is
the entry 28 of today's 38 already read and the only one demonstrably populated (28 live
consumers). A per-service key requires a KV write nobody is prompted to make, which is exactly
the step that was skipped.

BASELINE. The 10 pre-existing per-service entries are baselined against #3485, NOT migrated:
switching a live ExternalSecret's remoteRef re-projects the Secret, and this repo cannot see what
those KV entries hold. If they hold a stale value the switch is a no-op improvement; if
`account-service` were the stale one it would break 10 services at once. That is not a call to
make blind, so this gate freezes the split instead of moving it. The baseline is SHRINK-ONLY and
a stale entry (one that no longer violates) is reported too -- so a migration done later cannot
leave a dead exemption behind, and a new service cannot join the losing side quietly.

Run standalone:  .github/scripts/check-oidc-secret-convention.py [--enforce]
Self-test:       .github/scripts/check-oidc-secret-convention.py --self-test
"""

from __future__ import annotations

import sys
from pathlib import Path

import yaml

REPO = Path(__file__).resolve().parents[2]
COMPONENTS = REPO / "openbank-infra" / "gitops" / "components"

SECRET_FIELD = "OIDC_CLIENT_SECRET"
SHARED_KEY = "account-service"

# Pre-existing per-service entries, measured mechanically on origin/main 2026-08-06 (#3485).
# path relative to gitops/components  ->  the KV key it reads.
# SHRINK-ONLY: remove an entry when the manifest is migrated to SHARED_KEY. Do not add.
BASELINE: dict[str, str] = {
    "anacredit/oidc-externalsecret.yaml": "anacredit-service",
    "statements/external-secret-oidc.yaml": "statement-service",
    "campaign/external-secret-oidc.yaml": "campaign-service",
    "sdd/oidc-externalsecret.yaml": "sdd-service",
    "tpp-registry/oidc-externalsecret.yaml": "tpp-registry-service",
    "external-secrets/es-sanctions-service-oidc.yaml": "sanctions-service",
    "external-secrets/es-audit-service-oidc.yaml": "audit-service",
    "external-secrets/es-mcp-service.yaml": "mcp-service",
    "external-secrets/es-agent-service.yaml": "agent-service",
    "external-secrets/es-balance-service-oidc.yaml": "balance-service",
}


def entries_in(doc, rel: str) -> list[tuple[str, str]]:
    """Every OIDC-client-secret projection in `doc`, as (rel, remoteRef.key)."""
    if not isinstance(doc, dict) or doc.get("kind") != "ExternalSecret":
        return []
    out = []
    for entry in (doc.get("spec") or {}).get("data") or []:
        if not isinstance(entry, dict):
            continue
        ref = entry.get("remoteRef") or {}
        if not isinstance(ref, dict):
            continue
        if SECRET_FIELD not in (ref.get("property"), entry.get("secretKey")):
            continue
        key = ref.get("key")
        if isinstance(key, str):
            out.append((rel, key))
    return out


def classify(found: list[tuple[str, str]]) -> tuple[list[str], list[str], int]:
    """-> (violations, stale baseline entries, count of conforming entries)."""
    violations, conforming = [], 0
    still_violating: set[str] = set()
    for rel, key in found:
        if key == SHARED_KEY:
            conforming += 1
            continue
        if BASELINE.get(rel) == key:
            still_violating.add(rel)
            continue
        violations.append(
            f"{rel}: OIDC_CLIENT_SECRET is read from remoteRef.key `{key}`. Every service "
            f"authenticates as the same Keycloak client `openbank-services`, so the shared KV "
            f"entry `{SHARED_KEY}` is the convention (rules.yaml: oidc_secret_convention). A "
            f"per-service key needs a KV write nobody prompts you for; when it is missing ESO "
            f"answers `Secret does not exist`, the Secret is never created and the pod sits in "
            f"CreateContainerConfigError (#3471)."
        )
    stale = [
        f"{rel}: baselined as reading `{key}` but no longer does. Delete the entry from "
        f"BASELINE in {Path(__file__).name} -- a stale exemption hides the next regression."
        for rel, key in BASELINE.items()
        if rel not in still_violating
    ]
    return violations, stale, conforming


def audit() -> tuple[list[str], list[str], int, int]:
    found: list[tuple[str, str]] = []
    scanned = 0
    for path in sorted(COMPONENTS.rglob("*.yaml")):
        scanned += 1
        rel = path.relative_to(COMPONENTS).as_posix()
        try:
            docs = list(yaml.safe_load_all(path.read_text()))
        except yaml.YAMLError:
            # Not this gate's job: `Validate manifests` already fails on unparseable YAML.
            continue
        for doc in docs:
            found += entries_in(doc, rel)
    violations, stale, conforming = classify(found)
    return violations, stale, conforming, scanned


def _es(key: str, *, prop: str | None = SECRET_FIELD, secret_key: str = SECRET_FIELD) -> dict:
    ref: dict = {"key": key}
    if prop is not None:
        ref["property"] = prop
    return {"kind": "ExternalSecret", "spec": {"data": [{"secretKey": secret_key,
                                                         "remoteRef": ref}]}}


def self_test() -> int:
    """Feed it inputs it MUST flag and inputs it MUST NOT -- a gate that has only ever passed is
    unfalsified. Both the DETECTION (can it express the construct?) and the SCOPE (did it open
    any file at all?) are asserted; the scope half is the one a passing gate hides."""
    a_baselined = next(iter(BASELINE.items()))
    cases: list[tuple[str, list[tuple[str, str]], int, int]] = [
        # (name, found-entries, expected violations, expected stale)
        ("the exact #3471 defect -- a key nobody wrote",
         [("delegation/oidc-externalsecret.yaml", "delegation-service")], 1, len(BASELINE)),
        ("the shared key passes",
         [("delegation/oidc-externalsecret.yaml", SHARED_KEY)], 0, len(BASELINE)),
        ("a baselined entry is exempt, and not reported stale",
         [a_baselined], 0, len(BASELINE) - 1),
        ("a baselined path that MIGRATED is reported stale",
         [(a_baselined[0], SHARED_KEY)], 0, len(BASELINE)),
        ("a baselined path pointing at a DIFFERENT wrong key is still a violation",
         [(a_baselined[0], "somewhere-else")], 1, len(BASELINE)),
        ("today's tree, minus baseline, is clean", [], 0, len(BASELINE)),
    ]
    failed = 0
    for name, found, want_v, want_s in cases:
        v, s, _ = classify(found)
        ok = (len(v), len(s)) == (want_v, want_s)
        failed += not ok
        print(f"  {'PASS' if ok else 'FAIL'}  {name} "
              f"(expected {want_v}v/{want_s}s, got {len(v)}v/{len(s)}s)")

    parse_cases = [
        ("an ExternalSecret projecting the secret is seen", _es("x"), 1),
        ("property alone is enough (secretKey renamed)",
         _es("x", secret_key="CLIENT_SECRET"), 1),
        ("secretKey alone is enough (no property)", _es("x", prop=None), 1),
        ("an unrelated secret in the same file is ignored",
         _es("x", prop="DB_PASSWORD", secret_key="DB_PASSWORD"), 0),
        ("a non-ExternalSecret document mentioning it is not flagged",
         {"kind": "Deployment", "spec": {"data": [
             {"secretKey": SECRET_FIELD, "remoteRef": {"key": "x"}}]}}, 0),
        ("an ExternalSecret with no data entries is fine", {"kind": "ExternalSecret",
                                                            "spec": {}}, 0),
        ("a null document does not crash the walk", None, 0),
    ]
    for name, doc, want in parse_cases:
        got = len(entries_in(doc, "z/es.yaml"))
        ok = got == want
        failed += not ok
        print(f"  {'PASS' if ok else 'FAIL'}  {name} (expected {want}, got {got})")

    # SCOPE. A gate is green two independent ways: it cannot express the construct, or it never
    # opened the file. The cases above falsify detection; this falsifies scope, by asserting the
    # real walk both reaches files and finds the entries the baseline claims exist.
    _, _, conforming, scanned = audit()
    total = conforming + len(BASELINE)
    scope_ok = scanned > 100 and conforming > 0
    failed += not scope_ok
    print(f"  {'PASS' if scope_ok else 'FAIL'}  scope: walked {scanned} manifest(s) under "
          f"gitops/components and found {total} OIDC_CLIENT_SECRET projection(s) "
          f"({conforming} on `{SHARED_KEY}`, {len(BASELINE)} baselined)")

    n = len(cases) + len(parse_cases) + 1
    print(f"self-test: {n - failed}/{n} passed")
    return 1 if failed else 0


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()
    enforce = "--enforce" in sys.argv
    violations, stale, conforming, scanned = audit()
    total = conforming + len(BASELINE) - len(stale) + len(violations)
    print(f"check-oidc-secret-convention: walked {scanned} manifest(s) under "
          f"gitops/components; {total} OIDC_CLIENT_SECRET projection(s) "
          f"({conforming} on `{SHARED_KEY}`, {len(BASELINE) - len(stale)} baselined).")
    findings = violations + stale
    if not findings:
        print("check-oidc-secret-convention: OK — every non-baselined projection reads "
              f"`{SHARED_KEY}`, and the baseline is exact.")
        return 0
    for f in findings:
        print(f"{'::error::' if enforce else '::warning::'}{f}")
    return 1 if enforce else 0


if __name__ == "__main__":
    sys.exit(main())
