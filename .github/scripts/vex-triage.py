#!/usr/bin/env python3
"""VEX triage lifecycle — turn the fleet triage queue into SLA-tracked issues (ADR-0030 D1).

vex-inventory.py aggregates the OpenVEX docs attached to every component's latest
release and surfaces the CVEs still `under_investigation`. This script closes the
loop that made VEX "produced but not triaged":

  1. every CVE needing triage gets exactly one GitHub issue
     (`VEX triage: <CVE>`, label `vex-triage` + `severity:<level>`); the issue's
     creation date IS the SLA clock — no repo-committed state needed
  2. severity is resolved from api.osv.dev (CVSS -> critical/high/medium/low);
     unknown severities are conservatively treated as high
  3. an open vex-triage issue older than rules.yaml `vuln_management.sla_days`
     for its severity fails the job (::error) — the weekly red build is the
     escalation, mirroring the gate-graduation forcing function (ADR-0144)
  4. issues whose CVE left the queue (human verdict landed in
     openbank-libs/governance/vex/<component>.openvex.json, or the dep was bumped)
     are commented and closed automatically

Triage verdict = a maintainer edit to the component's OpenVEX overlay
(status: not_affected / affected / fixed + justification), which
build-release-evidence.sh folds into the next release's VEX document.

Usage: GH_TOKEN=... python3 .github/scripts/vex-triage.py [--dry-run]
Env: GH_TOKEN (required), GITHUB_REPOSITORY (default JiRaska/open-bank-oss).
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

REPO = os.environ.get("GITHUB_REPOSITORY", "JiRaska/open-bank-oss")
TOKEN = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
API = "https://api.github.com"
LABEL = "vex-triage"
TITLE_RE = re.compile(r"^VEX triage: (\S+)$")


def sla_days() -> dict[str, int]:
    rules = Path("openbank-libs/governance/rules.yaml").read_text()
    m = re.search(r"sla_days:\s*{([^}]*)}", rules)
    if not m:
        sys.exit("cannot parse vuln_management.sla_days from rules.yaml")
    out: dict[str, int] = {}
    for part in m.group(1).split(","):
        k, v = part.split(":")
        out[k.strip()] = int(v.strip())
    return out


def gh(url: str, method: str = "GET", body: dict | None = None):
    req = urllib.request.Request(
        url,
        method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={
            "Authorization": f"Bearer {TOKEN}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "Content-Type": "application/json",
        },
    )
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read() or "{}")


def osv_severity(vuln_id: str) -> str:
    """critical/high/medium/low from api.osv.dev CVSS; 'high' when unknown (conservative)."""
    try:
        with urllib.request.urlopen(f"https://api.osv.dev/v1/vulns/{vuln_id}", timeout=15) as r:
            doc = json.loads(r.read())
    except (urllib.error.URLError, json.JSONDecodeError, TimeoutError):
        return "high"
    # GHSA entries (most JVM/npm advisories) carry database_specific.severity
    for eco in [doc] + (doc.get("affected") or []):
        db = eco.get("database_specific") or {}
        s = (db.get("severity") or "").lower()
        if s in ("critical", "high", "moderate", "medium", "low"):
            return {"moderate": "medium"}.get(s, s)
    # fall back to a numeric CVSS base score when one is present
    score = 0.0
    for sev in doc.get("severity", []) or []:
        sc = sev.get("score", "")
        if re.match(r"^[\d.]+$", sc):
            score = max(score, float(sc))
    if score >= 9:
        return "critical"
    if score >= 7:
        return "high"
    if 0 < score < 7:
        return "medium" if score >= 4 else "low"
    return "high"


def triage_queue() -> dict[str, list[str]]:
    """CVE -> [components] still under_investigation, from vex-inventory.py --json."""
    res = subprocess.run(
        [sys.executable, ".github/scripts/vex-inventory.py", "--json"],
        capture_output=True, text=True,
    )
    if res.returncode != 0:
        sys.exit(f"vex-inventory failed: {res.stderr.strip()}")
    data = json.loads(res.stdout)
    out: dict[str, list[str]] = {}
    for cve in data.get("needs_triage", []):
        comps = [c for c, s in data["cve"].get(cve, {}).items() if s == "under_investigation"]
        out[cve] = sorted(comps)
    return out


def open_triage_issues() -> dict[str, dict]:
    issues: dict[str, dict] = {}
    page = 1
    while True:
        batch = gh(f"{API}/repos/{REPO}/issues?state=open&labels={LABEL}&per_page=100&page={page}")
        if not batch:
            break
        for it in batch:
            m = TITLE_RE.match(it.get("title", ""))
            if m:
                issues[m.group(1)] = it
        page += 1
    return issues


def main() -> int:
    if not TOKEN:
        sys.exit("GH_TOKEN/GITHUB_TOKEN required")
    dry = "--dry-run" in sys.argv
    slas = sla_days()
    queue = triage_queue()
    existing = open_triage_issues()
    now = datetime.now(timezone.utc)
    failures = 0

    # 1+2: ensure an issue per queued CVE
    for cve, comps in sorted(queue.items()):
        if cve in existing:
            continue
        sev = osv_severity(cve)
        body = (
            f"`{cve}` is `under_investigation` in the latest release VEX of: "
            f"{', '.join(f'`{c}`' for c in comps)}.\n\n"
            f"**Severity:** {sev} → SLA **{slas.get(sev, slas['high'])} days** from this "
            f"issue's creation (rules.yaml `vuln_management.sla_days`; this issue is the clock).\n\n"
            "## Triage verdict\n\n"
            "Add a statement to `openbank-libs/governance/vex/<component>.openvex.json` "
            "(status: `not_affected` + justification / `affected` + fix plan / `fixed`). "
            "The next release's evidence bundle folds it in and this issue auto-closes "
            "on the next weekly run.\n\n"
            f"Links: [OSV](https://osv.dev/vulnerability/{cve}) · "
            f"[NVD](https://nvd.nist.gov/vuln/detail/{cve})\n\n"
            "_Opened automatically by vex-triage.yml (ADR-0030 D1)._"
        )
        if dry:
            print(f"[dry-run] would open: VEX triage: {cve} (severity:{sev}) — {comps}")
            continue
        gh(f"{API}/repos/{REPO}/issues", "POST", {
            "title": f"VEX triage: {cve}",
            "body": body,
            "labels": [LABEL, f"severity:{sev}", "governance"],
        })
        print(f"opened: VEX triage: {cve} (severity:{sev})")

    # 4: close issues whose CVE left the queue
    for cve, issue in sorted(existing.items()):
        if cve in queue:
            continue
        if dry:
            print(f"[dry-run] would close #{issue['number']} ({cve}) — no longer in triage queue")
            continue
        gh(f"{API}/repos/{REPO}/issues/{issue['number']}/comments", "POST", {
            "body": "This CVE is no longer `under_investigation` in any component's latest "
                    "release VEX (triaged in the overlay, or the dependency moved). "
                    "Closing automatically. — vex-triage.yml"
        })
        gh(f"{API}/repos/{REPO}/issues/{issue['number']}", "PATCH", {"state": "closed"})
        print(f"closed #{issue['number']} ({cve})")

    # 3: SLA enforcement on whatever remains open
    for cve, issue in sorted(existing.items()):
        if cve not in queue:
            continue
        sev = next((lbl["name"].split(":", 1)[1] for lbl in issue.get("labels", [])
                    if lbl.get("name", "").startswith("severity:")), "high")
        limit = slas.get(sev, slas["high"])
        opened = datetime.fromisoformat(issue["created_at"].replace("Z", "+00:00"))
        age = (now - opened).days
        if age > limit:
            print(f"::error::VEX triage SLA breached: {cve} (severity:{sev}) open {age}d "
                  f"> {limit}d — #{issue['number']} needs a human verdict in the VEX overlay "
                  f"(rules.yaml vuln_management)")
            failures += 1
        else:
            print(f"in SLA: {cve} (severity:{sev}) {age}d/{limit}d — #{issue['number']}")

    print(f"vex-triage: queue={len(queue)} open_issues={len(existing)} sla_breaches={failures}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
