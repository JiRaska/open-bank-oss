#!/usr/bin/env python3
"""Guard: no `runBlocking` in the body of a `@Scheduled` method (rules.yaml: scheduled_methods).

WHY THIS EXISTS: Quarkus invokes a plain (non-`suspend`) `@Scheduled` method on a bare
`executor-thread`, which carries **no Vert.x context**. So a body of `runBlocking { … }` runs
the coroutine on that thread, and the first reactive Hibernate-Reactive / Panache call inside
throws:

    HR000068: This method should exclusively be invoked from a Vert.x EventLoop thread;
    currently running on thread 'executor-thread-1'

The job then aborts having done nothing — and every one of these failed *silently*, because the
throw happens before whatever per-item try/catch the job has (or is swallowed by the job's own
outer catch into a single ERROR line nobody reads).

This is not hypothetical. #2148 found `StandingOrderExecutionScheduler.sweep()` in this shape:
no due standing order had **ever** executed. The #2187 fleet sweep then found the same shape in
four more schedulers, three of them money-path — the daily sub-ledger tie-out control and the
daily FX revaluation (both ledger-service, #2190), the statutory ČNB fixing ingestion
(fx-service, #2191) and the monthly billing cycle (#2194). None had ever run.

Every one of them had tests. None could see it: a test that calls the method *directly* supplies
a Vert.x context the scheduler does not, so it passes against the broken code. That is what makes
this a guard rather than a lesson in a doc — the natural test does not fail.

THE FIX is always the same: make the method a `suspend fun`. Quarkus dispatches a suspending
`@Scheduled` method on a proper (duplicated) Vert.x context. That is the unanimous fleet
convention — every other reactive `@Scheduled` method in the monorepo is either `suspend` or
returns `Uni`. Prefer it over `VertxContextSupport.subscribeAndAwait`, which merely swaps one
blocking bridge for another, pins the scheduler worker for the whole run, and *throws* if ever
called from an event-loop thread (so it breaks the day the method gains `@NonBlocking` or a
virtual thread).

WHAT IT CHECKS: every `openbank-*/src/main/kotlin/**.kt`. For each `@Scheduled` annotation it
locates the annotated method, brace-matches its body, and flags `runBlocking` inside it — in
code, never in comments. A genuine exception (a service with no reactive persistence on its
classpath at all, where `runBlocking` is a deliberate bridge for *blocking* clients) must be
listed in `rules.yaml: scheduled_methods.runblocking_allowlist` with a one-line reason, the same
individually-justified-exception idiom as the ktlint/detekt baselines and
`event_consumer_liveness.allowlist`.

ENFORCED: findings are ::error:: annotations and exit 1.

stdlib + PyYAML (already installed earlier in the same CI job, matching
check-event-consumer-liveness.py).
Usage: check-no-runblocking-in-scheduled.py [--root .] [--rules openbank-libs/governance/rules.yaml]
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys

import yaml

SCHEDULED_RE = re.compile(r"^\s*@Scheduled\b")
FUN_RE = re.compile(r"\bfun\s+`?(\w+)`?\s*\(")
RUNBLOCKING_RE = re.compile(r"\brunBlocking\b")


def strip_comments(lines: list[str]) -> list[str]:
    """Blanks out `//` line comments and `/* … */` blocks, keeping line numbering intact.

    Needed in both directions: a fix comment explaining *why* the method must never use
    `runBlocking` (every one of the #2187 fixes carries one) must not itself trip the guard, and
    a real violation must not be hideable by trailing a comment marker.
    """
    out: list[str] = []
    in_block = False
    for line in lines:
        buf = []
        i = 0
        while i < len(line):
            two = line[i:i + 2]
            if in_block:
                if two == "*/":
                    in_block = False
                    i += 2
                    continue
                i += 1
                continue
            if two == "/*":
                in_block = True
                i += 2
                continue
            if two == "//":
                break
            buf.append(line[i])
            i += 1
        out.append("".join(buf))
    return out


def scheduled_method_bodies(lines: list[str]) -> list[tuple[str, int, int, int]]:
    """Every `@Scheduled` method in a file as (name, decl_line, body_start, body_end), 0-based.

    Walks from each `@Scheduled` to the `fun` that follows it (past any further annotations or a
    wrapped argument list), then brace-matches the body. An expression body (`= runBlocking { … }`)
    is matched the same way — the guard only cares which lines belong to the method.
    """
    found: list[tuple[str, int, int, int]] = []
    for idx, line in enumerate(lines):
        if not SCHEDULED_RE.match(line):
            continue
        decl = next(
            (j for j in range(idx, min(idx + 40, len(lines))) if FUN_RE.search(lines[j])),
            None,
        )
        if decl is None:
            continue
        name = FUN_RE.search(lines[decl]).group(1)

        # The body opens at the first `{` after the parameter list closes. Tracking paren depth
        # keeps a default-argument lambda or a wrapped signature from being mistaken for it.
        depth_paren = 0
        start = None
        for j in range(decl, len(lines)):
            for ch in lines[j]:
                if ch == "(":
                    depth_paren += 1
                elif ch == ")":
                    depth_paren -= 1
                elif ch == "{" and depth_paren <= 0:
                    start = j
                    break
            if start is not None:
                break
        if start is None:
            continue

        depth = 0
        end = None
        for j in range(start, len(lines)):
            depth += lines[j].count("{") - lines[j].count("}")
            if depth <= 0:
                end = j
                break
        found.append((name, decl, start, end if end is not None else len(lines) - 1))
    return found


def load_allowlist(rules_path: pathlib.Path) -> dict[str, str]:
    """`rules.yaml: scheduled_methods.runblocking_allowlist` as {"<path>#<method>": reason}."""
    data = yaml.safe_load(rules_path.read_text(encoding="utf-8")) or {}
    entries = (data.get("scheduled_methods") or {}).get("runblocking_allowlist") or []
    allow: dict[str, str] = {}
    for entry in entries:
        if isinstance(entry, dict) and "method" in entry:
            allow[str(entry["method"])] = str(entry.get("reason", ""))
    return allow


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--rules", default="openbank-libs/governance/rules.yaml")
    args = ap.parse_args()

    root = pathlib.Path(args.root)
    allow = load_allowlist(root / args.rules)
    used_allow: set[str] = set()

    sources = sorted(
        p for p in root.glob("openbank-*/src/main/kotlin/**/*.kt") if "/build/" not in str(p)
    )

    scheduled = 0
    fail = 0
    for path in sources:
        raw = path.read_text(encoding="utf-8").splitlines()
        if not any(SCHEDULED_RE.match(line) for line in raw):
            continue
        code = strip_comments(raw)
        rel = path.relative_to(root).as_posix()
        for name, _decl, start, end in scheduled_method_bodies(code):
            scheduled += 1
            hits = [j for j in range(start, end + 1) if RUNBLOCKING_RE.search(code[j])]
            if not hits:
                continue
            key = f"{rel}#{name}"
            if key in allow:
                used_allow.add(key)
                print(f"::notice file={rel},line={hits[0] + 1}::allowlisted: {allow[key]}")
                continue
            fail = 1
            for j in hits:
                print(
                    f"::error file={rel},line={j + 1}::runBlocking inside the @Scheduled method "
                    f"`{name}` — Quarkus invokes a non-suspend @Scheduled method on a bare "
                    f"executor-thread with no Vert.x context, so the first reactive Panache call "
                    f"throws HR000068 and the job silently does nothing (#2148, #2187). Make it a "
                    f"`suspend fun` (rules.yaml: scheduled_methods)."
                )

    for key in sorted(set(allow) - used_allow):
        fail = 1
        print(
            f"::error file={args.rules}::stale allowlist entry "
            f"`scheduled_methods.runblocking_allowlist: {key}` — that method no longer has "
            f"runBlocking in a @Scheduled body (or no longer exists). Remove it."
        )

    verdict = "clean." if fail == 0 else "VIOLATIONS above."
    print(
        f"check-no-runblocking-in-scheduled: {scheduled} @Scheduled method(s) checked across "
        f"{len(sources)} main-source file(s), {len(allow)} allowlisted — {verdict}"
    )
    return fail


if __name__ == "__main__":
    sys.exit(main())
