#!/usr/bin/env python3
"""Probe every GitHub personal access token CI uses; warn before it expires (#15483).

WHY THIS EXISTS
  On 2026-11-12 `merged-past-red-check-watch.yml` failed ~25 times from 09:58Z with
  `401 Bad credentials`: RULESET_AUDIT_TOKEN had expired. The workflow failed loudly, which is
  right, but only on the day — and only the owner can rotate a PAT. GitHub tells every caller
  when a token expires (`github-authentication-token-expiration` response header, sent for
  fine-grained PATs and for classic PATs with an expiry), so the warning was available all along.

WHAT IT DOES
  For each credential NAME listed in CREDENTIALS (env, space separated) it reads the token from
  the env var of the same name and sends ONE request to `GET /rate_limit` (free: does not count
  against the rate limit). Verdicts:

    OK        200 and no expiry within the window (or no expiry at all)
    EXPIRING  200, but the expiry header falls within WARN_DAYS (default 7)
    INVALID   401 — expired or revoked; every workflow using it is already failing
    UNSET     a listed secret is not configured — a finding: the workflows that read it are
              skipping or failing, and an unset secret must never read as a clean probe
    DEFERRED  a listed secret is not configured, but `.github/config-inventory.yaml`
              already records it under `github.unprovisioned` with a reason, an issue
              and a `review_by` date that has not passed — the gap is acknowledged and
              owned, so the watch must not re-file it as a fresh finding every day
              (#16786). Once `review_by` passes the config-inventory gate fails AND the
              verdict falls back to UNSET here: deferral cannot outlive its review date.
              If the inventory cannot be read at all, every UNSET stays a finding.
    UNKNOWN   any other answer (network, 5xx, 403) — never read as OK

  Token values are never printed, logged or written anywhere; only names and verdicts.
  App private keys are out of scope: they do not expire, and every App-token workflow mints
  on every run, so a revoked key is already loud. Non-GitHub secrets have no common probe.

Exit: 0 nothing to act on; 2 a credential is EXPIRING or INVALID; 3 UNKNOWN only.
"""
from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

API = os.environ.get("GITHUB_API_URL", "https://api.github.com")
HEADER = "github-authentication-token-expiration"


def parse_expiry(value: str | None) -> datetime | None:
    """GitHub sends e.g. `2026-11-19 09:58:00 UTC` (older: `... +0000`)."""
    if not value:
        return None
    v = value.strip()
    for fmt in ("%Y-%m-%d %H:%M:%S %Z", "%Y-%m-%d %H:%M:%S %z"):
        try:
            dt = datetime.strptime(v, fmt)
            return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)
        except ValueError:
            continue
    raise ValueError(f"unparseable {HEADER} header: {v!r}")


def classify(status: int | None, expiry_header: str | None, now: datetime, warn_days: int) -> tuple[str, str]:
    if status is None:
        return "UNSET", "secret not configured"
    if status == 401:
        return "INVALID", "401 Bad credentials — expired or revoked"
    if status != 200:
        return "UNKNOWN", f"HTTP {status}"
    try:
        exp = parse_expiry(expiry_header)
    except ValueError as e:
        return "UNKNOWN", str(e)
    if exp is None:
        return "OK", "no expiry reported"
    left = exp - now
    if left <= timedelta(days=warn_days):
        return "EXPIRING", f"expires {exp:%Y-%m-%d %H:%M} UTC ({left.days} day(s) left)"
    return "OK", f"expires {exp:%Y-%m-%d} UTC"


def probe(token: str) -> tuple[int, str | None]:
    req = urllib.request.Request(f"{API}/rate_limit", headers={
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "User-Agent": "openbank-credential-expiry-watch",
    })
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, r.headers.get(HEADER)
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get(HEADER) if e.headers else None


def known_unprovisioned(inventory: Path, today) -> dict | None:
    """Secret names acknowledged as unprovisioned in config-inventory, with a live review_by.

    Returns None when the inventory cannot be read or parsed: the caller must then treat
    every UNSET as a finding (fail safe — a broken acknowledgment mechanism acknowledges
    nothing). An entry whose review_by is unparseable or past is NOT returned: the
    config-inventory gate flags it, and this probe treats the gap as unacknowledged.
    """
    try:
        import yaml  # lazy: the PR lane (self-test, --coverage) never touches this
        doc = yaml.safe_load(inventory.read_text())
    except Exception as e:
        print(f"::warning::cannot read {inventory} ({type(e).__name__}: {e}); UNSET secrets stay findings")
        return None
    out = {}
    for entry in ((doc.get("github") or {}).get("unprovisioned") or []):
        if entry.get("kind") != "secret":
            continue
        try:
            review_by = datetime.strptime(str(entry.get("review_by")), "%Y-%m-%d").date()
        except ValueError:
            continue
        if review_by < today:
            continue
        for name in entry.get("names") or []:
            out[name] = entry
    return out


