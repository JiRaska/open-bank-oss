#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""
Honeytokens must stay wired to a tripwire, and must stay powerless (ADR-0357, phase 1).

A honeytoken fails in two silent ways, and this gate catches both.

1. UNWIRED. The lure exists but nothing listens: rename the ServiceAccount and the metric
   filter keeps matching the old name, so the token works as a credential and alerts
   nobody. That is the ADR-0279 failure shape (HoneyEndpointHit over a filter no service
   configured, 0 hits in 7 days, unable to fire), and it is invisible from every angle
   because "no alert" is also what a quiet week looks like.

2. ARMED. Someone binds the honey SA to a Role, or attaches a policy or group to the canary
   IAM user, and the lure becomes a real credential sitting in the most exposed namespace.
   The design rests on the token being useless; nothing else would notice it stopped being.

Checks:
  - every ServiceAccount labelled openbank.io/deception=honeytoken has a CloudWatch metric
    filter in the substrate root whose pattern names system:serviceaccount:<ns>:<name>, and
    every such pattern names a declared honey SA;
  - no RoleBinding or ClusterRoleBinding anywhere in gitops has a subject that is a honey SA;
  - the canary IAM user has exactly one inline policy, it is an explicit Deny "*" on "*",
    and no policy attachment or group membership references it;
  - the canary EventBridge rules opt in to read-only management events (without that, the
    attacker's first call, sts:GetCallerIdentity, never reaches the rule);
  - while a Loki rule named HoneyEndpointHit exists, at least one workload sets a non-empty
    OPENBANK_SECURITY_HONEY_PATHS (else the rule has no subject — the ADR-0279 shape), and
    HoneytokenFilter stays @PreMatching (a post-matching filter is never called for a path
    with no resource method, i.e. for every honey path; measured 2026-10-31).

Usage:
    check-honeytoken-wiring.py             # scan
    check-honeytoken-wiring.py --selftest  # prove every check can fail
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

import yaml

REPO = Path(__file__).resolve().parents[2]
GITOPS = REPO / "openbank-infra" / "gitops"
TOFU = REPO / "openbank-infra" / "aws" / "envs" / "sandbox-substrate"
FILTER = REPO / "openbank-libs-runtime" / "src" / "main" / "kotlin" / "com" / "openbank" / "libs" / "security" / "HoneytokenFilter.kt"
HONEY_ENV = "OPENBANK_SECURITY_HONEY_PATHS"
LABEL = "openbank.io/deception"

FILTER_SA_RE = re.compile(r'system:serviceaccount:(?P<ns>[a-z0-9-]+):(?P<name>[a-z0-9-]+)')
RESOURCE_RE = re.compile(r'^resource\s+"(?P<type>[\w]+)"\s+"(?P<name>[\w]+)"\s*\{', re.M)


def _blocks(text: str) -> list[tuple[str, str, str]]:
    """(type, name, body) for each top-level resource block, by brace matching."""
    out = []
    for m in RESOURCE_RE.finditer(text):
        depth, i = 0, m.end() - 1
        while i < len(text):
            if text[i] == "{":
                depth += 1
            elif text[i] == "}":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        out.append((m.group("type"), m.group("name"), text[m.end():i]))
    return out


def _candidates(gitops: Path, needle: str):
    """Files that can possibly matter, by a byte search before any YAML parse.

    Parsing every manifest in gitops twice per scan, six scans per self-test, cost 44s on a
    hosted runner (budget 30s). A file that does not contain the needle cannot declare a honey
    SA or a binding, so skipping it changes no verdict, only the time.
    """
    raw = needle.encode()
    for path in sorted(gitops.rglob("*.yaml")):
        try:
            if raw in path.read_bytes():
                yield path
        except OSError:
            continue


def honey_sas(gitops: Path) -> set[tuple[str, str]]:
    found = set()
    for path in _candidates(gitops, LABEL):
        try:
            docs = list(yaml.safe_load_all(path.read_text(encoding="utf-8")))
        except yaml.YAMLError:
            continue
        for d in docs:
            if isinstance(d, dict) and d.get("kind") == "ServiceAccount":
                meta = d.get("metadata") or {}
                if (meta.get("labels") or {}).get(LABEL) == "honeytoken":
                    found.add((str(meta.get("namespace", "")), str(meta.get("name", ""))))
    return found


def bound_subjects(gitops: Path) -> set[tuple[str, str]]:
    found = set()
    for path in _candidates(gitops, "Binding"):
        try:
            docs = list(yaml.safe_load_all(path.read_text(encoding="utf-8")))
        except yaml.YAMLError:
            continue
        for d in docs:
            if isinstance(d, dict) and d.get("kind") in ("RoleBinding", "ClusterRoleBinding"):
                for s in d.get("subjects") or []:
                    if s.get("kind") == "ServiceAccount":
                        found.add((str(s.get("namespace", "")), str(s.get("name", ""))))
    return found


def honey_path_workloads(gitops: Path) -> list[str]:
    """Workload containers that set a non-empty honey-path env."""
    found = []
    for path in _candidates(gitops, HONEY_ENV):
        try:
            docs = list(yaml.safe_load_all(path.read_text(encoding="utf-8")))
        except yaml.YAMLError:
            continue
        for d in docs:
            if not isinstance(d, dict):
                continue
            spec = ((d.get("spec") or {}).get("template") or {}).get("spec") or {}
            for c in spec.get("containers") or []:
                for e in c.get("env") or []:
                    if e.get("name") == HONEY_ENV and str(e.get("value") or "").strip(","):
                        found.append(f"{(d.get('metadata') or {}).get('name')}/{c.get('name')}")
    return found


def honey_rule_present(gitops: Path) -> bool:
    return any(True for _ in _candidates(gitops, "alert: HoneyEndpointHit"))


def scan(gitops: Path, tofu: Path, filter_src: Path = FILTER) -> tuple[list[str], int]:
    findings: list[str] = []
    text = "\n".join(p.read_text(encoding="utf-8") for p in sorted(tofu.glob("*.tf")))
    blocks = _blocks(text)

    sas = honey_sas(gitops)
    wired = {
        (m.group("ns"), m.group("name"))
        for t, _, body in blocks if t == "aws_cloudwatch_log_metric_filter"
        for m in FILTER_SA_RE.finditer(body)
    }
    for ns, name in sorted(sas - wired):
        findings.append(f"honey ServiceAccount {ns}/{name} has no metric filter naming it — unwired lure")
    for ns, name in sorted(wired - sas):
        findings.append(f"metric filter names {ns}/{name}, which is not a declared honey ServiceAccount — tripwire on nothing")
    for ns, name in sorted(sas & bound_subjects(gitops)):
        findings.append(f"honey ServiceAccount {ns}/{name} is a subject of a RoleBinding/ClusterRoleBinding — the lure is armed")

    users = [n for t, n, _ in blocks if t == "aws_iam_user"]
    if "canary" not in users:
        findings.append("aws_iam_user.canary not found in the substrate root")
    else:
        ref = "aws_iam_user.canary."
        inline = [b for t, _, b in blocks if t == "aws_iam_user_policy" and ref in b]
        if len(inline) != 1:
            findings.append(f"canary user must carry exactly one inline policy, found {len(inline)}")
        elif not re.search(r'Effect\s*=\s*"Deny"', inline[0]) or not re.search(r'Action\s*=\s*"\*"', inline[0]):
            findings.append("canary user's inline policy is not an explicit Deny on Action \"*\"")
        for t, n, b in blocks:
            if t in ("aws_iam_user_policy_attachment", "aws_iam_user_group_membership",
                     "aws_iam_group_membership", "aws_iam_policy_attachment") and ref in b:
                findings.append(f"{t}.{n} grants the canary user something — the lure is armed")
        rules = [b for t, _, b in blocks if t == "aws_cloudwatch_event_rule" and "canary_event_pattern" in b]
        if not rules:
            findings.append("no EventBridge rule uses the canary event pattern")
        for b in rules:
            if "ENABLED_WITH_ALL_CLOUDTRAIL_MANAGEMENT_EVENTS" not in b:
                findings.append("a canary EventBridge rule does not opt in to read-only management events — sts:GetCallerIdentity would never alert")
    workloads = honey_path_workloads(gitops)
    if honey_rule_present(gitops) and not workloads:
        findings.append(f"HoneyEndpointHit exists but no workload sets {HONEY_ENV} — an alert with no subject")
    if workloads:
        src = filter_src.read_text(encoding="utf-8") if filter_src.exists() else ""
        if not re.search(r"^@PreMatching\s*$", src, re.M):
            findings.append("HoneytokenFilter is not @PreMatching — it is never called for an unrouted honey path")
    return findings, len(sas) + len(users) + len(workloads)


def selftest() -> int:
    import shutil
    import tempfile

    clean, subjects = scan(GITOPS, TOFU)
    if subjects < 2:
        print("SELFTEST FAIL: fewer than two subjects — nothing to check")
        return 1
    if clean:
        print("SELFTEST FAIL: committed tree is not clean, a negative control proves nothing")
        for f in clean:
            print("   ", f)
        return 1
    cases = {
        "unwired SA": (
            "gitops", "components/platform/honey-serviceaccount.yaml",
            lambda s: s.replace("name: deploy-prod\n  namespace", "name: deploy-prod-renamed\n  namespace", 1),
            "no metric filter naming it"),
        "armed SA": (
            "gitops", "components/platform/honey-serviceaccount.yaml",
            lambda s: s + "\n---\napiVersion: rbac.authorization.k8s.io/v1\nkind: RoleBinding\nmetadata:\n  name: x\n  namespace: arc-runners\nroleRef:\n  apiGroup: rbac.authorization.k8s.io\n  kind: ClusterRole\n  name: view\nsubjects:\n  - kind: ServiceAccount\n    name: deploy-prod\n    namespace: arc-runners\n",
            "the lure is armed"),
        "armed IAM user": (
            "tofu", "deception.tf",
            lambda s: s + '\nresource "aws_iam_user_policy_attachment" "oops" {\n  user       = aws_iam_user.canary.name\n  policy_arn = "arn:aws:iam::aws:policy/ReadOnlyAccess"\n}\n',
            "grants the canary user"),
        "deny weakened": (
            "tofu", "deception.tf",
            lambda s: s.replace('Effect   = "Deny"', 'Effect   = "Allow"', 1),
            "not an explicit Deny"),
        "read-only opt-in dropped": (
            "tofu", "deception.tf",
            lambda s: s.replace('state         = "ENABLED_WITH_ALL_CLOUDTRAIL_MANAGEMENT_EVENTS"', 'state         = "ENABLED"', 1),
            "read-only management events"),
        "honey paths unset": (
            "gitops", "components/customer-edge/customer-edge.yaml",
            lambda s: s.replace(f"name: {HONEY_ENV}", "name: OPENBANK_SECURITY_HONEY_PATHS_DISABLED", 1),
            "an alert with no subject"),
        "filter post-matching": (
            "filter", "HoneytokenFilter.kt",
            lambda s: s.replace("\n@PreMatching\n", "\n", 1),
            "not @PreMatching"),
    }
    for label, (tree, rel, mutate, expect) in cases.items():
        with tempfile.TemporaryDirectory() as tmp:
            g, t = Path(tmp) / "gitops", Path(tmp) / "tofu"
            for comp in ("platform", "customer-edge", "observability"):
                shutil.copytree(GITOPS / "components" / comp, g / "components" / comp)
            shutil.copytree(TOFU, t, ignore=shutil.ignore_patterns(".terraform", "lambda"))
            k = Path(tmp) / "HoneytokenFilter.kt"
            shutil.copy(FILTER, k)
            target = {"gitops": g, "tofu": t, "filter": Path(tmp)}[tree] / rel
            target.write_text(mutate(target.read_text(encoding="utf-8")), encoding="utf-8")
            bad, _ = scan(g, t, k)
            if not any(expect in f for f in bad):
                print(f"SELFTEST FAIL: case '{label}' was not reported (got {bad})")
                return 1
    print(f"check-honeytoken-wiring --selftest: OK — clean tree, and all {len(cases)} sabotage cases reported")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true")
    if ap.parse_args().selftest:
        return selftest()
    findings, subjects = scan(GITOPS, TOFU)
    print(f"SUBJECTS={subjects}  # honey ServiceAccounts + IAM users + honey-path workloads")
    for f in findings:
        print(f"::error::{f}")
    if findings:
        return 1
    print("check-honeytoken-wiring: OK — every honeytoken is wired to a tripwire and holds no permission")
    return 0


if __name__ == "__main__":
    sys.exit(main())
