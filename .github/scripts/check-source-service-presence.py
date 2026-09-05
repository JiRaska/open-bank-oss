#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# sourceService PRESENCE guard: every module that PRODUCES an `openbank.*` domain-event topic must
# state its own name on the events it emits (issues #5256, #6035).
#
# WHY PRESENCE IS A DIFFERENT GATE FROM CONVENTION
#   `check-source-service-convention.py` (PR #5967) answers "is the value a producer stamps equal to
#   its module directory name". Its subject set is, necessarily, the WRITE SITES it can find -- so a
#   producer that never mentions `sourceService` at all has zero write sites, is not a subject, and
#   is reported by neither a finding nor a count. The two checks are complements, not duplicates:
#   that one polices the VALUE of a claim, this one polices whether the claim EXISTS. Running only
#   the convention check would report a clean fleet in exactly the state this file exists to catch.
#
# WHY THE SCOPE IS DERIVED AND NEVER HAND-KEPT
#   This repo's most-repeated defect is a gate whose coverage set is maintained BESIDE the artifacts
#   it covers: such a gate reads as PASSING when the list is short, never as UNCHECKED. So the
#   denominator here is derived from each module's own `application.yaml` -- every literal
#   `openbank.*` topic under an `mp.messaging.outgoing.*` channel. A new event-producing service
#   therefore enters this gate's scope on the commit that declares its channel, with no list to
#   update and no way to be quietly omitted.
#
# WHY IT IS WORTH GATING RATHER THAN DOCUMENTING
#   audit-service resolves attribution strongest-claim-first: a `sourceService` on the event body is
#   recorded as `AttributionSource.EVENT`; without it the row falls back to the topic ladder
#   (`TopicAttribution`) and is recorded as `AttributionSource.TOPIC` -- a value DERIVED from the
#   topic name rather than STATED by the producer. Today no subscribed topic records `"unknown"`,
#   so this is not a live UNKNOWN: it is derived-rather-than-declared attribution. That still cannot
#   be repaired later. `audit_entries` is append-only AT THE DATABASE (V2's `no_update_audit` /
#   `no_delete_audit` are `DO INSTEAD NOTHING`, so a normalising UPDATE touches zero rows and
#   REPORTS SUCCESS) and `source_service` is chain-hashed into `record_hash`. Every event emitted
#   without the field is one more permanently-inferred row; the fix is forward-only by construction,
#   which is the whole argument for a gate rather than a sweep.
#
# THE COUNTING TRAP THIS CHECK IS BUILT AROUND
#   Event payloads are built two ways in this fleet, and a quoted-string probe sees only one:
#     * hand-built map / JSON string  -> the key appears literally: `"sourceService" to X`
#     * serialised Kotlin data class  -> the key exists ONLY at runtime as a property name
#   `grep '"sourceService"'` returns ZERO for `openbank-card-issuance-service` and
#   `openbank-fx-service`, both of which declare `val sourceService: String = SOURCE_SERVICE` on
#   their event supertype and demonstrably put it on the wire. A presence probe that looked for the
#   quoted key would report those two -- and every module sharing their idiom -- as missing, and
#   would just as easily report a module as PRESENT on the strength of a KDoc paragraph. Both
#   idioms are matched below, comment lines are stripped first, and `_SELF_TEST` pins one
#   known-positive per idiom so a regression that blinds the probe to one of them fails the check
#   instead of shrinking its findings.
#
# EXIT CODES
#   0  every derived producer declares sourceService (modulo KNOWN_GAPS)
#   1  a producer declares none and is not baselined; or a KNOWN_GAPS entry that is no longer a gap
#      (the reverse ratchet -- a temporary exception cannot quietly become permanent)
#   2  the check could not run: tree missing, or fewer than --min-producers producers enumerated.
#      Never conflated with 0 -- an enumeration that finds nothing is a broken probe, not a clean
#      fleet, and that is precisely how the original gap stayed invisible.
#
# Run:  python3 .github/scripts/check-source-service-presence.py [--root .] [--self-test]

from __future__ import annotations

import argparse
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import gatelib  # noqa: E402

