#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""A Kotlin default value on a @ConfigProperty constructor parameter silently discards the config.

WHAT HAPPENS

A Kotlin constructor parameter with a default value generates a synthetic constructor. Arc
instantiates the bean through it, and every `@ConfigProperty` on that constructor is then never
consulted: the bean exists, injection reports success, and every field holds its Kotlin fallback.

Measured 2026-08-02 in the sandbox. The lending pod carried `LENDING_INTAKE_ENABLED=true` and the
endpoint answered:

    403 {"error":"customer self-service intake is disabled"}

Nothing about `CustomerIntakeConfig` had ever been configured — not the caller principal, not the
price, not the amount bounds. The whole ADR-0211 customer application path was dead from the day it
shipped, and its 403 was indistinguishable from someone having switched it off on purpose.

ONE DEFAULT IS ENOUGH, which is the part worth encoding. `CustomerIntakeConfig` had ten defaulted
parameters, so "all of them" was an available and wrong explanation. `OriginationConfigInjectionTest`
was written against a bean with exactly ONE (`lending.origination.auto-approve`) and failed the same
way, so the rule is per-parameter, not per-class.

WHY THIS IS WORTH A GATE RATHER THAN A FIXED-UP BEAN

The same shape guarded two security flags: `openbank.ml.require-signature` (fraud model) and
`mcp.obo.enabled`. Both default false, both unturnable-on. A flag that cannot be enabled is worse
than one that is disabled, because the operator who enables it stops looking.

None of this is visible to a unit test that constructs the bean by hand — which is how these are
normally tested, and which passed throughout — nor to any startup check, because there is no error.
The only signals are a live probe or this comparison.

SCOPE: constructor parameters. A `@ConfigProperty` on a FIELD (`@Inject lateinit var`) is a
different mechanism and is not affected.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]
BASELINE = pathlib.Path(__file__).with_name("configproperty-kotlin-defaults-baseline.txt")

# @ConfigProperty(...) or @param:ConfigProperty(...) on the following line a `val`/`var` parameter
# carrying `= <default>`. The parameter may be `private`. Multi-line annotation arguments are
# tolerated by allowing anything but a closing paren inside.
PATTERN = re.compile(
    r"@(?:param:)?ConfigProperty\s*\([^)]*\)\s*\n"
    r"\s*(?:private\s+|internal\s+)?(?:val|var)\s+(\w+)\s*:\s*[^,=\n]+?=\s*([^,\n]+)",
)


def scan(root: pathlib.Path) -> list[tuple[str, int, str, str]]:
    findings: list[tuple[str, int, str, str]] = []
    for path in sorted(root.glob("openbank-*/src/main/kotlin/**/*.kt")):
        try:
            text = path.read_text(errors="ignore")
        except OSError:
            continue
        if "ConfigProperty" not in text:
            continue
        for m in PATTERN.finditer(text):
            line = text[: m.start()].count("\n") + 1
            findings.append((str(path.relative_to(root)), line, m.group(1), m.group(2).strip().rstrip(",")))
    return findings


def self_test() -> int:
    """Feed it both the shape it must flag and the shapes it must not.

    A checker that has only seen a clean tree is unfalsified — and here the near-misses matter as
    much as the hit, because the annotation legitimately carries its OWN `defaultValue`, which must
    never be confused with a Kotlin default.
    """
    import tempfile

    cases = [
        (
            "defaulted parameter — MUST flag",
            """
            @ApplicationScoped
            class C(
                @param:ConfigProperty(name = "a.b", defaultValue = "false")
                val enabled: Boolean = false,
            )
            """,
            1,
        ),
        (
            "annotation defaultValue, no Kotlin default — must NOT flag",
            """
            @ApplicationScoped
            class C(
                @param:ConfigProperty(name = "a.b", defaultValue = "false")
                val enabled: Boolean,
            )
            """,
            0,
        ),
        (
            "private defaulted parameter — MUST flag",
            """
            class C @Inject constructor(
                @ConfigProperty(name = "a.b", defaultValue = "false")
                private val flag: Boolean = false,
            )
            """,
            1,
        ),
        (
            "field injection with a default — must NOT flag (different mechanism)",
            """
            class C {
                @ConfigProperty(name = "a.b")
                lateinit var v: String
                val other: Boolean = false
            }
            """,
            0,
        ),
        (
            "ordinary property with a default, no annotation — must NOT flag",
            """
            class C(val enabled: Boolean = false)
            """,
            0,
        ),
    ]

    failures = 0
    for label, body, expected in cases:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            d = root / "openbank-demo/src/main/kotlin/com/demo"
            d.mkdir(parents=True)
            (d / "C.kt").write_text(body)
            got = len(scan(root))
            ok = got == expected
            failures += 0 if ok else 1
            print(f"  [{'ok' if ok else 'FAIL'}] {label}: found={got} expected={expected}")

    print("self-test:", "PASS" if failures == 0 else f"{failures} FAILED")
    return 1 if failures else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    baseline = set()
    if BASELINE.exists():
        baseline = {
            line.strip()
            for line in BASELINE.read_text().splitlines()
            if line.strip() and not line.startswith("#")
        }

    all_found = scan(REPO)
    keys = {f"{path}::{name}" for path, _, name, _ in all_found}
    findings = [f for f in all_found if f"{f[0]}::{f[2]}" not in baseline]

    # A baseline entry that no longer exists is reported too. A stale declaration in either
    # direction is how a frozen list quietly becomes permanent.
    stale = sorted(baseline - keys)
    if stale:
        print("baseline entries that no longer match anything (delete them):\n")
        for s in stale:
            print(f"::error::{s} is baselined but was not found — the defect is gone, remove the line.")

    if findings:
        print("NEW @ConfigProperty constructor parameters carrying a Kotlin default value:\n")
        for path, line, name, default in findings:
            print(
                f"::error file={path},line={line}::'{name}' has the Kotlin default `= {default}`. "
                f"That generates a synthetic constructor, Arc builds the bean through it, and the "
                f"@ConfigProperty is never applied — the field silently keeps this value whatever "
                f"the environment says. Delete the ` = {default}` and rely on the annotation's "
                f"defaultValue instead."
            )
        return 1
    if stale:
        return 1
    print(f"@ConfigProperty Kotlin-default check: OK ({len(baseline)} baselined, 0 new)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
