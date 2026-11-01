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

import os
import re
import sys
from pathlib import Path

# `http.get(`${BASE_URL}/api/v1/products`, {` -> /api/v1/products
PATTERN = re.compile(r"\$\{BASE_URL\}(?P<path>[^`'\"]*)")


# `const ACCOUNT_ID = __ENV.PERF_ACCOUNT_ID || "00…c2";` or `const LIMIT = "20";`
CONST = re.compile(
    r"""const\s+(?P<name>[A-Za-z_]\w*)\s*=\s*"""
    r"""(?:__ENV\.(?P<env>\w+)\s*\|\|\s*)?(?P<q>["'])(?P<default>[^"'`]*)(?P=q)"""
)
PLACEHOLDER = re.compile(r"\$\{(?P<name>[A-Za-z_]\w*)\}")


def smoke_path(source: str, env: dict[str, str] | None = None) -> str:
    """The first request path, with every `${NAME}` resolved the way k6 would resolve it.

    Probing the raw template is a probe of a route that does not exist: run 41183559088 sent
    `/api/v1/transactions?accountId=${ACCOUNT_ID}` and read the 404 as the service failing.
    A placeholder the script does not define from a literal or an `__ENV` fallback is refused,
    never guessed.
    """
    env = os.environ if env is None else env
    m = PATTERN.search(source)
    if not m:
        raise SystemExit("no ${BASE_URL}<path> request found — cannot pre-flight this script")
    consts = {}
    for c in CONST.finditer(source):
        value = env.get(c.group("env")) if c.group("env") else None
        consts[c.group("name")] = value or c.group("default")

    def resolve(ph: re.Match) -> str:
        name = ph.group("name")
        if name not in consts:
            raise SystemExit(f"cannot resolve ${{{name}}} in the probed path — refusing to guess")
        return consts[name]

    return PLACEHOLDER.sub(resolve, m.group("path")) or "/"


def _self_test() -> int:
    cases = [
        ("const r = http.get(`${BASE_URL}/api/v1/products`, {});", "/api/v1/products"),
        ('http.get(`${BASE_URL}/api/v1/fees?type=X`)', "/api/v1/fees?type=X"),
        ("http.get(`${BASE_URL}/`)", "/"),
        ("http.get(`${BASE_URL}`)", "/"),
        (
            'const ACCOUNT_ID = __ENV.PERF_ACCOUNT_ID || "acc-default";\n'
            "http.get(`${BASE_URL}/api/v1/t?accountId=${ACCOUNT_ID}&limit=20`)",
            "/api/v1/t?accountId=acc-from-env&limit=20",
        ),
        (
            "const PARTY_ID = __ENV.PERF_PARTY_ID || 'p-default';\n"
            "http.get(`${BASE_URL}/api/v1/c/party/${PARTY_ID}`)",
            "/api/v1/c/party/p-default",
        ),
    ]
    bad = 0
    for src, want in cases:
        got = smoke_path(src, {"PERF_ACCOUNT_ID": "acc-from-env"})
        ok = got == want
        bad += not ok
        print(f"  {'PASS' if ok else 'FAIL'}  {src!r} -> {got!r} (want {want!r})")
    try:
        smoke_path("http.get('http://hardcoded/api')")
        print("  FAIL  a script with no ${BASE_URL} must be rejected, not guessed")
        bad += 1
    except SystemExit:
        print("  PASS  a script with no ${BASE_URL} is rejected rather than guessed")
    try:
        smoke_path("http.get(`${BASE_URL}/api/v1/x/${UNDECLARED}`)", {})
        print("  FAIL  an unresolvable placeholder must be rejected, not probed literally")
        bad += 1
    except SystemExit:
        print("  PASS  an unresolvable placeholder is rejected rather than probed literally")
    print(f"self-test: {'all cases passed' if not bad else f'{bad} failed'}")
    return 1 if bad else 0


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        raise SystemExit(_self_test())
    print(smoke_path(Path(sys.argv[1]).read_text()))
