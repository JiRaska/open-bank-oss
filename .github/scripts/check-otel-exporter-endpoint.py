#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""
Assert every deployed Quarkus service that exports traces is told WHERE to export them.

`quarkus-opentelemetry` exports spans over OTLP to `quarkus.otel.exporter.otlp.endpoint`, and
both its built-in default and the value most `application.yaml` files carry is
`http://localhost:4317` — correct for a laptop with a collector beside it, wrong in a pod, where
nothing listens there. A Deployment that does not override it (`QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT`,
pointing at Tempo) exports into a closed port: every batch fails with `Connection refused`, the
service keeps serving, the pod stays Ready, and its traces simply never reach Tempo. Twelve
Deployments were in that state at once (2026-09-24), each found only by reading a pod log.

WHAT COUNTS AS A SUBJECT
------------------------
A container in a gitops `Deployment` whose image repository is `openbank-<x>`, where the repo
module `openbank-<x>/build.gradle.kts` depends on `quarkus-opentelemetry`. A module WITHOUT the
extension has no exporter and is not a subject — setting the variable there would be inert, and
flagging it would be a gate crying wolf about correct config. Non-Quarkus images (the Python
document-renderer sidecar, `opa`) never match a module with the extension.

A container passes when its `env` names `QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT`, when it turns the
SDK off outright (`QUARKUS_OTEL_SDK_DISABLED: "true"`, analytics-sink's deliberate choice), or when
the module's own `application*.yaml` sets the endpoint to something other than localhost — the
failure is the localhost fallback, not the absence of one particular spelling.

BASELINE
--------
`KNOWN_PENDING` holds manifests whose fix is already in an open PR. An entry that has become clean
fails the gate too, so a baseline line cannot outlive the thing it excuses.

Usage:
    check-otel-exporter-endpoint.py             # gate (exit 1 on a missing endpoint)
    check-otel-exporter-endpoint.py --self-test # prove the gate can fail
"""
from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gatelib

REPO = Path(__file__).resolve().parents[2]
COMPONENTS = Path("openbank-infra/gitops/components")
ENV_VAR = "QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT"
SDK_DISABLED = "QUARKUS_OTEL_SDK_DISABLED"

# manifest path (relative to COMPONENTS) -> the open PR that fixes it
KNOWN_PENDING = {
    "loyalty/loyalty-service.yaml": "#10761",
}

# Both spellings exist in the fleet: the version-catalog alias and a literal coordinate.
OTEL_DEP = re.compile(r"quarkus\.opentelemetry\b|io\.quarkus:quarkus-opentelemetry\b")
IMAGE_REPO = re.compile(r"(?:^|/)(openbank-[a-z0-9-]+)(?::|@|$)")
ENDPOINT_LINE = re.compile(r"^\s*endpoint:\s*[\"']?([^\"'\s#]+)", re.MULTILINE)


def exports_traces(module: Path) -> bool:
    build = module / "build.gradle.kts"
    return build.is_file() and bool(OTEL_DEP.search(build.read_text()))


def config_points_off_localhost(module: Path) -> bool:
    """True when application*.yaml sets an OTLP endpoint that is not localhost."""
    for cfg in (module / "src" / "main" / "resources").glob("application*.y*ml"):
        text = cfg.read_text()
        # Only an `endpoint:` inside an `otlp:` block is the exporter's; take the lines after it.
        for m in re.finditer(r"^(\s*)otlp:\s*$", text, re.MULTILINE):
            block = text[m.end():m.end() + 400]
            e = ENDPOINT_LINE.search(block)
            if e and "localhost" not in e.group(1) and "127.0.0.1" not in e.group(1) \
                    and not e.group(1).startswith("${"):
                return True
    return False


def audit(root: Path) -> tuple[list[str], int]:
    """Findings are `<path relative to COMPONENTS>\t<message>`, so the baseline can key on them."""
    findings: list[str] = []
    examined = 0
    for manifest in sorted((root / COMPONENTS).rglob("*.yaml")):
        try:
            docs = list(yaml.safe_load_all(manifest.read_text()))
        except yaml.YAMLError:
            continue  # malformed YAML is yamllint's to report, not this gate's
        for doc in docs:
            if not isinstance(doc, dict) or doc.get("kind") != "Deployment":
                continue
            pod = (((doc.get("spec") or {}).get("template") or {}).get("spec") or {})
            for c in pod.get("containers") or []:
                m = IMAGE_REPO.search(str(c.get("image", "")))
                if not m:
                    continue
                module = root / m.group(1)
                if not exports_traces(module):
                    continue
                examined += 1
                env = {e.get("name"): e.get("value") for e in c.get("env") or [] if isinstance(e, dict)}
                if ENV_VAR in env or str(env.get(SDK_DISABLED, "")).lower() == "true" \
                        or config_points_off_localhost(module):
                    continue
                findings.append(
                    f"{manifest.relative_to(root / COMPONENTS)}\t"
                    f"{manifest.relative_to(root)}: container '{c.get('name')}' "
                    f"({m.group(1)} has quarkus-opentelemetry) sets no {ENV_VAR}"
                )
    return findings, examined


DEPLOYMENT = """\
apiVersion: apps/v1
kind: Deployment
metadata: {{name: demo-service}}
spec:
  template:
    spec:
      containers:
        - name: demo-service
          image: registry.example/openbank-demo-service:sandbox-1
          env:
            - {{name: QUARKUS_HTTP_PORT, value: "8080"}}{extra}
        - name: opa
          image: openpolicyagent/opa:1.0.0
"""
DISABLED = '\n            - {name: QUARKUS_OTEL_SDK_DISABLED, value: "true"}'
WITH_ENV = '\n            - {name: QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT, value: "http://tempo:4317"}'
LOCAL_CFG = "quarkus:\n  otel:\n    exporter:\n      otlp:\n        endpoint: http://localhost:4317\n"
REMOTE_CFG = "quarkus:\n  otel:\n    exporter:\n      otlp:\n        endpoint: http://tempo:4317\n"


def self_test() -> int:
    cases = [
        # label, has otel dep, extra env, application.yaml, want finding, want subjects
        ("otel dep, no env, localhost config -> finding", True, "", LOCAL_CFG, True, 1),
        ("otel dep, no env, no config -> finding (built-in default is localhost)", True, "", None, True, 1),
        ("otel dep, env set -> clean", True, WITH_ENV, LOCAL_CFG, False, 1),
        ("otel dep, SDK disabled -> clean", True, DISABLED, LOCAL_CFG, False, 1),
        ("otel dep, no env, config points at tempo -> clean", True, "", REMOTE_CFG, False, 1),
        ("no otel dep -> not a subject", False, "", LOCAL_CFG, False, 0),
    ]
    failed = 0
    for label, dep, env, cfg, want_finding, want_subjects in cases:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            comp = root / COMPONENTS / "demo"
            comp.mkdir(parents=True)
            (comp / "demo-service.yaml").write_text(DEPLOYMENT.format(extra=env))
            module = root / "openbank-demo-service"
            (module / "src/main/resources").mkdir(parents=True)
            (module / "build.gradle.kts").write_text(
                "dependencies {\n" + ("    implementation(libs.quarkus.opentelemetry)\n" if dep else "") + "}\n"
            )
            if cfg:
                (module / "src/main/resources/application.yaml").write_text(cfg)
            got, examined = audit(root)
            ok = bool(got) == want_finding and examined == want_subjects
            print(f"{'ok  ' if ok else 'FAIL'} {label} (findings={len(got)}, subjects={examined})")
            failed += not ok
    if failed:
        print(f"self-test: {failed} case(s) failed", file=sys.stderr)
        return 1
    print("self-test: all cases behaved as expected")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()

    raw, examined = audit(REPO)
    gatelib.subjects(examined, "Deployment containers of Quarkus modules with quarkus-opentelemetry")
    flagged = {r.split("\t", 1)[0] for r in raw}
    findings = [r.split("\t", 1)[1] for r in raw if r.split("\t", 1)[0] not in KNOWN_PENDING]
    stale = sorted(set(KNOWN_PENDING) - flagged)
    if stale:
        for path in stale:
            print(f"KNOWN_PENDING entry is now clean — remove it: {path} ({KNOWN_PENDING[path]})",
                  file=sys.stderr)
        return 1
    if findings:
        print("Deployments that will export traces to localhost:4317 (Connection refused):\n",
              file=sys.stderr)
        for f in findings:
            print(f"  {f}", file=sys.stderr)
        print(
            f"\n{len(findings)} finding(s). Add to the container's env, as aml-service does:\n"
            f"  - name: {ENV_VAR}\n"
            f"    value: \"http://tempo.observability.svc:4317\"\n"
            f"  - name: OTEL_TRACES_SAMPLER_ARG\n"
            f"    value: \"0.1\"",
            file=sys.stderr,
        )
        return 1
    print(f"OK: every Quarkus Deployment with quarkus-opentelemetry sets {ENV_VAR}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