MODULE_GLOB = "openbank-*"

# Producers derived on origin/main as of 2026-08-31: 38. The floor sits well below that so a
# service legitimately retiring its last outgoing channel does not red the gate, and far enough
# above zero that a broken enumeration cannot pass vacuously (exit 2, never 0).
MIN_PRODUCERS_DEFAULT = 30

# Producers that do NOT declare sourceService today, each against the issue that owns it. Fixing
# one means DELETING its entry -- a stale entry is itself an error, so the list cannot drift in
# either direction and a half-fix cannot pass.
#
# Every entry here is a producer whose audit rows are attributed by derivation rather than by its
# own claim. That is not a live `"unknown"`: `TopicAttribution` covers all 26 topics audit-service
# subscribes to. It is the weaker `AttributionSource.TOPIC`, and it is unrepairable in retrospect.
KNOWN_GAPS: dict[str, str] = {
    # --- audit-subscribed money-path topics: the ones that actually reach `audit_entries` today ---
    "openbank-billing-service": (
        "#6035 - billing is not subscribed by audit-service at all, so this producer has no audit "
        "rows to attribute yet; it is baselined in check-audit-money-path-subscription.py for the "
        "same reason. Subscribing it is that gate's decision, not this one's."
    ),
    "openbank-standing-order-service": (
        "#6035 - found by check-audit-money-path-subscription.py and named in no issue before it; "
        "nothing has yet reviewed whether this stream belongs in the audit trail."
    ),
    # --- producers of topics audit-service does not subscribe to ---
    # Not money-path and not audited today, so no `audit_entries` row is being attributed by
    # derivation right now. They are baselined rather than excluded because a topic entering the
    # audit subscription later must not silently arrive without a producer claim -- at that moment
    # the entry below is what has to be deleted, and the ratchet is what forces the question.
    "openbank-aml-service": "#5256 - producer of openbank.aml.events; not audit-subscribed today.",
    "openbank-campaign-service": "#5256 - producer of openbank.campaign.banner.placements.",
    "openbank-engagement-service": "#5256 - producer of openbank.engagement.events.",
    "openbank-incentive-service": "#5256 - producer of openbank.incentive.events.",
    "openbank-notification-service": "#5256 - producer of openbank.notification.outcomes.v1.",
    "openbank-pid-service": "#5256 - producer of openbank.pid.events.",
    "openbank-tpp-registry-service": "#5256 - producer of openbank.tpp.registry.event.",
}

# Producers whose stamp is written and IN REVIEW, not absent. Each names ONE open PR and warns
# rather than errors, so the entry announces its own removal on the day that PR merges instead of
# reddening this gate against work that is already done.
#
# This is deliberately NOT a hole in the ratchet: an entry here still has to name a PR, still turns
# into a reported ::warning the moment its producer starts declaring the field, and a producer that
# is neither listed here nor in KNOWN_GAPS is an error exactly as before. What it buys is that this
# governance change can be reviewed and merged on its own timeline, rather than being held behind
# five money-path approvals it does not need.
IN_FLIGHT: dict[str, str] = {
    "openbank-delegation-service": "#7716",
    "openbank-ledger-service": "#7716",
    "openbank-sdd-service": "#7716",
    "openbank-interest-service": "#7716",
    "openbank-fraud-service": "#7716",
}

# One known-positive per WRITE IDIOM. These are asserted by --self-test, so a change that blinds the
# probe to an idiom fails loudly instead of silently shrinking the finding set -- the failure mode
# that let the original gap survive a `grep`.
_SELF_TEST_IDIOMS = {
    # Serialised data class: NO literal "sourceService" anywhere in this module's source.
    "openbank-card-issuance-service": "data-class property",
    "openbank-fx-service": "data-class property",
    # Hand-built map / Jackson node: the quoted key is present.
    "openbank-customer-edge": "quoted key",
}

# A line that is wholly a comment carries no write site. KDoc in this fleet discusses `sourceService`
# far more often than code sets it, so stripping comments is what makes this probe stricter than the
# grep that missed the gap in the first place.
_COMMENT = re.compile(r"^\s*(//|\*|/\*)")

