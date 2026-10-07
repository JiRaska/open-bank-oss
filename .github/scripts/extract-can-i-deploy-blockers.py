#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Allowlist blocking pairs from Pact's JSON matrix without exporting broker URLs.

The table verdict remains the deployment gate. This is optional evidence for the
per-service #9898 artifact: a malformed, stale, or contradictory second query
must never become an asserted blocker identity.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit


SERVICE = re.compile(r"^openbank-[a-z0-9-]+$")
RESULT_PATH = re.compile(
    r"^/pacts/provider/([^/]+)/consumer/([^/]+)/pact-version/[^/]+/verification-results/([1-9][0-9]{0,17})$"
)
FAILED_RESULT = re.compile(r"^\d+\. \S+/verification-results/([1-9][0-9]{0,17}) \(failure\)$", re.M)


def extract(raw: str, service: str, block_class: str, table: str = "") -> list[dict]:
    if not SERVICE.fullmatch(service):
        raise ValueError("invalid service")
    doc = json.loads(raw)
    if not isinstance(doc, dict) or not isinstance(doc.get("matrix"), list):
        raise ValueError("missing matrix")
    if (doc.get("summary") or {}).get("deployable") is not False:
        raise ValueError("second verdict is not blocked")
    if len(doc["matrix"]) > 100:
        raise ValueError("unbounded matrix")
    pairs = []
    for row in doc["matrix"]:
        if not isinstance(row, dict):
            raise ValueError("invalid row")
        consumer = ((row.get("consumer") or {}).get("name"))
        provider = ((row.get("provider") or {}).get("name"))
        if not isinstance(consumer, str) or not SERVICE.fullmatch(consumer):
            raise ValueError("invalid consumer")
        if not isinstance(provider, str) or not SERVICE.fullmatch(provider):
            raise ValueError("invalid provider")
        if service not in (consumer, provider):
            raise ValueError("matrix row outside requested service")
        result = row.get("verificationResult")
        if result is not None and not isinstance(result, dict):
            raise ValueError("invalid verification result")
        if result is not None and result.get("success") is True:
            continue
        if result is not None and result.get("success") is not False:
            raise ValueError("unknown verification status")
        verification_id = None
        if result is not None:
            href = (((result.get("_links") or {}).get("self") or {}).get("href"))
            if not isinstance(href, str):
                raise ValueError("failed result without link")
            path = urlsplit(href).path
            match = RESULT_PATH.fullmatch(path)
            if not match or unquote(match[1]) != provider or unquote(match[2]) != consumer:
                raise ValueError("verification link contradicts pair")
            verification_id = int(match[3])
        pairs.append({"consumer": consumer, "provider": provider,
                      "verification_id": verification_id})
    if not pairs or (block_class == "REGRESSION" and not any(
            p["verification_id"] is not None for p in pairs)):
        raise ValueError("matrix does not support block class")
    # The JSON query follows the table gate. A live Broker can change between
    # them. For failed verifications, require the same result IDs in both reads.
    # Missing/inconsistent evidence leaves blocked_on null in the caller.
    json_ids = sorted(p["verification_id"] for p in pairs if p["verification_id"] is not None)
    table_ids = sorted(int(n) for n in FAILED_RESULT.findall(table))
    if table and json_ids != table_ids:
        raise ValueError("JSON and table contain different failed verifications")
    return sorted(pairs, key=lambda p: (p["consumer"], p["provider"], p["verification_id"] or 0))


def self_test() -> None:
    service = "openbank-account-service"
    pair = {"consumer": {"name": service}, "provider": {"name": "openbank-party-service"},
            "verificationResult": {"success": False, "_links": {"self": {
                "href": "https://broker.example/pacts/provider/openbank-party-service/consumer/"
                        "openbank-account-service/pact-version/abc/verification-results/69451"}}}}
    raw = json.dumps({"summary": {"deployable": False}, "matrix": [pair]})
    table = "1. https://broker.example/pacts/provider/openbank-party-service/consumer/" \
            "openbank-account-service/pact-version/abc/verification-results/69451 (failure)"
    got = extract(raw, service, "REGRESSION", table)
    assert got == [{"consumer": service, "provider": "openbank-party-service",
                    "verification_id": 69451}]
    assert "broker.example" not in json.dumps(got)
    try:
        extract(raw, service, "REGRESSION", table.replace("69451", "68504"))
    except ValueError:
        pass
    else:
        raise AssertionError("changed verification result accepted")
    for bad in [raw.replace("69451", "0"),
                raw.replace("provider/openbank-party-service/consumer",
                            "provider/openbank-other-service/consumer"),
                raw.replace('"deployable": false', '"deployable": true')]:
        try:
            extract(bad, service, "REGRESSION")
        except ValueError:
            pass
        else:
            raise AssertionError("contradictory matrix accepted")
    print("extract-can-i-deploy-blockers: self-test PASS")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--service")
    parser.add_argument("--class", dest="block_class")
    parser.add_argument("--table-file", type=Path)
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    try:
        table = args.table_file.read_text() if args.table_file else ""
        print(json.dumps(extract(sys.stdin.read(), args.service, args.block_class, table),
                         sort_keys=True, separators=(",", ":")))
    except (ValueError, TypeError, KeyError):
        print("matrix did not establish a bounded blocker identity", file=sys.stderr)
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
