#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Every reactive datasource pool must carry the self-heal bounds (idle-timeout,
max-lifetime, max-size).

WHY THIS EXISTS
---------------
billing-service #1682 measured the failure mode: Quarkus leaves all three pool levers
OFF by default, so a transient DB blip that poisons pooled connections leaves them dead
forever — the reactive pool has no way to purge them and the service wedges until a pod
restart (billing sat unable to reach a healthy billing-db for ~8h, 0 restarts, every
health probe green). The fix landed only in billing; the 2026-10 fleet audit found the
other ~60 reactive-datasource services still running the unbounded default.

The three keys do distinct jobs (billing's own comment): idle-timeout evicts a dead idle
connection within the cleaner period so the next acquire opens a fresh one; max-lifetime
is the hard ceiling on how long any wedge can survive; max-size bounds the pool so a
reconnect storm can't grow it unbounded.

`--fix` seeds billing's measured values (idle-timeout: 5M, max-lifetime: 30M,
max-size: 20). A service whose load profile needs different bounds sets them explicitly —
that decision belongs to the service owner, not this gate. The check is
indentation-based text parsing, NOT a YAML round-trip, because these files carry
review-critical comments a load/dump cycle would destroy.

Usage:  check-datasource-pool-self-heal.py --root . [--enforce] [--fix]
        check-datasource-pool-self-heal.py --self-test
