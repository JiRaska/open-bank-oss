#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""
Keep every edge decoy route paired with a Loki rule that reads it, and vice versa (ADR-0357).

WHY THIS EXISTS.

ADR-0279 WS2 shipped a honey-endpoint ALERT (HoneyEndpointHit) over a honey-endpoint FILTER
that no service ever configured. Measured 2026-10-29: `openbank.security.honey.paths` set
nowhere, the metric series absent, the alert 0 hits in 7 days and unable to fire. Nothing
could notice, because the alert and its subject lived in different places with no check
between them. The edge decoys (httproute-edge-decoys.yaml) are the replacement, and this
gate is the check that was missing: a decoy HTTPRoute whose Envoy route name no Loki rule
selects is a decoy nobody will ever hear about; a rule selecting a route no manifest
declares is an alert that can never fire. Both fail the build.

HOW IT PAIRS THEM. Envoy Gateway names every route `httproute/<ns>/<name>/rule/...` and
writes that into the access log. A decoy route is any HTTPRoute in gitops whose name starts
with `edge-decoys-`. A reader is any `route_name=~"httproute/<ns>/<name>/..."` matcher in a
`loki_rule` ConfigMap. The two sets must be equal.

Usage:
    check-edge-decoy-rule-parity.py             # scan
    check-edge-decoy-rule-parity.py --selftest  # prove the gate can fail in both directions
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

import yaml

REPO = Path(__file__).resolve().parents[2]
COMPONENTS = REPO / "openbank-infra" / "gitops" / "components"
DECOY_PREFIX = "edge-decoys-"
READER_RE = re.compile(r'route_name=~"httproute/(?P<ns>[^/"]+)/(?P<name>[^/"]+)/')


def _docs(path: Path):
    try:
        yield from (d for d in yaml.safe_load_all(path.read_text(encoding="utf-8")) if d)
    except yaml.YAMLError as ex:
        sys.stderr.write(f"::error file={path}::not parseable YAML: {ex}\n")
        yield from ()


def decoy_routes(root: Path) -> set[tuple[str, str]]:
    found: set[tuple[str, str]] = set()
    for path in sorted(root.rglob("*.yaml")):
        for doc in _docs(path):
            if not isinstance(doc, dict) or doc.get("kind") != "HTTPRoute":
                continue
            meta = doc.get("metadata") or {}
            name = str(meta.get("name", ""))
            if name.startswith(DECOY_PREFIX):
                found.add((str(meta.get("namespace", "")), name))
    return found


def rule_readers(root: Path) -> set[tuple[str, str]]:
    found: set[tuple[str, str]] = set()
    for path in sorted(root.rglob("loki-rules-*.yaml")):
        for doc in _docs(path):
            if not isinstance(doc, dict) or doc.get("kind") != "ConfigMap":
                continue
            labels = (doc.get("metadata") or {}).get("labels") or {}
            if str(labels.get("loki_rule")) != "1":
                continue
            for payload in (doc.get("data") or {}).values():
                for m in READER_RE.finditer(str(payload)):
                    found.add((m.group("ns"), m.group("name")))
    return found


def scan(root: Path) -> tuple[list[str], int]:
    routes, readers = decoy_routes(root), rule_readers(root)
    findings = [
        f"decoy route {ns}/{name} has no Loki rule selecting route_name httproute/{ns}/{name}/..."
        for ns, name in sorted(routes - readers)
    ] + [
        f"Loki rule selects httproute/{ns}/{name}/... but no HTTPRoute {ns}/{name} is declared"
        for ns, name in sorted(readers - routes)
    ]
    return findings, len(routes | readers)


def selftest() -> int:
    import shutil
    import tempfile

    findings, subjects = scan(COMPONENTS)
    if subjects == 0:
        print("SELFTEST FAIL: zero decoy routes or readers — the gate has no subject")
        return 1
    if findings:
        print("SELFTEST FAIL: the committed tree is not clean, so a negative control proves nothing")
        for f in findings:
            print("   ", f)
        return 1
    obs = COMPONENTS / "observability"
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp) / "components" / "observability"
        shutil.copytree(obs, work)
        # Direction 1: a decoy route with no reader. Rename the targeted route in the manifest.
        route_file = work / "httproute-edge-decoys.yaml"
        route_file.write_text(
            route_file.read_text(encoding="utf-8").replace(
                "name: edge-decoys-targeted", "name: edge-decoys-orphan", 1),
            encoding="utf-8")
        bad, _ = scan(work.parent)
        if not any("edge-decoys-orphan" in f and "no Loki rule" in f for f in bad):
            print("SELFTEST FAIL: an unread decoy route was not reported")
            return 1
        if not any("edge-decoys-targeted" in f and "no HTTPRoute" in f for f in bad):
            print("SELFTEST FAIL: a reader of a missing route was not reported")
            return 1
    print(f"check-edge-decoy-rule-parity --selftest: OK — {subjects} pair(s) clean; "
          "both an unread route and a route-less reader are reported")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        return selftest()
    findings, subjects = scan(COMPONENTS)
    print(f"SUBJECTS={subjects}  # decoy routes + rule readers, union")
    for f in findings:
        print(f"::error::{f}")
    if findings:
        return 1
    print(f"check-edge-decoy-rule-parity: OK — {subjects} decoy route(s) each read by a Loki rule")
    return 0


if __name__ == "__main__":
    sys.exit(main())
