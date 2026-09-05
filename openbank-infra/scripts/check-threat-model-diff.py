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
  - openbank-infra/gitops/components/**: S's network-policies.yaml (any change —
    a NetworkPolicy is reach by construction), or its Deployment/Rollout manifest
    when the DIFF HUNKS touch a boundary key (ports, serviceAccountName,
    securityContext, auth-shaped env) rather than generated churn — a
    policy-checksum restamp or an image tag is not a boundary change (#3431)

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
                            entirely. Hunks cannot be inspected this way, so the
                            manifest rule stays conservative and flags on the path.
    --head <ref>            head ref/sha (default HEAD); lets a measurement replay a
                            past commit with `--base <sha>^ --head <sha>`.
    --self-test             run the classifier against known-positive/negative diffs.

Modes (ADR-0144 gate graduation):
    default    advisory — findings are ::warning annotations, exit 0
    --enforce  findings are ::error annotations, exit 1
"""
from __future__ import annotations

import argparse
import difflib
import importlib.util
import pathlib
import re
import subprocess
import sys

import yaml

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

# Generated lines in a Deployment/Rollout that are never a trust-boundary change:
# the OPA pod-roll annotation (restamped fleet-wide by any rules.yaml edit) and the
# image reference (rewritten by auto-deploy on every push). Matched BEFORE the
# boundary keys below, since an image line contains ':' and digests look like config.
MANIFEST_GENERATED = re.compile(
    r"^(openbank\.tech/policy-checksum|image|imagePullPolicy)\s*:",
    re.IGNORECASE,
)

# Keys in a Deployment/Rollout/NetworkPolicy hunk that CAN move a trust boundary:
# who can reach the pod, what identity it runs as, what it may talk to.
MANIFEST_BOUNDARY_KEY = re.compile(
    r"\b("
    r"ports?|containerPort|hostPort|nodePort|targetPort|"          # listeners
    r"ingress|egress|policyTypes|podSelector|namespaceSelector|"    # NetworkPolicy reach
    r"serviceAccountName|automountServiceAccountToken|"            # workload identity
    r"securityContext|runAsUser|runAsNonRoot|privileged|"          # privilege
    r"capabilities|hostNetwork|hostPID|hostIPC|"
    r"[A-Z_]*(OIDC|AUTHZ?|OPA|TLS|MTLS|ISSUER|TOKEN|SECRET|KEYCLOAK)[A-Z_]*"  # env names
    r")\b",
    re.IGNORECASE,
)


def load_money_path_services() -> list[str]:
    """Reuse check-threat-models.py's rules.yaml parser — one parser, one truth."""
    path = pathlib.Path(__file__).resolve().parent / "check-threat-models.py"
    spec = importlib.util.spec_from_file_location("check_threat_models", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.money_path_services()


def changed_files(base: str, head: str = "HEAD") -> list[str]:
    res = subprocess.run(
        ["git", "diff", "--name-only", f"{base}...{head}"],  # 3-dot: the squash delta
        capture_output=True, text=True, cwd=REPO,
    )
    if res.returncode != 0 and "no merge base" in res.stderr:
        # Shallow CI checkout (fetch-depth 1 + a depth-1 fetch of the PR base sha) has
        # no merge base. In PR CI, HEAD is the fresh refs/pull/N/merge commit, so a
        # 2-dot diff against the base sha IS the squash delta there (same reason
        # check-api-contract.py diffs 2-dot) — fall back to it.
        res = subprocess.run(
            ["git", "diff", "--name-only", base, head],
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


def changed_body_lines(diff_text: str) -> list[str]:
    """The added/removed content lines of a unified diff, comments and headers dropped."""
    out: list[str] = []
    for line in diff_text.splitlines():
        if line.startswith(("+++", "---")) or line[:1] not in "+-":
            continue
        body = line[1:].strip()
        if body and not body.startswith("#"):
            out.append(body)
    return out


def hunk_moves_boundary(diff_text: str) -> bool:
    """True if a Deployment/Rollout diff changes something that can move a trust boundary.

    The manifest FILENAME is not evidence. A single `rules.yaml` edit restamps the
    `openbank.tech/policy-checksum` pod-roll annotation on ~29 service manifests at once, and
    auto-deploy rewrites an image tag on every push — neither opens a port, grants an identity
    or changes who may reach the pod. Treating those as boundary changes lit up the whole
    money-path fleet on 7 of 60 commits (issue #3431) and is exactly the generated churn that
    teaches people to ignore a gate.

    So: drop the known-generated lines first, then require a key that actually describes the
    boundary. Mirrors what this script already does for application.yaml — the gitops rule was
    the one place trusting the path instead of the hunk.
    """
    for body in changed_body_lines(diff_text):
        if MANIFEST_GENERATED.search(body):
            continue
        if MANIFEST_BOUNDARY_KEY.search(body):
            return True
    return False


def documents_naming(text: str, tokens: set[str]) -> str:
    """The YAML documents in `text` whose own `metadata.name` names this service.

    WHY THIS EXISTS — a shared manifest is not a per-service one. `payments-services.yaml` carries
    the Deployment and Service of a dozen services in one file, so a diff that ADDS a new service's
    Deployment touches a file every other service in it is named by. `hunk_moves_boundary` then sees
    `env:`, `secretKeyRef:` and `containerPort:` in the added block and reports a trust-boundary
    change for sepa-payment, domestic-payment and everyone else — none of whose documents changed by
    a byte.

    Measured on the ADR-0283 phase 1 PR: adding card-processing-service flagged two unrelated
    money-path services, and the only remedies were editing their threat models to say nothing had
    changed (a lie, in two documents) or this.

    A file this cannot parse is returned WHOLE, which keeps the old conservative behaviour rather
    than turning an unparseable manifest into an exemption.
    """
    try:
        docs = list(yaml.safe_load_all(text))
    except Exception:  # noqa: BLE001 - an unparseable manifest must not become an exemption
        return text
    kept = []
    for doc in docs:
        if not isinstance(doc, dict):
            continue
        name = str(((doc.get("metadata") or {}).get("name")) or "")
        if name and token_in(tokens, name):
            kept.append(yaml.safe_dump(doc, sort_keys=True))
    return "\n".join(kept)


def own_document_diff(
    service: str, rel: str, base: str | None, head: str = "HEAD",
) -> str | None:
    """A unified diff of only THIS service's documents inside a shared manifest.

    None means the question could not be answered (no base, or a version that cannot be read), and
    the caller stays conservative — an unanswerable probe is not a clean one.

    The result is fed back through [hunk_moves_boundary] rather than compared for equality: a
    document can change without moving a boundary, and the generated churn this file already knows
    about (the pod-roll `policy-checksum`, an auto-deploy image tag) restamps every service's own
    document at once. Comparing whole documents would re-introduce exactly the noise #3431 removed.
    """
    if base is None:
        return None
    tokens = gitops_tokens(service)
    try:
        before = subprocess.run(
            ["git", "show", f"{base}:{rel}"], cwd=REPO, capture_output=True, text=True, check=False,
        )
        after = subprocess.run(
            ["git", "show", f"{head}:{rel}"], cwd=REPO, capture_output=True, text=True, check=False,
        )
    except OSError:
        return None
    if before.returncode != 0 or after.returncode != 0:
        return None
    return "\n".join(
        difflib.unified_diff(
            documents_naming(before.stdout, tokens).splitlines(),
            documents_naming(after.stdout, tokens).splitlines(),
            lineterm="",
        )
    )


def file_diff(base: str | None, head: str, rel: str) -> str | None:
    """Unified diff of one path, or None when it cannot be inspected."""
    if base is None:
        return None
    res = subprocess.run(
        ["git", "diff", f"{base}...{head}", "--", rel],
        capture_output=True, text=True, cwd=REPO,
    )
    if res.returncode != 0 or not res.stdout:
        return None
    return res.stdout


def yaml_security_keys_changed(base: str | None, head: str, rel: str) -> bool:
    """True if the application.yaml diff touches a trust-boundary key.

    Without git (--changed-files synthetic list) the hunks cannot be inspected —
    be conservative and treat the touch as boundary-relevant (advisory gate).
    """
    diff_text = file_diff(base, head, rel)
    if diff_text is None:
        return True  # can't inspect — stay conservative
    return any(SECURITY_KEY.search(b) for b in changed_body_lines(diff_text))


def adapter_is_client(rel: str) -> bool:
    path = REPO / rel
    if not path.is_file():
        return False  # deleted / synthetic — shrinking an edge is reviewed elsewhere
    try:
        return bool(CLIENT_HINT.search(path.read_text(encoding="utf-8", errors="replace")))
    except OSError:
        return False


def gitops_hit(
    service: str, comp: str, fname: str, base: str | None = None, head: str = "HEAD",
) -> str | None:
    """Reason string if this gitops file is S's NetworkPolicy or Deployment/Rollout.

    A NetworkPolicy is a boundary by construction — its entire content is reach — so any
    change to one counts. A Deployment/Rollout is not: see hunk_moves_boundary.
    """
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
            diff_text = file_diff(base, head, rel)
            if diff_text is None:
                # Cannot inspect hunks (synthetic --changed-files list): stay conservative,
                # same contract as yaml_security_keys_changed.
                return f"{rel} (Deployment/Rollout)"
            if not hunk_moves_boundary(diff_text):
                return None
            # A shared manifest carries many services. Narrow to this one's own documents and read
            # the hunks again — see documents_naming for what that prevents.
            own_diff = own_document_diff(service, rel, base, head)
            if own_diff is not None and not hunk_moves_boundary(own_diff):
                return None
            return f"{rel} (Deployment/Rollout)"
    return None


def boundary_reasons(service: str, changed: list[str], base: str | None, head: str = "HEAD") -> list[str]:
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
        if m and m.group(1) == service and yaml_security_keys_changed(base, head, rel):
            reasons.append(f"{rel} (authn/listener/transport keys)")
            continue
        m = GITOPS_RE.match(rel)
        if m:
            hit = gitops_hit(service, m.group(1), m.group(2), base, head)
            if hit:
                reasons.append(hit)
    return reasons


SELF_TEST_CASES: list[tuple[str, str, bool]] = [
    (
        "pod-roll annotation restamp only (a rules.yaml edit does this fleet-wide)",
        "@@\n-        openbank.tech/policy-checksum: \"8f455914ec6024b2\"\n"
        "+        openbank.tech/policy-checksum: \"6ef160b0682ff4d9\"\n",
        False,
    ),
    (
        "image tag bump only (auto-deploy rewrites this on every push)",
        "@@\n-        image: ghcr.io/jiraska/openbank-ledger-service:sandbox-8992de5\n"
        "+        image: ghcr.io/jiraska/openbank-ledger-service:sandbox-2b8a7cf\n",
        False,
    ),
    (
        "a new container port IS a boundary change",
        "@@\n         ports:\n+          - containerPort: 9443\n",
        True,
    ),
    (
        "a changed service account IS a boundary change",
        "@@\n-      serviceAccountName: ledger\n+      serviceAccountName: ledger-privileged\n",
        True,
    ),
    (
        "a new OIDC issuer env var IS a boundary change",
        "@@\n+            - name: QUARKUS_OIDC_AUTH_SERVER_URL\n"
        "+              value: https://keycloak.example/realms/openbank\n",
        True,
    ),
    (
        "a replica count is not a boundary change",
        "@@\n-  replicas: 2\n+  replicas: 3\n",
        False,
    ),
    (
        "an annotation restamp AND a port change still flags",
        "@@\n-        openbank.tech/policy-checksum: \"aaaa\"\n"
        "+        openbank.tech/policy-checksum: \"bbbb\"\n+          - containerPort: 8443\n",
        True,
    ),
]


def self_test() -> int:
    """Feed the classifier diffs it MUST flag and diffs it MUST NOT.

    This gate shipped advisory with no self-test, so its failure path had never run —
    the 7-of-60 false-positive rate in #3431 was found by hand, not by CI. A gate whose
    only demonstrated behaviour is passing cannot be graduated to enforced.
    """
    ok = True
    for name, diff_text, expected in SELF_TEST_CASES:
        got = hunk_moves_boundary(diff_text)
        mark = "ok" if got == expected else "FAIL"
        if got != expected:
            ok = False
        print(f"  [{mark}] {name}: flagged={got} expected={expected}")
    print(f"self-test: {'PASS' if ok else 'FAIL'}")
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="origin/main", help="PR base ref/sha (3-dot diff)")
    ap.add_argument("--head", default="HEAD", help="head ref/sha; lets a measurement replay a past commit")
    ap.add_argument("--changed-files", help="file with a newline-separated changed-file list (skips git)")
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true", help="run the classifier's known-positive/negative cases")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    if args.changed_files:
        changed = [
            line.strip()
            for line in pathlib.Path(args.changed_files).read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]
        base = None
    else:
        changed = changed_files(args.base, args.head)
        base = args.base

    level = "error" if args.enforce else "warning"
    services = load_money_path_services()
    changed_set = set(changed)
    findings: list[tuple[str, list[str]]] = []

    for service in services:
        reasons = boundary_reasons(service, changed, base, args.head)
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
