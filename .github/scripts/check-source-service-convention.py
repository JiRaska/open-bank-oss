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
# PASS-THROUGH IS NOT A WRITE SITE — BUT IT IS A WHITELIST, NOT A FALLBACK
#   `"sourceService" to event.sourceService` (sepa-instant's publisher) and
#   `node["sourceService"]?.asText()` (the audit/analytics consumers) copy a value someone else
#   decided. They carry no literal, so they are not this check's subject; flagging them would make
#   the gate noise and silence is the correct verdict.
#
#   The direction of the default is the load-bearing part. `_resolve` used to end in
#   `return None, None`, so ANY expression it could not parse was reclassified as a pass-through:
#   the site produced 0 findings AND 0 producers, so the module disappeared from the check
#   entirely — invisible AND uncounted, which also put it beneath the MIN_PRODUCERS floor that is
#   supposed to catch exactly this. Two ordinary Kotlin shapes reproduced it, and in both the
#   module decides the value it emits:
#       `"sourceService" to configuredSource`   // a @ConfigProperty-injected field
#       `"sourceService" to sourceServiceName`  // a lowercase, non-`const` val
#
#   So the rule is now: a subject this gate cannot parse must never be indistinguishable from a
#   subject that passed. Recognised pass-throughs (_PASSTHROUGH) are silent; everything else that
#   cannot be resolved either FAILS, or appears in UNRESOLVED_ALLOWED with a written reason and is
#   still COUNTED as a measured subject. Both lists are reverse ratchets, so neither can quietly
#   outlive the code it describes.
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
#      resolved and is not an acknowledged skip; or a BASELINE / UNRESOLVED_ALLOWED entry that no
#      longer matches anything (the reverse ratchet)
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
    ("openbank-analytics-sink", "account-service"): (
        "#8792/#2891: NOT a producer stamping its own events. AccountInitialLoadSource projects "
        "account-service's CREATION event for accounts that pre-date the stream, so the row it "
        "writes describes a business fact account-service produced — the projection reconstructs "
        "what that service emitted, through the same mapping and masking as the live consumer. "
        "Stamping \"analytics-sink\" would create exactly the split this gate exists to prevent, "
        "in the opposite direction: bronze already holds 19 real AccountCreated rows saying "
        "\"account-service\", and the 66 seeded ones would report a second producer for one "
        "stream, with the boundary at whichever day an operator ran the load. Provenance of the "
        "INGESTION is carried by ingest_source=INITIAL_LOAD and batch_id, which are separate "
        "columns and exist precisely so source_service does not have to double as one. It pins "
        "the VALUE, so this module drifting to any other spelling still fails."
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
    # `(?<![=!<>])=(?!=)` — an ASSIGNMENT, never a comparison. Without it,
    # `if (envelope.sourceService == UNKNOWN_SERVICE)` parsed as a write site emitting
    # "= UNKNOWN_SERVICE": the regex ate the first `=` as the assign and captured the rest.
    # A comparison is a READ, and reporting one is the noise that gets a gate switched off.
    re.compile(r"\bsourceService\s*(?::\s*[A-Za-z_][A-Za-z0-9_.<>?]*\s*)?(?<![=!<>])=(?!=)\s*([^,)\n]+)"),
    re.compile(r'"sourceService"\s+to\s+([^,)\n]+)'),
    re.compile(r'\bput\(\s*"sourceService"\s*,\s*([^,)\n]+)\)'),
)
# Idiom 4: the value sits inside a hand-built JSON string, so it is already quoted.
_JSON_SITE = re.compile(r'"sourceService"\s*:\s*\\?"([^"\\]*)\\?"')

_STRING_LITERAL = re.compile(r'^"([^"]*)"$')
_CONST_REF = re.compile(r"^(?:[A-Za-z_][A-Za-z0-9_]*\.)*([A-Z][A-Z0-9_]*)$")
_INTERPOLATION = re.compile(r"^\$\{?(?:[A-Za-z_][A-Za-z0-9_]*\.)*([A-Z][A-Z0-9_]*)\}?$")
_CONST_DECL = re.compile(r'\bconst\s+val\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*String\s*)?=\s*"([^"]*)"')

# A RECOGNISED pass-through: the expression reads a `sourceService` that somebody else already
# decided — `event.sourceService`, `cmd.payload?.sourceService`, a bare `sourceService` parameter.
# This is a WHITELIST on purpose, and the direction matters more than the pattern: see _resolve.
_PASSTHROUGH = re.compile(
    r"^(?:"
    # `event.sourceService`, `cmd.payload?.sourceService`, a bare `sourceService` parameter
    r"(?:[A-Za-z_][A-Za-z0-9_]*\s*[?!]?\s*[.]\s*)*sourceService\s*[?!]*"
    r"|"
    # the inbound-envelope read the module header already names as a pass-through:
    # `node["sourceService"]?.asText(`, `node.textOrNull("sourceService")`
    r"[A-Za-z_][A-Za-z0-9_]*\s*(?:\[\s*\"sourceService\"\s*\]|\.\s*[A-Za-z_][A-Za-z0-9_]*\s*\(\s*\"sourceService\")"
    r".*"
    r")$",
)

