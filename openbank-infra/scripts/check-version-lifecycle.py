#!/usr/bin/env python3
"""FinOps managed-service version-lifecycle gate / audit (ADR-0054).

Enforces `rules.yaml: finops`: a managed-service version pinned in infra must be
on STANDARD support and have >= min_standard_support_months of standard support
left, selected "latest minus one" (N-1) for maturity. Catches the #84 mistake
(provisioning EKS 1.31 when it was already in paid extended support) at PR time.

stdlib only — runs in PR CI with no cloud credentials.

Modes:
  (default)   gate    -> print findings, exit 1 if the pinned version violates policy
  --report            -> print a markdown report to stdout for the weekly audit
                         issue; exit 0. First line is `FINOPS_FINDING=0|1` so the
                         workflow can decide whether to open/update an issue.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]
RULES = REPO / "openbank-libs" / "governance" / "rules.yaml"
DEFAULT_TABLE = REPO / "openbank-infra" / "aws" / "finops" / "eks-version-lifecycle.json"

DEFAULT_MIN_MONTHS = 6
DEFAULT_FORBID_EXTENDED = True
DAYS_PER_MONTH = 30.44


def _ver_key(v: str) -> tuple[int, ...]:
    return tuple(int(p) for p in v.split("."))


def load_policy() -> tuple[int, bool]:
    """Read the two scalars the gate needs from rules.yaml (single source of truth).

    Regex rather than a YAML lib so the check stays stdlib-only; falls back to the
    ADR-0054 defaults with a warning if the finops block is absent.
    """
    min_months, forbid = DEFAULT_MIN_MONTHS, DEFAULT_FORBID_EXTENDED
    try:
        text = RULES.read_text(encoding="utf-8")
    except OSError:
        print(f"WARN: cannot read {RULES}; using ADR-0054 defaults", file=sys.stderr)
        return min_months, forbid
    m = re.search(r"min_standard_support_months:\s*(\d+)", text)
    if m:
        min_months = int(m.group(1))
    f = re.search(r"forbid_extended_support:\s*(true|false)", text)
    if f:
        forbid = f.group(1) == "true"
    return min_months, forbid


def parse_pinned_version(tofu_file: pathlib.Path, key: str) -> str | None:
    text = tofu_file.read_text(encoding="utf-8")
    block = re.search(
        r'variable\s+"' + re.escape(key) + r'"\s*\{.*?\}',
        text,
        re.DOTALL,
    )
    scope = block.group(0) if block else text
    m = re.search(r'default\s*=\s*"(\d+\.\d+)"', scope)
    return m.group(1) if m else None


def classify(version: str, versions: dict, today: dt.date, min_months: int):
    """Return (status, runway_months_or_None) for a version against today."""
    info = versions.get(version)
    if not info:
        return "unknown", None
    eos = dt.date.fromisoformat(info["end_of_standard_support"])
    eoe = dt.date.fromisoformat(info["end_of_extended_support"])
    runway = (eos - today).days / DAYS_PER_MONTH
    if today >= eoe:
        return "unsupported", runway
    if today >= eos:
        return "extended", runway
    return "standard", runway


def recommend(versions: dict, today: dt.date, min_months: int) -> str | None:
    """Policy target: newest STANDARD minor with >= min_months runway, excluding the
    single newest minor (N-1), for banking maturity. Falls back to newest compliant."""
    ordered = sorted(versions.keys(), key=_ver_key, reverse=True)
    if not ordered:
        return None
    newest = ordered[0]
    eligible = []
    for v in ordered:
        status, runway = classify(v, versions, today, min_months)
        if status == "standard" and runway is not None and runway >= min_months:
            eligible.append(v)
    n_minus_one = [v for v in eligible if v != newest]
    if n_minus_one:
        return n_minus_one[0]
    return eligible[0] if eligible else None


def evaluate(table_path: pathlib.Path, today: dt.date):
    data = json.loads(table_path.read_text(encoding="utf-8"))
    meta = data.get("_meta", {})
    versions = data["versions"]
    min_months, forbid = load_policy()

    governs = meta.get("governs", {})
    tofu_file = REPO / governs.get("tofu_file", "")
    key = governs.get("tofu_key", "")
    pinned = parse_pinned_version(tofu_file, key) if governs else None

    service = meta.get("service", "managed-service")
    violations: list[str] = []
    if pinned is None:
        violations.append(
            f"could not parse pinned {service} version from {governs.get('tofu_file')} (key {key})"
        )
        status = runway = None
    else:
        status, runway = classify(pinned, versions, today, min_months)
        if status == "unknown":
            violations.append(f"{service} {pinned} not in lifecycle table — cannot verify support status")
        elif status == "unsupported":
            violations.append(f"{service} {pinned} is PAST end-of-extended-support — unsupported and force-upgraded by AWS")
        elif status == "extended" and forbid:
            violations.append(
                f"{service} {pinned} is in EXTENDED support (paid surcharge) — policy forbids provisioning/running it"
            )
        if runway is not None and runway < min_months and status == "standard":
            violations.append(
                f"{service} {pinned} has only ~{runway:.1f} months of standard support left (< {min_months} required)"
            )

    rec = recommend(versions, today, min_months)
    return {
        "service": service,
        "pinned": pinned,
        "status": status,
        "runway_months": runway,
        "min_months": min_months,
        "forbid_extended": forbid,
        "recommended": rec,
        "violations": violations,
        "tofu_file": governs.get("tofu_file"),
    }


def print_human(r: dict) -> None:
    print(f"FinOps version-lifecycle check — {r['service']} (ADR-0054)")
    print(f"  pinned:      {r['pinned']}  in {r['tofu_file']}")
    rw = f"{r['runway_months']:.1f}mo standard left" if r["runway_months"] is not None else "n/a"
    print(f"  status:      {r['status']}  ({rw})")
    print(f"  policy:      standard-only={r['forbid_extended']}, min_runway={r['min_months']}mo, N-1 target")
    print(f"  recommended: {r['recommended']}")
    if r["violations"]:
        print("  VIOLATIONS:")
        for v in r["violations"]:
            print(f"    - {v}")
    else:
        print("  OK: pinned version is compliant.")


def markdown_report(r: dict, today: dt.date) -> str:
    rw = f"{r['runway_months']:.1f} months" if r["runway_months"] is not None else "n/a"
    finding = bool(r["violations"])
    lines = [
        f"FINOPS_FINDING={'1' if finding else '0'}",
        "",
        f"## FinOps version-lifecycle audit — {today.isoformat()}",
        "",
        f"_Weekly static audit (ADR-0054). Service: **{r['service']}**._",
        "",
        f"- **Pinned version:** `{r['pinned']}` (`{r['tofu_file']}`)",
        f"- **Support status:** `{r['status']}` — {rw} of standard support remaining",
        f"- **Policy:** standard-support only, ≥ {r['min_months']} months runway, N-1 target",
        f"- **Recommended target:** `{r['recommended']}`",
        "",
    ]
    if finding:
        lines.append("### ⚠️ Action required")
        for v in r["violations"]:
            lines.append(f"- {v}")
        lines.append("")
        lines.append(
            f"Upgrade toward `{r['recommended']}` (EKS upgrades one minor at a time). "
            "See ADR-0054 and the 1.31→1.34 remediation runbook."
        )
    else:
        lines.append("No action required — the pinned version is on standard support with runway.")
    return "\n".join(lines) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser(description="FinOps version-lifecycle gate/audit (ADR-0054)")
    ap.add_argument("--table", default=str(DEFAULT_TABLE), help="lifecycle table JSON")
    ap.add_argument("--report", action="store_true", help="emit markdown audit report (exit 0)")
    ap.add_argument("--date", help="override today (YYYY-MM-DD), for testing")
    args = ap.parse_args()

    today = dt.date.fromisoformat(args.date) if args.date else dt.date.today()
    r = evaluate(pathlib.Path(args.table), today)

    if args.report:
        sys.stdout.write(markdown_report(r, today))
        return 0

    print_human(r)
    return 1 if r["violations"] else 0


if __name__ == "__main__":
    sys.exit(main())
