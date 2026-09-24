#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Every released package must resolve to a component and every release must carry its evidence bundle.


Issue #7597. `.github/workflows/release-please.yml`'s "Map released components -> tags" step
builds `released.tsv` from `release-please-config.json`'s per-package `component` field:

    comp = (cfg.get(p) or {}).get("component")
    ...
    if comp and tag:
        print("\t".join([p, comp, ver, tag, upurl]))

A package with NO `component` key produces no row in `released.tsv` — silently, no error. Every
downstream step (SBOM generation, cosign provenance attestation, VEX) reads only `released.tsv`,
so that package's release ships with NO supply-chain evidence at all, while release-please still
cuts the tag and the release, and everything else about the release LOOKS normal (green CI, a
GitHub Release, a tag). Nothing in the pipeline says "this one has no evidence" — the gap is
visible only by reading `released.tsv` on a specific run, or by diffing every `version.txt`
against `release-please-config.json`'s `packages` map by hand.

Measured today: `openbank-campaign-service` and `openbank-tax-reporting-service` both carry a
`version.txt` (so release-please treats them as released, versioned components) and both have a
`packages` entry with `"release-type": "simple"` and NO `component` key.

THIS SCRIPT DOES NOT FIX THAT. Adding a `component` key changes the release TAG NAME
(`include-component-in-tag: true`), and `version.txt` / `release-please-config.json` /
`.release-please-manifest.json` are a three-way lockstep for an ALREADY-RELEASED package
(ADR-0029 rule 2) — retagging an existing package's release history is a deliberate call for the
repo owner, not a drive-by fix bundled with a CI gate. What this script guards is the DURABLE half
of the issue: a package with a `version.txt` must resolve to a component that reaches
`released.tsv`, derived from the same config the workflow reads — never from a hand-kept list —
so a THIRD package cannot join the two above silently.

DERIVED, NOT LISTED: "released package" is discovered the same way
`check-test-intelligence-ecosystem.py` counts released packages — `openbank-*/version.txt` on
disk — and cross-referenced against `release-please-config.json`'s `packages` map, read fresh
every run. Nothing here re-encodes which packages exist.

    python3 .github/scripts/check-release-evidence-coverage.py --enforce
    python3 .github/scripts/check-release-evidence-coverage.py --self-test
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path
from urllib.parse import quote

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gatelib  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
CONFIG = ROOT / "release-please-config.json"

# Packages known TODAY to have a version.txt but no `component` in release-please-config.json, so
# their releases carry no SBOM/provenance/VEX (#7597). Adding a `component` key is a deliberate,
# separate decision for the repo owner (it changes the release tag name — ADR-0029 rule 2 lockstep
# for an already-released package). This baseline keeps the gate GREEN today and red the moment a
# THIRD package joins without either a component or a declared reason here.
#
# Kept here rather than in rules.yaml on purpose, same reasoning as check-pact-provider-replay.py:
# most gen-*opa-bundle*.sh scripts hash rules.yaml into every service's OPA bundle checksum, and
# this list belongs next to the code that reads it, not in a file that restamps ~40 unrelated
# artifacts when it changes.
KNOWN_UNCOVERED: dict[str, str] = {
    "openbank-campaign-service": "#7597 — release-please-config.json has no `component`; adding one "
    "changes the release tag name, a repo-owner decision, not a drive-by fix",
    "openbank-tax-reporting-service": "#7597 — same as openbank-campaign-service",
}

errors: list[str] = []


def fail(msg: str) -> None:
    errors.append(msg)


def discover_released_packages(root: Path) -> set[str]:
    """Package directories release-please treats as released: `openbank-*/version.txt` on disk.

    Same discovery `check-test-intelligence-ecosystem.py` uses to count released packages — never
    a hand-kept list, so a new service with a version.txt is picked up automatically.
    """
    return {f.parent.name for f in root.glob("openbank-*/version.txt") if f.is_file()}


def load_packages_config(config_path: Path) -> dict[str, dict]:
    data = json.loads(config_path.read_text(encoding="utf-8"))
    return data.get("packages") or {}


