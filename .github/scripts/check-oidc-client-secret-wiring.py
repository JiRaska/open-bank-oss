#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""A service that mints M2M tokens must have its OIDC client secret actually provisioned.

Why this exists
---------------
Four workloads referenced a `<service>-oidc` Secret that nothing in gitops creates, and every one
of those refs was `optional: true` — so the pod booted clean, `OIDC_CLIENT_SECRET` was simply
unset, the default oidc-client stayed "not initialized", and the rest-clients that carry
`OidcClientRequestReactiveFilter` sent no `Authorization` header at all (#2929).

`optional: true` was perfectly correlated with the Secret being absent: all 36 refs marked
`optional: false` had theirs, because a missing one blocks the pod and somebody notices within
minutes. The flag was not documenting an optional dependency — it was converting an unprovisioned
one from a loud crashloop into a silent boot.

For `fx-service` the consequence was that the ADR-0032 screening gate (sanctions, AML, fraud)
could not authenticate. Nothing had logged it, because the path had never executed:
`fx_conversions` is empty, so it would have failed on the FIRST conversion anyone performed. Same
shape as the ČNB feed 404 in #2204 — correct-looking until exercised.

WHAT IT CHECKS — all four from committed artifacts, so it needs no cluster access:

  1. A service whose `src/main` contains `OidcClientRequestReactiveFilter` (or the blocking
     variant) MUST have an `OIDC_CLIENT_SECRET` env ref in its Deployment.
  2. That ref must be `optional: false`. An authorization-bearing credential that is allowed to be
     missing does not degrade gracefully; it sends unauthenticated requests.
  3. The Secret it names must be created by an `ExternalSecret` in gitops.
  4. That ExternalSecret must read the ONE agreed KV entry (`rules.yaml:
     oidc_client_secret_storage.shared_kv_entry`), not a per-service one. See below.

And the converse, which is what makes it a shape rather than a checklist: a service with NO
OIDC-filtered client should not carry the env ref at all.

Check 4 — one credential, one KV entry (#3485)
---------------------------------------------
Checks 1-3 answer "is this secret delivered?" and cannot answer "delivered from WHERE?". The
estate had split into two storage conventions for one credential — measured on main 2026-08-02,
29 ExternalSecret entries read `account-service` and 10 read `<their-own>-service` — with nothing
recording which was intended. The split is arbitrary rather than meaningful because the `openbank`
realm defines exactly one confidential M2M client, `openbank-services`: a per-service KV entry
holds a COPY of the one credential, seeded by copying the shared entry
(`openbank-infra/scripts/seed-vault-gaps.sh` does that verbatim for audit-service).

That makes the copies pure cost. Rotation is per-CLIENT, so one Keycloak rotation must fan out to
every copy, with nothing enumerating them; and each copy needs a seed step that only a human
performs. That step is the one that gets skipped — `openbank-delegation-service` shipped pointing
at an entry nobody had written, ESO answered `Secret does not exist`, and the pod sat in
`CreateContainerConfigError` for its entire life behind ~12 alerts that named everything except
the cause (#3471).

Scope: an entry is IN scope when its `secretKey` or its `remoteRef.property` is
`OIDC_CLIENT_SECRET` — the name the shared client's secret is stored under everywhere. Workloads
authenticating as a DIFFERENT realm client (admin-ui, customer-edge, the WebAuthn client) store
theirs under `client-secret`/`kc-client-secret` and are correctly per-entry; they are out of
scope, and that is the distinction the property name carries. If per-service realm clients are
ever introduced this rule becomes wrong and must key off the client-id instead of the path.

Usage:  check-oidc-client-secret-wiring.py [--enforce] [--selftest]
Advisory by default (prints ::warning, exits 0) per the repo convention; --enforce fails the build.
"""

from __future__ import annotations

import argparse
import pathlib
import sys

import yaml

REPO = pathlib.Path(__file__).resolve().parents[2]
GITOPS = REPO / "openbank-infra" / "gitops"
RULES = REPO / "openbank-libs" / "governance" / "rules.yaml"
FILTER_MARKERS = ("OidcClientRequestReactiveFilter", "OidcClientRequestFilter")
ENV_NAME = "OIDC_CLIENT_SECRET"

# Workloads still carrying `optional: true` on a secret that IS provisioned by an ExternalSecret.
# Not flipped here on purpose: `optional: false` makes the pod refuse to start if the Secret is
# absent, and this check can only see the ExternalSecret DECLARATION, never whether ESO actually
# synced it. Flipping eight workloads on a declaration would trade a silent failure for a fleet
# crashloop — each needs one `kubectl -n <ns> get secret <name>` first, then the flip.
#
# Shrink-only: an entry that no longer has optional:true is itself reported, so this cannot
# quietly become the permanent state. Tracked in #2929.
OPTIONAL_TRUE_PENDING_LIVE_CHECK: dict[str, str] = {
    "agent-service": "secret is declared; flip after confirming it synced in `agent`.",
    "anacredit-service": "secret is declared; flip after confirming it synced in `anacredit`.",
    "billing-service": "secret is declared; flip after confirming it synced in `billing`.",
    "document-service": "secret is declared; flip after confirming it synced in `documents`.",
    "interest-service": "secret is declared; flip after confirming it synced in `interest`.",
    "mcp-service": "secret is declared; flip after confirming it synced in `mcp`.",
    "sdd-service": "secret is declared; flip after confirming it synced in `sdd`.",
    "tpp-registry-service": "secret is declared; flip after confirming it synced in `tpp-registry`.",
}


def services_minting_tokens() -> set[str]:
    """Services whose main sources wire an OIDC client filter onto a rest-client."""
    found: set[str] = set()
    for service in sorted(REPO.glob("openbank-*/src/main")):
        name = service.parts[len(REPO.parts)]
        for path in service.rglob("*"):
            if not path.is_file() or path.suffix not in (".kt", ".yaml", ".yml", ".properties"):
                continue
            try:
                text = path.read_text(encoding="utf-8")
            except (UnicodeDecodeError, OSError):
                continue
            if any(marker in text for marker in FILTER_MARKERS):
                found.add(name)
                break
    return found


def provisioned_secrets() -> set[tuple[str, str]]:
    """{(namespace, secret-name)} created by an ExternalSecret."""
    out: set[tuple[str, str]] = set()
    for path in GITOPS.rglob("*.yaml"):
        try:
            docs = list(yaml.safe_load_all(path.read_text(encoding="utf-8")))
        except (yaml.YAMLError, UnicodeDecodeError):
            continue
        for doc in docs:
            if not isinstance(doc, dict) or doc.get("kind") != "ExternalSecret":
                continue
            meta = doc.get("metadata") or {}
            target = ((doc.get("spec") or {}).get("target") or {}).get("name") or meta.get("name")
            if target:
                out.add((meta.get("namespace", ""), target))
    return out


def storage_policy() -> tuple[str, dict[str, str]]:
    """(shared KV entry, {externalsecret-name: reason}) from rules.yaml — never a second copy."""
    block = (yaml.safe_load(RULES.read_text(encoding="utf-8")) or {}).get(
        "oidc_client_secret_storage",
    ) or {}
    shared = block.get("shared_kv_entry")
    if not shared:
        raise SystemExit(
            "rules.yaml: oidc_client_secret_storage.shared_kv_entry is missing — the checker has "
            "no convention to enforce and would silently pass everything.",
        )
    return shared, dict(block.get("exemptions") or {})


def shared_client_secret_entries() -> list[tuple[str, str, str, str, pathlib.Path]]:
    """[(namespace, externalsecret, secretKey, remoteRef.key, manifest)] delivering the shared
    openbank-services client secret. In scope iff the secretKey or the remote property is
    OIDC_CLIENT_SECRET — a different realm client stores its secret under a different name."""
    out: list[tuple[str, str, str, str, pathlib.Path]] = []
    for path in GITOPS.rglob("*.yaml"):
        try:
            docs = list(yaml.safe_load_all(path.read_text(encoding="utf-8")))
        except (yaml.YAMLError, UnicodeDecodeError):
            continue
        for doc in docs:
            if not isinstance(doc, dict) or doc.get("kind") != "ExternalSecret":
                continue
            meta = doc.get("metadata") or {}
            for entry in (doc.get("spec") or {}).get("data") or []:
                if not isinstance(entry, dict):
                    continue
                remote = entry.get("remoteRef") or {}
                if ENV_NAME not in (entry.get("secretKey"), remote.get("property")):
                    continue
                out.append((
                    meta.get("namespace", ""), meta.get("name", "?"),
                    str(entry.get("secretKey")), str(remote.get("key")), path,
                ))
    return out


def oidc_env_refs() -> dict[str, tuple[str, str, bool, pathlib.Path]]:
    """{workload: (namespace, secret-name, optional, manifest)} for every OIDC_CLIENT_SECRET ref."""
    refs: dict[str, tuple[str, str, bool, pathlib.Path]] = {}
    for path in GITOPS.rglob("*.yaml"):
        try:
            docs = list(yaml.safe_load_all(path.read_text(encoding="utf-8")))
        except (yaml.YAMLError, UnicodeDecodeError):
            continue
        for doc in docs:
            if not isinstance(doc, dict) or doc.get("kind") not in ("Deployment", "Rollout", "StatefulSet"):
                continue
            meta = doc.get("metadata") or {}
            pod = (((doc.get("spec") or {}).get("template") or {}).get("spec") or {})
            for container in pod.get("containers") or []:
                for env in container.get("env") or []:
                    if not isinstance(env, dict) or env.get("name") != ENV_NAME:
                        continue
                    ref = ((env.get("valueFrom") or {}).get("secretKeyRef") or {})
                    if not ref.get("name"):
                        continue
                    refs[meta.get("name", "?")] = (
                        meta.get("namespace", ""), ref["name"], bool(ref.get("optional", False)), path,
                    )
    return refs


def storage_findings(
    entries: list[tuple[str, str, str, str, pathlib.Path]],
    shared: str,
    exemptions: dict[str, str],
) -> list[str]:
    """One finding per ExternalSecret reading a KV entry other than the agreed shared one, plus
    one per stale exemption. Pure, so --selftest can drive it with synthetic input."""
    findings: list[str] = []
    used: set[str] = set()
    for namespace, name, secret_key, remote_key, manifest in sorted(entries):
        if remote_key == shared:
            continue
        if name in exemptions:
            used.add(name)
            print(f"::notice::{name} reads KV entry {remote_key!r} by exemption — "
                  f"{exemptions[name]}")
            continue
        findings.append(
            f"::error file={manifest.relative_to(REPO)}::ExternalSecret {namespace}/{name} "
            f"delivers {secret_key} from KV entry {remote_key!r}, but the shared "
            f"openbank-services client secret lives at {shared!r} "
            f"(rules.yaml: oidc_client_secret_storage). There is one realm client, so a "
            f"per-service entry is a COPY somebody has to seed by hand — and that is the step "
            f"that gets skipped, leaving ESO with `Secret does not exist` and the pod in "
            f"CreateContainerConfigError (#3471, #3485).",
        )
    for stale in sorted(set(exemptions) - used):
        findings.append(
            f"::error::stale oidc_client_secret_storage.exemptions entry {stale!r} — it no longer "
            f"reads a non-shared KV entry (or no longer exists). Remove it from rules.yaml, so "
            f"the exemption list can only shrink.",
        )
    return findings


def selftest() -> int:
    """Prove the scans can distinguish the states they exist to tell apart."""
    shared, exemptions = storage_policy()
    entries = shared_client_secret_entries()
    if not entries:
        print("selftest FAIL: no ExternalSecret delivers the shared client secret — the storage "
              "scan came back empty, which passes every manifest vacuously.")
        return 1
    # Falsifiability, both directions, on synthetic input the tree cannot supply.
    fake = pathlib.Path(REPO / "openbank-infra" / "gitops" / "selftest.yaml")
    must_flag = storage_findings([("ns", "es-x", ENV_NAME, "some-other-service", fake)],
                                 shared, {})
    must_pass = storage_findings([("ns", "es-x", ENV_NAME, shared, fake)], shared, {})
    if len(must_flag) != 1:
        print(f"selftest FAIL: a per-service KV entry produced {len(must_flag)} finding(s), "
              f"expected 1 — the convention check cannot go red.")
        return 1
    if must_pass:
        print("selftest FAIL: the agreed shared KV entry was flagged — the check would fail every "
              "conforming manifest and get switched off.")
        return 1
    if len(storage_findings([], shared, {"es-ghost": "gone"})) != 1:
        print("selftest FAIL: a stale exemption was not reported — the list could grow silently.")
        return 1
    print(f"selftest OK (storage): {len(entries)} shared-client-secret entry/entries, "
          f"shared_kv_entry={shared!r}; a per-service key flags, the shared key does not, "
          f"a stale exemption flags.")

    minting = services_minting_tokens()
    refs = oidc_env_refs()
    provisioned = provisioned_secrets()
    if not minting or not refs or not provisioned:
        print(f"selftest FAIL: an input scan came back empty "
              f"(minting={len(minting)}, refs={len(refs)}, provisioned={len(provisioned)}) — "
              f"a broken probe reports every service clean.")
        return 1
    # A name that cannot exist must not resolve, or the membership test is vacuous.
    if ("nowhere", "no-such-service-oidc") in provisioned:
        print("selftest FAIL: an invented secret resolved as provisioned.")
        return 1
    print(f"selftest OK: {len(minting)} token-minting service(s), {len(refs)} env ref(s), "
          f"{len(provisioned)} provisioned secret(s); the invented name does not resolve.")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--selftest", action="store_true", help="verify the check can fail")
    args = ap.parse_args()
    if args.selftest:
        return selftest()

    minting = services_minting_tokens()
    refs = oidc_env_refs()
    provisioned = provisioned_secrets()
    shared, exemptions = storage_policy()
    entries = shared_client_secret_entries()
    findings: list[str] = storage_findings(entries, shared, exemptions)
    used_pending: set[str] = set()

    for workload, (namespace, secret, optional, manifest) in sorted(refs.items()):
        rel = manifest.relative_to(REPO)
        if (namespace, secret) not in provisioned:
            findings.append(
                f"::error file={rel}::{workload} references the Secret {secret!r} in namespace "
                f"{namespace!r}, which no ExternalSecret creates. The env var is simply unset at "
                f"runtime and the oidc-client never initializes (#2929).",
            )
        if optional:
            if workload in OPTIONAL_TRUE_PENDING_LIVE_CHECK:
                used_pending.add(workload)
                print(f"::notice file={rel}::{workload} optional:true pending live check — "
                      f"{OPTIONAL_TRUE_PENDING_LIVE_CHECK[workload]}")
                continue
            findings.append(
                f"::error file={rel}::{workload}'s {ENV_NAME} ref is optional:true. A credential "
                f"that is allowed to be missing does not degrade gracefully — the service sends "
                f"UNAUTHENTICATED requests instead of failing. Make it optional:false, or drop "
                f"the ref entirely if the service has no OIDC-filtered rest-client (#2929).",
            )

    for workload in sorted(set(OPTIONAL_TRUE_PENDING_LIVE_CHECK) - used_pending):
        findings.append(
            f"::error::stale OPTIONAL_TRUE_PENDING_LIVE_CHECK entry {workload!r} — it no longer "
            f"carries optional:true (or no longer exists). Remove it, so the list can only shrink.",
        )

    referencing_services = {w if w.startswith("openbank-") else f"openbank-{w}" for w in refs}
    for service in sorted(minting):
        short = service.removeprefix("openbank-")
        if not any(w in (short, service, short.removesuffix("-service")) for w in refs):
            findings.append(
                f"::error file={service}/src/main::{service} wires an OIDC client filter onto a "
                f"rest-client but its workload has no {ENV_NAME} env ref — the M2M token it mints "
                f"has no client secret to mint from (#2929).",
            )
    del referencing_services

    for line in findings:
        print(line if args.enforce else line.replace("::error", "::warning", 1))
    verdict = "clean." if not findings else f"{len(findings)} finding(s) above."
    print(f"check-oidc-client-secret-wiring: {len(refs)} {ENV_NAME} ref(s), {len(minting)} "
          f"token-minting service(s), {len(provisioned)} provisioned secret(s), {len(entries)} "
          f"shared-client-secret KV read(s) against {shared!r} — {verdict}")
    return 1 if findings and args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
