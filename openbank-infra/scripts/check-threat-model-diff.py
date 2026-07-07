#!/usr/bin/env python3
"""Diff-aware threat-model gate for money-path services (ADR-0030 D2, issue #265).

The coverage gate (check-threat-models.py) proves a threat model EXISTS; this one
proves it is UPDATED when a PR moves a money-path service's TRUST BOUNDARIES. A
trust-boundary change for service S is any changed file matching:

  - S/src/main/**/infrastructure/rest/**           (new/changed inbound endpoints)
  - S/src/main/**/infrastructure/client/**          (new/changed outbound edge)
  - S/src/main/**/adapter{,s}/**.kt|.java with an HTTP/gRPC client hint in the file
  - S/src/main/resources/application.yaml with (oidc|auth|authz|opa|http|kafka|
    security) keys in the diff hunks                 (listeners / authn / transport)
  - openbank-infra/gitops/components/**: S's network-policies.yaml or its
    Deployment/Rollout manifest                      (new ingress/egress)

When any of those change for a money-path service (rules.yaml:
money_path_services, parsed by check-threat-models.py's parser) and
docs/threat-models/<service>.md is NOT part of the same diff, emit a finding.

stdlib-only; shells out to `git` unless --changed-files supplies the list.

Usage:
    check-threat-model-diff.py [--base <ref>] [--changed-files <path>] [--enforce]

    --base <ref>            PR base; changed files = `git diff --name-only
                            <ref>...HEAD` (3-dot = the actual squash delta).
                            Default: origin/main.
    --changed-files <path>  newline-separated changed-file list — bypasses git
                            entirely (testable with a synthetic diff).

Modes (ADR-0144 gate graduation):
    default    advisory — findings are ::warning annotations, exit 0
    --enforce  findings are ::error annotations, exit 1
"""
from __future__ import annotations

import argparse
import importlib.util
import pathlib
import re
import subprocess
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]

REST_RE = re.compile(r"^(openbank-[^/]+)/src/main/.*/infrastructure/rest/")
CLIENT_RE = re.compile(r"^(openbank-[^/]+)/src/main/.*/infrastructure/client/")
ADAPTER_RE = re.compile(r"^(openbank-[^/]+)/src/main/.*/adapters?/.*\.(?:kt|java)$")
APP_YAML_RE = re.compile(r"^(openbank-[^/]+)/src/main/resources/application\.yaml$")
GITOPS_RE = re.compile(r"^openbank-infra/gitops/components/([^/]+)/([^/]+\.ya?ml)$")

# An adapter file is an outbound trust edge only if it actually speaks HTTP/gRPC —
# a persistence adapter is not one (same hint style as check-api-contract.py).
CLIENT_HINT = re.compile(r"@RegisterRestClient|RestClient\b|HttpClient|WebTarget|[Gg]rpc")

# application.yaml keys that move a trust boundary: authn/authz, policy engine,
# HTTP listeners, Kafka transport, security.* — matched against diff hunk lines.
SECURITY_KEY = re.compile(r"\b(oidc|authz?|opa|http|kafka|security)[\w.-]*\s*:", re.IGNORECASE)

# Only these manifest kinds define ingress/egress or the runtime env of a service.
GITOPS_KIND = re.compile(r"^kind:\s*(NetworkPolicy|Deployment|Rollout)\s*$", re.MULTILINE)


