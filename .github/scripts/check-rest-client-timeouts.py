#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Every `quarkus.rest-client.<client>` with a `url:` must declare connect + read timeouts.

WHY THIS EXISTS
---------------
The 2026-10 fleet audit found explicit client timeouts on only ~19 of 67 services. A
REST client without an explicit timeout inherits SmallRye/Vert.x defaults, which are
either "no timeout" or an implementation-detail default — both are the classic source of
a cascading overload: one slow upstream holds every caller thread until the pool
exhausts, and the failure surfaces three services away from its cause. For a banking
core, an unbounded wait on a money-path call is a defect in itself, whatever the
upstream does.

WHAT IT CHECKS
--------------
For every service application.yaml, every direct child of a `rest-client:` block that
carries a `url:` must also carry `connect-timeout:` and `read-timeout:`. Clients without
a `url:` (named OIDC clients live under oidc-client, not here; interface-keyed clients
get their address from the registry) are out of scope. The check is indentation-based
text parsing — NOT a YAML round-trip — because these files carry review-critical
comments that any load/dump cycle would destroy.

Fleet convention for the values (measured on the 19 services that already declare
them): connect 2000-3000 ms, read 2000-5000 ms. `--fix` writes connect-timeout: 2000 /
read-timeout: 5000 after the client's `url:` line; a service whose SLO needs a
different bound must set it explicitly — that decision belongs to the service owner,
not to this gate.

ADVISORY (ADR-0144): `--fix` seeds the fleet, the gate then enforces the ratchet.

Usage:  check-rest-client-timeouts.py --root . [--enforce] [--fix]
        check-rest-client-timeouts.py --self-test
"""

from __future__ import annotations

import argparse
import pathlib
import sys
import tempfile

CONNECT_TIMEOUT_MS = 2000
READ_TIMEOUT_MS = 5000
INDENT_STEP = 2


def client_blocks(lines: list[str]) -> list[tuple[int, int, str]]:
    """(client_key_line_idx, client_indent, client_name) for every direct child of a
    `rest-client:` mapping, wherever it appears in the document (including profile
    sections)."""
    blocks: list[tuple[int, int, str]] = []
    for idx, line in enumerate(lines):
        stripped = line.strip()
        if not stripped.endswith(":"):
            continue
        indent = len(line) - len(line.lstrip(" "))
        parent = _parent_key(lines, idx, indent)
        if parent is not None and parent[1].endswith("rest-client"):
            blocks.append((idx, indent, stripped[:-1]))
    return blocks


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
                return None  # a scalar sits between us and any parent mapping
        probe -= 1
    return None


def child_keys(lines: list[str], block_idx: int, indent: int) -> dict[str, int]:
    """key -> line_idx for every direct child of the block starting at block_idx."""
    keys: dict[str, int] = {}
    child_indent = indent + INDENT_STEP
    for probe in range(block_idx + 1, len(lines)):
        line = lines[probe]
        if not line.strip():
            continue
        if line.strip().startswith("#"):
            continue
        probe_indent = len(line) - len(line.lstrip(" "))
        if probe_indent <= indent:
            break
        if probe_indent == child_indent:
            stripped = line.strip()
            key = stripped.split(":", 1)[0]
            keys[key] = probe
    return keys


def find_violations(lines: list[str]) -> list[str]:
    """Human-readable findings: client blocks with a url but no explicit timeouts."""
    findings: list[str] = []
    for block_idx, indent, name in client_blocks(lines):
        children = child_keys(lines, block_idx, indent)
        if "url" not in children:
            continue
        missing = [key for key in ("connect-timeout", "read-timeout") if key not in children]
        if missing:
            findings.append(f"{name} (line {block_idx + 1}): missing {', '.join(missing)}")
    return findings


def fix(lines: list[str]) -> tuple[list[str], int]:
    """Insert the default timeouts right after each violating client's `url:` line.

    Blocks are processed in REVERSE document order so an insertion below never shifts
    the indices a later (earlier-in-document) lookup still needs.
    """
    out = list(lines)
    fixed = 0
    for block_idx, indent, _name in reversed(client_blocks(out)):
        children = child_keys(out, block_idx, indent)
        if "url" not in children:
            continue
        missing = [key for key in ("connect-timeout", "read-timeout") if key not in children]
        if not missing:
            continue
        pad = " " * (indent + INDENT_STEP)
        insertion = [f"{pad}{key}: {CONNECT_TIMEOUT_MS if key == 'connect-timeout' else READ_TIMEOUT_MS}\n"
                     for key in ("connect-timeout", "read-timeout") if key in missing]
        url_idx = children["url"]
        if not out[url_idx].endswith("\n"):
            out[url_idx] = out[url_idx] + "\n"
        anchor = url_idx + 1
        out[anchor:anchor] = insertion
        fixed += 1
    return out, fixed


def count_subjects(lines: list[str]) -> int:
    """Url-carrying rest-client blocks — the corpus the floor is declared against."""
    return sum(1 for block_idx, indent, _ in client_blocks(lines) if "url" in child_keys(lines, block_idx, indent))


def run(root: pathlib.Path, enforce: bool, apply_fix: bool) -> int:
    findings_total = 0
    fixed_total = 0
    subjects_total = 0
    for yaml_path in sorted(root.glob("openbank-*/src/main/resources/application.yaml")):
        lines = yaml_path.read_text(encoding="utf-8").splitlines(keepends=True)
        if apply_fix:
            out, fixed = fix(lines)
            if fixed:
                yaml_path.write_text("".join(out), encoding="utf-8")
                fixed_total += fixed
                lines = out
        findings = find_violations(lines)
        subjects_total += count_subjects(lines)
        for finding in findings:
            annotation = "error" if enforce else "warning"
            print(f"::{annotation}:: {yaml_path}: {finding}")
        findings_total += len(findings)
    if apply_fix:
        print(f"rest-client-timeouts: fixed {fixed_total} client(s)")
    print(f"SUBJECTS={subjects_total}")
    if findings_total:
        print(f"rest-client-timeouts: {findings_total} client(s) without explicit timeouts")
        return 1 if enforce else 0
    print("rest-client-timeouts: every url-carrying rest-client declares both timeouts")
    return 0


def self_test() -> int:
    fails: list[str] = []

    def doc(body: str) -> list[str]:
        return body.strip("\n").splitlines(keepends=True)

    clean = doc(
        """
