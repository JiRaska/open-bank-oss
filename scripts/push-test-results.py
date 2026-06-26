#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
# See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
"""
Push JUnit XML test results to openbank_ci.test_runs and openbank_ci.test_cases.

Usage:
  python3 scripts/push-test-results.py [service-name ...]
  python3 scripts/push-test-results.py          # all services

Env vars (with defaults for local dev):
  PGHOST     localhost
  PGPORT     5432
  PGUSER     openbank
  PGPASSWORD openbank_secret
  PGDATABASE openbank_ci
"""

import os, sys, re, subprocess
from datetime import datetime, timezone
from pathlib import Path

# ── config ───────────────────────────────────────────────────────────────────
ROOT = Path(__file__).parent.parent

SERVICES = [
    "openbank-account-service",
    "openbank-aml-service",
    "openbank-audit-service",
    "openbank-balance-service",
    "openbank-card-issuance-service",
    "openbank-clearing-service",
    "openbank-consent-service",
    "openbank-dispute-service",
    "openbank-domestic-payment",
    "openbank-fx-service",
    "openbank-interest-service",
    "openbank-kyc-service",
    "openbank-ledger-service",
    "openbank-notification-service",
    "openbank-party-service",
    "openbank-pid-service",
    "openbank-psd2-service",
    "openbank-sanctions-service",
    "openbank-sca-service",
    "openbank-security-scanner",
    "openbank-sepa-instant",
    "openbank-sepa-payment",
    "openbank-standing-order-service",
    "openbank-swift-service",
    "openbank-tpp-registry-service",
    "openbank-transaction-service",
]

PGHOST     = os.environ.get("PGHOST",     "localhost")
PGPORT     = os.environ.get("PGPORT",     "5432")
PGUSER     = os.environ.get("PGUSER",     "openbank")
PGPASSWORD = os.environ.get("PGPASSWORD", "openbank_secret")
PGDATABASE = os.environ.get("PGDATABASE", "openbank_ci")

# ── helpers ───────────────────────────────────────────────────────────────────
def attr(tag: str, name: str) -> str:
    m = re.search(rf'{name}="([^"]*)"', tag, re.I)
    return m.group(1) if m else "0"

def escape_sql(s: str) -> str:
    return s.replace("'", "''")

def parse_suite(path: Path) -> dict:
    xml = path.read_text(errors="replace")
    suite = (re.search(r'<testsuite[^>]*>', xml, re.I) or type('', (), {'group': lambda s, i: ''})()).group(0)
    test_class = attr(suite, "name") or path.stem.replace("TEST-", "")
    short_name = test_class.split(".")[-1]
    test_type = "integration" if short_name.endswith("IT") else "unit"
    return {
        "test_class":  test_class,
        "test_type":   test_type,
        "tests":       int(attr(suite, "tests"))    or 0,
        "failures":    int(attr(suite, "failures")) or 0,
        "errors":      int(attr(suite, "errors"))   or 0,
        "skipped":     int(attr(suite, "skipped"))  or 0,
        "duration_ms": int(float(attr(suite, "time") or "0") * 1000),
    }

def parse_testcases(path: Path) -> list[dict]:
    xml = path.read_text(errors="replace")
    suite_m = re.search(r'<testsuite[^>]*>', xml, re.I)
    if not suite_m:
        return []
    suite_tag = suite_m.group(0)
    test_class = attr(suite_tag, "name") or path.stem.replace("TEST-", "")
    short_name = test_class.split(".")[-1]
    test_type = "integration" if short_name.endswith("IT") else "unit"

    cases = []
    for m in re.finditer(r'<testcase([^>]*)>(.*?)</testcase>|<testcase([^>]*)/>', xml, re.DOTALL | re.I):
        tag  = m.group(1) or m.group(3) or ""
        body = m.group(2) or ""
        name = attr(tag, "name")
        if not name:
            continue
        duration_ms = int(float(attr(tag, "time") or "0") * 1000)

        if re.search(r'<failure', body, re.I):
            status = "failed"
            fm = re.search(r'<failure[^>]*>(.*?)</failure>', body, re.DOTALL | re.I)
            failure_message = fm.group(1).strip()[:2000] if fm else ""
        elif re.search(r'<error', body, re.I):
            status = "error"
            em = re.search(r'<error[^>]*>(.*?)</error>', body, re.DOTALL | re.I)
            failure_message = em.group(1).strip()[:2000] if em else ""
        elif re.search(r'<skipped', body, re.I):
            status = "skipped"
            failure_message = None
        else:
            status = "passed"
            failure_message = None

        cases.append({
            "test_class":      test_class,
            "test_type":       test_type,
            "test_name":       name,
            "status":          status,
            "duration_ms":     duration_ms,
            "failure_message": failure_message,
        })
    return cases

