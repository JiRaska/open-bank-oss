#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Dependency freshness score — fleet-level from the catalog, per-service from usage.

WHY (ADR-0279 WS3 #15). Dependabot already OPENS the bump PRs every Monday; what nobody
had was the number that says whether we are winning — a fleet freshness score, and its
per-service decomposition, so "which service drags the average down" is a lookup, not an
investigation. This script computes it and (in the workflow) maintains a standing issue
whose body is always the latest report.

HOW:
  * pinned modules come from `openbank-libs/gradle/libs.versions.toml` (direct pins only;
    BOM-managed entries have no version to be stale);
  * the latest available version comes from Maven Central's solrsearch API (network —
    weekly job, never a PR gate; per-module failures degrade to `unknown`, never fail
    the run);
  * per-service attribution is offline: an alias used in `<svc>/build.gradle.kts` counts
    toward that service. A library used nowhere still counts toward the FLEET score —
    an unused stale pin is latent debt, not someone else's problem.

SCORE (per module): 100 current, 75 behind-patch, 50 behind-minor, 25 behind-major,
0 unknown-is-not-counted (excluded from the average, listed separately). The fleet and
per-service scores are plain averages over their modules.

Usage:  deps-freshness.py [--json out.json] [--md out.md] [--limit N]
        deps-freshness.py --self-test     # offline fixtures, no network
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

CATALOG = Path("openbank-libs/gradle/libs.versions.toml")


# ── offline halves ───────────────────────────────────────────────────────────

def parse_catalog(text: str) -> dict[str, tuple[str, str]]:
    """module 'group:artifact' -> (alias, pinned) for direct pins only."""
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
            out[module] = (alias.replace("-", "."), ver)
    return out


def version_key(v: str) -> tuple:
    return tuple(int(p) if p.isdigit() else 0 for p in re.split(r"[.\-+]", v))


def staleness(pinned: str, latest: str) -> str:
    """current / behind-patch / behind-minor / behind-major by first differing segment."""
    p, l = version_key(pinned), version_key(latest)
    if l <= p:
        return "current"
    n = max(len(p), len(l))
    p += (0,) * (n - len(p))
    l += (0,) * (n - len(l))
    idx = next(i for i in range(n) if p[i] != l[i])
    return ["behind-major", "behind-minor", "behind-patch"][min(idx, 2)]


SCORE = {"current": 100, "behind-patch": 75, "behind-minor": 50, "behind-major": 25}


def service_usage(root: Path) -> dict[str, set[str]]:
    """service dir -> set of catalog aliases it references (libs.<alias>)."""
    out: dict[str, set[str]] = {}
    for build in root.glob("openbank-*/build.gradle.kts"):
        svc = build.parent.name
        for alias in re.findall(r"libs\.([a-z0-9.]+)", build.read_text()):
            out.setdefault(svc, set()).add(alias)
    return out


def aggregate(modules: dict[str, dict], usage: dict[str, set[str]],
              pins: dict[str, tuple[str, str]]) -> dict:
    """modules: module -> {state}; returns fleet + per-service averages."""
    alias_to_module = {alias: mod for mod, (alias, _) in pins.items()}
    scored = [m for m in modules.values() if m["state"] in SCORE]
    fleet = round(sum(SCORE[m["state"]] for m in scored) / len(scored)) if scored else None
    services = {}
    for svc, aliases in sorted(usage.items()):
        states = [modules[alias_to_module[a]]["state"] for a in aliases
                  if a in alias_to_module and alias_to_module[a] in modules
                  and modules[alias_to_module[a]]["state"] in SCORE]
        if states:
            services[svc] = round(sum(SCORE[s] for s in states) / len(states))
    return {"fleet": fleet, "services": services}


def render_md(report: dict) -> str:
    lines = ["# Dependency freshness report", "",
             f"Fleet score: **{report['fleet'] if report['fleet'] is not None else 'n/a'}/100** "
             f"over {report['scored_modules']} directly-pinned modules "
             f"({report['unknown_modules']} unknown — Maven Central unreachable for them).", "",
             "## Stale modules", "",
             "| module | pinned | latest | state |", "|---|---|---|---|"]
    for mod, m in sorted(report["modules"].items()):
        if m["state"] != "current":
            lines.append(f"| `{mod}` | {m['pinned']} | {m.get('latest', '—')} | {m['state']} |")
    lines += ["", "## Per-service score (bottom 15)", "",
              "| service | score |", "|---|---|"]
    for svc, score in sorted(report["services"].items(), key=lambda kv: kv[1])[:15]:
        lines.append(f"| `{svc}` | {score} |")
    lines += ["", "_Maintained by deps-freshness.yml (ADR-0279 #15). Do not edit by hand._"]
    return "\n".join(lines) + "\n"


