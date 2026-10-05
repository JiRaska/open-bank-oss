#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Check the latest release of every versioned OpenBank component (#11685).

The CI gate checks that each latest release has the complete evidence asset set.
The scheduled verifier consumes the same tag inventory and verifies the signatures
and manifest digests with the KMS-backed cosign key. A missing component, release,
asset, or readable API response is a finding, never an empty green inventory.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gatelib

ROOT = Path(__file__).resolve().parents[2]
ASSET_SUFFIXES = (
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
# The current graduation criterion expressly excludes admin-ui. It must be reconsidered
# when its non-Gradle SBOM source has verified real releases.
EXCLUDED = {"openbank-admin-ui"}


def expected_components(root: Path) -> tuple[dict[str, str], list[str]]:
    packages = json.loads((root / "release-please-config.json").read_text())["packages"]
    result: dict[str, str] = {}
    findings: list[str] = []
    for version in sorted(root.glob("openbank-*/version.txt")):
        name = version.parent.name
        if name in EXCLUDED:
            continue
        component = (packages.get(name) or {}).get("component")
        if not isinstance(component, str) or not component:
            findings.append(f"{name}: versioned package has no release component")
        elif component in result:
            findings.append(f"{name}: duplicate release component {component}")
        else:
            result[component] = name
    if not result:
        findings.append("no versioned release components discovered")
    return result, findings


def fetch_releases(repository: str, token: str) -> list[dict]:
    if not token:
        raise ValueError("GH_TOKEN is required for release inventory")
    releases: list[dict] = []
    for page in range(1, 101):
        url = f"https://api.github.com/repos/{repository}/releases?per_page=100&page={page}"
        request = urllib.request.Request(
            url,
            headers={
                "Authorization": f"Bearer {token}",
                "Accept": "application/vnd.github+json",
                "User-Agent": "openbank-fleet-release-evidence",
            },
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            batch = json.load(response)
        if not isinstance(batch, list):
            raise TypeError("release API did not return a list")
        releases.extend(batch)
        if len(batch) < 100:
            return releases
    raise ValueError(
        "release API pagination exceeded 100 pages; inventory is incomplete"
    )


def check_fleet(
    components: dict[str, str], releases: list[dict]
) -> tuple[list[str], list[str]]:
    findings: list[str] = []
    latest: dict[str, tuple[str, dict]] = {}
    for release in releases:
        if not isinstance(release, dict):
            findings.append("malformed release entry")
            continue
        if release.get("draft") or release.get("prerelease"):
            continue
        tag = release.get("tag_name")
        published = release.get("published_at")
        if not isinstance(tag, str) or not isinstance(published, str):
            findings.append("published release has no tag or timestamp")
            continue
        if not re.fullmatch(r"[A-Za-z0-9._-]+", tag):
            findings.append("published release has an unsafe tag name")
            continue
        for component in components:
            if tag.startswith(f"{component}-v"):
                if component not in latest or published > latest[component][0]:
                    latest[component] = (published, release)
                break
    tags: list[str] = []
    for component, package in sorted(components.items()):
        if component not in latest:
            findings.append(f"{package}: no published release for {component}")
            continue
        published, release = latest[component]
        tag = release["tag_name"]
        tags.append(tag)
        assets = release.get("assets")
        if not isinstance(assets, list) or any(
            not isinstance(asset, dict)
            or not isinstance(asset.get("name"), str)
            or not isinstance(asset.get("size"), int)
            or isinstance(asset.get("size"), bool)
            for asset in assets
        ):
            findings.append(f"{tag}: malformed release asset list")
            continue
        names = {asset["name"]: asset["size"] for asset in assets}
        if len(names) != len(assets):
            findings.append(f"{tag}: duplicate release asset name")
        missing = sorted(
            f"{tag}{suffix}"
            for suffix in ASSET_SUFFIXES
            if names.get(f"{tag}{suffix}", 0) <= 0
        )
        if missing:
            findings.append(
                f"{tag}: missing or empty evidence assets: {', '.join(missing)}"
            )
        # Grace never substitutes an older release for the actual latest. It is an explicit
        # pending observation so the graduation record cannot count this run as clean.
        try:
            age = dt.datetime.now(dt.timezone.utc) - dt.datetime.fromisoformat(
                published.replace("Z", "+00:00")
            )
            if age < dt.timedelta(hours=2):
                findings.append(
                    f"{tag}: latest release is inside the two-hour evidence grace window (pending)"
                )
        except ValueError:
            findings.append(f"{tag}: invalid publication timestamp")
    return tags, findings


def workflow_findings(source: str) -> list[str]:
    """Keep the scheduled cryptographic consumer attached to the fleet inventory."""
    findings: list[str] = []
    # actionlint and the duplicate-key gate parse this YAML; this check asserts the
    # producer wiring without making the standalone scheduled job install PyYAML.
    commands = "\n".join(
        line for line in source.splitlines() if not line.lstrip().startswith("#")
    )
    if not re.search(r"^on:\s*\n\s+schedule:", commands, re.MULTILINE):
        findings.append("verification workflow has no schedule")
    for required in (
        "check-fleet-release-evidence.py --tags-output tags.txt",
        "verify-blob",
        "verify-release-evidence-manifest.py",
    ):
        if required not in commands:
            findings.append(f"verification workflow lacks {required}")
    if commands.count("done < tags.txt") < 2:
        findings.append(
            "verification workflow does not download and verify every selected tag"
        )
    return findings


def self_test() -> int:
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        (root / "release-please-config.json").write_text(
            json.dumps(
                {
                    "packages": {
                        "openbank-covered": {"component": "covered"},
                        "openbank-missing": {},
                    }
                }
            )
        )
        for name in ("openbank-covered", "openbank-missing", "openbank-admin-ui"):
            (root / name).mkdir()
            (root / name / "version.txt").write_text("1.0.0\n")
        components, inventory_findings = expected_components(root)
        if components != {"covered": "openbank-covered"} or not any(
            "openbank-missing" in finding for finding in inventory_findings
        ):
            print(
                f"FAIL self-test: missing component input accepted: {inventory_findings}"
            )
            return 1
        print("PASS self-test: missing component input rejected")
    component = {"fixture-service": "openbank-fixture-service"}
    tag = "fixture-service-v1.2.3"
    valid = {
        "tag_name": tag,
        "published_at": "2020-01-01T00:00:00Z",
        "draft": False,
        "prerelease": False,
        "assets": [{"name": f"{tag}{suffix}", "size": 10} for suffix in ASSET_SUFFIXES],
    }
    scenarios = [
        ("complete latest release", [valid], False),
        ("missing release", [], True),
        ("missing signature", [{**valid, "assets": valid["assets"][:-1]}], True),
        (
            "empty asset",
            [
                {
                    **valid,
                    "assets": [
                        {**a, "size": 0} if a["name"].endswith(".sig") else a
                        for a in valid["assets"]
                    ],
                }
            ],
            True,
        ),
        ("malformed assets", [{**valid, "assets": None}], True),
    ]
    for label, releases, should_fail in scenarios:
        tags, findings = check_fleet(component, releases)
        if tags != ([tag] if releases else []) or bool(findings) != should_fail:
            print(f"FAIL self-test: {label}: tags={tags}, findings={findings}")
            return 1
        print(f"PASS self-test: {label}")
    workflow = (ROOT / ".github/workflows/verify-release-evidence.yml").read_text()
    if workflow_findings(workflow):
        print(
            f"FAIL self-test: verification workflow detached: {workflow_findings(workflow)}"
        )
        return 1
    broken = workflow.replace("verify-blob", "removed-verifier")
    if not workflow_findings(broken):
        print("FAIL self-test: missing verification workflow accepted")
        return 1
    print("PASS self-test: missing verification workflow rejected")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--tags-output", type=Path)
    parser.add_argument(
        "--repository",
        default=os.environ.get("GITHUB_REPOSITORY", "JiRaska/open-bank-oss"),
    )
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    try:
        components, findings = expected_components(ROOT)
        workflow = (ROOT / ".github/workflows/verify-release-evidence.yml").read_text()
        findings.extend(workflow_findings(workflow))
        releases = fetch_releases(args.repository, os.environ.get("GH_TOKEN", ""))
        tags, release_findings = check_fleet(components, releases)
        findings.extend(release_findings)
    except (
        OSError,
        ValueError,
        TypeError,
        KeyError,
        urllib.error.URLError,
        json.JSONDecodeError,
    ) as exc:
        gatelib.subjects_unresolved("release inventory API or configuration unreadable")
        print(f"::error::fleet release evidence inventory unresolved: {exc}")
        return 2
    gatelib.subjects(len(components), "versioned release components")
    print(
        f"Checked {len(components)} release components; {len(tags)} latest tags resolved"
    )
    if args.tags_output:
        args.tags_output.write_text("".join(f"{tag}\n" for tag in tags))
    for finding in findings:
        print(f"::error::{finding}")
    if findings:
        return 1
    print("PASS: every latest release has its full evidence asset set")
    return 0


if __name__ == "__main__":
    sys.exit(main())
