#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
"""libs-core-purity — ADR-0317: business packages leave openbank-libs-domain and stay out.

ADR-0317 splits bounded-context packages out of openbank-libs-domain into per-context
`openbank-libs-<context>` modules, so a change to one context rebuilds its 1-11 declarers
instead of all 57 libs-domain consumers. Three regressions would silently undo that, and this
checker reports each:

  (a) a file under openbank-libs-domain/src/main declares (or imports) a package that has MOVED OUT — i.e.
      a package owned by a context module that exists in this checkout. Packages are only
      checked once their module exists, so a phase that has not landed yet is not a finding;
      the phase that creates the module arms the check for its packages.
  (b) openbank-libs-domain or openbank-libs-runtime declares `api`/`implementation` on a
      context module — core re-exporting a context brings the 57-module fan-out straight back.
  (c) an `openbank-libs-*` module with `src/main` is absent from LIBS_MODULES in
      libs-change-dependents.sh. That list decides which services rebuild on a libs change; a
      module missing from it is the #2983 silent-no-rebuild defect.

ADVISORY in phase 1 (exit 0, ::warning), enforced from phase 4 (`--enforce`, exit 1).

    check-libs-core-purity.py [--root DIR] [--enforce]
    check-libs-core-purity.py --self-test
"""
from __future__ import annotations

import argparse
import re
import shutil
import sys
import tempfile
from pathlib import Path

CORE = "openbank-libs-domain"
CORE_BUILD_FILES = ("openbank-libs-domain/build.gradle.kts", "openbank-libs-runtime/build.gradle.kts")

# Context module -> the package prefixes it owns (ADR-0317 "Target modules" table). Adding a
# phase = adding its row; the row does nothing until the module directory exists.
CONTEXT_PACKAGES: dict[str, tuple[str, ...]] = {
    "openbank-libs-lending": ("com.openbank.libs.lending", "com.openbank.libs.decision"),
    "openbank-libs-iso20022": ("com.openbank.libs.iso20022", "com.openbank.libs.domain.payment"),
}

# `openbank-libs-*` modules that are deliberately NOT in LIBS_MODULES, each with its reason.
NOT_RUNTIME_MODULES = {
    "openbank-libs": "aggregator; its src/main is in GLOBAL_RE, which rebuilds everything",
    "openbank-libs-testing": "testImplementation only; a change there rebuilds no image",
    "openbank-libs-detekt-rules": "detekt plugin; affects lint, not any service's runtime",
}

PACKAGE_RE = re.compile(r"^\s*package\s+([A-Za-z0-9_.]+)", re.M)
IMPORT_RE = re.compile(r"^\s*import\s+([A-Za-z0-9_.]+)", re.M)
LIBS_MODULES_RE = re.compile(r"^LIBS_MODULES=\(([^)]*)\)", re.M)


def find_violations(root: Path) -> tuple[list[str], int]:
    """Returns (findings, subjects inspected)."""
    findings: list[str] = []
    subjects = 0
    present = {m: pkgs for m, pkgs in CONTEXT_PACKAGES.items() if (root / m).is_dir()}

    # (a) moved-out package still (or again) declared in core
    moved = tuple(p for pkgs in present.values() for p in pkgs)
    for f in sorted((root / CORE / "src/main").rglob("*.kt")) if (root / CORE / "src/main").is_dir() else []:
        subjects += 1
        text = f.read_text(encoding="utf-8", errors="replace")
        for imp in IMPORT_RE.findall(text):
            if any(imp == p or imp.startswith(p + ".") for p in moved):
                findings.append(f"{f.relative_to(root)}: imports {imp} — core must not reach into a context module (ADR-0317)")
        m = PACKAGE_RE.search(text)
        if not m:
            continue
        pkg = m.group(1)
        for p in moved:
            if pkg == p or pkg.startswith(p + "."):
                owner = next(k for k, v in present.items() if p in v)
                findings.append(f"{f.relative_to(root)}: package {pkg} belongs in {owner} (ADR-0317), not {CORE}")
                break

    # (b) core depends on a context module
    for rel in CORE_BUILD_FILES:
        bf = root / rel
        if not bf.is_file():
            continue
        subjects += 1
        for mod in CONTEXT_PACKAGES:
            if re.search(rf'^\s*(api|implementation)\(project\(":{re.escape(mod)}"\)\)', bf.read_text(), re.M):
                findings.append(f"{rel}: declares {mod} — core must not depend on a context module (ADR-0317)")

    # (c) every runtime libs module is in LIBS_MODULES
    script = root / ".github/scripts/libs-change-dependents.sh"
    listed: set[str] = set()
    if script.is_file():
        m = LIBS_MODULES_RE.search(script.read_text())
        if m:
            listed = set(m.group(1).split())
    for d in sorted(root.glob("openbank-libs*/src/main")):
        mod = d.parent.parent.name
        subjects += 1
        if mod in NOT_RUNTIME_MODULES or mod in listed:
            continue
        findings.append(f".github/scripts/libs-change-dependents.sh: LIBS_MODULES lacks {mod} — its consumers would not rebuild on a change (#2983)")
    return findings, subjects


