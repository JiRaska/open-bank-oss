#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
"""Fail when gradle/verification-metadata.xml is missing entries a build would need.

Why this exists (#3218): the only thing that refreshes the file is
`dependabot-verification-metadata.yml`, and it is gated on
`github.actor == 'dependabot[bot]'`. A human PR that adds or bumps a dependency
therefore ships it unpinned. Today that is a warning
(`org.gradle.dependency.verification=lenient`); at the ADR-0144 graduation
(2026-09-30, #1915) it becomes a failing build on any cold cache.

This checks rather than writes: regeneration happens against a throwaway copy and
the committed file is restored, so nothing is pushed back to a contributor's
branch and the trust boundary that motivates the actor gate is untouched.

Two things this deliberately does NOT do:

  * It does not diff the file as text. `--write-verification-metadata` reindents
    the whole document (~18.5k lines), so `git diff --exit-code` would be red on
    every run regardless of content. The comparison is over the SET of
    (component, artifact) pairs.
  * It does not report removals. Regeneration only observes what the selected
    tasks resolve, so an entry absent from this run is not evidence it is unused
    — a narrower task list would "remove" half the file. Only additions mean a
    real gap.

Usage:
    check-verification-metadata-complete.py --modules openbank-a,openbank-b
    check-verification-metadata-complete.py --modules "" --enforce   # no-op, exits 0
"""

from __future__ import annotations

import argparse
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

METADATA = pathlib.Path("gradle/verification-metadata.xml")
COMPONENT_RE = re.compile(r'<component group="([^"]*)" name="([^"]*)" version="([^"]*)"')
ARTIFACT_RE = re.compile(r'<artifact name="([^"]*)"')

# Both graphs matter. `testClasses` alone resolves only the test classpath, which
# is exactly how the prod-side gap in #3017 stayed invisible; quarkusDependenciesBuild
# resolves the Quarkus application model (prod runtime classpath + platform BOMs)
# without the cost of a full quarkusBuild — see #3199.
TASKS = ("testClasses", "quarkusDependenciesBuild")


def artifact_set(path: pathlib.Path) -> set[str]:
    """Every (component, artifact) pair in the file, as flat 'g:n:v|artifact' keys."""
    found: set[str] = set()
    component = None
    for line in path.read_text(encoding="utf-8").splitlines():
        match = COMPONENT_RE.search(line)
        if match:
            component = "%s:%s:%s" % match.groups()
            continue
        match = ARTIFACT_RE.search(line)
        if match and component:
            found.add(f"{component}|{match.group(1)}")
    return found


# `--write-verification-metadata` OOMs at Gradle's default heap: measured, it dies
# with `Java heap space` even on a single module. This is not optional tuning.
GRADLE_HEAP = "-Dorg.gradle.jvmargs=-Xmx6g"


def regenerate(modules: list[str]) -> None:
    """Run the metadata writer for the given modules, in place.

    Raises RegenerationFailed if Gradle itself failed. That is deliberately NOT the
    same outcome as "a gap was found": a crashed build proves nothing either way,
    and reporting it as a gap would send the author chasing entries that are fine.
    """
    targets = [f":{module}:{task}" for module in modules for task in TASKS]
    result = subprocess.run(
        ["./gradlew", "--write-verification-metadata", "sha256",
         # Without this the check is vacuous on CI. A warm Gradle cache does not
         # re-resolve metadata artifacts, so nothing gets re-hashed and the run
         # reports "no gaps" even when an entry is demonstrably missing — measured:
         # delete a known entry, run without --refresh-dependencies, and it passes.
         # CI always has a warm cache (`actions/setup-java` with `cache: gradle`).
         "--refresh-dependencies",
         *targets, GRADLE_HEAP, "--no-daemon", "--console=plain", "-q"],
        check=False,
    )
    if result.returncode != 0:
        raise RegenerationFailed(result.returncode)


class RegenerationFailed(RuntimeError):
    def __init__(self, code: int) -> None:
        super().__init__(f"gradle exited {code}")
        self.code = code


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--modules", required=True,
                        help="comma-separated Gradle module dirs; empty means nothing to do")
    parser.add_argument("--enforce", action="store_true",
                        help="exit non-zero on a gap (default: warn only)")
    args = parser.parse_args()

    modules = [m.strip() for m in args.modules.split(",") if m.strip()]
    if not modules:
        print("check-verification-metadata: no Gradle modules changed — nothing to check")
        return 0

    if not METADATA.is_file():
        print(f"::error::{METADATA} not found")
        return 1

    print(f"check-verification-metadata: regenerating for {len(modules)} module(s): "
          f"{', '.join(modules)}")

    before = artifact_set(METADATA)
    with tempfile.NamedTemporaryFile(suffix=".xml", delete=False) as backup_handle:
        backup = pathlib.Path(backup_handle.name)
    shutil.copy2(METADATA, backup)
    failed = None
    try:
        regenerate(modules)
        after = artifact_set(METADATA)
    except RegenerationFailed as exc:
        failed = exc
        after = before
    finally:
        # Restore unconditionally: a failed Gradle run can still have rewritten the file.
        shutil.copy2(backup, METADATA)
        backup.unlink(missing_ok=True)

    if failed is not None:
        # Infrastructure failure, not a verdict about the metadata. Say so out loud
        # rather than letting a crash read as either a pass or a gap.
        print(f"::error title=verification-metadata check could not run::Gradle failed "
              f"({failed}); this says nothing about whether the metadata is complete. "
              f"Re-run, and if it persists treat it as a build problem, not a metadata gap.")
        return 1

    missing = sorted(after - before)
    if not missing:
        print(f"check-verification-metadata: OK — {len(before)} artifacts, no gaps "
              f"for the changed modules")
        return 0

    level = "error" if args.enforce else "warning"
    print(f"::{level} title=verification-metadata is missing {len(missing)} entr"
          f"{'y' if len(missing) == 1 else 'ies'}::A cold-cache build of the changed "
          f"modules resolves artifacts that gradle/verification-metadata.xml does not pin. "
          f"Regenerate locally and commit the additions: "
          f"./gradlew --write-verification-metadata sha256 "
          f"{' '.join(f':{m}:{t}' for m in modules for t in TASKS)} "
          f"-Dorg.gradle.jvmargs=-Xmx6g")
    for key in missing:
        component, artifact = key.split("|", 1)
        print(f"  missing: {artifact}  ({component})")

    return 1 if args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
