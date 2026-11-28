#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

"""ADR-0326 phase b: shrink-only service-local JAX-RS ExceptionMapper baseline.

Named and anonymous declarations both count. Stable keys are module/path/declaration, not line
numbers. Libs-owned mappers are outside the corpus. PSD2 is excluded only in a source file carrying
Berlin Group tppMessages, the response family ADR-0326 explicitly leaves out of the shared model.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

import gatelib
from error_code_catalogue import strip_comments

BASELINE = ".github/scripts/service-local-exception-mappers-baseline.txt"
HEADER = """# ADR-0326 phase b service-local ExceptionMapper baseline (issue #11610).
# Schema: MODULE<TAB>REPO_RELATIVE_PATH<TAB>DECLARATION
# A new declaration fails. When a mapper is deleted, delete its row in the same reviewed change.
"""
NAMED = re.compile(
    r"\b(?:class|object)\s+(\w+)\s*(?:\([^{;]*?\))?\s*:\s*(?:[A-Za-z_]\w*\.)*ExceptionMapper\s*<",
    re.DOTALL,
)
ANONYMOUS = re.compile(r"\bobject\s*:\s*(?:[A-Za-z_]\w*\.)*ExceptionMapper\s*<")


def scan(root: pathlib.Path) -> tuple[list[str], int]:
    current: list[str] = []
    subjects = 0
    for path in sorted(root.glob("openbank-*/**/src/main/**/*.kt")):
        relative = path.relative_to(root).as_posix()
        module = relative.split("/", 1)[0]
        if module.startswith("openbank-libs") or "/build/" in relative or "/generated/" in relative.lower():
            continue
        subjects += 1
        text = path.read_text(encoding="utf-8")
        if "ExceptionMapper" not in text:
            continue
        source = strip_comments(text)
        if module == "openbank-psd2-service" and "tppMessages" in source:
            continue
        declarations = [(match.start(), match.group(1)) for match in NAMED.finditer(source)]
        anonymous = [(match.start(), f"anonymous-{number}") for number, match in enumerate(ANONYMOUS.finditer(source), 1)]
        for _, declaration in sorted(declarations + anonymous):
            current.append(f"{module}\t{relative}\t{declaration}")
    return current, subjects


def load_baseline(root: pathlib.Path) -> set[str]:
    lines = (root / BASELINE).read_text(encoding="utf-8").splitlines()
    entries = {line for line in lines if line.strip() and not line.lstrip().startswith("#")}
    for line in entries:
        if len(line.split("\t")) != 3:
            raise ValueError(f"malformed mapper baseline row: {line!r}")
    return entries


def evaluate(current: list[str], baseline: set[str]) -> list[str]:
    errors = []
    for key in sorted(set(current) - baseline):
        module, path, declaration = key.split("\t")
        errors.append(
            f"::error file={path}::new service-local ExceptionMapper {declaration} in {module}; "
            "throw a DomainException subclass and use libs-runtime's DomainExceptionMapper (ADR-0326)"
        )
    for key in sorted(baseline - set(current)):
        errors.append(f"stale mapper baseline entry; remove the row only with its declaration: {key}")
    return errors


def run(root: pathlib.Path, write_baseline: bool = False, list_only: bool = False) -> int:
    try:
        current, subjects = scan(root)
        gatelib.subjects(subjects, "service Kotlin src/main files scanned")
        if list_only:
            print("\n".join(current))
            return 0
        if write_baseline:
            (root / BASELINE).write_text(HEADER + "\n".join(current) + "\n", encoding="utf-8")
            print(f"WROTE {len(current)} service-local mapper baseline rows")
            return 0
        errors = evaluate(current, load_baseline(root))
    except (OSError, ValueError) as exc:
        print(f"::error::service-local mapper ratchet could not be evaluated: {exc}", file=sys.stderr)
        return 2
    for error in errors:
        print(error)
    if errors:
        print(f"FAIL: {len(errors)} service-local mapper ratchet finding(s)")
        return 1
    print(f"OK: {len(current)} existing service-local mapper declaration(s), none new and no stale baseline rows")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=".")
    parser.add_argument("--write-baseline", action="store_true")
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        from test_service_local_exception_mappers import run_self_test

        return run_self_test()
    return run(pathlib.Path(args.root).resolve(), write_baseline=args.write_baseline, list_only=args.list)


if __name__ == "__main__":
    raise SystemExit(main())
