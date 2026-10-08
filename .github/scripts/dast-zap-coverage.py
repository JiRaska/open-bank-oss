#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Turn one authenticated ZAP API scan into a coverage record, and refuse a non-scan.

WHY (issue #12097). The DAST lane used to be a passive baseline spider aimed at one URL with no
token. Its last green run reached 6 unauthenticated GET URLs, every one 4xx, and reported no
operation count -- yet `ledger.pentest` was attested from it. A green ZAP run says nothing about
WHAT was scanned. This script says it, in numbers, and fails the job when the answer is "nothing".

WHERE THE COUNTS COME FROM. Not from ZAP. The service under test logs every request it RECEIVED
(Quarkus access log, a `ZAPACCESS <method> <path> <status>` line), and each line is matched back
to an operation of the service's committed openapi.yaml. Measuring at the receiving end means a
ZAP that silently imported nothing, or a header that never reached the wire, cannot inflate the
count: a request the service did not see is not a request.

THE RECORD (`<out-dir>/<service>-ops.json`) deliberately reuses the keys of the schemathesis
lanes' `fuzz-reports/<svc>-ops*.json` (#5769) -- `service`, `lane`, `selected`, `auth_blocked`,
`exercised`, `run`, `date` -- so a `pentest` attestation's `ops:` cites `exercised` here exactly
as it does there (check-readiness-attestations.py R8). Extra keys carry what only this lane has:
operations in the spec, responses by status class, ZAP alerts by risk.

  selected     spec operations the service received at least one request for
  auth_blocked of those, operations that only ever answered 401/403 (no handler logic ran)
  exercised    selected - auth_blocked  -- the number an attestation may cite

VERDICT. `scanned` or `not-a-scan`, and the second is a HARNESS failure, never a finding:
  * the service never became ready                         (--ready false)
  * zero spec operations requested
  * >= 95 % of matched responses were 401/403              (the token did not authenticate)
Findings (ZAP alerts) are reported and never fail the job -- they feed ADR-0030 D1 triage.

Usage:
  dast-zap-coverage.py --service S --spec openapi.yaml --access-log boot.log \
      [--zap-json report_json.json] --ready true|false --out-dir zap-reports [--run-url URL]
  dast-zap-coverage.py --self-test
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import sys
from pathlib import Path

METHODS = ("get", "put", "post", "delete", "patch", "head", "options", "trace")
ACCESS_RE = re.compile(r"ZAPACCESS (\S+) (\S+) (\d{3})")
AUTH_BLOCKED_RATIO = 0.95
LANE = "zap authenticated"
RISKS = {"3": "high", "2": "medium", "1": "low", "0": "informational"}


def spec_operations(spec: dict) -> list[tuple[str, str]]:
    ops = []
    for path, item in (spec.get("paths") or {}).items():
        if not isinstance(item, dict):
            continue
        for m in item:
            if m.lower() in METHODS:
                ops.append((m.upper(), path))
    return ops


def _matchers(ops: list[tuple[str, str]]):
    """Most-literal template first, so /journals/trial-balance wins over /journals/{journalId}."""
    out = []
    for method, path in ops:
        segs = path.strip("/").split("/")
        literal = sum(1 for s in segs if not s.startswith("{"))
        rx = "^/" + "/".join(r"[^/]+" if s.startswith("{") else re.escape(s) for s in segs) + "/?$"
        out.append((literal, len(segs), method, path, re.compile(rx)))
    out.sort(key=lambda t: (-t[0], -t[1]))
    return out


def status_class(code: int) -> str:
    return f"{code // 100}xx" if 2 <= code // 100 <= 5 else "other"


def alerts_by_risk(zap: dict | None) -> dict:
    counts = {v: 0 for v in RISKS.values()}
    for site in (zap or {}).get("site", []) or []:
        for a in site.get("alerts", []) or []:
            counts[RISKS.get(str(a.get("riskcode")), "informational")] += 1
    return counts


def evaluate(spec: dict, access_lines: list[str], zap: dict | None, ready: bool) -> dict:
    ops = spec_operations(spec)
    matchers = _matchers(ops)
    per_op: dict[tuple[str, str], list[int]] = {}
    unmatched = 0
    for line in access_lines:
        m = ACCESS_RE.search(line)
        if not m:
            continue
        method, path, code = m.group(1).upper(), m.group(2).split("?", 1)[0], int(m.group(3))
        hit = next((k for k in matchers if k[2] == method and k[4].match(path)), None)
        if hit is None:
            unmatched += 1
            continue
        per_op.setdefault((hit[2], hit[3]), []).append(code)

    responses = {"2xx": 0, "3xx": 0, "4xx": 0, "5xx": 0, "other": 0}
    auth_resp = total = 0
    for codes in per_op.values():
        for c in codes:
            responses[status_class(c)] += 1
            total += 1
            auth_resp += c in (401, 403)
    selected = len(per_op)
    auth_blocked = sum(1 for codes in per_op.values() if all(c in (401, 403) for c in codes))

    reasons = []
    if not ready:
        reasons.append("service never became ready")
    if selected == 0:
        reasons.append("zero spec operations were requested")
    elif auth_resp / total >= AUTH_BLOCKED_RATIO:
        reasons.append(
            f"{auth_resp}/{total} responses ({100 * auth_resp / total:.0f}%) were 401/403 "
            f"-- the scan never got past authentication")
    return {
        "lane": LANE,
        "verdict": "not-a-scan" if reasons else "scanned",
        "reasons": reasons,
        "spec_operations": len(ops),
        "selected": selected,
        "auth_blocked": auth_blocked,
        "exercised": selected - auth_blocked,
        "requests": total,
        "unmatched_requests": unmatched,
        "auth_rejected_responses": auth_resp,
        "responses": responses,
        "alerts": alerts_by_risk(zap),
        "not_requested": sorted(f"{m} {p}" for m, p in ops if (m, p) not in per_op),
    }


def summary_md(service: str, rec: dict) -> str:
    r, a = rec["responses"], rec["alerts"]
    head = "SCANNED" if rec["verdict"] == "scanned" else "NOT A SCAN (harness failure, not a finding)"
    lines = [
        f"## DAST (ZAP, authenticated) — `{service}`: {head}",
        "",
        "| Operations in spec | Requested | Auth-blocked | Exercised | Requests | Unmatched |",
        "|---:|---:|---:|---:|---:|---:|",
        f"| {rec['spec_operations']} | {rec['selected']} | {rec['auth_blocked']} | "
        f"{rec['exercised']} | {rec['requests']} | {rec['unmatched_requests']} |",
        "",
        "| 2xx | 3xx | 4xx | 5xx | of which 401/403 |",
        "|---:|---:|---:|---:|---:|",
        f"| {r['2xx']} | {r['3xx']} | {r['4xx']} | {r['5xx']} | {rec['auth_rejected_responses']} |",
        "",
        "| Alerts: High | Medium | Low | Informational |",
        "|---:|---:|---:|---:|",
        f"| {a['high']} | {a['medium']} | {a['low']} | {a['informational']} |",
        "",
    ]
    lines += [f"> ❌ {why}" for why in rec["reasons"]]
    if rec["not_requested"]:
        lines += ["", f"<details><summary>{len(rec['not_requested'])} spec operation(s) never "
                  "requested</summary>", ""] + [f"- `{o}`" for o in rec["not_requested"]] + ["", "</details>"]
    return "\n".join(lines) + "\n"


def self_test() -> int:
    spec = {"paths": {
        "/api/v1/journals": {"get": {}, "post": {}, "parameters": []},
        "/api/v1/journals/{journalId}": {"get": {}},
        "/api/v1/journals/trial-balance": {"get": {}},
        "/api/v1/ledger/periods/{type}/{date}/freeze": {"post": {}},
    }}
    L = lambda m, p, s: f"2026-10-04 INFO [io.qu.ht.access-log] ZAPACCESS {m} {p} {s}"  # noqa: E731
    zap = {"site": [{"alerts": [{"riskcode": "2"}, {"riskcode": "0"}, {"riskcode": "0"}]}]}
    cases = []

    def case(name, cond):
        cases.append((name, bool(cond)))

    good = evaluate(spec, [
        "unrelated boot line",
        L("GET", "/q/health", 200),
        L("GET", "/api/v1/journals", 200),
        L("POST", "/api/v1/journals", 400),
        L("GET", "/api/v1/journals/trial-balance", 200),
        L("GET", "/api/v1/journals/abc-123", 404),
        L("POST", "/api/v1/ledger/periods/MONTH/2026-09/freeze", 401),
    ], zap, True)
    case("spec counts every method x path (parameters key is not an operation)", good["spec_operations"] == 5)
    case("literal template wins over {param}", "GET /api/v1/journals/{journalId}" not in good["not_requested"]
         and good["selected"] == 5)
    case("non-spec request is unmatched, not counted", good["unmatched_requests"] == 1 and good["requests"] == 5)
    case("an op answering only 401 is auth-blocked", good["auth_blocked"] == 1 and good["exercised"] == 4)
    case("status classes", good["responses"] == {"2xx": 2, "3xx": 0, "4xx": 3, "5xx": 0, "other": 0})
    case("alerts by risk", good["alerts"] == {"high": 0, "medium": 1, "low": 0, "informational": 2})
    case("a real scan is scanned", good["verdict"] == "scanned" and not good["reasons"])

    nothing = evaluate(spec, [L("GET", "/q/health", 200)], None, True)
    case("zero requested ops is NOT a scan", nothing["verdict"] == "not-a-scan" and nothing["selected"] == 0)

    walled = [L("GET", "/api/v1/journals", 401)] * 19 + [L("GET", "/api/v1/journals/x", 200)]
    w = evaluate(spec, walled, None, True)
    case("95% 401/403 is NOT a scan (token did not authenticate)", w["verdict"] == "not-a-scan")
    below = evaluate(spec, [L("GET", "/api/v1/journals", 403)] * 18
                     + [L("GET", "/api/v1/journals/x", 200)] * 2, None, True)
    case("90% 401/403 is still a scan (threshold is >= 95%, not any 401)", below["verdict"] == "scanned")

    dead = evaluate(spec, [L("GET", "/api/v1/journals", 200)], None, False)
    case("never-ready is NOT a scan even with requests", dead["verdict"] == "not-a-scan"
         and "service never became ready" in dead["reasons"])
    case("findings never decide the verdict", evaluate(
        spec, [L("GET", "/api/v1/journals", 200)], {"site": [{"alerts": [{"riskcode": "3"}]}]}, True
    )["verdict"] == "scanned")
    case("query string does not break matching",
         evaluate(spec, [L("GET", "/api/v1/journals?x=1", 200)], None, True)["selected"] == 1)

    bad = [n for n, ok in cases if not ok]
    for n in bad:
        print(f"self-test FAIL: {n}")
    print(f"SUBJECTS={len(cases)}  # assertions")
    print("dast-zap-coverage self-test: " + ("clean" if not bad else f"{len(bad)} failure(s)"))
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--service")
    ap.add_argument("--spec", type=Path)
    ap.add_argument("--access-log", type=Path)
    ap.add_argument("--zap-json", type=Path)
    ap.add_argument("--ready", choices=("true", "false"), default="false")
    ap.add_argument("--out-dir", type=Path, default=Path("zap-reports"))
    ap.add_argument("--run-url", default="")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    if not (args.service and args.spec):
        ap.error("--service and --spec are required")

    import yaml  # only the real run needs it; the self-test stays dependency-free

    spec = yaml.safe_load(args.spec.read_text())
    lines = args.access_log.read_text(errors="replace").splitlines() \
        if args.access_log and args.access_log.is_file() else []
    zap = None
    if args.zap_json and args.zap_json.is_file():
        try:
            zap = json.loads(args.zap_json.read_text())
        except json.JSONDecodeError:
            zap = None
    rec = {"service": args.service, **evaluate(spec, lines, zap, args.ready == "true"),
           "zap_report": bool(zap), "run": args.run_url, "date": dt.date.today().isoformat()}

    args.out_dir.mkdir(parents=True, exist_ok=True)
    (args.out_dir / f"{args.service}-ops.json").write_text(json.dumps(rec, indent=2) + "\n")
    md = summary_md(args.service, rec)
    with open(os.environ.get("GITHUB_STEP_SUMMARY", os.devnull), "a") as fh:
        fh.write(md)
    print(md)
    if rec["verdict"] != "scanned":
        for why in rec["reasons"]:
            print(f"::error::[{args.service}] DAST NOT A SCAN — {why}. This is a harness failure, "
                  "not a finding about the HTTP surface; the run is not pentest evidence.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
