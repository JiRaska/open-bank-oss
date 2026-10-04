#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Alloy must extract trace/span/correlation ids from the path the services' log encoder writes.

WHY THIS EXISTS
---------------
The ids reach Loki structured metadata (`trace_id`, `span_id`, `correlationId`), and through
that the Grafana logs->trace link, only if Alloy's `stage.json` reads them from the exact JSON
path the services emit. That path is set in one place, openbank-libs-runtime's
microprofile-config.properties (the quarkus-logging-json encoder nests MDC values under `mdc`,
or under its `key-overrides` rename). Nothing tied the two together, and twice the link died
with every component looking configured:
  - until 2026-09-30 Alloy read `mdc.traceId` while services logged a top-level `traceId`;
  - #11657 moved Alloy to top-level on 09-30, and #11630 moved services to the nested encoder on
    10-01. They crossed, and `trace_id` was set on 0 lines in 24h.
An empty structured-metadata value is not an error anywhere in the pipeline, so only a check
that derives the path from the contract and compares it with the pipeline can see this.

WHAT IT CHECKS
--------------
Contract (derived, never hard-coded):
  - container key: `mdc`, or what `quarkus.log.console.json.key-overrides` renames `mdc` to;
    no container if MDC is flattened, and a finding if `mdc` is in `excluded-keys`
  - field names: `traceId`/`spanId` (quarkus-opentelemetry's MDC keys) and the value of
    `MDC_CORRELATION_ID` in libs-runtime's CorrelationIdFilter.kt
Pipeline: every `stage.json` in gitops/apps/alloy.yaml. For each of trace_id/span_id/
correlationId, the FIRST alternative of the JMESPath expression (before any `||` fallback) must
equal the contract path. A fallback after `||` is allowed (it carries pods on an older image).

Usage:  check-log-trace-id-path.py [--root DIR] [--enforce] [--self-test]
Advisory by default (prints ::warning, exits 0); --enforce fails.
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gatelib  # noqa: E402

PROPS = "openbank-libs-runtime/src/main/resources/META-INF/microprofile-config.properties"
FILTER = "openbank-libs-runtime/src/main/kotlin/com/openbank/libs/web/CorrelationIdFilter.kt"
ALLOY = "openbank-infra/gitops/apps/alloy.yaml"
PREFIX = "quarkus.log.console.json"

STAGE_JSON = re.compile(r"stage\.json\s*\{\s*expressions\s*=\s*\{(.*?)\}", re.S)
EXPR = re.compile(r'([A-Za-z_][A-Za-z0-9_]*)\s*=\s*"((?:[^"\\]|\\.)*)"')
CORR = re.compile(r'MDC_CORRELATION_ID\s*=\s*"([^"]+)"')


def read_props(text: str) -> dict[str, str]:
    props: dict[str, str] = {}
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", "!")) or "=" not in line:
            continue
        key, value = line.split("=", 1)
        props[key.strip()] = value.strip()
    return props


def contract(root: pathlib.Path) -> tuple[dict[str, str], list[str]]:
    """Return ({metadata_name: json_path}, findings)."""
    findings: list[str] = []
    props = read_props(gatelib.read_text(root / PROPS))
    overrides = {}
    for pair in props.get(f"{PREFIX}.key-overrides", "").split(","):
        if "=" in pair:
            k, v = pair.split("=", 1)
            overrides[k.strip()] = v.strip()
    excluded = {k.strip() for k in props.get(f"{PREFIX}.excluded-keys", "").split(",")}
    flat = any(
        k.startswith(PREFIX) and "flat" in k and v.lower() == "true" for k, v in props.items()
    )
    if "mdc" in excluded:
        findings.append(f"{PROPS}: excluded-keys drops `mdc` -- no trace id reaches any log line")
    container = "" if flat else overrides.get("mdc", "mdc")
    m = CORR.search(gatelib.read_text(root / FILTER))
    if not m:
        findings.append(f"{FILTER}: MDC_CORRELATION_ID not found -- cannot derive the contract")
        corr = None
    else:
        corr = m.group(1)

    def path(field: str) -> str:
        return f"{container}.{field}" if container else field

    want = {"trace_id": path("traceId"), "span_id": path("spanId")}
    if corr:
        want["correlationId"] = path(corr)
    return want, findings


def pipeline(root: pathlib.Path) -> dict[str, list[str]]:
    """Return {metadata_name: [first-alternative expression, ...]} over every stage.json."""
    got: dict[str, list[str]] = {}
    for block in STAGE_JSON.findall(gatelib.read_text(root / ALLOY)):
        for name, expr in EXPR.findall(block):
            got.setdefault(name, []).append(expr.split("||")[0].strip())
    return got


def check(root: pathlib.Path) -> list[str]:
    want, findings = contract(root)
    got = pipeline(root)
    for name, path in sorted(want.items()):
        firsts = got.get(name)
        if not firsts:
            findings.append(f"{ALLOY}: no stage.json extracts `{name}` (contract path `{path}`)")
        elif path not in firsts:
            findings.append(
                f"{ALLOY}: `{name}` reads {firsts} but the encoder writes `{path}` "
                f"(derived from {PROPS}) -- logs->trace link is dead"
            )
    gatelib.subjects(len(want), "id fields compared")
    return findings


GOOD_PROPS = f"{PREFIX}=true\n{PREFIX}.key-overrides=logger-name=logger\n{PREFIX}.excluded-keys=ndc\n"
GOOD_KT = 'const val MDC_CORRELATION_ID = "correlationId"\n'


def alloy(trace: str, span: str, corr: str) -> str:
    return (
        "content: |-\n  stage.json {\n    expressions = {\n      level = \"level\",\n"
        f'      trace_id = "{trace}",\n      span_id = "{span}",\n      correlationId = "{corr}",\n'
        "    }\n  }\n"
    )


def self_test() -> int:
    nested = alloy("mdc.traceId || traceId", "mdc.spanId || spanId", "mdc.correlationId || correlationId")
    cases = [
        ("nested with fallback", GOOD_PROPS, nested, True),
        ("nested only", GOOD_PROPS, alloy("mdc.traceId", "mdc.spanId", "mdc.correlationId"), True),
        ("old top-level only (#11657 shape)", GOOD_PROPS, alloy("traceId", "spanId", "correlationId"), False),
        ("fallback order reversed", GOOD_PROPS, alloy("traceId || mdc.traceId", "mdc.spanId", "mdc.correlationId"), False),
        ("encoder renames mdc", GOOD_PROPS.replace("logger-name=logger", "logger-name=logger,mdc=ctx"), nested, False),
        ("renamed on both sides", GOOD_PROPS.replace("logger-name=logger", "logger-name=logger,mdc=ctx"),
         alloy("ctx.traceId", "ctx.spanId", "ctx.correlationId"), True),
        ("encoder drops mdc", GOOD_PROPS.replace("excluded-keys=ndc", "excluded-keys=ndc,mdc"), nested, False),
        ("span_id missing", GOOD_PROPS, nested.replace('      span_id = "mdc.spanId || spanId",\n', ""), False),
    ]
    failed = 0
    for name, props, alloy_text, ok in cases:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            for rel, text in ((PROPS, props), (FILTER, GOOD_KT), (ALLOY, alloy_text)):
                (root / rel).parent.mkdir(parents=True, exist_ok=True)
                (root / rel).write_text(text)
            got_ok = not check(root)
        status = "ok" if got_ok == ok else "WRONG"
        failed += status != "ok"
        print(f"self-test [{status}] {name}: expected {'pass' if ok else 'fail'}, got {'pass' if got_ok else 'fail'}")
    print("self-test:", "PASS" if not failed else f"FAIL ({failed})")
    return 1 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    findings = check(pathlib.Path(args.root))
    for f in findings:
        print(f"::{'error' if args.enforce else 'warning'}::{f}")
    if not findings:
        print("check-log-trace-id-path: Alloy extracts every id from the encoder's path -- clean.")
        return 0
    return 1 if args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
