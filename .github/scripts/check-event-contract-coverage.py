#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""check-event-contract-coverage.py — ADR-0006 ratchet: no NEW undocumented Kafka topic.

WHERE ADR-0006 ACTUALLY STANDS
------------------------------
Measured, not assumed. At introduction: 36 distinct topics (34 with an in-tree producer), 71
`eventType` literals, **0 `.avsc`, 0 `asyncapi.yaml`, and no `openbank-contracts/` directory**.
Re-measured since: 38 producer pairs across 32 services / 36 distinct topics, of which **2 now have
an AsyncAPI contract** (delegation, notification) and 36 are grandfathered below. The `.avsc` count
is still **0**, and no topic has a schema registered in Apicurio. The registry the ADR calls for is
deployed and running in the `messaging` namespace — the runtime half exists and the contract half
does not. Every Kafka event on this platform is raw, unversioned JSON.

The nearest thing to a compatibility check is `check-event-schema-compat.py`, which diffs Kotlin
data-class constructors at build time and says so itself. It cannot see a payload, so nothing
validates one at runtime.

This script checks only that a contract FILE exists. That a contract DESCRIBES its producer —
channels against produced topics, message names against event-type literals, payload properties
against constructor properties — is `check-event-contract-code-agreement.py`, which was added
precisely because a file-existence check cannot see a document that is wrong.

