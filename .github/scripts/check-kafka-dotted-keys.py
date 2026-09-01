#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Kafka dotted-key guard: a `group.id` / `auto.offset.reset` written as a YAML leaf key does not
# reach the connector, so the file says one thing and the consumer does another (issues #686, #2945).
#
# THE MECHANISM
#   SmallRye Config's YAML source quotes any leaf map key containing a literal dot: `group.id:` under
#   a channel registers as the property name `"group.id"`, quotes included, and
#   `KafkaConnectorIncomingConfiguration`'s plain `getOptionalValue("group.id", …)` never finds it.
#   The connector then uses its own default. Nothing errors. `openbank-transaction-service`'s
#   application.yaml documents this in place, and `PartyEventsConsumerGroupIdBootIT` proves it.
#
#   The established fix is NOT to delete the key — local dev and tests read YAML fine, and it is the
#   deployed path that breaks — but to set the property from a real config source: a
#   `*-msg-override.yaml` ConfigMap carrying `override.properties` with `config_ordinal=500`.
#
# WHAT THIS CHECKS, AND WHY THE TWO KEYS DIFFER
#   `group.id`         — the fallback is `quarkus.application.name`. Six services USED TO get away
#                        with the broken key purely because the value they wanted happened to equal
#                        their application name — correct by coincidence, not by design: it breaks
#                        the day a service is renamed, or a channel wants its own group (exactly
#                        what transaction-service did). All six now carry a `*-msg-override`
#                        ConfigMap setting the same value (#2945), so an override is the ONLY thing
#                        that makes a dotted `group.id` acceptable and the coincidence exemption is
#                        gone. Removing it is what stops the next service re-introducing the
#                        latent version of this bug: matching the app name would otherwise pass CI
#                        while resolving through a fallback nobody chose.
#   `auto.offset.reset`— there is no such coincidence available. The fallback is the connector's own
#                        default, so a channel declaring a NON-default value and having no override
#                        is running on the default while its config file says otherwise. This is the
#                        finding that made #2945 more than a latent-risk note.
#
# WHY IT IS A RATCHET, NOT A FLAT FAIL
#   The remediation is NOT mechanical. Making `auto.offset.reset: earliest` actually take effect on a
#   consumer that has been running as `latest` re-reads the topic from the beginning — a mass replay
#   with real downstream consequences on money-path channels. That is a deliberate, per-channel
#   operational decision, not a config tidy-up. So today's set is BASELINED with its issue, CI stays
#   green, and a NEW occurrence fails. A baseline entry that becomes covered is also reported, so the
#   list cannot quietly rot in either direction.
#
#   That last property only holds if every baseline entry names ONE channel — see the BASELINE
#   comment (#3928). A `*` channel makes the ratchet unable to see a channel added later, and the
#   symptom is a confident `OK`, never a red.
#
# EXIT CODES
#   0  no new occurrences, no stale baseline entries
#   1  a dotted `group.id` with no override ConfigMap, a dotted `auto.offset.reset` asking for a
#      non-default value with no override — or a baseline entry that is now covered and should be
#      removed
#   2  the check could not run (PyYAML missing, tree not found) or the BASELINE itself is malformed
#      (a wildcard channel, an unwatched key). Never conflated with 0.
#
# Run:  python3 .github/scripts/check-kafka-dotted-keys.py [--root .] [--self-test]

import argparse
import pathlib
import re
import sys

import gatelib

try:
    import yaml
except ImportError:  # pragma: no cover - reported as exit 2 by main()
    yaml = None

# The connector's own default for auto.offset.reset. A channel declaring exactly this is a no-op
# even when the key does not resolve, so it is not reported.
CONNECTOR_DEFAULT_OFFSET_RESET = "latest"

WATCHED_KEYS = ("group.id", "auto.offset.reset")

