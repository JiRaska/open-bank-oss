#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Kafka dotted-key guard (#11683): SmallRye quotes dotted YAML leaf keys under mp.messaging.
# The quoted property is not the plain property the Kafka connector reads. This applies to every
# observed family in both directions: group/offset, client ID, serializers/deserializers and
# bootstrap servers. See DottedMessagingKeyResolutionTest for real YamlConfigSource controls.
#
# THE MECHANISM
#   SmallRye Config's YAML source quotes any leaf map key containing a literal dot. For example,
#   `value.serializer:` registers as `...channel."value.serializer"`, while the Kafka connector
#   reads `...channel.value.serializer`. The real YamlConfigSource regression test covers all nine
#   direction/key families seen in the fleet, including the nested spelling that does resolve.
#   An inert YAML entry can be masked by a default or another config source; the gate does not
#   claim that every baselined service fails at runtime.
#
# WHY IT IS A RATCHET
#   Current occurrences are frozen by exact service, direction, channel and key in the JSON
#   baseline. Making an existing `auto.offset.reset: earliest` effective could replay a whole
#   topic, so remediation is a channel-by-channel operational decision. New occurrences fail;
#   removals and entries newly covered by a msg-override ConfigMap become stale and also fail
#   until the baseline is trimmed. A wildcard channel is forbidden (#3928).
#
# EXIT CODES
#   0  no new occurrences, no stale baseline entries
#   1  a new dotted leaf without an exact override, or a stale baseline entry
#   2  the check could not run (PyYAML missing, tree not found) or the BASELINE itself is malformed
#      (a wildcard channel, duplicate row). Never conflated with 0.
#
# Run:  python3 .github/scripts/check-kafka-dotted-keys.py [--root .] [--self-test]

import argparse
import json
import pathlib
import sys

import gatelib

try:
    import yaml
except ImportError:  # pragma: no cover - reported as exit 2 by main()
    yaml = None

BASELINE_PATH = pathlib.Path(".github/gates/kafka-dotted-keys-baseline.json")

# Occurrences that exist today. Each entry pins a single service, direction, channel and key.
# Adding one needs an issue-backed reason; the checked-in JSON is the migration inventory.
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
BASELINE = {}


def load_baseline(root):
    """Frozen exact (service, direction, channel, key) occurrences, never a live inventory."""
    rows = json.loads((pathlib.Path(root) / BASELINE_PATH).read_text(encoding="utf-8"))
    if not isinstance(rows, list) or any(not isinstance(row, list) or len(row) != 4 for row in rows):
        raise ValueError(f"{BASELINE_PATH} must contain a list of four-string entries")
    entries = [tuple(row) for row in rows]
    if any(not all(isinstance(value, str) for value in row) for row in entries):
        raise ValueError(f"{BASELINE_PATH} entries must contain only strings")
    if len(entries) != len(set(entries)):
        raise ValueError(f"{BASELINE_PATH} has duplicate entries")
    return {entry: "#11683: pre-existing dotted YAML leaf" for entry in entries}


def channels(doc):
    """Yield (direction, channel, config-map) for both mp.messaging directions."""
    try:
        messaging = doc["mp"]["messaging"]
    except (KeyError, TypeError):
        return
    if not isinstance(messaging, dict):
        return
    for direction in ("incoming", "outgoing"):
        entries = messaging.get(direction)
        if not isinstance(entries, dict):
            continue
        for channel, cfg in entries.items():
            if isinstance(cfg, dict):
                yield direction, channel, cfg