# Idiom 1 -- a Kotlin property or named argument. `val sourceService: String = ...`,
# `sourceService = "x"`, `override val sourceService`. The wire key is the property NAME, so no
# quoted string exists to match.
_PROPERTY = re.compile(r"\bsourceService\s*(:\s*String\s*)?=")
# Idiom 2 -- the key as a literal: map entry, Jackson `put`, or hand-built JSON.
_LITERAL = re.compile(r'"sourceService"')
# Reads, not writes. A consumer parsing the field back out is not a producer declaring it, and
# counting it would let a module pass on the strength of code that only ever CONSUMES attribution.
_READ = re.compile(r'(get|path|at)\(\s*"sourceService"|\[\s*"sourceService"\s*\]')


def _walk(node: object, path: list[str], out: list[tuple[list[str], object]]) -> None:
    if isinstance(node, dict):
        for key, value in node.items():
            _walk(value, path + [str(key)], out)
    elif isinstance(node, list):
        for value in node:
            _walk(value, path, out)
    else:
        out.append((path, node))


def produced_topics(module: pathlib.Path) -> set[str]:
    """Literal `openbank.*` topics this module declares it PRODUCES.

    Interpolated values (`${...}`) are skipped: they are not resolvable from the repo alone, and a
    guessed topic name would put a module in or out of scope on a guess.
    """
    app_yaml = module / "src" / "main" / "resources" / "application.yaml"
    if not app_yaml.is_file():
        return set()
    try:
        doc = gatelib.load_yaml(app_yaml)
    except Exception:
        return set()
    flat: list[tuple[list[str], object]] = []
    _walk(doc, [], flat)
    out: set[str] = set()
    for path, value in flat:
        if not path or path[-1] not in ("topic", "topics") or not isinstance(value, str):
            continue
        if "outgoing" not in path:
            continue
        for name in value.split(","):
            name = name.strip().strip("\"'")
            if name.startswith("openbank.") and "$" not in name and "{" not in name:
                out.add(name)
    return out


def declares_source_service(module: pathlib.Path) -> bool:
    """True when this module WRITES `sourceService` somewhere in its production source."""
    main = module / "src" / "main"
    if not main.is_dir():
        return False
    for kt in sorted(main.rglob("*.kt")):
        for line in gatelib.read_text(kt).splitlines():
            if _COMMENT.match(line) or "sourceService" not in line:
                continue
            if _READ.search(line):
                continue
            if _PROPERTY.search(line) or _LITERAL.search(line):
                return True
    return False


def scan(root: pathlib.Path) -> tuple[dict[str, set[str]], set[str]]:
    """-> ({producer module -> topics it produces}, modules that declare sourceService)."""
    producers: dict[str, set[str]] = {}
    declaring: set[str] = set()
    for module in sorted(root.glob(MODULE_GLOB)):
        if not module.is_dir():
            continue
        topics = produced_topics(module)
        if not topics:
            continue
        producers[module.name] = topics
        if declares_source_service(module):
            declaring.add(module.name)
    return producers, declaring


