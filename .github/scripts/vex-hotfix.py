#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""KEV-listed CVE -> automatic hotfix PR against the Gradle version catalog.

WHY (ADR-0279 WS3 #13). vex-triage.py already escalates a KEV CVE to a
severity:critical issue with the `kev-listed` label (#8620). Escalation without a
remediation path is an alarm, not a fix — the loop closed here: when OSV knows a fixed
version AND the vulnerable module is pinned directly in
`openbank-libs/gradle/libs.versions.toml`, this script opens the bump PR itself.
A human still reviews and merges — the bot writes the diff, never presses merge.

SCOPE, deliberately narrow:
  * only issues labelled `kev-listed` (actively exploited — the whole point of the
    lane; the general dependency wave stays with Dependabot);
  * only Maven-ecosystem entries whose package name is an exact `group:artifact`
    of a `[libraries]` entry with a `version.ref` or inline `version` — a BOM-managed
    or transitive dependency has no line to bump and gets an issue COMMENT saying so
    (actionable: the fix is a BOM/platform bump, a human decision);
  * one PR per CVE, idempotent — an existing open PR on the same branch is reused,
    never duplicated.

FAILURE POSTURE: degrade-don't-die. An OSV outage or an unreadable issue skips that
CVE with a ::warning; the run still reports what it did. A catalog parse failure is
fatal (that would mean the file the PR would edit is not what we think).

Usage:  vex-hotfix.py [--dry-run]
        vex-hotfix.py --self-test      # offline, no network, no repo
Env:    GH_TOKEN (issues/PR API), GITHUB_REPOSITORY (owner/name)
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

API = "https://api.github.com"
TOKEN = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN", "")
REPO = os.environ.get("GITHUB_REPOSITORY", "")
KEV_LABEL = "kev-listed"
TITLE_RE = re.compile(r"VEX triage:\s*(CVE-\d{4}-\d+)")
CATALOG = Path("openbank-libs/gradle/libs.versions.toml")
BRANCH_PREFIX = "security/kev-hotfix"


# ── pure, offline-testable halves ────────────────────────────────────────────

def parse_catalog(text: str) -> dict[str, tuple[str, str]]:
    """module 'group:artifact' -> (version_key, pinned_version) for libraries whose
    version is a `version.ref` into [versions] or an inline `version = "..."`.
    Entries with neither (BOM-managed) are intentionally absent from the result."""
    versions = dict(re.findall(r'^([a-z0-9-]+)\s*=\s*"([^"]+)"', text, re.M))
    out: dict[str, tuple[str, str]] = {}
    for m in re.finditer(
        r'^([a-z0-9-]+)\s*=\s*\{\s*module\s*=\s*"([^"]+)"([^}]*)}', text, re.M
    ):
        alias, module, rest = m.group(1), m.group(2), m.group(3)
        ref = re.search(r'version\.ref\s*=\s*"([^"]+)"', rest)
        inl = re.search(r'version\s*=\s*"([^"]+)"', rest)
        ver = versions.get(ref.group(1)) if ref else (inl.group(1) if inl else None)
        if ver:
            out[module] = (alias, ver)
    return out


def osv_fixed_versions(doc: dict) -> dict[str, str]:
    """Maven 'group:artifact' -> lowest fixed version, from an OSV vuln document."""
    out: dict[str, str] = {}
    for aff in doc.get("affected") or []:
        pkg = aff.get("package") or {}
        if pkg.get("ecosystem") != "Maven":
            continue
        name = pkg.get("name", "")
        for rng in aff.get("ranges") or []:
            for ev in rng.get("events") or []:
                fixed = ev.get("fixed")
                if fixed and (name not in out or version_key(fixed) < version_key(out[name])):
                    out[name] = fixed
    return out


def version_key(v: str) -> tuple:
    parts = re.split(r"[.\-+]", v)
    return tuple(int(p) if p.isdigit() else 0 for p in parts)


def is_upgrade(pinned: str, fixed: str) -> bool:
    return version_key(fixed) > version_key(pinned)


def bump_catalog(text: str, alias: str, new_version: str) -> str:
    """Rewrite the [versions] line for `alias`'s version.ref target — the alias line
    itself never carries the number in this catalog's convention."""
    # find the library's version.ref
    m = re.search(
        rf'^{re.escape(alias)}\s*=\s*\{{[^}}]*version\.ref\s*=\s*"([^"]+)"[^}}]*\}}',
        text, re.M)
    if not m:
        raise ValueError(f"{alias}: no version.ref (inline versions are not bumped)")
    key = m.group(1)
    new_text, n = re.subn(rf'^({re.escape(key)}\s*=\s*")[^"]+(")', rf"\g<1>{new_version}\g<2>",
                          text, count=1, flags=re.M)
    if n != 1:
        raise ValueError(f"version key '{key}' not found exactly once in [versions]")
    return new_text


def self_test() -> int:
    bad = 0
    cat = (
        "[versions]\nlogback = \"1.5.6\"\nquarkus = \"3.38.0\"\n\n"
        "[libraries]\n"
        "logback-classic = { module = \"ch.qos.logback:logback-classic\", version.ref = \"logback\" }\n"
        "quarkus-rest = { module = \"io.quarkus:quarkus-rest\" }\n"  # BOM-managed: absent from map
    )
    pins = parse_catalog(cat)
    if pins.get("ch.qos.logback:logback-classic") != ("logback-classic", "1.5.6"):
        print("self-test FAIL: catalog parse"); bad += 1
    if "io.quarkus:quarkus-rest" in pins:
        print("self-test FAIL: BOM-managed entry must not be pinnable"); bad += 1
    doc = {"affected": [
        {"package": {"ecosystem": "Maven", "name": "ch.qos.logback:logback-classic"},
         "ranges": [{"events": [{"introduced": "0"}, {"fixed": "1.5.13"}]}]},
        {"package": {"ecosystem": "npm", "name": "left-pad"},
         "ranges": [{"events": [{"introduced": "0"}, {"fixed": "9.9.9"}]}]},
    ]}
    fixed = osv_fixed_versions(doc)
    if fixed != {"ch.qos.logback:logback-classic": "1.5.13"}:
        print("self-test FAIL: fixed-version extraction"); bad += 1
    if not is_upgrade("1.5.6", "1.5.13") or is_upgrade("1.5.13", "1.5.6"):
        print("self-test FAIL: version comparison"); bad += 1
    bumped = bump_catalog(cat, "logback-classic", "1.5.13")
    if 'logback = "1.5.13"' not in bumped or 'quarkus = "3.38.0"' not in bumped:
        print("self-test FAIL: catalog bump rewrote the wrong line"); bad += 1
    try:
        bump_catalog(cat, "quarkus-rest", "9.9.9")
        print("self-test FAIL: BOM-managed alias must refuse the bump"); bad += 1
    except ValueError:
        pass
    print("vex-hotfix self-test: " + ("clean" if not bad else f"{bad} failure(s)"))
    return 1 if bad else 0


# ── networked halves ─────────────────────────────────────────────────────────

def gh(url: str, method: str = "GET", body: dict | None = None):
    req = urllib.request.Request(
        url, method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Authorization": f"Bearer {TOKEN}", "Accept": "application/vnd.github+json",
                 "X-GitHub-Api-Version": "2022-11-28", "Content-Type": "application/json"})
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read() or "{}")