# Write sites whose value this gate genuinely cannot resolve AND which have been looked at.
# (module, expression) -> why it is knowingly skipped.
#
# This exists so an unresolvable site is COUNTED and NAMED rather than silently absent. It is a
# reverse ratchet like BASELINE: an entry that stops matching is reported STALE, so the list
# cannot quietly outlive the code it describes. Adding to it is a decision someone has to write
# down — which is the property the old `return None, None` destroyed.
UNRESOLVED_ALLOWED = {
    ("openbank-audit-service", "resolvedSource.first"): (
        "audit-service is the CONSUMER here, not a producer of its own claim. "
        "`resolveSourceService(node, address)` (AuditConsumer.kt:218) returns the event's own "
        "sourceService, else TopicAttribution's topic-derived value, else \"unknown\" — all three "
        "are somebody else's attribution being copied onto the row. The pair is destructured "
        "one line later into `sourceServiceSource`, which records WHICH of the three it was. "
        "Not whitelisted by shape: `<val>.first` is far too generic to be a safe pass-through "
        "pattern, so it is pinned to this module and this expression instead."
    ),
}


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


def file_consts(path: pathlib.Path):
    """const name -> set of distinct string values declared in THIS FILE alone."""
    consts = {}
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if _COMMENT_LINE.match(line):
            continue
        m = _CONST_DECL.search(line)
        if m:
            consts.setdefault(m.group(1), set()).add(m.group(2))
    return consts


def _resolve(expr: str, consts, own_file_consts=None):
    """(value, None) when the emitted value is knowable, (None, reason) when it is not.

    (None, None) means "not a write site" — a RECOGNISED pass-through of somebody else's value.

    THE DEFAULT IS "REPORT", NOT "SKIP", AND THAT IS THE WHOLE POINT
      This used to end in `return None, None`, so every expression the resolver could not parse
      was silently reclassified as a pass-through. That made an unparseable write site produce
      0 findings AND 0 producers — invisible AND uncounted, indistinguishable from a module that
      genuinely conforms. Two shapes reproduced it, both of them ordinary Kotlin:

        `"sourceService" to configuredSource`   // a @ConfigProperty-injected field
        `"sourceService" to sourceServiceName`  // a lowercase, non-`const` val

      Neither is a pass-through — the module decides the value in both — and either could have
      emitted any spelling at all without this gate noticing. Worse, because the module was never
      counted as a producer, the MIN_PRODUCERS floor could not see the loss either: the one
      mechanism that exists here to catch a broken enumeration was itself blind to it.

      So the pass-through set is now a WHITELIST (_PASSTHROUGH: an expression that reads a
      `sourceService` somebody else set), and anything else that cannot be resolved is REPORTED
      with the expression quoted. A subject the gate cannot parse must never be indistinguishable
      from a subject that passed — if it cannot be resolved it fails, or it is counted as an
      explicit skip with a reason. It is never silently absent.
    """
    expr = expr.strip().rstrip(",")
    lit = _STRING_LITERAL.match(expr)
    if lit:
        return lit.group(1), None
    ref = _CONST_REF.match(expr) or _INTERPOLATION.match(expr)
    if ref:
        # A bare identifier in Kotlin resolves to the declaration in its OWN FILE before anything
        # else, and a `private const val` is file-private besides — so a same-named constant in a
        # sibling file cannot be what this line reads. Resolving module-wide first reported two
        # live sites as ambiguous while both were correct: kyc-service declares SERVICE both as a
        # metric label ("kyc", in OrphanedPartyGauge) and as its event source ("kyc-service", in
        # KycEvent), and domestic-payment declares SOURCE_SERVICE both for the inbound producer it
        # VALIDATES ("delegation-service", in DelegatedSpendBinding) and for its own outbound event
        # ("domestic-payment"). Neither is a naming defect; both were this resolver guessing.
        #
        # This preference cannot launder a wrong value: the file's own declaration is what the code
        # actually emits, so a file declaring the wrong spelling is still flagged even when a
        # correct constant of the same name exists elsewhere in the module. `openbank-samefile-bad-
        # service` in the self-test is that negative control.
        own = (own_file_consts or {}).get(ref.group(1))
        if own and len(own) == 1:
            return next(iter(own)), None
        values = consts.get(ref.group(1))
        if not values:
            return None, f"references {ref.group(1)}, which no `const val` in this module declares"
        if len(values) > 1:
            return None, (
                f"references {ref.group(1)}, which this module declares with "
                f"{len(values)} different values ({', '.join(sorted(values))}) — ambiguous"
            )
        return next(iter(values)), None
    if _PASSTHROUGH.match(expr):
        return None, None  # reads a sourceService someone else decided — not this module's claim
    return None, (
        f"value `{expr}` is not a string literal, a `const val` this module declares, or a "
        f"recognised pass-through of somebody else's sourceService — this gate cannot tell what "
        f"this producer emits. Emit a literal or a module-level `const val`, or extend "
        f"_PASSTHROUGH in this script if it really is a pass-through."
    )


