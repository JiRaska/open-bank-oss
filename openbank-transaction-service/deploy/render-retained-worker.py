#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Render an independently retained worker from an environment's reviewed API pod template.

Never applies resources. Environment-specific output belongs in the private deployment
configuration, not in this public repository. Requires PyYAML.
"""
import argparse
import copy
import re
import sys

import yaml


APP_LABEL = "app.kubernetes.io/name"


def render(source, image, legacy=False):
    if not re.fullmatch(r"[^\s@]+@sha256:[a-f0-9]{64}", image):
        raise ValueError("worker image must be pinned by its complete sha256 digest")
    if source.get("kind") != "Rollout" or source.get("metadata", {}).get("name") != "transaction-service":
        raise ValueError("source must be the reviewed transaction-service Rollout")
    digest = image.rsplit(":", 1)[1]
    name = f"transaction-worker-{'legacy-' if legacy else ''}{digest[:12]}"
    namespace = source["metadata"]["namespace"]
    template = copy.deepcopy(source["spec"]["template"])
    labels = template["metadata"].setdefault("labels", {})
    labels[APP_LABEL] = name
    labels["app.kubernetes.io/component"] = "temporal-worker"
    # Kubernetes label values are limited to 63 characters; full digest is an annotation/env value.
    template["metadata"].setdefault("annotations", {})["openbank.tech/worker-image-digest"] = digest
    for key in ("rollouts-pod-template-hash", "pod-template-hash"):
        labels.pop(key, None)
    containers = template["spec"]["containers"]
    main = [c for c in containers if c["name"] == "transaction-service"]
    if len(main) != 1:
        raise ValueError("source must contain exactly one transaction-service container")
    container = main[0]
    container["image"] = image
    values = {
        "OPENBANK_TRANSACTION_WORKER_ENABLED": "true",
        "QUARKUS_SCHEDULER_ENABLED": "false",
        "OPENBANK_OUTBOX_DISPATCH_ENABLED": "false",
        "OPENBANK_OUTBOX_RETENTION_ENABLED": "false",
        "OPENBANK_TRANSACTION_WORKER_VERSIONING_ENABLED": str(not legacy).lower(),
    }
    if not legacy:
        values.update({
            "OPENBANK_TRANSACTION_WORKER_VERSIONING_DEPLOYMENT_NAME": "payment-execution",
            "OPENBANK_TRANSACTION_WORKER_VERSIONING_BUILD_ID": digest,
        })
    env = container.setdefault("env", [])
    prefix = "OPENBANK_TRANSACTION_WORKER_VERSIONING_"
    env[:] = [e for e in env if e["name"] not in values and not e["name"].startswith(prefix)]
    env.extend({"name": key, "value": value} for key, value in values.items())
    template["spec"]["terminationGracePeriodSeconds"] = max(
        60, template["spec"].get("terminationGracePeriodSeconds", 0),
    )
    selector = {APP_LABEL: name}
    if all(labels.get(key) == value for key, value in source["spec"]["selector"]["matchLabels"].items()):
        raise ValueError("retained worker must not match the API Rollout selector")
    deployment = {
        "apiVersion": "apps/v1", "kind": "Deployment",
        "metadata": {"name": name, "namespace": namespace, "labels": selector},
        "spec": {
            "replicas": 1, "selector": {"matchLabels": selector},
            "strategy": {"type": "Recreate"}, "template": template,
        },
    }
    # Workers poll outbound. Admit only observability on management; never expose the API listener.
    policy = {
        "apiVersion": "networking.k8s.io/v1", "kind": "NetworkPolicy",
        "metadata": {"name": name, "namespace": namespace},
        "spec": {
            "podSelector": {"matchLabels": selector}, "policyTypes": ["Ingress"],
            "ingress": [{
                "from": [{"namespaceSelector": {"matchLabels": {
                    "kubernetes.io/metadata.name": "observability",
                }}}],
                "ports": [{"protocol": "TCP", "port": 8085}],
            }],
        },
    }
    return [deployment, policy]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, help="environment's reviewed multi-document Rollout YAML")
    parser.add_argument("--image", required=True, help="immutable image reference including @sha256:digest")
    parser.add_argument("--legacy", action="store_true", help="retain the original SDK 1.25.1 unversioned image")
    args = parser.parse_args()
    with open(args.source, encoding="utf-8") as stream:
        matches = [d for d in yaml.safe_load_all(stream) if d and d.get("kind") == "Rollout"
                   and d.get("metadata", {}).get("name") == "transaction-service"]
    if len(matches) != 1:
        parser.error("source must contain exactly one transaction-service Rollout")
    try:
        resources = render(matches[0], args.image, args.legacy)
    except (KeyError, ValueError) as error:
        parser.error(str(error))
    yaml.safe_dump_all(resources, sys.stdout, sort_keys=False)


if __name__ == "__main__":
    main()
