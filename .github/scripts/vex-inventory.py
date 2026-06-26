#!/usr/bin/env python3
"""Fleet VEX inventory: consolidate the OpenVEX docs attached to every latest release
into one triage report (ADR-0030 D1).

For each component's most recent release tag, download `<tag>.vex.json` and aggregate
CVE -> {component: status}. Surfaces the human-triage queue: anything still
`under_investigation` needs a verdict in openbank-libs/governance/vex/<component>.openvex.json;
`not_affected`/`fixed` are already triaged.

Usage: GH_TOKEN=$(gh auth token) python3 .github/scripts/vex-inventory.py [--json]
Env: GH_TOKEN (required), GITHUB_REPOSITORY (default JiRaska/open-bank).
"""
import json
import os
import sys
import urllib.request

REPO = os.environ.get("GITHUB_REPOSITORY", "JiRaska/open-bank")
TOKEN = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
API = "https://api.github.com"


def api(url, accept="application/vnd.github+json"):
    req = urllib.request.Request(url, headers={
        "Authorization": f"Bearer {TOKEN}", "Accept": accept,
        "X-GitHub-Api-Version": "2022-11-28",
    })
    with urllib.request.urlopen(req) as r:
        return r.read()


def all_releases():
    out, page = [], 1
    while True:
        batch = json.loads(api(f"{API}/repos/{REPO}/releases?per_page=100&page={page}"))
        if not batch:
            break
        out += batch
        page += 1
    return out


def main():
    if not TOKEN:
        sys.exit("GH_TOKEN/GITHUB_TOKEN required")
    # Latest release per component (tag = <component>-v<version>); releases come newest-first.
    latest = {}
    for rel in all_releases():
        tag = rel.get("tag_name", "")
        if "-v" not in tag:
            continue
        comp = tag.rsplit("-v", 1)[0]
        latest.setdefault(comp, rel)  # first seen = newest

    # cve -> {component: status}; track which components have a bundle at all.
    cve = {}
    with_bundle, without_bundle = [], []
    for comp, rel in sorted(latest.items()):
        vex_asset = next((a for a in rel.get("assets", []) if a["name"].endswith(".vex.json")), None)
        if not vex_asset:
            without_bundle.append(comp)
            continue
        with_bundle.append(comp)
        try:
            doc = json.loads(api(f"{API}/repos/{REPO}/releases/assets/{vex_asset['id']}",
                                 accept="application/octet-stream"))
        except Exception as e:  # noqa: BLE001
            print(f"::warning::failed to read VEX for {comp}: {e}", file=sys.stderr)
            continue
        for st in doc.get("statements", []) or []:
            v = st.get("vulnerability")
            name = v.get("name") if isinstance(v, dict) else v
            if name:
                cve.setdefault(name, {})[comp] = st.get("status", "under_investigation")

    needs_triage = {c: m for c, m in cve.items()
                    if any(s == "under_investigation" for s in m.values())}

    if "--json" in sys.argv:
        print(json.dumps({"cve": cve, "needs_triage": sorted(needs_triage),
                          "with_bundle": with_bundle, "without_bundle": without_bundle}, indent=2))
        return

    print("# Fleet VEX inventory\n")
    print(f"- components with a signed evidence bundle: **{len(with_bundle)}**")
    print(f"- components WITHOUT a bundle (backfill needed): **{len(without_bundle)}**"
          + (f" — {', '.join(without_bundle)}" if without_bundle else ""))
    print(f"- distinct CVEs across the fleet: **{len(cve)}**")
    print(f"- CVEs still needing human triage (`under_investigation`): **{len(needs_triage)}**\n")
    if needs_triage:
        print("## Triage queue (CVE → affected components)\n")
        for name in sorted(needs_triage):
            comps = ", ".join(f"{c}({s})" for c, s in sorted(needs_triage[name].items()))
            print(f"- `{name}` → {comps}")


if __name__ == "__main__":
    main()
