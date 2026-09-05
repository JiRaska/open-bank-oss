#!/usr/bin/env python3
"""VEX triage lifecycle — turn the fleet triage queue into SLA-tracked issues (ADR-0030 D1).

vex-inventory.py aggregates the OpenVEX docs attached to every component's latest
release and surfaces the CVEs still `under_investigation`. This script closes the
loop that made VEX "produced but not triaged":

  1. every CVE needing triage gets exactly one GitHub issue
     (`VEX triage: <CVE>`, label `vex-triage` + `severity:<level>`); the issue's
     creation date IS the SLA clock — no repo-committed state needed
  2. severity is resolved from api.osv.dev (CVSS -> critical/high/medium/low);
     unknown severities are conservatively treated as high — EXCEPT when the CVE is on
     CISA's KEV catalog (actively exploited in the wild): then it triages at the critical
     SLA regardless of score, carries the `kev-listed` label, and shows its EPSS exploit
     probability (FIRST.org). A CVE that enters KEV after its issue opened escalates the
     open issue in place (ADR-0279 #11/#13)
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

# ADR-0279 #11/#13: exploitability beats CVSS for prioritization. A CVE on CISA's KEV
# catalog is actively exploited in the wild — it is triaged at the CRITICAL SLA whatever
# its base score, and carries the `kev-listed` label so the queue sorts by it. The KEV
# fetch is best-effort: a feed outage degrades prioritization, it must never break the
# triage loop itself (the SLA machinery above is the control that must always run).
KEV_URL = "https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json"
EPSS_URL = "https://api.first.org/data/v1/epss?cve="


def kev_catalog() -> dict[str, str]:
    """CVE id -> KEV remediation due date. Empty dict on any fetch/parse failure (degrade, don't die)."""
    try:
        with urllib.request.urlopen(KEV_URL, timeout=30) as r:
            doc = json.loads(r.read())
        return {v["cveID"]: v.get("dueDate", "") for v in doc.get("vulnerabilities", [])}
    except Exception as ex:  # noqa: BLE001 — feed outage must not fail triage
        print(f"::warning::KEV catalog fetch failed ({type(ex).__name__}) — continuing without exploitability data")
        return {}


def epss_score(cve: str) -> float | None:
    """EPSS exploit-probability (0..1) from FIRST.org; None when unknown or unreachable."""
    try:
        with urllib.request.urlopen(EPSS_URL + cve, timeout=15) as r:
            doc = json.loads(r.read())
        data = doc.get("data") or []
        return float(data[0]["epss"]) if data else None
    except Exception:  # noqa: BLE001 — advisory enrichment only
        return None


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


def _cvss31_base_score(vector: str) -> float | None:
    """CVSS v3.0/3.1 base score from the vector string (deterministic, per the spec).

    OSV returns `severity[].score` as a CVSS VECTOR for CVE entries (not a number and not a
    qualitative rating), so we must compute the base score to derive critical/high/medium/low.
    Returns None for a non-v3 vector (v2/v4 handled by the caller's fallback).
    """
    if not vector.startswith(("CVSS:3.0", "CVSS:3.1")):
        return None
    m = dict(part.split(":", 1) for part in vector.split("/") if ":" in part)
    try:
        av = {"N": 0.85, "A": 0.62, "L": 0.55, "P": 0.2}[m["AV"]]
        ac = {"L": 0.77, "H": 0.44}[m["AC"]]
        ui = {"N": 0.85, "R": 0.62}[m["UI"]]
        changed = m["S"] == "C"
        pr = ({"N": 0.85, "L": 0.68, "H": 0.5} if changed else {"N": 0.85, "L": 0.62, "H": 0.27})[m["PR"]]
        imp = {"H": 0.56, "L": 0.22, "N": 0.0}
        c, i, a = imp[m["C"]], imp[m["I"]], imp[m["A"]]
    except KeyError:
        return None
    isc_base = 1 - (1 - c) * (1 - i) * (1 - a)
    if changed:
        impact = 7.52 * (isc_base - 0.029) - 3.25 * (isc_base - 0.02) ** 15
    else:
        impact = 6.42 * isc_base
    if impact <= 0:
        return 0.0
    exploitability = 8.22 * av * ac * pr * ui
    raw = min((1.08 if changed else 1.0) * (impact + exploitability), 10.0)
    # spec roundup: ceil to one decimal, avoiding binary-float artifacts
    scaled = int(round(raw * 100000))
    return scaled / 100000.0 if scaled % 10000 == 0 else (scaled // 10000 + 1) / 10.0


def _qualitative(score: float) -> str:
    if score >= 9.0:
        return "critical"
    if score >= 7.0:
        return "high"
    if score >= 4.0:
        return "medium"
    return "low"


def osv_severity(vuln_id: str) -> str:
    """critical/high/medium/low from api.osv.dev; 'high' when unknown (conservative)."""
    try:
        with urllib.request.urlopen(f"https://api.osv.dev/v1/vulns/{vuln_id}", timeout=15) as r:
            doc = json.loads(r.read())
    except (urllib.error.URLError, json.JSONDecodeError, TimeoutError):
        return "high"
    # 1) GHSA-style qualitative rating, when present (npm/some JVM advisories).
    for eco in [doc] + (doc.get("affected") or []):
        db = eco.get("database_specific") or {}
        s = (db.get("severity") or "").lower()
        if s in ("critical", "high", "moderate", "medium", "low"):
            return {"moderate": "medium"}.get(s, s)
    # 2) CVE-style: severity[].score is a CVSS vector — compute the base score (v3),
    #    or accept a bare numeric score if a database ever provides one.
    best = None
    for sev in doc.get("severity", []) or []:
        sc = (sev.get("score") or "").strip()
        if re.fullmatch(r"[\d.]+", sc):
            val = float(sc)
        else:
            val = _cvss31_base_score(sc)
        if val is not None:
            best = val if best is None else max(best, val)
    if best is not None:
        return _qualitative(best)
    return "high"  # unknown severity → conservative


def triage_queue() -> tuple[dict[str, list[str]], bool]:
    """(CVE -> [components] under_investigation, inventory_had_bundles) from vex-inventory.py.

    The second element guards the auto-close loop: if the inventory transiently returns nothing
    (e.g. a GitHub release-asset fetch blip), we must NOT interpret an empty queue as 'every CVE
    triaged' and close every open issue — that would silently reset every SLA clock.
    """
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
    had_bundles = bool(data.get("with_bundle"))
    return out, had_bundles


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
    queue, inventory_had_data = triage_queue()
    existing = open_triage_issues()
    kev = kev_catalog()
    now = datetime.now(timezone.utc)
    failures = 0

    # 1+2: ensure an issue per queued CVE
    for cve, comps in sorted(queue.items()):
        if cve in existing:
            continue
        sev = osv_severity(cve)
        kev_due = kev.get(cve)
        epss = epss_score(cve)
        if kev_due is not None:
            # Actively exploited in the wild: triage at the critical SLA whatever the base
            # score said — CVSS measures theoretical impact, KEV measures current reality.
            sev = "critical"
        exploit_note = ""
        if kev_due is not None:
            exploit_note += (
                f"\n\n**⚠️ CISA KEV-listed — actively exploited in the wild.** CISA remediation "
                f"due date: {kev_due}. Triaged at the **critical** SLA regardless of CVSS."
            )
        if epss is not None:
            exploit_note += f"\n\n**EPSS:** {epss:.2%} exploit probability (FIRST.org)."
        body = (
            f"`{cve}` is `under_investigation` in the latest release VEX of: "
            f"{', '.join(f'`{c}`' for c in comps)}.\n\n"
            f"**Severity:** {sev} → SLA **{slas.get(sev, slas['high'])} days** from this "
            f"issue's creation (rules.yaml `vuln_management.sla_days`; this issue is the clock)."
            f"{exploit_note}\n\n"
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
        labels = [LABEL, f"severity:{sev}", "governance"]
        if kev_due is not None:
            labels.append("kev-listed")
        gh(f"{API}/repos/{REPO}/issues", "POST", {
            "title": f"VEX triage: {cve}",
            "body": body,
            "labels": labels,
        })
        print(f"opened: VEX triage: {cve} (severity:{sev})")

    # 2b: escalate issues whose CVE entered the KEV catalog AFTER the issue opened — the
    # severity label and the SLA are derived from it, so the escalation relabels, comments,
    # and lets the SLA loop below re-read the new severity.
    for cve, issue in sorted(existing.items()):
        if cve not in kev:
            continue
        label_names = {lbl.get("name", "") for lbl in issue.get("labels", [])}
        if "kev-listed" in label_names:
            continue
        if dry:
            print(f"[dry-run] would escalate #{issue['number']} ({cve}) — newly KEV-listed")
            continue
        new_labels = sorted((label_names - {f"severity:{s}" for s in slas})
                            | {"kev-listed", "severity:critical"})
        gh(f"{API}/repos/{REPO}/issues/{issue['number']}", "PATCH", {"labels": new_labels})
        gh(f"{API}/repos/{REPO}/issues/{issue['number']}/comments", "POST", {
            "body": f"⚠️ `{cve}` entered the CISA KEV catalog (actively exploited; CISA due "
                    f"{kev[cve]}). Escalated to `severity:critical` — the SLA clock now reads "
                    f"the critical limit from this issue's creation date. — vex-triage.yml"
        })
        print(f"escalated #{issue['number']} ({cve}) — newly KEV-listed")
        issue["labels"] = [{"name": n} for n in new_labels]

    # 4: close issues whose CVE left the queue — but ONLY if the inventory actually returned
    # bundles. A transiently-empty inventory must not be read as "everything triaged" and
    # close every open issue (which would reset every SLA clock). SLA enforcement below still
    # runs on the existing issues regardless.
    if not inventory_had_data and existing:
        print("::warning::vex-inventory returned no bundles this run — skipping auto-close to "
              "avoid resetting SLA clocks on a transient empty inventory.")
    for cve, issue in ([] if not inventory_had_data else sorted(existing.items())):
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