def osv_doc(cve: str) -> dict | None:
    try:
        with urllib.request.urlopen(f"https://api.osv.dev/v1/vulns/{cve}", timeout=15) as r:
            return json.loads(r.read())
    except (urllib.error.URLError, json.JSONDecodeError, TimeoutError) as e:
        print(f"::warning::{cve}: OSV unreachable ({e}) — skipped this run")
        return None


def kev_issues() -> list[dict]:
    out: list[dict] = []
    page = 1
    while True:
        batch = gh(f"{API}/repos/{REPO}/issues?state=open&labels={KEV_LABEL}&per_page=100&page={page}")
        if not batch:
            return out
        out.extend(it for it in batch if TITLE_RE.match(it.get("title", "")))
        page += 1


def run_git(*args: str) -> None:
    subprocess.run(["git", *args], check=True, capture_output=True, text=True)


def open_hotfix_pr(cve: str, issue: dict, module: str, alias: str, pinned: str, fixed: str,
                   dry: bool) -> None:
    branch = f"{BRANCH_PREFIX}-{cve.lower()}"
    body = (
        f"## What\n\nBump `{module}` **{pinned} → {fixed}** — fixes {cve}, which is on the "
        f"CISA Known Exploited Vulnerabilities catalog.\n\n"
        f"Opened automatically by `vex-hotfix.yml` (ADR-0279 #13). A human reviews and merges; "
        f"the bot writes the diff, never presses merge.\n\n"
        f"Refs #{issue['number']}\n\n"
        f"Links: [OSV](https://osv.dev/vulnerability/{cve}) · "
        f"[CISA KEV](https://www.cisa.gov/known-exploited-vulnerabilities-catalog)\n")
    if dry:
        print(f"[dry-run] would open PR {branch}: {module} {pinned} -> {fixed} (issue #{issue['number']})")
        return
    existing = gh(f"{API}/repos/{REPO}/pulls?state=open&head={REPO.split('/')[0]}:{branch}")
    if existing:
        print(f"PR already open for {cve} (#{existing[0]['number']}) — skipping")
        return
    run_git("checkout", "-b", branch, "origin/main")
    text = CATALOG.read_text()
    CATALOG.write_text(bump_catalog(text, alias, fixed))
    run_git("add", str(CATALOG))
    run_git("-c", "user.name=github-actions[bot]",
            "-c", "user.email=41898282+github-actions[bot]@users.noreply.github.com",
            "commit", "-s", "-m",
            f"fix(security): bump {module} to {fixed} — {cve} (KEV)\n\nRefs #{issue['number']}")
    run_git("push", "-u", "origin", branch)
    with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False) as bf:
        bf.write(body)
        path = bf.name
    subprocess.run(["gh", "pr", "create", "--title",
                    f"fix(security): bump {module.split(':')[1]} to {fixed} — {cve} (KEV)",
                    "--body-file", path, "--base", "main",
                    "--label", "security", "--label", KEV_LABEL],
                   check=True)
    print(f"opened hotfix PR for {cve}: {module} {pinned} -> {fixed}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    if not TOKEN or not REPO:
        sys.exit("GH_TOKEN and GITHUB_REPOSITORY required")

    pins = parse_catalog(CATALOG.read_text())
    acted = 0
    for issue in kev_issues():
        cve = TITLE_RE.match(issue["title"]).group(1)
        doc = osv_doc(cve)
        if doc is None:
            continue
        fixed_map = osv_fixed_versions(doc)
        hits = [(m, fixed_map[m]) for m in fixed_map if m in pins and is_upgrade(pins[m][1], fixed_map[m])]
        unpinned = [m for m in fixed_map if m not in pins]
        if not hits:
            note = "no fixed version in OSV yet" if not fixed_map else (
                f"fix exists ({', '.join(f'{m} → {v}' for m, v in fixed_map.items())}) but the "
                f"module is BOM-managed/transitive — no catalog line to bump; needs a human "
                f"platform decision" if unpinned else "no fixed version in OSV yet")
            print(f"{cve} (#{issue['number']}): {note}")
            if fixed_map and unpinned and not args.dry_run:
                gh(f"{API}/repos/{REPO}/issues/{issue['number']}/comments", "POST", {
                    "body": f"vex-hotfix: {note}. No automatic PR opened. — ADR-0279 #13"})
            continue
        for module, fixed in hits:
            alias, pinned = pins[module]
            try:
                open_hotfix_pr(cve, issue, module, alias, pinned, fixed, args.dry_run)
                acted += 1
            except (subprocess.CalledProcessError, ValueError) as e:
                print(f"::warning::{cve}: hotfix PR failed ({e}) — issue stays open for a human")
    print(f"vex-hotfix: {acted} hotfix PR(s) opened/reused")
    return 0


if __name__ == "__main__":
    sys.exit(main())
