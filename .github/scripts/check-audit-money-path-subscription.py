#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Every topic a money-path service PRODUCES must reach the audit trail — in all three places.

Why this exists
---------------
#5859 found that audit-service had never subscribed to `openbank.delegation.events`, so no
delegation event of any kind had ever reached the audit trail. #6035 then applied the same probe
across `money_path_services` and found five more — ledger, sdd, interest, fraud and billing — each
missing from all three places at once. That is the signature of a set that was never enumerated,
not six independent oversights, and it is exactly the failure a hand-kept list produces: it reads
as complete because it is short.

A subscription needs three things, and each one alone is silent when absent:

  1. the topic in audit-service's `mp.messaging.incoming.audit-events-in.topics` list
     — absent: nothing is read, no error anywhere;
  2. an entry in `TopicAttribution` (`EventAttribution.kt`)
     — absent: the row is written with `source_service = "unknown"`. `audit_entries` is
       append-only at the DB (`no_update_audit`/`no_delete_audit` are `DO INSTEAD NOTHING`, so an
       UPDATE affects zero rows and REPORTS SUCCESS) and `source_service` is chain-hashed into
       `record_hash` — so a wrong attribution cannot be corrected in place, ever;
  3. a Read ACL on the `audit-service` KafkaUser
     — absent: the subscription is ACL-denied at runtime (the broker runs
       `allow.everyone.if.no.acl.found=false`), as a retry loop rather than a red pod (#5338/#3271).

An unaudited money-path topic is indistinguishable from a quiet period. Nothing errors, no pod is
unhealthy, lag reads zero, and the audit trail simply has no rows.

HOW THE PRODUCED SET IS DERIVED (and why not by grepping topic names)
---------------------------------------------------------------------
Producers are resolved from `mp.messaging.outgoing.<channel>.topic` in each money-path service's
own `application.yaml` — the connector's own declaration. A literal grep for topic strings cannot
do this job: this repo builds event payloads two ways, and a serialised data class has no literal
anywhere in the tree (the key exists only at runtime as a Kotlin property name), so a grep returns
a confident false clean over exactly the producers it structurally cannot see (#3883).

A money-path service that declares no outgoing Kafka channel at all — settlement-service and
vop-service today — therefore contributes nothing and is reported as `no outgoing Kafka channel`,
which is correctly ABSENT rather than missing. That distinction is derived from the config, not
declared here.

SCOPE IS DERIVED, NOT LISTED
----------------------------
The set of services checked comes from `rules.yaml: money_path_services`; the set of topics from
their own channel config; the three subscription artefacts from audit-service's config, source and
KafkaUser. Nothing about the coverage set is maintained by hand. The only hand-kept list is
`KNOWN_GAPS` below — the debt that exists TODAY — and it may only SHRINK: an entry that is no
longer a gap is itself reported as an error, in enforced mode, so a temporary exception cannot
quietly become permanent (the idiom of `check-kafka-acl-coverage.py`'s KNOWN_GAPS and the pact
gate's KNOWN_UNCOVERED).

Usage:  check-audit-money-path-subscription.py [--root .] [--enforce] [--selftest]
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import yaml  # noqa: E402

import gatelib  # noqa: E402

AUDIT_SERVICE = "openbank-audit-service"
AUDIT_CHANNEL = "audit-events-in"
AUDIT_KAFKAUSER = "audit-service"

# Gaps that exist TODAY, each keyed `<service>#<topic>#<place>` and carrying the issue that owns
# it. Fixing one means DELETING its entries here — and the check fails on an entry that is no
# longer a gap, so the list cannot drift in either direction.
#
# What is left after #6035's first backfill. Four of the original seven -- ledger
# (openbank.ledger.journal.posted), sdd, interest and fraud -- were wired in all three places and
# their entries deleted here; a stale entry is itself an error, so a half-fix cannot pass.
#
# The three that remain each need a decision this ratchet should not make silently:
#  - billing has no KafkaTopic CR for openbank.billing.fee.event at all, so a Read grant would
#    point at an undeclared topic -- a second way to get silence, and the topic's ownership is
#    billing's call, not audit's.
#  - standing-order and psd2 were found by THIS check and named in neither #5859 nor #6035, so
#    nothing has yet reviewed whether their streams belong in the audit trail.
_GAP_ISSUE = {
    "openbank-billing-service": "#6035 - also has no KafkaTopic CR (see the issue)",
    # Found by THIS check on its first run, and by nothing before it: neither #5859's probe nor
    # #6035's enumeration named these two. That is the argument for deriving the scope rather
    # than probing it by hand - a careful manual enumeration of the same set, done twice, was
    # still two short.
    "openbank-standing-order-service": "#6035 - found by this check, not named in the issue",
    # openbank-psd2-service left the list in #8510 not by being subscribed but by ceasing to be a
    # producer at all: the psd2_outbox apparatus had zero writers and was deleted together with
    # the openbank.psd2.events topic, so there is nothing left to audit-subscribe. A service that
    # publishes no events has no audit-subscription gap.
}
_GAP_TOPICS = {
    "openbank-billing-service": "openbank.billing.fee.event",
    "openbank-standing-order-service": "openbank.standing-orders.order.event",
}

# Topics a money-path service produces that are deliberately NOT audit subjects, each with the
# reason. Separate from KNOWN_GAPS because these are not debt: nothing here is meant to be fixed
# by subscribing. Like KNOWN_GAPS it fails when stale - an entry naming a topic no money-path
# service produces any more is reported - so the exclusion is the thing a human has to justify,
# and it cannot outlive its reason.
OUT_OF_SCOPE: dict[str, str] = {
    "openbank.notification.requests": (
        "not a domain-event stream: it is notification-service's own INBOX, a fan-in command "
        "topic four services write onto (account, sca, campaign, and notification itself). "
        "Auditing it would record requests to notify, not the money-path facts that caused them "
        "- those are already audited on each producer's own event topic."
    ),
}

# Gaps whose fix is an OPEN PR, so this check must accept BOTH states: still a gap while the PR is
# open, already fixed the moment it merges. Neither is an error - failing on the fixed state would
# red-gate `main` the instant that PR lands, purely because two PRs were in flight at once.
#
# This is deliberately NOT a hole in the ratchet. An entry here is reported as a ::warning naming
# the PR the moment its gap is closed, so it announces its own removal rather than waiting to be
# noticed, and it covers ONE topic against ONE named open PR. A new gap for a topic not listed here
# is an error exactly as before.
# Empty: `openbank.delegation.events` lived here while #5857 was open. This PR subscribes,
# attributes and ACL-grants it directly, so the topic is covered by the ratchet itself and the
# entry would now be a STALE declaration -- which this check reports as an error in its own right.
IN_FLIGHT: dict[str, str] = {}

PLACES = ("topics", "attribution", "acl")
KNOWN_GAPS: dict[str, str] = {
    f"{svc}#{_GAP_TOPICS[svc]}#{place}": _GAP_ISSUE[svc]
    for svc in _GAP_TOPICS
    for place in PLACES
}


def _walk(node: object, path: list[str], out: list[tuple[list[str], object]]) -> None:
    if isinstance(node, dict):
        for key, value in node.items():
            _walk(value, path + [str(key)], out)
    elif isinstance(node, list):
        for value in node:
            _walk(value, path, out)
    else:
        out.append((path, node))


def _topic_values(doc: object, direction: str) -> set[str]:
    """Literal `openbank.*` topics under `mp.messaging.<direction>.*.topic(s)`.

    `topics` (plural, the consumer form) is comma-separated; `topic` is singular. Interpolated
    values (`${...}`) are skipped — they are not resolvable from the repo alone.
    """
    flat: list[tuple[list[str], object]] = []
    _walk(doc, [], flat)
    out: set[str] = set()
    for path, value in flat:
        if not path or path[-1] not in ("topic", "topics") or not isinstance(value, str):
            continue
        if direction not in path:
            continue
        for name in value.split(","):
            name = name.strip().strip("\"'")
            if name.startswith("openbank.") and "$" not in name and "{" not in name:
                out.add(name)
    return out


def money_path_services(repo: pathlib.Path) -> list[str]:
    doc = gatelib.load_yaml(repo / "openbank-libs" / "governance" / "rules.yaml")
    entries = (doc or {}).get("money_path_services") or []
    return [str(e).strip() for e in entries if isinstance(e, str)]


def produced_topics(repo: pathlib.Path, service: str) -> set[str]:
    """Topics `service` declares it PRODUCES, from its own outgoing channel config."""
    app_yaml = repo / service / "src" / "main" / "resources" / "application.yaml"
    if not app_yaml.is_file():
        return set()
    try:
        doc = gatelib.load_yaml(app_yaml)
    except yaml.YAMLError:
        return set()
    return _topic_values(doc, "outgoing")


def subscribed_topics(repo: pathlib.Path) -> set[str]:
    """audit-service's entire subscription surface — every incoming channel's topic list."""
    app_yaml = repo / AUDIT_SERVICE / "src" / "main" / "resources" / "application.yaml"
    return _topic_values(gatelib.load_yaml(app_yaml), "incoming")


def attributed_topics(repo: pathlib.Path) -> set[str]:
    """Keys of `TopicAttribution.TOPIC_TO_SERVICE`, read as `"<topic>" to "<service>"` pairs."""
    src = repo / AUDIT_SERVICE / "src" / "main" / "kotlin" / "com" / "openbank" / "audit" / "application" / "EventAttribution.kt"
    if not src.is_file():
        return set()
    text = gatelib.read_text(src)
    return set(re.findall(r'"(openbank\.[A-Za-z0-9._-]+)"\s+to\s+"', text))


def audit_read_acl_topics(repo: pathlib.Path) -> set[tuple[str, str]]:
    """{(name, patternType)} the audit KafkaUser holds `Read` on."""
    out: set[tuple[str, str]] = set()
    for path in gatelib.rglob(repo / "openbank-infra" / "gitops", "*.yaml"):
        try:
            docs = gatelib.load_yaml_all(path)
        except (yaml.YAMLError, UnicodeDecodeError):
            continue
        for doc in docs:
            if not isinstance(doc, dict) or doc.get("kind") != "KafkaUser":
                continue
            if (doc.get("metadata") or {}).get("name") != AUDIT_KAFKAUSER:
                continue
            for acl in ((doc.get("spec") or {}).get("authorization") or {}).get("acls") or []:
                if not isinstance(acl, dict):
                    continue
                resource = acl.get("resource") or {}
                if resource.get("type") != "topic" or not resource.get("name"):
                    continue
                if "Read" in set(acl.get("operations") or []):
                    out.add((resource["name"], resource.get("patternType", "literal")))
    return out


def acl_covers(topic: str, rules: set[tuple[str, str]]) -> bool:
    for name, pattern in rules:
        if topic.startswith(name) if pattern == "prefix" else topic == name:
            return True
    return False


PLACE_CONSEQUENCE = {
    "topics": ("audit-service's incoming topics list", "nothing is read; no error is raised anywhere"),
    "attribution": (
        "TopicAttribution in EventAttribution.kt",
        'the row stores source_service="unknown", and audit_entries is append-only with '
        "source_service chain-hashed into record_hash, so it can never be corrected",
    ),
    "acl": (
        "the audit-service KafkaUser's Read ACLs",
        "the subscription is ACL-denied at runtime (allow.everyone.if.no.acl.found=false) as a "
        "silent retry loop, not a red pod (#5338)",
    ),
}


def evaluate(repo: pathlib.Path) -> tuple[list[tuple[str, str, str]], int, list[str], set[str]]:
    """-> (gaps, topics examined, services with no outgoing channel, OUT_OF_SCOPE topics seen)."""
    subscribed = subscribed_topics(repo)
    attributed = attributed_topics(repo)
    acls = audit_read_acl_topics(repo)
    gaps: list[tuple[str, str, str]] = []
    examined = 0
    silent: list[str] = []
    excluded: set[str] = set()
    for service in money_path_services(repo):
        topics = produced_topics(repo, service)
        if not topics:
            silent.append(service)
            continue
        for topic in sorted(topics):
            if topic in OUT_OF_SCOPE:
                excluded.add(topic)
                continue
            examined += 1
            if topic not in subscribed:
                gaps.append((service, topic, "topics"))
            if topic not in attributed:
                gaps.append((service, topic, "attribution"))
            if not acl_covers(topic, acls):
                gaps.append((service, topic, "acl"))
    return gaps, examined, silent, excluded


def selftest(repo: pathlib.Path) -> int:
    """Feed the deciding functions states they MUST reject, and states they must NOT.

    A gate is proven by what it PREVENTS. Once the fleet is clean the flagging branches never
    execute again, so this runs on every CI invocation.
    """
    cases: list[tuple[str, bool]] = []

    # 1. ACL matching, both directions.
    rules = {("openbank.party.events", "literal"), ("openbank.payments.swift.", "prefix")}
    cases.append(("acl literal hit", acl_covers("openbank.party.events", rules) is True))
    cases.append(("acl near-miss name", acl_covers("openbank.parties.events", rules) is False))
    cases.append(("acl prefix hit", acl_covers("openbank.payments.swift.event", rules) is True))

    # 2. The producer resolution reads the CHANNEL DIRECTION, not a topic literal anywhere.
    doc = yaml.safe_load(
        "mp:\n"
        "  messaging:\n"
        "    outgoing:\n"
        "      x-out:\n"
        "        topic: openbank.demo.out\n"
        "    incoming:\n"
        "      x-in:\n"
        "        topics: openbank.demo.a,openbank.demo.b\n",
    )
    cases.append(("outgoing resolved", _topic_values(doc, "outgoing") == {"openbank.demo.out"}))
    cases.append((
        "incoming resolved, comma-split",
        _topic_values(doc, "incoming") == {"openbank.demo.a", "openbank.demo.b"},
    ))
    cases.append((
        "a produced topic is not mistaken for a subscribed one",
        "openbank.demo.out" not in _topic_values(doc, "incoming"),
    ))
    cases.append((
        "no outgoing channel yields the empty set, not a false topic",
        _topic_values(yaml.safe_load("quarkus:\n  application:\n    name: x\n"), "outgoing") == set(),
    ))

    # 3. The corpus is really there — a gate that lost its subjects must not read as clean.
    if not money_path_services(repo):
        print("selftest FAIL: money_path_services is empty — the rules.yaml read is broken.")
        return 1
    if not subscribed_topics(repo):
        print("selftest FAIL: audit-service has no subscribed topics — the config read is broken.")
        return 1
    if not attributed_topics(repo):
        print("selftest FAIL: TopicAttribution parsed empty — the Kotlin read is broken.")
        return 1
    if not audit_read_acl_topics(repo):
        print("selftest FAIL: the audit KafkaUser has no Read ACLs — the gitops read is broken.")
        return 1

    # 4. The negative control on the WHOLE evaluation: take one topic out of each of the three
    #    places in turn and require the corresponding gap to appear.
    subscribed = subscribed_topics(repo)
    attributed = attributed_topics(repo)
    acls = audit_read_acl_topics(repo)
    covered = sorted(
        t for t in subscribed if t in attributed and acl_covers(t, acls)
    )
    if not covered:
        print("selftest FAIL: no fully covered topic to use as a negative control.")
        return 1
    victim = covered[0]
    cases.append(("removing it from the topics list is detected", victim not in (subscribed - {victim})))
    cases.append(("removing it from TopicAttribution is detected", victim not in (attributed - {victim})))
    cases.append((
        "removing its Read ACL is detected",
        not acl_covers(victim, {r for r in acls if r[0] != victim}),
    ))
    cases.append(("and with all three present it is NOT flagged",
                  victim in subscribed and victim in attributed and acl_covers(victim, acls)))

    # IN_FLIGHT must accept BOTH states and must not silently swallow an unrelated topic.
    cases.append(("IN_FLIGHT covers only its own topics", set(IN_FLIGHT) <= set(_GAP_TOPICS.values()) | {"openbank.delegation.events"}))
    cases.append(("IN_FLIGHT and KNOWN_GAPS do not overlap",
                  not {k.split("#")[1] for k in KNOWN_GAPS} & set(IN_FLIGHT)))
    cases.append(("OUT_OF_SCOPE and IN_FLIGHT do not overlap", not set(OUT_OF_SCOPE) & set(IN_FLIGHT)))

    failed = [name for name, ok in cases if not ok]
    if failed:
        for name in failed:
            print(f"selftest FAIL: {name}")
        return 1
    print(
        f"selftest OK: {len(cases)} cases, both directions "
        f"(negative control on {victim!r} in all three places; direction-aware channel parse).",
    )
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--selftest", action="store_true", help="verify the check can fail")
    args = ap.parse_args()
    repo = pathlib.Path(args.root).resolve()

    if args.selftest:
        return selftest(repo)

    gaps, examined, silent, excluded = evaluate(repo)
    findings: list[str] = []
    used: set[str] = set()

    in_flight_open: set[str] = set()

    for service, topic, place in gaps:
        key = f"{service}#{topic}#{place}"
        if topic in IN_FLIGHT:
            in_flight_open.add(topic)
            print(f"::notice::in flight {key}: {IN_FLIGHT[topic]}")
            continue
        where, consequence = PLACE_CONSEQUENCE[place]
        if key in KNOWN_GAPS:
            used.add(key)
            print(f"::notice::known gap {key}: {KNOWN_GAPS[key]}")
            continue
        findings.append(
            f"::error::{service} produces {topic} but it is missing from {where}. "
            f"Consequence: {consequence}. A money-path topic that never reaches the audit trail "
            f"is indistinguishable from a quiet period — see #6035/#5859.",
        )

    for key in sorted(set(KNOWN_GAPS) - used):
        findings.append(
            f"::error::stale KNOWN_GAPS entry {key} — that is no longer a gap (or the topic is no "
            f"longer produced). Remove it, so the list can only shrink.",
        )

    for topic in sorted(set(IN_FLIGHT) - in_flight_open):
        print(
            f"::warning::IN_FLIGHT entry {topic} is no longer a gap - {IN_FLIGHT[topic]} has "
            f"landed. Delete the entry so the ratchet covers this topic again.",
        )

    for topic in sorted(set(OUT_OF_SCOPE) - excluded):
        findings.append(
            f"::error::stale OUT_OF_SCOPE entry {topic} - no money-path service produces it any "
            f"more. Remove it, so an exclusion cannot outlive its reason.",
        )

    for topic in sorted(excluded):
        print(f"::notice::out of scope {topic}: {OUT_OF_SCOPE[topic]}")

    for service in silent:
        print(f"::notice::{service} declares no outgoing Kafka channel — correctly absent from the "
              f"audit subscription, not missing.")

    for line in findings:
        print(line if args.enforce else line.replace("::error", "::warning", 1))

    gatelib.subjects(examined, "money-path produced topics")
    verdict = "clean." if not findings else f"{len(findings)} finding(s) above."
    print(
        f"check-audit-money-path-subscription: {examined} produced topic(s) across "
        f"{len(money_path_services(repo))} money-path service(s) "
        f"({len(silent)} produce no Kafka events), {len(KNOWN_GAPS)} known gap(s) — {verdict}",
    )
    return 1 if findings and args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
