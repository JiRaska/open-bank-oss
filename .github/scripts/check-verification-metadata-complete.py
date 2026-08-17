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

    Exit codes: 0 = no gap; 1 = a real gap (with --enforce); 2 = the check could not run
    (Gradle failed), which is NOT a verdict about the metadata. Callers must treat 2 as
    "unknown" and must not report it as drift.
    check-verification-metadata-complete.py --selftest               # prove it can fail
"""

from __future__ import annotations

import argparse
import os
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

# `quarkusDependenciesBuild` only exists on modules applying the `openbank.quarkus-service`
# convention plugin. Three do not — openbank-libs-{domain,runtime,temporal} — and naming
# the task for them fails the WHOLE invocation with "Cannot locate tasks that match", so a
# PR touching one of those build files would take the check down with it. They still get
# `testClasses`, which is the only graph they have.
# Whether a module owns `quarkusDependenciesBuild` is decided by how it applies the
# Quarkus plugin, and there are TWO ways in this repo:
#   id("openbank.quarkus-service")   the convention plugin (55 services)
#   alias(libs.plugins.quarkus)      the plugin directly (openbank-analytics-sink)
# Naming the task for a module that has neither fails the WHOLE Gradle invocation
# with "Cannot locate tasks that match", so this must be exact in both directions.
#
# Comments are stripped first, and that is not defensive coding. A plain substring
# search misfires on prose: openbank-libs-testing says "This module isn't a Quarkus
# service (no openbank.quarkus-service ...)", and that sentence alone classified it AS
# one. openbank-libs carries a comment with the same effect. Both were caught by the
# nightly sweep on its first real run; the over-correction that followed — matching only
# the convention plugin — then misclassified analytics-sink the other way, which would
# have silently stopped checking its prod graph rather than failing loudly.
QUARKUS_PLUGIN_RE = re.compile(
    r'^\s*(?:id\("openbank\.quarkus-service"\)|alias\(libs\.plugins\.quarkus\))', re.M)


def tasks_for(module: str) -> tuple[str, ...]:
    build_file = pathlib.Path(module) / "build.gradle.kts"
    try:
        text = build_file.read_text(encoding="utf-8")
    except OSError:
        return ("testClasses",)
    code = "\n".join(l for l in text.split("\n") if not l.lstrip().startswith("//"))
    return TASKS if QUARKUS_PLUGIN_RE.search(code) else ("testClasses",)


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
#
# 6g was not enough either (#4907): `openbank-libs-runtime` — the largest shared dependency graph
# in the tree — still died with `Java heap space` under
# `--refresh-dependencies --write-verification-metadata sha256`, twice, including a clean re-run.
# That blocks a required context on ANY pull request that touches a libs module's build file.
#
# Before raising it, the mechanism was verified rather than assumed: `org.gradle.jvmargs` passed as
# `-D` on the command line is a classic place for a setting to be silently dropped. An init script
# printing the build JVM's Runtime.maxMemory() reports 6144 MB with this flag and 3072 MB without
# it (the gradle.properties default), so the value does reach the build JVM. The flag works; the
# number was too small.
#
# 8g is a STEP, not a measured threshold — the local reproduction needed to find the real figure did
# not complete. `ubuntu-latest` has 16 GB, so 8g leaves headroom for the launcher and the Kotlin
# daemons. If this recurs, raise the number; do not re-litigate whether the flag applies.
GRADLE_HEAP = "-Dorg.gradle.jvmargs=-Xmx8g"

# "Failed to notify build model lifecycle listener > Java heap space" (2026-08-16, run 31903136753,
# AFTER the 8g raise above had already landed on main, regenerating openbank-libs-runtime ALONE --
# not #4793's batching shape, which #5031 already fixed and this branch already carries).
#
# `-Dorg.gradle.jvmargs` on the command line configures the DAEMON/worker JVM Gradle spawns; it does
# NOT touch the `./gradlew` LAUNCHER process's own heap, which Gradle's wrapper script reads from
# `GRADLE_OPTS`/`JAVA_OPTS` instead. "Build model lifecycle listener" notification happens in that
# launcher process during configuration, before the worker JVM's heap is even relevant -- so raising
# GRADLE_HEAP was raising the wrong pool for this failure. The same distinction is already made
# fleet-wide: `openbank-product-catalog/Dockerfile.native`'s builder stage sets a bare `GRADLE_OPTS`
# for exactly this reason, on a comparably large full-repo `settings.gradle.kts` (30+ modules).
#
# Passed as `env`, not appended to the command line -- GRADLE_OPTS is read from the environment, a
# `-D` argument on the invoked `./gradlew` script would just be an inert positional argument to it.
GRADLE_LAUNCHER_OPTS = "-Xmx1g"


def regenerate(modules: list[str]) -> None:
    """Run the metadata writer for each module IN ITS OWN Gradle invocation, in place.

    Raises RegenerationFailed if Gradle itself failed on any module. That is
    deliberately NOT the same outcome as "a gap was found": a crashed build proves
    nothing either way, and reporting it as a gap would send the author chasing
    entries that are fine.

    ONE INVOCATION PER MODULE, not one invocation for the whole list (#4793). The
    periodic sweep passes several modules per shard (`--shard I/N` strides a sorted
    list, so a shard can land `openbank-libs-runtime` next to two or three others by
    coincidence of alphabetical distance, not by any weighting). Bundling them into a
    single `./gradlew ... target1 target2 target3` command line means ONE JVM holds
    every module's resolved dependency graph in memory AT THE SAME TIME — so even
    after #4907 raised the heap enough for libs-runtime ALONE, shard 4 still died: it
    was libs-runtime plus three more modules in one process. Invoking Gradle once per
    module releases the JVM (and its heap) between modules, so the peak footprint is
    bounded by the single largest module in the shard, not their sum — the same bound
    the PR-gate case (always exactly one module) already runs under successfully.
    """
    failures: list[tuple[str, int]] = []
    for module in modules:
        targets = [f":{module}:{task}" for task in tasks_for(module)]
        env = dict(os.environ)
        env["GRADLE_OPTS"] = GRADLE_LAUNCHER_OPTS
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
            env=env,
        )
        if result.returncode != 0:
            failures.append((module, result.returncode))
    if failures:
        raise RegenerationFailed(failures)


class RegenerationFailed(RuntimeError):
    def __init__(self, failures: list[tuple[str, int]]) -> None:
        detail = ", ".join(f"{module} (exit {code})" for module, code in failures)
        super().__init__(f"gradle failed for: {detail}")
        self.failures = failures
        # Kept for callers that only cared about "did it fail" before this change —
        # the first module's code, which is what a single-module (PR-gate) call always was.
        self.code = failures[0][1]


def selftest() -> int:
    """Prove the gate can fail, without Gradle — fixtures for every unit the verdict rests on.

    A gate that has only ever passed is unfalsified, and this one's verdict is
    `missing = after - before` over `artifact_set` — so the fixtures feed that
    algebra both ways, plus the two parser traps that were each paid for once
    (the comment-only plugin mention, and the alias() plugin application).
    """
    failures = 0

    def check(name: str, cond: bool) -> None:
        nonlocal failures
        print(f"  {'ok' if cond else 'FAIL'}: {name}")
        if not cond:
            failures += 1

    with tempfile.TemporaryDirectory() as d:
        tmp = pathlib.Path(d)

        # (1) artifact_set parses (component, artifact) pairs — and only pairs: an
        # artifact before any component, and a component with no artifacts, drop out.
        meta = tmp / "metadata.xml"
        meta.write_text(
            '<artifact name="orphan-1.0.jar"/>\n'
            '<component group="com.acme" name="core" version="1.2.3">\n'
            '  <artifact name="core-1.2.3.jar"/>\n'
            '  <artifact name="core-1.2.3.pom"/>\n'
            '</component>\n'
            '<component group="com.acme" name="empty" version="9">\n'
            '</component>\n'
        )
        parsed = artifact_set(meta)
        check("artifact_set parses pairs and drops unpaired lines",
              parsed == {"com.acme:core:1.2.3|core-1.2.3.jar",
                         "com.acme:core:1.2.3|core-1.2.3.pom"})

        # (2) The verdict algebra: a regenerated file carrying one NEW pair must
        # surface exactly that pair as missing — and nothing when the sets agree.
        regen = tmp / "regen.xml"
        regen.write_text(meta.read_text().replace(
            '</component>\n<component group="com.acme" name="empty" version="9">',
            '  <artifact name="core-1.2.3-sources.jar"/>\n'
            '</component>\n<component group="com.acme" name="empty" version="9">'))
        after = artifact_set(regen)
        check("after - before names exactly the added pair",
              sorted(after - parsed) == ["com.acme:core:1.2.3|core-1.2.3-sources.jar"])
        check("identical sets report no gap", not (parsed - parsed))
        # A pair present in before but absent in after is NOT a finding (removals are
        # deliberately not reported — a narrower task list would "remove" half the file).
        check("removals are not reported as gaps", not (parsed - after))

        # tasks_for resolves `module/build.gradle.kts` relative to the cwd, so the
        # fixture modules are exercised from inside the fixture root.
        previous_cwd = os.getcwd()
        os.chdir(tmp)
        try:
            # (3) tasks_for: both plugin-application shapes get the full task pair.
            conv = tmp / "openbank-conv"; conv.mkdir()
            (conv / "build.gradle.kts").write_text('plugins {\n    id("openbank.quarkus-service")\n}\n')
            check("convention plugin -> both graphs",
                  tasks_for("openbank-conv") == TASKS)
            aliased = tmp / "openbank-alias"; aliased.mkdir()
            (aliased / "build.gradle.kts").write_text('plugins {\n    alias(libs.plugins.quarkus)\n}\n')
            check("alias(libs.plugins.quarkus) -> both graphs",
                  tasks_for("openbank-alias") == TASKS)

            # (4) The comment trap: a build file that only TALKS about the plugin must
            # not get quarkusDependenciesBuild — this exact prose once classified a
            # non-Quarkus module AS Quarkus (see QUARKUS_PLUGIN_RE above).
            talker = tmp / "openbank-talker"; talker.mkdir()
            (talker / "build.gradle.kts").write_text(
                '// This module isn\'t a Quarkus service (no openbank.quarkus-service here)\n'
                'plugins {\n    kotlin("jvm") version "2.3.20"\n}\n')
            check("comment-only mention -> testClasses only",
                  tasks_for("openbank-talker") == ("testClasses",))

            # (5) Neither plugin -> testClasses only; missing build file -> same, no crash.
            plain = tmp / "openbank-plain"; plain.mkdir()
            (plain / "build.gradle.kts").write_text('plugins {\n    kotlin("jvm") version "2.3.20"\n}\n')
            check("no Quarkus plugin -> testClasses only",
                  tasks_for("openbank-plain") == ("testClasses",))
            check("missing build file -> testClasses only",
                  tasks_for("openbank-does-not-exist") == ("testClasses",))
        finally:
            os.chdir(previous_cwd)

    if failures:
        print(f"selftest FAILED ({failures} case(s))")
        return 1
    print("selftest OK — parser, verdict algebra, and both plugin shapes proven both ways.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--modules", required=False, default=None,
                        help="comma-separated Gradle module dirs; empty means nothing to do")
    parser.add_argument("--enforce", action="store_true",
                        help="exit non-zero on a gap (default: warn only)")
    parser.add_argument("--shard", type=str, default=None, metavar="I/N",
                        help="with --modules all: take shard I of N (1-based), for the "
                             "periodic guard — a single fleet run needs ~12g of heap, "
                             "more than a 16 GB runner can safely give it")
    parser.add_argument("--selftest", action="store_true",
                        help="prove the gate can fail, against fixtures it must and must "
                             "not flag (no Gradle required)")
    args = parser.parse_args()

    if args.selftest:
        return selftest()

    if args.modules is None:
        parser.error("--modules is required unless --selftest is given")

    if args.modules.strip() == "all":
        every = sorted(d.name for d in pathlib.Path(".").iterdir()
                       if d.is_dir() and d.name.startswith("openbank-")
                       and (d / "build.gradle.kts").is_file())
        if args.shard:
            index, total = (int(x) for x in args.shard.split("/"))
            every = every[index - 1::total]
            print(f"check-verification-metadata: shard {index}/{total}")
        modules = every
    else:
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
        # Exit 2, NOT 1. The sentence above was already true and already printed, and the
        # caller still could not act on it: both outcomes returned 1, so the workflow's
        # `if: failure()` fired the same "unpinned artifacts on main" issue either way.
        # That is how #4162 came to assert drift when all three failing shards had died of
        # `Java heap space`. A checker that knows the difference must ENCODE the difference
        # in the one thing its caller reads.
        return 2

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
          f"{' '.join(f':{m}:{t}' for m in modules for t in tasks_for(m))} "
          f"-Dorg.gradle.jvmargs=-Xmx6g")
    for key in missing:
        component, artifact = key.split("|", 1)
        print(f"  missing: {artifact}  ({component})")

    return 1 if args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