def self_test() -> int:
    bad = 0
    if staleness("1.5.6", "1.5.13") != "behind-patch":
        print("self-test FAIL: patch staleness"); bad += 1
    if staleness("1.5.6", "1.7.0") != "behind-minor":
        print("self-test FAIL: minor staleness"); bad += 1
    if staleness("1.5.6", "2.0.0") != "behind-major":
        print("self-test FAIL: major staleness"); bad += 1
    if staleness("1.5.13", "1.5.6") != "current":
        print("self-test FAIL: pinned-ahead is current"); bad += 1
    pins = parse_catalog(
        "[versions]\nlogback = \"1.5.6\"\n\n[libraries]\n"
        "logback-classic = { module = \"ch.qos.logback:logback-classic\", version.ref = \"logback\" }\n")
    if pins != {"ch.qos.logback:logback-classic": ("logback.classic", "1.5.6")}:
        print("self-test FAIL: catalog parse / alias dotted form"); bad += 1
    mods = {"m": {"state": "behind-minor", "pinned": "1", "latest": "2"}}
    agg = aggregate(mods, {"svc-a": {"logback.classic"}}, pins)
    if agg["fleet"] != 50 or agg["services"]:
        print("self-test FAIL: aggregate must not attribute an alias the fixture pins lack"); bad += 1
    pins2 = {"m": ("logback.classic", "1")}
    agg2 = aggregate(mods, {"svc-a": {"logback.classic"}, "svc-b": set()}, pins2)
    if agg2["services"] != {"svc-a": 50} or agg2["fleet"] != 50:
        print("self-test FAIL: per-service aggregation"); bad += 1
    md = render_md({"fleet": 50, "scored_modules": 1, "unknown_modules": 0,
                    "modules": mods, "services": {"svc-a": 50}})
    if "behind-minor" not in md or "svc-a" not in md:
        print("self-test FAIL: markdown render"); bad += 1
    print("deps-freshness self-test: " + ("clean" if not bad else f"{bad} failure(s)"))
    return 1 if bad else 0


# ── networked half ───────────────────────────────────────────────────────────

def latest_version(group: str, artifact: str) -> str | None:
    q = urllib.parse.urlencode({"q": f'g:"{group}" AND a:"{artifact}"',
                                "rows": "1", "wt": "json"})
    url = f"https://search.maven.org/solrsearch/select?{q}"
    try:
        with urllib.request.urlopen(url, timeout=15) as r:
            doc = json.loads(r.read())
        docs = doc.get("response", {}).get("docs", [])
        return docs[0].get("latestVersion") if docs else None
    except (urllib.error.URLError, json.JSONDecodeError, TimeoutError, KeyError, IndexError):
        return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--json")
    ap.add_argument("--md")
    ap.add_argument("--limit", type=int, default=0, help="only first N modules (smoke runs)")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()

    pins = parse_catalog(CATALOG.read_text())
    usage = service_usage(Path("."))
    modules: dict[str, dict] = {}
    items = sorted(pins.items())[: args.limit or None]
    for module, (alias, pinned) in items:
        group, artifact = module.split(":", 1)
        latest = latest_version(group, artifact)
        state = "unknown" if latest is None else staleness(pinned, latest)
        modules[module] = {"alias": alias, "pinned": pinned, "latest": latest, "state": state}
        if state != "current":
            print(f"  {module}: {pinned} -> {latest or '?'} ({state})")
    agg = aggregate(modules, usage, pins)
    report = {
        "fleet": agg["fleet"],
        "scored_modules": sum(1 for m in modules.values() if m["state"] in SCORE),
        "unknown_modules": sum(1 for m in modules.values() if m["state"] == "unknown"),
        "modules": modules,
        "services": agg["services"],
    }
    print(f"fleet freshness: {report['fleet']}/100 "
          f"({report['scored_modules']} scored, {report['unknown_modules']} unknown)")
    if args.json:
        Path(args.json).write_text(json.dumps(report, indent=2) + "\n")
    if args.md:
        Path(args.md).write_text(render_md(report))
    return 0


if __name__ == "__main__":
    sys.exit(main())
