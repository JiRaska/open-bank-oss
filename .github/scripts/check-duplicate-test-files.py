#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""duplicate-test-files (enforced ratchet, #13169).

A test file that exists as the SAME source in more than two modules is a copy that should live once
in `openbank-libs-testing` (an abstract conformance kit or a shared fixture) with a thin subclass per
service. Copies drift: the per-service `HibernateSequenceGuardTest` had three byte variants before
#13196 replaced them, and 25 `M2mOidcClientIdentityWiringTest` copies were one rename away from 25
independent edits.

"Same source" is the file's bytes with the `package` line removed: a copy of a helper necessarily
differs by package, so comparing raw bytes alone reports nothing at all (measured: 0 groups) while
the package-normalised comparison finds the real ones. Files under 400 bytes are skipped (a one-line
marker class is not worth a kit). Only `src/test` Kotlin and Java files count, one module = one
top-level `openbank-*` directory.

Ratchet: a group with MORE than two modules fails unless its basename is in BASELINE, and a baselined
basename may not spread to more modules than recorded. Strictly shrink-only: an entry whose real count is lower than recorded
(or zero) FAILS with "lower the baseline", so the allowance cannot silently grow back. A PR that
removes copies therefore edits BASELINE in the same change.
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import os
import pathlib
import re
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gatelib

MAX_MODULES = 2
MIN_BYTES = 400
PACKAGE_LINE = re.compile(rb"(?m)^package .*$")

# basename -> the most modules that may carry an identical copy. Debt recorded 2026-10-23 (#13169).
BASELINE: dict[str, int] = {
    "MockAuthzProducer.kt": 3,
    # Removed by the TraceContract consolidation (libs-testing `trace` package); the PR that lands
    # it deletes these two lines, because a shrunk entry fails.
    "TraceContractRecorder.kt": 20,
    "TraceContractProfile.kt": 11,
}


def groups(root: pathlib.Path) -> dict[str, list[tuple[str, str]]]:
    """sha256 of the package-normalised bytes -> [(module, relative path)]."""
    found: dict[str, list[tuple[str, str]]] = collections.defaultdict(list)
    for module in sorted(p for p in root.iterdir() if p.is_dir() and p.name.startswith("openbank-")):
        tests = module / "src" / "test"
        if not tests.is_dir():
            continue
        for dirpath, dirnames, filenames in os.walk(tests):
            dirnames.sort()  # every directory under src/test counts: `build` can be a package name
            for name in sorted(filenames):
                if not name.endswith((".kt", ".java")):
                    continue
                path = pathlib.Path(dirpath) / name
                data = PACKAGE_LINE.sub(b"", path.read_bytes())
                if len(data.strip()) < MIN_BYTES:
                    continue
                found[hashlib.sha256(data).hexdigest()].append(
                    (module.name, path.relative_to(root).as_posix())
                )
    return found


def check(root: pathlib.Path, baseline: dict[str, int] = BASELINE) -> tuple[list[str], list[str], int]:
    findings: list[str] = []
    notes: list[str] = []
    seen_basenames: set[str] = set()
    subjects = 0
    for members in groups(root).values():
        subjects += 1
        modules = sorted({m for m, _ in members})
        base = pathlib.PurePosixPath(members[0][1]).name
        if len(modules) <= MAX_MODULES:
            continue
        seen_basenames.add(base)
        allowed = baseline.get(base)
        if allowed is None:
            findings.append(
                f"{base}: identical in {len(modules)} modules ({', '.join(modules[:4])}"
                f"{', ...' if len(modules) > 4 else ''}) — move it to openbank-libs-testing "
                f"and keep only the per-service parameters in each module"
            )
        elif len(modules) > allowed:
            findings.append(
                f"{base}: now identical in {len(modules)} modules, baseline allows {allowed} "
                f"(#13169) — the copy is spreading"
            )
        elif len(modules) < allowed:
            findings.append(
                f"{base}: down to {len(modules)} modules, baseline still allows {allowed} — lower the baseline "
                f"to {len(modules)} so the debt cannot grow back"
            )
    for base in sorted(set(baseline) - seen_basenames):
        findings.append(f"{base}: no longer duplicated — lower the baseline: delete its BASELINE entry")
    return findings, notes, subjects


def write(root: pathlib.Path, module: str, rel: str, text: str) -> None:
    path = root / module / "src" / "test" / "kotlin" / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)


BODY = "class Helper {\n" + "    fun work() = 1 // padding to clear the size floor\n" * 12 + "}\n"


def self_test() -> int:
    ok = True

    def run(modules: int, *, vary: bool = False, baseline: dict[str, int] | None = None):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            for i in range(modules):
                text = f"package svc{i}\n\n" + BODY
                if vary and i == 0:
                    text += "// one real difference\n"
                write(root, f"openbank-s{i}", f"svc{i}/Helper.kt", text)
            return check(root, baseline or {})

    cases = [
        ("three identical copies differing only by package are flagged (known-positive)",
         run(3), True),
        ("two copies are within the limit", run(2), False),
        ("a single real difference leaves only two identical copies", run(3, vary=True), False),
        ("a baselined group at its recorded size passes", run(3, baseline={"Helper.kt": 3}), False),
    ]
    for name, (findings, _, _), expect in cases:
        if bool(findings) != expect:
            print(f"SELF-TEST FAIL: {name}: findings={findings}")
            ok = False
    findings, _, _ = run(4, baseline={"Helper.kt": 3})
    if len(findings) != 1 or "spreading" not in findings[0]:
        print(f"SELF-TEST FAIL: a baselined group that grew must fail: {findings}")
        ok = False
    findings, _, _ = run(3, baseline={"Helper.kt": 5})
    if len(findings) != 1 or "lower the baseline" not in findings[0]:
        print(f"SELF-TEST FAIL: a shrunk baseline entry must fail: {findings}")
        ok = False
    findings, _, _ = run(2, baseline={"Helper.kt": 3})
    if len(findings) != 1 or "lower the baseline" not in findings[0]:
        print(f"SELF-TEST FAIL: a vanished group's baseline entry must fail: {findings}")
        ok = False
    with tempfile.TemporaryDirectory() as d:  # a package literally named `build` is still a test file
        root = pathlib.Path(d)
        for i in range(3):
            write(root, f"openbank-s{i}", f"build/svc{i}/Helper.kt", f"package build.svc{i}\n\n" + BODY)
        if not check(root, {})[0]:
            print("SELF-TEST FAIL: a `build` package directory under src/test must be scanned")
            ok = False
    with tempfile.TemporaryDirectory() as d:  # the size floor
        root = pathlib.Path(d)
        for i in range(3):
            write(root, f"openbank-s{i}", f"svc{i}/Tiny.kt", f"package svc{i}\nclass Tiny\n")
        if check(root, {})[0]:
            print("SELF-TEST FAIL: files under the size floor must not count")
            ok = False
    print("self-test: " + ("pass" if ok else "FAIL"))
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    findings, notes, subjects = check(pathlib.Path(args.root))
    gatelib.subjects(subjects, "distinct test-file contents compared across modules")
    for f in findings:
        print(f"  {f}")
    if findings:
        print(f"{len(findings)} test file(s) duplicated across more than {MAX_MODULES} modules")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
