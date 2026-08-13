#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Print the request path a k6 smoke script exercises, for perf-gate.yml's pre-flight probe.

WHY THIS EXISTS. perf-gate.yml waits for /q/health/ready and then hands k6 the clock. Readiness
says the process is up; it says nothing about the route the script calls. Measured on run
31570530574: product-catalog reported `http_req_failed 100.00%, 108685 out of 108685` while
`http_req_duration` PASSED at avg 1.14ms — the latency of an authentication rejection. Every
number that gate has ever published for that service was a measurement of the error path.

Extracting the path here rather than inline keeps the quoting out of a `run:` block, where a
regex full of ${} and quotes trips shellcheck and, worse, is easy to get subtly wrong.

Self-test:  k6-smoke-path.py --self-test
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

# `http.get(`${BASE_URL}/api/v1/products`, {` -> /api/v1/products
PATTERN = re.compile(r"\$\{BASE_URL\}(?P<path>[^`'\"]*)")


def smoke_path(source: str) -> str:
    m = PATTERN.search(source)
    if not m:
        raise SystemExit("no ${BASE_URL}<path> request found — cannot pre-flight this script")
    return m.group("path") or "/"


def _self_test() -> int:
    cases = [
        ("const r = http.get(`${BASE_URL}/api/v1/products`, {});", "/api/v1/products"),
        ('http.get(`${BASE_URL}/api/v1/fees?type=X`)', "/api/v1/fees?type=X"),
        ("http.get(`${BASE_URL}/`)", "/"),
        ("http.get(`${BASE_URL}`)", "/"),
    ]
    bad = 0
    for src, want in cases:
        got = smoke_path(src)
        ok = got == want
        bad += not ok
        print(f"  {'PASS' if ok else 'FAIL'}  {src!r} -> {got!r} (want {want!r})")
    try:
        smoke_path("http.get('http://hardcoded/api')")
        print("  FAIL  a script with no ${BASE_URL} must be rejected, not guessed")
        bad += 1
    except SystemExit:
        print("  PASS  a script with no ${BASE_URL} is rejected rather than guessed")
    print(f"self-test: {'all cases passed' if not bad else f'{bad} failed'}")
    return 1 if bad else 0


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        raise SystemExit(_self_test())
    print(smoke_path(Path(sys.argv[1]).read_text()))
