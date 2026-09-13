#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""check-asyncapi-doc-discriminator.py — the published event doc must not lie about the discriminator.

WHY THIS EXISTS AND WHY IT IS NOT THE EXISTING GATE
---------------------------------------------------
`docs/asyncapi/openbank-events.yaml` is the fleet-wide event document, and it is PUBLISHED: the
admin-ui developer-docs page links it (`src/app/docs/api/page.tsx`). It is not the per-service
contract tree — `openbank-contracts/<service>/asyncapi.yaml` is — and the two ADR-0006 gates
(`check-event-contract-coverage.py`, `check-event-contract-code-agreement.py`) scope to that tree
only. So until now **nothing under `.github/` read this file at all**, and it drifted (#4761).

How it drifted is the point. 18 of its 21 payload schemas asserted `eventType` as a BODY field.
For an outbox-relayed producer — 27 of 29 in this fleet — that is false: the dispatcher moves the
row's `eventType` onto the Kafka header `ce-type` (`OutboxKafkaHeaders.HEADER_EVENT_TYPE`) and the
body is the bare serialised aggregate. A consumer author who follows the published schema writes
`node.path("eventType")`, gets `""`, takes a quiet `?: return`, and discards 100% of messages with
no error, no exception and no log. That is not hypothetical: it blocked #1035 on
`openbank.sanctions.screening.event` for four weeks, where 9 of the 10 declared fields were never
on the wire. An undetectably wrong document is worse than an absent one, because the reader's own
tests — written against a fixture copied from the same document — agree with it.

WHAT THIS CHECKS
----------------
For every channel in `docs/asyncapi/openbank-events.yaml`:

  A. THE ADDRESS IS A REAL TOPIC. The channel `address` must be a topic some in-tree service
     produces or consumes (`mp.messaging.outgoing|incoming.*.topic` in its own
     `application.yaml`). Catches a channel documenting a topic nobody speaks.

  B. THE CHANNEL SAYS WHERE THE DISCRIMINATOR LIVES. The channel `description` must contain
     either the literal `ce-type` (header-carried — the outbox-relayed shape) or the literal
     `direct emitter` (the producer serialises its own envelope, so the body carries the type).
     "Switch on eventType" is true for 2 of 29 producers and false for the rest; a channel that
     declines to say which it is, is the exact state this document was in.

  C. A BODY `eventType` MUST BE CLAIMED IN PROSE, AND A CLAIM MUST BE BACKED BY THE SCHEMA — both
     directions. A payload that declares `eventType` is only legal if the channel description
     says the literal `body eventType`; and a description that says `body eventType` must resolve
     to a payload that declares it. That is the self-contradiction which produced #4761: prose and
     schema disagreeing inside one file, with the schema being the half a consumer author copies.

     Both directions matter because both failures happened here. Fourteen channels declared
     `eventType` with no prose claiming it (the #4761 defect). The opposite — prose promising a
     body field the schema drops — is what a half-finished correction leaves behind.

     It is a genuine constraint and not a rubber stamp: some producers really do put `eventType`
     in the body (ledger, party, kyc, consent, clearing, dispute) and some really do not (aml,
     card, standing-order, fx, swift, interest, sanctions, sepa, domestic). The gate forces the
     author to say which, and then holds them to it.

     `$ref` and `allOf` are resolved into a flat property-name set, so inheriting the shared
     `EventEnvelope` (which declares `eventType`) counts as declaring it.

WHAT THIS DELIBERATELY DOES NOT CHECK, AND WHY THAT IS NOT A COP-OUT
--------------------------------------------------------------------
It does not verify the payload FIELD SET against the producer. It cannot, and neither can the
existing gates: `check-event-contract-code-agreement.py` resolves payload fields from a data-class
primary constructor or a `mapOf`/`buildMap` construction site, and an outbox-relayed producer
serialises the AGGREGATE — `writeValueAsString(sanctionsCheck)` — which is neither. There is no
constructor at the call site and no literal key anywhere in the tree, so that producer is
structurally invisible to a source-reading gate. Establishing the true field set needs a captured
real message or a registered wire schema, which is #1916's job (Apicurio is deployed, healthy for
75 days, and holds zero artifacts).

So this gate checks the SELF-CONSISTENCY of the document plus the reality of its addresses, and is
honest that a channel can still be green here while listing body fields the producer never emits.
It closes the specific defect class that has now bitten twice — the discriminator — and it makes a
new channel unable to be added in the old, silent shape.

Retirement: when #1916 registers wire schemas and this document is superseded by
`openbank-contracts/`, delete the document and this gate together. It is ~1 s, no network, and
scoped to one file on purpose.

Usage:  check-asyncapi-doc-discriminator.py [--self-test]
Exit:   0 clean, 1 the published document contradicts itself or names a topic nobody speaks
"""

from __future__ import annotations

import pathlib
import sys
import tempfile

import gatelib

REPO = pathlib.Path(__file__).resolve().parents[2]
DOC = REPO / "docs" / "asyncapi" / "openbank-events.yaml"

HEADER_MARKER = "ce-type"
DIRECT_MARKER = "direct emitter"
BODY_CLAIM = "body eventType"


def _yaml():
    import yaml

    return yaml


def spoken_topics(root: pathlib.Path) -> set[str]:
    """Every topic any in-tree service produces to or consumes from, derived from its own config."""
    yaml = _yaml()
    topics: set[str] = set()
    for cfg in sorted(root.glob("openbank-*/src/main/resources/application.yaml")):
        try:
            doc = yaml.safe_load(cfg.read_text(encoding="utf-8")) or {}
        except Exception:  # noqa: BLE001 - a malformed config is another gate's problem
            continue
        messaging = ((doc.get("mp") or {}).get("messaging")) or {}
        for direction in ("outgoing", "incoming"):
            for channel in (messaging.get(direction) or {}).values():
                if not isinstance(channel, dict):
                    continue
                if channel.get("topic"):
                    topics.add(str(channel["topic"]))
                # A fan-in consumer subscribes with `topics:` (comma-separated), not `topic:`.
                # audit-service reads 21 topics that way; missing this idiom would make every
                # one of them look unspoken.
                if channel.get("topics"):
                    topics |= {t.strip() for t in str(channel["topics"]).split(",") if t.strip()}
    return topics


def resolve_properties(node, doc, seen: frozenset = frozenset()) -> set[str]:
    """Flatten a schema node's property names through `$ref` and `allOf`."""
    if not isinstance(node, dict):
        return set()
    ref = node.get("$ref")
    if isinstance(ref, str) and ref.startswith("#/"):
        if ref in seen:
            return set()
        target = doc
        for part in ref.lstrip("#/").split("/"):
            if not isinstance(target, dict) or part not in target:
                return set()
            target = target[part]
        return resolve_properties(target, doc, seen | {ref})
    names: set[str] = set()
    props = node.get("properties")
    if isinstance(props, dict):
        names |= set(props)
    for sub in node.get("allOf") or []:
        names |= resolve_properties(sub, doc, seen)
    return names


def payload_properties(channel: dict, doc: dict) -> set[str]:
    """Union of the property names of every message this channel carries."""
    names: set[str] = set()
    for message in (channel.get("messages") or {}).values():
        resolved = message
        ref = message.get("$ref") if isinstance(message, dict) else None
        if isinstance(ref, str) and ref.startswith("#/"):
            target = doc
            for part in ref.lstrip("#/").split("/"):
                if not isinstance(target, dict) or part not in target:
                    target = None
                    break
                target = target[part]
            resolved = target if isinstance(target, dict) else {}
        if isinstance(resolved, dict) and "payload" in resolved:
            names |= resolve_properties(resolved["payload"], doc)
    return names


def audit(doc_path: pathlib.Path, root: pathlib.Path) -> list[str]:
    yaml = _yaml()
    doc = yaml.safe_load(doc_path.read_text(encoding="utf-8")) or {}
    channels = doc.get("channels") or {}
    spoken = spoken_topics(root)
    failures: list[str] = []

    for name, channel in sorted(channels.items()):
        if not isinstance(channel, dict):
            continue
        address = channel.get("address")
        description = str(channel.get("description") or "")

        # A. the address must be a topic somebody actually speaks
        if address and spoken and address not in spoken:
            failures.append(
                f"{name}: address '{address}' is not produced or consumed by any in-tree "
                f"service (no mp.messaging.*.topic declares it)"
            )

        # B. the channel must say where the discriminator lives
        header_carried = HEADER_MARKER in description
        direct = DIRECT_MARKER in description
        if not header_carried and not direct:
            failures.append(
                f"{name}: description does not say where the event-type discriminator lives. "
                f"Say '{HEADER_MARKER}' for an outbox-relayed channel (the discriminator is the "
                f"Kafka header) or '{DIRECT_MARKER}' for a producer that serialises its own "
                f"envelope. 'Switch on eventType' is true for 2 of 29 producers (#4761)."
            )
            continue

        # C. a body eventType must be claimed in prose, and a claim must be backed by the schema
        props = payload_properties(channel, doc)
        declares = "eventType" in props
        claims = BODY_CLAIM in description
        if declares and not claims:
            failures.append(
                f"{name}: the payload schema declares 'eventType' as a body property (possibly "
                f"inherited via allOf/$ref from EventEnvelope), but the description never claims "
                f"the producer puts it there. For an outbox-relayed producer it does not: a "
                f'consumer reading node.path("eventType") gets "" and silently discards every '
                f"message (#4761, #1035). Either drop it from the schema, or state "
                f"'{BODY_CLAIM}' in the description and name the producer that emits it."
            )
        elif claims and not declares:
            failures.append(
                f"{name}: the description claims a '{BODY_CLAIM}', but no payload schema on this "
                f"channel declares that property. A promise the schema does not keep is the same "
                f"defect pointing the other way."
            )

    return failures


def self_test() -> int:
    """Falsify the checker: it must go red on each defect and green on the corrected shape."""
    yaml = _yaml()
    cases: list[tuple[str, str, bool]] = []

    good = {
        "channels": {
            "ok-header": {
                "address": "openbank.x.y.event",
                "description": "Outbox-relayed; the discriminator is the ce-type Kafka header.",
                "messages": {"M": {"$ref": "#/components/messages/M"}},
            },
            "ok-direct": {
                "address": "openbank.x.z.event",
                "description": "A direct emitter; the body eventType carries the type.",
                "messages": {"M2": {"$ref": "#/components/messages/M2"}},
            },
        },
        "components": {
            "messages": {
                "M": {"payload": {"$ref": "#/components/schemas/Bare"}},
                "M2": {"payload": {"$ref": "#/components/schemas/Env"}},
            },
            "schemas": {
                "Bare": {"type": "object", "properties": {"id": {"type": "string"}}},
                "Env": {"type": "object", "properties": {"eventType": {"type": "string"}}},
            },
        },
    }
    cases.append(("corrected document is clean", yaml.safe_dump(good), True))

    silent = yaml.safe_load(yaml.safe_dump(good))
    silent["channels"]["ok-header"]["description"] = "Published when something happens."
    cases.append(("a channel that does not say where the discriminator lives", yaml.safe_dump(silent), False))

    contradiction = yaml.safe_load(yaml.safe_dump(good))
    contradiction["channels"]["ok-header"]["messages"] = {"M2": {"$ref": "#/components/messages/M2"}}
    cases.append(("an unclaimed body eventType (the #4761 defect)", yaml.safe_dump(contradiction), False))

    inherited = yaml.safe_load(yaml.safe_dump(good))
    inherited["components"]["schemas"]["Bare"] = {
        "allOf": [{"$ref": "#/components/schemas/Env"}, {"type": "object", "properties": {"id": {}}}]
    }
    cases.append(("an unclaimed body eventType inherited through allOf/$ref", yaml.safe_dump(inherited), False))

    unbacked = yaml.safe_load(yaml.safe_dump(good))
    unbacked["channels"]["ok-header"]["description"] = (
        "Outbox-relayed via the ce-type header, and it also has a body eventType."
    )
    cases.append(("a body eventType claim the schema does not back", yaml.safe_dump(unbacked), False))

    claimed = yaml.safe_load(yaml.safe_dump(good))
    claimed["channels"]["ok-header"]["description"] = (
        "Outbox-relayed: ce-type header, and this producer duplicates it as a body eventType."
    )
    claimed["channels"]["ok-header"]["messages"] = {"M2": {"$ref": "#/components/messages/M2"}}
    cases.append(("a body eventType that IS claimed is allowed", yaml.safe_dump(claimed), True))

    failed = 0
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        for label, text, expect_clean in cases:
            path = root / "doc.yaml"
            path.write_text(text, encoding="utf-8")
            # empty root ⇒ no application.yaml ⇒ check A is inert, isolating B and C
            findings = audit(path, root)
            clean = not findings
            ok = clean is expect_clean
            print(f"  [{'ok' if ok else 'FAIL'}] {label}")
            if not ok:
                failed += 1
                for f in findings:
                    print(f"        {f}")

    # the address check, against a known-positive
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        svc = root / "openbank-a" / "src" / "main" / "resources"
        svc.mkdir(parents=True)
        (svc / "application.yaml").write_text(
            "mp:\n  messaging:\n    outgoing:\n      out:\n        topic: openbank.x.y.event\n",
            encoding="utf-8",
        )
        doc = yaml.safe_load(yaml.safe_dump(good))
        doc["channels"]["ok-direct"]["address"] = "openbank.nobody.speaks.this"
        path = root / "doc.yaml"
        path.write_text(yaml.safe_dump(doc), encoding="utf-8")
        findings = audit(path, root)
        ok = any("not produced or consumed" in f for f in findings)
        print(f"  [{'ok' if ok else 'FAIL'}] an address no service speaks is flagged")
        if not ok:
            failed += 1

    print("self-test: " + ("PASS" if failed == 0 else f"FAIL ({failed})"))
    return 1 if failed else 0


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()

    if not DOC.is_file():
        print(f"ok: {DOC.relative_to(REPO)} is absent — nothing to check")
        return 0

    findings = audit(DOC, REPO)
    doc = _yaml().safe_load(DOC.read_text(encoding="utf-8")) or {}
    count = len(doc.get("channels") or {})
    # Unconditionally, and BEFORE the failure path: a gate that found its corpus and then failed
    # on it must not also read as having lost its corpus.
    gatelib.subjects(count, f"channels in {DOC.relative_to(REPO)}")

    if findings:
        print(f"\nFAIL: {len(findings)} channel(s) contradict themselves or name an unspoken topic:\n")
        for f in findings:
            print(f"  - {f}")
        print(
            "\nThe discriminator for an outbox-relayed channel is the `ce-type` Kafka header "
            "(OutboxKafkaHeaders.HEADER_EVENT_TYPE), never a body field. See #4761."
        )
        return 1

    print("ok: every channel states where its discriminator lives and does not contradict it")
    return 0


if __name__ == "__main__":
    sys.exit(main())
