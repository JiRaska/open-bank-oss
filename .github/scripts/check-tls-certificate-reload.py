#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Every workload that serves TLS from a mounted certificate file must reload it.

cert-manager renews an internal leaf 15 days before it expires (duration 2160h, renewBefore
360h) and writes the new pair into the mounted Secret. Quarkus reads
QUARKUS_HTTP_SSL_CERTIFICATE_FILES ONCE at start unless
QUARKUS_HTTP_SSL_CERTIFICATE_RELOAD_PERIOD is set. A pod that outlives the renewal window
keeps serving the old certificate until it expires, while cert-manager, the Secret and the
expiry metric all report a fresh one: every signal is green and every mTLS caller fails.

Measured on main 2026-11-15: 26 serving containers, 2 with a reload period (#16292).

The check is per CONTAINER, not per file: payments-services.yaml carries three.
"""
from __future__ import annotations

import argparse
import sys
import tempfile
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
COMPONENTS = Path("openbank-infra/gitops/components")
FILES = "QUARKUS_HTTP_SSL_CERTIFICATE_FILES"
RELOAD = "QUARKUS_HTTP_SSL_CERTIFICATE_RELOAD_PERIOD"
WORKLOAD_KINDS = {"Deployment", "StatefulSet", "Rollout", "DaemonSet", "Job", "CronJob"}


def containers(doc: dict):
    spec = doc.get("spec") or {}
    if doc.get("kind") == "CronJob":
        spec = ((spec.get("jobTemplate") or {}).get("spec")) or {}
    pod = ((spec.get("template") or {}).get("spec")) or {}
    yield from (pod.get("containers") or [])
    yield from (pod.get("initContainers") or [])


def findings(root: Path) -> tuple[int, list[str]]:
    subjects, out = 0, []
    for path in sorted((root / COMPONENTS).rglob("*.yaml")):
        text = path.read_text()
        if FILES not in text:
            continue  # cheap prefilter: only files that mention the variable can hold a subject
        try:
            docs = list(yaml.safe_load_all(text))
        except yaml.YAMLError:
            continue  # yamllint owns syntax
        for doc in docs:
            if not isinstance(doc, dict) or doc.get("kind") not in WORKLOAD_KINDS:
                continue
            for c in containers(doc):
                names = {e.get("name") for e in (c.get("env") or []) if isinstance(e, dict)}
                if FILES not in names:
                    continue
                subjects += 1
                if RELOAD not in names:
                    rel = path.relative_to(root)
                    out.append(f"{rel}: {doc['kind']}/{doc.get('metadata', {}).get('name')} "
                               f"container {c.get('name')} sets {FILES} without {RELOAD}")
    return subjects, out


def self_test() -> int:
    good = """apiVersion: apps/v1
kind: Deployment
metadata: {name: ok}
spec:
  template:
    spec:
      containers:
        - name: app
          env:
            - {name: QUARKUS_HTTP_SSL_CERTIFICATE_FILES, value: /mnt/t/tls.crt}
            - {name: QUARKUS_HTTP_SSL_CERTIFICATE_RELOAD_PERIOD, value: 1h}
"""
    bad = good.replace("name: ok", "name: bad").replace(
        "            - {name: QUARKUS_HTTP_SSL_CERTIFICATE_RELOAD_PERIOD, value: 1h}\n", "")
    with tempfile.TemporaryDirectory() as d:
        comp = Path(d) / COMPONENTS / "x"
        comp.mkdir(parents=True)
        (comp / "ok.yaml").write_text(good)
        n, f = findings(Path(d))
        if n != 1 or f:
            print(f"FAIL: compliant workload misjudged (subjects={n}, findings={f})")
            return 1
        (comp / "bad.yaml").write_text(bad)
        n, f = findings(Path(d))
        if n != 2 or len(f) != 1 or "bad" not in f[0]:
            print(f"FAIL: missing reload period not flagged (subjects={n}, findings={f})")
            return 1
    print("self-test OK: compliant passes, missing reload period is flagged")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--min-subjects", type=int, default=20)
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    n, f = findings(ROOT)
    if n < a.min_subjects:
        print(f"::error::only {n} TLS-serving containers found (min {a.min_subjects}) — the scan measured nothing")
        return 1
    print(f"SUBJECTS={n}")
    for line in f:
        print(f"::error::{line}")
    print(f"check-tls-certificate-reload: {n} TLS-serving container(s), {len(f)} without a reload period")
    return 1 if f else 0


if __name__ == "__main__":
    sys.exit(main())
