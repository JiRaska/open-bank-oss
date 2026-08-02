#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""check-event-contract-coverage.py — ADR-0006 ratchet: no NEW undocumented Kafka topic.

WHERE ADR-0006 ACTUALLY STANDS
------------------------------
Measured, not assumed: 36 distinct topics (34 with an in-tree producer), 71 `eventType` literals,
**0 `.avsc`, 0 `asyncapi.yaml`, and no `openbank-contracts/` directory**. The Apicurio registry the
ADR calls for is deployed and running in the `messaging` namespace — the runtime half exists and the
contract half does not. Every Kafka event on this platform is raw, unversioned JSON.

The nearest thing to a compatibility check is `check-event-schema-compat.py`, which diffs Kotlin
data-class constructors at build time and says so itself. It cannot see a payload, so nothing
validates one at runtime.

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


def producer_pairs() -> set[str]:
    """`<service>:<topic>` for every topic a service produces to, derived from its own config."""
    import yaml

    pairs: set[str] = set()
    for cfg in sorted(REPO.glob("openbank-*/src/main/resources/application.yaml")):
        service = cfg.relative_to(REPO).parts[0]
        try:
            doc = yaml.safe_load(cfg.read_text(encoding="utf-8")) or {}
        except Exception:  # noqa: BLE001 - a malformed config is another gate's problem
            continue
        outgoing = (((doc.get("mp") or {}).get("messaging") or {}).get("outgoing")) or {}
        for channel in outgoing.values():
            if isinstance(channel, dict) and channel.get("topic"):
                pairs.add(f"{service}:{channel['topic']}")
    return pairs


def has_contract(service: str) -> bool:
    return (CONTRACTS / service / "asyncapi.yaml").is_file()


def load_baseline() -> set[str]:
    if not BASELINE.is_file():
        return set()
    return {
        line.strip()
        for line in BASELINE.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    }


def main() -> int:
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