def check_coverage(
    released: set[str],
    packages_cfg: dict[str, dict],
    known_uncovered: dict[str, str],
) -> None:
    for pkg in sorted(released):
        cfg = packages_cfg.get(pkg)
        component = (cfg or {}).get("component")
        covered = bool(component)
        baselined = pkg in known_uncovered

        if covered and baselined:
            fail(
                f"{pkg} is listed in KNOWN_UNCOVERED but release-please-config.json now declares "
                f"component={component!r} — delete the stale entry, #7597 is what should empty "
                "this list"
            )
            continue
        if covered:
            continue
        if baselined:
            continue

        if cfg is None:
            fail(
                f"{pkg} has a version.txt (release-please treats it as released) but no entry at "
                "all in release-please-config.json's `packages` map — it cannot be released, let "
                "alone produce evidence. Register it, or if it is not meant to release, drop "
                "version.txt / .release-please-manifest.json instead."
            )
        else:
            fail(
                f"{pkg} has a version.txt and a release-please-config.json entry, but that entry "
                "has no `component` key — its releases produce NO row in released.tsv, so no "
                "SBOM, no cosign provenance, and no VEX ship with the release (#7597). Either add "
                "`component` (repo-owner decision: it changes the release tag name, ADR-0029 rule "
                "2 lockstep) or add it to KNOWN_UNCOVERED here with a reason."
            )


def check_baseline_is_live(
    released: set[str], packages_cfg: dict[str, dict], known_uncovered: dict[str, str]
) -> None:
    for pkg in sorted(known_uncovered):
        if pkg not in released:
            fail(
                f"KNOWN_UNCOVERED lists {pkg}, which has no version.txt anymore — it is not a "
                "released package, drop the stale entry"
            )
        elif pkg not in packages_cfg:
            fail(
                f"KNOWN_UNCOVERED lists {pkg}, which has no release-please-config.json entry at "
                "all — that is a worse problem than a missing component, see the 'no entry' error"
            )


REQUIRED_ASSET_SUFFIXES = (
    ".cdx.json",
    ".cdx.json.sig",
    ".cdx.json.intoto.jsonl",
    ".slsa.json",
    ".slsa.json.sig",
    ".vex.json",
    ".vex.json.sig",
    ".evidence.json",
    ".evidence.json.sig",
)


def expected_release_assets(tag: str) -> set[str]:
    return {f"{tag}{suffix}" for suffix in REQUIRED_ASSET_SUFFIXES}


def missing_release_assets(tag: str, actual: set[str]) -> list[str]:
    return sorted(expected_release_assets(tag) - actual)


def fetch_release_asset_names(repository: str, tag: str, runner=None) -> tuple[set[str] | None, str | None]:
    if runner is None:
        runner = subprocess.run
    endpoint = f"repos/{repository}/releases/tags/{quote(tag, safe='-._')}"
    try:
        result = runner(["gh", "api", endpoint], capture_output=True, text=True, check=False)
    except OSError as exc:
        return None, f"UNRESOLVED release API for {tag}: {exc}"
    if result.returncode != 0:
        detail = (result.stderr or "").strip()
        lowered = detail.lower()
        if "api rate limit exceeded" in lowered or "secondary rate limit" in lowered or "http 429" in lowered:
            return None, f"UNRESOLVED release API rate limit for {tag}: {detail or 'no error details'}"
        return None, f"UNRESOLVED release API for {tag}: {detail or 'no error details'}"
    try:
        release = json.loads(result.stdout or "")
    except json.JSONDecodeError as exc:
        return None, f"UNRESOLVED malformed release API response for {tag}: {exc}"
    assets = release.get("assets") if isinstance(release, dict) else None
    if not isinstance(assets, list) or any(
        not isinstance(asset, dict) or not isinstance(asset.get("name"), str) for asset in assets
    ):
        return None, f"UNRESOLVED malformed release asset list for {tag}"
    return {asset["name"] for asset in assets}, None


def verify_released_assets(paths: list[str], outputs: dict, repository: str, runner=None) -> list[str]:
    findings: list[str] = []
    if not isinstance(paths, list) or not paths:
        return ["UNRESOLVED release-please reported no released paths to verify"]
    if not isinstance(outputs, dict):
        return ["UNRESOLVED release-please rp_outputs is not a JSON object"]
    for path in paths:
        if not isinstance(path, str) or not path:
            findings.append(f"UNRESOLVED invalid path in release-please outputs: {path!r}")
            continue
        tag = outputs.get(f"{path}--tag_name")
        if not isinstance(tag, str) or not tag:
            findings.append(f"UNRESOLVED release-please produced no tag_name for released path {path}")
            continue
        actual, error = fetch_release_asset_names(repository, tag, runner)
        if error:
            findings.append(error)
            continue
        missing = missing_release_assets(tag, actual or set())
        if missing:
            findings.append(f"{tag} is missing required release evidence assets: {', '.join(missing)}")
        else:
            print(f"PASS {tag}: all {len(REQUIRED_ASSET_SUFFIXES)} required release evidence assets are attached")
    return findings


