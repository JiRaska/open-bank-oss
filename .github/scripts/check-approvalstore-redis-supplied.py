#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Every service that PRODUCES an ApprovalStore (four-eyes, ADR-0155) must be deployed with a
# non-localhost Redis host.
#
# THE MECHANISM
#   The producer injects ReactiveRedisDataSource, and the service's application.yaml keeps
#   `quarkus.redis.hosts: redis://localhost:6379` for local dev. A pod that does not set
#   QUARKUS_REDIS_HOSTS therefore boots fine (health probes never touch Redis) and every
#   GET/PATCH on its approvals endpoints answers 500 "Connection refused: localhost/127.0.0.1:6379".
#   swift-service and clearing-service ran that way for 7 days.
#
# WHAT THIS CHECKS
#   For each src/main file declaring an `ApprovalStore` producer, find every gitops workload whose
#   container image is `openbank-<service>:` and require its env to carry QUARKUS_REDIS_HOSTS with a
#   literal value that is not localhost/127.0.0.1/::1 (or a valueFrom reference, which we cannot
#   resolve statically and so trust). A service with no gitops workload is reported, not skipped.
#
# EXIT CODES  0 clean · 1 violation · 2 could not run (PyYAML missing, nothing scanned)
#
# Run: python3 .github/scripts/check-approvalstore-redis-supplied.py [--root .] [--self-test]
import argparse
import pathlib
import re
import sys
import tempfile

try:
    import yaml
except ImportError:  # pragma: no cover
    print("ERROR: PyYAML missing", file=sys.stderr)
    sys.exit(2)

LOCAL = re.compile(r"(localhost|127\.0\.0\.1|\[?::1\]?)")
PRODUCER = re.compile(r"fun\s+\w+\([^)]*\)\s*:\s*ApprovalStore\b")
IMAGE = re.compile(r"/openbank-([a-z0-9-]+):")


def producers(root):
    out = {}
    for kt in root.glob("openbank-*/src/main/kotlin/**/*.kt"):
        if "libs" in kt.parts[len(root.parts)]:
            continue
        try:
            if PRODUCER.search(kt.read_text()):
                out[kt.relative_to(root).parts[0].removeprefix("openbank-")] = kt
        except UnicodeDecodeError:
            pass
    return out


def containers(doc):
    if isinstance(doc, dict):
        if isinstance(doc.get("image"), str) and "env" in doc or (isinstance(doc.get("image"), str) and "name" in doc):
            yield doc
        for v in doc.values():
            yield from containers(v)
    elif isinstance(doc, list):
        for v in doc:
            yield from containers(v)


def workloads(root):
    """service -> [(file, container name, redis value or None)]"""
    found = {}
    for f in (root / "openbank-infra/gitops").rglob("*.yaml"):
        try:
            docs = list(yaml.safe_load_all(f.read_text()))
        except yaml.YAMLError:
            continue
        for d in docs:
            if not isinstance(d, dict) or d.get("kind") not in ("Rollout", "Deployment"):
                continue
            for c in containers(d):
                m = IMAGE.search(c["image"])
                if not m:
                    continue
                val = None
                for e in c.get("env") or []:
                    if e.get("name") == "QUARKUS_REDIS_HOSTS":
                        val = e.get("value", "<valueFrom>" if "valueFrom" in e else "")
                found.setdefault(m.group(1), []).append((str(f.relative_to(root)), d["metadata"]["name"], val))
    return found


def check(root):
    prod, wl, bad, scanned = producers(root), workloads(root), [], 0
    for svc, kt in sorted(prod.items()):
        if svc not in wl:
            bad.append(f"{svc}: produces ApprovalStore ({kt.relative_to(root)}) but no gitops workload runs its image")
        for f, name, val in wl.get(svc, []):
            scanned += 1
            if not val:
                bad.append(f"{svc}: {f} Rollout/Deployment {name} sets no QUARKUS_REDIS_HOSTS -> falls back to localhost, approvals 500")
            elif LOCAL.search(val):
                bad.append(f"{svc}: {f} {name} QUARKUS_REDIS_HOSTS={val} is a local address")
    return bad, scanned, len(prod)


def self_test():
    def build(env):
        t = pathlib.Path(tempfile.mkdtemp())
        k = t / "openbank-demo-service/src/main/kotlin/x"
        k.mkdir(parents=True)
        (k / "ApprovalConfig.kt").write_text("class A { fun approvalStore(r: R): ApprovalStore = X(r) }")
        g = t / "openbank-infra/gitops/components/demo"
        g.mkdir(parents=True)
        (g / "d.yaml").write_text(yaml.safe_dump({"kind": "Rollout", "metadata": {"name": "demo"}, "spec": {"template": {"spec": {"containers": [{"name": "demo", "image": "1.dkr.ecr/openbank-demo-service:tag", "env": env}]}}}}))
        return t
    good = check(build([{"name": "QUARKUS_REDIS_HOSTS", "value": "redis://redis.payments.svc:6379"}]))
    missing = check(build([{"name": "OTHER", "value": "x"}]))
    local = check(build([{"name": "QUARKUS_REDIS_HOSTS", "value": "redis://localhost:6379"}]))
    assert not good[0] and good[1] == 1, f"known-negative flagged: {good}"
    assert missing[0], "known-positive (env absent) not flagged"
    assert local[0], "known-positive (localhost) not flagged"
    print("self-test OK")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    bad, scanned, nprod = check(pathlib.Path(a.root).resolve())
    if nprod == 0 or scanned == 0:
        print(f"ERROR: scanned nothing (producers={nprod}, workloads={scanned})", file=sys.stderr)
        sys.exit(2)
    print(f"SUBJECTS={nprod}")
    for b in bad:
        print("FAIL", b)
    print(f"{nprod} ApprovalStore producers, {scanned} workloads checked, {len(bad)} violations")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