# Occurrences that exist today. Each entry is (service, CHANNEL, key) -> why it is tolerated.
# Adding to this list is a deliberate act that needs a reason; see the ratchet note above.
#
# THE CHANNEL IS PINNED, AND `*` IS REJECTED OUTRIGHT (#3928)
#   This list used to carry six `(service, "*", "auto.offset.reset")` entries. A wildcard channel
#   makes the baseline a property of the SERVICE, so every channel the service gains later is
#   pre-absorbed: the ratchet reports "no new ones" about a finding it never looked at, and the
#   exclusion silently grows past what was ever justified. That is not hypothetical —
#   `openbank-account-service`'s `delegation-events-in` arrived on 2026-08-02 (#3058), one day
#   after the baseline was written (#2969), and inherited the exemption with nobody deciding it.
#   Same repo rule as the pact-drift scope: never let a gate's coverage set be maintained
#   separately from the artifacts it covers, and never let an exclusion be broader than what was
#   actually justified. `validate_baseline()` now refuses a `*` channel with exit 2, so the shape
#   cannot come back by hand.
BASELINE = {
    ("openbank-account-service", "party-events-in", "auto.offset.reset"): "#2945 — declared earliest, effective default; replay risk makes the fix operational",
    # NOT part of the #2945 decision. Arrived after the baseline and was absorbed by the `*` channel
    # (#3058 -> #3928). Pinned here so it is a named, reviewable entry rather than an invisible one;
    # the operational call (msg-override ConfigMap, i.e. a real replay on openbank.delegation.events)
    # is still open and is exactly what the wildcard was hiding.
    ("openbank-account-service", "delegation-events-in", "auto.offset.reset"): "#3928 — post-#2945 arrival, absorbed by the old `*` entry; replay decision still OPEN",
    ("openbank-aml-service", "party-events-in", "auto.offset.reset"): "#2945 — same",
    ("openbank-balance-service", "ledger-events-in", "auto.offset.reset"): "#2945 — same",
    ("openbank-balance-service", "balance-init-in", "auto.offset.reset"): "#2945 — same",
    ("openbank-document-service", "account-created-in", "auto.offset.reset"): "#2945 — same",
    ("openbank-party-service", "kyc-events-in", "auto.offset.reset"): "#2945 — same",
    ("openbank-party-service", "aml-events-in", "auto.offset.reset"): "#2945 — same",
    ("openbank-party-service", "consent-events-in", "auto.offset.reset"): "#2945 — same",
    ("openbank-statement-service", "account-events-in", "auto.offset.reset"): "#2945 — same",
    # #4122/#4217/ADR-0248. The group.id entry that stood here is GONE: the deployed image now
    # carries the channel (check-msg-channel-image-parity.py passes), so the override was added and
    # the key is covered. Only auto.offset.reset remains, and for a different reason than the
    # original deferral — not image parity, which is satisfied, but the #2945 replay rule: its
    # declared `earliest` and its effective `latest` genuinely differ, so making it effective
    # re-reads the retained log for a group already running as `latest`. Owner decision.
    ("openbank-document-service", "billing-outbox-events-in", "auto.offset.reset"): "#2945 — declared earliest vs effective latest; setting it would replay the retained log",
}


def app_name(doc):
    try:
        return doc["quarkus"]["application"]["name"]
    except (KeyError, TypeError):
        return None


def incoming_channels(doc):
    """Yield (channel, config-map) for every mp.messaging.incoming.<channel>."""
    try:
        incoming = doc["mp"]["messaging"]["incoming"]
    except (KeyError, TypeError):
        return
    if not isinstance(incoming, dict):
        return
    for channel, cfg in incoming.items():
        if isinstance(cfg, dict):
            yield channel, cfg


