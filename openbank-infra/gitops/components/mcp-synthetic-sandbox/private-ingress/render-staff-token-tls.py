#!/usr/bin/env python3
"""Render the sandbox IAM staff token sidecar and the caller's egress policy."""

import argparse
import shutil
from pathlib import Path

import yaml

from render import APP_LABEL, HTTPS, IMAGE, NAMESPACE, name, peer, port, resource, service_dns

IAM = "iam"
KEYCLOAK = "keycloak"
SERVICE = "mcp-sandbox-staff-token"
SERVER_PORT = 8445
PORT_NAME = "mcp-staff-token"
CODE = "mcp-sandbox-staff-token-code"
CERT = "mcp-sandbox-staff-token-server-tls"


def issuer_host(value):
    labels = value.split(".")
    if len(labels) < 2:
        raise argparse.ArgumentTypeError("expected the canonical staff issuer DNS hostname")
    for label in labels:
        name(label)
    return value


def write_yaml(path, value):
    path.write_text(yaml.safe_dump(value, sort_keys=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--relay-image", required=True, help="Signed runner image reference with SHA-256 digest")
    parser.add_argument("--staff-issuer-host", type=issuer_host, required=True)
    parser.add_argument("--staff-realm", type=name, required=True)
    parser.add_argument("--caller-workload", type=name, required=True, help="Exact app label in the synthetic sandbox")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    if not IMAGE.fullmatch(args.relay_image):
        parser.error("use the registry-qualified openbank-mcp-harness image with its SHA-256 digest")
    if args.staff_realm in {"master", "openbank-customers"}:
        parser.error("expected a dedicated staff realm")

    private_host = f"{service_dns(SERVICE, IAM)}:{HTTPS}"
    sidecar = {
        "name": SERVICE, "image": args.relay_image,
        "command": ["node", "/app/staff-token-frontchannel.mjs"],
        "env": [{"name": k, "value": v} for k, v in {
            "STAFF_TOKEN_ISSUER_HOST": args.staff_issuer_host,
            "STAFF_TOKEN_SERVICE_HOST": private_host,
            "STAFF_TOKEN_REALM": args.staff_realm,
        }.items()],
        "ports": [{"name": PORT_NAME, "containerPort": SERVER_PORT}],
        "readinessProbe": {"tcpSocket": {"port": PORT_NAME}},
        "securityContext": {
            "runAsNonRoot": True, "runAsUser": 1001, "runAsGroup": 1001,
            "allowPrivilegeEscalation": False, "readOnlyRootFilesystem": True,
            "capabilities": {"drop": ["ALL"]}, "seccompProfile": {"type": "RuntimeDefault"},
        },
        "resources": {"requests": {"cpu": "25m", "memory": "32Mi"}, "limits": {"cpu": "125m", "memory": "96Mi"}},
        "volumeMounts": [
            {"name": CERT, "mountPath": "/tls/staff-token", "readOnly": True},
            {"name": CODE, "mountPath": "/app", "readOnly": True},
        ],
    }
    patch = resource("apps/v1", "Deployment", KEYCLOAK, {"template": {"spec": {
        "containers": [sidecar],
        "volumes": [
            {"name": CERT, "secret": {"secretName": CERT, "items": [
                {"key": "tls.crt", "path": "tls.crt"}, {"key": "tls.key", "path": "tls.key"},
            ]}},
            {"name": CODE, "configMap": {"name": CODE}},
        ],
    }}}, namespace=IAM)
    service = resource("v1", "Service", SERVICE, {
        "type": "ClusterIP", "selector": {APP_LABEL: KEYCLOAK},
        "ports": [{"name": "https", "port": HTTPS, "targetPort": PORT_NAME}],
    }, namespace=IAM)
    ingress = resource("networking.k8s.io/v1", "NetworkPolicy", SERVICE, {
        **peer(KEYCLOAK), "policyTypes": ["Ingress"],
        "ingress": [{"from": [peer(args.caller_workload, NAMESPACE)], "ports": [port(SERVER_PORT)]}],
    }, namespace=IAM)
    egress = resource("networking.k8s.io/v1", "NetworkPolicy", f"{SERVICE}-egress", {
        **peer(args.caller_workload), "policyTypes": ["Egress"],
        "egress": [{"to": [peer(KEYCLOAK, IAM)], "ports": [port(SERVER_PORT)]}],
    })
    # The base policy's same-namespace rule has no port filter. Keep its known HTTP and management
    # ports before adding a listener intended exclusively for the sandbox caller.
    legacy_ingress = [
        {"op": "test", "path": "/spec/ingress/0", "value": {"from": [{"podSelector": {}}]}},
        {"op": "add", "path": "/spec/ingress/0/ports", "value": [port(8080), port(9000)]},
    ]

    args.output.mkdir()
    shutil.copyfile(Path(__file__).with_name("staff-token-frontchannel.mjs"), args.output / "staff-token-frontchannel.mjs")
    write_yaml(args.output / "deployment-patch.yaml", patch)
    write_yaml(args.output / "existing-ingress-patch.yaml", legacy_ingress)
    (args.output / "resources.yaml").write_text(yaml.safe_dump_all([service, ingress], sort_keys=False))
    write_yaml(args.output / "kustomization.yaml", {
        "apiVersion": "kustomize.config.k8s.io/v1alpha1", "kind": "Component",
        "resources": ["resources.yaml"],
        "patches": [
            {"path": "deployment-patch.yaml"},
            {"path": "existing-ingress-patch.yaml", "target": {
                "group": "networking.k8s.io", "version": "v1", "kind": "NetworkPolicy",
                "name": "keycloak-ingress-allow-list", "namespace": IAM,
            }},
        ],
        "configMapGenerator": [{"name": CODE, "namespace": IAM, "files": ["staff-token-frontchannel.mjs"]}],
    })
    caller = args.output / "caller-egress"
    caller.mkdir()
    write_yaml(caller / "network-policy.yaml", egress)
    write_yaml(caller / "kustomization.yaml", {
        "apiVersion": "kustomize.config.k8s.io/v1beta1", "kind": "Kustomization",
        "resources": ["network-policy.yaml"],
    })


if __name__ == "__main__":
    main()
