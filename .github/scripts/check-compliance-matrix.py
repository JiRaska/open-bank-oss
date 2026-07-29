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

Usage:
    check-compliance-matrix.py [--enforce]
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys

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
        yield from svc.rglob("*.kt")


def column_has_reader(column: str, cache: dict[str, bool]) -> bool:
    if column in cache:
        return cache[column]
    needle = column
    for kt in kotlin_sources():
        try:
            text = kt.read_text(encoding="utf-8", errors="ignore")
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


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    args = ap.parse_args()

    if not PAGE.is_file():
        print(f"::error::compliance page not found at {PAGE}")
        return 1

    text = PAGE.read_text(encoding="utf-8")
    items = iter_items(text)
    if not items:
        print("::error::no COMPLIANCE_AREAS items parsed — the page shape changed and the gate sees nothing")
        return 1

    findings = []
    reader_cache: dict[str, bool] = {}
    checked_columns = 0
    for status, req, note in items:
        for column in set(COLUMN_RE.findall(note)) - NOT_COLUMNS - ENFORCEMENT_DDL:
            checked_columns += 1
            if not column_has_reader(column, reader_cache) and status == "ok":
                findings.append(
                    f"row marked ok but cites a column no code reads: '{column}' "
                    f"(req: {req[:60]}…)"
                )
        for svc in set(SERVICE_RE.findall(note)):
            if not service_exists(svc):
                findings.append(f"cited service does not exist as a module: '{svc}'")

    print(f"compliance-matrix: {len(items)} rows, {checked_columns} column citations checked")
    for f in findings:
        print(("::error::" if args.enforce else "::warning::") + f)
    if findings and args.enforce:
        print(f"compliance-matrix: {len(findings)} finding(s) — fix the row or the evidence, not the gate (#2370)")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
