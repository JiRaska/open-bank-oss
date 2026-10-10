#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Drop shared-library TEST-ONLY paths from services-ci's code-global test.

services-ci.yml rebuilds the whole fleet (~50 modules, ~150 jobs) when any path under a
shared library (openbank-libs*, gradle/, build-logic, root build files) changes, because
such a change can alter every module's compile/test inputs. A library's `src/test/` cannot:
test sources are not published to consumers. Treating them as code-global made a one-row
edit to libs-runtime's OutboxDeadLetterAlertNamingTest cost a full-fleet run on every update.

  (default)   stdin: changed paths; stdout: the same paths MINUS those confined to a
              library's test sources. services-ci greps the code-global regex over this
              output; the unfiltered list still drives per-module attribution, so the
              library module itself is built (`^<module>/` match).
  --census    list the libraries whose test paths this exempts (SUBJECTS=<n>); 0 libs = the
              probe broke (exit 1). Used as the gate's run.
  --self-test known-positive + known-negative fixtures, including a consumed testFixtures.

`src/testFixtures/` is dropped too, but ONLY for a library no build script consumes via
`testFixtures(project(":<lib>"))` — a consumed fixture is another module's test input and
stays code-global. Anything unrecognised stays in the output (over-build is the safe side).
"""
from __future__ import annotations

import re
import sys
import tempfile
from pathlib import Path

LIB_TEST = re.compile(r"^(openbank-libs[^/]*)/src/(test|testFixtures)/")
CONSUMER = re.compile(r'testFixtures\(\s*project\(\s*"(:[^"]+)"')
# Mirrors the code-global regex in services-ci.yml; used by --self-test only.
CODE_GLOBAL = re.compile(
    r"^(openbank-libs[^/]*/"
    r"|gradle/|settings\.gradle\.kts"
    r"|build\.gradle\.kts|gradle\.properties|build-logic/)"
)


def consumed_fixtures(root: Path) -> set[str]:
    out: set[str] = set()
    for f in root.glob("*/build.gradle.kts"):
        for m in CONSUMER.finditer(f.read_text(errors="replace")):
            out.add(m.group(1).lstrip(":"))
    for f in [root / "build.gradle.kts", *root.glob("build-logic/**/*.gradle.kts")]:
        if f.is_file():
            for m in CONSUMER.finditer(f.read_text(errors="replace")):
                out.add(m.group(1).lstrip(":"))
    return out


def keep(paths: list[str], consumed: set[str]) -> list[str]:
    kept = []
    for p in paths:
        m = LIB_TEST.match(p)
        if m and (m.group(2) == "test" or m.group(1) not in consumed):
            continue
        kept.append(p)
    return kept


def decide(paths: list[str], modules: list[str], consumed: set[str]) -> list[str]:
    """PR-path decision without the derived edges: full fleet, or the attributed modules."""
    if any(CODE_GLOBAL.match(p) for p in keep(paths, consumed)):
        return sorted(modules)
    return sorted({m for m in modules for p in paths if p.startswith(m + "/")})


def self_test() -> int:
    mods = ["openbank-libs", "openbank-libs-runtime", "openbank-libs-testing",
            "openbank-card-processing-service", "openbank-ledger-service", "openbank-libs-future"]
    with tempfile.TemporaryDirectory() as d:
        root = Path(d)
        (root / "openbank-ledger-service").mkdir()
        (root / "openbank-ledger-service/build.gradle.kts").write_text(
            'dependencies { testImplementation(testFixtures(project(":openbank-libs-testing"))); testImplementation(testFixtures(project(":openbank-libs-future"))) }\n')
        consumed = consumed_fixtures(root)
    cases = [
        (["openbank-libs-future/src/main/x/Foo.kt"], mods),
        (["openbank-libs-future/build.gradle.kts"], mods),
        (["openbank-libs-future/src/testFixtures/x/Kit.kt"], mods),
        (["openbank-libs-future/src/test/x/FooTest.kt"], ["openbank-libs-future"]),
        (["openbank-libs-runtime/src/test/x/FooTest.kt"], ["openbank-libs-runtime"]),
        (["openbank-libs-runtime/src/main/x/Foo.kt"], mods),
        (["build.gradle.kts"], mods),
        (["openbank-libs-runtime/src/test/x/FooTest.kt",
          "openbank-card-processing-service/src/main/x/A.kt"],
         ["openbank-card-processing-service", "openbank-libs-runtime"]),
        (["openbank-libs-testing/src/testFixtures/x/Kit.kt"], mods),        # consumed
        (["openbank-libs-runtime/src/testFixtures/x/Kit.kt"], ["openbank-libs-runtime"]),
        (["openbank-libs-runtime/build.gradle.kts"], mods),
        (["openbank-libs-runtime/src/test/x/T.kt", "gradle/libs.versions.toml"], mods),
    ]
    bad = 0
    for paths, want in cases:
        got = decide(paths, mods, consumed)
        ok = got == sorted(want)
        bad += not ok
        print(f"{'ok  ' if ok else 'FAIL'} {paths} -> {got}")
    if consumed != {"openbank-libs-testing", "openbank-libs-future"}:
        print(f"FAIL consumer detection: {consumed}")
        bad += 1
    print("self-test:", "FAIL" if bad else "pass")
    return 1 if bad else 0


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        return self_test()
    root = Path(__file__).resolve().parents[2]
    if "--census" in argv:
        libs = sorted(d.name for d in root.glob("openbank-libs*") if (d / "src/test").is_dir())
        consumed = consumed_fixtures(root)
        print(f"SUBJECTS={len(libs)}")
        for lib in libs:
            fx = "code-global (consumed)" if lib in consumed else "per-module"
            print(f"  {lib}: src/test per-module; testFixtures {fx}")
        return 0 if libs else 1
    paths = [ln.strip() for ln in sys.stdin if ln.strip()]
    sys.stdout.write("".join(p + "\n" for p in keep(paths, consumed_fixtures(root))))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