def self_test(root: pathlib.Path) -> int:
    cases: list[tuple[str, bool]] = []
    producers, declaring = scan(root)

    # The probe must see BOTH write idioms. Pinned per idiom, because a probe blinded to one of
    # them does not error -- it returns a smaller, confident, wrong answer.
    for module, idiom in _SELF_TEST_IDIOMS.items():
        cases.append((f"{module} detected ({idiom})", module in declaring))

    # The negative control for the idiom trap: the quoted-key probe ALONE must genuinely fail on
    # the data-class modules. If this ever passes, the trap has stopped being a trap and the
    # comment block at the top of this file is describing a world that no longer exists.
    quoted_only_blind = []
    for module in ("openbank-card-issuance-service", "openbank-fx-service"):
        path = root / module / "src" / "main"
        hits = any(
            _LITERAL.search(line)
            for kt in path.rglob("*.kt")
            for line in gatelib.read_text(kt).splitlines()
            if not _COMMENT.match(line)
        ) if path.is_dir() else True
        quoted_only_blind.append(not hits)
    cases.append(("a quoted-key-only probe is blind to the data-class idiom", all(quoted_only_blind)))

    # A KDoc mention must NOT count as a declaration.
    cases.append(("a comment line is not a write site", _COMMENT.match(" * sourceService = \"x\"") is not None))
    # A consumer reading the field back is not a producer declaring it.
    cases.append(("a read is not a write", _READ.search('node.get("sourceService")') is not None))
    # ...and the read pattern must not swallow a genuine write.
    cases.append(("a Jackson put is still a write", _READ.search('node.put("sourceService", S)') is None))

    cases.append(("enumeration is non-empty", len(producers) >= MIN_PRODUCERS_DEFAULT))
    cases.append((
        "every IN_FLIGHT entry names a PR and is a real producer",
        all(v.startswith("#") and v[1:].isdigit() and k in producers for k, v in IN_FLIGHT.items()),
    ))
    cases.append((
        "IN_FLIGHT and KNOWN_GAPS are disjoint",
        not (set(IN_FLIGHT) & set(KNOWN_GAPS)),
    ))

    failed = [name for name, ok in cases if not ok]
    for name, ok in cases:
        print(f"  {'ok  ' if ok else 'FAIL'}  {name}")
    if failed:
        print(f"::error::self-test failed: {', '.join(failed)}")
        return 1
    print(f"self-test: {len(cases)} case(s) passed")
    return 0


def run(root: pathlib.Path, min_producers: int) -> int:
    if not root.is_dir():
        print(f"::error::root {root} does not exist")
        return 2

    producers, declaring = scan(root)
    gatelib.subjects(len(producers), "modules producing an openbank.* domain-event topic")

    if len(producers) < min_producers:
        print(
            f"::error::enumerated only {len(producers)} event producer(s), expected at least "
            f"{min_producers}. The enumeration is broken, not the fleet -- exiting 2 rather than "
            f"reporting a clean run, because a probe that finds nothing looks exactly like a "
            f"fleet with nothing wrong."
        )
        return 2

    missing = sorted(set(producers) - declaring)
    findings = [m for m in missing if m not in KNOWN_GAPS and m not in IN_FLIGHT]
    stale = sorted(m for m in KNOWN_GAPS if m in declaring or m not in producers)
    landed = sorted(m for m in IN_FLIGHT if m in declaring)
    for module in landed:
        print(
            f"::warning::IN_FLIGHT entry {module!r} has landed ({IN_FLIGHT[module]} merged): it now "
            f"declares sourceService. Delete the entry so the ratchet covers it directly."
        )

    for module in findings:
        topics = ", ".join(sorted(producers[module]))
        print(
            f"::error::{module} produces {topics} but declares no sourceService. Its audit rows "
            f"are attributed by derivation from the topic name, not by this service's own claim, "
            f"and audit_entries is append-only at the DB with source_service chain-hashed into "
            f"record_hash -- so those rows can never be corrected. Stamp the module directory name "
            f"minus the `openbank-` prefix onto the outgoing payload."
        )
    for module in stale:
        why = "now declares sourceService" if module in declaring else "is no longer an event producer"
        print(
            f"::error::KNOWN_GAPS entry {module!r} is stale: it {why}. Delete the entry -- a "
            f"baseline that outlives its reason is how a temporary exception becomes permanent."
        )

    if findings or stale:
        print(
            f"\nsourceService presence: {len(findings)} undeclared producer(s), "
            f"{len(stale)} stale baseline entr(y/ies), across {len(producers)} producer(s)."
        )
        return 1

    print(
        f"sourceService presence: OK -- {len(producers)} producer(s) enumerated, "
        f"{len(declaring)} declare the field, {len(KNOWN_GAPS)} baselined against a named issue, "
        f"{len(IN_FLIGHT)} in review."
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--min-producers", type=int, default=MIN_PRODUCERS_DEFAULT)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    root = pathlib.Path(args.root).resolve()
    if args.self_test:
        return self_test(root)
    return run(root, args.min_producers)


if __name__ == "__main__":
    sys.exit(main())