def mounted_override_names(directory):
    """ConfigMaps loaded as a properties source by a container in this GitOps component."""
    mounted = set()
    for path in directory.glob("*.yaml"):
        for doc in yaml.safe_load_all(path.read_text(encoding="utf-8")):
            if not isinstance(doc, dict) or doc.get("kind") not in ("Deployment", "Rollout"):
                continue
            pod = (((doc.get("spec") or {}).get("template") or {}).get("spec") or {})
            volumes = {}
            for volume in pod.get("volumes") or []:
                if not isinstance(volume, dict) or not isinstance(volume.get("configMap"), dict):
                    continue
                config_map = volume["configMap"]
                items = config_map.get("items")
                # A projected ConfigMap may omit or rename override.properties. The file named
                # in CONFIG_LOCATIONS exists only when the projection keeps that exact path.
                projects_override = items is None or (
                    isinstance(items, list) and any(
                        isinstance(item, dict)
                        and item.get("key") == "override.properties"
                        and item.get("path") == "override.properties"
                        for item in items
                    )
                )
                if projects_override:
                    volumes[volume.get("name")] = config_map.get("name")
            for container in pod.get("containers") or []:
                if not isinstance(container, dict):
                    continue
                locations = {
                    env.get("value") for env in container.get("env") or []
                    if isinstance(env, dict) and env.get("name") in
                    ("QUARKUS_CONFIG_LOCATIONS", "SMALLRYE_CONFIG_LOCATIONS")
                }
                for mount in container.get("volumeMounts") or []:
                    if not isinstance(mount, dict):
                        continue
                    name = volumes.get(mount.get("name"))
                    base = mount.get("mountPath")
                    if isinstance(name, str) and isinstance(base, str):
                        if f"{base.rstrip('/')}/override.properties" in locations:
                            mounted.add(name)
    return mounted


def load_overrides(root):
    """Every `(service, full property name)` loaded with the same declared value.

    Derived from the gitops manifests themselves — never a hand-kept list — so a service that gains
    an override stops being reported without anyone editing this script.

    Two things the earlier text matcher got wrong, both worth keeping in mind:

    1. **Keyed by service, not just channel.** Channel names are NOT globally unique — `party-events-in`
       is consumed by account, aml, card-issuance, kyc, onboarding and others. Keying on the channel
       alone let card-issuance's override silently vouch for account's and aml's, which is how the
       guard reported "0 new occurrences" about services it had never actually cleared.
    2. **Only parsed data.override.properties counts.** Prose or a similarly named but unmounted
       ConfigMap cannot vouch for the channel. The properties source must declare ordinal >= 500.
    """
    covered = {}
    gitops = pathlib.Path(root) / "openbank-infra" / "gitops" / "components"
    for path in gitops.rglob("*msg-override*.yaml"):
        mounts = mounted_override_names(path.parent)
        for doc in yaml.safe_load_all(path.read_text(encoding="utf-8")):
            if not isinstance(doc, dict) or doc.get("kind") != "ConfigMap":
                continue
            name = (doc.get("metadata") or {}).get("name")
            if not isinstance(name, str) or not name.endswith("-msg-override") or name not in mounts:
                continue
            body = (doc.get("data") or {}).get("override.properties")
            if not isinstance(body, str):
                continue
            properties = {}
            for line in body.splitlines():
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, value = line.split("=", 1)
                properties[key.strip()] = value.strip()
            try:
                if int(properties.get("config_ordinal", "0")) < 500:
                    continue
            except ValueError:
                continue
            service = f"openbank-{name[:-len('-msg-override')]}"
            for key, value in properties.items():
                if key.startswith(("mp.messaging.incoming.", "mp.messaging.outgoing.")):
                    covered[(service, key)] = value
    return covered


def baseline_key(service, direction, channel, key):
    """Exact occurrence only — neither channels nor directions can share an exemption."""
    candidate = (service, direction, channel, key)
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
        if len(entry) != 4:
            errors.append(f"baseline entry {entry} must name service, direction, channel and key")
            continue
        service, direction, channel, key = entry
        if channel == "*" or "*" in key or "*" in service:
            errors.append(
                f"baseline entry {entry} uses a wildcard. A baseline pins ONE channel and key, "
                f"or it pre-absorbs every channel {service} gains later (#3928). Enumerate them.",
            )
        if direction not in ("incoming", "outgoing") or "." not in key:
            errors.append(f"baseline entry {entry} must name a direction and a dotted leaf key")
    return errors


def config_paths(root):
    """The corpus is every service application.yaml, even where no dotted key exists."""
    return sorted(pathlib.Path(root).glob("openbank-*/src/main/resources/application.yaml"))


