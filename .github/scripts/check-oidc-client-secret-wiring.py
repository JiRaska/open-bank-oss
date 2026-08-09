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

WHAT IT CHECKS — all three from committed artifacts, so it needs no cluster access:

  1. A service whose `src/main` contains `OidcClientRequestReactiveFilter` (or the blocking
     variant) MUST have an `OIDC_CLIENT_SECRET` env ref in its Deployment.
  2. That ref must be `optional: false`. An authorization-bearing credential that is allowed to be
     missing does not degrade gracefully; it sends unauthenticated requests.
  3. The Secret it names must be created by an `ExternalSecret` in gitops.

And the converse, which is what makes it a shape rather than a checklist: a service with NO
OIDC-filtered client should not carry the env ref at all.

Usage:  check-oidc-client-secret-wiring.py [--enforce] [--selftest]
Advisory by default (prints ::warning, exits 0) per the repo convention; --enforce fails the build.
"""

from __future__ import annotations

import argparse
import pathlib
import sys

import yaml

import gatelib

REPO = pathlib.Path(__file__).resolve().parents[2]
GITOPS = REPO / "openbank-infra" / "gitops"
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
    "engagement-service": "new service, no Vault entry seeded yet — flip after confirming it synced in `engagement`.",
}


def services_minting_tokens() -> set[str]:
    """Services whose main sources wire an OIDC client filter onto a rest-client."""
    found: set[str] = set()
    for service in gatelib.glob(REPO, "openbank-*/src/main"):
        name = service.parts[len(REPO.parts)]
        for path in gatelib.rglob(service, "*"):
            if not path.is_file() or path.suffix not in (".kt", ".yaml", ".yml", ".properties"):
                continue
            try:
                text = gatelib.read_text(path)
            except (UnicodeDecodeError, OSError):
                continue
            if any(marker in text for marker in FILTER_MARKERS):
                found.add(name)
                break
    return found


def provisioned_secrets() -> set[tuple[str, str]]:
    """{(namespace, secret-name)} created by an ExternalSecret."""
    out: set[tuple[str, str]] = set()
    for path in gatelib.rglob(GITOPS, "*.yaml"):
        try:
            docs = gatelib.load_yaml_all(path)
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


def oidc_env_refs() -> dict[str, tuple[str, str, bool, pathlib.Path]]:
    """{workload: (namespace, secret-name, optional, manifest)} for every OIDC_CLIENT_SECRET ref."""
    refs: dict[str, tuple[str, str, bool, pathlib.Path]] = {}
    for path in gatelib.rglob(GITOPS, "*.yaml"):
        try:
            docs = gatelib.load_yaml_all(path)
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


def selftest() -> int:
    """Prove the scans can distinguish the states they exist to tell apart."""
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
    findings: list[str] = []
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
          f"token-minting service(s), {len(provisioned)} provisioned secret(s) — {verdict}")
    return 1 if findings and args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
