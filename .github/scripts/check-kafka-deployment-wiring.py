#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""A service registered as a Kafka mTLS client must actually be WIRED to reach the broker.

Why this exists
----------------
`check-kafka-acl-coverage.py` (#2598) closed the gap between "a service names a topic" and "its
KafkaUser has the ACL for it." It assumes the connection to the broker itself is a given. It is
not: billing-service's `application.yaml` had read `KAFKA_BOOTSTRAP_SERVERS` /
`KAFKA_SECURITY_PROTOCOL` / `KAFKA_SSL_*` since its outbox producer shipped (ADR-0248), and its
`KafkaUser` + `ExternalSecret` cert projection never existed at all, and its Deployment supplied
NONE of those env vars — so SmallRye's Kafka connector fell through to its bare default of
`localhost:9092`. Nothing failed loudly: the pod stayed up, only its readiness probe reported the
reactive-messaging channel DOWN, and the outbox dispatcher's own circuit breaker quietly parked
every event DEAD after retry exhaustion. Found only by reading a readiness log by hand (#4701).

WHAT IT CHECKS
--------------
For every service whose `application.yaml` declares at least one `smallrye-kafka` channel
(incoming or outgoing — reuses `check-kafka-acl-coverage.py`'s own topic/channel walk), find its
container in the gitops tree (matched by container `name:` against the service's short name, the
same convention `check-kafka-acl-coverage.py` uses for KafkaUser matching) and require
`KAFKA_BOOTSTRAP_SERVERS` in that container's `env:`. A service with Kafka channels but no
matching container is reported as a notice, not a failure — same "not checkable here" shape as
the ACL gate, since some Kafka-using workloads (schedulers, agents) may not run as a plain
Deployment/Rollout container this scan can find.

WHAT IT DELIBERATELY DOES NOT CHECK
------------------------------------
The full mTLS chain (`KAFKA_SECURITY_PROTOCOL`, keystore/truststore paths, the `KafkaUser` CR, or
the `ExternalSecret` projections) — `KAFKA_BOOTSTRAP_SERVERS` alone is the one env var whose
absence silently falls through to `localhost:9092` and is therefore the highest-value single
signal; a service that sets bootstrap servers but botches the TLS half fails loudly at connect
time instead (a real error, not a silent no-op), which is a different and more visible failure
mode this gate does not need to also cover.

Usage:  check-kafka-deployment-wiring.py [--enforce] [--selftest]
Advisory by default (prints ::warning, exits 0) per the repo convention; --enforce fails the build.
"""

from __future__ import annotations

import argparse
import pathlib
import sys

import yaml

import gatelib

REPO = pathlib.Path(__file__).resolve().parents[2]
GITOPS = REPO / "openbank-infra" / "gitops"

# Coverage gaps that exist TODAY and are not fixed by this change. May only SHRINK: an entry no
# longer a gap is itself reported (same idiom as check-kafka-acl-coverage.py's KNOWN_GAPS).
KNOWN_GAPS: dict[str, str] = {}


def _walk(node: object, path: list[str], out: list[tuple[list[str], object]]) -> None:
    if isinstance(node, dict):
        for key, value in node.items():
            _walk(value, path + [str(key)], out)
    elif isinstance(node, list):
        for value in node:
            _walk(value, path, out)
    else:
        out.append((path, node))


def uses_kafka(app_yaml: pathlib.Path) -> bool:
    """True if this service declares any smallrye-kafka channel, incoming or outgoing."""
    try:
        doc = gatelib.load_yaml(app_yaml)
    except yaml.YAMLError:
        return False
    flat: list[tuple[list[str], object]] = []
    _walk(doc, [], flat)
    for path, value in flat:
        if path and path[-1] == "connector" and value == "smallrye-kafka":
            return True
    return False


def container_env_names(short: str) -> dict[str, set[str]]:
    """{container-name: {env-var-names}} for every Deployment/Rollout container in gitops
    whose name matches a service's short directory name."""
    result: dict[str, set[str]] = {}
    for path in gatelib.rglob(GITOPS, "*.yaml"):
        try:
            docs = gatelib.load_yaml_all(path)
        except (yaml.YAMLError, UnicodeDecodeError):
            continue
        for doc in docs:
            if not isinstance(doc, dict) or doc.get("kind") not in ("Deployment", "Rollout"):
                continue
            spec = (doc.get("spec") or {}).get("template", {}).get("spec", {}) or {}
            for container in spec.get("containers") or []:
                if not isinstance(container, dict):
                    continue
                name = container.get("name")
                if name != short:
                    continue
                env_names = {
                    e.get("name") for e in (container.get("env") or [])
                    if isinstance(e, dict) and e.get("name")
                }
                result.setdefault(name, set()).update(env_names)
    return result


def selftest() -> int:
    """Feed the extraction logic inputs it MUST flag and inputs it must NOT."""
    cases_ok = container_env_names("kafka-deployment-wiring-selftest-nonexistent-service")
    if cases_ok:
        print("selftest FAIL: matched a container that cannot exist — matching is too loose.")
        return 1
    # A real fleet member with confirmed wiring (fixed by #4701's own PR) must show the var.
    ledger = container_env_names("ledger-service")
    if "ledger-service" not in ledger or "KAFKA_BOOTSTRAP_SERVERS" not in ledger["ledger-service"]:
        print("selftest FAIL: ledger-service's own known-good KAFKA_BOOTSTRAP_SERVERS wiring "
              "was not found — the container/env extraction itself is broken.")
        return 1
    print("selftest OK: no false match on a nonexistent service, ledger-service's known-good "
          "wiring is found.")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--selftest", action="store_true", help="verify the check can fail")
    args = ap.parse_args()
    if args.selftest:
        return selftest()

    findings: list[str] = []
    used_gaps: set[str] = set()
    checked = 0

    for app_yaml in gatelib.glob(REPO, "openbank-*/src/main/resources/application.yaml"):
        service = app_yaml.parts[len(REPO.parts)]
        if not uses_kafka(app_yaml):
            continue
        short = service.removeprefix("openbank-")
        # Try the bare directory name first, then with "-service" appended: some module
        # directories (openbank-security-scanner) omit the suffix their own container/KafkaUser
        # name carries (security-scanner-service) -- the same asymmetry
        # check-kafka-acl-coverage.py handles from the other direction (its candidates try
        # `short` AND `short.removesuffix("-service")`).
        candidates = [short] if short.endswith("-service") else [short, f"{short}-service"]
        containers: dict[str, set[str]] = {}
        for candidate in candidates:
            containers.update(container_env_names(candidate))
        if not containers:
            print(f"::notice::{service} declares a smallrye-kafka channel but no gitops "
                  f"Deployment/Rollout container named any of {candidates} was found — not "
                  f"checkable here (it may not be deployed yet).")
            continue
        checked += 1
        for name, env_names in containers.items():
            if "KAFKA_BOOTSTRAP_SERVERS" in env_names:
                continue
            # Alternate wiring (analytics-sink, #686): SmallRye's env-var reverse-mapping is
            # ambiguous for a hyphenated channel name, so Kafka config is supplied via a mounted
            # override.properties ConfigMap through QUARKUS_CONFIG_LOCATIONS instead of literal
            # env vars. This gate does not parse the mounted file's contents (that ConfigMap can
            # live in a separate document from the container spec) -- the env var's presence is
            # treated as the deliberate opt-out signal it already is in the one service that uses
            # it. If this starts hiding a real gap, tighten to actually read the ConfigMap.
            if "QUARKUS_CONFIG_LOCATIONS" in env_names:
                continue
            key = f"{service}#{name}"
            if key in KNOWN_GAPS:
                used_gaps.add(key)
                print(f"::notice::known gap {key}: {KNOWN_GAPS[key]}")
                continue
            findings.append(
                f"::error file={app_yaml.relative_to(REPO)}::{service} declares a smallrye-kafka "
                f"channel but its container {name!r} has no KAFKA_BOOTSTRAP_SERVERS env var — "
                f"SmallRye's connector falls through to localhost:9092 and every publish/consume "
                f"fails silently (readiness DOWN, no crash), the exact shape of issue #4701.",
            )

    for key in sorted(set(KNOWN_GAPS) - used_gaps):
        findings.append(
            f"::error::stale KNOWN_GAPS entry {key} — that service is now wired (or no longer "
            f"declares a Kafka channel). Remove it, so the list can only shrink.",
        )

    for line in findings:
        print(line if args.enforce else line.replace("::error", "::warning", 1))
    gatelib.subjects(checked, "Kafka-using service(s) checked")
    verdict = "clean." if not findings else f"{len(findings)} finding(s) above."
    print(f"check-kafka-deployment-wiring: {checked} Kafka-using service(s) checked, "
          f"{len(KNOWN_GAPS)} known gap(s) — {verdict}")
    return 1 if findings and args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