def load_money_path_services() -> list[str]:
    """Reuse check-threat-models.py's rules.yaml parser — one parser, one truth."""
    path = pathlib.Path(__file__).resolve().parent / "check-threat-models.py"
    spec = importlib.util.spec_from_file_location("check_threat_models", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.money_path_services()


def changed_files(base: str) -> list[str]:
    res = subprocess.run(
        ["git", "diff", "--name-only", f"{base}...HEAD"],  # 3-dot: the squash delta
        capture_output=True, text=True, cwd=REPO,
    )
    if res.returncode != 0 and "no merge base" in res.stderr:
        # Shallow CI checkout (fetch-depth 1 + a depth-1 fetch of the PR base sha) has
        # no merge base. In PR CI, HEAD is the fresh refs/pull/N/merge commit, so a
        # 2-dot diff against the base sha IS the squash delta there (same reason
        # check-api-contract.py diffs 2-dot) — fall back to it.
        res = subprocess.run(
            ["git", "diff", "--name-only", base, "HEAD"],
            capture_output=True, text=True, cwd=REPO,
        )
    if res.returncode != 0:
        print(f"::error::threat-model-diff gate: git diff against {base} failed: {res.stderr.strip()}")
        sys.exit(1)
    return [line for line in res.stdout.splitlines() if line.strip()]


def gitops_tokens(service: str) -> set[str]:
    """Name tokens that identify S in gitops paths (component dir or filename).

    openbank-ledger-service -> {ledger-service, ledger}; openbank-sepa-payment ->
    {sepa-payment, sepa, payment} (the shared `payments` component hosts it).
    """
    short = service.removeprefix("openbank-")
    toks = {short}
    if short.endswith("-service"):
        toks.add(short[: -len("-service")])
    toks.update(t for t in short.split("-") if t != "service" and len(t) > 2)
    return toks


def token_in(tokens: set[str], text: str) -> bool:
    return any(
        re.search(rf"(^|[^a-z0-9]){re.escape(t)}s?($|[^a-z0-9])", text) for t in tokens
    )


def yaml_security_keys_changed(base: str | None, rel: str) -> bool:
    """True if the application.yaml diff touches a trust-boundary key.

    Without git (--changed-files synthetic list) the hunks cannot be inspected —
    be conservative and treat the touch as boundary-relevant (advisory gate).
    """
    if base is None:
        return True
    res = subprocess.run(
        ["git", "diff", f"{base}...HEAD", "--", rel],
        capture_output=True, text=True, cwd=REPO,
    )
    if res.returncode != 0 or not res.stdout:
        return True  # can't inspect — stay conservative
    for line in res.stdout.splitlines():
        if line.startswith(("+++", "---")) or line[:1] not in "+-":
            continue
        body = line[1:].strip()
        if not body.startswith("#") and SECURITY_KEY.search(body):
            return True
    return False


def adapter_is_client(rel: str) -> bool:
    path = REPO / rel
    if not path.is_file():
        return False  # deleted / synthetic — shrinking an edge is reviewed elsewhere
    try:
        return bool(CLIENT_HINT.search(path.read_text(encoding="utf-8", errors="replace")))
    except OSError:
        return False


def gitops_hit(service: str, comp: str, fname: str) -> str | None:
    """Reason string if this gitops file is S's NetworkPolicy or Deployment/Rollout."""
    tokens = gitops_tokens(service)
    if not (token_in(tokens, comp) or token_in(tokens, fname)):
        return None
    rel = f"openbank-infra/gitops/components/{comp}/{fname}"
    if fname == "network-policies.yaml":
        return f"{rel} (ingress/egress)"
    path = REPO / rel
    if path.is_file():
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            return None
        if GITOPS_KIND.search(text) and token_in(tokens, text):
            return f"{rel} (Deployment/Rollout)"
    return None


def boundary_reasons(service: str, changed: list[str], base: str | None) -> list[str]:
    reasons: list[str] = []
    for rel in changed:
        m = REST_RE.match(rel)
        if m and m.group(1) == service:
            reasons.append(f"{rel} (inbound REST surface)")
            continue
        m = CLIENT_RE.match(rel)
        if m and m.group(1) == service:
            reasons.append(f"{rel} (outbound client edge)")
            continue
        m = ADAPTER_RE.match(rel)
        if m and m.group(1) == service and adapter_is_client(rel):
            reasons.append(f"{rel} (HTTP/gRPC adapter)")
            continue
        m = APP_YAML_RE.match(rel)
        if m and m.group(1) == service and yaml_security_keys_changed(base, rel):
            reasons.append(f"{rel} (authn/listener/transport keys)")
            continue
        m = GITOPS_RE.match(rel)
        if m:
            hit = gitops_hit(service, m.group(1), m.group(2))
            if hit:
                reasons.append(hit)
    return reasons


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="origin/main", help="PR base ref/sha (3-dot diff)")
    ap.add_argument("--changed-files", help="file with a newline-separated changed-file list (skips git)")
    ap.add_argument("--enforce", action="store_true")
    args = ap.parse_args()

    if args.changed_files:
        changed = [
            line.strip()
            for line in pathlib.Path(args.changed_files).read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]
        base = None
    else:
        changed = changed_files(args.base)
        base = args.base

    level = "error" if args.enforce else "warning"
    services = load_money_path_services()
    changed_set = set(changed)
    findings: list[tuple[str, list[str]]] = []

    for service in services:
        reasons = boundary_reasons(service, changed, base)
        if not reasons:
            continue
        tm_rel = f"docs/threat-models/{service}.md"
        if tm_rel in changed_set:
            print(f"threat-model-diff gate: {service}: trust-boundary change WITH a {tm_rel} update — OK.")
        else:
            findings.append((service, reasons))

    for service, reasons in findings:
        print(
            f"::{level}::threat-model-diff gate: {service}: trust-boundary change without a "
            f"docs/threat-models/{service}.md update — {'; '.join(reasons)} (ADR-0030 D2)"
        )

    if findings and args.enforce:
        return 1
    if findings:
        print(
            f"threat-model-diff gate: {len(findings)} finding(s) — advisory until the ADR-0144 "
            "target_enforce_date; will become a hard gate."
        )
    else:
        print("threat-model-diff gate: no money-path trust-boundary change without a threat-model update.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
