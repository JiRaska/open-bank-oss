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

The CLIENT side has the same defect one layer up: a named `quarkus.tls.<name>` config with a
`key-store` (the outbound mTLS identity a service presents to a peer's 8443) is read once
unless it declares `reload-period`. After renewal the peer still accepts the old client
certificate until it expires, then every call over that config fails. Measured on main
2026-11-15: 49 named configs with a key-store, 4 with a reload period.
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


SERVICE_YAML = "openbank-*/src/main/resources/application.yaml"


def named_tls_findings(root: Path) -> tuple[int, list[str]]:
    """Every named quarkus.tls config with a key-store, in any profile, must set reload-period."""
    subjects, out = 0, []
    for path in sorted(root.glob(SERVICE_YAML)):
        try:
            doc = yaml.safe_load(path.read_text()) or {}
        except yaml.YAMLError:
            continue
        scopes = [("base", doc)] + [(k, v) for k, v in doc.items() if isinstance(k, str) and k.startswith("%")]
        for profile, scope in scopes:
            tls = ((scope or {}).get("quarkus") or {}).get("tls") if isinstance(scope, dict) else None
            if not isinstance(tls, dict):
                continue
            for name, cfg in tls.items():
                if not isinstance(cfg, dict) or "key-store" not in cfg:
                    continue
                subjects += 1
                if "reload-period" not in cfg:
                    out.append(f"{path.relative_to(root)}: {profile} quarkus.tls.{name} has a key-store without reload-period")
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
        svc = Path(d) / "openbank-x/src/main/resources"
        svc.mkdir(parents=True)
        (svc / "application.yaml").write_text(
            '"%prod":\n  quarkus:\n    tls:\n'
            '      ok-authority: {reload-period: 1h, key-store: {pem: {}}}\n'
            '      bad-authority: {key-store: {pem: {}}}\n'
            '      trust-only: {trust-store: {pem: {}}}\n')
        n, f = named_tls_findings(Path(d))
        if n != 2 or len(f) != 1 or "bad-authority" not in f[0]:
            print(f"FAIL: named client TLS misjudged (subjects={n}, findings={f})")
            return 1
    print("self-test OK: compliant passes, missing reload period is flagged (containers and named client TLS)")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--min-subjects", type=int, default=20)  # per family
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    n, f = findings(ROOT)
    cn, cf = named_tls_findings(ROOT)
    if cn < a.min_subjects:
        print(f"::error::only {cn} named client TLS configs found (min {a.min_subjects}) — the scan measured nothing")
        return 1
    f += cf
    print(f"named client TLS configs: {cn}, {len(cf)} without reload-period")
    n += cn
    if n < a.min_subjects:
        print(f"::error::only {n} TLS-serving containers found (min {a.min_subjects}) — the scan measured nothing")
        return 1
    print(f"SUBJECTS={n}")
    for line in f:
        print(f"::error::{line}")
    print(f"check-tls-certificate-reload: {n} subject(s) (containers + named client TLS), {len(f)} without a reload period")
    return 1 if f else 0


if __name__ == "__main__":
    sys.exit(main())
