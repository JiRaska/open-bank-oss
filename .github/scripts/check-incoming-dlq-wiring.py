#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Every `mp.messaging.incoming` channel dead-letters, explicitly, into a topic it may write.

WHY THIS GATE EXISTS
    #5698 converted ~21 event consumers from swallow-and-ack to rethrow, on the stated basis that
    "the connector dead-letters". SmallRye's DEFAULT `failure-strategy` is `fail`, which STOPS the
    channel. Only four of the fleet's 44 incoming channels configured a DLQ, so for most of the
    sweep the rethrow traded silent data loss for a WEDGED consumer — the exact outcome the swallow
    comments it replaced were written to avoid. Nothing errored, and no test could see it: the
    consumers' own KDocs asserted a behaviour their configuration did not provide (#5745).

WHAT IT REQUIRES, per incoming channel
    1. `failure-strategy: dead-letter-queue`.
    2. An EXPLICIT topic. SmallRye's implicit default is `dead-letter-topic-<channel>`, derived from
       the channel name alone — and channel names repeat (`party-events-in` is declared by eight
       services), so left implicit those eight share one topic and every alert over it misattributes.
    3. In `application.yaml`, the topic written in NESTED form, never as the dotted leaf key
       `dead-letter-queue.topic:`.
       SmallRye Config's YAML source quotes any leaf key containing a literal dot, registering it as
       ...`"dead-letter-queue.topic"`, which the connector's plain getOptionalValue never reads.
       Nothing errors; the default silently applies. Same footgun as `group.id` (#686).
       `openbank-aml-service`'s DeadLetterQueueConfigResolutionTest proves both directions against
       the real SmallRye YAML source; this gate is the fleet-wide ratchet over that proof.
    4. Fleet-unique topic names — the whole point of (2).
    5. A `KafkaTopic` CR: this cluster does not auto-create topics.
    6. A KafkaUser `Write` grant on it. Without the ACL the DLQ send itself fails and the consumer
       wedges on the very failure the DLQ was added to park — strictly worse than `fail`.

WHERE IT LOOKS
    `application.yaml` is not the only config source. A `<svc>-msg-override` ConfigMap under
    `openbank-infra/gitops/components/**` carries `override.properties` at `config_ordinal=500`,
    which OUTRANKS the baked `application.yaml` (~254) and is loaded via QUARKUS_CONFIG_LOCATIONS.
    That is where a `dead-letter-queue.topic` legitimately lives, because in a `.properties` source
    the dots are read correctly while SmallRye's YAML source would quote the leaf key and the
    connector would never read it (#686). notification-service's `party-events-in` is wired exactly
    that way (#5737), and fraud-service's `transaction-signal` has been since before #5745.

    A gate that read only `application.yaml` would report both as "DLQ topic left implicit" — a
    FALSE statement about a channel that is correctly wired, and one that would go red on `main`
    rather than on a PR. So the properties source is parsed too, and the effective value is the
    override's where both exist.

USAGE
    check-incoming-dlq-wiring.py [--enforce] [--self-test]
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gatelib  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
GITOPS = REPO / "openbank-infra/gitops/components"

# Channels deliberately not yet wired. An entry needs a REASON, and the gate fails on a stale one
# in either direction — a baselined channel that becomes wired must leave this list, so the debt
# cannot quietly become permanent.
KNOWN_UNWIRED = {
    # openbank-notification-service is OFF this list entirely as of #8346. Its `party-events-in`
    # (#5737) and `notification-events-in` (#5745) name their DLQ through the msg-override
    # ConfigMap because those changes also moved a dotted `group.id`; `delegation-events-in`
    # (#8346) uses the NESTED form in application.yaml instead, which resolves in every config
    # source, so local dev and tests get the same wiring as the pod. The gate reads both sources
    # and sees all three.
    ("openbank-tax-reporting-service", "withholding-remitted-in"): "no KafkaUser in gitops — a Write ACL cannot be granted, so a DLQ would wedge on the send (#5745)",
    # The channels that had a DLQ BEFORE #5745, on SmallRye's implicit `dead-letter-topic-<channel>`
    # name. Naming one explicitly is a RENAME of a live topic: it strands whatever is already parked
    # in the old one and moves what the AccountPartyEventDeadLettered alert must read. That is an
    # operational change with a migration, not a config edit, which is why they were recorded here
    # rather than done in passing.
    #
    # account-service is OFF this list as of #5752. Its two channels now name
    # `openbank.dlq.account.party-events-in` / `openbank.dlq.account.delegation-events-in`, with
    # KafkaTopic CRs and Write ACLs, and the migration is carried explicitly: the two old topics and
    # their Write ACLs are RETAINED so an existing backlog stays drainable, and the alert matches
    # both names for the length of the rollout (old and new pods run side by side under a Rollout).
    #
    # card-issuance-service's `delegation-events-in` stays. It no longer COLLIDES — account-service
    # was the other writer of `dead-letter-topic-delegation-events-in` and has moved off it — so what
    # remains is a single service on an implicit, channel-derived name. Renaming it is still a live
    # topic migration, and it is card-issuance's to make.
    ("openbank-card-issuance-service", "delegation-events-in"): "pre-#5745 implicit DLQ name; sole writer since #5752 moved account-service off it, but the rename is still a live-topic migration",
    # NOT the implicit name, and not a rename: fraud-service already sets an EXPLICIT topic
    # (`openbank.transactions.transaction.initiated.fraud.dlq`) in
    # components/fraud-service/fraud-service-msg-override.yaml, with a matching [Write, Describe]
    # ACL in kafka-fraud-mtls.yaml. What it lacks is requirement 5 — no `KafkaTopic` CR exists for
    # that name anywhere. One CR, no migration; load-bearing because #5715 makes
    # TransactionSignalConsumer rethrow on this very channel.
    ("openbank-fraud-service", "transaction-signal"): "explicit topic set in fraud-service-msg-override.yaml; the gap is a missing KafkaTopic CR, not a rename (#5745)",
}


def channels(text: str) -> list[tuple[str, dict[str, str]]]:
    """Top-level mp.messaging.incoming channels and their flattened leaf properties.

    Profile blocks (`"%test":`, `"%dev":`) are excluded on purpose: they sit at a deeper
    indentation and describe local runs, not the deployed channel.
    """
    lines = text.split("\n")
    out = []
    for i, line in enumerate(lines):
        if line != "    incoming:":
            continue
        j = i + 1
        while j < len(lines):
            cur = lines[j]
            if cur.strip() == "" or cur.lstrip().startswith("#"):
                j += 1
                continue
            if len(cur) - len(cur.lstrip()) <= 4:
                break
            m = re.match(r"^      ([\w.-]+):\s*$", cur)
            if m:
                body, k = [], j + 1
                while k < len(lines):
                    nxt = lines[k]
                    if nxt.strip() and len(nxt) - len(nxt.lstrip()) <= 6 and not nxt.lstrip().startswith("#"):
                        break
                    body.append(nxt)
                    k += 1
                out.append((m.group(1), props(body)))
            j += 1
    return out


def props(body: list[str]) -> dict[str, str]:
    """Flatten a channel body to dotted property names, the way SmallRye's YAML source does —
    including the QUOTING of a leaf key that itself contains a dot, which is the defect this
    gate exists to make visible."""
    flat, stack = {}, []
    for line in body:
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        m = re.match(r"^(\s*)([\w.\"-]+):\s*(.*)$", line)
        if not m:
            continue
        indent, key, value = len(m.group(1)), m.group(2), m.group(3).strip()
        while stack and stack[-1][0] >= indent:
            stack.pop()
        path = [k for _, k in stack] + [key if "." not in key else f'"{key}"']
        if value:
            flat[".".join(path)] = value
        else:
            stack.append((indent, key))
    return flat


def gitops_text() -> str:
    return "\n".join(p.read_text(encoding="utf-8", errors="replace") for p in GITOPS.rglob("*.yaml"))


def overrides(root: Path) -> dict[tuple[str, str], dict[str, str]]:
    """{(service-dir, channel): {property: value}} from every `<svc>-msg-override` ConfigMap.

    These carry `override.properties` at `config_ordinal=500`, which OUTRANKS the baked
    `application.yaml`, so a `dead-letter-queue.topic` set here is the EFFECTIVE one — and for a
    dotted leaf key it is the only source that works at all (#686). Parsed as text rather than as
    YAML on purpose: the block is a `.properties` payload inside a literal scalar, and reading it
    line-wise costs nothing and cannot be confused by the surrounding manifest.
    """
    out: dict[tuple[str, str], dict[str, str]] = {}
    if not GITOPS.is_dir():
        return out
    for path in sorted(GITOPS.rglob("*msg-override*.yaml")):
        # `notification-service-msg-override.yaml` -> `openbank-notification-service`. A file whose
        # name does not resolve to a real service directory is skipped rather than guessed at.
        svc = "openbank-" + path.name.replace("-msg-override.yaml", "")
        if not (root / svc / "src/main/resources/application.yaml").is_file():
            continue
        for line in path.read_text(encoding="utf-8", errors="replace").split("\n"):
            line = line.strip()
            if line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            m = re.match(r"^mp\.messaging\.incoming\.([\w-]+)\.(.+)$", key.strip())
            if m:
                out.setdefault((svc, m.group(1)), {})[m.group(2)] = value.strip()
    return out


def kafka_topic_cr(gitops: str, topic: str) -> bool:
    """True only if a `kind: KafkaTopic` document declares `metadata.name: <topic>`.

    A bare `f"name: {topic}" in gitops` substring test cannot fail independently of the ACL test:
    every KafkaUser ACL entry granting Write on the DLQ is itself a line reading
    `name: <topic>`, so deleting every KafkaTopic CR while keeping the ACLs left the CR branch
    green. Scoping to the document is what gives requirement 5 a failure it can actually reach.
    """
    for doc in re.split(r"(?m)^---\s*$", gitops):
        if re.search(r"(?m)^kind:\s*KafkaTopic\s*$", doc) and re.search(
            r"(?m)^\s{0,4}name:\s*%s\s*$" % re.escape(topic), doc
        ):
            return True
    return False


def findings(root: Path) -> tuple[list[str], int]:
    seen: dict[str, tuple[str, str]] = {}
    out: list[str] = []
    total = 0
    gitops = gitops_text() if GITOPS.is_dir() else ""
    over = overrides(root)

    for app in sorted(root.glob("openbank-*/src/main/resources/application.yaml")):
        svc = app.relative_to(root).parts[0]
        for ch, p in channels(app.read_text(encoding="utf-8", errors="replace")):
            total += 1
            o = over.get((svc, ch), {})
            # The ConfigMap wins where both set the key: config_ordinal=500 vs application.yaml's
            # ~254. Read the .properties spelling only from the properties source — in YAML that
            # same dotted key is the defect (#686), which is why `quoted` is checked separately.
            strategy = o.get("failure-strategy") or p.get("failure-strategy")
            topic = o.get("dead-letter-queue.topic") or p.get("dead-letter-queue.topic")
            quoted = p.get('"dead-letter-queue.topic"')

            # Every requirement is evaluated for EVERY channel, baselined or not, and the baseline
            # then suppresses. That is what makes the stale-entry check honest in both directions:
            # an entry is stale only when the channel passes ALL SIX requirements, not merely the
            # two readable from application.yaml. Judging staleness on a subset is how
            # fraud-service — explicit topic, no KafkaTopic CR — reads as "now wired".
            chan: list[str] = []
            if strategy != "dead-letter-queue":
                chan.append(f"{svc} :: {ch}: no `failure-strategy: dead-letter-queue` — the connector "
                            f"default is `fail`, which STOPS the channel on a rethrow")
            elif quoted and not o.get("dead-letter-queue.topic") and not p.get("dead-letter-queue.topic"):
                chan.append(f"{svc} :: {ch}: DLQ topic written as the dotted leaf key "
                            f"`dead-letter-queue.topic:` in application.yaml — SmallRye quotes it and "
                            f"the connector never reads it (#686). Nest it under `dead-letter-queue:`, "
                            f"or set it in the service's msg-override ConfigMap where the dots are read")
            elif not topic:
                chan.append(f"{svc} :: {ch}: DLQ topic left implicit — SmallRye derives "
                            f"`dead-letter-topic-{ch}` from the channel name alone, which collides "
                            f"across services sharing it")
            else:
                if topic in seen:
                    other = seen[topic]
                    chan.append(f"{svc} :: {ch}: DLQ topic {topic} already used by {other[0]} :: {other[1]}")
                seen[topic] = (svc, ch)
                if gitops:
                    if not kafka_topic_cr(gitops, topic):
                        chan.append(f"{svc} :: {ch}: no KafkaTopic CR for {topic} — this cluster does not "
                                    f"auto-create topics, so the DLQ send fails and the consumer wedges")
                    if not re.search(r"name:\s*%s\b[\s\S]{0,200}?operations:\s*\[[^\]]*Write" % re.escape(topic), gitops):
                        chan.append(f"{svc} :: {ch}: no KafkaUser Write ACL on {topic} — the DLQ send is denied "
                                    f"and the consumer wedges on the failure it was meant to park")

            baseline = KNOWN_UNWIRED.get((svc, ch))
            if baseline is None:
                out.extend(chan)
            elif not chan:
                out.append(f"{svc} :: {ch}: now wired — remove its KNOWN_UNWIRED entry ({baseline})")
    return out, total


SELF_TEST = [
    ("a channel with no failure-strategy is flagged",
     "    incoming:\n      x-in:\n        connector: smallrye-kafka\n", 1),
    ("the dotted leaf key is flagged, not accepted",
     "    incoming:\n      x-in:\n        failure-strategy: dead-letter-queue\n"
     "        dead-letter-queue.topic: openbank.dlq.a.x-in\n", 1),
    ("an implicit topic is flagged",
     "    incoming:\n      x-in:\n        failure-strategy: dead-letter-queue\n", 1),
    ("the nested form with an explicit topic passes",
     "    incoming:\n      x-in:\n        failure-strategy: dead-letter-queue\n"
     "        dead-letter-queue:\n          topic: openbank.dlq.a.x-in\n", 0),
    ("a %test profile override is not a channel",
     "    incoming:\n      x-in:\n        failure-strategy: dead-letter-queue\n"
     "        dead-letter-queue:\n          topic: openbank.dlq.a.x-in\n"
     '"%test":\n  mp:\n    messaging:\n      incoming:\n        x-in:\n          enabled: false\n', 0),
]


def self_test() -> int:
    failed = 0
    for name, yaml, expect in SELF_TEST:
        chans = channels(yaml)
        got = 0
        for _channel, p in chans:
            if p.get("failure-strategy") != "dead-letter-queue" or not p.get("dead-letter-queue.topic"):
                got += 1
        if got != expect:
            print(f"SELF-TEST FAIL: {name} (expected {expect}, got {got})")
            failed += 1
        else:
            print(f"self-test ok: {name}")
    # the profile case must also see exactly one channel, not two
    if len(channels(SELF_TEST[4][1])) != 1:
        print("SELF-TEST FAIL: a %test profile block was counted as a second channel")
        failed += 1

    # --- kafka_topic_cr: the branch that used to be unfalsifiable ---------------------------
    # The old test was `f"name: {topic}" in gitops` over all gitops YAML concatenated. Every
    # KafkaUser ACL granting Write on the DLQ is itself a line reading `name: <topic>`, so the CR
    # branch could not go red while the ACL branch was green — deleting every KafkaTopic CR left
    # it passing. These two cases assert the discrimination directly: same corpus, ACL only vs
    # ACL + CR.
    acl_only = (
        "apiVersion: kafka.strimzi.io/v1beta2\n"
        "kind: KafkaUser\n"
        "spec:\n  authorization:\n    acls:\n      - resource:\n"
        "          type: topic\n          name: openbank.dlq.x.y-in\n"
        "        operations: [Write, Describe]\n"
    )
    with_cr = acl_only + (
        "---\n"
        "apiVersion: kafka.strimzi.io/v1\n"
        "kind: KafkaTopic\n"
        "metadata:\n  name: openbank.dlq.x.y-in\n  namespace: messaging\n"
    )
    for name, corpus, expect in (
        ("a Write ACL alone is NOT a KafkaTopic CR", acl_only, False),
        ("a real KafkaTopic document is found", with_cr, True),
    ):
        got = kafka_topic_cr(corpus, "openbank.dlq.x.y-in")
        if got is not expect:
            print(f"SELF-TEST FAIL: {name} (expected {expect}, got {got})")
            failed += 1
        else:
            print(f"self-test ok: {name}")

    # --- overrides(): the msg-override ConfigMap is a real config source ----------------------
    # A gate reading only application.yaml calls notification-service's `party-events-in` and
    # fraud-service's `transaction-signal` "implicit" — a FALSE statement about correctly wired
    # channels, and one that goes red on main rather than on a PR. Asserted against the live
    # manifests, so the case dies if the ConfigMap is renamed or the key moves.
    live = overrides(REPO)
    for svc, ch, key in (
        ("openbank-notification-service", "party-events-in", "dead-letter-queue.topic"),
        ("openbank-fraud-service", "transaction-signal", "dead-letter-queue.topic"),
    ):
        value = live.get((svc, ch), {}).get(key)
        if not value:
            print(f"SELF-TEST FAIL: {svc} :: {ch}: no `{key}` read from its msg-override ConfigMap")
            failed += 1
        else:
            print(f"self-test ok: {svc} :: {ch} -> {value} (from the msg-override ConfigMap)")
    return failed


def main() -> int:
    if "--self-test" in sys.argv:
        return 1 if self_test() else 0
    out, total = findings(REPO)
    # Printed unconditionally, on the failure path too: a gate that found its corpus and then
    # failed on it must not also read as having lost it (gatelib.subjects).
    gatelib.subjects(total, "incoming Kafka channels")
    if out:
        print(f"{len(out)} incoming channel(s) whose dead-letter wiring is missing or inert:")
        for f in out:
            print(f"  {f}")
        return 1 if "--enforce" in sys.argv else 0
    print(f"clean: {total} incoming channels, every one dead-letters into an explicit topic it may write")
    return 0


if __name__ == "__main__":
    sys.exit(main())
