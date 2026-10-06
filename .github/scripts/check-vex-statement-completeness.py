#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Ratchet incomplete standalone OpenVEX statements and probe Trivy matching.

Baseline entries are known INVALID statements, not exceptions or dispositions. A new
productless/timestampless statement fails; fixing one requires deleting its stale entry.
The scanner smoke test proves this control still models Trivy's real PURL behavior.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VEX_DIR = ROOT / "openbank-libs/governance/vex"
BASELINE = ROOT / ".github/scripts/vex-incomplete-baseline.json"
FIXTURE_PURL = "pkg:maven/org.hibernate.reactive/hibernate-reactive-core@3.4.2.Final?type=jar"
WRONG_VERSION_PURL = "pkg:maven/org.hibernate.reactive/hibernate-reactive-core@3.4.2.Final-other?type=jar"
FIXTURE_CVE = "CVE-2025-14969"
PURL = re.compile(r"^pkg:[A-Za-z][A-Za-z0-9.+-]*/[^\s]+$")
RFC3339 = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$")


def valid_time(value: object) -> bool:
    if not isinstance(value, str) or not RFC3339.fullmatch(value):
        return False
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return False
    return parsed.tzinfo is not None


def inspect_document_time(name: str, document: dict) -> tuple[set[str], list[str]]:
    timestamp = document.get("timestamp")
    if timestamp is None:
        return {name}, []
    if not valid_time(timestamp):
        return set(), [f"{name}: document timestamp must be RFC 3339 with timezone"]
    return set(), []


def inspect_document(name: str, document: dict) -> tuple[set[str], set[str], list[str]]:
    productless: set[str] = set()
    timestampless: set[str] = set()
    errors: list[str] = []
    seen: set[str] = set()
    for statement in document.get("statements", []):
        vuln = statement.get("vulnerability") or {}
        cve = vuln.get("name")
        if not isinstance(cve, str) or not cve:
            errors.append(f"{name}: statement has no vulnerability name")
            continue
        key = f"{name}:{cve}"
        if key in seen:
            errors.append(f"{key}: duplicate vulnerability ID; baseline identity is ambiguous")
        seen.add(key)
        products = statement.get("products")
        if not products:
            productless.add(key)
        elif not isinstance(products, list) or any(
            not isinstance(p, dict) or not isinstance(p.get("@id"), str)
            or not PURL.fullmatch(p["@id"]) for p in products
        ):
            errors.append(f"{key}: products must contain package-url @id values")
        timestamp = statement.get("timestamp", document.get("timestamp"))
        if timestamp is None:
            timestampless.add(key)
        elif not valid_time(timestamp):
            errors.append(f"{key}: timestamp must be RFC 3339 with timezone")
    return productless, timestampless, errors


def compare(current: set[str], baseline: set[str], kind: str) -> list[str]:
    errors = [f"NEW {kind}: {key}" for key in sorted(current - baseline)]
    errors += [f"STALE {kind} baseline: {key}" for key in sorted(baseline - current)]
    return errors


def audit() -> list[str]:
    baseline = json.loads(BASELINE.read_text())
    products: set[str] = set()
    times: set[str] = set()
    document_times: set[str] = set()
    errors: list[str] = []
    for path in sorted(VEX_DIR.glob("*.openvex.json")):
        doc = json.loads(path.read_text())
        p, t, e = inspect_document(path.name, doc)
        missing_document_time, document_errors = inspect_document_time(path.name, doc)
        products.update(p)
        times.update(t)
        document_times.update(missing_document_time)
        errors.extend(e)
        errors.extend(document_errors)
    errors.extend(compare(products, set(baseline["productless"]), "productless"))
    errors.extend(compare(times, set(baseline["timestampless"]), "timestampless"))
    errors.extend(compare(document_times, set(baseline["document_timestampless"]), "document timestampless"))
    print(f"SUBJECTS={sum(len(json.loads(p.read_text()).get('statements', [])) for p in VEX_DIR.glob('*.openvex.json'))}")
    print(f"known invalid: productless={len(products)} timestampless={len(times)} "
          f"documents_without_timestamp={len(document_times)}")
    return errors


