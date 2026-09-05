#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Aggregate the three ADR-0279 security KPIs into openbank-admin-ui/security-kpis.json.

WHY. The excellence console (runbook 0016) reads read-only endpoints; the three signals
landed by #8619/#8666/#8695/#8700 live in CI logs and standing issues. This collector is
the bridge: it recomputes the numbers FROM THE SAME SCRIPTS the gates run (never a
reimplementation that could drift), and writes one snapshot the admin-ui image bakes at
build time — the `security-report.json` pattern documented in /api/security/route.ts.

Sources:
  * netpol coverage  — check-netpol-coverage.py --root . --enforce (rc + stdout parse)
  * dependency freshness — deps-freshness.py --json (Maven Central; degrade to null
    on outage — a missing number renders as 'unavailable', never as 0)
  * credential inventory — credential-inventory.py --inventory (counts parsed from stdout)

Usage:  security-kpis.py [--out openbank-admin-ui/security-kpis.json] [--skip-freshness]
        security-kpis.py --self-test
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import subprocess
import sys
from pathlib import Path

OUT_DEFAULT = Path("openbank-admin-ui/security-kpis.json")


def run_json(cmd: list[str]) -> tuple[int, str]:
    res = subprocess.run(cmd, capture_output=True, text=True)
    return res.returncode, res.stdout


def collect_netpol() -> dict:
    rc, out = run_json([sys.executable, ".github/scripts/check-netpol-coverage.py",
                        "--root", ".", "--enforce"])
    m = re.search(r"netpol-coverage: (\d+)/(\d+) components.*\((\d+)%\)", out)
    if not m:
        return {"available": False, "reason": "collector output unparsable"}
    return {"available": True, "covered": int(m.group(1)), "total": int(m.group(2)),
            "coveragePct": int(m.group(3)), "gateGreen": rc == 0}


def collect_freshness(tmp: Path) -> dict:
    rc, out = run_json([sys.executable, ".github/scripts/deps-freshness.py",
                        "--json", str(tmp / "fresh.json")])
    f = tmp / "fresh.json"
    if rc != 0 or not f.exists():
        return {"available": False, "reason": "collector failed"}
    data = json.loads(f.read_text())
    if data.get("fleet") is None:
        return {"available": False, "reason": "no scored modules (Maven Central outage?)"}
    return {"available": True, "fleetScore": data["fleet"],
            "scoredModules": data["scored_modules"], "unknownModules": data["unknown_modules"]}


def collect_credentials() -> dict:
    rc, out = run_json([sys.executable, ".github/scripts/credential-inventory.py",
                        "--inventory", "--today", dt.date.today().isoformat()])
    m = re.search(r"credential-inventory: (\d+) ExternalSecrets, (\d+) static, "
                  r"(\d+) with deadline, (\d+) overdue, (\d+) undeclared", out)
    if not m:
        return {"available": False, "reason": "collector output unparsable"}
    total, static, declared, overdue, undeclared = map(int, m.groups())
    return {"available": True, "totalSecrets": total, "staticSecrets": static,
            "withDeadline": declared, "overdue": overdue, "undeclared": undeclared,
            "overdueFound": rc != 0}


def collect_threat_models() -> dict:
    """Threat-model freshness over money-path services (ADR-0279 #23 hub signal).

    Age = days since the model's last git change (the publisher checks out with
    fetch-depth: 0, so the history is real). A model untouched for > 90 days counts as
    stale — the fleet rule is that a trust-boundary change must touch it (gate
    threat-model-updated-on-trust-boundary-change), and a year-old money-path model is
    prose nobody has re-derived from the code.
    """
    # rules.yaml money_path_services is a flat dash list under one top-level key — BUT the
    # entries carry multi-line trailing comments (issue #1478 assessments), so capture up to
    # the next top-level key and take only `  - ` lines. A two-regex parse avoids a PyYAML
    # install in the publisher job (and the hash-pinning Scorecard would demand of it).
    raw = Path("openbank-libs/governance/rules.yaml").read_text()
    block = re.search(r"^money_path_services:\n(.*?)(?=^\S)", raw, re.M | re.S)
    if not block:
        return {"available": False, "reason": "money_path_services block unparsable"}
    money = re.findall(r"^  - (\S+)", block.group(1), re.M)
    now = dt.datetime.now(dt.timezone.utc).timestamp()
    ages, missing = [], []
    for svc in money:
        f = Path("docs/threat-models") / f"{svc}.md"
        if not f.is_file():
            missing.append(svc)
            continue
        rc, out = run_json(["git", "log", "-1", "--format=%ct", "--", str(f)])
        if rc != 0 or not out.strip():
            missing.append(svc)
            continue
        ages.append((svc, int((now - int(out.strip())) / 86400)))
    if not ages and missing:
        return {"available": False, "reason": "no money-path threat model resolved"}
    stale = sum(1 for _, a in ages if a > 90)
    return {"available": True, "moneyPathTotal": len(money),
            "withModel": len(ages), "missing": missing,
            "staleCount": stale, "oldestDays": max((a for _, a in ages), default=0)}