def git_info() -> tuple[str, str]:
    def run(cmd):
        try:
            return subprocess.check_output(cmd, cwd=ROOT, stderr=subprocess.DEVNULL).decode().strip()
        except Exception:
            return ""
    return run(["git", "rev-parse", "--short", "HEAD"]), run(["git", "rev-parse", "--abbrev-ref", "HEAD"])

PSQL = next(
    (p for p in [
        "/opt/homebrew/opt/libpq/bin/psql",
        "/usr/local/bin/psql",
        "/usr/bin/psql",
    ] if os.path.exists(p)),
    "psql",
)

def psql(sql: str) -> None:
    env = {**os.environ, "PGPASSWORD": PGPASSWORD}
    result = subprocess.run(
        [PSQL, "-h", PGHOST, "-p", PGPORT, "-U", PGUSER, "-d", PGDATABASE, "-c", sql],
        env=env, capture_output=True, text=True
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip())

# ── main ─────────────────────────────────────────────────────────────────────
def main():
    targets = sys.argv[1:] if len(sys.argv) > 1 else SERVICES
    commit, branch = git_info()
    run_at = datetime.now(timezone.utc).isoformat()
    suites_inserted = 0
    cases_inserted  = 0

    for service in targets:
        xml_dir = ROOT / service / "build" / "test-results" / "test"
        files = sorted(xml_dir.glob("TEST-*.xml")) if xml_dir.exists() else []
        if not files:
            print(f"  skip {service}: no XML files")
            continue

        for f in files:
            r = parse_suite(f)
            passed = r["tests"] - r["failures"] - r["errors"] - r["skipped"]
            psql(
                "INSERT INTO test_runs"
                " (service, test_class, test_type, tests, passed, failed, errors, skipped, duration_ms, run_at, commit_sha, branch)"
                f" VALUES ("
                f"  '{service}',"
                f"  '{escape_sql(r['test_class'])}',"
                f"  '{r['test_type']}',"
                f"  {r['tests']}, {passed}, {r['failures']}, {r['errors']}, {r['skipped']},"
                f"  {r['duration_ms']},"
                f"  '{run_at}',"
                f"  {repr(commit) if commit else 'NULL'},"
                f"  {repr(branch) if branch else 'NULL'}"
                f");"
            )
            suites_inserted += 1

            cases = parse_testcases(f)
            for c in cases:
                fm_sql = f"'{escape_sql(c['failure_message'])}'" if c["failure_message"] is not None else "NULL"
                psql(
                    "INSERT INTO test_cases"
                    " (service, test_class, test_type, test_name, status, duration_ms, failure_message, run_at)"
                    f" VALUES ("
                    f"  '{service}',"
                    f"  '{escape_sql(c['test_class'])}',"
                    f"  '{c['test_type']}',"
                    f"  '{escape_sql(c['test_name'])}',"
                    f"  '{c['status']}',"
                    f"  {c['duration_ms']},"
                    f"  {fm_sql},"
                    f"  '{run_at}'"
                    f");"
                )
                cases_inserted += 1

        print(f"  ok {service}: {len(files)} suite(s), {sum(len(parse_testcases(f)) for f in files)} test cases")

    print(f"\nInserted {suites_inserted} suite rows and {cases_inserted} test case rows into {PGDATABASE}")

if __name__ == "__main__":
    main()