def run(names: list[str], warn_days: int, probe_fn=None, inventory: Path | None = None) -> int:
    probe_fn = probe_fn or probe
    now = datetime.now(timezone.utc)
    worst = 0
    deferred = None
    for name in names:
        token = os.environ.get(name, "")
        if token:
            try:
                status, hdr = probe_fn(token)
            except (urllib.error.URLError, TimeoutError, OSError) as e:
                status, hdr = -1, None
                verdict, why = "UNKNOWN", f"no answer: {type(e).__name__}"
            else:
                verdict, why = classify(status, hdr, now, warn_days)
        elif inventory is not None:
            if deferred is None:
                deferred = known_unprovisioned(inventory, now.date())
            entry = (deferred or {}).get(name)
            if entry:
                verdict, why = "DEFERRED", (f"unset, acknowledged in config-inventory "
                                            f"(issue #{entry.get('issue')}, review_by {entry.get('review_by')})")
            else:
                verdict, why = classify(None, None, now, warn_days)
        else:
            verdict, why = classify(None, None, now, warn_days)
        print(f"{verdict:9} {name}: {why}")
        if verdict in ("EXPIRING", "INVALID", "UNSET"):
            worst = 2
        elif verdict == "UNKNOWN" and worst == 0:
            worst = 3
    print(f"SUBJECTS={len(names)}")
    return worst


# Secrets that LOOK like GitHub credentials by name but are not, or that this watch cannot read.
# Each needs a reason; a stale entry (no longer used anywhere) fails the coverage check too.
NOT_PROBED = {
    "GITHUB_TOKEN": "per-job token minted by Actions; never expires out from under a run",
    "PERF_READ_TOKEN": "environment secret for an in-cluster service, not a GitHub token",
    "GLITCHTIP_AUTH_TOKEN": "error-tracker API token, not a GitHub token",
    "ALERTMANAGER_DIGEST_TOKEN": "Alertmanager credential, not a GitHub token",
}
CRED_NAME = re.compile(r"secrets\.([A-Z0-9_]*(?:_PAT|_TOKEN))\b")
DECLARED = re.compile(r"^\s*CREDENTIALS:\s*[\"']?([A-Z0-9_ ]+)[\"']?\s*$", re.M)


def coverage(root: Path, watch: Path) -> int:
    """Every *_PAT / *_TOKEN secret a workflow reads is probed or excluded with a reason."""
    used: set[str] = set()
    for wf in sorted((root / ".github/workflows").glob("*.y*ml")):
        used |= set(CRED_NAME.findall(wf.read_text()))
    m = DECLARED.search(watch.read_text())
    if not m:
        print(f"{watch}: no `CREDENTIALS:` env line found")
        return 1
    declared = set(m.group(1).split())
    problems = []
    for n in sorted(used - declared - NOT_PROBED.keys()):
        problems.append(f"{n} is read by a workflow but neither probed nor excluded (add it to CREDENTIALS)")
    for n in sorted(declared - used):
        problems.append(f"{n} is probed but no workflow reads it any more (remove it)")
    for n in sorted(NOT_PROBED.keys() - used):
        problems.append(f"{n} is excluded but no workflow reads it any more (stale exclusion)")
    for n in sorted(declared & NOT_PROBED.keys()):
        problems.append(f"{n} is both probed and excluded")
    print(f"SUBJECTS={len(used)}")
    for p in problems:
        print(f"::error::{p}")
    if not problems:
        print(f"coverage OK: {len(declared)} probed, {len(used & NOT_PROBED.keys())} excluded with a reason")
    return 1 if problems else 0


