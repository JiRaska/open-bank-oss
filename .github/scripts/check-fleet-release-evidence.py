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
import hashlib
import json
import os
import re
import signal
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import release_evidence_assets as release_assets

ROOT = Path(__file__).resolve().parents[2]
ASSET_SUFFIXES = release_assets.REQUIRED_ASSET_SUFFIXES
FETCH_BUDGET_SECONDS = 60  # inside the advisory gate's 90-second total budget
SEMVER = re.compile(
    r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?"
)
# The current graduation criterion expressly excludes admin-ui. It must be reconsidered
# when its non-Gradle SBOM source has verified real releases.
EXCLUDED = {"openbank-admin-ui"}


def expected_packages(root: Path, packages: dict) -> set[str]:
    """Cover versioned packages plus local image producers and declared workload images.

    Like check-deploy-coverage.sh, image discovery is position independent: a sidecar
    or initContainer is still in scope. Digest pins also remain in this inventory.
    No deployment allowlist excuses a missing release-evidence subject.
    """
    result = {path.parent.name for path in root.glob("openbank-*/version.txt") if path.is_file()}
    result.update(name for name in packages if re.fullmatch(r"openbank-[a-z0-9-]+", name))
    result.update(path.parent.name for path in root.glob("openbank-*/Dockerfile*") if path.is_file())
    gitops = root / "openbank-infra/gitops"
    if not gitops.is_dir():
        raise ValueError("GitOps workload inventory is missing")
    references = set()
    for path in sorted(gitops.rglob("*")):
        if path.is_file() and path.suffix in {".yaml", ".yml"}:
            # Drop whole-line comments; preserve embedded pod templates as in deploy coverage.
            source = "\n".join(line for line in path.read_text().splitlines()
                               if not line.lstrip().startswith("#"))
            references.update(re.findall(r"\b(openbank-[a-z0-9-]+)(?=:[A-Za-z0-9._-]+|@sha256:[a-f0-9]{64})", source))
    if not references:
        raise ValueError("GitOps workload inventory has no first-party image references")
    return result | references


def expected_components(root: Path, subjects: set[str] | None = None) -> tuple[dict[str, str], list[str]]:
    packages = json.loads((root / "release-please-config.json").read_text())["packages"]
    if not isinstance(packages, dict):
        raise TypeError("release packages must be an object")
    if subjects is None:
        subjects = expected_packages(root, packages)
    result: dict[str, str] = {}
    findings: list[str] = []
    for name in sorted(subjects - EXCLUDED):
        if not (root / name / "version.txt").is_file():
            findings.append(f"UNRESOLVED {name}: deployable package has no version.txt")
        config = packages.get(name)
        component = config.get("component") if isinstance(config, dict) else None
        if not isinstance(component, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", component):
            findings.append(f"UNRESOLVED {name}: package has no valid release component")
        elif component in result:
            findings.append(f"UNRESOLVED {name}: duplicate release component {component}")
        else:
            result[component] = name
    if not result:
        findings.append("no release components discovered")
    return result, findings


def fetch_releases(repository: str, token: str, budget_seconds: float = FETCH_BUDGET_SECONDS) -> list[dict]:
    """Bound the entire paginated read, including a server trickling response bytes.

    SIGALRM is supported by both CI's Linux runner and the macOS operator path.
    A per-socket timeout alone cannot enforce a total inventory budget.
    """
    if not token:
        raise ValueError("GH_TOKEN is required for release inventory")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("invalid repository identity")
    if not 0 < budget_seconds <= FETCH_BUDGET_SECONDS:
        raise ValueError("invalid release inventory budget")
    started = time.monotonic()

    def expired(_signum, _frame):
        raise TimeoutError("release inventory exceeded its total fetch deadline")

    previous_handler = signal.signal(signal.SIGALRM, expired)
    previous_timer = signal.setitimer(signal.ITIMER_REAL, budget_seconds)
    try:
        releases: list[dict] = []
        seen = set()
        for page in range(1, 101):
            remaining = budget_seconds - (time.monotonic() - started)
            if remaining <= 0:
                expired(None, None)
            url = f"https://api.github.com/repos/{repository}/releases?per_page=100&page={page}"
            request = urllib.request.Request(url, headers={
                "Authorization": f"Bearer {token}",
                "Accept": "application/vnd.github+json",
                "User-Agent": "openbank-fleet-release-evidence",
            })
            with urllib.request.urlopen(request, timeout=min(10, remaining)) as response:
                batch = json.load(response)
            if not isinstance(batch, list):
                raise TypeError("release API did not return a list")
            if len(batch) > 100:
                raise ValueError("release API returned an oversized page")
            for release in batch:
                identity = release.get("id") if isinstance(release, dict) else None
                if type(identity) is not int or identity <= 0 or identity in seen:
                    raise ValueError("release API has a missing or repeated identity; pagination unresolved")
                seen.add(identity)
            releases.extend(batch)
            if len(batch) < 100:
                return releases
        raise ValueError("release API pagination exceeded 100 pages; inventory is incomplete")
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous_handler)
        if previous_timer[0] > 0:
            remaining = max(.001, previous_timer[0] - (time.monotonic() - started))
            signal.setitimer(signal.ITIMER_REAL, remaining, previous_timer[1])


