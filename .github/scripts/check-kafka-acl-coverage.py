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
`Write`, from the KafkaUser whose cert the deployed container mounts. Before deployment, the
service-name convention supplies a provisional identity. `patternType: prefix` rules are honoured.

WHAT IT DELIBERATELY DOES NOT CHECK
-----------------------------------
Topics a service names in code rather than in `application.yaml` (a dispatcher that derives the
topic at runtime). A service with neither a matching KafkaUser name nor a GitOps Deployment/Rollout
remains a notice; a deployed but untraceable mTLS identity fails closed.

Usage:  check-kafka-acl-coverage.py [--enforce] [--selftest]
Advisory by default (prints ::warning, exits 0) per the repo convention; --enforce fails the build.
"""

from __future__ import annotations

import argparse
import copy
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
# have since been answered with evidence: deployed workloads mount a Kafka keystore projected
# from their KafkaUser, which this check now resolves through the Deployment and ExternalSecret
# instead of assuming the service and principal share a name. `components/kafka/kafka.yaml` sets
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


def kafka_users(docs: list[dict] | None = None) -> dict[str, list[tuple[str, str, set[str]]]]:
    """{user: [(topic-or-prefix, patternType, {operations})]} in the broker namespace."""
    users: dict[str, list[tuple[str, str, set[str]]]] = {}
    if docs is None:
        docs = gitops_documents()
    for doc in docs:
        if doc.get("kind") != "KafkaUser":
            continue
        meta = doc.get("metadata") or {}
        if meta.get("namespace") != "messaging":
            continue
        name = meta.get("name")
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


def gitops_documents() -> list[dict]:
    """Parse the GitOps corpus once for deployed cert identity resolution."""
    docs: list[dict] = []
    for path in gatelib.rglob(GITOPS, "*.yaml"):
        try:
            parsed = gatelib.load_yaml_all(path)
        except (yaml.YAMLError, UnicodeDecodeError) as exc:
            raise ValueError(f"cannot parse GitOps YAML {path.relative_to(REPO)}") from exc
        docs.extend(doc for doc in parsed if isinstance(doc, dict))
    return docs


def mounted_volume(path: str, container: dict, pod: dict) -> dict | None:
    """Return the one pod volume supplying a container path; ambiguity is unsafe."""
    mounts = [mount for mount in container.get("volumeMounts") or []
              if isinstance(mount, dict) and isinstance(mount.get("mountPath"), str)
              and (path == mount["mountPath"] or path.startswith(mount["mountPath"].rstrip("/") + "/"))]
    if len(mounts) != 1:
        return None
    mount = mounts[0]
    if mount.get("subPath") or mount.get("subPathExpr"):
        return None  # file remapping needs separate proof of the projected key
    volumes = [volume for volume in pod.get("volumes") or []
               if isinstance(volume, dict) and volume.get("name") == mount.get("name")]
    if len(volumes) != 1:
        return None
    volume = volumes[0]
    source = volume.get("secret") or volume.get("configMap") or {}
    if "items" in source:
        relative = path.removeprefix(mount["mountPath"].rstrip("/")).lstrip("/")
        expected_key = pathlib.PurePosixPath(path).name
        if not any(isinstance(item, dict) and item.get("key") == expected_key
                   and item.get("path") == relative for item in source["items"] or []):
            return None
    return volume


def kafka_override(container: dict, pod: dict, namespace: str, docs: list[dict]) -> dict[str, str] | None:
    """Read a mounted Quarkus properties file when Kafka settings are not env vars."""
    env = {entry.get("name"): entry for entry in container.get("env") or [] if isinstance(entry, dict)}
    location = (env.get("QUARKUS_CONFIG_LOCATIONS") or {}).get("value")
    if not isinstance(location, str):
        return None
    volume = mounted_volume(location, container, pod)
    config_name = (volume.get("configMap") or {}).get("name") if volume else None
    if not config_name:
        return None
    configs = [doc for doc in docs if doc.get("kind") == "ConfigMap"
               and (doc.get("metadata") or {}).get("namespace") == namespace
               and (doc.get("metadata") or {}).get("name") == config_name]
    if len(configs) != 1:
        return None
    source = ((configs[0].get("data") or {}).get(pathlib.PurePosixPath(location).name))
    if not isinstance(source, str):
        return None
    properties: dict[str, str] = {}
    for line in source.splitlines():
        key, separator, value = line.partition("=")
        if separator and not key.lstrip().startswith("#"):
            properties[key.strip()] = value.strip()
    return properties


def deployed_user(short: str, docs: list[dict]) -> tuple[str | None, str | None]:
    """Resolve a container's mTLS principal, or explain an unsafe deployed chain.

    A missing container is not proof of a broken identity: the module may be undeployed.
    Once a container exists, the broker's only listener is mTLS :9093, so a missing or
    ambiguous cert chain must fail rather than print a no-user notice.
    """
    names = {short, f"{short}-service"} if not short.endswith("-service") else {short}
    workloads: list[tuple[str, dict, dict]] = []
    for doc in docs:
        if doc.get("kind") not in ("Deployment", "Rollout"):
            continue
        namespace = (doc.get("metadata") or {}).get("namespace")
        pod = (((doc.get("spec") or {}).get("template") or {}).get("spec") or {})
        containers = pod.get("containers") or []
        for container in containers:
            if isinstance(container, dict) and container.get("name") in names:
                workloads.append((namespace, container, pod))
    if not workloads:
        return None, None

    identities: set[str] = set()
    for namespace, container, pod in workloads:
        env = {entry.get("name"): entry for entry in container.get("env") or [] if isinstance(entry, dict)}
        protocol = (env.get("KAFKA_SECURITY_PROTOCOL") or {}).get("value")
        location = (env.get("KAFKA_SSL_KEYSTORE_LOCATION") or {}).get("value")
        if not protocol or not location:
            override = kafka_override(container, pod, namespace, docs) or {}
            protocol = protocol or override.get("kafka.security.protocol")
            location = location or override.get("kafka.ssl.keystore.location")
        if protocol != "SSL":
            return None, "deployed container has no verified SSL Kafka protocol"
        if not isinstance(location, str):
            return None, "deployed mTLS container has no verified keystore location"
        cert_secret = (((env.get("KAFKA_SSL_KEYSTORE_PASSWORD") or {}).get("valueFrom") or {})
                       .get("secretKeyRef") or {}).get("name")
        if not cert_secret:
            return None, "deployed mTLS container has no keystore Secret reference"
        volume = mounted_volume(location, container, pod)
        if not volume or (volume.get("secret") or {}).get("secretName") != cert_secret:
            return None, "deployed keystore path is not mounted from the password Secret"
        extracts: set[str] = set()
        matching_secrets = 0
        for doc in docs:
            if doc.get("kind") != "ExternalSecret":
                continue
            meta = doc.get("metadata") or {}
            if meta.get("namespace") != namespace:
                continue
            spec = doc.get("spec") or {}
            if (spec.get("target") or {}).get("name", meta.get("name")) != cert_secret:
                continue
            matching_secrets += 1
            store = spec.get("secretStoreRef") or {}
            if store.get("kind") != "ClusterSecretStore" or store.get("name") != "kafka-messaging-certs":
                return None, "deployed keystore is not projected from the Kafka cert store"
            # A target template or explicit data mapping may replace user.p12 while
            # leaving the extract key in place; that key would no longer prove the
            # cert principal mounted by the container.
            data_from = spec.get("dataFrom") or []
            target = spec.get("target") or {}
            if spec.get("data") or target.get("template") or len(data_from) != 1:
                return None, "deployed keystore ExternalSecret is not extract-only"
            item = data_from[0]
            if not isinstance(item, dict) or set(item) != {"extract"}:
                return None, "deployed keystore ExternalSecret is not extract-only"
            extract = item.get("extract")
            if not isinstance(extract, dict) or set(extract) != {"key"} or not isinstance(extract["key"], str):
                return None, "deployed keystore ExternalSecret has no sole extract key"
            extracts.add(extract["key"])
        if matching_secrets != 1 or len(extracts) != 1:
            return None, "deployed mTLS keystore has no unique ExternalSecret extract identity"
        identities.update(extracts)
    if len(identities) != 1:
        return None, "deployed containers resolve to different Kafka identities"
    identity = next(iter(identities))
    principals = [doc for doc in docs if doc.get("kind") == "KafkaUser"
                  and (doc.get("metadata") or {}).get("name") == identity
                  and (doc.get("metadata") or {}).get("namespace") == "messaging"]
    if len(principals) != 1:
        return None, "deployed cert identity has no unique KafkaUser in messaging namespace"
    if ((principals[0].get("spec") or {}).get("authentication") or {}).get("type") != "tls":
        return None, "deployed cert identity KafkaUser is not TLS-authenticated"
    return identity, None


def acl_candidates(short: str, users: dict, docs: list[dict]) -> tuple[list[str], str | None]:
    """Prefer the deployed cert principal; use the name convention only before deployment."""
    identity, error = deployed_user(short, docs)
    if error:
        return [], error
    if identity is not None:
        if identity not in users:
            return [], "deployed KafkaUser identity has no parsed topic ACLs"
        return [identity], None
    return [u for u in users if u in (short, short.removesuffix("-service"))], None


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

    # The cert mounted by a deployed service is authoritative even when its KafkaUser
    # name differs from the service directory. An undeployed module stays a notice;
    # a deployed but untraceable cert must fail closed.
    deployment = {
        "kind": "Deployment", "metadata": {"namespace": "platform"},
        "spec": {"template": {"spec": {"containers": [{
            "name": "case-coordinator-agent", "env": [
                {"name": "KAFKA_SECURITY_PROTOCOL", "value": "SSL"},
                {"name": "KAFKA_SSL_KEYSTORE_LOCATION", "value": "/mnt/kafka/user.p12"},
                {"name": "KAFKA_SSL_KEYSTORE_PASSWORD", "valueFrom": {
                    "secretKeyRef": {"name": "case-coordinator-kafka-keystore"},
                }},
            ],
            "volumeMounts": [{"name": "keystore", "mountPath": "/mnt/kafka"}],
        }], "volumes": [{"name": "keystore", "secret": {
            "secretName": "case-coordinator-kafka-keystore",
        }}]}}},
    }
    cert = {
        "kind": "ExternalSecret", "metadata": {"namespace": "platform"},
        "spec": {"target": {"name": "case-coordinator-kafka-keystore"},
                 "secretStoreRef": {"kind": "ClusterSecretStore", "name": "kafka-messaging-certs"},
                 "dataFrom": [{"extract": {"key": "case-coordinator"}}]},
    }
    principal = {"kind": "KafkaUser", "metadata": {"name": "case-coordinator", "namespace": "messaging"},
                 "spec": {"authentication": {"type": "tls"}}}
    identity, error = deployed_user("case-coordinator-agent", [deployment, cert, principal])
    if (identity, error) != ("case-coordinator", None):
        print(f"selftest FAIL: deployed cert identity resolved as {(identity, error)}")
        return 1
    # A misleading same-directory principal may have the needed grant; the cert's
    # actual principal lacks it. Only the latter is allowed into the ACL check.
    grants = {
        "case-coordinator": [("openbank.other", "literal", {"Write"})],
        "case-coordinator-agent": [("openbank.dlq.demo", "literal", {"Write"})],
    }
    candidates, error = acl_candidates("case-coordinator-agent", grants, [deployment, cert, principal])
    if (candidates, error) != (["case-coordinator"], None) or covers(
        "openbank.dlq.demo", "Write", grants[candidates[0]]
    ) or not covers("openbank.dlq.demo", "Write", grants["case-coordinator-agent"]):
        print("selftest FAIL: derived principal with a missing topic ACL would pass")
        return 1
    if deployed_user("tax-reporting-service", [deployment, cert, principal]) != (None, None):
        print("selftest FAIL: undeployed module did not remain unclassified")
        return 1
    for broken in ([deployment, principal], [deployment, cert], [deployment, cert, {
        "kind": "ExternalSecret", "metadata": {"namespace": "platform"},
        "spec": {"target": {"name": "case-coordinator-kafka-keystore"},
                 "dataFrom": [{"extract": {"key": "wrong-user"}}]},
    }, principal]):
        identity, error = deployed_user("case-coordinator-agent", broken)
        if identity is not None or error is None:
            print("selftest FAIL: deployed missing/ambiguous mTLS chain did not fail closed")
            return 1

    wrong_mount = copy.deepcopy(deployment)
    wrong_mount["spec"]["template"]["spec"]["volumes"][0]["secret"]["secretName"] = "wrong-secret"
    wrong_items = copy.deepcopy(deployment)
    wrong_items["spec"]["template"]["spec"]["volumes"][0]["secret"]["items"] = [
        {"key": "some-other-key", "path": "user.p12"},
    ]
    wrong_store = copy.deepcopy(cert)
    wrong_store["spec"]["secretStoreRef"]["name"] = "untrusted-store"
    wrong_template = copy.deepcopy(cert)
    wrong_template["spec"]["target"]["template"] = {"data": {"user.p12": "other-cert"}}
    wrong_data = copy.deepcopy(cert)
    wrong_data["spec"]["data"] = [{"secretKey": "user.p12", "remoteRef": {"key": "other"}}]
    for broken_deployment, broken_cert in ((wrong_mount, cert), (wrong_items, cert),
                                            (deployment, wrong_store),
                                            (deployment, wrong_template), (deployment, wrong_data)):
        identity, error = deployed_user("case-coordinator-agent", [broken_deployment, broken_cert, principal])
        if identity is not None or error is None:
            print("selftest FAIL: wrong mounted Secret/store or cert override did not fail closed")
            return 1
    wrong_auth = copy.deepcopy(principal)
    wrong_auth["spec"]["authentication"]["type"] = "scram-sha-512"
    if deployed_user("case-coordinator-agent", [deployment, cert, wrong_auth])[1] is None:
        print("selftest FAIL: non-TLS KafkaUser was accepted as a cert principal")
        return 1

    # analytics-sink's genuine alternate: protocol and path are in a mounted
    # properties ConfigMap rather than env vars.
    override_deployment = copy.deepcopy(deployment)
    pod = override_deployment["spec"]["template"]["spec"]
    container = pod["containers"][0]
    container["env"] = [entry for entry in container["env"]
                        if entry["name"] not in ("KAFKA_SECURITY_PROTOCOL", "KAFKA_SSL_KEYSTORE_LOCATION")]
    container["env"].append({"name": "QUARKUS_CONFIG_LOCATIONS", "value": "/mnt/config/override.properties"})
    container["volumeMounts"].append({"name": "override", "mountPath": "/mnt/config"})
    pod["volumes"].append({"name": "override", "configMap": {"name": "kafka-override"}})
    override = {"kind": "ConfigMap", "metadata": {"namespace": "platform", "name": "kafka-override"},
                "data": {"override.properties": "kafka.security.protocol=SSL\n"
                        "kafka.ssl.keystore.location=/mnt/kafka/user.p12\n"}}
    if deployed_user("case-coordinator-agent", [override_deployment, override, cert, principal]) != (
        "case-coordinator", None
    ):
        print("selftest FAIL: mounted Kafka properties did not resolve the cert identity")
        return 1

    # ACLs from a same-named KafkaUser in another namespace do not belong to
    # this broker. The inventory must use only messaging/KafkaUser grants.
    outside = {"kind": "KafkaUser", "metadata": {"namespace": "other", "name": "case-coordinator"},
               "spec": {"authorization": {"acls": [{"resource": {"type": "topic", "name": "openbank.dlq.demo"},
                                                  "operations": ["Write"]}]}}}
    if kafka_users([outside, principal]):
        print("selftest FAIL: KafkaUser ACL from another namespace entered the inventory")
        return 1

    print(
        f"selftest OK: {len(cases)} coverage case(s), both directions (flags the near-miss, spares the "
        f"prefix match), {len(expected)} direction case(s) incl. the write-only DLQ, and deployed "
        "cert identity/missing-chain controls.",
    )
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--selftest", action="store_true", help="verify the check can fail")
    args = ap.parse_args()
    if args.selftest:
        return selftest()

    try:
        docs = gitops_documents()
    except ValueError as exc:
        print(f"::error::{exc}; Kafka identity scope cannot be established.")
        return 1
    users = kafka_users(docs)
    findings: list[str] = []
    used_gaps: set[str] = set()
    checked = 0
    for app_yaml in gatelib.glob(REPO, "openbank-*/src/main/resources/application.yaml"):
        service = app_yaml.parts[len(REPO.parts)]
        wanted = required(app_yaml)
        if not wanted:
            continue
        short = service.removeprefix("openbank-")
        candidates, error = acl_candidates(short, users, docs)
        if error:
            findings.append(
                f"::error file={app_yaml.relative_to(REPO)}::{service} references "
                f"{len(wanted)} topic(s) and {error}; Kafka authorization cannot be "
                "verified for its deployed container.",
            )
            continue
        if not candidates:
            print(f"::notice::{service} references {len(wanted)} topic(s) but has no "
                  "matching GitOps Deployment/Rollout container or KafkaUser; Kafka ACLs "
                  "cannot be verified before deployment.")
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
