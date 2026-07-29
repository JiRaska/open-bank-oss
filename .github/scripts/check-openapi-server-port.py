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

REPO = pathlib.Path(__file__).resolve().parents[2]
SKIP_DIRS = {"openbank-libs", "openbank-libs-domain", "openbank-libs-runtime",
             "openbank-admin-ui", "openbank-infra"}


def find_mismatches() -> list[str]:
    findings = []
    for svc_dir in sorted(REPO.glob("openbank-*")):
        if not svc_dir.is_dir() or svc_dir.name in SKIP_DIRS:
            continue
        spec = svc_dir / "src" / "main" / "resources" / "openapi.yaml"
        app = svc_dir / "src" / "main" / "resources" / "application.yaml"
        if not spec.is_file() or not app.is_file():
            continue
        spec_text = spec.read_text(encoding="utf-8")
        app_text = app.read_text(encoding="utf-8")
        m_srv = re.search(r"url:\s*https?://[^/:]+:(\d+)", spec_text)
        if not m_srv:
            continue  # prefix-only server (e.g. /api/v1) — no port to drift
        m_port = re.search(r"^\s*port:\s*(\d+)", app_text, re.M)
        if not m_port:
            continue  # no explicit quarkus.http.port — nothing to compare against
        if m_srv.group(1) != m_port.group(1):
            findings.append(
                f"{svc_dir.name}: openapi.yaml servers port {m_srv.group(1)} "
                f"!= quarkus.http.port {m_port.group(1)}"
            )
    return findings


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    args = ap.parse_args()

    findings = find_mismatches()
    if not findings:
        print("openapi-server-port: every spec's declared dev port matches quarkus.http.port")
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