def current_release_asset_workflow_findings() -> list[str]:
    path = ROOT / ".github/workflows/release-please.yml"
    try:
        workflow = yaml.safe_load(path.read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as exc:
        return [f"release-please.yml cannot be parsed for asset verification: {exc}"]
    return release_asset_workflow_findings(workflow)


def release_asset_workflow_findings(workflow: dict) -> list[str]:
    findings: list[str] = []
    jobs = workflow.get("jobs") if isinstance(workflow, dict) else None
    job = jobs.get("release-evidence-assets") if isinstance(jobs, dict) else None
    if not isinstance(job, dict):
        return ["release-please.yml has no release-evidence-assets postcondition job"]
    needs = job.get("needs")
    required_needs = {"release-please", "release-evidence", "provenance-subjects", "provenance"}
    need_names = {name for name in needs if isinstance(name, str)} if isinstance(needs, list) else set()
    if not required_needs.issubset(need_names):
        findings.append("release-evidence-assets must wait for release evidence and provenance jobs")
    condition = str(job.get("if", ""))
    if "always()" not in condition or "needs.release-please.outputs.releases_created" not in condition:
        findings.append("release-evidence-assets must run after upstream failures only when a release was created")
    steps = job.get("steps")
    commands = [str(step.get("run", "")) for step in steps if isinstance(step, dict)] if isinstance(steps, list) else []
    command = " ".join(commands)
    for argument in ("--verify-release-assets", "--paths-released", "--rp-outputs", "--repository"):
        if argument not in command:
            findings.append(f"release-evidence-assets does not invoke verifier argument {argument}")
    return findings


def self_test_release_assets() -> list[tuple[str, bool]]:
    tag = "fixture-v1.2.3"
    expected = expected_release_assets(tag)

    def response(assets: set[str], returncode: int = 0, stderr: str = ""):
        payload = json.dumps({"assets": [{"name": name} for name in sorted(assets)]})
        return subprocess.CompletedProcess(["gh", "api"], returncode, payload, stderr)

    def complete_runner(_command, **_kwargs):
        return response(expected)

    complete = verify_released_assets(["openbank-fixture"], {"openbank-fixture--tag_name": tag}, "owner/repo", complete_runner)
    incomplete_names = expected - {f"{tag}.evidence.json.sig"}

    def incomplete_runner(_command, **_kwargs):
        return response(incomplete_names)

    incomplete = verify_released_assets(["openbank-fixture"], {"openbank-fixture--tag_name": tag}, "owner/repo", incomplete_runner)

    def empty_runner(_command, **_kwargs):
        return response(set())

    empty = verify_released_assets(["openbank-fixture"], {"openbank-fixture--tag_name": tag}, "owner/repo", empty_runner)

    def rate_limited_runner(_command, **_kwargs):
        return subprocess.CompletedProcess(
            ["gh", "api"], 1, "", "gh: API rate limit exceeded for installation (HTTP 403)"
        )

    unreadable = verify_released_assets(["openbank-fixture"], {"openbank-fixture--tag_name": tag}, "owner/repo", rate_limited_runner)
    return [
        ("complete release passes with all nine assets", not complete),
        ("release missing a signature is rejected", any(f"{tag}.evidence.json.sig" in item for item in incomplete)),
        ("release with no assets is rejected", bool(empty) and len(expected) == len(REQUIRED_ASSET_SUFFIXES)),
        ("rate-limited release lookup is unresolved, not a pass", any("UNRESOLVED release API rate limit" in item for item in unreadable)),
        ("released path without a tag is unresolved", bool(verify_released_assets(["openbank-missing-tag"], {}, "owner/repo", complete_runner))),
    ]


def self_test() -> int:
    """Feed every check an input it MUST flag. A gate whose failure path never ran is unfalsified."""
    print("== self-test: each check must reject a known-bad input ==")
    results: list[tuple[str, bool]] = []

    def run(name: str, fn) -> None:
        global errors
        saved, errors = errors, []
        try:
            fn()
            caught = errors
        finally:
            errors = saved
        results.append((name, bool(caught)))
        print(f"  {name}: {'PASS (rejected)' if caught else 'FAIL (accepted bad input!)'}")
        if caught:
            print(f"      first message: {caught[0][:150]}")

    covered_cfg = {"openbank-a": {"component": "a"}}
    uncovered_cfg = {"openbank-b": {"release-type": "simple"}}
    missing_cfg: dict[str, dict] = {}

    # 1. A package with a component must NOT be flagged (the positive control) — checked via the
    # inverse of `run`: this call must produce NO errors, so assert that directly rather than with
    # the "must reject" helper.
    def clean(fn) -> bool:
        global errors
        saved, errors = errors, []
        try:
            fn()
            return not errors
        finally:
            errors = saved

    covered_clean = clean(lambda: check_coverage({"openbank-a"}, covered_cfg, {}))
    results.append(("covered package (has component) passes clean", covered_clean))
    print(f"  covered package (has component) passes clean: {'PASS' if covered_clean else 'FAIL'}")

    # 2. A package with no component and no baseline entry must be flagged.
    run(
        "uncovered package with NO baseline entry is flagged",
        lambda: check_coverage({"openbank-b"}, uncovered_cfg, {}),
    )

    # 3. A package with no component but a baseline entry is accepted (the #7597 shape today).
    baselined_clean = clean(lambda: check_coverage({"openbank-b"}, uncovered_cfg, {"openbank-b": "reason"}))
    results.append(("baselined uncovered package is accepted", baselined_clean))
    print(f"  baselined uncovered package is accepted: {'PASS' if baselined_clean else 'FAIL'}")

    # 4. A package present on disk (version.txt) but entirely absent from packages_cfg.
    run(
        "released package with no config entry at all is flagged",
        lambda: check_coverage({"openbank-c"}, missing_cfg, {}),
    )

    # 5. A baseline entry that has since gained a component must be flagged as stale.
    run(
        "baseline entry that is now covered is flagged as stale",
        lambda: check_coverage({"openbank-a"}, covered_cfg, {"openbank-a": "stale reason"}),
    )

    # 6. A baseline entry naming a package with no version.txt must be flagged as stale.
    run(
        "baseline entry for a package that no longer has version.txt is flagged",
        lambda: check_baseline_is_live(set(), {}, {"openbank-gone": "reason"}),
    )

    for name, passed in self_test_release_assets():
        results.append((name, passed))
        print(f"  {name}: {'PASS' if passed else 'FAIL'}")

    workflow_ok = not current_release_asset_workflow_findings()
    results.append(("release workflow verifies every created release after upstream jobs", workflow_ok))
    print(f"  release workflow runs its postcondition: {'PASS' if workflow_ok else 'FAIL'}")
    broken_workflow = release_asset_workflow_findings({"jobs": {}})
    results.append(("workflow without the postcondition is rejected", bool(broken_workflow)))
    print(f"  workflow without the postcondition is rejected: {'PASS' if broken_workflow else 'FAIL'}")

    ok = all(flagged for _, flagged in results)
    print()
    print("self-test: ALL CHECKS CAN FAIL" if ok else "self-test: SOME CHECK IS UNFALSIFIED")
    return 0 if ok else 1


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--enforce", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--verify-release-assets", action="store_true")
    parser.add_argument("--paths-released")
    parser.add_argument("--rp-outputs")
    parser.add_argument("--repository")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    if args.verify_release_assets:
        if not args.paths_released or not args.rp_outputs or not args.repository:
            parser.error("--verify-release-assets requires --paths-released, --rp-outputs, and --repository")
        try:
            paths = json.loads(args.paths_released)
            outputs = json.loads(args.rp_outputs)
        except json.JSONDecodeError as exc:
            print(f"::error::UNRESOLVED invalid release-please JSON: {exc}")
            return 1
        findings = verify_released_assets(paths, outputs, args.repository)
        if findings:
            for finding in findings:
                print(f"::error::{finding}")
            return 1
        print("OK — every release-please tag has the complete release evidence bundle")
        return 0

    released = discover_released_packages(ROOT)
    packages_cfg = load_packages_config(CONFIG)

    check_coverage(released, packages_cfg, KNOWN_UNCOVERED)
    check_baseline_is_live(released, packages_cfg, KNOWN_UNCOVERED)
    errors.extend(current_release_asset_workflow_findings())

    covered = sum(
        1
        for p in released
        if (packages_cfg.get(p) or {}).get("component")
    )
    gatelib.subjects(len(released), "released packages (version.txt on disk)")
    print(f"Released packages (version.txt on disk): {len(released)}")
    print(f"Resolve to a component that reaches released.tsv: {covered}")
    print(f"Declared uncovered (backlog, #7597): {len(KNOWN_UNCOVERED)}")
    print()

    if errors:
        for e in errors:
            print(f"::error::{e}")
        print(f"\nFAIL: {len(errors)} release-evidence-coverage problem(s).")
        return 1
    print("OK — every released package resolves to a component, or is a declared, still-accurate exception.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
