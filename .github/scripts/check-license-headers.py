#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Verify the multi-license tree is self-consistent (ADR-0123 + ADR-0136).

This repository is Apache-2.0 at the root (ADR-0123) with an AGPL-3.0-only open-core
subset (ADR-0136, extended by ADR-0181 for mcp-service and ADR-0193 for ap2-service).
Nothing used to compare the per-file SPDX headers against that declaration, so four
sources drifted apart: the tree carried 12 AGPL modules while rules.yaml listed 10 and
the published NOTICE/README named 4 -- implying to any downstream adopter that the other
8 were Apache-2.0 (#2280). An SPDX scanner reads this as AGPL contamination of an
Apache-2.0 distribution.

`dependencies.license_boundary_exceptions[0].agpl_modules` in rules.yaml is the single
source of truth. Every check below compares something against THAT list:

  1. rules.yaml internal consistency -- the denylist-exception `paths` list and the
     `rule:` string enumerate the same modules as agpl_modules.
  2. Per-module LICENSE -- every AGPL module has its own LICENSE naming AGPL-3.0-only,
     and no module outside the list has one (which would mean a 13th appeared silently).
  3. Per-file SPDX headers -- a file that declares a licence declares the one its module
     is under. Files with NO header are reported but do not fail (adding headers fleet-wide
     is out of scope; scripts/add-license-headers.sh stamps new files correctly).
  4. Copyleft boundary -- no Apache-2.0 module takes a build/compile dependency on an
     AGPL module. This is the check that keeps the AGPL from actually contaminating the
     platform; everything else is labelling.
  5. NOTICE / README -- must point at rules.yaml and must NOT enumerate a partial subset
     of the AGPL modules. A hand-maintained second copy of the list is precisely how the
     4-vs-12 drift happened, so a partial enumeration is a hard failure.

REUSE.toml `precedence = "override"` entries win over in-file headers, for files whose
header cannot be corrected (applied Flyway migrations -- editing one breaks its checksum
and the service fails at boot).

Usage:
    .github/scripts/check-license-headers.py            # verify, exit 1 on any violation
    .github/scripts/check-license-headers.py --selftest # prove the gate can actually fail
"""

from __future__ import annotations

import re
import subprocess
import sys
import tomllib
from pathlib import Path

import yaml

import gatelib

REPO = Path(__file__).resolve().parents[2]
RULES = REPO / "openbank-libs" / "governance" / "rules.yaml"
REUSE_TOML = REPO / "REUSE.toml"
NOTICE = REPO / "NOTICE"
README = REPO / "README.md"

APACHE = "Apache-2.0"
AGPL = "AGPL-3.0-only"

# Extensions that conventionally carry a header in this tree.
SOURCE_SUFFIXES = {".kt", ".kts", ".java", ".ts", ".tsx", ".js", ".mjs", ".py", ".sh", ".rego", ".sql", ".yaml", ".yml"}

# Header must be near the top; a match deeper in the file is content, not a declaration.
HEADER_SCAN_LINES = 8
SPDX_RE = re.compile(r"SPDX-License-Identifier:\s*([A-Za-z0-9.+-]+)")

errors: list[str] = []
notes: list[str] = []


def fail(msg: str) -> None:
    errors.append(msg)


def load_rules() -> dict:
    return yaml.safe_load(RULES.read_text(encoding="utf-8")) or {}


def canonical_agpl_modules(rules: dict) -> list[str]:
    """The single source of truth. Absent/empty is fatal -- never fall back to a guess."""
    exceptions = (rules.get("dependencies") or {}).get("license_boundary_exceptions") or []
    mods = exceptions[0].get("agpl_modules") if exceptions else None
    if not mods:
        sys.exit(
            "FATAL: rules.yaml dependencies.license_boundary_exceptions[0].agpl_modules "
            "is missing or empty. Refusing to run -- with no canonical list this gate "
            "would pass vacuously."
        )
    return list(mods)


def check_rules_internal_consistency(rules: dict, agpl: list[str]) -> None:
    """Check 1: the other two copies of the list inside rules.yaml agree with the canonical one."""
    deps = rules.get("dependencies") or {}

    declared_paths: list[str] = []
    for exc in deps.get("license_denylist_exceptions") or []:
        if exc.get("license") == AGPL:
            declared_paths.extend(exc.get("paths") or [])
    paths_as_modules = {p.rstrip("/") for p in declared_paths}

    missing = sorted(set(agpl) - paths_as_modules)
    extra = sorted(paths_as_modules - set(agpl))
    if missing:
        fail(
            "rules.yaml: license_denylist_exceptions.paths is missing "
            f"{missing} -- present in agpl_modules. A third-party licence scan would flag "
            "AGPL in those paths as a denylist violation."
        )
    if extra:
        fail(
            "rules.yaml: license_denylist_exceptions.paths lists "
            f"{extra} which are NOT in agpl_modules. Remove them or add them to agpl_modules."
        )

    exceptions = deps.get("license_boundary_exceptions") or []
    rule_text = exceptions[0].get("rule", "") if exceptions else ""
    unnamed = sorted(m for m in agpl if f"':{m}'" not in rule_text)
    if unnamed:
        fail(
            f"rules.yaml: license_boundary_exceptions[0].rule does not name {unnamed}. "
            "The rule string must enumerate every agpl_modules entry so the boundary it "
            "states matches the boundary that is enforced."
        )


def check_per_module_license(agpl: list[str]) -> None:
    """Check 2: LICENSE files and the canonical list agree in both directions."""
    for module in agpl:
        lic = REPO / module / "LICENSE"
        if not lic.is_file():
            fail(f"{module}/LICENSE is missing -- an AGPL module must carry its own LICENSE (ADR-0136).")
            continue
        if AGPL not in lic.read_text(encoding="utf-8"):
            fail(f"{module}/LICENSE does not name {AGPL}.")

    for lic in sorted(REPO.glob("openbank-*/LICENSE")):
        module = lic.parent.name
        if module not in agpl:
            fail(
                f"{module}/LICENSE exists but {module} is not in rules.yaml agpl_modules. "
                "Either add it to the canonical list (and to NOTICE's pointer scope) or "
                "delete the file -- a module-level LICENSE is how a component leaves the "
                "root Apache-2.0 grant, and it must be declared."
            )


def reuse_overrides() -> dict[str, str]:
    """REUSE.toml precedence=override entries: repo-relative path -> SPDX id."""
    if not REUSE_TOML.is_file():
        return {}
    data = tomllib.loads(REUSE_TOML.read_text(encoding="utf-8"))
    out: dict[str, str] = {}
    for ann in data.get("annotations") or []:
        if ann.get("precedence") != "override":
            continue
        spdx = ann.get("SPDX-License-Identifier")
        paths = ann.get("path")
        if isinstance(paths, str):
            paths = [paths]
        for p in paths or []:
            out[p] = spdx
    return out


def tracked_files() -> list[str]:
    out = subprocess.run(
        ["git", "-C", str(REPO), "ls-files"], capture_output=True, text=True, check=True
    ).stdout
    return [line for line in out.splitlines() if line]


def check_headers(agpl: list[str], overrides: dict[str, str]) -> None:
    """Check 3: every declared header matches its module's licence."""
    agpl_set = set(agpl)
    mismatched: list[tuple[str, str, str]] = []
    missing_agpl = 0

    for rel in tracked_files():
        path = REPO / rel
        if path.suffix not in SOURCE_SUFFIXES or not path.is_file():
            continue

        module = rel.split("/")[0]
        expected = AGPL if module in agpl_set else APACHE

        override = overrides.get(rel)
        if override is not None:
            # The file's own header is known-wrong and frozen; REUSE.toml is the declaration.
            if override != expected:
                fail(
                    f"REUSE.toml declares {rel} as {override} but its module {module} is "
                    f"{expected}. The override must state the module's licence."
                )
            continue

        try:
            head = "".join(path.open(encoding="utf-8", errors="replace").readlines()[:HEADER_SCAN_LINES])
        except OSError as exc:  # unreadable file is a real problem, not something to skip
            fail(f"{rel}: cannot read ({exc})")
            continue

        match = SPDX_RE.search(head)
        if not match:
            if module in agpl_set:
                missing_agpl += 1
            continue
        found = match.group(1)
        if found != expected:
            mismatched.append((rel, found, expected))

    for rel, found, expected in mismatched:
        hint = (
            "If this migration is already APPLIED anywhere its header is frozen -- editing it "
            "changes the Flyway checksum and the service fails at boot -- so add a "
            "precedence=override entry to REUSE.toml instead of editing the file."
            if Path(rel).suffix == ".sql"
            else "Fix the header."
        )
        fail(f"{rel}: header says {found}, module is {expected}. {hint}")

    if missing_agpl:
        notes.append(
            f"{missing_agpl} file(s) in AGPL modules carry no SPDX header at all (not a failure; "
            "run scripts/add-license-headers.sh --apply, which is path-aware)."
        )


def check_boundary(agpl: list[str]) -> None:
    """Check 4: the copyleft boundary itself. No Apache module may build-depend on an AGPL one."""
    agpl_set = set(agpl)
    for rel in tracked_files():
        if Path(rel).name not in {"build.gradle.kts", "settings.gradle.kts"}:
            continue
        owner = rel.split("/")[0]
        if owner in agpl_set:
            continue  # AGPL -> AGPL is fine
        text = (REPO / rel).read_text(encoding="utf-8")
        for module in agpl:
            if re.search(rf"""project\(\s*["']:{re.escape(module)}["']""", text):
                fail(
                    f"{rel}: Apache-2.0 module '{owner}' declares a build dependency on AGPL "
                    f"module '{module}'. This is real copyleft contamination, not a labelling "
                    "issue -- agent-plane services may only be reached over HTTP (ADR-0136)."
                )


def licensing_text(label: str, path: Path) -> str | None:
    """The licensing prose only.

    NOTICE is entirely licensing. README mentions nearly every module in its service catalog,
    so only its "## License" section counts -- otherwise the subset check below would fire on
    unrelated prose instead of on a licensing claim.
    """
    if not path.is_file():
        fail(f"{label} is missing.")
        return None
    text = path.read_text(encoding="utf-8")
    if label != "README.md":
        return text
    match = re.search(r"^## License$(.*?)(?=^## |\Z)", text, re.MULTILINE | re.DOTALL)
    if not match:
        fail("README.md has no '## License' section.")
        return None
    return match.group(1)


def check_published_docs(agpl: list[str]) -> None:
    """Check 5: NOTICE/README point at the canonical list and never enumerate a subset."""
    pointer = "openbank-libs/governance/rules.yaml"
    for label, path in (("NOTICE", NOTICE), ("README.md", README)):
        text = licensing_text(label, path)
        if text is None:
            continue

        named = sorted(m for m in agpl if m in text)
        if named and len(named) < len(agpl):
            fail(
                f"{label} names {len(named)} of {len(agpl)} AGPL modules "
                f"(missing {sorted(set(agpl) - set(named))}). A partial list tells downstream "
                "adopters the unnamed modules are covered by the root Apache-2.0 LICENSE, which "
                f"is false. Reference {pointer} instead of enumerating."
            )
        if pointer not in text:
            fail(
                f"{label} must reference {pointer} as the authoritative list of AGPL-3.0-only "
                "components, so the licensing statement cannot drift from the tree."
            )


def selftest(agpl: list[str]) -> int:
    """Feed each check an input it MUST flag. A gate whose failure path never ran is unfalsified.

    Runs in-memory only -- no file in the working tree is touched.
    """
    print("== selftest: each check must reject a known-bad input ==")
    results: list[tuple[str, bool]] = []

    def run(name: str, fn) -> None:
        global errors
        saved = errors
        errors = []
        try:
            fn()
            flagged = bool(errors)
        finally:
            caught, errors = errors, saved
        results.append((name, flagged))
        status = "PASS (rejected)" if flagged else "FAIL (accepted bad input!)"
        print(f"  {name}: {status}")
        if flagged:
            print(f"      first message: {caught[0][:120]}")

    truncated = agpl[:-1]  # a list that has silently lost a module
    rules = load_rules()

    run("paths-vs-agpl_modules drift", lambda: check_rules_internal_consistency(rules, agpl + ["openbank-not-a-module"]))
    run("rule-string omits a module", lambda: check_rules_internal_consistency(
        {"dependencies": {
            "license_denylist_exceptions": [{"license": AGPL, "paths": [f"{m}/" for m in agpl]}],
            "license_boundary_exceptions": [{"agpl_modules": agpl, "rule": "no Apache-2.0 module may declare anything"}],
        }}, agpl))
    run("AGPL module with no LICENSE", lambda: check_per_module_license(agpl + ["openbank-libs-domain"]))
    run("header/module licence mismatch", lambda: check_headers(truncated, reuse_overrides()))
    # The boundary check is the one that catches REAL copyleft contamination rather than a
    # mislabel, so it must be falsified too. openbank-libs-domain is build-depended on by many
    # Apache-2.0 modules, so calling it AGPL is exactly the forbidden edge.
    run("Apache module build-depends on AGPL", lambda: check_boundary(agpl + ["openbank-libs-domain"]))
    # "openbank-libs" is named in both NOTICE and the README License section (as the Apache-2.0
    # dependency the AGPL modules consume). Pretending it is an AGPL module therefore makes both
    # documents name 1 of 13 -- exactly the partial-enumeration shape that shipped as "the four
    # AI agent services" while the tree carried twelve.
    run("licensing doc enumerates a subset", lambda: check_published_docs(agpl + ["openbank-libs"]))

    ok = all(flagged for _, flagged in results)
    print()
    print("selftest: ALL CHECKS CAN FAIL" if ok else "selftest: SOME CHECK IS UNFALSIFIED")
    return 0 if ok else 1


def main() -> int:
    rules = load_rules()
    agpl = canonical_agpl_modules(rules)

    if "--selftest" in sys.argv:
        return selftest(agpl)

    gatelib.subjects(len(agpl), "AGPL-3.0-only modules from rules.yaml")
    print(f"Canonical AGPL-3.0-only modules (rules.yaml): {len(agpl)}")
    for m in agpl:
        print(f"  - {m}")
    print()

    overrides = reuse_overrides()
    check_rules_internal_consistency(rules, agpl)
    check_per_module_license(agpl)
    check_headers(agpl, overrides)
    check_boundary(agpl)
    check_published_docs(agpl)

    for note in notes:
        print(f"note: {note}")

    if errors:
        print()
        print(f"FAIL: {len(errors)} licensing inconsistency/ies")
        for e in errors:
            print(f"  - {e}")
        print()
        print("The root LICENSE is Apache-2.0; the modules above are AGPL-3.0-only (ADR-0136).")
        print("Every declaration of that split must agree, or a downstream adopter is misled.")
        return 1

    print("OK: multi-license tree is self-consistent")
    print(f"  {len(agpl)} AGPL-3.0-only modules, each with its own LICENSE")
    print(f"  {len(overrides)} REUSE.toml override(s) for files whose header is frozen")
    print("  no Apache-2.0 module build-depends on an AGPL module")
    return 0


if __name__ == "__main__":
    sys.exit(main())
