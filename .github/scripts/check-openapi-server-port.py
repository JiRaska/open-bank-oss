#!/usr/bin/env python3
"""openapi-server-port conformance gate — the spec's declared dev port vs quarkus.http.port.

WHY THIS EXISTS: issue #2314 measured 22 of 35 specs declaring a `servers[0].url` port that
disagrees with the service's own `quarkus.http.port` — the cheapest fully mechanical signal
that the spec file is not maintained against the service it describes. The port is a proxy,
not the point: a file whose one-line dev-server declaration drifts is the same file whose
schemas invent fields (#2312) and whose routes were never published (#2358). This gate keeps
the proxy at zero so the signal stays meaningful; the deeper checks are
check-openapi-route-conformance.py (#2360) and check-openapi-request-schema-conformance.py.

WHAT IT CHECKS: for every `openbank-*/src/main/resources/openapi.yaml` whose `servers[0].url`
is an absolute URL with an explicit port (e.g. `http://localhost:8101`), that port must equal
the `quarkus.http.port` declared in the same service's `application.yaml`. Prefix-only servers
(`/api/v1`) and specs without an absolute URL are out of scope — they carry no port to drift.

WHAT IT DOES NOT PROVE: only the port agrees. It says nothing about routes, schemas, or status
codes — do not read a green here as "this spec is true".

stdlib-only; reads the working tree so it runs identically in CI and locally.

Usage:
    check-openapi-server-port.py [--enforce]

Without --enforce it prints findings and exits 0 (advisory). With --enforce any mismatch
exits 1. Landed with the fleet already clean (the 21 mismatches measured on 2026-07-29 were
corrected in the same PR), so enforce is the expected invocation — no baseline file needed.
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys

import gatelib

REPO = pathlib.Path(__file__).resolve().parents[2]
SKIP_DIRS = {"openbank-libs", "openbank-libs-domain", "openbank-libs-runtime",
             "openbank-admin-ui", "openbank-infra"}


def reading_is_empty(examined: int) -> bool:
    """True when no spec/application pair was read at all.

    A separate function, not an inline `if` in main(), because a guard main() owns is a guard
    the self-test cannot reach: the deliberate break that removed this one was NOT caught
    while it lived inline. Same fix as check-release-registration.py, same reason.
    """
    return examined == 0


def find_mismatches(root: pathlib.Path = None) -> tuple[list[str], int]:
    """(findings, specs_examined). The count is returned, not just the findings, so the caller
    can tell 'every spec agrees' from 'no spec was read' — three empty inputs are trivially
    consistent and would otherwise print the same green."""
    findings = []
    examined = 0
    for svc_dir in sorted((root or REPO).glob("openbank-*")):
        if not svc_dir.is_dir() or svc_dir.name in SKIP_DIRS:
            continue
        spec = svc_dir / "src" / "main" / "resources" / "openapi.yaml"
        app = svc_dir / "src" / "main" / "resources" / "application.yaml"
        if not spec.is_file() or not app.is_file():
            continue
        # Counted here — when the pair of files is READ — not later where the comparison
        # happens. The count exists to distinguish "no spec disagrees" from "no spec was
        # read", so it must not be gated on the spec being comparable: a fleet of
        # prefix-only servers is a legitimate zero-findings run, a moved layout is not.
        examined += 1
        spec_text = spec.read_text(encoding="utf-8")
        app_text = app.read_text(encoding="utf-8")
        m_srv = re.search(r"url:\s*https?://[^/:]+:(\d+)", spec_text)
        if not m_srv:
            continue  # prefix-only server (e.g. /api/v1) — no port to drift
        # Anchored to quarkus.http specifically. A bare `^\s*port:` matches the FIRST port key
        # at any indentation — a datasource, a management interface, a Kafka broker — and then
        # compares the spec against an unrelated number. The old pattern is kept as a fallback
        # only when no quarkus.http block exists, so today's verdict does not move.
        m_port = re.search(r"quarkus:.*?\n\s+http:.*?\n\s+port:\s*(\d+)", app_text, re.S)
        if not m_port:
            m_port = re.search(r"^\s*port:\s*(\d+)", app_text, re.M)
        if not m_port:
            continue  # no explicit quarkus.http.port — nothing to compare against
        if m_srv.group(1) != m_port.group(1):
            findings.append(
                f"{svc_dir.name}: openapi.yaml servers port {m_srv.group(1)} "
                f"!= quarkus.http.port {m_port.group(1)}"
            )
    return findings, examined


def self_test() -> int:
    """Falsify the comparison against fixture services.

    #2314 measured 22 of 35 specs declaring a dev port that disagreed with the service's own
    `quarkus.http.port`. The port is a proxy, not the point: a spec whose one-line server
    declaration has drifted is the same spec whose schemas invent fields and whose routes were
    never published. The gate keeps the proxy at zero so the signal keeps meaning something —
    which is worth exactly nothing if the gate cannot go red.
    """
    import tempfile

    fails: list[str] = []

    def svc(root: pathlib.Path, name: str, spec: str, app: str) -> None:
        d = root / f"openbank-{name}" / "src" / "main" / "resources"
        d.mkdir(parents=True, exist_ok=True)
        (d / "openapi.yaml").write_text(spec)
        (d / "application.yaml").write_text(app)

    QHTTP = "quarkus:\n  http:\n    port: 8101\n"

    def case(label: str, spec: str, app: str, want_finding: bool, want_examined: int = 1) -> None:
        with tempfile.TemporaryDirectory() as td:
            root = pathlib.Path(td)
            svc(root, "x", spec, app)
            findings, examined = find_mismatches(root)
            if bool(findings) != want_finding:
                fails.append(f"{label}: expected finding={want_finding}, got {findings}")
            if examined != want_examined:
                fails.append(f"{label}: expected examined={want_examined}, got {examined}")

    # THE DEFECT: the spec says one port, the service another.
    case("a disagreeing port is FLAGGED",
         "servers:\n  - url: http://localhost:9999\n", QHTTP, True)

    # Agreement is the only clean shape; without this case a gate that flags everything looks
    # identical to a working one.
    case("a matching port is clean",
         "servers:\n  - url: http://localhost:8101\n", QHTTP, False)

    # OUT OF SCOPE, deliberately: a prefix-only server carries no port to drift. Flagging it
    # would push authors to invent a port just to satisfy the gate.
    case("a prefix-only server is out of scope (examined, no finding)",
         "servers:\n  - url: /api/v1\n", QHTTP, False)

    # THE REGEX TRAP this pass fixed: a bare `^\s*port:` matches the FIRST port key at any
    # indentation. With a datasource port declared before quarkus.http, the old pattern
    # compared the spec against the DATABASE port and either missed a real drift or invented
    # one. Anchoring to quarkus.http is what makes this case answerable at all.
    case("a datasource port does not shadow quarkus.http.port",
         "servers:\n  - url: http://localhost:8101\n",
         "quarkus:\n  datasource:\n    port: 5432\n  http:\n    port: 8101\n", False)
    case("...and the same shape still catches a real drift",
         "servers:\n  - url: http://localhost:9999\n",
         "quarkus:\n  datasource:\n    port: 5432\n  http:\n    port: 8101\n", True)

    # A service with no explicit quarkus.http.port has nothing to compare against.
    case("no port declared anywhere is not a finding",
         "servers:\n  - url: http://localhost:8101\n", "quarkus:\n  http:\n    root-path: /\n", False)

    # EMPTINESS: zero specs examined must not be reachable as a pass.
    with tempfile.TemporaryDirectory() as td:
        findings, examined = find_mismatches(pathlib.Path(td))
        if findings or examined != 0:
            fails.append(f"an empty tree should examine 0 specs and find none, got {examined}/{findings}")
    for label, n, want in (("zero examined is empty", 0, True), ("one examined is not", 1, False)):
        if reading_is_empty(n) != want:
            fails.append(f"{label}: reading_is_empty({n}) should be {want}")

    # And the real repo must still be readable — a fixture-only self-test cannot tell that the
    # glob and the skip-list still resolve against the tree they are written for.
    live_findings, live_examined = find_mismatches()
    if live_examined == 0:
        fails.append("reading the real repo examined ZERO specs — the layout or glob moved")

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print(f"self-test ok: openapi-server-port is falsifiable (7 cases + a live read of "
          f"{live_examined} spec(s))")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    findings, examined = find_mismatches()
    gatelib.subjects(examined, "openapi.yaml/application.yaml pairs examined")
    if reading_is_empty(examined):
        # Never report a pass about a corpus that was not read. Zero specs examined is what a
        # moved layout or a wrong CWD produces, and it prints identically to a clean fleet.
        print("::error::openapi-server-port: examined ZERO specs — the layout moved, "
              "the gate did not. Refusing to report a pass about nothing.")
        return 1
    if not findings:
        print(f"openapi-server-port: {examined} spec(s) examined; every declared dev port "
              f"matches quarkus.http.port")
        return 0
    for f in findings:
        print(f"::error::{f}" if args.enforce else f"::warning::{f}")
    if args.enforce:
        print(f"openapi-server-port: {len(findings)} spec(s) declare a dev port that disagrees "
              f"with quarkus.http.port (#2314) — correct the spec, not the check")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