def self_test() -> None:
    assert valid_time("2026-10-05T21:00:00Z")
    assert valid_time("2026-10-05T21:00:00.123+02:00")
    assert not valid_time("2026-10-05 21:00:00+00:00")
    assert not valid_time("2026-10-05T21:00:00+0000")
    assert not valid_time("2026-10-05T21:00:00")
    base = {"timestamp": "2026-10-05T21:00:00Z", "statements": [{
        "vulnerability": {"name": FIXTURE_CVE},
        "products": [{"@id": FIXTURE_PURL}], "status": "not_affected",
    }]}
    assert inspect_document("fixture", base) == (set(), set(), [])
    assert inspect_document_time("fixture", base) == (set(), [])
    no_product = json.loads(json.dumps(base))
    del no_product["statements"][0]["products"]
    assert inspect_document("fixture", no_product)[0] == {f"fixture:{FIXTURE_CVE}"}
    no_time = json.loads(json.dumps(base))
    del no_time["timestamp"]
    assert inspect_document("fixture", no_time)[1] == {f"fixture:{FIXTURE_CVE}"}
    assert inspect_document_time("fixture", no_time)[0] == {"fixture"}
    statement_time_only = json.loads(json.dumps(no_time))
    statement_time_only["statements"][0]["timestamp"] = "2026-10-05T21:00:00Z"
    assert inspect_document("fixture", statement_time_only)[1] == set()
    assert inspect_document_time("fixture", statement_time_only)[0] == {"fixture"}
    malformed_time = json.loads(json.dumps(base))
    malformed_time["timestamp"] = "not-a-time"
    assert inspect_document_time("fixture", malformed_time)[1]
    malformed = json.loads(json.dumps(base))
    malformed["statements"][0]["products"] = [{"@id": "not-a-purl"}]
    assert inspect_document("fixture", malformed)[2]
    assert compare({"new"}, set(), "productless")
    assert compare({"new"}, set(), "timestampless")
    assert compare({"new"}, set(), "document timestampless")
    assert compare(set(), {"fixed"}, "productless")
    print("vex statement completeness self-test: OK")


def scanner_self_test() -> None:
    """Use the real Trivy DB; fail closed if the CVE fixture no longer triggers."""
    with tempfile.TemporaryDirectory(prefix="vex-scanner-effect-") as tmp:
        root = Path(tmp)
        sbom = {"bomFormat": "CycloneDX", "specVersion": "1.5", "version": 1,
                "metadata": {"component": {"type": "application", "name": "vex-fixture", "version": "1"}},
                "components": [{"type": "library", "group": "org.hibernate.reactive",
                                "name": "hibernate-reactive-core", "version": "3.4.2.Final",
                                "purl": FIXTURE_PURL}]}
        sbom_path = root / "fixture-bom.json"
        sbom_path.write_text(json.dumps(sbom))
        doc = {"@context": "https://openvex.dev/ns/v0.2.0",
               "@id": "https://open-bank.tech/vex/scanner-fixture", "author": "OpenBank Security",
               "timestamp": "2026-10-05T21:00:00Z", "version": 1,
               "statements": [{"vulnerability": {"name": FIXTURE_CVE},
                               "status": "not_affected", "justification": "vulnerable_code_not_present"}]}
        counts = []
        for mode in ("productless", "wrong-version", "exact-product"):
            fixture = json.loads(json.dumps(doc))
            if mode == "exact-product":
                fixture["statements"][0]["products"] = [{"@id": FIXTURE_PURL}]
            elif mode == "wrong-version":
                fixture["statements"][0]["products"] = [{"@id": WRONG_VERSION_PURL}]
            vex_path = root / f"{mode}.openvex.json"
            vex_path.write_text(json.dumps(fixture))
            output = root / f"{mode}.scan.json"
            cmd = ["trivy", "sbom", "--scanners", "vuln", "--vex", str(vex_path)]
            if os.environ.get("VEX_SCANNER_OFFLINE") == "1":
                cmd += ["--offline-scan", "--skip-db-update", "--skip-java-db-update"]
            cmd += ["--format", "json", "--output", str(output), str(sbom_path)]
            subprocess.run(cmd, check=True, capture_output=True, text=True, timeout=180)
            scan = json.loads(output.read_text())
            ids = [v["VulnerabilityID"] for result in scan.get("Results", [])
                   for v in result.get("Vulnerabilities", []) or []]
            counts.append(ids.count(FIXTURE_CVE))
        if counts != [1, 1, 0]:
            raise AssertionError(f"Trivy VEX product matching changed: expected [1, 1, 0], got {counts}")
    print("Trivy VEX scanner-effect self-test: productless=1 wrong-version=1 exact-product=0")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--scanner-self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.scanner_self_test:
        scanner_self_test()
        return 0
    errors = audit()
    for error in errors:
        print(error)
    print("vex statement completeness:", "FAIL" if errors else "OK (legacy invalid entries still tracked)")
    return bool(errors)


if __name__ == "__main__":
    raise SystemExit(main())