def self_test() -> int:
    now = datetime(2026, 11, 12, 9, 0, tzinfo=timezone.utc)
    cases = [
        # (name, status, header, expected verdict)
        ("expired token", 401, None, "INVALID"),
        ("expires in 3 days", 200, "2026-11-15 09:00:00 UTC", "EXPIRING"),
        ("expires in exactly 7 days", 200, "2026-11-19 09:00:00 UTC", "EXPIRING"),
        ("legacy +0000 header form", 200, "2026-11-14 09:00:00 +0000", "EXPIRING"),
        ("expires in 30 days", 200, "2026-12-12 09:00:00 UTC", "OK"),
        ("no expiry header", 200, None, "OK"),
        ("server error is not OK", 503, None, "UNKNOWN"),
        ("garbled header is not OK", 200, "next tuesday", "UNKNOWN"),
        ("unset secret", None, None, "UNSET"),
    ]
    bad = []
    for name, status, hdr, want in cases:
        got, _ = classify(status, hdr, now, 7)
        if got != want:
            bad.append(f"{name}: got {got}, want {want}")
    # Exit codes of a whole run. Known-negative: one healthy token exits 0. Known-positives:
    # an UNSET listed secret and a 401 token must each exit 2 (never 0).
    import contextlib, io
    ok_probe = lambda _t: (200, "2027-06-01 00:00:00 UTC")
    bad_probe = lambda _t: (401, None)
    runs = [
        ("healthy token exits 0", {"T_OK": "x"}, ["T_OK"], ok_probe, 0),
        ("unset secret exits 2", {}, ["T_UNSET_ST"], ok_probe, 2),
        ("401 token exits 2", {"T_BAD": "x"}, ["T_BAD"], bad_probe, 2),
    ]
    for name, env, names, fn, want in runs:
        saved = {n: os.environ.pop(n, None) for n in names}
        os.environ.update(env)
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                got = run(names, 7, fn)
        finally:
            for n, v in saved.items():
                os.environ.pop(n, None)
                if v is not None:
                    os.environ[n] = v
        if got != want:
            bad.append(f"{name}: exit {got}, want {want}")

    # Inventory: an acknowledged unprovisioned secret defers (exit 0); a past review_by or an
    # unreadable inventory keeps UNSET a finding (exit 2). PyYAML is guaranteed here: CI
    # installs it before the self-test, and locally the repo's scripts require it anyway.
    import tempfile
    with tempfile.TemporaryDirectory() as d:
        inv = Path(d) / "config-inventory.yaml"
        live = ("github:\n  unprovisioned:\n    - kind: secret\n      names: [T_UNSET_ST]\n"
                "      reason: awaiting owner provisioning\n      issue: 16492\n"
                "      review_by: 2026-12-16\n")
        stale = live.replace("2026-12-16", "2026-01-01")
        for text, want in ((live, 0), (stale, 2)):
            inv.write_text(text)
            with contextlib.redirect_stdout(io.StringIO()) as buf:
                got = run(["T_UNSET_ST"], 7, ok_probe, inventory=inv)
            if got != want:
                bad.append(f"inventory {'live' if want == 0 else 'stale'}: exit {got}, want {want}")
            if want == 0 and "DEFERRED" not in buf.getvalue():
                bad.append("live inventory: verdict line lost its DEFERRED marker")
        with contextlib.redirect_stdout(io.StringIO()):
            got = run(["T_UNSET_ST"], 7, ok_probe, inventory=Path(d) / "missing.yaml")
        if got != 2:
            bad.append(f"unreadable inventory: exit {got}, want 2 (fail safe)")

    # Coverage: a credential read by a workflow but missing from CREDENTIALS must be flagged,
    # and a complete declaration must pass.
    with tempfile.TemporaryDirectory() as d:
        root = Path(d)
        (root / ".github/workflows").mkdir(parents=True)
        (root / ".github/workflows/a.yml").write_text(
            "env:\n  T: ${{ secrets.NEW_PAT }}\n  G: ${{ secrets.GITHUB_TOKEN }}\n")
        watch = root / ".github/workflows/w.yml"
        for decl, want in (("OTHER_TOKEN", 1), ("NEW_PAT", 0)):
            watch.write_text(f"    CREDENTIALS: \"{decl}\"\n")
            # GITHUB_TOKEN etc. are excluded; fake their use so the stale-exclusion rule is quiet.
            (root / ".github/workflows/x.yml").write_text(
                "".join(f"# ${{{{ secrets.{n} }}}}\n" for n in NOT_PROBED))
            import contextlib, io
            with contextlib.redirect_stdout(io.StringIO()):
                got = coverage(root, watch)
            if got != want:
                bad.append(f"coverage with CREDENTIALS={decl}: exit {got}, want {want}")
    if bad:
        print("SELF-TEST FAILED:\n  " + "\n  ".join(bad))
        return 1
    print(f"self-test OK: {len(cases)} cases, INVALID/EXPIRING/OK/UNKNOWN/UNSET all reachable")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--coverage", metavar="WATCH_WORKFLOW",
                    help="offline: check the CREDENTIALS list in this workflow covers every PAT/token secret")
    ap.add_argument("--warn-days", type=int, default=int(os.environ.get("WARN_DAYS", "7")))
    ap.add_argument("--inventory", metavar="CONFIG_INVENTORY", default=".github/config-inventory.yaml",
                    help="acknowledged-unprovisioned secrets read from here (UNSET -> DEFERRED); "
                         "pass an empty string to disable")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    if a.coverage:
        return coverage(Path("."), Path(a.coverage))
    names = os.environ.get("CREDENTIALS", "").split()
    if not names:
        print("CREDENTIALS is empty — refusing to report a clean run over nothing", file=sys.stderr)
        return 1
    return run(names, a.warn_days, inventory=Path(a.inventory) if a.inventory else None)


if __name__ == "__main__":
    sys.exit(main())
