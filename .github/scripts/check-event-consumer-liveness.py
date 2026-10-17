#!/usr/bin/env python3
"""Event-consumer liveness gate (ADR-0160 mechanism 1, rules.yaml: event_consumer_liveness).

WHY THIS EXISTS: standing-order-service published `standing-order.due.v1` to Kafka every day
for weeks — full CRUD, a passing test suite, complete docs, a scheduler doc-comment claiming
"downstream payment rails consume this event" — and nothing consumed it. No payment was ever
initiated (issue #889). Unit tests passed because they mocked exactly the seam that was broken;
nothing else in the fleet checked whether a *published* domain event has a *live* consumer
anywhere. This script closes that gap generally, the same way check-outbox-dispatch-enabled.sh
closed the "dispatch-enabled defaults to false" footgun and check-no-service-principal-type.sh
closed the "SERVICE principal never fires" footgun — each written after one incident, this one
written to stop the *pattern*, not just the next instance of it.

WHAT IT CHECKS: builds a topic -> {producer services} / {consumer services} map from every
`openbank-*/src/main/resources/application.yaml`'s `mp.messaging.outgoing`/`incoming` config
(including multi-topic `topics:` comma-list subscribers, e.g. audit-service/analytics-sink, and
`${ENV_VAR:default}`-wrapped topic names). Any topic with at least one producer and ZERO
consumers fleet-wide is a violation — UNLESS it is listed in
`rules.yaml: event_consumer_liveness.allowlist` with a one-line reason (external sink,
deliberately audit-only, or a tracked "not built yet" gap with an issue number). This mirrors the
ktlint-baseline/detekt-baseline idiom: an explicit, reviewable, individually justified exception
list, not a blanket skip.

ADVISORY (ADR-0144 gate-graduation): findings are ::warning:: annotations; the script exits 0
regardless of findings unless invoked with --enforce, at which point findings exit 1. A first
fleet scan (2026-07-13) found 19 of 35 topics producer-only — real triage work tracked as a
fleet-sweep issue, not something this ADR resolves by fiat. Flip to enforce once that triage
brings unallowlisted violations to zero (see rules.yaml: event_consumer_liveness.target_enforce_date).

stdlib + PyYAML (already installed earlier in the same CI job, matching check-slo-registry.py).
Usage: check-event-consumer-liveness.py [--root .] [--rules openbank-libs/governance/rules.yaml] [--enforce]
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys

import yaml

TOPIC_LINE = re.compile(r"^\s*topic:\s*(.+?)\s*$")
TOPICS_LINE = re.compile(r"^\s*topics:\s*(.+?)\s*$")
SECTION_HEADER = re.compile(r"^(\s*)(outgoing|incoming):\s*$")
ENV_DEFAULT = re.compile(r"^\$\{[^:}]*:([^}]+)\}$")


def resolve_topic_value(raw: str) -> str | None:
    """'${VAR:default}' -> 'default'; a bare literal is returned as-is; '${VAR}' (no default,
    cannot be resolved statically) is skipped — same fail-closed-on-ambiguity stance as
    check-outbox-dispatch-enabled.sh."""
    raw = raw.strip().strip('"').strip("'")
    # There used to be an explicit `startswith("${") and endswith("}") and ":" not in raw`
    # branch here. It was UNREACHABLE-equivalent: `${T}` is already refused by the
    # `startswith("${")` fallback below, and `${T:x}` never entered it because of the colon.
    # No input distinguishes the two versions (checked across the `${}`/`${:x}`/`${T:}`/
    # nested-colon/unterminated shapes), so it was dead weight that made the resolver look
    # like it handled more cases than it does — the same reason this repo bans a rego rule
    # gated on a principal type nothing emits. Found by a self-test break going UNCAUGHT.
    m = ENV_DEFAULT.match(raw)
    if m:
        return m.group(1)
    if raw.startswith("${"):
        return None
    return raw


def scan_application_yaml(path: pathlib.Path, service: str) -> tuple[dict[str, set[str]], dict[str, set[str]]]:
    """Returns (producer_topics, consumer_topics) declared in a single application.yaml,
    keyed by resolved topic name -> {this service}."""
    producers: dict[str, set[str]] = {}
    consumers: dict[str, set[str]] = {}
    lines = path.read_text(encoding="utf-8").splitlines()

    section: str | None = None
    section_indent = -1
    for line in lines:
        header = SECTION_HEADER.match(line)
        if header:
            section, section_indent = header.group(2), len(header.group(1))
            continue
        if section is not None:
            stripped = line.strip()
            indent = len(line) - len(line.lstrip(" "))
            if stripped and not stripped.startswith("#") and indent <= section_indent:
                section = None

        m = TOPIC_LINE.match(line)
        if m and section:
            topic = resolve_topic_value(m.group(1))
            if topic:
                (producers if section == "outgoing" else consumers).setdefault(topic, set()).add(service)
            continue

        # `topics:` (plural, comma list) is only ever a multi-topic @Incoming subscriber
        # (audit-service, analytics-sink) — never valid under `outgoing:`.
        m = TOPICS_LINE.match(line)
        if m:
            for raw in m.group(1).split(","):
                topic = resolve_topic_value(raw)
                if topic:
                    consumers.setdefault(topic, set()).add(service)

    return producers, consumers


def load_allowlist(rules_path: pathlib.Path) -> dict[str, str]:
    if not rules_path.exists():
        return {}
    data = yaml.safe_load(rules_path.read_text(encoding="utf-8")) or {}
    rule = (data.get("change_requirements") or {}).get("event_consumer_liveness") or {}
    entries = rule.get("allowlist") or []
    allowlist: dict[str, str] = {}
    for entry in entries:
        topic = entry.get("topic")
        reason = entry.get("reason", "")
        if topic:
            allowlist[topic] = reason
    return allowlist


def build_topic_maps(root: pathlib.Path) -> tuple[dict[str, set[str]], dict[str, set[str]], list[pathlib.Path]]:
    """Fleet-wide (producer_topics, consumer_topics, scanned_files). Reused by
    check-governance-lineage.py (ADR-0160 mechanism 2) via importlib — see that script for why a
    hyphenated-filename cross-script import needs spec_from_file_location, matching the existing
    gen_network_policies_test.py idiom, rather than a normal `import`."""
    all_producers: dict[str, set[str]] = {}
    all_consumers: dict[str, set[str]] = {}

    yaml_files = sorted(root.glob("openbank-*/src/main/resources/application.yaml"))
    for f in yaml_files:
        service = f.relative_to(root).parts[0]
        producers, consumers = scan_application_yaml(f, service)
        for topic, services in producers.items():
            all_producers.setdefault(topic, set()).update(services)
        for topic, services in consumers.items():
            all_consumers.setdefault(topic, set()).update(services)

    return all_producers, all_consumers, yaml_files


def classify_topics(
    all_producers: dict[str, set[str]],
    all_consumers: dict[str, set[str]],
    allowlist: dict[str, str],
) -> tuple[list[str], list[str], list[str]]:
    """(violations, allowlisted_hit, stale_allowlist) — the three-way split over producer
    topics. `stale_allowlist` is a topic that is BOTH in the allowlist AND now has a consumer:
    the exemption's removal condition has been met and nothing else would say so."""
    violations = sorted(t for t in all_producers if t not in all_consumers and t not in allowlist)
    allowlisted_hit = sorted(t for t in all_producers if t not in all_consumers and t in allowlist)
    stale_allowlist = sorted(t for t in all_producers if t in all_consumers and t in allowlist)
    return violations, allowlisted_hit, stale_allowlist


