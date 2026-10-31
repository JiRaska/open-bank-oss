#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""A tofu-managed image ref must already be the ECR pull-through path Kyverno would rewrite it to.

WHY THIS EXISTS
---------------
Kyverno's MutatingPolicy `ecr-pull-through-rewrite-cel`
(openbank-infra/gitops/components/kyverno/ecr-pull-through-rewrite-cel.yaml) rewrites every
`quay.io/`, `registry.k8s.io/`, `public.ecr.aws/`, `docker.io/` and `ghcr.io/` image ref to the
private ECR pull-through path at admission — on Pods AND, via autogen, on Deployments,
DaemonSets, StatefulSets, ReplicaSets, Jobs and CronJobs.

When OpenTofu owns one of those controllers (`kubernetes_cron_job_v1`, `kubernetes_deployment_v1`,
...) and writes the ORIGIN ref, the live object holds the rewritten ref, so every `tofu plan`
shows an in-place update reverting Kyverno, and every apply is rewritten straight back: a
perpetual diff that trains the reader to ignore the plan. `kubernetes_cron_job_v1.arc_reaper`
did exactly this. The fix is to write the pull-through path in tofu (derived from the account,
region and the `aws_ecr_pull_through_cache_rule` prefix); the policy is idempotent on it.

WHAT IT CHECKS
--------------
Every quoted string in `openbank-infra/**/*.tf` that starts with one of the five origin
prefixes (chart repositories, `oci://` / `https://`, are not image refs and do not match) is a
finding, unless its line carries `# pull-through-exempt: <reason>`. The exemption is for refs
that tofu writes into an object Kyverno does NOT mutate — e.g. Helm values rendered into an ARC
AutoscalingRunnerSet custom resource, where only the runner Pod (which tofu never tracks) is
rewritten. The reason is mandatory: an empty marker is itself a finding.

Usage:  check-tofu-image-pull-through.py [--enforce] [--self-test]
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCAN_ROOT = ROOT / "openbank-infra"
ORIGIN_PREFIXES = ("quay.io/", "registry.k8s.io/", "public.ecr.aws/", "docker.io/", "ghcr.io/")
_REF = re.compile(r'"((?:' + "|".join(re.escape(p) for p in ORIGIN_PREFIXES) + r')[^"]*)"')
_EXEMPT = re.compile(r"#\s*pull-through-exempt:\s*(\S.*)?$")


def scan_text(text: str) -> list[tuple[int, str]]:
    findings: list[tuple[int, str]] = []
    for n, line in enumerate(text.splitlines(), 1):
        m = _REF.search(line.split("#", 1)[0])
        if not m:
            continue
        ex = _EXEMPT.search(line)
        if ex and ex.group(1):
            continue
        reason = "exempt marker has no reason" if ex else "origin image ref; write the ECR pull-through path"
        findings.append((n, f"{m.group(1)} — {reason}"))
    return findings


def tf_files() -> list[pathlib.Path]:
    return sorted(p for p in SCAN_ROOT.rglob("*.tf") if ".terraform" not in p.parts)


def self_test() -> int:
    cases = [
        ('  img = "public.ecr.aws/docker/library/alpine:3.21.3"\n', 1),
        ('  image = "quay.io/foo/bar:1"\n', 1),
        ('  image = "ghcr.io/x/y:1" # pull-through-exempt:\n', 1),
        ('  image = "docker.io/library/nginx:1" # pull-through-exempt: CR, not mutated\n', 0),
        ('  img = "${local.acct}.dkr.ecr.eu-north-1.amazonaws.com/ecr-public/docker/library/alpine:3"\n', 0),
        ('  repository = "oci://ghcr.io/actions/actions-runner-controller-charts"\n', 0),
        ('  repository = "https://charts.jetstack.io"\n', 0),
        ('  # was "public.ecr.aws/docker/library/alpine:3" before the fix\n', 0),
    ]
    bad = 0
    for i, (text, want) in enumerate(cases, 1):
        got = len(scan_text(text))
        ok = got == want
        bad += not ok
        print(f"  case {i}: {'ok' if ok else 'FAIL'} (want {want}, got {got})")
    print(f"self-test: {len(cases) - bad}/{len(cases)} passed")
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    files = tf_files()
    total = 0
    for f in files:
        for n, msg in scan_text(f.read_text(encoding="utf-8")):
            total += 1
            print(f"{f.relative_to(ROOT)}:{n}: {msg}")
    print(f"SUBJECTS={len(files)}")
    print(f"{total} finding(s) across {len(files)} .tf files")
    return 1 if (total and args.enforce) else 0


if __name__ == "__main__":
    sys.exit(main())