"""

from __future__ import annotations

import argparse
import pathlib
import sys
import tempfile

REQUIRED_KEYS = {
    "idle-timeout": "5M",
    "max-lifetime": "30M",
    "max-size": "20",
}
INDENT_STEP = 2


def _parent_key(lines: list[str], idx: int, indent: int) -> tuple[int, str] | None:
    """The (line_idx, key) of the nearest preceding mapping key at a smaller indent."""
    probe = idx - 1
    while probe >= 0:
        line = lines[probe]
        if line.strip() and not line.strip().startswith("#"):
            probe_indent = len(line) - len(line.lstrip(" "))
            if probe_indent < indent:
                stripped = line.strip()
                if stripped.endswith(":"):
                    return probe, stripped[:-1]
                return None
        probe -= 1
    return None


def reactive_pool_blocks(lines: list[str]) -> list[tuple[int, int]]:
    """(block_start_idx, indent) for every `reactive:` mapping whose parent chain is
    quarkus -> datasource (the reactive datasource pool config), anywhere in the
    document including profile sections."""
    blocks: list[tuple[int, int]] = []
    for idx, line in enumerate(lines):
        stripped = line.strip()
        if stripped != "reactive:":
            continue
        indent = len(line) - len(line.lstrip(" "))
        parent = _parent_key(lines, idx, indent)
        if parent is None or parent[1] != "datasource":
            continue
        grandparent = _parent_key(lines, parent[0], parent_indent(lines, parent[0]))
        if grandparent is not None and grandparent[1] == "quarkus":
            blocks.append((idx, indent))
    return blocks


def parent_indent(lines: list[str], idx: int) -> int:
    return len(lines[idx]) - len(lines[idx].lstrip(" "))


def child_keys(lines: list[str], block_idx: int, indent: int) -> dict[str, int]:
    """key -> line_idx for every direct child of the block starting at block_idx."""
    keys: dict[str, int] = {}
    child_indent = indent + INDENT_STEP
    for probe in range(block_idx + 1, len(lines)):
        line = lines[probe]
        if not line.strip() or line.strip().startswith("#"):
            continue
        probe_indent = len(line) - len(line.lstrip(" "))
        if probe_indent <= indent:
            break
        if probe_indent == child_indent:
            keys[line.strip().split(":", 1)[0]] = probe
    return keys


BASE_RELPATH = pathlib.Path("openbank-libs-runtime/src/main/resources/application.yaml")


def inherited_keys(root: pathlib.Path) -> set[str]:
    """Self-heal keys the libs-runtime base supplies to every service on the classpath.

    A service file may omit a bound the base already carries: the base sits on every
    service's classpath, and a service key (same name, any value) still overrides it. Only
    the un-profiled `quarkus.datasource.reactive` block counts, because a `%profile` key
    would not reach prod. Returns the empty set if the base is absent, so deleting it makes
    every service violate again instead of silently passing."""
    base = root / BASE_RELPATH
    if not base.is_file():
        return set()
    lines = base.read_text(encoding="utf-8").splitlines(keepends=True)
    keys: set[str] = set()
    for block_idx, indent in reactive_pool_blocks(lines):
        # reactive_pool_blocks walks profile sections too; only a block at indent 4 under
        # a top-level `quarkus:` is un-profiled.
        if indent == INDENT_STEP * 2 and not _under_profile(lines, block_idx):
            keys |= set(child_keys(lines, block_idx, indent))
    return keys & set(REQUIRED_KEYS)


def _under_profile(lines: list[str], idx: int) -> bool:
    for probe in range(idx - 1, -1, -1):
        line = lines[probe]
        if line.strip() and not line.startswith((" ", "#")):
            return line.lstrip().startswith(('"%', "'%", "%"))
    return False


def find_violations(lines: list[str], inherited: frozenset[str] | set[str] = frozenset()) -> list[str]:
    findings: list[str] = []
    for block_idx, indent in reactive_pool_blocks(lines):
        children = child_keys(lines, block_idx, indent)
        missing = [key for key in REQUIRED_KEYS if key not in children and key not in inherited]
        if missing:
            findings.append(
                f"reactive datasource pool (line {block_idx + 1}): missing {', '.join(missing)}",
            )
    return findings


def fix(lines: list[str]) -> tuple[list[str], int]:
    """Insert the missing self-heal keys after the pool's `url:` line (or at the block
    head when there is no url child), processing blocks in reverse document order so an
    insertion never shifts indices an earlier lookup still needs."""
    out = list(lines)
    fixed = 0
    for block_idx, indent in reversed(reactive_pool_blocks(out)):
        children = child_keys(out, block_idx, indent)
        missing = [key for key in REQUIRED_KEYS if key not in children]
        if not missing:
            continue
        pad = " " * (indent + INDENT_STEP)
        insertion = [f"{pad}{key}: {REQUIRED_KEYS[key]}\n" for key in REQUIRED_KEYS if key in missing]
        anchor = children.get("url", block_idx) + 1
        if anchor - 1 == block_idx:
            # no url child: insert directly under the `reactive:` line, and make sure the
            # reactive line itself is newline-terminated first
            if not out[block_idx].endswith("\n"):
                out[block_idx] = out[block_idx] + "\n"
        elif not out[anchor - 1].endswith("\n"):
            out[anchor - 1] = out[anchor - 1] + "\n"
        out[anchor:anchor] = insertion
        fixed += 1
    return out, fixed


def count_subjects(lines: list[str]) -> int:
    return len(reactive_pool_blocks(lines))


def run(root: pathlib.Path, enforce: bool, apply_fix: bool) -> int:
    findings_total = 0
    fixed_total = 0
    subjects_total = 0
    inherited = inherited_keys(root)
    for yaml_path in sorted(root.glob("openbank-*/src/main/resources/application.yaml")):
        lines = yaml_path.read_text(encoding="utf-8").splitlines(keepends=True)
        if apply_fix:
            out, fixed = fix(lines)
            if fixed:
                yaml_path.write_text("".join(out), encoding="utf-8")
                fixed_total += fixed
                lines = out
        findings = find_violations(lines, inherited)
        subjects_total += count_subjects(lines)
        for finding in findings:
            annotation = "error" if enforce else "warning"
            print(f"::{annotation}:: {yaml_path}: {finding}")
        findings_total += len(findings)
    if apply_fix:
        print(f"datasource-pool-self-heal: fixed {fixed_total} pool(s)")
    print(f"SUBJECTS={subjects_total}")
    if findings_total:
        print(f"datasource-pool-self-heal: {findings_total} reactive pool(s) without self-heal bounds")
        return 1 if enforce else 0
    print("datasource-pool-self-heal: every reactive pool declares idle-timeout, max-lifetime, max-size")
    return 0


def self_test() -> int:
    fails: list[str] = []

    def doc(body: str) -> list[str]:
        return body.strip("\n").splitlines(keepends=True)

    clean = doc(
        """