DLQ_TOPIC = re.compile(r"(^openbank\.dlq\.)|(\.dlq$)")
ISSUE_REF = re.compile(r"#\d{2,}")


def is_connector_dlq(topic: str) -> bool:
    """A dead-letter topic is written by the connector's own failure-strategy, never by a service
    channel, so it has a 'consumer' declaration (the nested dead-letter-queue topic: key under an
    incoming channel) and no producer by construction. Matching by NAME is deliberate: the scanner
    cannot tell a nested DLQ key from a channel topic, and every DLQ here follows the convention."""
    return bool(DLQ_TOPIC.search(topic))


def load_consumer_allowlist(rules_path: pathlib.Path) -> dict[str, str]:
    if not rules_path.exists():
        return {}
    data = yaml.safe_load(rules_path.read_text(encoding="utf-8")) or {}
    rule = (data.get("change_requirements") or {}).get("event_consumer_liveness") or {}
    return {e["topic"]: e.get("reason", "") for e in (rule.get("consumer_allowlist") or []) if e.get("topic")}


def classify_consumer_only(
    all_producers: dict[str, set[str]],
    all_consumers: dict[str, set[str]],
    consumer_allowlist: dict[str, str],
) -> tuple[list[str], list[str], list[str], list[str]]:
    """Reverse direction: (violations, allowlisted_hit, stale_now_produced, stale_no_longer_consumed).
    A consumer-only topic is one some service subscribes to and no service publishes."""
    consumer_only = sorted(t for t in all_consumers if t not in all_producers and not is_connector_dlq(t))
    violations = [t for t in consumer_only if t not in consumer_allowlist]
    hit = [t for t in consumer_only if t in consumer_allowlist]
    stale_produced = sorted(t for t in consumer_allowlist if t in all_producers)
    stale_gone = sorted(t for t in consumer_allowlist if t not in all_consumers and t not in all_producers)
    return violations, hit, stale_produced, stale_gone


