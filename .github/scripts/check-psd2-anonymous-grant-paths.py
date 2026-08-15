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


def granted_actions(rego_text: str | None = None) -> set[str]:
    """The action set the ANONYMOUS rule grants, read out of the rego (comments stripped).

    Takes the TEXT rather than reading the path, so the self-test can drive it. The two
    `sys.exit` calls below are deliberate: a rule that has vanished, or one that no longer
    gates on ANONYMOUS, is not "zero granted actions" — it is the gate having lost its
    subject, and reporting a clean comparison about it would be the exact failure this whole
    class of guard exists to prevent.
    """
    body = strip_rego_comments(rego_text if rego_text is not None else REGO.read_text())
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


def gated_prefixes(filter_text: str | None = None) -> tuple[set[str], set[str]]:
    """(gated prefixes, excluded prefixes) read out of EidasMtlsFilter's `gated` predicate."""
    src = strip_kotlin_comments(filter_text if filter_text is not None else FILTER.read_text())
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


def self_test() -> int:
    """Falsify the two readers this gate compares.

    PSD2 lets a TPP reach certain endpoints ANONYMOUSLY — no user token — which is only safe
    because eIDAS mTLS proves the caller is a licensed institution first. The rego grant and
    the filter's path list are written in different languages, in different files, by
    different people; if they drift, the anonymous grant survives while the mTLS gate no
    longer covers those paths, and the endpoints become open to anyone. Nothing else in CI
    compares them.

    Both readers are regex-over-source, so both can silently return LESS than the truth — and
    less means fewer paths to check, which is the direction that reports clean.
    """
    fails: list[str] = []

    def case(label, got, want):
        if got != want:
            fails.append(f"{label}: expected {want}, got {got}")

    rego = (
        'allowed_reasons contains "%s" if {\n'
        '  input.principal.type == "ANONYMOUS"\n'
        '  input.action in {"psd2.read", "psd2.consent"}\n'
        '  # "psd2.commented" must not be counted\n'
        '}\n'
    ) % ANONYMOUS_RULE
    case("granted actions are read, comments excluded",
         sorted(granted_actions(rego)), ["psd2.consent", "psd2.read"])

    # A rule that no longer gates on ANONYMOUS is a CHANGED subject, not an empty result. The
    # script exits fatally; the self-test asserts that rather than letting it pass quietly.
    import contextlib, io
    for label, text in (
        ("a vanished rule", 'allowed_reasons contains "other" if {\n  true\n}\n'),
        ("a rule that dropped ANONYMOUS",
         'allowed_reasons contains "%s" if {\n  input.principal.type == "HUMAN"\n}\n' % ANONYMOUS_RULE),
    ):
        sink = io.StringIO()
        try:
            with contextlib.redirect_stderr(sink), contextlib.redirect_stdout(sink):
                granted_actions(text)
        except SystemExit:
            pass
        else:
            fails.append(f"{label} did not abort — the gate would compare against an empty set "
                         f"and report agreement it never established")

    # --- the filter's path predicate ------------------------------------------------------
    kt = (
        'val gated = path.startsWith("/api/v1/accounts") ||\n'
        '            path.startsWith("/api/v1/payments") &&\n'
        '            !path.startsWith("/api/v1/payments/public")\n'
        '    if (!gated) return\n'
    )
    inc, exc = gated_prefixes(kt)
    case("included prefixes are read", sorted(inc), ["/api/v1/accounts", "/api/v1/payments"])
    case("a NEGATED prefix is an exclusion, not an inclusion", sorted(exc), ["/api/v1/payments/public"])

    # PROSE: a KDoc naming a path must not become a gated prefix — that would invent coverage
    # the filter does not have, which is the direction that reads as safe.
    # The comment must sit INSIDE the `val gated = ... if (!gated)` span. Placed above it, as
    # the first version of this fixture had it, the regex never sees it and the stripper is
    # never exercised — the deliberate break went UNCAUGHT.
    kt2 = (
        'val gated = path.startsWith("/api/v1/accounts") ||\n'
        '            // path.startsWith("/api/v1/never") was removed, see #x\n'
        '            path.startsWith("/api/v1/payments")\n'
        '    if (!gated) return\n'
    )
    inc2, _e = gated_prefixes(kt2)
    if "/api/v1/never" in inc2:
        fails.append("a path named in a comment was read as a gated prefix")

    # A live read of both real files: fixtures cannot tell that either file still parses.
    live_actions = granted_actions()
    live_inc, _live_exc = gated_prefixes()
    if not live_actions:
        fails.append("the real rego yielded NO granted actions")
    if not live_inc:
        fails.append("the real filter yielded NO gated prefixes — the gate would compare "
                     "nothing against nothing")

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print(f"self-test ok: psd2 anonymous-grant parity is falsifiable "
          f"(8 cases + a live read of {len(live_actions)} action(s), {len(live_inc)} prefix(es))")
    return 0


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()

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
