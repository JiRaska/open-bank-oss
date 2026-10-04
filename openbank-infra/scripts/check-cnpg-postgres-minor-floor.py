#!/usr/bin/env python3
"""Reject newly changed GitOps CNPG image pins below the fleet's minor baseline.

The check is diff-scoped so the existing pricing 18.1 pin (#11286, PR #11607)
does not redden unrelated PRs while its reviewed update is pending. Every changed
GitOps YAML document is inspected, including nested Helm values and DR templates.
The floors are deliberate policy values, not derived from today's manifests: a
new old pin must fail even if an existing manifest has drifted.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
GITOPS = "openbank-infra/gitops/"
FLOORS = {16: 15, 18: 6}
IMAGE = re.compile(r"(?:^|/)cloudnative-pg/postgresql:(\d+)\.(\d+)$")


def changed_files(base: str) -> list[str]:
    result = subprocess.run(
        ["git", "diff", "--name-only", f"{base}...HEAD", "--", GITOPS],
        cwd=ROOT, capture_output=True, text=True, check=False,
    )
    if result.returncode and "no merge base" in result.stderr.lower():
        result = subprocess.run(
            ["git", "diff", "--name-only", base, "HEAD", "--", GITOPS],
            cwd=ROOT, capture_output=True, text=True, check=False,
        )
    if result.returncode:
        raise ValueError(f"cannot diff GitOps manifests against {base}: {result.stderr.strip()}")
    return result.stdout.splitlines()


def image_names(value: object):
    if isinstance(value, dict):
        for key, child in value.items():
            if key == "imageName":
                yield child
            else:
                yield from image_names(child)
    elif isinstance(value, list):
        for child in value:
            yield from image_names(child)


def findings(path: str, source: str) -> list[str]:
    if "cloudnative-pg/postgresql" not in source:
        return []
    try:
        documents = list(yaml.safe_load_all(source))
    except yaml.YAMLError as exc:
        return [f"{path}: cannot parse CNPG image manifest: {exc}"]
    errors = []
    for document in documents:
        for image in image_names(document):
            if not isinstance(image, str) or "cloudnative-pg/postgresql" not in image:
                continue
            match = IMAGE.search(image)
            if not match:
                errors.append(f"{path}: CNPG PostgreSQL image must use a bare major.minor tag")
                continue
            major, minor = map(int, match.groups())
            floor = FLOORS.get(major)
            if floor is None:
                errors.append(f"{path}: PostgreSQL major {major} has no reviewed minor floor")
            elif minor < floor:
                errors.append(f"{path}: PostgreSQL {major}.{minor} is below fleet floor {major}.{floor}")
    return errors


def fleet_pin_count() -> int:
    """Count the real GitOps image corpus even when this PR changes no CNPG file."""
    count = 0
    for path in (ROOT / GITOPS).rglob("*"):
        if path.suffix not in {".yaml", ".yml", ".tmpl"}:
            continue
        source = path.read_text(encoding="utf-8")
        if "cloudnative-pg/postgresql" not in source:
            continue
        try:
            documents = yaml.safe_load_all(source)
            for document in documents:
                count += sum(isinstance(image, str) and "cloudnative-pg/postgresql" in image
                             for image in image_names(document))
        except yaml.YAMLError as exc:
            raise ValueError(f"cannot parse GitOps CNPG image manifest {path.relative_to(ROOT)}") from exc
    return count


def self_test() -> int:
    cases = [
        ("old-18", "spec:\n  imageName: ghcr.io/cloudnative-pg/postgresql:18.1\n", True),
        ("current-18", "spec:\n  imageName: ghcr.io/cloudnative-pg/postgresql:18.6\n", False),
        ("old-16", "spec:\n  imageName: ghcr.io/cloudnative-pg/postgresql:16.14\n", True),
        ("current-16", "spec:\n  imageName: mirror/ghcr/cloudnative-pg/postgresql:16.15\n", False),
        ("nested-chart", "values:\n  postgres:\n    imageName: ghcr.io/cloudnative-pg/postgresql:18.1\n", True),
        ("unrelated", "spec:\n  imageName: example.invalid/other:18.1\n", False),
        ("unknown-major", "spec:\n  imageName: ghcr.io/cloudnative-pg/postgresql:19.1\n", True),
        ("invalid-tag", "spec:\n  imageName: ghcr.io/cloudnative-pg/postgresql:18.6.1\n", True),
    ]
    failures = [name for name, source, should_fail in cases
                if bool(findings(name, source)) != should_fail]
    if failures:
        print(f"self-test failed: {', '.join(failures)}", file=sys.stderr)
        return 1
    print(f"self-test ok: {len(cases)} CNPG image floor controls")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", help="PR base commit or ref")
    parser.add_argument("--changed-files", type=Path, help="newline-separated test or CI file list")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if not args.base and not args.changed_files:
        parser.error("--base or --changed-files is required")
    try:
        paths = (args.changed_files.read_text().splitlines() if args.changed_files
                 else changed_files(args.base))
        subjects = fleet_pin_count()
    except (OSError, ValueError) as exc:
        print(f"::error::{exc}", file=sys.stderr)
        return 1
    print(f"SUBJECTS={subjects}")
    checked = 0
    errors = []
    for rel in paths:
        if not rel.startswith(GITOPS) or not rel.endswith((".yaml", ".yml", ".tmpl")):
            continue
        path = ROOT / rel
        if not path.is_file():
            continue  # a deleted manifest cannot introduce an old pin
        checked += 1
        errors.extend(findings(rel, path.read_text(encoding="utf-8")))
    for error in errors:
        print(f"::error::{error}")
    print(f"CNPG PostgreSQL minor floor: {checked} changed GitOps manifest(s), {len(errors)} finding(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