def check_fleet(components: dict[str, str], releases: list[dict]) -> tuple[list[str], list[str]]:
    """Latest means greatest stable SemVer, NOT most recently published/backfilled.

    Build metadata has no precedence. Multiple releases with the same precedence
    are ambiguous and cannot supply a clean observation. Publication time is used
    only for a timezone-aware grace check, never to choose an older version.
    """
    findings: list[str] = []
    latest: dict[str, tuple[tuple[int, ...], dict, dt.datetime]] = {}
    ambiguous: set[str] = set()
    seen_ids: set[int] = set()
    seen_tags: set[str] = set()
    blocked: set[str] = set()

    def unresolved_release(message: str, component: str | None = None) -> None:
        # A malformed candidate cannot silently fall back to an older known-good tag.
        # If its scope is unknown, no component's latest release can be established.
        blocked.update({component} if component else components)
        scope = f"{components[component]}: " if component else ""
        findings.append(f"UNRESOLVED {scope}{message}")

    for release in releases:
        if not isinstance(release, dict):
            unresolved_release("malformed release entry")
            continue
        tag = release.get("tag_name")
        safe_tag = isinstance(tag, str) and re.fullmatch(r"[A-Za-z0-9._+-]+", tag)
        # Attribute only a safe, exact component prefix before interpreting other metadata.
        component = next((name for name in sorted(components, key=len, reverse=True)
                          if safe_tag and tag.startswith(f"{name}-v")), None)
        identity = release.get("id")
        if type(identity) is not int or identity <= 0 or identity in seen_ids:
            unresolved_release("missing or duplicate release identity")
            continue
        seen_ids.add(identity)
        if any(type(release.get(flag)) is not bool for flag in ("draft", "prerelease")):
            unresolved_release("release draft/prerelease flags must be booleans", component)
            continue
        if release["draft"] or release["prerelease"]:
            continue
        if not safe_tag:
            unresolved_release("published release has an invalid tag")
            continue
        if tag in seen_tags:
            unresolved_release(f"duplicate release tag {tag}", component)
            continue
        seen_tags.add(tag)
        published = release.get("published_at")
        try:
            if not isinstance(published, str):
                raise TypeError("missing timestamp")
            publication = dt.datetime.fromisoformat(published.replace("Z", "+00:00"))
            if publication.tzinfo is None or publication.utcoffset() is None:
                raise ValueError("timezone is missing")
        except (TypeError, ValueError):
            unresolved_release(f"{tag}: invalid publication timestamp", component)
            continue
        if component is None:
            continue
        version = SEMVER.fullmatch(tag[len(component) + 2:])
        if version is None:
            unresolved_release(f"{tag}: published stable tag is not stable SemVer", component)
            continue
        precedence = tuple(int(part) for part in version.groups())
        current = latest.get(component)
        if current is None or precedence > current[0]:
            latest[component] = (precedence, release, publication)
            ambiguous.discard(component)
        elif precedence == current[0]:
            ambiguous.add(component)
    tags: list[str] = []
    for component, package in sorted(components.items()):
        if component in blocked:
            continue
        if component not in latest:
            findings.append(f"{package}: no published stable release for {component}")
            continue
        if component in ambiguous:
            findings.append(f"UNRESOLVED {package}: ambiguous latest stable SemVer")
            continue
        _version, release, publication = latest[component]
        tag = release["tag_name"]
        tags.append(tag)
        assets = release.get("assets")
        if not isinstance(assets, list) or any(
            not isinstance(asset, dict)
            or not isinstance(asset.get("name"), str)
            or type(asset.get("size")) is not int
            or asset["size"] < 0 for asset in assets
        ):
            findings.append(f"UNRESOLVED {tag}: malformed release asset list")
            continue
        names = {asset["name"]: asset["size"] for asset in assets}
        if len(names) != len(assets):
            findings.append(f"UNRESOLVED {tag}: duplicate release asset name")
        missing = release_assets.missing_release_assets(tag, set(names))
        empty = sorted(name for name in release_assets.expected_release_assets(tag) if names.get(name) == 0)
        if missing or empty:
            findings.append(f"{tag}: missing or empty evidence assets: {', '.join(sorted(set(missing + empty)))}")
        age = dt.datetime.now(dt.timezone.utc) - publication
        if age < dt.timedelta(hours=2):
            findings.append(f"{tag}: latest release is inside the two-hour evidence grace window (pending)")
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
    if not re.search(r"\bverify-blob\s+--key\b", commands):
        findings.append("verification workflow has no executable signature verifier")
    if "release-identities.tsv" in commands:
        if commands.count("done < tags.txt") < 1 or commands.count("done < release-identities.tsv") < 2:
            findings.append("verification workflow does not resolve, download and verify every selected tag")
    elif commands.count("done < tags.txt") < 2:
        findings.append("verification workflow does not download and verify every selected tag")
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
        gitops = root / "openbank-infra/gitops"
        gitops.mkdir(parents=True)
        (gitops / "fixture.yaml").write_text("image: registry/openbank-covered:sandbox-abc\n")
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
        "id": 1,
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
    return subprocess.run([sys.executable, str(Path(__file__).with_name("test-fleet-release-evidence.py"))], check=False).returncode


