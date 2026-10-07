#!/usr/bin/env python3
"""Keep the Pact Broker's pooled PostgreSQL connections failover-safe (#7376)."""

from __future__ import annotations

import argparse
import copy
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "openbank-infra/gitops/components/pact-broker/pact-broker.yaml"
SETTING = "PACT_BROKER_DATABASE_CONNECTION_VALIDATION_TIMEOUT"


def check(documents: list[object]) -> list[str]:
    deployments = [
        doc for doc in documents
        if isinstance(doc, dict)
        and doc.get("kind") == "Deployment"
        and doc.get("metadata", {}).get("name") == "pact-broker"
        and doc.get("metadata", {}).get("namespace") == "pact-broker"
    ]
    if len(deployments) != 1:
        return ["expected exactly one pact-broker Deployment in pact-broker namespace"]
    pod = deployments[0].get("spec", {}).get("template", {}).get("spec", {})
    containers = pod.get("containers", [])
    brokers = [container for container in containers
               if container.get("name") == "pact-broker"]
    if len(brokers) != 1:
        return ["expected exactly one pact-broker container"]
    env = brokers[0].get("env", [])
    values = [entry for entry in env if entry.get("name") == SETTING]
    if len(values) != 1:
        return [f"expected exactly one {SETTING} declaration"]
    if values[0] != {"name": SETTING, "value": "-1"}:
        return [f"{SETTING} must be literal -1 (validate every pooled connection)"]
    return []


def self_test() -> None:
    good = {
        "kind": "Deployment",
        "metadata": {"name": "pact-broker", "namespace": "pact-broker"},
        "spec": {"template": {"spec": {"containers": [
            {"name": "pact-broker", "env": [{"name": SETTING, "value": "-1"}]},
        ]}}},
    }
    assert not check([good])
    mutations = ("missing", "default", "duplicate", "indirect",
                 "wrong-container", "wrong-namespace")
    for mutation in mutations:
        bad = copy.deepcopy(good)
        env = bad["spec"]["template"]["spec"]["containers"][0]["env"]
        if mutation == "missing":
            env.clear()
        elif mutation == "default":
            env[0]["value"] = "3600"
        elif mutation == "duplicate":
            env.append(dict(env[0]))
        elif mutation == "indirect":
            env[0] = {"name": SETTING, "valueFrom": {
                "configMapKeyRef": {"name": "x", "key": "x"},
            }}
        elif mutation == "wrong-container":
            bad["spec"]["template"]["spec"]["containers"][0]["name"] = "sidecar"
        else:
            bad["metadata"]["namespace"] = "other"
        assert check([bad]), f"{mutation} escaped connection-validation gate"
    print("pact-broker DB validation self-test: 7 cases passed")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    try:
        documents = list(yaml.safe_load_all(MANIFEST.read_text()))
    except (OSError, yaml.YAMLError) as exc:
        print(f"Pact Broker manifest unavailable or invalid: {exc}")
        return 1
    errors = check(documents)
    for error in errors:
        print(f"Pact Broker database connection validation: {error}")
    if not errors:
        print("SUBJECTS=1  # Pact Broker Deployment checked")
        print("Pact Broker database connection validation: declared")
    return int(bool(errors))


if __name__ == "__main__":
    raise SystemExit(main())
