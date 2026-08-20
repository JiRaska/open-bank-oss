#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# sourceService convention guard: the value a producer stamps on its own events must be its own
# module directory name minus the `openbank-` prefix (issues #5256, #5902).
#
# WHY THIS EXISTS
#   `source_service` is the column every audit/analytics query groups by — it is the documented way
#   to detect a producer that has stopped emitting, or one that never started. A producer whose
#   self-reported spelling disagrees with the fleet convention splits itself off from the topic
#   fallback table (`audit-service`'s `TopicAttribution`), which uses the module name. When a
#   producer later gains EVENT-sourced attribution, its rows change spelling MID-STREAM and every
#   group-by silently reports two producers where there is one, with the boundary at a merge date.
#   That happened: `openbank-lending-service` emits "lending", `TopicAttribution` says
#   "lending-service", and PR #5399 flipped six of its nine event types from the topic-derived value
#   to the event-derived one on 2026-08-18. See #5902.
#
# THE PROBE TRAP THIS CHECK EXISTS TO AVOID
#   `grep -c sourceService src/main` returns non-zero for all 21 producers, because it sees the
#   FIELD being mentioned — a KDoc paragraph, a data-class property, a consumer reading it back —
#   and never its VALUE. That probe reported full conformance while lending disagreed with the
#   convention at nine write sites. So this check resolves the emitted value at each WRITE SITE,
#   through whichever of the fleet's four idioms is in use, and refuses to guess.
#
# THE FOUR WRITE-SITE IDIOMS IN THIS FLEET (all four are load-bearing; a check that knows one
# reports "clean" about the other three)
#   1. Kotlin named arg / property default   `sourceService = "account-service"`
#   2. Map entry                             `"sourceService" to SOURCE_SERVICE`
#   3. Jackson node                          `node.put("sourceService", "customer-edge")`
#   4. Hand-built JSON string                `""""sourceService":"$SOURCE_SERVICE"}"""`
#   Idioms 2 and 4 usually name a `const val`, so the resolver follows a simple-name const within
#   the same module. A const name that resolves to more than one distinct value in a module is
#   reported UNRESOLVED rather than guessed — several modules carry an unrelated metrics-tag
#   `const val SERVICE = "fx"` next to their `SOURCE_SERVICE = "fx-service"`, and picking the wrong
#   one would produce a confident false verdict in either direction.
#
# PASS-THROUGH IS NOT A WRITE SITE
#   `"sourceService" to event.sourceService` (sepa-instant's publisher) and
#   `node["sourceService"]?.asText()` (the audit/analytics consumers) copy a value someone else
#   decided. They carry no literal, so they are not this check's subject; flagging them would make
#   the gate noise and silence is the correct verdict.
#
# WHY LENDING IS AN EXCEPTION — A SETTLED DECISION, NOT DEBT
#   `openbank-lending-service` emits "lending". That is DECIDED and kept (#5902): the boundary it
#   leaves in `audit_entries` is documented in `openbank-lending-service/CLAUDE.md` — which three
#   aliases are one producer, which six event types and which nine days the "lending-service" window
#   covers, and the query that reconciles them. Nothing here is waiting on anyone.
#
#   Renaming was rejected on cost, and the cost is structural rather than a matter of effort:
#   `audit_entries` is append-only AT THE DATABASE (V2's `no_update_audit` / `no_delete_audit` rules
#   are `DO INSTEAD NOTHING`, so a normalising UPDATE touches zero rows and reports success), and
#   `source_service` is hashed into the ADR-0031/0133 `record_hash` chain, so rewriting it would
#   break tamper-evidence for every affected row and every row after it. The existing rows cannot be
#   converged by anyone, ever; a rename would only add a FOURTH boundary on top of them.
#
#   So this entry is not a TODO and should not be "cleaned up" — a future reader finding it must not
#   read it as a rename nobody got around to. It stays until someone deliberately reopens #5902, and
#   deleting it is what that reopening looks like. It pins the (module, VALUE) pair, so lending
#   drifting to some third spelling still fails the gate — that is the property this entry exists to
#   keep, and it survives independently of the decision above.
#
# EXIT CODES
#   0  every producer's emitted value equals its module directory name (modulo the baseline)
#   1  a producer emits a value that is not its module name; or a write site whose value cannot be
#      resolved; or a baseline entry that no longer matches anything (the reverse ratchet)
#   2  the check could not run: the tree is missing, or fewer than --min-producers producers were
#      found. Never conflated with 0 — an enumeration that finds nothing is a broken probe, not a
#      clean fleet, and that is exactly how the original gap stayed invisible.
#
# Run:  python3 .github/scripts/check-source-service-convention.py [--root .] [--self-test]

