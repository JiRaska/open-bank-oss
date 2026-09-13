#!/usr/bin/env python3
"""Compliance-matrix evidence gate (issue #2370).

The admin-ui compliance page renders 46 regulatory-conformance claims from a hardcoded
`COMPLIANCE_AREAS` literal in `openbank-admin-ui/src/app/docs/compliance/page.tsx`. Until
#2667 landed the warn rows by hand, the page was wrong in BOTH directions: GDPR rows marked
`ok` cited database columns that exist only in a Flyway migration (no code reads or writes
them), and the erasure row described a mechanism ADR-0118 replaced. Hand-corrected rows rot
the same way the original ones did — this gate is what stops the next silent drift.

What it verifies, mechanically:

1. **Every snake_case column cited in an item's note has a code reader.** A column that
   appears only in `**/db/migration/**` is DDL, not a control — #2370's exact failure class
   (`marketing_consent`, `gdpr_consent_at`, `deleted_at`, `data_retention_until`,
   `data_sensitivity` were all `ok` on that basis). The reader search covers every
   `openbank-*/src/main` Kotlin source and the admin-ui's own TypeScript.
2. **A row citing a column with NO reader must be `warn`, not `ok`.** A green claim with
   dead evidence is the finding this gate exists for; a `warn` row with live evidence is
   reported too (over-caution rots the signal the other way).
3. **Every `<name>-service` cited resolves to a real module** (`openbank-<name>-service` or
   `openbank-<name>` dir) — the "service X implements Y" rows.

Deliberately NOT checked: the prose half of every claim (feature descriptions like
"implements OTP + FIDO2", "EBA register sync") — that needs judgement, and a mechanical
verdict there would be the same fiction in a new costume. The gate guards the *evidence*
references, the part that can be made honest by construction.

stdlib-only; reads the working tree so it runs identically in CI and locally.

Registered in `.github/gates/gates.yaml` as `compliance-matrix`. It was written for #2370 and
then referenced from nowhere — no workflow, no manifest entry, and nothing in this repo
iterates `.github/scripts/check-*` (`run-gates.py` reads the manifest, not the directory). So
for its whole life the drift it exists to stop was unstopped (#3240). `--self-test` exists for
the same reason the registration does: a gate that has only ever passed is unfalsified.

Usage:
    check-compliance-matrix.py [--enforce] [--self-test]
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys

import gatelib

REPO = pathlib.Path(__file__).resolve().parents[2]
PAGE = REPO / "openbank-admin-ui/src/app/docs/compliance/page.tsx"

# snake_case tokens that are clearly not database columns (status enums, common words in
# the bilingual prose, constraint names from migrations).
NOT_COLUMNS = {
    "ok", "warn", "error", "info", "redirect_uri", "end_to_end_id", "charge_bearer",
    "pep_flag", "aml_screened", "frequency_per_day",
}
# Enforcement DDL, not storage: a PostgreSQL RULE/TRIGGER *is* the control (the DB engine
# enforces it), so "no code reads it" is the wrong verdict — unlike a dead column, which
# stores nothing and proves nothing. The immutable-audit-log row's evidence is these rules.
ENFORCEMENT_DDL = {
    "no_update_audit", "no_delete_audit", "no_update_audit_anchor", "no_delete_audit_anchor",
}
SERVICE_RE = re.compile(r"\b([a-z][a-z0-9-]*-service)\b")
COLUMN_RE = re.compile(r"\b([a-z][a-z0-9]*(?:_[a-z0-9]+)+)\b")


def iter_items(text: str) -> list[tuple[str, str, str]]:
    """Yield (status, req_text, note_text) for every COMPLIANCE_AREAS item."""
    items = []
    for m in re.finditer(
        r"\{\s*req:\s*\[([^\]]+)\],\s*status:\s*'([a-z]+)',\s*note:\s*\[([^\]]+)\]\s*\}",
        text,
    ):
        items.append((m.group(2), m.group(1), m.group(3)))
    return items


def kotlin_sources():
    for svc in REPO.glob("openbank-*/src/main"):
        if "migration" in svc.parts:
            continue
        yield from gatelib.rglob(svc, "*.kt")


def column_has_reader(column: str, cache: dict[str, bool]) -> bool:
    if column in cache:
        return cache[column]
    needle = column
    for kt in kotlin_sources():
        try:
            text = gatelib.read_text(kt, errors="ignore")
        except OSError:
            continue
        if needle in text:
            cache[column] = True
            return True
    cache[column] = False
    return False


def service_exists(name: str) -> bool:
    for cand in (f"openbank-{name}", f"openbank-{name.removesuffix('-service')}-service"):
        if (REPO / cand).is_dir():
            return True
    return False


def analyse() -> tuple[list[str], list[str], int, int]:
    """Return (findings, notices, row_count, checked_column_count).

    `findings` are the direction that must be able to fail the build: a row claiming `ok` on
    evidence nothing reads. `notices` are the opposite direction — a `warn` row whose cited
    column IS live — which the docstring has always promised to report and which the first
    implementation silently omitted. It is reported and never fails: over-caution rots the
    signal, but it does not publish a false green, and a gate that goes red on someone being
    too careful teaches people to mark rows `ok`.
    """
    text = gatelib.read_text(PAGE)
    items = iter_items(text)
    if not items:
        return (
            ["no COMPLIANCE_AREAS items parsed — the page shape changed and the gate sees nothing"],
            [],
            0,
            0,
        )

    findings: list[str] = []
    notices: list[str] = []
    reader_cache: dict[str, bool] = {}
    checked_columns = 0
    for status, req, note in items:
        for column in sorted(set(COLUMN_RE.findall(note)) - NOT_COLUMNS - ENFORCEMENT_DDL):
            checked_columns += 1
            live = column_has_reader(column, reader_cache)
            if not live and status == "ok":
                findings.append(
                    f"row marked ok but cites a column no code reads: '{column}' "
                    f"(req: {req[:60]}…)"
                )
            elif live and status == "warn":
                notices.append(
                    f"row marked warn but its cited column '{column}' has a live reader — "
                    f"check whether the row can be promoted (req: {req[:60]}…)"
                )
        for svc in sorted(set(SERVICE_RE.findall(note))):
            if not service_exists(svc):
                findings.append(f"cited service does not exist as a module: '{svc}'")

    return findings, notices, len(items), checked_columns


def _fixture(tmp: pathlib.Path, rows: str, kotlin: str = "", services: tuple[str, ...] = ()) -> None:
    """Build a throwaway repo the real code paths can run against unchanged.

    Deliberately a real directory tree rather than a stubbed reader: `column_has_reader` and
    `service_exists` walk the filesystem, and a test that replaced them would be asserting
    against its own re-implementation rather than against the gate (the failure #3349 is open
    about elsewhere in this repo).
    """
    page = tmp / "openbank-admin-ui/src/app/docs/compliance/page.tsx"
    page.parent.mkdir(parents=True, exist_ok=True)
    page.write_text(f"const COMPLIANCE_AREAS = [\n{rows}\n]\n", encoding="utf-8")
    src = tmp / "openbank-demo-service/src/main/kotlin"
    src.mkdir(parents=True, exist_ok=True)
    (src / "Demo.kt").write_text(kotlin or "class Demo\n", encoding="utf-8")
    for svc in services:
        (tmp / svc / "src/main").mkdir(parents=True, exist_ok=True)


def _run_against(tmp: pathlib.Path) -> tuple[list[str], list[str]]:
    """Point the module at `tmp` and analyse it, restoring the real paths afterwards."""
    global REPO, PAGE
    real_repo, real_page = REPO, PAGE
    REPO, PAGE = tmp, tmp / "openbank-admin-ui/src/app/docs/compliance/page.tsx"
    try:
        findings, notices, _, _ = analyse()
        return findings, notices
    finally:
        REPO, PAGE = real_repo, real_page


def selftest() -> int:
    """Feed each rule an input it MUST flag and one it must NOT."""
    import tempfile

    ok_dead = "  { req: ['R'], status: 'ok', note: ['stores ghost_column'] },"
    ok_live = "  { req: ['R'], status: 'ok', note: ['stores ghost_column'] },"
    warn_live = "  { req: ['R'], status: 'warn', note: ['stores ghost_column'] },"
    warn_dead = "  { req: ['R'], status: 'warn', note: ['stores ghost_column'] },"
    bad_svc = "  { req: ['R'], status: 'warn', note: ['implemented by unicorn-service'] },"
    real_svc = "  { req: ['R'], status: 'warn', note: ['implemented by demo-service'] },"
    reader = 'val x = row["ghost_column"]\n'

    cases = [
        # (label, rows, kotlin, services, expect_findings, expect_notices)
        ("ok row citing a column nothing reads", ok_dead, "", (), 1, 0),
        ("ok row whose column has a reader", ok_live, reader, (), 0, 0),
        ("warn row whose column has a reader", warn_live, reader, (), 0, 1),
        ("warn row citing a dead column", warn_dead, "", (), 0, 0),
        ("a cited service that is not a module", bad_svc, "", (), 1, 0),
        ("a cited service that exists", real_svc, "", ("openbank-demo-service",), 0, 0),
    ]
    for label, rows, kotlin, services, want_f, want_n in cases:
        with tempfile.TemporaryDirectory() as d:
            tmp = pathlib.Path(d)
            _fixture(tmp, rows, kotlin, services)
            findings, notices = _run_against(tmp)
        if len(findings) != want_f or len(notices) != want_n:
            print(
                f"selftest FAIL: {label} — expected {want_f} finding(s)/{want_n} notice(s), "
                f"got {len(findings)}/{len(notices)}: {findings or notices}"
            )
            return 1

    # A page the parser cannot read must be a finding, never a silent pass. This is the
    # failure mode that would make every other case above vacuous: a shape change to the
    # page turns "0 rows parsed" into "0 findings", which reads exactly like a clean run.
    with tempfile.TemporaryDirectory() as d:
        tmp = pathlib.Path(d)
        _fixture(tmp, "  // the literal was refactored away")
        findings, _ = _run_against(tmp)
    if not findings:
        print("selftest FAIL: an unparseable page produced no finding — the gate would go green on nothing")
        return 1

    print(f"selftest OK: {len(cases)} fixture(s) both ways, plus the unparseable-page case.")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true", dest="self_test",
                    help="prove the gate can fail, against fixtures it must and must not flag")
    args = ap.parse_args()

    if args.self_test:
        return selftest()

    if not PAGE.is_file():
        print(f"::error::compliance page not found at {PAGE}")
        return 1

    findings, notices, rows, checked_columns = analyse()

    # The Kotlin corpus every column citation is resolved against. Measured 2026-09-03: with
    # openbank-ledger-service renamed away this gate stayed green, because a column whose
    # reader has vanished reads the same as a column that never needed one. Emitting the count
    # lets run-gates' min_subjects floor tell those apart.
    gatelib.subjects(sum(1 for _ in kotlin_sources()), "Kotlin sources the citations resolve against")

    print(f"compliance-matrix: {rows} rows, {checked_columns} column citations checked")
    for f in findings:
        print(("::error::" if args.enforce else "::warning::") + f)
    for n in notices:
        print("::warning::" + n)
    if findings and args.enforce:
        print(f"compliance-matrix: {len(findings)} finding(s) — fix the row or the evidence, not the gate (#2370)")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