def inventory_report(subjects: set[str] | None, components: dict[str, str], tags: list[str],
                     releases: list[dict], findings: list[str], unresolved: bool = False) -> dict:
    """An inventory receipt, never a cryptographic or graduation verdict."""
    revision = os.environ.get("GITHUB_WORKFLOW_SHA")
    if not revision:
        result = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT,
                                capture_output=True, text=True, check=False)
        revision = result.stdout.strip() if result.returncode == 0 else None
    if revision is not None and not re.fullmatch(r"[a-f0-9]{40}", revision):
        revision = None
    by_package = {package: component for component, package in components.items()}
    selected = {component: next((tag for tag in tags if tag.startswith(f"{component}-v")
                                  and SEMVER.fullmatch(tag[len(component) + 2:])), None)
                for component in components}
    unresolved_findings = [finding for finding in findings if finding.startswith("UNRESOLVED")]
    package_prefixes = tuple(f"UNRESOLVED {package}:" for package in subjects or set())
    tag_prefixes = tuple(f"UNRESOLVED {tag}:" for tag in tags)
    unknown_release_scope = any(not finding.startswith(package_prefixes + tag_prefixes)
                                for finding in unresolved_findings)
    rows = []
    for package in sorted(subjects or set()):
        component = by_package.get(package)
        tag = selected.get(component)
        prefixes = [f"{package}:", f"UNRESOLVED {package}:"]
        if tag:
            prefixes.extend((f"{tag}:", f"UNRESOLVED {tag}:"))
        related = [finding for finding in findings if finding.startswith(tuple(prefixes))]
        if package in EXCLUDED:
            status = "EXEMPT"
            related = ["current graduation criterion excludes admin-ui"]
        elif (unresolved or unknown_release_scope or component is None or tag is None
              or any(item.startswith("UNRESOLVED") for item in related)):
            status = "UNRESOLVED"
        elif related:
            status = "PENDING" if all("(pending)" in item for item in related) else "FINDINGS"
        else:
            status = "ASSETS_PRESENT"
        release = next((release for release in releases if isinstance(release, dict)
                        and release.get("tag_name") == tag), {}) if tag else {}
        rows.append({"package": package, "component": component, "tag": tag,
                     "version": tag[len(component) + 2:] if component and tag else None,
                     "releaseId": release.get("id"), "publishedAt": release.get("published_at"),
                     "status": status, "findings": related, "cryptography": "not_checked"})
    return {"schema": "openbank.release-evidence-inventory/v1",
            "observedAt": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "verifierRevision": revision,
            "verifierScriptSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            "assetContractSha256": hashlib.sha256(Path(release_assets.__file__).read_bytes()).hexdigest(),
            "latestDefinition": "highest stable SemVer; build metadata ties unresolved; publication time only for grace",
            "expectedSubjects": len(subjects) if subjects is not None else None,
            "status": "UNRESOLVED" if unresolved or unresolved_findings else ("FINDINGS" if findings else "ASSETS_PRESENT"),
            "subjects": rows, "findings": findings, "cryptography": "not_checked"}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument(
        "--local-only",
        action="store_true",
        help="discover the local subject denominator without fetching live release metadata",
    )
    parser.add_argument("--tags-output", type=Path)
    parser.add_argument("--report-output", type=Path)
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY", "JiRaska/open-bank-oss"))
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    subjects = None
    components: dict[str, str] = {}
    findings: list[str] = []
    tags: list[str] = []
    releases: list[dict] = []
    unresolved = False
    try:
        packages = json.loads((ROOT / "release-please-config.json").read_text())["packages"]
        if not isinstance(packages, dict):
            raise TypeError("release packages must be an object")
        subjects = expected_packages(ROOT, packages)
        components, findings = expected_components(ROOT, subjects)
        print(f"SUBJECTS={len(subjects)}  # expected packages including unresolved and exempt")
        for package in sorted(subjects & EXCLUDED):
            print(f"EXEMPT {package}: current graduation criterion excludes admin-ui")
        workflow = (ROOT / ".github/workflows/verify-release-evidence.yml").read_text()
        findings.extend(workflow_findings(workflow))
        if args.local_only:
            findings.extend(
                f"UNRESOLVED {package}: live release metadata not fetched in local-only mode"
                for package in sorted(subjects - EXCLUDED)
            )
            unresolved = True
        else:
            releases = fetch_releases(args.repository, os.environ.get("GH_TOKEN", ""))
            tags, release_findings = check_fleet(components, releases)
            findings.extend(release_findings)
    except (OSError, ValueError, TypeError, KeyError, urllib.error.URLError) as exc:
        if subjects is None:
            print("SUBJECTS=UNRESOLVED  # release inventory configuration unreadable")
        findings.append(f"UNRESOLVED release inventory API or configuration: {exc}")
        unresolved = True
    if args.tags_output:
        # Truncate stale output on failed reads too; a prior tag set is not this observation.
        args.tags_output.write_text("".join(f"{tag}\n" for tag in tags))
    if args.report_output:
        report = inventory_report(subjects, components, tags, releases, findings, unresolved)
        report["repository"] = args.repository
        args.report_output.write_text(json.dumps(report, indent=2) + "\n")
    for finding in findings:
        print(f"::error::{finding}")
    if subjects is not None:
        print(f"Expected {len(subjects)} packages; {len(components)} mapped components; {len(tags)} latest stable tags resolved")
    if unresolved:
        return 2
    if findings:
        return 1
    print("PASS: every non-exempt latest stable release has its full evidence asset set; cryptography not checked here")
    return 0


if __name__ == "__main__":
    sys.exit(main())
