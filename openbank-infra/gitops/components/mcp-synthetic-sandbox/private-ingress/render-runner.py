#!/usr/bin/env python3
"""Render the suspended synthetic MCP runner and its persistent passkey state."""

import argparse
from pathlib import Path

import yaml

from render import APP_LABEL, FRONTEND, HTTPS, IMAGE, NAMESPACE, ROUTER, TRUST_LEAF, name, peer, port, resource, service_dns

RUNNER = "mcp-synthetic-runner"
STATE = "mcp-synthetic-passkey-counter"
STAGE_CREDENTIALS = """
umask 077
cp /fixture/passkey.json /tmp/passkey.json
cp /fixture/device.pem /tmp/device.pem
exec sh ./run-sandbox-journey.sh "$@"
""".strip()


def objects(args):
    host = service_dns(FRONTEND)
    origin = f"https://{host}"
    values = {
        "MCP_HARNESS_SYNTHETIC_SANDBOX": "true",
        "MCP_HARNESS_SOFTWARE_CONNECT_APPROVAL": "true",
        "MCP_HARNESS_APP_SIGN_IN": "true",
        "MCP_HARNESS_ALLOWED_SUFFIXES": host.split(".", 1)[1],
        "MCP_HARNESS_RESOURCE_URL": f"{origin}/customer/mcp",
        "MCP_HARNESS_AUTH_SERVER": f"{origin}/realms/{args.customer_realm}",
        "MCP_HARNESS_CUSTOMER_EDGE_URL": f"{origin}/customer/v1",
        "MCP_HARNESS_CLIENT_ID": args.client_id,
        "MCP_HARNESS_SCOPE": "openid",
        "MCP_HARNESS_READ_TOOL": "get_balance",
        "MCP_HARNESS_PASSKEY_FILE": "/tmp/passkey.json",
        "MCP_HARNESS_CONNECT_PRIVATE_KEY_FILE": "/tmp/device.pem",
        "MCP_HARNESS_PRIVATE_CA_FILE": "/ca/ca.crt",
        "MCP_HARNESS_APP_COUNTER_FILE": "/state/counter.json",
    }
    selector = {APP_LABEL: RUNNER}
    env = [{"name": key, "value": value} for key, value in values.items()]
    fixture_keys = (
        ("MCP_HARNESS_PARTY_ID", "party-id"),
        ("MCP_HARNESS_CONNECT_CREDENTIAL_ID", "device-id"),
        ("MCP_HARNESS_READ_TOOL_ARGS", "read-tool-args.json"),
        ("MCP_HARNESS_VERIFY_BALANCE_CURRENCY", "currency"),
    )
    journey = ["--read-only", "--verify-revocation"]
    if args.mode == "verify-proposal":
        fixture_keys += (("MCP_HARNESS_PROPOSE_ARGS", "propose-args.json"),)
        journey = ["--verify-proposal"]
    for variable, key in fixture_keys:
        env.append({"name": variable, "valueFrom": {"secretKeyRef": {"name": args.fixture_secret, "key": key}}})

    yield resource("v1", "PersistentVolumeClaim", STATE, {
        "accessModes": ["ReadWriteOncePod"],
        "storageClassName": args.storage_class,
        "resources": {"requests": {"storage": "1Gi"}},
    }, annotations={
        "argocd.argoproj.io/sync-options": "Prune=confirm,Delete=confirm",
        "argocd.argoproj.io/sync-wave": "2",
    })

    mounts = [
        {"name": "fixture", "mountPath": "/fixture", "readOnly": True},
        {"name": "ca", "mountPath": "/ca", "readOnly": True},
        {"name": "state", "mountPath": "/state"},
        {"name": "home", "mountPath": "/home/pwuser"},
        {"name": "tmp", "mountPath": "/tmp"},
    ]
    volumes = [
        {"name": "fixture", "secret": {"secretName": args.fixture_secret, "defaultMode": 0o440, "items": [
            {"key": "passkey.json", "path": "passkey.json"},
            {"key": "device.pem", "path": "device.pem"},
        ]}},
        {"name": "ca", "secret": {"secretName": TRUST_LEAF, "items": [{"key": "ca.crt", "path": "ca.crt"}]}},
        {"name": "state", "persistentVolumeClaim": {"claimName": STATE}},
        {"name": "home", "emptyDir": {"sizeLimit": "256Mi"}},
        {"name": "tmp", "emptyDir": {"sizeLimit": "256Mi"}},
    ]
    pod = {
        "serviceAccountName": RUNNER,
        "automountServiceAccountToken": False,
        "enableServiceLinks": False,
        "restartPolicy": "Never",
        "securityContext": {
            "runAsNonRoot": True, "runAsUser": 1001, "runAsGroup": 1001, "fsGroup": 1001,
            "seccompProfile": {"type": "RuntimeDefault"},
        },
        "containers": [{
            "name": "journey", "image": args.image,
            "command": ["sh", "-euc", STAGE_CREDENTIALS, "sandbox-journey"], "args": journey,
            "env": env, "volumeMounts": mounts,
            "securityContext": {
                "allowPrivilegeEscalation": False, "readOnlyRootFilesystem": True,
                "capabilities": {"drop": ["ALL"]},
            },
            "resources": {
                "requests": {"cpu": "250m", "memory": "512Mi", "ephemeral-storage": "256Mi"},
                "limits": {"cpu": "1", "memory": "1Gi", "ephemeral-storage": "1Gi"},
            },
        }],
        "volumes": volumes,
    }
    yield resource("batch/v1", "CronJob", RUNNER, {
        "schedule": "17 3 * * *", "timeZone": "Etc/UTC", "suspend": True,
        "concurrencyPolicy": "Forbid", "startingDeadlineSeconds": 300,
        "successfulJobsHistoryLimit": 2, "failedJobsHistoryLimit": 2,
        "jobTemplate": {"spec": {
            "backoffLimit": 0, "activeDeadlineSeconds": 600,
            "template": {"metadata": {"labels": selector}, "spec": pod},
        }},
    }, annotations={"argocd.argoproj.io/sync-wave": "4"})

    dns = {"namespaceSelector": {"matchLabels": {"kubernetes.io/metadata.name": "kube-system"}},
           "podSelector": {"matchLabels": {"k8s-app": "kube-dns"}}}
    yield resource("networking.k8s.io/v1", "NetworkPolicy", RUNNER, {
        **peer(RUNNER), "policyTypes": ["Ingress", "Egress"], "ingress": [],
        "egress": [
            {"to": [peer(ROUTER)], "ports": [port(HTTPS)]},
            {"to": [dns], "ports": [port(53, "UDP"), port(53)]},
        ],
    })


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True, help="Signed runner image reference with SHA-256 digest")
    parser.add_argument("--customer-realm", type=name, required=True)
    parser.add_argument("--client-id", type=name, required=True)
    parser.add_argument("--fixture-secret", type=name, required=True)
    parser.add_argument("--mode", choices=("read-revoke", "verify-proposal"), default="read-revoke")
    parser.add_argument("--storage-class", type=name, required=True, help="Existing CSI class supporting ReadWriteOncePod")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    if not IMAGE.fullmatch(args.image):
        parser.error("use the registry-qualified openbank-mcp-harness image with its SHA-256 digest")
    if args.fixture_secret in {TRUST_LEAF, "mcp-synthetic-router-client", "mcp-synthetic-root-ca"}:
        parser.error("runner fixture Secret must be separate from TLS and CA Secrets")

    args.output.mkdir()
    (args.output / "resources.yaml").write_text(yaml.safe_dump_all(objects(args), sort_keys=False))
    kustomization = {
        "apiVersion": "kustomize.config.k8s.io/v1beta1", "kind": "Kustomization",
        "resources": ["resources.yaml"], "namespace": NAMESPACE,
    }
    (args.output / "kustomization.yaml").write_text(yaml.safe_dump(kustomization, sort_keys=False))


if __name__ == "__main__":
    main()
