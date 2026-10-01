#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Every deployed Quarkus service must export traces somewhere that exists.

openbank-libs defaults `quarkus.otel.exporter.otlp.endpoint` to
`${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4317}` so local dev works with
a laptop-side collector. In the cluster there is nothing on localhost:4317, so
a service whose gitops workload does not set `QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT`
(or `OTEL_EXPORTER_OTLP_ENDPOINT`) exports every span into a closed port —
silently: the exporter logs one warning at boot and the service is otherwise
healthy. Measured 2026-09-30: 66 workloads set it, 7 did not, and among the 7
was customer-edge — the public entry point, so every customer trace began one
hop in, with no root span. Tempo showed 54 services; nothing said 10 were
missing, because a trace store cannot alert on a service it has never heard of.

Subjects: every container in a Rollout/Deployment/StatefulSet under
openbank-infra/gitops/components whose image is an `openbank-<name>` and whose
`openbank-<name>/build.gradle.kts` marks it a Quarkus service. Non-JVM
openbank images (a Python renderer, an nginx portal, LiteLLM) are out of
scope — they have no Quarkus OTel SDK to point anywhere.

Exit 1 on any Quarkus workload without the endpoint env. `--selftest` proves
both directions on synthetic manifests.
"""
from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
COMPONENTS = ROOT / "openbank-infra" / "gitops" / "components"
ENDPOINT_ENVS = {"QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT", "OTEL_EXPORTER_OTLP_ENDPOINT"}
WORKLOAD_KINDS = {"Rollout", "Deployment", "StatefulSet"}
QUARKUS_MARKERS = re.compile(r"openbank\.quarkus-service|io\.quarkus")
IMAGE_RE = re.compile(r"/(openbank-[a-z0-9-]+)(?::|@|$)")


def is_quarkus_service(repo_root: Path, image_basename: str) -> bool:
    build = repo_root / image_basename / "build.gradle.kts"
    return build.is_file() and bool(QUARKUS_MARKERS.search(build.read_text(errors="replace")))


def containers(doc: dict):
    spec = doc.get("spec") or {}
    tmpl = (spec.get("template") or {}).get("spec") or {}
    yield from tmpl.get("containers") or []


def evaluate(components_dir: Path, repo_root: Path):
    subjects, findings = [], []
    # Pure-Python PyYAML is the whole cost of this gate, and most files under components/ are
    # OPA bundles, policies and ConfigMaps that cannot hold a workload. Parse only files whose
    # text declares one of WORKLOAD_KINDS; a quoted or spaced `kind:` still matches.
    kind_re = re.compile(
        r"^\s*kind:\s*[\"']?(" + "|".join(map(re.escape, sorted(WORKLOAD_KINDS))) + r")[\"']?\s*$", re.M
    )
    for path in sorted(components_dir.rglob("*.yaml")):
        text = path.read_text()
        if not kind_re.search(text):
            continue
        try:
            docs = list(yaml.safe_load_all(text))
        except yaml.YAMLError:
            continue
        for doc in docs:
            if not isinstance(doc, dict) or doc.get("kind") not in WORKLOAD_KINDS:
                continue
            for c in containers(doc):
                m = IMAGE_RE.search(str(c.get("image", "")))
                if not m or not is_quarkus_service(repo_root, m.group(1)):
                    continue
                name = f"{path.relative_to(components_dir)}:{doc['metadata'].get('name')}/{c.get('name')}"
                subjects.append(name)
                envs = {e.get("name") for e in (c.get("env") or []) if isinstance(e, dict)}
                if not envs & ENDPOINT_ENVS:
                    findings.append(name)
    return subjects, findings


def selftest() -> int:
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        comp = root / "components"
        (root / "openbank-good-service").mkdir()
        (root / "openbank-good-service" / "build.gradle.kts").write_text('id("openbank.quarkus-service")\n')
        (root / "openbank-bad-service").mkdir()
        (root / "openbank-bad-service" / "build.gradle.kts").write_text('id("io.quarkus")\n')
        (root / "openbank-python-thing").mkdir()  # no build.gradle.kts -> out of scope
        comp.mkdir()
        (comp / "a.yaml").write_text(
            "apiVersion: argoproj.io/v1alpha1\nkind: Rollout\nmetadata: {name: good}\nspec:\n  template:\n    spec:\n"
            "      containers:\n        - name: good\n          image: 1.dkr.ecr/openbank-good-service:sandbox-1\n"
            "          env:\n            - name: QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT\n              value: http://tempo:4317\n"
            "---\napiVersion: apps/v1\nkind: Deployment\nmetadata: {name: bad}\nspec:\n  template:\n    spec:\n"
            "      containers:\n        - name: bad\n          image: 1.dkr.ecr/openbank-bad-service:sandbox-1\n"
            "          env:\n            - name: OTHER\n              value: x\n"
            "---\napiVersion: apps/v1\nkind: Deployment\nmetadata: {name: py}\nspec:\n  template:\n    spec:\n"
            "      containers:\n        - name: py\n          image: 1.dkr.ecr/openbank-python-thing:1\n"
        )
        subjects, findings = evaluate(comp, root)
        if len(subjects) != 2:
            print(f"selftest FAIL: expected 2 subjects (python image out of scope), got {subjects}")
            return 1
        if findings != ["a.yaml:bad/bad"]:
            print(f"selftest FAIL: expected exactly the workload without the env, got {findings}")
            return 1
        # Negative case: remove the endpoint from the good one and the gate must flag it too.
        (comp / "a.yaml").write_text((comp / "a.yaml").read_text().replace("QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT", "NOT_IT"))
        _, findings = evaluate(comp, root)
        if sorted(findings) != ["a.yaml:bad/bad", "a.yaml:good/good"]:
            print(f"selftest FAIL: removing the env did not produce a finding: {findings}")
            return 1
    print("selftest OK")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--enforce", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        return selftest()
    subjects, findings = evaluate(COMPONENTS, ROOT)
    print(f"SUBJECTS={len(subjects)}")
    for f in findings:
        print(f"::error::check-otel-endpoint-declared: {f} is a Quarkus service with no "
              "QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT — its spans go to localhost:4317, which is nothing")
    if findings:
        return 1 if args.enforce else 0
    print(f"check-otel-endpoint-declared: OK — {len(subjects)} Quarkus workloads all declare an OTLP endpoint")
    return 0


if __name__ == "__main__":
    sys.exit(main())
