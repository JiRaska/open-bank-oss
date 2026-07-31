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
#   `group.id`         — the fallback is `quarkus.application.name`. Six services get away with the
#                        broken key purely because the value they wanted happens to equal their
#                        application name. That is correct by coincidence: it breaks the day a
#                        service is renamed, or a channel wants its own group (exactly what
#                        transaction-service did). Accepted, but only when the values match.
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
# EXIT CODES
#   0  no new occurrences, no stale baseline entries
#   1  a new dotted key that neither matches its fallback nor has an override — or a baseline entry
#      that is now covered and should be removed
#   2  the check could not run (PyYAML missing, tree not found). Never conflated with 0.
#
# Run:  python3 .github/scripts/check-kafka-dotted-keys.py [--root .] [--self-test]

import argparse
import pathlib
import re
import sys

try:
    import yaml
except ImportError:  # pragma: no cover - reported as exit 2 by main()
    yaml = None

# The connector's own default for auto.offset.reset. A channel declaring exactly this is a no-op
# even when the key does not resolve, so it is not reported.
CONNECTOR_DEFAULT_OFFSET_RESET = "latest"

WATCHED_KEYS = ("group.id", "auto.offset.reset")

# Occurrences that exist today. Each entry is (service, channel, key) -> why it is tolerated.
# Adding to this list is a deliberate act that needs a reason; see the ratchet note above.
BASELINE = {
    ("openbank-account-service", "*", "auto.offset.reset"): "#2945 — declared earliest, effective default; replay risk makes the fix operational",
    ("openbank-aml-service", "*", "auto.offset.reset"): "#2945 — same",
    ("openbank-balance-service", "*", "auto.offset.reset"): "#2945 — same",
    ("openbank-document-service", "*", "auto.offset.reset"): "#2945 — same",
    ("openbank-party-service", "*", "auto.offset.reset"): "#2945 — same",
    ("openbank-statement-service", "*", "auto.offset.reset"): "#2945 — same",
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
    """Baseline entries may pin a channel or use '*' for the whole service."""
    for candidate in ((service, channel, key), (service, "*", key)):
        if candidate in BASELINE:
            return candidate
    return None


def scan(root):
    """Returns (findings, matched_baseline_keys)."""
    findings = []
    matched = set()
    overrides = load_overrides(root)

    for path in sorted(pathlib.Path(root).glob("openbank-*/src/main/resources/application.yaml")):
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

                # group.id survives when the fallback (quarkus.application.name) is the same string.
                if key == "group.id" and name is not None and value == name:
                    continue
                # auto.offset.reset survives only when it asks for the connector default anyway.
                if key == "auto.offset.reset" and value == CONNECTOR_DEFAULT_OFFSET_RESET:
                    continue

                bk = baseline_key(service, channel, key)
                if bk:
                    matched.add(bk)
                    continue

                fallback = name if key == "group.id" else CONNECTOR_DEFAULT_OFFSET_RESET
                findings.append(
                    f"{path}: channel '{channel}' sets `{key}: {value}` as a dotted YAML key with no "
                    f"*-msg-override ConfigMap.\n"
                    f"       That key does not reach the connector (#686), so the effective value is "
                    f"the fallback ({fallback!r}), not {value!r}.\n"
                    f"       Fix: add `mp.messaging.incoming.{channel}.{key}={value}` to a "
                    f"`*-msg-override.yaml` ConfigMap (config_ordinal=500), as transaction-service does.",
                )
    return findings, matched


SELF_TEST_DOC = """
quarkus:
  application:
    name: openbank-demo-service
mp:
  messaging:
    incoming:
      matches-app-name:
        group.id: openbank-demo-service
      differs-from-app-name:
        group.id: some-other-group
      asks-for-default:
        auto.offset.reset: latest
      asks-for-non-default:
        auto.offset.reset: earliest
"""


def self_test():
    """Exercise the classifier against channels it MUST flag and channels it MUST NOT.

    The must-NOT half is the one that matters: a guard that flags every dotted key would be noise,
    and the whole point is that `group.id` matching the application name is genuinely harmless while
    `auto.offset.reset: earliest` never is.
    """
    doc = yaml.safe_load(SELF_TEST_DOC)
    name = app_name(doc)
    results = {}
    for channel, cfg in incoming_channels(doc):
        for key in WATCHED_KEYS:
            if key not in cfg:
                continue
            value = str(cfg[key])
            harmless = (key == "group.id" and value == name) or (
                key == "auto.offset.reset" and value == CONNECTOR_DEFAULT_OFFSET_RESET
            )
            results[channel] = not harmless  # True == should be flagged

    expected = {
        "matches-app-name": False,
        "differs-from-app-name": True,
        "asks-for-default": False,
        "asks-for-non-default": True,
    }
    failures = 0
    for channel, want in expected.items():
        got = results.get(channel)
        ok = got == want
        verdict = "flag" if want else "allow"
        print(f"{'pass' if ok else 'FAIL'}  {channel} -> {verdict}" + ("" if ok else f" (got flag={got})"))
        failures += 0 if ok else 1

    # app_name must be read from the real path, or every group.id comparison silently "differs".
    ok = name == "openbank-demo-service"
    print(f"{'pass' if ok else 'FAIL'}  quarkus.application.name resolves" + ("" if ok else f" (got {name!r})"))
    failures += 0 if ok else 1

    print(f"\nself-test: {len(expected) + 1 - failures} passed, {failures} failed")
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
