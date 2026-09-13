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

`runBlocking` is only the SPELLING the first version of this guard knew, and keying on a spelling
is how the same defect walked straight past it. `ConsentExpirationJob.sweepExpiredConsents()`
(#2913) reached the identical HR000068 with no `runBlocking` anywhere: it built a `Uni` and
`subscribe()`d it. That reads as "hand this off, it runs elsewhere" and does not — the
subscription starts on the *caller's* thread, which is the contextless executor-thread. The
hourly consent-expiration sweep had therefore never once succeeded, and the fleet sweep that
found it found two more in the same shape (notification-service's device-token sweep and its
dead-letter janitor). So the guard now checks the PROPERTY — a `@Scheduled` method that starts
reactive work must be `suspend` (or `Uni`-returning, the other shape Quarkus dispatches on a
proper context) — rather than one forbidden identifier.

WHAT IT CHECKS: every `openbank-*/src/main/kotlin/**.kt`. For each `@Scheduled` annotation it
locates the annotated method, brace-matches its body, and flags, in code and never in comments:

  1. `runBlocking` anywhere in the body, and
  2. any reactive entry point (`subscribe()`, `Panache.`, `Uni.`, `Multi.`, `awaitSuspending`,
     `await()`) in a method that is neither `suspend` nor declared to return `Uni<…>`.

A genuine exception (a service with no reactive persistence on its classpath at all, where
`runBlocking` is a deliberate bridge for *blocking* clients) must be listed in
`rules.yaml: scheduled_methods.runblocking_allowlist` — or, for (2),
`scheduled_methods.nonsuspend_reactive_allowlist` — with a one-line reason, the same
individually-justified-exception idiom as the ktlint/detekt baselines and
`event_consumer_liveness.allowlist`. Both allowlists fail on a stale entry, in either direction,
so an exception cannot quietly outlive its reason.

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

import gatelib

SCHEDULED_RE = re.compile(r"^\s*@Scheduled\b")
FUN_RE = re.compile(r"\bfun\s+`?(\w+)`?\s*\(")
RUNBLOCKING_RE = re.compile(r"\brunBlocking\b")

# Reactive work started from the method body. `runBlocking` is the *spelling* the original guard
# knew; these are the shapes that reach Hibernate Reactive without it — most importantly
# `subscribe()`, which reads as "fire and forget, off this thread" and is not: the subscription
# starts on the caller's thread, so the first Panache call still throws HR000068 (#2913).
REACTIVE_RE = re.compile(
    r"\.subscribe\(\)|\bPanache\.|\bUni\.|\bMulti\.|\bawaitSuspending\b|\.await\(\)"
)
# A `Uni<…>`-returning @Scheduled method is the OTHER shape Quarkus dispatches on a proper
# (duplicated) Vert.x context — it hands the Uni back to the scheduler rather than subscribing on
# the scheduler thread. Four services use it, and it is not a violation.
UNI_RETURN_RE = re.compile(r"\)\s*:\s*Uni\s*<")


def strip_comments(lines: list[str]) -> list[str]:
    """Blanks out `//` line comments and `/* … */` blocks, keeping line numbering intact.

    Needed in both directions: a fix comment explaining *why* the method must never use
    `runBlocking` (every one of the #2187 fixes carries one) must not itself trip the guard, and
    a real violation must not be hideable by trailing a comment marker.

    Block depth is COUNTED, not a boolean: Kotlin block comments NEST, so `/* a /* b */ c */`
    ends at the LAST marker, not the first. With a boolean the comment closed early and its
    remaining text was scanned as code — so a KDoc explaining "never wrap this in runBlocking"
    became a violation of the rule it documents. This repo has paid for that exact confusion
    once already (#2450), and it was found here by writing this script's first self-test.
    """
    out: list[str] = []
    depth = 0
    for line in lines:
        buf = []
        i = 0
        while i < len(line):
            two = line[i:i + 2]
            if depth > 0:
                if two == "*/":
                    depth -= 1
                    i += 2
                    continue
                if two == "/*":
                    depth += 1
                    i += 2
                    continue
                i += 1
                continue
            if two == "/*":
                depth += 1
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


def load_allowlist(rules_path: pathlib.Path, key: str = "runblocking_allowlist") -> dict[str, str]:
    """`rules.yaml: scheduled_methods.<key>` as {"<path>#<method>": reason}."""
    data = yaml.safe_load(rules_path.read_text(encoding="utf-8")) or {}
    entries = (data.get("scheduled_methods") or {}).get(key) or []
    allow: dict[str, str] = {}
    for entry in entries:
        if isinstance(entry, dict) and "method" in entry:
            allow[str(entry["method"])] = str(entry.get("reason", ""))
    return allow


def self_test() -> int:
    """Falsify the DETECTOR against Kotlin fixtures whose answer is known.

    This gate owns the defect that silently stopped five schedulers, three of them money-path
    (#2148, #2187): Quarkus invokes a non-suspend `@Scheduled` method on a bare executor-thread
    with no Vert.x context, so a `runBlocking { … }` around a reactive Panache call throws
    HR000068 and the job does nothing — before the per-item try/catch, so not even a log line.

    It shipped with no self-test, which for a guard is the same bug one level up: its RED path
    was code nobody had run. Every case below states what the detector must answer and why the
    wrong answer is expensive — a false negative reinstates the original outage class, and a
    false positive over a COMMENT is how a text guard gets disabled by the first person it
    annoys (this repo has burnt that too: Kotlin block comments NEST, #2450).
    """
    import tempfile

    fails: list[str] = []

    def flagged(src: str) -> bool:
        """Run the real pipeline — strip_comments + scheduled_method_bodies + RUNBLOCKING_RE —
        never a re-implementation of it. A fixture that goes through a copy of the logic proves
        nothing about the logic."""
        code = strip_comments(src.splitlines())
        for _name, _decl, start, end in scheduled_method_bodies(code):
            if any(RUNBLOCKING_RE.search(code[j]) for j in range(start, end + 1)):
                return True
        return False

    def case(label: str, src: str, want: bool) -> None:
        got = flagged(src)
        if got != want:
            fails.append(f"{label}: expected flagged={want}, got {got}")

    # THE DEFECT ITSELF. If this stops being flagged, the outage class is back.
    case("a plain @Scheduled with runBlocking must be FLAGGED", """
        @Scheduled(every = "60s")
        fun sweep() {
            runBlocking { repo.findDue() }
        }
    """, True)

    # The documented fix must read as clean, or the gate blocks the very shape it demands.
    case("a suspend @Scheduled without runBlocking is CLEAN", """
        @Scheduled(every = "60s")
        suspend fun sweep() {
            repo.findDue()
        }
    """, False)

    # Expression body — the same defect, a different spelling. `= runBlocking { … }` is exactly
    # the Kotlin idiom that also makes JUnit5 silently drop a test, so it is common here.
    case("an expression-body @Scheduled must be FLAGGED", """
        @Scheduled(every = "60s")
        fun sweep() = runBlocking { repo.findDue() }
    """, True)

    # SCOPE. runBlocking elsewhere in the same file is not this gate's business; flagging it
    # would make the gate unusable in any file that also contains a scheduler.
    case("runBlocking OUTSIDE any @Scheduled method is clean", """
        @Scheduled(every = "60s")
        suspend fun sweep() {
            repo.findDue()
        }
        fun helper() {
            runBlocking { repo.findDue() }
        }
    """, False)

    # PROSE. A guard that flags the comment explaining the rule gets deleted by the next person
    # who hits it. Kotlin block comments NEST, which is why stripping is not a one-liner.
    case("runBlocking named in a LINE comment is not a hit", """
        @Scheduled(every = "60s")
        suspend fun sweep() {
            // never wrap this in runBlocking { } — HR000068
            repo.findDue()
        }
    """, False)
    case("runBlocking inside a NESTED block comment is not a hit", """
        @Scheduled(every = "60s")
        suspend fun sweep() {
            /* outer /* inner */ runBlocking { } still comment */
            repo.findDue()
        }
    """, False)

    # A default-argument lambda opens a brace inside the parameter list. If the body finder
    # mistook it for the body, the real body would fall outside the scanned range and a genuine
    # hit would be missed — the failure that reads as a pass.
    case("a brace in the parameter list does not truncate the body", """
        @Scheduled(every = "60s")
        fun sweep(clock: () -> Long = { 0L }) {
            runBlocking { repo.findDue() }
        }
    """, True)

    # Several schedulers in one file: the second must still be seen.
    case("a second @Scheduled in the same file is still scanned", """
        @Scheduled(every = "60s")
        suspend fun first() {
            repo.a()
        }
        @Scheduled(every = "90s")
        fun second() {
            runBlocking { repo.b() }
        }
    """, True)

    # The allowlist is keyed "<path>#<method>", and a malformed rules.yaml must not silently
    # empty it — an empty allowlist turns every allowlisted scheduler red at once.
    with tempfile.TemporaryDirectory() as td:
        rules = pathlib.Path(td) / "rules.yaml"
        rules.write_text(
            "scheduled_methods:\n"
            "  runblocking_allowlist:\n"
            "    - method: 'openbank-x/src/main/kotlin/X.kt#sweep'\n"
            "      reason: 'documented'\n"
        )
        allow = load_allowlist(rules)
        if allow.get("openbank-x/src/main/kotlin/X.kt#sweep") != "documented":
            fails.append("allowlist entry did not load")
        empty = pathlib.Path(td) / "empty.yaml"
        empty.write_text("scheduled_methods: {}\n")
        if load_allowlist(empty) != {}:
            fails.append("an empty allowlist did not read as empty")

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print("self-test ok: the @Scheduled runBlocking detector is falsifiable (10 cases)")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--rules", default="openbank-libs/governance/rules.yaml")
    ap.add_argument("--self-test", action="store_true", help="falsify the detector, no repo scan")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    root = pathlib.Path(args.root)
    allow = load_allowlist(root / args.rules)
    reactive_allow = load_allowlist(root / args.rules, "nonsuspend_reactive_allowlist")
    used_allow: set[str] = set()
    used_reactive_allow: set[str] = set()

    sources = sorted(
        p for p in root.glob("openbank-*/src/main/kotlin/**/*.kt") if "/build/" not in str(p)
    )
    # The corpus is the FILE SET, not the 94 @Scheduled methods inside it: a moved source root
    # takes the files, and "no @Scheduled found" then reads exactly like a clean fleet.
    gatelib.subjects(len(sources), "main-source .kt files scanned")

    scheduled = 0
    fail = 0
    for path in sources:
        raw = path.read_text(encoding="utf-8").splitlines()
        if not any(SCHEDULED_RE.match(line) for line in raw):
            continue
        code = strip_comments(raw)
        rel = path.relative_to(root).as_posix()
        for name, decl, start, end in scheduled_method_bodies(code):
            scheduled += 1
            key = f"{rel}#{name}"
            signature = " ".join(code[decl:start + 1])
            suspending = "suspend" in signature.split("fun")[0]

            hits = [j for j in range(start, end + 1) if RUNBLOCKING_RE.search(code[j])]
            if hits:
                if key in allow:
                    used_allow.add(key)
                    print(f"::notice file={rel},line={hits[0] + 1}::allowlisted: {allow[key]}")
                else:
                    fail = 1
                    for j in hits:
                        print(
                            f"::error file={rel},line={j + 1}::runBlocking inside the @Scheduled "
                            f"method `{name}` — Quarkus invokes a non-suspend @Scheduled method on "
                            f"a bare executor-thread with no Vert.x context, so the first reactive "
                            f"Panache call throws HR000068 and the job silently does nothing "
                            f"(#2148, #2187). Make it a `suspend fun` "
                            f"(rules.yaml: scheduled_methods)."
                        )

            # Same defect, different spelling: a plain method that starts reactive work at all.
            if suspending or UNI_RETURN_RE.search(signature):
                continue
            reactive_hits = [j for j in range(start, end + 1) if REACTIVE_RE.search(code[j])]
            if not reactive_hits:
                continue
            if key in reactive_allow:
                used_reactive_allow.add(key)
                print(
                    f"::notice file={rel},line={reactive_hits[0] + 1}::allowlisted: "
                    f"{reactive_allow[key]}"
                )
                continue
            fail = 1
            print(
                f"::error file={rel},line={reactive_hits[0] + 1}::the @Scheduled method `{name}` is "
                f"neither `suspend` nor `Uni`-returning, yet starts reactive work — Quarkus invokes "
                f"it on a bare executor-thread with no Vert.x context, so the first reactive Panache "
                f"call throws HR000068 and the job silently does nothing. `subscribe()` does NOT "
                f"move it off that thread: the subscription starts on the caller's (#2913). Make it "
                f"a `suspend fun` and await the pipeline (rules.yaml: scheduled_methods)."
            )

    for key in sorted(set(allow) - used_allow):
        fail = 1
        print(
            f"::error file={args.rules}::stale allowlist entry "
            f"`scheduled_methods.runblocking_allowlist: {key}` — that method no longer has "
            f"runBlocking in a @Scheduled body (or no longer exists). Remove it."
        )

    for key in sorted(set(reactive_allow) - used_reactive_allow):
        fail = 1
        print(
            f"::error file={args.rules}::stale allowlist entry "
            f"`scheduled_methods.nonsuspend_reactive_allowlist: {key}` — that method no longer "
            f"starts reactive work from a non-suspend @Scheduled body (or no longer exists). "
            f"Remove it."
        )

    verdict = "clean." if fail == 0 else "VIOLATIONS above."
    print(
        f"check-no-runblocking-in-scheduled: {scheduled} @Scheduled method(s) checked across "
        f"{len(sources)} main-source file(s), {len(allow)} runBlocking-allowlisted, "
        f"{len(reactive_allow)} nonsuspend-reactive-allowlisted — {verdict}"
    )
    return fail


if __name__ == "__main__":
    sys.exit(main())