WHY A RATCHET AND NOT A GATE
----------------------------
Closing this properly is a multi-PR programme: AsyncAPI specs per producer, Avro schemas per event
type, producer-side serialization, registry-enforced BACKWARD compatibility, then consumer rollout
(#1916 sets that order). None of that fits behind one check.

But the gap is not static — it GROWS. Every new service adds unversioned topics; two did this week.
So the cheap, honest move is the baseline-ratchet this repo already uses for domain purity and
detekt: grandfather what exists, and fail outright on anything NEW.

That converts "0 of 34, drifting" into "0 of 34, frozen", which is the difference between a debt and
a leak. It does not pretend ADR-0006 is closer to done than it is.

WHAT IT CHECKS
--------------
For every service that PRODUCES to a topic (`mp.messaging.outgoing.*.topic` in its
`application.yaml` — the producer owns the contract), a contract must exist at
`openbank-contracts/<service>/asyncapi.yaml`, unless the pair is grandfathered in
`.github/event-contract-baseline.txt`.

The baseline is checked in BOTH directions. A stale entry — a pair that no longer exists, or one
that now has a contract — fails just as loudly, so the debt cannot quietly become permanent and the
file cannot drift away from what it claims to describe.

Usage: python3 .github/scripts/check-event-contract-coverage.py
Exit:  0 clean, 1 a new undocumented producer topic, or a stale baseline entry
"""

from __future__ import annotations

import pathlib
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]
BASELINE = REPO / ".github" / "event-contract-baseline.txt"
CONTRACTS = REPO / "openbank-contracts"


def producer_pairs(root: pathlib.Path = None) -> set[str]:
    """`<service>:<topic>` for every topic a service produces to, derived from its own config."""
    import yaml

    pairs: set[str] = set()
    base = root or REPO
    for cfg in sorted(base.glob("openbank-*/src/main/resources/application.yaml")):
        service = cfg.relative_to(base).parts[0]
        try:
            doc = yaml.safe_load(cfg.read_text(encoding="utf-8")) or {}
        except Exception:  # noqa: BLE001 - a malformed config is another gate's problem
            continue
        outgoing = (((doc.get("mp") or {}).get("messaging") or {}).get("outgoing")) or {}
        for channel in outgoing.values():
            if isinstance(channel, dict) and channel.get("topic"):
                pairs.add(f"{service}:{channel['topic']}")
    return pairs


def has_contract(service: str, contracts: pathlib.Path = None) -> bool:
    return ((contracts or CONTRACTS) / service / "asyncapi.yaml").is_file()


def load_baseline() -> set[str]:
    if not BASELINE.is_file():
        return set()
    return {
        line.strip()
        for line in BASELINE.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    }


def self_test() -> int:
    """Falsify the deriver and the ratchet.

    ADR-0006: a topic a service produces to must have an AsyncAPI contract declaring its
    schema, consumers, retention and partitioning key. The gate is a RATCHET, so it has two
    silent failure modes in opposite directions: a deriver that finds nothing reports full
    coverage, and a baseline that never shrinks turns permanent debt into a permanent pass.
    """
    import tempfile

    fails: list[str] = []

    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)

        def svc(name: str, body: str) -> None:
            d = root / name / "src" / "main" / "resources"
            d.mkdir(parents=True, exist_ok=True)
            (d / "application.yaml").write_text(body)

        svc("openbank-a", "mp:\n  messaging:\n    outgoing:\n      out:\n        topic: a.events\n")
        svc("openbank-b", "mp:\n  messaging:\n    outgoing:\n      o1:\n        topic: b.one\n"
                          "      o2:\n        topic: b.two\n")
        # A consumer-only service produces nothing and must not appear.
        svc("openbank-c", "mp:\n  messaging:\n    incoming:\n      in:\n        topic: a.events\n")
        # A channel with a connector but NO topic declares no wire name — counting it would
        # invent a pair nobody produces to.
        svc("openbank-d", "mp:\n  messaging:\n    outgoing:\n      out:\n        connector: smallrye-kafka\n")
        # Malformed YAML is another gate's problem, but it must not take this one down.
        svc("openbank-e", "mp: [unclosed\n")

        got = producer_pairs(root)
        want = {"openbank-a:a.events", "openbank-b:b.one", "openbank-b:b.two"}
        if got != want:
            fails.append(f"producer pairs wrong: expected {sorted(want)}, got {sorted(got)}")

        # An empty tree must derive NOTHING — and main() turns that into a failure, because
        # "no producers" is what a moved layout looks like, not a fleet with no events.
        if producer_pairs(root / "nowhere") != set():
            fails.append("an empty tree derived producer pairs from nothing")

        # --- contract presence -----------------------------------------------------------
        contracts = root / "contracts"
        (contracts / "openbank-a").mkdir(parents=True)
        (contracts / "openbank-a" / "asyncapi.yaml").write_text("asyncapi: 3.0.0\n")
        if not has_contract("openbank-a", contracts):
            fails.append("a service WITH an asyncapi.yaml was reported as having none")
        if has_contract("openbank-b", contracts):
            fails.append("a service with no asyncapi.yaml was reported as having one")
        # A directory with no asyncapi.yaml is not a contract — an empty stub folder must not
        # satisfy the requirement.
        (contracts / "openbank-b").mkdir(parents=True)
        if has_contract("openbank-b", contracts):
            fails.append("an empty contract DIRECTORY counted as a contract")

    # A live read: fixtures cannot tell that the globs still match this repo.
    live = producer_pairs()
    if not live:
        fails.append("reading the real repo derived ZERO producer topics — the deriver or the "
                     "layout moved, and full coverage would be reported about nothing")

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print(f"self-test ok: event-contract coverage is falsifiable "
          f"(8 cases + a live read of {len(live)} producer pair(s))")
    return 0


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()

    pairs = producer_pairs()
    if not pairs:
        print("::error::check-event-contract-coverage: no producer topics found — the deriver is broken")
        return 1

    baseline = load_baseline()
    uncovered = {p for p in pairs if not has_contract(p.split(":", 1)[0])}

    new = sorted(uncovered - baseline)
    for pair in new:
        service, topic = pair.split(":", 1)
        print(
            f"::error::{service} produces to '{topic}' with no event contract. ADR-0006 requires "
            f"openbank-contracts/{service}/asyncapi.yaml declaring the channel, its message schema, "
            "producer, consumers, retention and partitioning key.",
        )

    # Stale in either direction: the pair is gone, or it now HAS a contract and the exemption lingers.
    stale = sorted(baseline - uncovered)
    for pair in stale:
        service = pair.split(":", 1)[0]
        reason = "now has a contract" if has_contract(service) else "no longer produces to that topic"
        print(f"::error::stale baseline entry '{pair}' — {service} {reason}. Remove the line.")

    if new or stale:
        if new:
            print(
                f"\n{len(new)} new undocumented producer topic(s). ADR-0006 is a ratchet, not a "
                "wish: what already exists is grandfathered in .github/event-contract-baseline.txt, "
                "and nothing new joins it. Adding your topic to the baseline is not the fix — the "
                "baseline only shrinks.\n\n"
                "The Apicurio registry is already deployed; what is missing is the contract. "
                "See #1916 for the migration order (money-path topics first, BACKWARD compatibility "
                "enforced at the registry, producers before consumers).",
            )
        return 1

    covered = len(pairs) - len(uncovered)
    print(
        f"OK: {len(pairs)} producer topic(s) — {covered} with an event contract, "
        f"{len(uncovered)} grandfathered. No new undocumented topic.",
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