def load_overrides(root):
    """Every `(service, channel, key)` actually set by a *-msg-override ConfigMap.

    Derived from the gitops manifests themselves — never a hand-kept list — so a service that gains
    an override stops being reported without anyone editing this script.

    Two things this got wrong on the first run, both worth keeping in mind:

    1. **Keyed by service, not just channel.** Channel names are NOT globally unique — `party-events-in`
       is consumed by account, aml, card-issuance, kyc, onboarding and others. Keying on the channel
       alone let card-issuance's override silently vouch for account's and aml's, which is how the
       guard reported "0 new occurrences" about services it had never actually cleared.
    2. **Comments are stripped first.** Every one of these ConfigMaps explains the bug in a comment
       that contains the literal string `mp.messaging.incoming.<channel>.group.id`. Matching raw text
       counts that prose as coverage — the repo's recurring "a text guard flags the text explaining
       the thing it guards" failure, here in its silent direction.
    """
    covered = set()
    gitops = pathlib.Path(root) / "openbank-infra" / "gitops" / "components"
    for path in gitops.rglob("*msg-override*.yaml"):
        raw = path.read_text(encoding="utf-8")
        body = "\n".join(ln for ln in raw.splitlines() if not ln.lstrip().startswith("#"))
        name = re.search(r"^\s*name:\s*([\w-]+)-msg-override\s*$", body, re.M)
        if not name:
            continue
        service = f"openbank-{name.group(1)}"
        for m in re.finditer(r"mp\.messaging\.incoming\.([\w.-]+?)\.(group\.id|auto\.offset\.reset)\s*=", body):
            covered.add((service, m.group(1), m.group(2)))
    return covered


def baseline_key(service, channel, key):
    """Exact `(service, channel, key)` only — a baseline entry can never span channels (#3928)."""
    candidate = (service, channel, key)
    return candidate if candidate in BASELINE else None


def validate_baseline(baseline=None):
    """Reject a baseline shape that would silently widen the exclusion. Returns a list of errors.

    The only shape banned here is the one that caused #3928: a `*` channel. It reads as a small
    convenience and is in fact an open-ended exemption for every channel the service has not been
    given yet — the failure lands as a green, so nothing else in the pipeline can notice it.
    """
    if baseline is None:
        baseline = BASELINE
    errors = []
    for entry in sorted(baseline):
        service, channel, key = entry
        if channel == "*":
            errors.append(
                f"baseline entry {entry} uses a wildcard channel. A baseline pins ONE channel, "
                f"or it pre-absorbs every channel {service} gains later (#3928). Enumerate them.",
            )
        if key not in WATCHED_KEYS:
            errors.append(f"baseline entry {entry} names {key!r}, which is not in WATCHED_KEYS.")
    return errors


def config_paths(root):
    """The corpus: the application.yaml set, not the occurrences found in it. A moved resource
    root yields zero of both, and zero findings is the ordinary green."""
    return sorted(pathlib.Path(root).glob("openbank-*/src/main/resources/application.yaml"))


def scan(root):
    """Returns (findings, matched_baseline_keys)."""
    findings = []
    matched = set()
    overrides = load_overrides(root)

    for path in config_paths(root):
        service = path.parts[-5] if len(path.parts) >= 5 else path.parent.name
        doc = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        name = app_name(doc)

        for channel, cfg in incoming_channels(doc):
            for key in WATCHED_KEYS:
                if key not in cfg:
                    continue
                value = str(cfg[key])

                # Covered by a real config source — the intended value actually reaches the connector.
                if (service, channel, key) in overrides:
                    continue

                # NOTE: `group.id == quarkus.application.name` used to be accepted here. It is not
                # any more (#2945) — see the header. A value that merely happens to equal the
                # fallback still does not RESOLVE, so accepting it means the config file and the
                # running consumer agree by accident, which is not a property a gate can keep.
                # auto.offset.reset survives only when it asks for the connector default anyway.
                if key == "auto.offset.reset" and value == CONNECTOR_DEFAULT_OFFSET_RESET:
                    continue

                bk = baseline_key(service, channel, key)
                if bk:
                    matched.add(bk)
                    continue

                fallback = name if key == "group.id" else CONNECTOR_DEFAULT_OFFSET_RESET
                effect = (
                    f"the effective value is the fallback ({fallback!r}) — which happens to equal "
                    f"the declared one, so nothing is broken TODAY and everything breaks on the "
                    f"first rename or channel-specific group (#2945)"
                    if str(fallback) == value
                    else f"the effective value is the fallback ({fallback!r}), not {value!r}"
                )
                findings.append(
                    f"{path}: channel '{channel}' sets `{key}: {value}` as a dotted YAML key with no "
                    f"*-msg-override ConfigMap.\n"
                    f"       That key does not reach the connector (#686), so {effect}.\n"
                    f"       Fix: add `mp.messaging.incoming.{channel}.{key}={value}` to a "
                    f"`*-msg-override.yaml` ConfigMap (config_ordinal=500), as transaction-service does.",
                )
    return findings, matched


