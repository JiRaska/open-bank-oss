#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
"""A service whose four-eyes ApprovalStore is Redis-backed must be DEPLOYED with a real Redis host.

Every such service's application.yaml points `quarkus.redis.hosts` at `redis://localhost:6379`
(right for dev and tests), and a pod has no Redis on localhost. So the deployed value has to come
from the gitops workload's `QUARKUS_REDIS_HOSTS`. When it does not, nothing fails at deploy: the
pod is Ready (no probe touches Redis), the bean resolves, and the first call to the approval queue
answers 500 "Connection refused: localhost/127.0.0.1:6379". swift-service and clearing-service
shipped exactly that way while the 16 other services with the same store were configured.

Subjects: every service that uses the Redis store — a `RedisApprovalStore(` construction in its own
src/main (the per-service ApprovalConfig shape), or `openbank.approval.store: redis` in its
application.yaml (the libs-runtime RedisApprovalStoreProducer opt-in). For each, every gitops
Deployment/Rollout/StatefulSet running that service's image must set QUARKUS_REDIS_HOSTS to a
non-loopback host, unless the service's own `%prod` profile already does. A service with no
workload at all is a finding too: "not deployed" and "deployed somewhere this gate cannot see"
must not read the same.

    check-approval-store-redis-host.py              # audit the tree
    check-approval-store-redis-host.py --self-test  # prove the gate can fail
"""
from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gatelib  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
WORKLOAD_KINDS = {"Deployment", "Rollout", "StatefulSet"}
LOOPBACK = re.compile(r"localhost|127\.0\.0\.1|\[::1\]")


def _image_name(image: str) -> str:
    return image.split("@")[0].rsplit("/", 1)[-1].split(":")[0]


def _uses_redis_store(service: Path) -> bool:
    app = service / "src/main/resources/application.yaml"
    if app.is_file():
        try:
            doc = yaml.safe_load(app.read_text()) or {}
        except yaml.YAMLError:
            doc = {}
        store = ((doc.get("openbank") or {}).get("approval") or {}).get("store")
        if store == "redis":
            return True
    src = service / "src/main/kotlin"
    return src.is_dir() and any("RedisApprovalStore(" in p.read_text() for p in src.rglob("*.kt"))


def _prod_redis_host(service: Path) -> str | None:
    app = service / "src/main/resources/application.yaml"
    try:
        doc = yaml.safe_load(app.read_text()) or {}
    except (OSError, yaml.YAMLError):
        return None
    prod = doc.get("%prod") or {}
    return (((prod.get("quarkus") or {}).get("redis") or {}).get("hosts")) or None


def _workloads(gitops: Path) -> dict[str, list[tuple[str, str, str | None]]]:
    """image name -> [(manifest, workload name, QUARKUS_REDIS_HOSTS or None)]"""
    out: dict[str, list[tuple[str, str, str | None]]] = {}
    for manifest in sorted(gitops.rglob("*.yaml")):
        try:
            docs = list(yaml.safe_load_all(manifest.read_text()))
        except yaml.YAMLError:
            continue
        for doc in docs:
            if not isinstance(doc, dict) or doc.get("kind") not in WORKLOAD_KINDS:
                continue
            pod = ((doc.get("spec") or {}).get("template") or {}).get("spec") or {}
            for c in pod.get("containers") or []:
                env = {e.get("name"): e.get("value") for e in c.get("env") or [] if isinstance(e, dict)}
                out.setdefault(_image_name(str(c.get("image", ""))), []).append(
                    (str(manifest), (doc.get("metadata") or {}).get("name", "?"), env.get("QUARKUS_REDIS_HOSTS"))
                )
    return out


def audit(repo: Path) -> tuple[list[str], int]:
    services = [p for p in sorted(repo.glob("openbank-*")) if p.is_dir() and not p.name.startswith("openbank-libs")]
    subjects = [s for s in services if _uses_redis_store(s)]
    workloads = _workloads(repo / "openbank-infra/gitops")
    findings: list[str] = []
    for s in subjects:
        prod = _prod_redis_host(s)
        if prod and not LOOPBACK.search(str(prod)):
            continue
        deployed = workloads.get(s.name)
        if not deployed:
            findings.append(f"{s.name}: uses the Redis ApprovalStore but no gitops workload runs its image")
            continue
        for manifest, name, host in deployed:
            if not host or LOOPBACK.search(host):
                shown = host or "unset"
                rel = Path(manifest).relative_to(repo) if Path(manifest).is_absolute() else manifest
                findings.append(f"{s.name}: {rel} workload '{name}' has QUARKUS_REDIS_HOSTS {shown}")
    return findings, len(subjects)


def _fixture(root: Path, env_value: str | None, prod_host: str | None = None, store: bool = True) -> None:
    svc = root / "openbank-demo-service"
    (svc / "src/main/resources").mkdir(parents=True)
    app = "openbank:\n  approval:\n    store: redis\n" if store else "quarkus:\n  application:\n    name: demo\n"
    if prod_host:
        app += f'"%prod":\n  quarkus:\n    redis:\n      hosts: {prod_host}\n'
    (svc / "src/main/resources/application.yaml").write_text(app)
    env = f"            - name: QUARKUS_REDIS_HOSTS\n              value: {env_value}\n" if env_value else ""
    gitops = root / "openbank-infra/gitops/components/demo"
    gitops.mkdir(parents=True)
    (gitops / "demo.yaml").write_text(
        "apiVersion: argoproj.io/v1alpha1\nkind: Rollout\nmetadata:\n  name: demo-service\nspec:\n"
        "  template:\n    spec:\n      containers:\n        - name: app\n"
        "          image: ghcr.io/x/openbank-demo-service:1.0.0\n          env:\n"
        "            - name: OTHER\n              value: x\n" + env
    )


def self_test() -> int:
    cases = [
        ("env unset (the swift/clearing defect)", None, None, True, 1),
        ("env pointing at loopback", "redis://localhost:6379", None, True, 1),
        ("env pointing at the namespace Redis", "redis://redis.payments.svc:6379", None, True, 0),
        ("%prod profile supplies a real host", None, "redis://redis.demo.svc:6379", True, 0),
        ("service without the Redis store is not a subject", None, None, False, 0),
    ]
    failed = False
    for label, env, prod, store, expected in cases:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _fixture(root, env, prod, store)
            findings, _ = audit(root)
            if len(findings) != expected:
                print(f"self-test: FAIL — {label}: expected {expected} finding(s), got {findings}")
                failed = True
    if failed:
        return 1
    print("self-test: PASS — fires on an unset or loopback host, silent on a real host and on non-subjects")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true")
    if ap.parse_args().self_test:
        return self_test()
    findings, examined = audit(REPO)
    gatelib.subjects(examined, "services using the Redis-backed four-eyes ApprovalStore")
    if findings:
        print("Redis-backed ApprovalStore deployed without a reachable Redis host:\n", file=sys.stderr)
        for f in findings:
            print(f"  {f}", file=sys.stderr)
        print(
            f"\n{len(findings)} finding(s). Set QUARKUS_REDIS_HOSTS on the workload to the namespace's "
            "Redis Service (see sepa-payment in components/payments/payments-services.yaml).",
            file=sys.stderr,
        )
        return 1
    print("OK: every Redis-backed ApprovalStore service is deployed with a non-loopback Redis host.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