def collect_mttr() -> dict:
    """Vulnerability remediation time from Dependabot alerts (SLO S1 proxy, ADR-0279 #23).

    The release-SBOM diff the SLO table names as S1's final source is not wired yet; the
    Dependabot alert stream is the computable proxy that exists TODAY: fixed alerts carry
    created_at→fixed_at, open Critical/High carry age. Median, never a mean — one quarter-
    old alert must not hide behind fifty same-day fixes.
    """
    import os
    import statistics
    import urllib.error
    import urllib.request

    # GITHUB_TOKEN has no permission class for the dependabot-alerts API and gets a flat 403;
    # the workflow passes the elevated METADATA_REFRESH_PAT (when the secret exists) via
    # MTTR_GH_TOKEN. With neither, stay unavailable with a reason that names the cause —
    # never invent a number.
    token = (os.environ.get("MTTR_GH_TOKEN") or os.environ.get("GH_TOKEN")
             or os.environ.get("GITHUB_TOKEN"))
    repo = os.environ.get("GITHUB_REPOSITORY", "JiRaska/open-bank-oss")
    if not token:
        return {"available": False, "reason": "no GH token"}
    try:
        req = urllib.request.Request(
            f"https://api.github.com/repos/{repo}/dependabot/alerts?per_page=100&state=all",
            headers={"Authorization": f"Bearer {token}",
                     "Accept": "application/vnd.github+json"})
        alerts = json.loads(urllib.request.urlopen(req, timeout=30).read())
        now = dt.datetime.now(dt.timezone.utc)
        fixed_days, open_crit = [], []
        for a in alerts:
            sev = (a.get("security_advisory") or {}).get("severity", "")
            if sev not in ("critical", "high"):
                continue
            created = dt.datetime.fromisoformat(a["created_at"].replace("Z", "+00:00"))
            if a.get("state") == "fixed" and a.get("fixed_at"):
                fixed = dt.datetime.fromisoformat(a["fixed_at"].replace("Z", "+00:00"))
                fixed_days.append((fixed - created).total_seconds() / 86400)
            elif a.get("state") == "open":
                open_crit.append((now - created).total_seconds() / 86400)
        return {"available": True, "severityScope": "critical+high",
                "fixedCount": len(fixed_days),
                "medianFixDays": round(statistics.median(fixed_days), 1) if fixed_days else None,
                "openCount": len(open_crit),
                "oldestOpenDays": round(max(open_crit)) if open_crit else 0}
    except urllib.error.HTTPError as exc:
        if exc.code == 403:
            return {"available": False,
                    "reason": "dependabot alerts API answered 403 — the token lacks access "
                              "(GITHUB_TOKEN can never read it; set a security-scoped "
                              "METADATA_REFRESH_PAT)"}
        return {"available": False, "reason": f"dependabot fetch failed: HTTP {exc.code}"}
    except Exception as exc:  # API degradation must not kill the other collectors
        return {"available": False, "reason": f"dependabot fetch failed: {exc.__class__.__name__}"}


def _no_auth_redirect_opener():
    """An urllib opener that DROPS the Authorization header on a cross-host redirect.

    `archive_download_url` 302s to a signed Azure blob URL. urllib's default redirect re-sends
    every original header — including `Authorization: Bearer <github-token>` — to the new host,
    and Azure answers 401 "Server failed to authenticate the request". Measured live against
    artifact 9967002015 (2026-09-05): the same URL and token download fine via `gh api` and fail
    with 401 via default urllib. The fuzz collector's first refresh (run 33964441963) therefore
    reported "fuzz artifact fetch failed: HTTPError" with the artifact sitting right there.
    """
    import urllib.parse
    import urllib.request

    class _NoAuthRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: ANN001 - stdlib signature
            new = super().redirect_request(req, fp, code, msg, headers, newurl)
            if new is not None and \
                    urllib.parse.urlparse(newurl).netloc != urllib.parse.urlparse(req.full_url).netloc:
                new.remove_header("Authorization")
            return new

    return urllib.request.build_opener(_NoAuthRedirect)