import argparse
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gatelib  # noqa: E402  — run-gates.py reads the SUBJECTS line this emits

MODULE_PREFIX = "openbank-"

# Producers on origin/main as of 2026-08-20 (measured by this script): 21. A run finding materially
# fewer than this has not measured the fleet — it has failed to enumerate it. The floor is one below
# today's count so a service legitimately retiring its events does not red the gate, and far enough
# above zero that a broken enumeration cannot pass vacuously.
MIN_PRODUCERS_DEFAULT = 20

# (module, emitted value) -> why it is tolerated. Pinning the VALUE, not just the module, is what
# keeps the entry from absorbing a future third spelling from the same producer.
BASELINE = {
    ("openbank-lending-service", "lending"): (
        "#5902 DECIDED: lending keeps \"lending\" and the audit_entries boundary is documented "
        "instead (openbank-lending-service/CLAUDE.md). NOT debt, and NOT a rename awaiting "
        "cleanup — the existing rows are unrewritable by anyone (audit_entries is append-only at "
        "the DB and source_service is chain-hashed), so a rename could only add a fourth boundary "
        "on top of a split that can never be repaired. This entry is permanent unless #5902 is "
        "deliberately reopened. It pins the VALUE, so a third spelling from this module still fails."
    ),
}

# A line that is entirely a comment carries no write site. Stripping them is the whole reason this
# check can be stricter than the grep that missed the gap: KDoc in this fleet discusses
# `sourceService` far more often than code sets it.
_COMMENT_LINE = re.compile(r"^\s*(//|\*|/\*)")

# Idioms 1-3: the RHS is a Kotlin expression.
_KOTLIN_SITES = (
    # The optional `: String` matters: four producers declare the property as
    #   `val sourceService: String = SOURCE_SERVICE`
    # and a pattern without it enumerated 17 of the 21 producers while printing a confident OK —
    # the same "the probe cannot express the case, so it reports clean" failure this whole check
    # exists to close. The MIN_PRODUCERS floor below is what turned that into a visible number.
    re.compile(r"\bsourceService\s*(?::\s*[A-Za-z_][A-Za-z0-9_.<>?]*\s*)?=\s*([^,)\n]+)"),
    re.compile(r'"sourceService"\s+to\s+([^,)\n]+)'),
    re.compile(r'\bput\(\s*"sourceService"\s*,\s*([^,)\n]+)\)'),
)
# Idiom 4: the value sits inside a hand-built JSON string, so it is already quoted.
_JSON_SITE = re.compile(r'"sourceService"\s*:\s*\\?"([^"\\]*)\\?"')

_STRING_LITERAL = re.compile(r'^"([^"]*)"$')
_CONST_REF = re.compile(r"^(?:[A-Za-z_][A-Za-z0-9_]*\.)*([A-Z][A-Z0-9_]*)$")
_INTERPOLATION = re.compile(r"^\$\{?(?:[A-Za-z_][A-Za-z0-9_]*\.)*([A-Z][A-Z0-9_]*)\}?$")
_CONST_DECL = re.compile(r'\bconst\s+val\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*String\s*)?=\s*"([^"]*)"')


def module_consts(module: pathlib.Path):
    """simple const name -> set of distinct string values declared anywhere in the module's main."""
    consts = {}
    for f in sorted((module / "src" / "main").rglob("*.kt")):
        for line in f.read_text(encoding="utf-8", errors="replace").splitlines():
            if _COMMENT_LINE.match(line):
                continue
            m = _CONST_DECL.search(line)
            if m:
                consts.setdefault(m.group(1), set()).add(m.group(2))
    return consts


