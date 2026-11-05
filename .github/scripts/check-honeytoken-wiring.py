#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""
Decoy credentials must stay wired to a tripwire, and must stay powerless (ADR-0361, phase 1).

A decoy credential fails in two silent ways, and this gate catches both.

1. UNWIRED. The decoy exists but nothing listens: rename it and the metric filter keeps
   matching the old name, so the credential works and alerts nobody. That is the ADR-0279
   failure shape (a rule over a filter no service configured), invisible because "no alert"
   is also what a quiet week looks like.

2. ARMED. Someone binds the decoy ServiceAccount to a Role, or attaches a policy or group to
   the decoy IAM user, and it becomes a real credential. The design rests on it being useless.

The decoys are found by SHAPE, not by a name list kept in this file:
  - a decoy ServiceAccount is a ServiceAccount that has a long-lived token Secret
    (type kubernetes.io/service-account-token) declared next to it in gitops;
  - a decoy IAM user is the user that owns an access key matched by an EventBridge rule's
    event pattern in the substrate root.

Checks:
  - every decoy ServiceAccount has a CloudWatch metric filter in the substrate root whose
    pattern names it, and every ServiceAccount a filter names exists in gitops with that shape;
  - no RoleBinding or ClusterRoleBinding anywhere in gitops has a decoy ServiceAccount as subject;
  - the decoy IAM user has exactly one inline policy, it is an explicit Deny "*", and no policy
    attachment or group membership references it;
  - the EventBridge rules opt in to read-only management events (without that, the attacker's
    first call, sts:GetCallerIdentity, never reaches the rule).

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
    hosted runner (budget 30s). A file that does not contain the needle cannot declare a decoy
    SA or a binding, so skipping it changes no verdict, only the time.
    """
    raw = needle.encode()
    for path in sorted(gitops.rglob("*.yaml")):
        try:
            if raw in path.read_bytes():
                yield path
        except OSError:
            continue


def service_accounts(gitops: Path) -> set[tuple[str, str]]:
    found = set()
    for path in _candidates(gitops, "ServiceAccount"):
        try:
            docs = list(yaml.safe_load_all(path.read_text(encoding="utf-8")))
        except yaml.YAMLError:
            continue
        for d in docs:
            if isinstance(d, dict) and d.get("kind") == "ServiceAccount":
                meta = d.get("metadata") or {}
                found.add((str(meta.get("namespace", "")), str(meta.get("name", ""))))
    return found


def token_shaped_service_accounts(gitops: Path) -> set[tuple[str, str]]:
    """ServiceAccounts that have a long-lived token Secret declared in gitops.

    Nothing in the fleet needs one: workloads use projected, expiring tokens. A token Secret
    is therefore the shape of a standing credential left to be found.
    """
    found = set()
    for path in _candidates(gitops, "service-account-token"):
        try:
            docs = list(yaml.safe_load_all(path.read_text(encoding="utf-8")))
        except yaml.YAMLError:
            continue
        for d in docs:
            if isinstance(d, dict) and d.get("kind") == "Secret" and d.get("type") == "kubernetes.io/service-account-token":
                meta = d.get("metadata") or {}
                sa = (meta.get("annotations") or {}).get("kubernetes.io/service-account.name")
                if sa:
                    found.add((str(meta.get("namespace", "")), str(sa)))
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


def scan(gitops: Path, tofu: Path) -> tuple[list[str], int]:
    findings: list[str] = []
    text = "\n".join(p.read_text(encoding="utf-8") for p in sorted(tofu.glob("*.tf")))
    blocks = _blocks(text)

    existing = service_accounts(gitops)
    sas = token_shaped_service_accounts(gitops) & existing
    wired = {
        (m.group("ns"), m.group("name"))
        for t, _, body in blocks if t == "aws_cloudwatch_log_metric_filter"
        for m in FILTER_SA_RE.finditer(body)
    }
    for ns, name in sorted(wired - existing):
        findings.append(f"metric filter names ServiceAccount {ns}/{name}, which does not exist in gitops — renamed or removed")
    for ns, name in sorted((wired & existing) - sas):
        findings.append(f"metric filter names ServiceAccount {ns}/{name}, which has no token Secret — tripwire on nothing")
    for ns, name in sorted(sas - wired):
        findings.append(f"token-bearing ServiceAccount {ns}/{name} has no metric filter naming it — unwired")
    if not wired:
        findings.append("no metric filter names a ServiceAccount — the cluster tripwire is gone")
    for path in _candidates(gitops, "openbank.io/deception"):
        findings.append(f"{path.relative_to(gitops)} carries an openbank.io/deception marker — it marks the object for an attacker")
    for ns, name in sorted(sas & bound_subjects(gitops)):
        findings.append(f"ServiceAccount {ns}/{name} is a subject of a RoleBinding/ClusterRoleBinding — armed")

    text_all = text
    rule_locals = set()
    for t, _, body in blocks:
        if t != "aws_cloudwatch_event_rule":
            continue
        m = re.search(r'event_pattern\s*=\s*local\.(\w+)', body)
        if m:
            pos = re.search(r'\b' + m.group(1) + r'\s*=', text_all)
            if pos and "accessKeyId" in text_all[pos.end():pos.end() + 400]:
                rule_locals.add(m.group(1))
    key_names = set(re.findall(r'accessKeyId\s*=\s*\[aws_iam_access_key\.(\w+)\.id\]', text_all))
    users: set[str] = set()
    for t, n, b in blocks:
        if t == "aws_iam_access_key" and n in key_names:
            m = re.search(r'user\s*=\s*aws_iam_user\.(\w+)\.name', b)
            if m:
                users.add(m.group(1))
    if not users:
        findings.append("no IAM user owns an access key matched by an EventBridge rule — the cloud tripwire is gone")
    if not rule_locals:
        findings.append("no EventBridge rule matches on an access key id")
    for u in sorted(users):
        ref = f"aws_iam_user.{u}."
        inline = [b for t, _, b in blocks if t == "aws_iam_user_policy" and ref in b]
        if len(inline) != 1:
            findings.append(f"watched IAM user must carry exactly one inline policy, found {len(inline)}")
        elif not re.search(r'Effect\s*=\s*"Deny"', inline[0]) or not re.search(r'Action\s*=\s*"\*"', inline[0]):
            findings.append("watched IAM user's inline policy is not an explicit Deny on Action \"*\"")
        for t, n, b in blocks:
            if t in ("aws_iam_user_policy_attachment", "aws_iam_user_group_membership",
                     "aws_iam_group_membership", "aws_iam_policy_attachment") and ref in b:
                findings.append(f"{t}.{n} grants the watched IAM user something — armed")
    for t, _, b in blocks:
        if t == "aws_cloudwatch_event_rule" and any(f"local.{l}" in b for l in rule_locals):
            if "ENABLED_WITH_ALL_CLOUDTRAIL_MANAGEMENT_EVENTS" not in b:
                findings.append("an access-key EventBridge rule does not opt in to read-only management events — sts:GetCallerIdentity would never alert")
    return findings, len(sas) + len(users)


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
    sa_file = next(iter(_candidates(GITOPS, "service-account-token")))
    rel_sa = sa_file.relative_to(GITOPS).as_posix()
    ns, name = sorted(token_shaped_service_accounts(GITOPS) & service_accounts(GITOPS))[0]
    tf_text = "\n".join(p.read_text(encoding="utf-8") for p in sorted(TOFU.glob("*.tf")))
    user = re.search(r'accessKeyId\s*=\s*\[aws_iam_access_key\.(\w+)\.id\]', tf_text).group(1)
    iam_user = re.search(r'resource\s+"aws_iam_access_key"\s+"' + user + r'"\s*\{[^}]*?user\s*=\s*aws_iam_user\.(\w+)\.name', tf_text).group(1)
    pattern = f"system:serviceaccount:{ns}:{name}"
    cases = {
        "renamed SA": (
            "gitops", rel_sa,
            lambda s: s.replace(f"name: {name}\n", f"name: {name}-renamed\n", 1).replace(f"service-account.name: {name}\n", f"service-account.name: {name}-renamed\n"),
            "does not exist in gitops"),
        "unwired SA": (
            "tofu", "deception.tf",
            lambda s: s.replace(pattern, f"system:serviceaccount:{ns}:other", 1),
            "does not exist in gitops"),
        "tripwire removed": (
            "tofu", "deception.tf",
            lambda s: s.replace(pattern, "x", 1),
            "no metric filter names a ServiceAccount"),
        "marker label back on the object": (
            "gitops", rel_sa,
            lambda s: s.replace("    app.kubernetes.io/part-of: openbank-ci\n", "    app.kubernetes.io/part-of: openbank-ci\n    openbank.io/deception: marker\n", 1),
            "marks the object"),
        "armed SA": (
            "gitops", rel_sa,
            lambda s: s + f"\n---\napiVersion: rbac.authorization.k8s.io/v1\nkind: RoleBinding\nmetadata:\n  name: x\n  namespace: {ns}\nroleRef:\n  apiGroup: rbac.authorization.k8s.io\n  kind: ClusterRole\n  name: view\nsubjects:\n  - kind: ServiceAccount\n    name: {name}\n    namespace: {ns}\n",
            "armed"),
        "armed IAM user": (
            "tofu", "deception.tf",
            lambda s: s + f'\nresource "aws_iam_user_policy_attachment" "oops" {{\n  user       = aws_iam_user.{iam_user}.name\n  policy_arn = "arn:aws:iam::aws:policy/ReadOnlyAccess"\n}}\n',
            "grants the watched IAM user"),
        "deny weakened": (
            "tofu", "deception.tf",
            lambda s: s.replace('Effect   = "Deny"', 'Effect   = "Allow"', 1),
            "not an explicit Deny"),
        "read-only opt-in dropped": (
            "tofu", "deception.tf",
            lambda s: s.replace('state         = "ENABLED_WITH_ALL_CLOUDTRAIL_MANAGEMENT_EVENTS"', 'state         = "ENABLED"', 1),
            "read-only management events"),
    }
    for label, (tree, rel, mutate, expect) in cases.items():
        with tempfile.TemporaryDirectory() as tmp:
            g, t = Path(tmp) / "gitops", Path(tmp) / "tofu"
            shutil.copytree(GITOPS / "components" / "platform", g / "components" / "platform")
            shutil.copytree(TOFU, t, ignore=shutil.ignore_patterns(".terraform", "lambda"))
            target = (g if tree == "gitops" else t) / rel
            target.write_text(mutate(target.read_text(encoding="utf-8")), encoding="utf-8")
            bad, _ = scan(g, t)
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
    print(f"SUBJECTS={subjects}  # token-bearing ServiceAccounts + watched IAM users in the substrate root")
    for f in findings:
        print(f"::error::{f}")
    if findings:
        return 1
    print("check-honeytoken-wiring: OK — every decoy credential is wired to a tripwire and holds no permission")
    return 0


if __name__ == "__main__":
    sys.exit(main())