quarkus:
  datasource:
    db-kind: postgresql
    reactive:
      url: postgresql://localhost:5432/x
      idle-timeout: 5M
      max-lifetime: 30M
      max-size: 20
""",
    )
    if find_violations(clean):
        fails.append("a fully-bounded pool was flagged")

    missing = doc(
        """
quarkus:
  datasource:
    reactive:
      url: postgresql://localhost:5432/x
""",
    )
    found = find_violations(missing)
    if len(found) != 1 or "idle-timeout" not in found[0]:
        fails.append(f"an unbounded pool was NOT flagged: {found}")

    profiled = doc(
        """
"%prod":
  quarkus:
    datasource:
      reactive:
        url: postgresql://prod-db/x
""",
    )
    if not find_violations(profiled):
        fails.append("a %prod-profile pool without bounds was NOT flagged")

    # `reactive:` under a DIFFERENT parent (messaging) must not be touched
    other_parent = doc(
        """
quarkus:
  messaging:
    reactive: true
""",
    )
    if find_violations(other_parent):
        fails.append("a scalar `reactive:` under a non-datasource parent was flagged")

    # A bound the base supplies is inherited; one it does not supply still fails.
    if find_violations(missing, {"idle-timeout", "max-lifetime", "max-size"}):
        fails.append("a pool whose bounds all come from the base was flagged")
    partial = find_violations(missing, {"max-size"})
    if len(partial) != 1 or "max-size" in partial[0]:
        fails.append(f"a partially inherited pool was not flagged for exactly the rest: {partial}")

    with tempfile.TemporaryDirectory() as tmp:
        base_dir = pathlib.Path(tmp) / "openbank-libs-runtime" / "src" / "main" / "resources"
        base_dir.mkdir(parents=True)
        base_file = base_dir / "application.yaml"
        base_file.write_text(
            "".join(clean).replace("      url: postgresql://localhost:5432/x\n", ""), encoding="utf-8")
        if inherited_keys(pathlib.Path(tmp)) != set(REQUIRED_KEYS):
            fails.append(f"base keys not read: {inherited_keys(pathlib.Path(tmp))}")
        base_file.write_text('"%test":\n' + "".join("  " + ln for ln in clean), encoding="utf-8")
        if inherited_keys(pathlib.Path(tmp)):
            fails.append("a %test-profile block in the base was counted as inherited")
        base_file.unlink()
        if inherited_keys(pathlib.Path(tmp)):
            fails.append("a missing base yielded inherited keys")

    with tempfile.TemporaryDirectory() as tmp:
        service = pathlib.Path(tmp) / "openbank-demo-service" / "src" / "main" / "resources"
        service.mkdir(parents=True)
        target = service / "application.yaml"
        target.write_text("".join(missing), encoding="utf-8")
        if run(pathlib.Path(tmp), enforce=False, apply_fix=True) != 0:
            fails.append("--fix did not clear the seeded violation")
        after = target.read_text(encoding="utf-8")
        for key in REQUIRED_KEYS:
            if f"{key}:" not in after:
                fails.append(f"--fix output lacks {key}:\n{after}")
        if find_violations(after.splitlines(keepends=True)):
            fails.append("--fix left a violating pool behind")

    if fails:
        for fail in fails:
            sys.stderr.write(f"self-test FAILED: {fail}\n")
        return 1
    print("self-test OK")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--enforce", action="store_true")
    parser.add_argument("--fix", action="store_true", help="seed missing self-heal bounds (one-off fleet sweep)")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        return self_test()
    return run(pathlib.Path(args.root), args.enforce, args.fix)


if __name__ == "__main__":
    sys.exit(main())
