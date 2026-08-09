#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""check-event-contract-code-agreement.py — ADR-0006: an event contract must describe the code.

WHAT WAS ALREADY CHECKED, AND WHAT WAS NOT
------------------------------------------
`check-event-contract-coverage.py` (the ADR-0006 ratchet, #1916) asserts that a service producing
to a Kafka topic has a file at `openbank-contracts/<service>/asyncapi.yaml`, unless the pair is
grandfathered in `.github/event-contract-baseline.txt`. It checks EXISTENCE and nothing else.

So a contract could name a topic the service does not produce, omit a message the service emits,
or list payload fields the producing data class has never carried, and every gate in this repo
stayed green. The first contract in the tree says so in its own header:

    "check-event-contract-coverage.py asserts this FILE EXISTS; nothing validates its content,
     and no AsyncAPI linter is wired despite ADR-0006's delivery table claiming one is."

That matters more here than it would for a REST spec. Nothing validates a Kafka payload at
RUNTIME either — zero topics have a registered schema in the Apicurio registry, so a producer
that violates its own contract fails nowhere, in no environment, ever. Until registry enforcement
lands, this document is the only description of the wire format a consumer author can read, and an
undetectably wrong description is worse than an absent one: a consumer written against it compiles,
passes its own tests against a fixture copied from the same document, and breaks on real traffic.

WHAT THIS CHECKS
----------------
For every `openbank-contracts/<service>/asyncapi.yaml` in the tree — the scope is DERIVED, there is
no list to maintain, and a contract added tomorrow is in scope the moment it is committed:

  A. CHANNELS vs PRODUCED TOPICS (both directions). Every `channels.*.address` must be a topic the
     service produces (`mp.messaging.outgoing.*.topic` in its own `application.yaml`), and every
     topic it produces must appear as a channel address. The second direction is the one the
     coverage ratchet cannot see at all: a contracted service that adds a SECOND topic still has
     "a contract file", so the ratchet passes and the new topic is undocumented.

  B. MESSAGE NAMES vs EVENT-TYPE LITERALS (both directions). Every `components.messages.*.name`
     must be declared as an event type in that service's `src/main` — either
     `override val eventType = "X"` (a `DomainEvent` subclass) or `const val EVENT_TYPE = "X"`
     (the plain-data-class shape notification-service uses) — and every such literal must have a
     message. This is what catches an event added in code and never documented.

  C. PAYLOAD PROPERTIES vs PRIMARY-CONSTRUCTOR PROPERTIES (both directions). The payload schema is
     resolved through `$ref` and `allOf` into a flat property-name set and compared with the
     producing data class's primary-constructor property names, plus the six fields `DomainEvent`
     contributes (`eventId`, `aggregateId`, `aggregateType`, `eventType`, `version`, `occurredAt`)
     when the class extends it. This is the check with teeth: it is what would have caught the
     #3410 class of defect, where the aggregate carried `validFrom`/`validTo`/`perTransactionLimit`
     and the events did not, so consumers read fields the producer never sent.

WHAT IT DELIBERATELY DOES NOT CHECK
-----------------------------------
TYPES. A property's Kotlin type and its JSON Schema type are related by Jackson's serialization,
not by name — `Set<DelegationCapability>` renders as an array of strings, `Instant` as a string,
and `EventMoney` as a nested object. Asserting a mapping would mean modelling the ObjectMapper's
configuration, and getting it subtly wrong produces exactly the confident-wrong-answer failure this
repo keeps re-learning. Names are compared because names are unambiguous; a type change is left to
`check-event-schema-compat.py`, which diffs them against the PR base where the comparison is
like-for-like. Nested object properties are likewise not descended into: `perTransactionLimit`
is compared as a name, its `amount`/`currency` are not.

It also does not validate AsyncAPI 3.0 structurally — that is an upstream linter's job, and this
gate must not become a bad reimplementation of one.

ENFORCED, not advisory. It cannot report a pre-existing violation into a baseline, because it has
none: the only two contracts in the tree agree with their code today, and this gate exists to keep
that true as the remaining 36 grandfathered topics are migrated. A gate over a generated-from-
reality claim is a contradiction as an advisory (ADR-0071 lesson) — a red here means the committed
document does not match the code, and there is no judgement left to exercise.

Usage:  check-event-contract-code-agreement.py [--self-test]
Exit:   0 clean, 1 a contract disagrees with the code it claims to describe
"""

from __future__ import annotations

import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]
CONTRACTS_DIRNAME = "openbank-contracts"

# Fields `DomainEvent` itself contributes to the serialized payload. `occurredAt` is a constructor
# parameter of the base class and re-declared as an `override val` on every subclass, so it also
# appears in the subclass's own parameter list; listing it here is harmless and makes the set
# correct for any subclass that ever stops re-declaring it.
DOMAIN_EVENT_FIELDS = frozenset(
    {"eventId", "aggregateId", "aggregateType", "eventType", "version", "occurredAt"}
)

# `override val eventType = "X"` — a DomainEvent subclass declaring its own type.
EVENT_TYPE_OVERRIDE_RE = re.compile(
    r"""\boverride\s+val\s+eventType\s*(?::\s*String\s*)?=\s*["']([^"']+)["']"""
)
# `const val EVENT_TYPE: String = "X"` — the plain-data-class shape (notification-service).
EVENT_TYPE_CONST_RE = re.compile(
    r"""\bconst\s+val\s+EVENT_TYPE\s*(?::\s*String\s*)?=\s*["']([^"']+)["']"""
)

# A top-level `data class Name(` opening a primary constructor.
DATA_CLASS_RE = re.compile(r"^data class\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(", re.MULTILINE)


def strip_kotlin_comments(src: str) -> str:
    """Remove // and /* */ comments. Kotlin BLOCK COMMENTS NEST, so track depth rather than
    matching the first `*/` — a KDoc containing `/*` otherwise closes early and the rest of the
    file is parsed as comment (or vice versa). String literals are not tracked: a `//` inside a
    string would be over-stripped, which can only ever HIDE a declaration from this gate, never
    invent one, and no event declaration in the fleet has that shape.
    """
    out: list[str] = []
    i, n, depth = 0, len(src), 0
    while i < n:
        two = src[i : i + 2]
        if depth:
            if two == "/*":
                depth += 1
                i += 2
                continue
            if two == "*/":
                depth -= 1
                i += 2
                continue
            # Keep newlines so line-oriented structure survives.
            out.append("\n" if src[i] == "\n" else " ")
            i += 1
            continue
        if two == "/*":
            depth += 1
            i += 2
            continue
        if two == "//":
            j = src.find("\n", i)
            if j == -1:
                break
            out.append("\n")
            i = j + 1
            continue
        out.append(src[i])
        i += 1
    return "".join(out)


def balanced_span(src: str, open_idx: int) -> tuple[int, int] | None:
    """Given the index of an opening `(`, return (start+1, index_of_matching_close)."""
    depth = 0
    for i in range(open_idx, len(src)):
        ch = src[i]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return open_idx + 1, i
    return None


def class_body(src: str, ctor_end: int) -> str:
    """The data class's OWN body — from its opening brace to the matching close, or empty.

    Bounding this at "the next top-level `data class`" instead is wrong in a way that reports
    AGREEMENT rather than a finding, which is the direction that never announces itself. A file
    laid out as

        data class Payload(val a: String, val b: String)

        class SomethingPublisher {
            companion object { const val EVENT_TYPE = "x.y" }
        }

    would attribute the publisher's literal to `Payload`, or to whichever data class precedes it —
    so the contract for `x.y` gets compared against the wrong class's properties. Both live
    producers of this shape are in customer-edge (`OnboardingFunnelPublisher`,
    `FeedbackPublisher`); nothing is wrong today only because that service has no contract yet.

    A data class with no body (the common single-line case) yields "", which is correct: it
    declares no literal, so it is simply not indexed.
    """
    i = ctor_end + 1
    n = len(src)
    while i < n:
        ch = src[i]
        if ch == "{":
            depth = 0
            for j in range(i, n):
                if src[j] == "{":
                    depth += 1
                elif src[j] == "}":
                    depth -= 1
                    if depth == 0:
                        return src[i + 1 : j]
            return src[i + 1 :]
        # A newline followed by a non-space character is the next top-level declaration, so this
        # data class has no body of its own.
        if ch == "\n" and i + 1 < n and src[i + 1] not in " \t\r\n":
            return ""
        i += 1
    return ""


def split_top_level(params: str) -> list[str]:
    """Split a Kotlin parameter list on top-level commas, aware of (), <>, [] and {}.

    `>` only closes a generic when depth is positive and it is not part of an arrow `->`, so a
    lambda-typed or comparison-defaulted parameter cannot drive depth negative and silently merge
    the parameters that follow it into one.
    """
    parts: list[str] = []
    cur: list[str] = []
    depth = 0
    prev = ""
    for ch in params:
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif ch == "<":
            depth += 1
        elif ch == ">" and depth > 0 and prev != "-":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
        prev = ch
    if "".join(cur).strip():
        parts.append("".join(cur))
    return parts


PARAM_NAME_RE = re.compile(r"\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*:")


def parse_event_classes(service_dir: pathlib.Path) -> dict[str, dict]:
    """Map declared event-type literal -> {class, props, domain_event, path}.

    A class is indexed only if it declares an event-type literal, so ordinary data classes and
    consumer-side string comparisons against someone else's `eventType` are never picked up.
    """
    by_event_type: dict[str, dict] = {}
    src_root = service_dir / "src" / "main" / "kotlin"
    if not src_root.is_dir():
        return by_event_type
    for kt in sorted(src_root.rglob("*.kt")):
        src = strip_kotlin_comments(kt.read_text(encoding="utf-8", errors="replace"))
        for m in DATA_CLASS_RE.finditer(src):
            name = m.group(1)
            span = balanced_span(src, m.end() - 1)
            if span is None:
                continue
            ctor_start, ctor_end = span
            props = [
                pm.group(1)
                for part in split_top_level(src[ctor_start:ctor_end])
                if (pm := PARAM_NAME_RE.search(part))
            ]
            body = class_body(src, ctor_end)
            supertypes = src[ctor_end : ctor_end + 200]
            domain_event = bool(re.search(r"\bDomainEvent\s*\(", supertypes))
            literal = None
            om = EVENT_TYPE_OVERRIDE_RE.search(body)
            if om:
                literal = om.group(1)
            else:
                cm = EVENT_TYPE_CONST_RE.search(body)
                if cm:
                    literal = cm.group(1)
            if literal:
                by_event_type[literal] = {
                    "class": name,
                    "props": set(props),
                    "domain_event": domain_event,
                    "path": str(kt.relative_to(REPO)),
                }
    return by_event_type


def produced_topics(service_dir: pathlib.Path) -> set[str]:
    import yaml

    cfg = service_dir / "src" / "main" / "resources" / "application.yaml"
    if not cfg.is_file():
        return set()
    try:
        doc = yaml.safe_load(cfg.read_text(encoding="utf-8")) or {}
    except Exception:  # noqa: BLE001 - a malformed config is another gate's problem
        return set()
    outgoing = (((doc.get("mp") or {}).get("messaging") or {}).get("outgoing")) or {}
    return {
        _resolve_topic(c["topic"])
        for c in outgoing.values()
        if isinstance(c, dict) and isinstance(c.get("topic"), str)
    }


ENV_DEFAULT_RE = re.compile(r"^\$\{[A-Za-z_][A-Za-z0-9_]*:(?P<default>[^}]*)\}$")


def _resolve_topic(raw: str) -> str:
    """`${CASE_OUTBOX_TOPIC:proposal-events}` -> `proposal-events`.

    SmallRye expands these at boot, so the committed default IS the address the service publishes
    to unless an env override says otherwise — and a contract can only ever document the name, not
    the override. Comparing the raw string instead produced three findings against
    openbank-case-coordinator-agent that were all one non-defect: the contract said
    `proposal-events`, the config said `${CASE_OUTBOX_TOPIC:proposal-events}`, and the gate called
    that both an undocumented topic and an unproduced channel at once.

    A topic with no default (`${VAR}`) is deliberately left as-is: there is no committed name to
    compare, so reporting the mismatch is correct.
    """
    m = ENV_DEFAULT_RE.match(raw.strip())
    return m.group("default").strip() if m else raw


def resolve_ref(doc: dict, ref: str):
    """Resolve a local JSON pointer `#/a/b/c`. Returns None for an external or unresolvable ref."""
    if not ref.startswith("#/"):
        return None
    node = doc
    for token in ref[2:].split("/"):
        token = token.replace("~1", "/").replace("~0", "~")
        if not isinstance(node, dict) or token not in node:
            return None
        node = node[token]
    return node


def schema_properties(doc: dict, node, seen: frozenset[str] = frozenset()) -> set[str]:
    """Flatten a payload schema to its top-level property NAMES, through $ref / allOf / anyOf / oneOf.

    `anyOf`/`oneOf` are unioned rather than intersected: this gate asserts that a name appearing in
    the document exists in the code, and a name reachable through any branch is a name a consumer
    author can read off the document.
    """
    if not isinstance(node, dict):
        return set()
    if "$ref" in node:
        ref = node["$ref"]
        if ref in seen:
            return set()
        target = resolve_ref(doc, ref)
        return schema_properties(doc, target, seen | {ref})
    props: set[str] = set()
    if isinstance(node.get("properties"), dict):
        props |= set(node["properties"].keys())
    for key in ("allOf", "anyOf", "oneOf"):
        for sub in node.get(key) or []:
            props |= schema_properties(doc, sub, seen)
    return props


def check_contract(path: pathlib.Path) -> list[str]:
    import yaml

    service = path.parent.name
    service_dir = REPO / service
    errors: list[str] = []
    rel = path.relative_to(REPO)
    # A malformed contract is a FINDING, not a crash. Letting the ScannerError escape ends the run
    # with a traceback and no verdict about any OTHER contract either — and a gate that dies is
    # indistinguishable from a gate that was never wired. It also hides which file is at fault
    # behind a stack trace. This is not hypothetical: openbank-fraud-service/asyncapi.yaml carried
    # an unquoted backtick-leading `description:` on main, which no gate could see because the
    # existing coverage check counts contract FILES without parsing their bodies — it reported
    # "5 with an event contract" including one it could not read.
    try:
        doc = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    except yaml.YAMLError as ex:
        first = str(ex).splitlines()[0]
        return [
            f"{rel}: is not parseable YAML ({first}). A contract nothing can read documents "
            f"nothing — quote a scalar that starts with a backtick, a colon or an at-sign."
        ]

    if not service_dir.is_dir():
        return [
            f"{rel}: no service directory `{service}/` — a contract must live under the name of the "
            f"service that produces it, or nothing can ever verify it"
        ]

    # ---- A. channels vs produced topics -------------------------------------------------
    channels = doc.get("channels") or {}
    addresses = {
        c["address"] for c in channels.values() if isinstance(c, dict) and isinstance(c.get("address"), str)
    }
    topics = produced_topics(service_dir)
    for extra in sorted(addresses - topics):
        errors.append(
            f"{rel}: channel address `{extra}` is not a topic {service} produces. Its "
            f"application.yaml declares: {sorted(topics) or 'none'}"
        )
    for missing in sorted(topics - addresses):
        errors.append(
            f"{rel}: {service} produces `{missing}` but no channel declares that address. A "
            f"contracted service adding a topic must document it — the coverage ratchet only "
            f"checks that this FILE exists, so it cannot see this."
        )

    # ---- B. message names vs event-type literals ----------------------------------------
    messages = ((doc.get("components") or {}).get("messages")) or {}
    declared = parse_event_classes(service_dir)
    doc_names: set[str] = set()
    for key, msg in messages.items():
        if not isinstance(msg, dict):
            continue
        doc_names.add(msg.get("name") or key)
    for name in sorted(doc_names - set(declared)):
        errors.append(
            f"{rel}: message `{name}` is declared in the contract but no class in {service}/src/main "
            f"declares that event type (`override val eventType = \"{name}\"` or "
            f'`const val EVENT_TYPE = "{name}"`). Either the producer was renamed and the contract '
            f"was not, or this message describes an event that is never emitted."
        )
    for name in sorted(set(declared) - doc_names):
        info = declared[name]
        errors.append(
            f"{rel}: {info['path']} declares event type `{name}` ({info['class']}) and the contract "
            f"has no message for it — an emitted event no consumer has been told about."
        )

    # ---- C. payload properties vs constructor properties --------------------------------
    for key, msg in messages.items():
        if not isinstance(msg, dict):
            continue
        name = msg.get("name") or key
        info = declared.get(name)
        if info is None:
            continue  # already reported by B; do not double-report
        payload = msg.get("payload")
        if payload is None:
            errors.append(f"{rel}: message `{name}` declares no payload schema.")
            continue
        documented = schema_properties(doc, payload)
        actual = set(info["props"])
        if info["domain_event"]:
            actual |= set(DOMAIN_EVENT_FIELDS)
        for extra in sorted(documented - actual):
            errors.append(
                f"{rel}: message `{name}` documents property `{extra}`, which "
                f"{info['class']} ({info['path']}) does not carry. A consumer written against this "
                f"document reads a field the producer never sends."
            )
        for missing in sorted(actual - documented):
            errors.append(
                f"{rel}: {info['class']} ({info['path']}) carries property `{missing}` and message "
                f"`{name}` does not document it — an undocumented field on a published event."
            )
    return errors


def contracts() -> list[pathlib.Path]:
    root = REPO / CONTRACTS_DIRNAME
    return sorted(root.glob("*/asyncapi.yaml")) if root.is_dir() else []


def run() -> int:
    found = contracts()
    if not found:
        print(f"OK: no {CONTRACTS_DIRNAME}/*/asyncapi.yaml in the tree — nothing to verify.")
        return 0
    errors: list[str] = []
    for path in found:
        errors.extend(check_contract(path))
    if errors:
        print("event contract does not agree with the code it describes (ADR-0006, #1916):\n")
        for e in errors:
            print(f"  ✗ {e}")
        print(
            f"\n{len(errors)} disagreement(s) across {len(found)} contract(s). Fix the contract or "
            f"the producer — do not delete the message to make this green."
        )
        return 1
    print(
        f"OK: {len(found)} event contract(s) agree with their producing code "
        f"(channels, message names and payload properties)."
    )
    return 0


# ---------------------------------------------------------------------------------------------
# Self-test. A gate that has only ever passed is unfalsified — and this one passes on today's tree
# by construction, so its green says nothing until each rule has been shown to fire. Every case
# below is a MUTATION of the real contracts and real sources, copied into a temp tree, so the
# harness cannot drift away from the shapes the gate meets in practice.
# ---------------------------------------------------------------------------------------------
def self_test() -> int:
    import shutil
    import tempfile

    global REPO
    real_repo = REPO
    src_contracts = real_repo / CONTRACTS_DIRNAME
    if not src_contracts.is_dir():
        print("self-test: no contracts in tree to mutate", file=sys.stderr)
        return 1

    def build(tmp: pathlib.Path, service: str) -> pathlib.Path:
        """Copy one service + its contract into an isolated tree."""
        shutil.copytree(src_contracts / service, tmp / CONTRACTS_DIRNAME / service)
        for sub in ("src/main/kotlin", "src/main/resources"):
            s = real_repo / service / sub
            if s.is_dir():
                shutil.copytree(s, tmp / service / sub)
        return tmp

    cases: list[tuple[str, str, callable, str]] = []

    def case(desc: str, service: str, mutate, expect: str):
        cases.append((desc, service, mutate, expect))

    def edit(path: pathlib.Path, old: str, new: str):
        text = path.read_text(encoding="utf-8")
        if old not in text:
            raise AssertionError(f"self-test fixture stale: {old!r} not in {path}")
        path.write_text(text.replace(old, new, 1), encoding="utf-8")

    # A: a channel address the service does not produce.
    case(
        "channel address not produced",
        "openbank-notification-service",
        lambda t: edit(
            t / CONTRACTS_DIRNAME / "openbank-notification-service" / "asyncapi.yaml",
            "address: openbank.notification.outcomes.v1",
            "address: openbank.notification.outcomes.v9",
        ),
        "is not a topic",
    )
    # A (other direction): a produced topic with no channel.
    case(
        "produced topic undocumented",
        "openbank-notification-service",
        lambda t: edit(
            t / "openbank-notification-service" / "src/main/resources/application.yaml",
            "topic: openbank.notification.outcomes.v1",
            "topic: openbank.notification.outcomes.v2",
        ),
        "no channel declares that address",
    )
    # B0: the event-type literal declared by a NON-data class that follows the payload.
    #
    # This is the case the gate could not see: with the class body bounded at "the next top-level
    # data class", the publisher's literal was attributed to whichever data class preceded it, and
    # the contract was then compared against the WRONG class's properties — reported as agreement,
    # never as a finding. Two live producers already have this layout
    # (customer-edge's OnboardingFunnelPublisher and FeedbackPublisher); they are only harmless
    # because that service carries no contract yet.
    case(
        "event-type literal owned by a following non-data class",
        "openbank-notification-service",
        lambda t: edit(
            t / "openbank-notification-service" / "src/main/kotlin/com/openbank/notification/domain/model/NotificationOutcome.kt",
            ") {\n    companion object {",
            ")\n\nclass NotificationOutcomePublisher {\n    companion object {",
        ),
        "no class in",
    )
    # B: a message the code does not declare.
    case(
        "message with no producing class",
        "openbank-notification-service",
        lambda t: edit(
            t / CONTRACTS_DIRNAME / "openbank-notification-service" / "asyncapi.yaml",
            "name: NotificationOutcome",
            "name: NotificationOutcomeRenamed",
        ),
        "no class in",
    )
    # B (other direction): an event type in code with no message.
    case(
        "event type undocumented",
        "openbank-delegation-service",
        lambda t: edit(
            t / "openbank-delegation-service" / "src/main/kotlin/com/openbank/delegation/domain/event/DelegationEvents.kt",
            'override val eventType = "DelegationExpired"',
            'override val eventType = "DelegationLapsed"',
        ),
        "has no message for it",
    )
    # C: a documented property the class does not carry.
    case(
        "documented property absent from the class",
        "openbank-delegation-service",
        lambda t: edit(
            t / CONTRACTS_DIRNAME / "openbank-delegation-service" / "asyncapi.yaml",
            "        validFrom:\n",
            "        validUntilSomething:\n          type: string\n        validFrom:\n",
        ),
        "does not carry",
    )
    # C (other direction): a constructor property the contract omits — the #3410 defect shape.
    case(
        "class property undocumented (#3410 shape)",
        "openbank-delegation-service",
        lambda t: edit(
            t / "openbank-delegation-service" / "src/main/kotlin/com/openbank/delegation/domain/event/DelegationEvents.kt",
            "    val reason: String,\n    override val occurredAt: Instant,\n) : DomainEvent(occurredAt) {\n    override val aggregateType = \"DelegationGrant\"\n    override val eventType = \"DelegationRevoked\"",
            "    val reason: String,\n    val suppressedBy: String,\n    override val occurredAt: Instant,\n) : DomainEvent(occurredAt) {\n    override val aggregateType = \"DelegationGrant\"\n    override val eventType = \"DelegationRevoked\"",
        ),
        "does not document it",
    )

    failures = 0
    for desc, service, mutate, expect in cases:
        with tempfile.TemporaryDirectory() as td:
            tmp = pathlib.Path(td)
            build(tmp, service)
            REPO = tmp
            try:
                clean = run()
            finally:
                pass
            if clean != 0:
                print(f"  FAIL [{desc}]: the UNMUTATED fixture is already red — fixture is wrong")
                failures += 1
                REPO = real_repo
                continue
            mutate(tmp)
            import io
            import contextlib

            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                rc = run()
            out = buf.getvalue()
            REPO = real_repo
            if rc == 0 or expect not in out:
                print(f"  FAIL [{desc}]: expected exit 1 containing {expect!r}, got {rc}\n{out}")
                failures += 1
            else:
                print(f"  ok   [{desc}]")
    if failures:
        print(f"\nself-test: {failures} of {len(cases)} case(s) FAILED")
        return 1
    print(f"\nself-test: all {len(cases)} case(s) detected.")
    return 0


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        sys.exit(self_test())
    sys.exit(run())