quarkus:
  rest-client:
    ledger-service:
      url: http://localhost:8101
      connect-timeout: 2000
      read-timeout: 5000
""",
    )
    if find_violations(clean):
        fails.append("a fully-configured client was flagged")

    violating = doc(
        """
quarkus:
  rest-client:
    party-service:
      url: http://localhost:8111
""",
    )
    found = find_violations(violating)
    if len(found) != 1 or "party-service" not in found[0]:
        fails.append(f"a url-carrying client without timeouts was NOT flagged: {found}")

    no_url = doc(
        """
quarkus:
  rest-client:
    some-interface-keyed-client:
      scope: jakarta.inject.Singleton
""",
    )
    if find_violations(no_url):
        fails.append("a url-less client (registry-addressed) was flagged")

    profiled = doc(
        """
"%test":
  quarkus:
    rest-client:
      stub-service:
        url: http://localhost:9999
""",
    )
    if not find_violations(profiled):
        fails.append("a %test-profile client without timeouts was NOT flagged")

    partial = doc(
        """
quarkus:
  rest-client:
    half-configured:
      url: http://localhost:8200
      connect-timeout: 2000
""",
    )
    found = find_violations(partial)
    if len(found) != 1 or "read-timeout" not in found[0]:
        fails.append(f"a client with only connect-timeout was not flagged for read-timeout: {found}")

    with tempfile.TemporaryDirectory() as tmp:
        service = pathlib.Path(tmp) / "openbank-demo-service" / "src" / "main" / "resources"
        service.mkdir(parents=True)
        target = service / "application.yaml"
        target.write_text("".join(violating), encoding="utf-8")
        if run(pathlib.Path(tmp), enforce=False, apply_fix=True) != 0:
            fails.append("--fix did not clear the seeded violation")
        after = target.read_text(encoding="utf-8")
        if "connect-timeout: 2000" not in after or "read-timeout: 5000" not in after:
            fails.append(f"--fix output lacks the timeout keys:\n{after}")
        if find_violations(after.splitlines(keepends=True)):
            fails.append("--fix left a violating client behind")

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
    parser.add_argument("--fix", action="store_true", help="seed missing timeouts (one-off fleet sweep)")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        return self_test()
    return run(pathlib.Path(args.root), args.enforce, args.fix)


if __name__ == "__main__":
    sys.exit(main())
