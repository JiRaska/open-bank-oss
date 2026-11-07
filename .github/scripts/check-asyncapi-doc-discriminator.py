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
     `application.yaml`, or a supported literal/constant Kafka Streams `stream()`/`to()` call).
     Catches a channel documenting a topic nobody speaks.

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
import re
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


def streams_topics(source: str) -> dict[str, set[str]]:
    """Static Kotlin Streams evidence, not a Kotlin compiler or a topic-name allowlist.

    Recognize imported StreamsBuilder/KStream receivers and supported fluent chains only.
    Topic arguments must be whole literals or unambiguous same-file const val literals.
    Dynamic topics, aliases, unknown chain methods and ambiguous/shadowed names fail closed.
    Comments (including nested Kotlin comments) and string contents are never executable code.
    """
    literals: dict[str, str] = {}
    parts: list[str] = []
    i = 0
    while i < len(source):
        start = i
        if source.startswith("//", i):
            end = source.find("\n", i)
            i = len(source) if end < 0 else end
        elif source.startswith("/*", i):
            depth = 1
            i += 2
            while i < len(source) and depth:
                if source.startswith("/*", i):
                    depth += 1
                    i += 2
                elif source.startswith("*/", i):
                    depth -= 1
                    i += 2
                else:
                    i += 1
        elif source.startswith('"""', i):
            end = source.find('"""', i + 3)
            i = len(source) if end < 0 else end + 3
        elif source[i] in ('"', "'"):
            quote = source[i]
            i += 1
            while i < len(source):
                if source[i] == "\\":
                    i += 2
                elif source[i] == quote:
                    i += 1
                    break
                else:
                    i += 1
            raw = source[start:i]
            marker = f"§{len(literals)}§"
            literals[marker] = raw[1:-1] if re.fullmatch(r'"[A-Za-z0-9_.-]{1,249}"', raw) else ""
            parts.append(marker)
            continue
        else:
            parts.append(source[i])
            i += 1
            continue
        parts.append("\n" * source[start:i].count("\n") + " ")
    code = "".join(parts)
    result: dict[str, set[str]] = {"stream": set(), "to": set()}
    builder_import = bool(re.search(r"(?m)^import org\.apache\.kafka\.streams\.StreamsBuilder\s*$", code))
    stream_import = bool(re.search(r"(?m)^import org\.apache\.kafka\.streams\.kstream\.KStream\s*$", code))
    if not (builder_import or stream_import):
        return result

    # A local declaration or parameter with the same name makes a file-level lookup unsafe.
    bindings = re.findall(r"\b(?:val|var)\s+(\w+)|[(,]\s*(\w+)\s*:", code)
    names = [left or right for left, right in bindings]
    constants = {
        name: literals[marker]
        for name, marker in re.findall(
            r"\bconst\s+val\s+(\w+)\s*(?::\s*String\s*)?=\s*(§\d+§)[ \t]*(?=[;\n}])", code
        )
        if names.count(name) == 1
    }
    declarations: list[tuple[str, str]] = []
    if builder_import:
        for match in re.finditer(r"\bval\s+(\w+)\s*=\s*StreamsBuilder\s*\(\s*\)", code):
            rest = code[match.end():]
            if (not rest.strip() or rest.lstrip().startswith((";", "}")) or
                    re.match(r"[ \t]*\n", rest) and not rest.lstrip().startswith(".")):
                declarations.append((match[1], "builder"))
        declarations += [(name, "builder") for name in re.findall(r"[(,]\s*(\w+)\s*:\s*StreamsBuilder\b", code)]
    if stream_import:
        declarations += [(name, "stream") for name in re.findall(r"[(,]\s*(\w+)\s*:\s*KStream\s*<", code)]
    receivers = dict(declarations)
    token_matches = list(re.finditer(r"§\d+§|[A-Za-z_]\w*|[^\s]", code))
    tokens = [match[0] for match in token_matches]

    def after_group(pos: int) -> int:
        pairs = {"(": ")", "{": "}", "[": "]", "<": ">"}
        stack = [pairs[tokens[pos]]]
        pos += 1
        while pos < len(tokens) and stack:
            token = tokens[pos]
            # Angle brackets only balance an explicit generic argument list, not comparisons.
            if token in ("(", "{", "[") or (token == "<" and stack[-1] == ">"):
                stack.append(pairs[token])
            elif token == stack[-1]:
                stack.pop()
            pos += 1
        return pos if not stack else len(tokens)

    stream_ops = {"filter", "filterNot", "map", "mapValues", "flatMap", "flatMapValues",
                  "selectKey", "peek", "repartition", "process", "processValues"}
    for start, receiver in enumerate(tokens):
        if receiver not in receivers or (start and tokens[start - 1] == "."):
            continue
        if names.count(receiver) != sum(name == receiver for name, _ in declarations):
            continue
        if len({kind for name, kind in declarations if name == receiver}) != 1:
            continue
        kind = receivers[receiver]
        pos = start + 1
        while pos + 2 < len(tokens) and tokens[pos] == ".":
            method = tokens[pos + 1]
            arg = pos + 2
            if tokens[arg] == "<":
                arg = after_group(arg)
            if arg >= len(tokens) or tokens[arg] not in ("(", "{"):
                kind = "unknown"
                break
            end = after_group(arg)
            if (method == "stream" and kind == "builder") or (method == "to" and kind == "stream"):
                if arg + 2 < len(tokens) and tokens[arg] == "(" and tokens[arg + 2] in (",", ")"):
                    topic = literals.get(tokens[arg + 1], constants.get(tokens[arg + 1], ""))
                    if topic and topic not in (".", ".."):
                        result[method].add(topic)
                kind = "stream" if method == "stream" else "terminal"
            elif kind == "stream" and method in stream_ops:
                pass
            elif kind == "stream" and method in {"groupBy", "groupByKey"}:
                kind = "grouped"
            elif kind == "grouped" and method == "windowedBy":
                pass
            elif kind == "grouped" and method in {"aggregate", "count", "reduce"}:
                kind = "table"
            elif kind == "table" and method == "toStream":
                kind = "stream"
            else:
                kind = "unknown"
                break
            if end < len(tokens) and tokens[end] == "{":
                end = after_group(end)
            pos = end
        if start >= 3 and tokens[start - 3] == "val" and tokens[start - 1] == "=" and kind == "stream":
            name = tokens[start - 2]
            # A call followed by an operator/property is not a proven KStream initializer.
            boundary = pos == len(tokens) or tokens[pos] in (";", "}")
            if pos < len(tokens) and pos > start + 1:
                boundary |= "\n" in code[token_matches[pos - 1].end():token_matches[pos].start()]
            if boundary:
                declarations.append((name, kind))
                receivers[name] = kind
    return result