def collect_fuzz() -> dict:
    """DAST coverage from the latest api-fuzz run's fuzz-coverage artifact (ADR-0279 #2).

    Reads the artifact the aggregate job uploads — NEVER the job list: a listed leg is
    not evidence the service was tested (#6492). Degrades to unavailable without a token
    or when no run has produced the artifact yet (pre-merge of the aggregate job), never
    invents a number.
    """
    import io
    import os
    import urllib.request
    import zipfile

    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    repo = os.environ.get("GITHUB_REPOSITORY", "JiRaska/open-bank-oss")
    if not token:
        return {"available": False, "reason": "no GH token"}

    def gh(path: str) -> bytes:
        req = urllib.request.Request(
            f"https://api.github.com/repos/{repo}/{path}",
            headers={"Authorization": f"Bearer {token}",
                     "Accept": "application/vnd.github+json"})
        return urllib.request.urlopen(req, timeout=30).read()

    try:
        runs = json.loads(gh("actions/workflows/api-fuzz.yml/runs?status=completed&per_page=10"))
        artifacts = []
        for run in runs.get("workflow_runs", []):
            arts = json.loads(gh(f"actions/runs/{run['id']}/artifacts?per_page=100"))
            hit = [a for a in arts.get("artifacts", [])
                   if a["name"] == "fuzz-coverage" and not a.get("expired")]
            if hit:
                artifacts = hit
                break
        if not artifacts:
            return {"available": False, "reason": "no fuzz-coverage artifact yet"}
        req = urllib.request.Request(
            artifacts[0]["archive_download_url"],
            headers={"Authorization": f"Bearer {token}",
                     "Accept": "application/vnd.github+json"})
        # Cross-host redirect must not carry the GitHub token — see _no_auth_redirect_opener.
        blob = _no_auth_redirect_opener().open(req, timeout=30).read()
        cov = json.loads(zipfile.ZipFile(io.BytesIO(blob)).read("fuzz-coverage.json"))
        return {"available": True, "inScope": cov["inScope"], "tested": cov["tested"],
                "coveragePct": cov["coveragePct"], "totalExercised": cov["totalExercised"],
                "excludedCount": len(cov.get("excluded", [])),
                "run": cov.get("run", ""), "runDate": cov.get("generatedAt", "")[:10]}
    except Exception as exc:  # network/API degradation must not kill the other collectors
        return {"available": False, "reason": f"fuzz artifact fetch failed: {exc.__class__.__name__}"}
def self_test() -> int:
    bad = 0
    # The parse regexes must survive the real scripts' output shape — pin them on the
    # exact strings the scripts print today (a drift here means the console goes blank,
    # which is exactly what this test exists to catch first).
    netpol_line = "netpol-coverage: 59/73 components carry the generated ingress policy (81%); 14 uncovered"
    if not re.search(r"netpol-coverage: (\d+)/(\d+) components.*\((\d+)%\)", netpol_line):
        print("self-test FAIL: netpol regex"); bad += 1
    cred_line = ("credential-inventory: 165 ExternalSecrets, 158 static, 0 with deadline, "
                 "0 overdue, 158 undeclared")
    m = re.search(r"credential-inventory: (\d+) ExternalSecrets, (\d+) static, "
                  r"(\d+) with deadline, (\d+) overdue, (\d+) undeclared", cred_line)
    if not m or m.group(2) != "158":
        print("self-test FAIL: credential regex"); bad += 1
    # The fuzz collector's artifact download 302s cross-host to Azure; the opener MUST drop
    # Authorization there (a forwarded Bearer gets a 401) and KEEP it for api.github.com.
    import urllib.request
    opener = _no_auth_redirect_opener()
    handler = next(h for h in opener.handlers
                   if isinstance(h, urllib.request.HTTPRedirectHandler))
    src = urllib.request.Request("https://api.github.com/x/artifacts/1/zip",
                                 headers={"Authorization": "Bearer t"})
    cross = handler.redirect_request(src, None, 302, "", {},
                                     "https://objects.githubusercontent.com/blob")
    if cross is None or cross.has_header("Authorization"):
        print("self-test FAIL: Authorization leaks across the artifact redirect"); bad += 1
    same = handler.redirect_request(src, None, 302, "", {}, "https://api.github.com/x/other")
    if same is None or not same.has_header("Authorization"):
        print("self-test FAIL: Authorization dropped on a same-host redirect"); bad += 1
    print("security-kpis self-test: " + ("clean" if not bad else f"{bad} failure(s)"))
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(OUT_DEFAULT))
    ap.add_argument("--skip-freshness", action="store_true",
                    help="offline smoke: skip the Maven Central collector")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()

    import tempfile
    with tempfile.TemporaryDirectory() as td:
        freshness = ({"available": False, "reason": "skipped"} if args.skip_freshness
                     else collect_freshness(Path(td)))
        snapshot = {
            "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
            "netpol": collect_netpol(),
            "freshness": freshness,
            "credentials": collect_credentials(),
            "fuzz": collect_fuzz(),
            "threatModels": collect_threat_models(),
            "mttr": collect_mttr(),
        }
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(snapshot, indent=2) + "\n")
    avail = sum(1 for k in ("netpol", "freshness", "credentials", "fuzz",
                            "threatModels", "mttr") if snapshot[k]["available"])
    print(f"security-kpis: {avail}/6 collectors available -> {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