def run(root: Path, enforce: bool) -> int:
    findings, subjects = find_violations(root)
    if subjects == 0:
        print(f"::error::libs-core-purity inspected nothing under {root} — the probe is broken, not clean")
        return 1
    print(f"SUBJECTS={subjects}")
    level = "error" if enforce else "warning"
    for f in findings:
        print(f"::{level}::libs-core-purity: {f}")
    print(f"libs-core-purity: {len(findings)} finding(s) over {subjects} subject(s)"
          + ("" if enforce else " (ADVISORY — not failing the build)"))
    return 1 if (findings and enforce) else 0


def self_test() -> int:
    """Known-positive and known-negative fixtures; exit 0 only when the gate discriminates."""
    ok = True
    with tempfile.TemporaryDirectory() as t:
        root = Path(t)
        (root / ".github/scripts").mkdir(parents=True)
        (root / ".github/scripts/libs-change-dependents.sh").write_text(
            "LIBS_MODULES=(openbank-libs-domain openbank-libs-runtime openbank-libs-lending)\n")
        for mod in ("openbank-libs-domain", "openbank-libs-runtime", "openbank-libs-lending"):
            (root / mod / "src/main/kotlin").mkdir(parents=True)
            (root / mod / "build.gradle.kts").write_text("dependencies {}\n")
        core_pkg = root / "openbank-libs-domain/src/main/kotlin/Money.kt"
        core_pkg.write_text("package com.openbank.libs.domain.money\n")

        clean, n = find_violations(root)
        if clean or n == 0:
            print(f"self-test FAIL: clean fixture reported {clean} over {n} subject(s)")
            ok = False

        # (a) the ADR's own self-test: a lending package placed back in core must be flagged
        bad = root / "openbank-libs-domain/src/main/kotlin/Aprc.kt"
        bad.write_text("package com.openbank.libs.lending\n")
        if not any("Aprc.kt" in f for f in find_violations(root)[0]):
            print("self-test FAIL: package com.openbank.libs.lending in libs-domain not flagged")
            ok = False
        if run(root, enforce=True) != 1:
            print("self-test FAIL: --enforce did not exit 1 on a finding")
            ok = False
        bad.write_text("package com.openbank.libs.domain.x\nimport com.openbank.libs.lending.Ifrs9\n")
        if not any("imports com.openbank.libs.lending.Ifrs9" in f for f in find_violations(root)[0]):
            print("self-test FAIL: lending import in libs-domain not flagged")
            ok = False
        bad.unlink()
        # a lookalike prefix must NOT be flagged
        (root / "openbank-libs-domain/src/main/kotlin/L.kt").write_text("package com.openbank.libs.lendingx\n")
        if find_violations(root)[0]:
            print("self-test FAIL: lookalike package com.openbank.libs.lendingx flagged")
            ok = False
        (root / "openbank-libs-domain/src/main/kotlin/L.kt").unlink()

        # (b) core declaring a context module
        rt = root / "openbank-libs-runtime/build.gradle.kts"
        rt.write_text('dependencies {\n    implementation(project(":openbank-libs-lending"))\n}\n')
        if not any("openbank-libs-runtime/build.gradle.kts" in f for f in find_violations(root)[0]):
            print("self-test FAIL: libs-runtime -> libs-lending dependency not flagged")
            ok = False
        rt.write_text("dependencies {}\n")

        # (c) a runtime libs module missing from LIBS_MODULES
        (root / "openbank-libs-newctx/src/main").mkdir(parents=True)
        if not any("openbank-libs-newctx" in f for f in find_violations(root)[0]):
            print("self-test FAIL: module absent from LIBS_MODULES not flagged")
            ok = False
        shutil.rmtree(root / "openbank-libs-newctx")

        # a context module that does not exist yet arms nothing
        core_pkg.write_text("package com.openbank.libs.iso20022\n")
        if find_violations(root)[0]:
            print("self-test FAIL: iso20022 flagged before openbank-libs-iso20022 exists")
            ok = False
    print("self-test " + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=str(Path(__file__).resolve().parents[2]))
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    return run(Path(a.root), a.enforce)


if __name__ == "__main__":
    sys.exit(main())