def service_streams_topics(service_dir: pathlib.Path, direction: str) -> set[str]:
    """Only production Kotlin source can corroborate an AsyncAPI address."""
    topics: set[str] = set()
    for path in sorted((service_dir / "src/main/kotlin").rglob("*.kt")):
        source = path.read_text(encoding="utf-8")
        if "org.apache.kafka.streams" in source:
            topics |= streams_topics(source)[direction]
    return topics


def spoken_topics(root: pathlib.Path) -> set[str]:
    """Topics evidenced by messaging config or actual supported Kafka Streams calls."""
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
    for service in sorted(root.glob("openbank-*")):
        topics |= service_streams_topics(service, "stream") | service_streams_topics(service, "to")
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
                f"service (no mp.messaging topic or supported Kafka Streams call declares it)"
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
    imports = "import org.apache.kafka.streams.StreamsBuilder\nimport org.apache.kafka.streams.kstream.KStream\n"
    topology = imports + '''
        const val INPUT = "openbank.input"
        const val OUTPUT: String = "openbank.output"
        const val UNUSED = "openbank.unused"
        fun build() {
            val builder = StreamsBuilder()
            val signals = builder.stream(INPUT, consumed).filter { _, value -> true }
            signals.to("openbank.literal")
        }
        fun aggregate(signals: KStream<String, String>) {
            signals.groupByKey().windowedBy(window).aggregate({ "{}" }, aggregate, store)
                .toStream().mapValues { key, value -> value }.to(OUTPUT, produced)
        }
    '''
    stream_cases = [
        ("Streams literal/constant calls and grouped fluent sink", topology,
         {"stream": {"openbank.input"}, "to": {"openbank.output", "openbank.literal"}}),
        ("unused constants are not calls", imports + 'const val TOPIC = "openbank.unused"\n', None),
        ("comments, nested comments and documentation strings are not calls", imports + '''
            // val builder = StreamsBuilder(); builder.stream("openbank.comment")
            /* outer /* nested */ val builder = StreamsBuilder(); builder.stream("openbank.nested") */
            val doc = """val builder = StreamsBuilder(); builder.stream("openbank.doc")"""
            val text = "builder.stream(\\"openbank.text\\")"
        ''', None),
        ("an unrelated to method cannot borrow Kafka imports", imports + '''
            fun unrelated() { other.to("openbank.unrelated"); other.stream("openbank.unrelated") }
        ''', None),
        ("unresolved, concatenated, interpolated and dynamic topics stay unsupported", imports + '''
            const val PREFIX = "openbank.prefix"
            const val CONCAT = "openbank." + suffix
            fun build() {
                val builder = StreamsBuilder()
                builder.stream(UNKNOWN)
                builder.stream(PREFIX + suffix)
                builder.stream("openbank.$suffix")
                builder.stream(CONCAT)
                builder.stream(topicName())
            }
        ''', None),
        ("shadowed constants cannot corroborate a different runtime topic", imports + '''
            const val TOPIC = "openbank.constant"
            fun build(TOPIC: String) { val builder = StreamsBuilder(); builder.stream(TOPIC) }
        ''', None),
        ("ambiguous constants in separate objects are unresolved", imports + '''
            object One { const val TOPIC = "openbank.one" }
            object Two { const val TOPIC = "openbank.two" }
            fun build() { val builder = StreamsBuilder(); builder.stream(TOPIC) }
        ''', None),
        ("unknown chain result is not a KStream", imports + '''
            fun build(signals: KStream<String, String>) {
                val unknown = signals.unsupported()
                unknown.to("openbank.unsupported")
                signals.unsupported().to("openbank.unsupported")
            }
        ''', None),
        ("builder factory suffix cannot establish a StreamsBuilder receiver", imports + '''
            fun build() {
                val builder = StreamsBuilder().unrelated()
                builder.stream("openbank.fake")
            }
        ''', None),
        ("operator result cannot establish a KStream receiver", imports + '''
            fun build(signals: KStream<String, String>) {
                val unknown = signals.mapValues { key, value -> value } + something
                unknown.to("openbank.fake")
            }
        ''', None),
        ("a shadowed receiver cannot borrow a KStream parameter", imports + '''
            fun build(signals: KStream<String, String>) {
                val signals = other
                signals.to("openbank.shadowed")
            }
        ''', None),
        ("source without Kafka imports is unsupported", '''
            fun build() { val builder = StreamsBuilder(); builder.stream("openbank.fake") }
        ''', None),
    ]
    for label, source, expected in stream_cases:
        actual = streams_topics(source)
        ok = actual == (expected or {"stream": set(), "to": set()})
        print(f"  [{'ok' if ok else 'FAIL'}] {label}")
        if not ok:
            failed += 1
            print(f"        {actual}")
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

        source_dir = root / "openbank-a/src/main/kotlin"
        source_dir.mkdir(parents=True)
        (source_dir / "Topology.kt").write_text(topology, encoding="utf-8")
        doc["channels"]["ok-direct"]["address"] = "openbank.output"
        path.write_text(yaml.safe_dump(doc), encoding="utf-8")
        ok = not audit(path, root) and "openbank.unused" not in spoken_topics(root)
        print(f"  [{'ok' if ok else 'FAIL'}] actual production Streams sink corroborates address")
        failed += not ok
        (source_dir / "Topology.kt").unlink()
        test_dir = root / "openbank-a/src/test/kotlin"
        test_dir.mkdir(parents=True)
        (test_dir / "Topology.kt").write_text(topology, encoding="utf-8")
        ok = any("not produced or consumed" in f for f in audit(path, root))
        print(f"  [{'ok' if ok else 'FAIL'}] test-only Streams sink cannot corroborate address")
        failed += not ok

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
