#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Guard: every psd2 action granted to an ANONYMOUS principal must sit behind EidasMtlsFilter.

`psd2_rest_ext.rego` grants five actions — psd2.list/read/create/initiate/delete — to
`input.principal.type == "ANONYMOUS"`. That is safe ONLY because a TPP has already been
authenticated upstream by `EidasMtlsFilter` (@Priority(AUTHENTICATION)), which 401s a missing or
unauthorized TPP identity on the path prefixes it gates. The rego says so in a comment; nothing
checked it (issue #2169).

The failure this prevents: someone adds `@Authorize(action = "psd2.initiate")` on a handler outside
the filter's gated prefixes — a new admin route, a debug endpoint, a resource class that moved, or
anything under `open-banking/sandbox/` — and the PDP hands payment initiation to a genuinely
unauthenticated caller while every gate in CI stays green.

BOTH SIDES ARE DERIVED, never re-declared here:
  * the granted ACTION set is read out of the rego rule;
  * the gated PATH prefixes are read out of `EidasMtlsFilter.kt`'s `gated` predicate, including its
    negated exclusions (`!path.startsWith("open-banking/sandbox/")`).
A guard that kept its own copy of either list would be the drift class it exists to prevent.

Usage: check-psd2-anonymous-grant-paths.py [--enforce]
Advisory (::warning, exit 0) unless --enforce.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
REGO = REPO / "openbank-infra/gitops/components/psd2-service/psd2_rest_ext.rego"
FILTER = REPO / (
    "openbank-psd2-service/src/main/kotlin/com/openbank/psd2/"
    "infrastructure/rest/filter/EidasMtlsFilter.kt"
)
SOURCES = REPO / "openbank-psd2-service/src/main/kotlin"

ANONYMOUS_RULE = "psd2-tpp-eidas-qwac"


def strip_kotlin_comments(src: str) -> str:
    """Remove // and /* */ comments, honouring the fact that Kotlin block comments NEST.

    A guard over source text must have an explicit rule for code-about-code: this file's own
    docstring quotes `@Authorize(action = "psd2.initiate")`, and the rego + the filter are both
    thick with prose naming the very annotations being matched. Comments are stripped BY DESIGN, so
    a KDoc example can never be reported as a real endpoint — and, conversely, prose that names a
    dead identifier is invisible to this guard forever.
    """
    out: list[str] = []
    i, n, depth = 0, len(src), 0
    in_line_comment = False
    in_string = False
    string_delim = ""
    while i < n:
        two = src[i : i + 2]
        if depth > 0:
            if two == "/*":
                depth += 1
                i += 2
                continue
            if two == "*/":
                depth -= 1
                i += 2
                continue
            i += 1
            continue
        if in_line_comment:
            if src[i] == "\n":
                in_line_comment = False
                out.append("\n")
            i += 1
            continue
        if in_string:
            out.append(src[i])
            if src[i] == "\\":
                if i + 1 < n:
                    out.append(src[i + 1])
                i += 2
                continue
            if src.startswith(string_delim, i):
                out.append(src[i + 1 : i + len(string_delim)])
                i += len(string_delim)
                in_string = False
                continue
            i += 1
            continue
        if src.startswith('"""', i):
            in_string, string_delim = True, '"""'
            out.append('"""')
            i += 3
            continue
        if src[i] == '"':
            in_string, string_delim = True, '"'
            out.append('"')
            i += 1
            continue
        if two == "/*":
            depth = 1
            i += 2
            continue
        if two == "//":
            in_line_comment = True
            i += 2
            continue
        out.append(src[i])
        i += 1
    return "".join(out)


def strip_rego_comments(src: str) -> str:
    return "\n".join(line.split("#", 1)[0] for line in src.splitlines())


def granted_actions() -> set[str]:
    """The action set the ANONYMOUS rule grants, read out of the rego (comments stripped)."""
    body = strip_rego_comments(REGO.read_text())
    match = re.search(
        rf'allowed_reasons contains "{ANONYMOUS_RULE}" if \{{(.*?)\n\}}',
        body,
        re.DOTALL,
    )
    if not match:
        sys.exit(f"FATAL: rule '{ANONYMOUS_RULE}' not found in {REGO.relative_to(REPO)}")
    rule = match.group(1)
    if "ANONYMOUS" not in rule:
        # The rule stopped being an anonymous grant. That is a policy change big enough that this
        # guard must not silently keep passing on a premise that no longer holds.
        sys.exit(f"FATAL: rule '{ANONYMOUS_RULE}' no longer gates on an ANONYMOUS principal")
    return set(re.findall(r'"(psd2\.[a-z]+)"', rule))


def gated_prefixes() -> tuple[set[str], set[str]]:
    """(gated prefixes, excluded prefixes) read out of EidasMtlsFilter's `gated` predicate."""
    src = strip_kotlin_comments(FILTER.read_text())
    match = re.search(r"val gated\s*=(.*?)\n\s*if \(!gated\)", src, re.DOTALL)
    if not match:
        sys.exit(f"FATAL: `val gated = ... ; if (!gated)` not found in {FILTER.relative_to(REPO)}")
    expr = match.group(1)
    included, excluded = set(), set()
    for negated, prefix in re.findall(r'(!?)path\.startsWith\("([^"]+)"\)', expr):
        (excluded if negated else included).add(prefix)
    if not included:
        sys.exit("FATAL: EidasMtlsFilter's `gated` predicate declares no path prefixes")
    return included, excluded


def annotation_block(lines: list[str], idx: int) -> list[str]:
    """The contiguous run of annotation lines containing `lines[idx]`."""
    start = idx
    while start > 0 and lines[start - 1].strip().startswith("@"):
        start -= 1
    end = idx
    while end + 1 < len(lines) and lines[end + 1].strip().startswith("@"):
        end += 1
    return lines[start : end + 1]


def annotated_endpoints() -> list[tuple[Path, int, str, str]]:
    """(file, line, action, resolved path) for every @Authorize in psd2-service's main sources."""
    found = []
    for file in sorted(SOURCES.rglob("*.kt")):
        src = strip_kotlin_comments(file.read_text())
        lines = src.splitlines()
        class_path = ""
        class_match = re.search(r'^@Path\("([^"]*)"\)', src, re.MULTILINE)
        if class_match:
            class_path = class_match.group(1)
        for idx, line in enumerate(lines):
            authorize = re.search(r'@Authorize\(action\s*=\s*"([^"]+)"', line)
            if not authorize:
                continue
            # The method @Path, if any, is in the CONTIGUOUS annotation block this @Authorize
            # belongs to — bounded in both directions by the first non-annotation line, so a
            # neighbouring method's @Path can never be attributed to this one.
            method_path = ""
            for probe in annotation_block(lines, idx):
                probe_match = re.match(r'\s+@Path\("([^"]*)"\)', probe)
                if probe_match:
                    method_path = probe_match.group(1)
                    break
            resolved = (class_path.rstrip("/") + "/" + method_path.lstrip("/")).strip("/")
            found.append((file, idx + 1, authorize.group(1), resolved))
    return found


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--enforce", action="store_true")
    args = parser.parse_args()

    actions = granted_actions()
    included, excluded = gated_prefixes()
    endpoints = annotated_endpoints()

    violations = []
    checked = 0
    for file, line, action, path in endpoints:
        if action not in actions:
            continue
        checked += 1
        gated = any(path.startswith(p) for p in included) and not any(
            path.startswith(p) for p in excluded
        )
        if not gated:
            violations.append((file, line, action, path))

    print("psd2 anonymous-grant path guard (issue #2169)")
    print(f"  granted to ANONYMOUS : {', '.join(sorted(actions))}")
    print(f"  gated prefixes       : {', '.join(sorted(included))}")
    print(f"  excluded prefixes    : {', '.join(sorted(excluded)) or '(none)'}")
    print(f"  annotated endpoints  : {len(endpoints)} ({checked} carry a granted action)")

    if not checked:
        # A scope that matched nothing reads as a pass while having checked nothing — the exact
        # vacuous green this repo has been bitten by. Say so loudly.
        print("::error::guard matched ZERO annotated endpoints — its parser has drifted from the source")
        return 1

    if not violations:
        print(f"  OK: all {checked} anonymous-granted endpoints sit behind EidasMtlsFilter")
        return 0

    for file, line, action, path in violations:
        rel = file.relative_to(REPO)
        level = "error" if args.enforce else "warning"
        print(
            f"::{level} file={rel},line={line}::{action} on '/{path}' is granted to an ANONYMOUS "
            f"principal by psd2_rest_ext.rego but is NOT behind EidasMtlsFilter's gated prefixes "
            f"— an unauthenticated caller would be allowed. Add the prefix to the filter's `gated` "
            f"predicate in the same change, or give the endpoint an action outside the granted set."
        )
    return 1 if args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