def _resolve(expr: str, consts):
    """(value, None) when the emitted value is knowable, (None, reason) when it is not.

    (None, None) means "not a write site" — a pass-through of somebody else's value.
    """
    expr = expr.strip().rstrip(",")
    lit = _STRING_LITERAL.match(expr)
    if lit:
        return lit.group(1), None
    ref = _CONST_REF.match(expr) or _INTERPOLATION.match(expr)
    if ref:
        values = consts.get(ref.group(1))
        if not values:
            return None, f"references {ref.group(1)}, which no `const val` in this module declares"
        if len(values) > 1:
            return None, (
                f"references {ref.group(1)}, which this module declares with "
                f"{len(values)} different values ({', '.join(sorted(values))}) — ambiguous"
            )
        return next(iter(values)), None
    return None, None  # pass-through: event.sourceService, node[...], a parameter, ...


def scan(root):
    """-> (findings, matched_baseline_keys, producers)

    producers is the set of modules with at least one resolvable write site, i.e. the check's real
    subject count. It is returned so the caller can refuse a vacuous run.
    """
    root = pathlib.Path(root)
    findings = []
    matched = set()
    producers = set()

    for module in sorted(root.glob(f"{MODULE_PREFIX}*")):
        if not (module / "src" / "main").is_dir():
            continue
        expected = module.name[len(MODULE_PREFIX):]
        consts = None
        for f in sorted((module / "src" / "main").rglob("*.kt")):
            for lineno, line in enumerate(f.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
                if _COMMENT_LINE.match(line) or "sourceService" not in line:
                    continue
                exprs = [(m.group(1), False) for pat in _KOTLIN_SITES for m in pat.finditer(line)]
                exprs += [(m.group(1), True) for m in _JSON_SITE.finditer(line)]
                for expr, already_quoted in exprs:
                    if consts is None:
                        consts = module_consts(module)
                    if already_quoted:
                        interp = _INTERPOLATION.match(expr.strip())
                        value, why = _resolve(expr, consts) if interp else (expr, None)
                    else:
                        value, why = _resolve(expr, consts)
                    where = f"{f.relative_to(root)}:{lineno}"
                    if value is None:
                        if why is None:
                            continue  # pass-through, not a write site
                        findings.append(f"{module.name}: {where} UNRESOLVED — {why}")
                        producers.add(module.name)
                        continue
                    producers.add(module.name)
                    if value == expected:
                        continue
                    key = (module.name, value)
                    if key in BASELINE:
                        matched.add(key)
                        continue
                    findings.append(
                        f'{module.name}: {where} emits sourceService="{value}", '
                        f'but the module directory says "{expected}"',
                    )
    return findings, matched, producers


# ---------------------------------------------------------------------------------------------
# Self-test. Drives the REAL scan() over a synthetic tree — never a retyped classifier, which is
# how a self-test ends up unable to see the rule it was written to guard.

SELF_TEST_MODULES = {
    # module dir -> {relative kt path: source}. Expected value is the dir minus openbank-.
    "openbank-ok-literal-service": {"E.kt": '    sourceService = "ok-literal-service",\n'},
    "openbank-ok-const-service": {
        "E.kt": 'private const val SOURCE_SERVICE = "ok-const-service"\nval m = mapOf("sourceService" to SOURCE_SERVICE)\n',
    },
    "openbank-ok-json-service": {
        "E.kt": 'private const val SOURCE_SERVICE = "ok-json-service"\nval s = """"sourceService":"$SOURCE_SERVICE"}"""\n',
    },
    "openbank-ok-put-service": {"E.kt": '    node.put("sourceService", "ok-put-service")\n'},
    # The idiom that the first draft of this check could not see at all (4 of 21 producers).
    "openbank-ok-typed-service": {
        "E.kt": 'private const val SOURCE_SERVICE = "ok-typed-service"\nval sourceService: String = SOURCE_SERVICE\n',
    },
    "openbank-bad-typed-service": {
        "E.kt": 'private const val SOURCE_SERVICE = "bad-typed"\nval sourceService: String = SOURCE_SERVICE\n',
    },
    "openbank-bad-literal-service": {"E.kt": '    sourceService = "bad-literal",\n'},
    "openbank-bad-const-service": {
        "E.kt": 'private const val SOURCE_SERVICE = "bad-const"\nval m = mapOf("sourceService" to SOURCE_SERVICE)\n',
    },
    "openbank-bad-json-service": {"E.kt": 'val s = """"sourceService":"bad-json"}"""\n'},
    # Must NOT be flagged: a pass-through copies a value this module did not choose.
    "openbank-passthrough-service": {"E.kt": '    mapOf("sourceService" to event.sourceService)\n'},
    # Must NOT be flagged, and must NOT count as a producer: the grep trap that hid the gap. Every
    # line here mentions the field, including a commented-out wrong assignment.
    "openbank-prose-only-service": {
        "E.kt": (
            "/**\n"
            " * `sourceService` is the producer's own claim. See [X.sourceService].\n"
            ' * Historically this was sourceService = "wrong-on-purpose",\n'
            " */\n"
            "class X { val notTheField = 1 }\n"
        ),
    },
    # Ambiguous const: two declarations, one value each, same simple name. Must be UNRESOLVED, not
    # a coin flip — the shape that exists live (fx-service's SERVICE="fx" vs SOURCE_SERVICE).
    "openbank-ambiguous-service": {
        "A.kt": 'private const val SERVICE = "ambiguous-service"\nval m = mapOf("sourceService" to SERVICE)\n',
        "B.kt": 'private const val SERVICE = "something-else"\n',
    },
}

# module -> must it appear in the findings?
SELF_TEST_EXPECT = {
    "openbank-ok-literal-service": False,
    "openbank-ok-const-service": False,
    "openbank-ok-json-service": False,
    "openbank-ok-put-service": False,
    "openbank-ok-typed-service": False,
    "openbank-bad-typed-service": True,
    "openbank-bad-literal-service": True,
    "openbank-bad-const-service": True,
    "openbank-bad-json-service": True,
    "openbank-passthrough-service": False,
    "openbank-prose-only-service": False,
    "openbank-ambiguous-service": True,
}

# Modules with a resolvable write site. prose-only and passthrough must NOT be counted, or the
# vacuity floor could be satisfied by modules the check never actually measured.
SELF_TEST_PRODUCERS = {m for m in SELF_TEST_MODULES if m not in ("openbank-prose-only-service", "openbank-passthrough-service")}


def _build_self_test_tree(root):
    for module, files in SELF_TEST_MODULES.items():
        d = root / module / "src" / "main" / "kotlin"
        d.mkdir(parents=True, exist_ok=True)
        for name, src in files.items():
            (d / name).write_text(src, encoding="utf-8")


def self_test():
    import tempfile

    global BASELINE
    saved = BASELINE
    cases = []
    try:
        BASELINE = {}
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            _build_self_test_tree(root)
            findings, _, producers = scan(root)

            flagged = {m for m in SELF_TEST_EXPECT if any(f.startswith(m + ":") for f in findings)}
            for module, want in sorted(SELF_TEST_EXPECT.items()):
                got = module in flagged
                cases.append((f"{module} -> {'flag' if want else 'allow'}", got == want))
            cases.append((
                "no finding about a module we never declared",
                len(flagged) == len(findings and flagged) and all(
                    any(f.startswith(m + ":") for m in SELF_TEST_EXPECT) for f in findings
                ),
            ))
            cases.append(("producer enumeration excludes prose-only and pass-through", producers == SELF_TEST_PRODUCERS))

            # A baseline entry silences its own VALUE only.
            BASELINE = {("openbank-bad-literal-service", "bad-literal"): "self-test"}
            f2, matched2, _ = scan(root)
            cases.append((
                "baseline silences its own (module, value)",
                not any(f.startswith("openbank-bad-literal-service:") for f in f2) and matched2 == set(BASELINE),
            ))
            cases.append((
                "baseline does NOT silence a different producer",
                any(f.startswith("openbank-bad-const-service:") for f in f2),
            ))

            # A baseline pinned to a DIFFERENT value from the same module must not silence it —
            # this is what stops the entry absorbing a future third spelling.
            BASELINE = {("openbank-bad-literal-service", "some-third-spelling"): "self-test"}
            f3, matched3, _ = scan(root)
            cases.append((
                "baseline pinned to another value does not silence the module",
                any(f.startswith("openbank-bad-literal-service:") for f in f3),
            ))
            cases.append(("a baseline matching nothing comes back stale", matched3 == set()))

            # Vacuity: an emptied tree must refuse, not pass.
            with tempfile.TemporaryDirectory() as empty:
                BASELINE = {}
                _, _, no_producers = scan(empty)
                cases.append(("empty tree yields zero producers", no_producers == set()))
                cases.append((
                    "run() refuses a vacuous tree with exit 2",
                    run(empty, MIN_PRODUCERS_DEFAULT, quiet=True) == 2,
                ))
    finally:
        BASELINE = saved

    cases.append((
        "shipped BASELINE is well-formed",
        all(isinstance(k, tuple) and len(k) == 2 and k[0].startswith(MODULE_PREFIX) and v for k, v in BASELINE.items()),
    ))

    failures = 0
    for label, ok in cases:
        print(f"{'pass' if ok else 'FAIL'}  {label}")
        failures += 0 if ok else 1
    print(f"\nself-test: {len(cases) - failures} passed, {failures} failed")
    return 0 if failures == 0 else 2


def run(root, min_producers, quiet=False):
    if not pathlib.Path(root).is_dir():
        if not quiet:
            print(f"::error::root {root} does not exist — the check could not run. This is NOT a pass.")
        return 2

    findings, matched, producers = scan(root)

    # Unconditional, and BEFORE the floor check: a gate that found its corpus and then failed on it
    # must not also be reported as having lost its corpus (gatelib.subjects' own rule).
    if not quiet:
        gatelib.subjects(len(producers), "modules with a resolvable sourceService write site")

    if len(producers) < min_producers:
        if not quiet:
            print(
                f"::error::enumerated only {len(producers)} producer(s) with a resolvable "
                f"sourceService write site, expected at least {min_producers}. The enumeration is "
                f"broken (moved tree, changed idiom, bad --root) — a fleet that emits nothing is "
                f"not a fleet that conforms.",
            )
        return 2

    stale = [k for k in BASELINE if k not in matched]
    if quiet:
        return 1 if (findings or stale) else 0

    for f in findings:
        print(f"FAIL  {f}")
    for module, value in stale:
        print(
            f'STALE  baseline entry ({module}, "{value}") no longer matches anything — the producer '
            f"was fixed, renamed or removed.\n"
            f"       Remove it from BASELINE in this script so the list keeps meaning something.",
        )
    if findings or stale:
        print(
            f"\n{len(findings)} non-conforming write site(s), {len(stale)} stale baseline entr(ies) "
            f"across {len(producers)} producer(s).\n"
            f"The value must be the module directory name without the `{MODULE_PREFIX}` prefix "
            f"(#5256), because that is what audit-service's TopicAttribution fallback uses; a "
            f"disagreement splits one producer into two in every group-by (#5902).",
        )
        return 1
    print(
        f"sourceService convention: OK — {len(producers)} producer(s) checked, "
        f"{len(BASELINE)} declared exception(s). See #5902 and BASELINE above: the exception is a "
        f"settled decision with a documented boundary, not pending work.",
    )
    return 0


def main():
    parser = argparse.ArgumentParser(description="sourceService convention guard (#5256, #5902)")
    parser.add_argument("--root", default=".")
    parser.add_argument("--min-producers", type=int, default=MIN_PRODUCERS_DEFAULT)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    return run(args.root, args.min_producers)


if __name__ == "__main__":
    sys.exit(main())