SELF_TEST_SERVICES = {
    # (service, application.yaml body, override.properties body or None)
    "openbank-demo-covered-service": (
        """
quarkus:
  application:
    name: openbank-demo-covered-service
mp:
  messaging:
    incoming:
      covered-in:
        group.id: openbank-demo-covered-service
""",
        "config_ordinal=500\nmp.messaging.incoming.covered-in.group.id=openbank-demo-covered-service\n",
    ),
    # The regression this gate exists for since #2945: a group.id equal to the application name.
    # It resolves to the right string today by ACCIDENT and must still be reported.
    "openbank-demo-coincidence-service": (
        """
quarkus:
  application:
    name: openbank-demo-coincidence-service
mp:
  messaging:
    incoming:
      coincidence-in:
        group.id: openbank-demo-coincidence-service
""",
        None,
    ),
    "openbank-demo-differs-service": (
        """
quarkus:
  application:
    name: openbank-demo-differs-service
mp:
  messaging:
    incoming:
      differs-in:
        group.id: some-other-group
      asks-for-default-in:
        auto.offset.reset: latest
      asks-for-non-default-in:
        auto.offset.reset: earliest
""",
        None,
    ),
}

# channel -> must this channel appear in the findings?
SELF_TEST_EXPECT = {
    "covered-in": False,           # override ConfigMap sets it — the only acceptable shape
    "coincidence-in": True,        # equal to quarkus.application.name, still does not resolve
    "differs-in": True,            # differs from the fallback — the loud case
    "asks-for-default-in": False,  # asks for the connector default anyway, so a no-op
    "asks-for-non-default-in": True,
}


def _build_self_test_tree(root):
    """Materialise SELF_TEST_SERVICES under `root`. Shared by both halves of the self-test."""
    for service, (app_yaml, override) in SELF_TEST_SERVICES.items():
        res = root / service / "src" / "main" / "resources"
        res.mkdir(parents=True)
        (res / "application.yaml").write_text(app_yaml, encoding="utf-8")
        if override is None:
            continue
        short = service[len("openbank-"):]
        comp = root / "openbank-infra" / "gitops" / "components" / short
        comp.mkdir(parents=True)
        (comp / f"{short}-msg-override.yaml").write_text(
            # The leading comment repeats the property name on purpose: a text guard that does
            # not strip comments would read this prose as coverage (the silent direction of the
            # "guard matches the text about the thing" failure).
            f"# explains mp.messaging.incoming.covered-in.group.id=... and why it is here\n"
            f"apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: {short}-msg-override\n"
            f"data:\n  override.properties: |\n    " + override.replace("\n", "\n    "),
            encoding="utf-8",
        )