def untracked_entries(allowlist: dict[str, str]) -> list[str]:
    """Entries whose reason cites no issue number: an exemption nobody is tracking is permanent."""
    return sorted(t for t, reason in allowlist.items() if not ISSUE_REF.search(reason))


def asyncapi_missing_plan(root: pathlib.Path, producer_allowlist: dict[str, str]) -> list[str]:
    """A producer-only topic that has an AsyncAPI file must say, in that file, which issue plans the
    consumer (`x-planned-consumer: <issue url>`), so a reader of the contract sees it is a plan and
    not an oversight. Returns '<topic> (<file>)' for each file that names the topic without it."""
    missing = []
    for f in sorted(root.glob("openbank-contracts/*/asyncapi.yaml")):
        doc = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
        for channel in (doc.get("channels") or {}).values():
            topic = (channel or {}).get("address")
            if topic in producer_allowlist and not re.fullmatch(
                r"https://github\.com/\S+/issues/\d+", str((channel or {}).get("x-planned-consumer", ""))
            ):
                missing.append(f"{topic} ({f.relative_to(root)})")
    return missing


def self_test() -> int:
    """Falsify the topic resolver and the producer/consumer scanner.

    ADR-0160 mechanism 1, and the incident behind it: standing-order-service published
    `standing-order.due.v1` every day for weeks with ZERO consumers anywhere in the fleet —
    full CRUD, passing tests, docs, and a scheduler comment claiming a downstream consumed it.
    Nothing checked the claim against the fleet (#889).

    The scanner is indentation- and section-aware, which is precisely the code that looks
    right and is off by one level: read a topic under the wrong section and a producer counts
    as its own consumer, which closes the gap on paper while the topic still goes nowhere.
    """
    import tempfile

    fails: list[str] = []

    def case(label, got, want):
        if got != want:
            fails.append(f"{label}: expected {want}, got {got}")

    # --- topic resolution ---------------------------------------------------------------
    case("a literal topic resolves to itself", resolve_topic_value("a.events"), "a.events")
    case("quotes are stripped", resolve_topic_value('"a.events"'), "a.events")
    case("an env default resolves to the default", resolve_topic_value("${T:a.events}"), "a.events")
    # An env var with NO default cannot be known from this repo. Guessing it would invent a
    # topic name and then report a missing consumer for a topic that does not exist.
    case("an env var with no default is refused", resolve_topic_value("${T}"), None)

    with tempfile.TemporaryDirectory() as td:
        d = pathlib.Path(td)

        # A producer and a consumer in one file, plus the `topics:` plural form some
        # connectors use — missing that form loses consumers and manufactures dead topics.
        f = d / "application.yaml"
        f.write_text(
            "mp:\n"
            "  messaging:\n"
            "    outgoing:\n"
            "      out:\n"
            "        topic: a.produced\n"
            "    incoming:\n"
            "      in:\n"
            "        topic: b.consumed\n"
            "      multi:\n"
            "        topics: c.one, c.two\n"
        )
        prod, cons = scan_application_yaml(f, "openbank-x")
        case("the produced topic is a producer", sorted(prod), ["a.produced"])
        case("consumed topics include both forms", sorted(cons), ["b.consumed", "c.one", "c.two"])

        # SECTION BOUNDARY: a `topic:` that dedents back out of the incoming/outgoing block
        # belongs to neither. Read as a consumer it would silently close a real gap — the
        # producer would appear to have a consumer and #889 repeats.
        f2 = d / "boundary.yaml"
        f2.write_text(
            "mp:\n"
            "  messaging:\n"
            "    outgoing:\n"
            "      out:\n"
            "        topic: a.produced\n"
            "unrelated:\n"
            "  topic: not.a.channel\n"
        )
        prod2, cons2 = scan_application_yaml(f2, "openbank-x")
        if "not.a.channel" in prod2 or "not.a.channel" in cons2:
            fails.append("a topic outside any incoming/outgoing section was counted as a channel")
        case("the real producer survives the boundary", sorted(prod2), ["a.produced"])

        # An unresolvable topic must be skipped rather than recorded under its raw text.
        f3 = d / "envonly.yaml"
        f3.write_text("mp:\n  messaging:\n    outgoing:\n      out:\n        topic: ${T}\n")
        prod3, _c = scan_application_yaml(f3, "openbank-x")
        case("an unresolvable topic is not recorded", sorted(prod3), [])

    # --- three-way classification --------------------------------------------------------
    # A still-needed allowlist entry (no consumer anywhere) must NOT fire as stale.
    v, hit, stale = classify_topics(
        {"needed.topic": {"svc-a"}}, {}, {"needed.topic": "external sink, tracked #1"}
    )
    case("a genuinely-still-needed allowlist entry is not a violation", v, [])
    case("...and is reported as allowlisted-hit", hit, ["needed.topic"])
    case("...and is NOT reported as stale", stale, [])

    # An allowlist entry whose topic now HAS a consumer must fire as stale, not as
    # allowlisted-hit (it's no longer producer-only) and not as an ordinary violation
    # (it's not unconsumed).
    v2, hit2, stale2 = classify_topics(
        {"fixed.topic": {"svc-a"}}, {"fixed.topic": {"svc-b"}}, {"fixed.topic": "remove once #999 merges"}
    )
    case("a now-consumed allowlisted topic is not an ordinary violation", v2, [])
    case("...and is not reported as still-uncovered", hit2, [])
    case("...and IS reported as stale", stale2, ["fixed.topic"])

    # An unallowlisted, unconsumed topic is the ordinary violation path — must stay unchanged.
    v3, hit3, stale3 = classify_topics({"broken.topic": {"svc-a"}}, {}, {})
    case("an unallowlisted unconsumed topic is a violation", v3, ["broken.topic"])
    case("...and not allowlisted-hit", hit3, [])
    case("...and not stale", stale3, [])

    # A live read: fixtures cannot tell that the fleet glob still resolves.
    live_prod, live_cons, live_files = build_topic_maps(pathlib.Path("."))
    if not live_files or not live_prod:
        fails.append(f"reading the real repo found {len(live_files)} config(s) and "
                     f"{len(live_prod)} produced topic(s) — the glob moved and this gate "
                     f"would report every topic as consumed")

    # --- reverse direction (#13168) ---
    cprod = {"a.produced": {"svc-a"}}
    ccons = {"a.produced": {"svc-b"}, "b.orphan": {"svc-b"}, "openbank.dlq.x.y": {"svc-b"}, "z.dlq": {"svc-b"}}
    v, hit, sp, sg = classify_consumer_only(cprod, ccons, {})
    case("a consumed-never-produced topic is a violation", v, ["b.orphan"])
    case("...a connector DLQ topic is NOT (openbank.dlq.* and *.dlq)", ("openbank.dlq.x.y" in v, "z.dlq" in v), (False, False))
    v, hit, sp, sg = classify_consumer_only(cprod, ccons, {"b.orphan": "r #12345"})
    case("an allowlisted orphan consumer is not a violation", v, [])
    case("...and is reported as allowlisted", hit, ["b.orphan"])
    v, hit, sp, sg = classify_consumer_only({**cprod, "b.orphan": {"svc-c"}}, ccons, {"b.orphan": "r #12345"})
    case("an allowlisted topic that now has a producer is stale", sp, ["b.orphan"])
    v, hit, sp, sg = classify_consumer_only(cprod, {"a.produced": {"svc-b"}}, {"gone.topic": "r #12345"})
    case("an allowlisted topic nobody consumes or produces any more is reported gone", sg, ["gone.topic"])
    case("a reason without an issue ref is untracked", untracked_entries({"t.a": "plan is vague", "t.b": "see #8793"}), ["t.a"])
    with tempfile.TemporaryDirectory() as td2:
        root2 = pathlib.Path(td2)
        (root2 / "openbank-contracts" / "x").mkdir(parents=True)
        (root2 / "openbank-contracts" / "x" / "asyncapi.yaml").write_text("channels:\n  c:\n    address: t.planned\n")
        case("an AsyncAPI channel for a producer-only topic without x-planned-consumer is flagged",
             asyncapi_missing_plan(root2, {"t.planned": "r #12345"}), ["t.planned (openbank-contracts/x/asyncapi.yaml)"])
        (root2 / "openbank-contracts" / "x" / "asyncapi.yaml").write_text(
            "channels:\n  c:\n    address: t.planned\n    x-planned-consumer: https://github.com/o/r/issues/12345\n")
        case("...and passes once it carries the issue link", asyncapi_missing_plan(root2, {"t.planned": "r #12345"}), [])
    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print(f"self-test ok: event-consumer liveness is falsifiable "
          f"(18 cases + a live read of {len(live_files)} config(s))")
    return 0


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()

    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=".")
    parser.add_argument("--rules", default="openbank-libs/governance/rules.yaml")
    parser.add_argument("--enforce", action="store_true")
    args = parser.parse_args()

    root = pathlib.Path(args.root)
    all_producers, all_consumers, yaml_files = build_topic_maps(root)

    allowlist = load_allowlist(root / args.rules)

    violations, allowlisted_hit, stale_allowlist = classify_topics(all_producers, all_consumers, allowlist)

    for topic in allowlisted_hit:
        producers = ", ".join(sorted(all_producers[topic]))
        print(f"::notice::event-consumer-liveness: {topic} (published by {producers}) has no consumer — allowlisted: {allowlist[topic]}")

    for topic in stale_allowlist:
        producers = ", ".join(sorted(all_producers[topic]))
        consumers = ", ".join(sorted(all_consumers[topic]))
        annotation = "error" if args.enforce else "warning"
        print(
            f"::{annotation}::event-consumer-liveness: {topic} is listed in rules.yaml: "
            f"event_consumer_liveness.allowlist ({allowlist[topic]}) but is now consumed by "
            f"{consumers} (published by {producers}) — delete the stale entry, the gap it was "
            f"covering no longer exists."
        )

    for topic in violations:
        producers = ", ".join(sorted(all_producers[topic]))
        annotation = "error" if args.enforce else "warning"
        print(
            f"::{annotation}::event-consumer-liveness: {topic} is published by {producers} but has NO "
            f"consumer anywhere in the fleet (no matching @Incoming topic/topics: declaration). "
            f"If this is intentional (external sink, audit-only, tracked future work), add it to "
            f"rules.yaml: event_consumer_liveness.allowlist with a one-line reason. If not, this is "
            f"the #889 failure class — a published event nobody consumes."
        )

    consumer_allowlist = load_consumer_allowlist(root / args.rules)
    c_viol, c_hit, c_stale_prod, c_stale_gone = classify_consumer_only(
        all_producers, all_consumers, consumer_allowlist
    )
    annotation = "error" if args.enforce else "warning"
    for topic in c_hit:
        print(f"::notice::event-consumer-liveness: {topic} is consumed by "
              f"{', '.join(sorted(all_consumers[topic]))} but published by no one — allowlisted: {consumer_allowlist[topic]}")
    for topic in c_viol:
        print(
            f"::{annotation}::event-consumer-liveness: {topic} is consumed by "
            f"{', '.join(sorted(all_consumers[topic]))} but NO service publishes it (no matching "
            f"mp.messaging.outgoing topic). The consumer receives nothing and reports healthy. Add the producer, "
            f"remove the consumer, or add it to rules.yaml: event_consumer_liveness.consumer_allowlist "
            f"with a reason and a tracking issue (#NNN)."
        )
    for topic in c_stale_prod:
        print(f"::{annotation}::event-consumer-liveness: {topic} is in consumer_allowlist but now HAS a producer "
              f"({', '.join(sorted(all_producers[topic]))}) — delete the stale entry.")
    for topic in c_stale_gone:
        print(f"::notice::event-consumer-liveness: {topic} is in consumer_allowlist but is no longer consumed or "
              f"produced anywhere — delete the entry when convenient.")
    untracked = [("allowlist", t) for t in untracked_entries(allowlist)] + [
        ("consumer_allowlist", t) for t in untracked_entries(consumer_allowlist)
    ]
    for where, topic in untracked:
        print(f"::{annotation}::event-consumer-liveness: {where} entry {topic} cites no tracking issue (#NNN) in its "
              f"reason — an exemption nobody tracks becomes permanent.")
    unplanned = asyncapi_missing_plan(root, allowlist)
    for item in unplanned:
        print(f"::{annotation}::event-consumer-liveness: {item} is a producer-only topic whose AsyncAPI channel "
              f"has no `x-planned-consumer: <issue url>` — link the issue that plans the consumer.")

    print(
        f"check-event-consumer-liveness: {len(c_viol)} consumer-only violation(s), "
        f"{len(c_hit)} allowlisted consumer-only topic(s).")
    print(
        f"check-event-consumer-liveness: {len(all_producers)} producer topic(s) scanned across "
        f"{len(yaml_files)} service(s); {len(violations)} unallowlisted violation(s), "
        f"{len(allowlisted_hit)} allowlisted producer-only topic(s), "
        f"{len(stale_allowlist)} stale allowlist entry/entries."
    )

    if (violations or stale_allowlist or c_viol or c_stale_prod or untracked or unplanned) and args.enforce:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
