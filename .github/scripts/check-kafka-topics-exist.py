#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Every Kafka topic a service consumes or produces must be a topic that EXISTS.

Why this exists
---------------
`openbank-analytics-sink` was subscribed to `openbank.account.events` and
`openbank.transaction.events`. Neither has ever existed — the real topics are
`openbank.accounts.account.created` and `openbank.transactions.transaction.initiated`. A Kafka
consumer subscribed to a nonexistent topic does not error; it simply receives nothing. So the sink
ran for weeks, healthy by every signal, while no ACCOUNT_OPENED and no TRANSACTION row ever reached
`bronze_events` — which silently made ADR-0210 D2's account->party resolution dead code, and made
Customer 360 structurally unable to show a customer's accounts or transactions (#2598).

Nothing was red. Nothing could have been: the consumer's own health, its consumer-group membership,
its lag and the sink's metrics are all consistent with "this topic has no traffic".

This is the same failure shape as a client pointed at a REST path that does not exist — the one that
shipped finrep's call to `/api/v1/ledger/trial-balance` (see the contract-test notes in CLAUDE.md).
There, the fix was to make the provider replay the contract at PR time. Here, the equivalent is to
compare the name a service asks for against the set of names that are declared to exist.

`openbank-infra/gitops` holds a `KafkaTopic` CR for every topic (42 declared, 42 on the sandbox
cluster at the time of writing), so the declared set is authoritative and complete. That is what
makes this checkable statically rather than needing a live broker.

Usage:  check-kafka-topics-exist.py [--enforce]
Advisory by default (prints ::warning, exits 0) per the repo convention; --enforce fails the build.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]
GITOPS = REPO / "openbank-infra" / "gitops"

# A channel's topic list. Both spellings appear in the fleet:
#   topics: a,b,c          (multi-topic consumer)
#   topic: a               (single)
TOPIC_LINE = re.compile(r"^\s*topics?:\s*(?P<val>[^\s#][^#]*?)\s*(?:#.*)?$")

# Anything templated is not a literal name and cannot be checked here.
TEMPLATED = re.compile(r"[$&{}*?]|\bSTRING\b")

# Topics whose existence this check cannot assert, with the reason. An entry here is a claim that
# needs to stay true — not a place to park a failure.
# EMPTY, and that is the point: with the #2598 topic names corrected, every topic any service
# references resolves to a declared CR. An entry added here needs a reason, and the stale-entry check
# below fails in BOTH directions — so a temporary exception cannot quietly become permanent.
ALLOWED_ABSENT: dict[str, str] = {}


def declared_topics() -> set[str]:
    """Every KafkaTopic CR name in gitops."""
    names: set[str] = set()
    for path in GITOPS.rglob("*.yaml"):
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        if "kind: KafkaTopic" not in text:
            continue
        # Strimzi allows spec.topicName to differ from metadata.name; the broker name is topicName
        # when present. Collect both so neither spelling produces a false positive.
        for m in re.finditer(r"^\s+(?:name|topicName):\s*(?P<n>[A-Za-z0-9._-]+)\s*$", text, re.M):
            names.add(m.group("n"))
    return names


def configured_topics() -> dict[str, set[str]]:
    """topic name -> the service application.yaml files that reference it."""
    used: dict[str, set[str]] = {}
    for path in REPO.glob("openbank-*/src/main/resources/application.yaml"):
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        for line in text.splitlines():
            m = TOPIC_LINE.match(line)
            if not m:
                continue
            raw = m.group("val").strip().strip("\"'")
            if not raw or TEMPLATED.search(raw):
                continue
            for name in (t.strip() for t in raw.split(",")):
                # A bare word with no dot is a config key value, not a topic name in this fleet's
                # convention (every real topic is dotted). Skipping them keeps the false-positive
                # rate at zero, which is what makes this enforceable.
                if not name or "." not in name:
                    continue
                used.setdefault(name, set()).add(
                    str(path.relative_to(REPO)).split("/", maxsplit=1)[0]
                )
    return used


def selftest() -> int:
    """Feed the check inputs it MUST flag, and inputs it must NOT.

    A gate that has only ever passed is unfalsified — its failure path is code nobody has run. This
    runs on every CI invocation so the failure path cannot rot: the real repo state is expected to be
    clean, so without this the flagging branch would never execute again after today.
    """
    declared = declared_topics()
    if not declared:
        print("selftest FAIL: no KafkaTopic CRs found — the scan itself is broken.")
        return 1

    known_good = sorted(declared)[0]
    cases = [
        # (name, expected_to_be_flagged, why)
        ("openbank.account.events", True, "the #2598 name — dotted, plausible, and never declared"),
        ("openbank.transaction.events", True, "the other #2598 name"),
        (known_good, False, f"a genuinely declared topic ({known_good}) must never be flagged"),
        ("notatopic", False, "undotted: a config value, not a topic name in this fleet"),
    ]

    failures = 0
    for name, should_flag in ((c[0], c[1]) for c in cases):
        flagged = "." in name and name not in declared and name not in ALLOWED_ABSENT
        if flagged != should_flag:
            verb = "did not flag" if should_flag else "wrongly flagged"
            print(f"selftest FAIL: {verb} {name!r}")
            failures += 1

    if failures:
        return 1
    print(f"selftest OK: {len(cases)} cases, both directions (flags the unknown, spares the declared).")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true", help="fail on a missing topic")
    ap.add_argument("--selftest", action="store_true", help="verify the check can fail")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    declared = declared_topics()
    if not declared:
        # A guard that found no declarations would pass everything. That is not a pass.
        print("::error::check-kafka-topics-exist: found NO KafkaTopic CRs under "
              f"{GITOPS.relative_to(REPO)} — the check cannot mean anything. Fix the scan, not this line.")
        return 1

    used = configured_topics()
    missing = {
        name: svcs for name, svcs in sorted(used.items())
        if name not in declared and name not in ALLOWED_ABSENT
    }

    print(f"check-kafka-topics-exist: {len(declared)} topics declared in gitops, "
          f"{len(used)} referenced by services, {len(missing)} unresolved.")

    stale = [t for t in ALLOWED_ABSENT if t in declared]
    if stale:
        print("::warning::check-kafka-topics-exist: ALLOWED_ABSENT names a topic that IS now "
              f"declared — remove the stale entry: {', '.join(sorted(stale))}")

    if not missing:
        return 0

    for name, svcs in missing.items():
        print(f"  MISSING  {name}   referenced by: {', '.join(sorted(svcs))}")
    detail = (
        "A service references a Kafka topic with no KafkaTopic CR in gitops. A consumer subscribed "
        "to a topic that does not exist receives nothing and reports healthy, so this cannot show up "
        "as a red signal at runtime (#2598). Either fix the name, or declare the topic."
    )
    if args.enforce:
        print(f"::error::check-kafka-topics-exist: {len(missing)} unresolved topic(s). {detail}")
        return 1
    print(f"::warning::check-kafka-topics-exist: {len(missing)} unresolved topic(s). {detail}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
