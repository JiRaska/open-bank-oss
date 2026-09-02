#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Every topic a service reads or writes must be covered by its own KafkaUser ACLs.

Why this exists
---------------
`check-kafka-topics-exist.py` (#2598) closed one half of a two-list problem: a service must not
name a topic that does not exist. This closes the other half — a service must not name a topic
its Kafka principal is not allowed to touch.

The two lists live in different trees and were kept in step by hand:

    openbank-<svc>/src/main/resources/application.yaml   mp.messaging.{incoming,outgoing}.*.topic(s)
    openbank-infra/gitops/**/KafkaUser                   spec.authorization.acls

`analytics-sink` is the proof that hand-keeping does not hold. #2629 corrected its SUBSCRIPTION
from the never-existing `openbank.account.events` / `openbank.transaction.events` to the real
`openbank.accounts.account.created` / `openbank.transactions.transaction.initiated` — and left the
ACL list on the dead names. So after the fix the sink was subscribed to the right topics with no
Read on either of them (#2598).

The broker runs `allow.everyone.if.no.acl.found=false`, and a denial here is as quiet as the
missing topic was: the consumer retries, the pod stays ready, the group stays joined, lag reads
zero, and every dashboard agrees the topic simply has no traffic.

WHAT IT CHECKS
--------------
For each `openbank-*/src/main/resources/application.yaml`, each literal `openbank.*` topic under
an `mp.messaging.incoming.*` channel needs `Read`, and each under `mp.messaging.outgoing.*` needs
`Write`, from a KafkaUser whose name matches the service. `patternType: prefix` rules are honoured.

WHAT IT DELIBERATELY DOES NOT CHECK
-----------------------------------
Topics a service names in code rather than in `application.yaml` (a dispatcher that derives the
topic at runtime), and services with no KafkaUser at all — reported as `no KafkaUser` notices
rather than failures, because "this service does not use the mTLS listener" is a legitimate state
and this check cannot tell it apart from a missing user.

Usage:  check-kafka-acl-coverage.py [--enforce] [--selftest]
Advisory by default (prints ::warning, exits 0) per the repo convention; --enforce fails the build.
"""

from __future__ import annotations

import argparse
import pathlib
import sys
import tempfile

import yaml

import gatelib

REPO = pathlib.Path(__file__).resolve().parents[2]
GITOPS = REPO / "openbank-infra" / "gitops"

# Coverage gaps that exist TODAY and are not fixed by this change, each with the reason it is a
# separate piece of work. The list may only SHRINK: an entry that is no longer a gap is itself
# reported, so a temporary exception cannot quietly become permanent (same idiom as the pact
# gate's KNOWN_UNCOVERED and scheduled_methods' allowlists).
#
# These were found by this check on its first run and are NOT drive-by-fixable: granting a
# principal a new topic permission is a live-broker change whose blast radius wants its own
# review, and several of them may instead mean the service publishes over a different principal.
# Tracked in #2598's follow-up.
KNOWN_GAPS: dict[str, str] = {}
# EMPTY, and that is the point. All twelve original entries were granted in #3271's follow-up.
#
# They were baselined rather than fixed because a grant is a live-broker change and it was not
# settled from the repo alone whether a service publishes under a different principal. Both halves
# have since been answered with evidence: every workload mounts `<service>-kafka-keystore`, whose
# ExternalSecret extracts the KafkaUser of the same name, so the name-based match this check makes
# is exact; and `components/kafka/kafka.yaml:118` sets
# `allow.everyone.if.no.acl.found: "false"`, so a topic with no ACL is denied, not open.
#
# What that baseline cost: domestic-payment's own event topic had no Write grant and 13 outbox
# rows went DEAD before anyone looked (#3271). "Declared debt" and "measured incident" were the
# same fact the whole time — the entry just made it easy not to check.
#
# An entry added here needs a reason, and the check fails on a stale declaration in BOTH
# directions, so a new gap cannot quietly become permanent.


def _walk(node: object, path: list[str], out: list[tuple[list[str], object]]) -> None:
    if isinstance(node, dict):
        for key, value in node.items():
            _walk(value, path + [str(key)], out)
    elif isinstance(node, list):
        for value in node:
            _walk(value, path, out)
    else:
        out.append((path, node))


def required(app_yaml: pathlib.Path) -> set[tuple[str, str]]:
    """{(topic, "Read"|"Write")} for one service, from its channel config.

    The direction comes from the channel's own key (`incoming` vs `outgoing`), which is what makes
    the operation checkable at all — a bare topic name says nothing about which way it flows.

    One exception, and it is not a special case so much as the same rule applied one level down:
    an incoming channel's `dead-letter-queue.topic` is WRITE-ONLY from this service's side. The
    SmallRye Kafka connector parks a failed message there with its own producer and never
    subscribes to it; nothing in this repo consumes an `openbank.dlq.*` topic — no channel, no
    redrive tool. Classifying it by the enclosing `incoming` key would demand a Read ACL nobody
    uses, which is a widening, not a fix (#5751).
    """
    try:
        doc = gatelib.load_yaml(app_yaml)
    except yaml.YAMLError:
        return set()
    flat: list[tuple[list[str], object]] = []
    _walk(doc, [], flat)
    out: set[tuple[str, str]] = set()
    for path, value in flat:
        leaf = path[-1] if path else ""
        if leaf not in ("topic", "topics", "dead-letter-queue.topic") or not isinstance(value, str):
            continue
        if leaf == "dead-letter-queue.topic" or "dead-letter-queue" in path:
            operation = "Write"          # the connector produces into the DLQ; it never reads it
        elif "incoming" in path:
            operation = "Read"
        elif "outgoing" in path:
            operation = "Write"
        else:
            continue
        for name in value.split(","):
            name = name.strip().strip("\"'")
            if name.startswith("openbank.") and "$" not in name and "{" not in name:
                out.add((name, operation))
    return out


def kafka_users() -> dict[str, list[tuple[str, str, set[str]]]]:
    """{user: [(topic-or-prefix, patternType, {operations})]} across every KafkaUser CR."""
    users: dict[str, list[tuple[str, str, set[str]]]] = {}
    for path in gatelib.rglob(GITOPS, "*.yaml"):
        try:
            docs = gatelib.load_yaml_all(path)
        except (yaml.YAMLError, UnicodeDecodeError):
            continue
        for doc in docs:
            if not isinstance(doc, dict) or doc.get("kind") != "KafkaUser":
                continue
            name = (doc.get("metadata") or {}).get("name")
            if not name:
                continue
            acls = ((doc.get("spec") or {}).get("authorization") or {}).get("acls") or []
            for acl in acls:
                if not isinstance(acl, dict):
                    continue
                resource = acl.get("resource") or {}
                if resource.get("type") != "topic" or not resource.get("name"):
                    continue
                users.setdefault(name, []).append(
                    (resource["name"], resource.get("patternType", "literal"), set(acl.get("operations") or [])),
                )
    return users


def covers(topic: str, operation: str, rules: list[tuple[str, str, set[str]]]) -> bool:
    for name, pattern, operations in rules:
        matched = topic.startswith(name) if pattern == "prefix" else topic == name
        if matched and operation in operations:
            return True
    return False


def selftest() -> int:
    """Feed `covers` inputs it MUST flag and inputs it must NOT.

    Once the fleet is clean the flagging branch never executes again, and a gate that has only
    ever passed is unfalsified — so this runs on every CI invocation.
    """
    rules = [
        ("openbank.party.events", "literal", {"Read", "Describe"}),
        ("openbank.payments.swift.", "prefix", {"Write", "Describe"}),
    ]
    cases = [
        ("openbank.party.events", "Read", True),
        ("openbank.party.events", "Write", False),        # right topic, wrong operation
        ("openbank.parties.events", "Read", False),        # the #2598 shape: near-miss name
        ("openbank.payments.swift.event", "Write", True),  # prefix rule
        ("openbank.payments.swift.event", "Read", False),
    ]
    for topic, operation, expected in cases:
        if covers(topic, operation, rules) != expected:
            verb = "missed" if expected else "wrongly flagged"
            print(f"selftest FAIL: {verb} {topic!r} {operation}")
            return 1
    if not kafka_users():
        print("selftest FAIL: no KafkaUser CRs found — the scan itself is broken.")
        return 1

    # Direction classification, including the DLQ carve-out. Without these the gate would demand a
    # Read ACL on every `openbank.dlq.*` topic (#5751) and nothing here could tell.
    fixture = (
        "mp:\n"
        "  messaging:\n"
        "    incoming:\n"
        "      party-events-in:\n"
        "        topic: openbank.party.events\n"
        "        failure-strategy: dead-letter-queue\n"
        "        dead-letter-queue:\n"
        "          topic: openbank.dlq.demo.party-events-in\n"
        "    outgoing:\n"
        "      demo-events-out:\n"
        "        topic: openbank.demo.events\n"
    )
    with tempfile.TemporaryDirectory() as tmp:
        fixture_path = pathlib.Path(tmp) / "application.yaml"
        fixture_path.write_text(fixture, encoding="utf-8")
        got = required(fixture_path)
    expected = {
        ("openbank.party.events", "Read"),
        ("openbank.demo.events", "Write"),
        ("openbank.dlq.demo.party-events-in", "Write"),
    }
    if got != expected:
        print(f"selftest FAIL: direction classification is {sorted(got)}, expected {sorted(expected)}")
        return 1
    if ("openbank.dlq.demo.party-events-in", "Read") in got:
        print("selftest FAIL: a dead-letter topic was classified as consumed")
        return 1

    print(
        f"selftest OK: {len(cases)} coverage case(s), both directions (flags the near-miss, spares the "
        f"prefix match), plus {len(expected)} direction case(s) incl. the write-only DLQ.",
    )
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--selftest", action="store_true", help="verify the check can fail")
    args = ap.parse_args()
    if args.selftest:
        return selftest()

    users = kafka_users()
    findings: list[str] = []
    used_gaps: set[str] = set()
    checked = 0

    for app_yaml in gatelib.glob(REPO, "openbank-*/src/main/resources/application.yaml"):
        service = app_yaml.parts[len(REPO.parts)]
        wanted = required(app_yaml)
        if not wanted:
            continue
        short = service.removeprefix("openbank-")
        candidates = [u for u in users if u in (short, short.removesuffix("-service"))]
        if not candidates:
            print(f"::notice::{service} references {len(wanted)} topic(s) but has no KafkaUser — "
                  f"not checkable here (it may not use the mTLS listener).")
            continue
        rules = [rule for user in candidates for rule in users[user]]
        for topic, operation in sorted(wanted):
            checked += 1
            if covers(topic, operation, rules):
                continue
            key = f"{service}#{topic}#{operation}"
            if key in KNOWN_GAPS:
                used_gaps.add(key)
                print(f"::notice::known gap {key}: {KNOWN_GAPS[key]}")
                continue
            findings.append(
                f"::error file={app_yaml.relative_to(REPO)}::{service} uses {topic} "
                f"({'consumes' if operation == 'Read' else 'produces'}) but its KafkaUser "
                f"({'/'.join(candidates)}) has no {operation} ACL covering it. The broker runs "
                f"allow.everyone.if.no.acl.found=false, so this is denied at runtime — silently, "
                f"as a retry loop rather than a red pod (#2598).",
            )

    for key in sorted(set(KNOWN_GAPS) - used_gaps):
        findings.append(
            f"::error::stale KNOWN_GAPS entry {key} — that topic/operation is now covered (or no "
            f"longer referenced). Remove it, so the list can only shrink.",
        )

    for line in findings:
        print(line if args.enforce else line.replace("::error", "::warning", 1))
    verdict = "clean." if not findings else f"{len(findings)} finding(s) above."
    print(f"check-kafka-acl-coverage: {checked} (topic, operation) pair(s) checked across "
          f"{len(users)} KafkaUser(s), {len(KNOWN_GAPS)} known gap(s) — {verdict}")
    return 1 if findings and args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