def scan(root):
    """Returns (findings, matched_baseline_keys)."""
    findings = []
    matched = set()
    overrides = load_overrides(root)

    for path in config_paths(root):
        service = path.parts[-5] if len(path.parts) >= 5 else path.parent.name
        doc = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        for direction, channel, cfg in channels(doc):
            for key, raw_value in cfg.items():
                if not isinstance(key, str) or "." not in key:
                    continue
                value = str(raw_value)
                property_name = f"mp.messaging.{direction}.{channel}.{key}"

                # Covered by a real config source — the intended value actually reaches the connector.
                if overrides.get((service, property_name)) == value:
                    continue

                bk = baseline_key(service, direction, channel, key)
                if bk:
                    matched.add(bk)
                    continue

                findings.append(
                    f"{path}: {direction} channel '{channel}' sets `{key}: {value}` as a dotted YAML key with no "
                    f"*-msg-override ConfigMap.\n"
                    f"       Its plain property name does not resolve through YamlConfigSource (#11683). "
                    f"The effective value comes from another source, a default, or fails validation.\n"
                    f"       Fix: use a nested YAML mapping or set `{property_name}` from a "
                    f"`*-msg-override.yaml` ConfigMap (config_ordinal=500).",
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
      deserializer-in:
        value.deserializer: example.CustomDeserializer
    outgoing:
      serializer-out:
        value.serializer: example.CustomSerializer
      broker-out:
        bootstrap.servers: example.invalid:9092
""",
        None,
    ),
}

# channel -> must this channel appear in the findings?
SELF_TEST_EXPECT = {
    "covered-in": False,           # override ConfigMap sets it — the only acceptable shape
    "coincidence-in": True,        # equal to quarkus.application.name, still does not resolve
    "differs-in": True,            # differs from the fallback — the loud case
    "asks-for-default-in": True,   # still a quoted property; fallback equality is coincidence
    "asks-for-non-default-in": True,
    "deserializer-in": True,
    "serializer-out": True,
    "broker-out": True,
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
        (comp / f"{short}-deployment.yaml").write_text(
            f"apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: {short}\n"
            f"spec:\n  template:\n    spec:\n      containers:\n        - name: {short}\n"
            f"          env:\n            - name: QUARKUS_CONFIG_LOCATIONS\n"
            f"              value: /mnt/msg/override.properties\n"
            f"          volumeMounts:\n            - name: msg\n              mountPath: /mnt/msg\n"
            f"      volumes:\n        - name: msg\n          configMap:\n"
            f"            name: {short}-msg-override\n",
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
            BASELINE = {(svc, "incoming", "asks-for-non-default-in", "auto.offset.reset"): "self-test"}
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
            BASELINE = {(svc, "incoming", "channel-that-does-not-exist", "auto.offset.reset"): "self-test"}
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
        len(validate_baseline({(svc, "incoming", "*", "auto.offset.reset"): "self-test"})) == 1,
    ))
    shipped = load_baseline(pathlib.Path(__file__).resolve().parents[2])
    cases.append(("shipped BASELINE is well-formed", validate_baseline(shipped) == [] and len(shipped) > 0))

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

    The must-NOT half matters too: an exact override must not be called a new occurrence.
    """
    import tempfile

    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        _build_self_test_tree(root)
        findings, _ = scan(str(root))
        comp = root / "openbank-infra" / "gitops" / "components" / "demo-covered-service"
        override = comp / "demo-covered-service-msg-override.yaml"
        deployment = comp / "demo-covered-service-deployment.yaml"
        original = override.read_text(encoding="utf-8")
        manifest = deployment.read_text(encoding="utf-8")
        negative_overrides = (
            ("wrong override value is not coverage", original.replace("group.id=openbank-demo-covered-service", "group.id=wrong")),
            ("wrong properties field is not coverage", original.replace("override.properties: |", "other.properties: |")),
            ("low source ordinal is not coverage", original.replace("config_ordinal=500", "config_ordinal=100")),
        )
        override_cases = []
        for label, body in negative_overrides:
            override.write_text(body, encoding="utf-8")
            bad, _ = scan(str(root))
            override_cases.append((label, any("channel 'covered-in'" in f for f in bad)))
        override.write_text(original, encoding="utf-8")
        deployment.unlink()
        bad, _ = scan(str(root))
        override_cases.append(("unmounted ConfigMap is not coverage", any("channel 'covered-in'" in f for f in bad)))
        deployment.write_text(manifest, encoding="utf-8")
        projection = "            name: demo-covered-service-msg-override\n"
        excluded = projection + "            items:\n              - key: other.properties\n                path: other.properties\n"
        deployment.write_text(manifest.replace(projection, excluded), encoding="utf-8")
        bad, _ = scan(str(root))
        override_cases.append(("projected ConfigMap without override.properties is not coverage", any("channel 'covered-in'" in f for f in bad)))
        renamed = projection + "            items:\n              - key: override.properties\n                path: renamed.properties\n"
        deployment.write_text(manifest.replace(projection, renamed), encoding="utf-8")
        bad, _ = scan(str(root))
        override_cases.append(("renamed override.properties projection is not coverage", any("channel 'covered-in'" in f for f in bad)))
        included = projection + "            items:\n              - key: override.properties\n                path: override.properties\n"
        deployment.write_text(manifest.replace(projection, included), encoding="utf-8")
        good, _ = scan(str(root))
        override_cases.append(("explicit override.properties projection is coverage", not any("channel 'covered-in'" in f for f in good)))
        deployment.write_text(manifest, encoding="utf-8")

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

    for label, ok in override_cases:
        print(f"{'pass' if ok else 'FAIL'}  {label}")
        failures += 0 if ok else 1

    total = len(SELF_TEST_EXPECT) + 1 + len(override_cases)

    bl_failures, bl_total = baseline_self_test()
    failures += bl_failures
    total += bl_total

    # Run the real CLI against a tiny frozen inventory. A newly added sibling must exit 1,
    # removing a frozen entry must also exit 1, and a matching baseline must exit 0.
    import subprocess
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        app = root / "openbank-demo" / "src" / "main" / "resources" / "application.yaml"
        app.parent.mkdir(parents=True)
        baseline = root / BASELINE_PATH
        baseline.parent.mkdir(parents=True)
        original = "mp:\n  messaging:\n    outgoing:\n      first:\n        client.id: first\n"
        app.write_text(original, encoding="utf-8")
        baseline.write_text(json.dumps([["openbank-demo", "outgoing", "first", "client.id"]]), encoding="utf-8")

        def run_cli():
            return subprocess.run(
                [sys.executable, __file__, "--root", str(root)],
                capture_output=True, text=True, check=False,
            )

        controls = [("exact baseline remains green", run_cli().returncode == 0)]
        app.write_text(original + "      second:\n        value.serializer: example.Serializer\n", encoding="utf-8")
        added = run_cli()
        controls.append(("new outgoing serializer exits 1", added.returncode == 1 and "NEW" in added.stdout))
        app.write_text("mp:\n  messaging:\n    outgoing:\n      first:\n        topic: clean\n", encoding="utf-8")
        removed = run_cli()
        controls.append(("removed baseline entry exits 1", removed.returncode == 1 and "STALE" in removed.stdout))
        for label, ok in controls:
            print(f"{'pass' if ok else 'FAIL'}  {label}")
            failures += 0 if ok else 1
        total += len(controls)

    print(f"\nself-test: {total - failures} passed, {failures} failed")
    return 0 if failures == 0 else 2


def main():
    global BASELINE
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if yaml is None:
        print("::error::PyYAML is not installed — the check could not run. This is NOT a pass.")
        return 2
    if args.self_test:
        return self_test()

    try:
        BASELINE = load_baseline(args.root)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"::error::Cannot load {BASELINE_PATH}: {error}")
        return 2

    shape_errors = validate_baseline()
    if shape_errors:
        for e in shape_errors:
            print(f"::error::{e}")
        return 2

    paths = config_paths(args.root)
    if not paths:
        print("::error::No service application.yaml found — the scan scope may have moved")
        return 2
    gatelib.subjects(len(paths), "service application.yaml globbed")
    findings, matched = scan(args.root)
    stale = [k for k in BASELINE if k not in matched]

    for f in findings:
        print(f"NEW  {f}")
    for k in stale:
        print(
            f"STALE  baseline entry {k} no longer occurs — it is either fixed or the service is gone.\n"
            f"       Remove it from {BASELINE_PATH} so the list keeps meaning something.",
        )

    if findings or stale:
        print(f"\n{len(findings)} new occurrence(s), {len(stale)} stale baseline entr(ies).")
        return 1
    print(
        f"kafka dotted keys: OK — {len(BASELINE)} exact baselined occurrence(s), no new ones. "
        f"See #11683 for the rollout decision behind this inventory.",
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
