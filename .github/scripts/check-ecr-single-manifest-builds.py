#!/usr/bin/env python3
"""Ratchet every known buildx producer against OCI-index pushes (#11573)."""
from __future__ import annotations

import argparse
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[2]
EXPECTED_CALLS = {
    ".github/workflows/auto-deploy.yml": 2,
    ".github/workflows/platform-images.yml": 2,
    ".github/workflows/runner-image.yml": 1,
    ".github/workflows/ghcr-publish.yml": 1,  # GHCR is outside the ECR admission policy.
    "openbank-infra/scripts/build-push-admin-ui.sh": 2,
    "openbank-infra/scripts/build-push-service.sh": 1,
    "openbank-infra/scripts/build-push-keycloak.sh": 1,
    "openbank-infra/scripts/build-push-pyroscope-agent.sh": 1,
}
EXEMPT = {".github/workflows/ghcr-publish.yml"}
FLAGS = ("--provenance=false", "--sbom=false")


def sources(root: pathlib.Path = ROOT) -> dict[str, str]:
    tracked = subprocess.run(
        ["git", "-C", str(root), "ls-files", "-z", "--", "*.sh", "*.yml", "*.yaml"],
        check=True, capture_output=True,
    ).stdout
    paths = [path.decode() for path in tracked.split(b"\0") if path]
    return {path: (root / path).read_text(encoding="utf-8") for path in paths}


def buildx_calls(source: str) -> list[tuple[int, str]]:
    lines = source.splitlines()
    calls = []
    for i, line in enumerate(lines):
        stripped = line.lstrip()
        if "docker buildx build" not in line or stripped.startswith(("#", "echo ")):
            continue
        command = line.strip()
        j = i
        while lines[j].rstrip().endswith("\\") and j + 1 < len(lines):
            j += 1
            command += " " + lines[j].strip()
        calls.append((i + 1, command))
    return calls


def findings(texts: dict[str, str]) -> tuple[list[str], int]:
    errors = []
    seen = set()
    subjects = 0
    for path, source in sorted(texts.items()):
        calls = buildx_calls(source)
        if not calls:
            continue
        seen.add(path)
        subjects += len(calls)
        expected = EXPECTED_CALLS.get(path)
        if expected is None or len(calls) != expected:
            errors.append(f"{path}: {len(calls)} buildx call(s), expected {expected}; review new producer")
        if path in EXEMPT:
            continue
        for line, command in calls:
            if path == "openbank-infra/scripts/build-push-admin-ui.sh":
                if '"${buildx_args[@]}"' not in command:
                    errors.append(f"{path}:{line}: buildx call no longer uses the guarded arguments")
            elif "--push" in command:
                for flag in FLAGS:
                    if flag not in command:
                        errors.append(f"{path}:{line}: pushed ECR image lacks {flag}")
    for path in EXPECTED_CALLS.keys() - seen:
        errors.append(f"{path}: known buildx producer disappeared; review the replacement")
    admin = texts.get("openbank-infra/scripts/build-push-admin-ui.sh", "")
    args = admin.split("buildx_args=(", 1)[-1].split("\n)", 1)[0]
    for flag in (*FLAGS, "--push"):
        if flag not in args:
            errors.append(f"build-push-admin-ui.sh: buildx_args lacks {flag}")
    helper = texts.get("openbank-infra/scripts/lib/cosign-attest.sh", "")
    if 'assert_ecr_single_image_manifest "$image" || return 1' not in helper:
        errors.append("cosign-attest.sh: runtime manifest check missing before signing")
    deploy = texts.get(".github/workflows/auto-deploy.yml", "")
    if 'assert_ecr_single_image_manifest "$img"' not in deploy:
        errors.append("auto-deploy.yml: runtime manifest check missing before signing")
    return errors, subjects


def self_test() -> int:
    base = sources()
    cases = [
        ("current producers", base, False),
        ("missing buildx flag", {**base, "openbank-infra/scripts/build-push-service.sh":
          base["openbank-infra/scripts/build-push-service.sh"].replace("--provenance=false", "", 1)}, True),
        ("new producer", {**base, "openbank-infra/scripts/new-producer.sh":
          "docker buildx build --push .\n"}, True),
        ("removed runtime check", {**base, "openbank-infra/scripts/lib/cosign-attest.sh":
          base["openbank-infra/scripts/lib/cosign-attest.sh"].replace(
              'assert_ecr_single_image_manifest "$image" || return 1', "", 1)}, True),
    ]
    for label, texts, want_errors in cases:
        errors, _ = findings(texts)
        if bool(errors) != want_errors:
            print(f"self-test FAIL {label}: {errors}")
            return 1
    print(f"self-test OK: {len(cases)} producer cases")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    errors, subjects = findings(sources())
    for error in errors:
        print(f"ERROR: {error}")
    print(f"SUBJECTS={subjects}")
    return bool(errors)


if __name__ == "__main__":
    raise SystemExit(main())