def scan(root):
    """-> (findings, matched_baseline_keys, producers, matched_skip_keys)

    producers is the set of modules with at least one write site this check actually MEASURED —
    resolved, or explicitly acknowledged as unresolvable. A site it could not parse and did not
    acknowledge is a finding, never an absence, so the subject count can no longer shrink
    silently when an idiom changes.
    """
    root = pathlib.Path(root)
    findings = []
    matched = set()
    producers = set()
    matched_skips = set()

    for module in sorted(root.glob(f"{MODULE_PREFIX}*")):
        if not (module / "src" / "main").is_dir():
            continue
        expected = module.name[len(MODULE_PREFIX):]
        consts = None
        for f in sorted((module / "src" / "main").rglob("*.kt")):
            own_consts = None
            for lineno, line in enumerate(f.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
                if _COMMENT_LINE.match(line) or "sourceService" not in line:
                    continue
                exprs = [(m.group(1), False) for pat in _KOTLIN_SITES for m in pat.finditer(line)]
                exprs += [(m.group(1), True) for m in _JSON_SITE.finditer(line)]
                for expr, already_quoted in exprs:
                    if consts is None:
                        consts = module_consts(module)
                    if own_consts is None:
                        own_consts = file_consts(f)
                    if already_quoted:
                        interp = _INTERPOLATION.match(expr.strip())
                        value, why = _resolve(expr, consts, own_consts) if interp else (expr, None)
                    else:
                        value, why = _resolve(expr, consts, own_consts)
                    where = f"{f.relative_to(root)}:{lineno}"
                    if value is None:
                        if why is None:
                            continue  # pass-through, not a write site
                        skip_key = (module.name, expr.strip().rstrip(","))
                        if skip_key in UNRESOLVED_ALLOWED:
                            # Acknowledged, and still COUNTED — an explicit skip is a measured
                            # subject, unlike the silent one this replaced.
                            matched_skips.add(skip_key)
                            producers.add(module.name)
                            continue
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
    return findings, matched, producers, matched_skips


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
    # Ambiguous const: two declarations, one value each, same simple name, and the USE is in a
    # third file that declares neither. Must be UNRESOLVED, not a coin flip. The use deliberately
    # sits away from both declarations — once the resolver prefers the referencing file's own
    # constant, a use next to one of them is no longer ambiguous, and this case must keep testing
    # the ambiguity rather than quietly becoming a duplicate of the same-file case below.
    "openbank-ambiguous-service": {
        "A.kt": 'private const val SERVICE = "ambiguous-service"\n',
        "B.kt": 'private const val SERVICE = "something-else"\n',
        "C.kt": 'val m = mapOf("sourceService" to SERVICE)\n',
    },
    # Same simple name declared twice in one module, and the USE sits beside the RIGHT one. This is
    # the live shape that reddened `main`: kyc-service declares SERVICE as a metric label ("kyc")
    # in one file and as its event source ("kyc-service") in another, and domestic-payment does the
    # same with SOURCE_SERVICE for an inbound producer it validates versus its own outbound event.
    # Kotlin resolves the reference to the declaration in its own file, so both were correct code
    # and the module-wide lookup was guessing. Must NOT be flagged.
    "openbank-samefile-service": {
        "A.kt": 'private const val SERVICE = "samefile-service"\nval m = mapOf("sourceService" to SERVICE)\n',
        "B.kt": 'private const val SERVICE = "a-metric-label"\n',
    },
    # The negative control for that preference, and the reason it is safe. The use sits beside the
    # WRONG value while a correct constant of the same name exists elsewhere in the module. If
    # file-scope resolution could be used to launder a wrong spelling, this would pass. It must be
    # flagged.
    "openbank-samefile-bad-service": {
        "A.kt": 'private const val SERVICE = "wrong-spelling"\nval m = mapOf("sourceService" to SERVICE)\n',
        "B.kt": 'private const val SERVICE = "samefile-bad-service"\n',
    },
    # THE TWO NEGATIVE CONTROLS THAT REPRODUCED THE PASS-THROUGH HOLE.
    # Before the fix each of these produced 0 findings AND 0 producers: the value was unparseable,
    # `_resolve` fell through to `return None, None`, and the module vanished from the check
    # entirely — invisible AND uncounted, so even the MIN_PRODUCERS floor could not see the loss.
    # Both are ordinary Kotlin, and in both the MODULE decides the value; neither is a pass-through.
    "openbank-configprop-service": {
        "E.kt": (
            '@ConfigProperty(name = "openbank.audit.source-service")\n'
            "lateinit var configuredSource: String\n"
            'val m = mapOf("sourceService" to configuredSource)\n'
        ),
    },
    "openbank-lowercaseval-service": {
        "E.kt": (
            'private val sourceServiceName = "totally-wrong"\n'
            'val m = mapOf("sourceService" to sourceServiceName)\n'
        ),
    },
    # Must NOT be flagged: a COMPARISON is a read. `sourceService == UNKNOWN_SERVICE` parsed as a
    # write site emitting "= UNKNOWN_SERVICE" until the assignment pattern excluded `==`
    # (live shape: analytics-sink's IngestAttributionMetrics).
    "openbank-comparison-service": {
        "E.kt": 'fun f(e: E) = e.sourceService == UNKNOWN_SERVICE\n',
    },
    # Must NOT be flagged: reading the field back off an inbound envelope, the shape the module
    # header has always called a pass-through (analytics-sink / audit-service consumers).
    "openbank-nodereader-service": {
        "E.kt": (
            'val a = node["sourceService"]?.asText() ?: UNKNOWN\n'
            'val b = node.textOrNull("sourceService") ?: UNKNOWN\n'
        ),
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
    "openbank-samefile-service": False,
    "openbank-samefile-bad-service": True,
    "openbank-configprop-service": True,
    "openbank-lowercaseval-service": True,
    "openbank-comparison-service": False,
    "openbank-nodereader-service": False,
}

# Modules with a resolvable write site. prose-only and passthrough must NOT be counted, or the
# vacuity floor could be satisfied by modules the check never actually measured.
SELF_TEST_PRODUCERS = {
    m
    for m in SELF_TEST_MODULES
    if m
    not in (
        "openbank-prose-only-service",
        "openbank-passthrough-service",
        # A comparison and an inbound read are not write sites, so neither makes its module a
        # producer. They are here to prove the fix did not buy its strictness with noise.
        "openbank-comparison-service",
        "openbank-nodereader-service",
    )
}


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
            findings, _, producers, _ = scan(root)

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
            f2, matched2, _, _ = scan(root)
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
            f3, matched3, _, _ = scan(root)
            cases.append((
                "baseline pinned to another value does not silence the module",
                any(f.startswith("openbank-bad-literal-service:") for f in f3),
            ))
            cases.append(("a baseline matching nothing comes back stale", matched3 == set()))

            # Vacuity: an emptied tree must refuse, not pass.
            with tempfile.TemporaryDirectory() as empty:
                BASELINE = {}
                _, _, no_producers, _ = scan(empty)
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

    findings, matched, producers, matched_skips = scan(root)

    # Unconditional, and BEFORE the floor check: a gate that found its corpus and then failed on it
    # must not also be reported as having lost its corpus (gatelib.subjects' own rule).
    if not quiet:
        gatelib.subjects(len(producers), "modules with a measured sourceService write site (resolved, or acknowledged-unresolvable)")

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
    stale_skips = [k for k in UNRESOLVED_ALLOWED if k not in matched_skips]
    if quiet:
        return 1 if (findings or stale or stale_skips) else 0

    for f in findings:
        print(f"FAIL  {f}")
    for module, value in stale:
        print(
            f'STALE  baseline entry ({module}, "{value}") no longer matches anything — the producer '
            f"was fixed, renamed or removed.\n"
            f"       Remove it from BASELINE in this script so the list keeps meaning something.",
        )
    for module, expr in stale_skips:
        print(
            f'STALE  acknowledged-unresolvable entry ({module}, "{expr}") no longer matches any '
            f"write site — the code was fixed, moved or the resolver learned to read it.\n"
            f"       Remove it from UNRESOLVED_ALLOWED in this script so the list keeps meaning "
            f"something.",
        )
    if findings or stale or stale_skips:
        print(
            f"\n{len(findings)} non-conforming write site(s), {len(stale)} stale baseline "
            f"entr(ies), {len(stale_skips)} stale acknowledged-unresolvable entr(ies) "
            f"across {len(producers)} producer(s).\n"
            f"The value must be the module directory name without the `{MODULE_PREFIX}` prefix "
            f"(#5256), because that is what audit-service's TopicAttribution fallback uses; a "
            f"disagreement splits one producer into two in every group-by (#5902).",
        )
        return 1
    print(
        f"sourceService convention: OK — {len(producers)} producer(s) checked, "
        f"{len(matched_skips)} acknowledged-unresolvable site(s) skipped WITH a reason, "
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