def baseline_self_test():
    """Exercise the BASELINE path itself — the branch #3928 lived in, which had NO coverage.

    Every case here was chosen because the old wildcard code passes it in the WRONG direction:
    absorbing a channel it was never given, and reporting nothing while doing it. A pinned entry
    must cover its own channel and NOTHING else, a `*` entry must be refused outright, and an entry
    matching nothing on the tree must come back as stale.
    """
    import tempfile

    global BASELINE
    saved = BASELINE
    svc = "openbank-demo-differs-service"
    cases = []
    try:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            _build_self_test_tree(root)

            # 1. A pinned entry silences its own channel...
            BASELINE = {(svc, "asks-for-non-default-in", "auto.offset.reset"): "self-test"}
            findings, matched = scan(str(root))
            cases.append((
                "pinned entry silences its own channel",
                not any("asks-for-non-default-in" in f for f in findings) and len(matched) == 1,
            ))
            # ...and does NOT silence a sibling channel of the same service. This is the whole bug.
            cases.append((
                "pinned entry does NOT absorb a sibling channel",
                any("channel 'differs-in'" in f for f in findings),
            ))

            # 2. An entry matching nothing on the tree is reported stale (the reverse ratchet).
            BASELINE = {(svc, "channel-that-does-not-exist", "auto.offset.reset"): "self-test"}
            _, matched = scan(str(root))
            cases.append((
                "baseline entry matching no channel is stale",
                [k for k in BASELINE if k not in matched] == list(BASELINE),
            ))
    finally:
        BASELINE = saved

    # 3. The shape guard refuses the wildcard that caused #3928, and accepts the real baseline.
    cases.append((
        "wildcard channel rejected",
        len(validate_baseline({(svc, "*", "auto.offset.reset"): "self-test"})) == 1,
    ))
    cases.append(("shipped BASELINE is well-formed", validate_baseline() == []))

    failures = 0
    for label, ok in cases:
        print(f"{'pass' if ok else 'FAIL'}  {label}")
        failures += 0 if ok else 1
    return failures, len(cases)


def self_test():
    """Drive the REAL scan() over a synthetic tree, not a retyped copy of its classifier.

    An earlier version of this self-test re-implemented the harmless/flagged decision inline. That
    could not see load_overrides() at all, so the one rule that now decides every group.id verdict
    — "is there an override ConfigMap" — was the one rule it never exercised. It also could not have
    caught the change it was written alongside.

    The must-NOT half is the half that matters: a guard that flags every dotted key is noise.
    """
    import tempfile

    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        _build_self_test_tree(root)
        findings, _ = scan(str(root))

    flagged = {ch for ch in SELF_TEST_EXPECT if any(f"channel '{ch}'" in f for f in findings)}
    for channel, want in sorted(SELF_TEST_EXPECT.items()):
        got = channel in flagged
        ok = got == want
        verdict = "flag" if want else "allow"
        print(f"{'pass' if ok else 'FAIL'}  {channel} -> {verdict}" + ("" if ok else f" (got flag={got})"))
        failures += 0 if ok else 1

    # A finding count larger than the expected set means scan() invented a channel we never declared.
    ok = len(findings) == sum(SELF_TEST_EXPECT.values())
    print(f"{'pass' if ok else 'FAIL'}  finding count == expected" + ("" if ok else f" (got {len(findings)})"))
    failures += 0 if ok else 1

    total = len(SELF_TEST_EXPECT) + 1

    bl_failures, bl_total = baseline_self_test()
    failures += bl_failures
    total += bl_total

    print(f"\nself-test: {total - failures} passed, {failures} failed")
    return 0 if failures == 0 else 2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if yaml is None:
        print("::error::PyYAML is not installed — the check could not run. This is NOT a pass.")
        return 2
    if args.self_test:
        return self_test()

    shape_errors = validate_baseline()
    if shape_errors:
        for e in shape_errors:
            print(f"::error::{e}")
        return 2

    gatelib.subjects(len(config_paths(args.root)), "service application.yaml globbed")
    findings, matched = scan(args.root)
    stale = [k for k in BASELINE if k not in matched]

    for f in findings:
        print(f"NEW  {f}")
    for k in stale:
        print(
            f"STALE  baseline entry {k} no longer occurs — it is either fixed or the service is gone.\n"
            f"       Remove it from BASELINE in this script so the list keeps meaning something.",
        )

    if findings or stale:
        print(f"\n{len(findings)} new occurrence(s), {len(stale)} stale baseline entr(ies).")
        return 1
    print(
        f"kafka dotted keys: OK — {len(BASELINE)} baselined occurrence(s), no new ones. "
        f"See #2945 for why the baselined set is an operational decision, not a pending tidy-up.",
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
