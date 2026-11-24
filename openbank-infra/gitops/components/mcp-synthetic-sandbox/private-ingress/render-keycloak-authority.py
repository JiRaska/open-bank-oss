#!/usr/bin/env python3
"""Render scoped Keycloak outbound authority TLS and exact sandbox network policies."""

import argparse
from pathlib import Path

import yaml

from render import HTTPS, NAMESPACE, name, peer, port, resource, service_dns, write_resources

VOLUME = "mcp-authority-tls"
MOUNT = "/tls/mcp-authority"
PREFIX = "KC_SPI_AUTHENTICATOR__OPENBANK_MCP_WITHHELD_CODE__"
DELEGATION = ("delegation", "delegation-service")
SCA = ("sca", "sca-service")


def authority_patch(args):
    token_url = (
        f"https://{service_dns(args.staff_service, args.staff_namespace)}:{HTTPS}"
        f"/realms/{args.staff_realm}/protocol/openid-connect/token"
    )
    values = {
        PREFIX + "SCA_URL": f"https://{service_dns(SCA[1], SCA[0])}:{HTTPS}",
        PREFIX + "TOKEN_URL": token_url,
        PREFIX + "CONNECT_AUTHORITY_URL": f"https://{service_dns(DELEGATION[1], DELEGATION[0])}:{HTTPS}",
        "MCP_READ_ISSUER_TOKEN_URL": token_url,
        "MCP_READ_DELEGATION_ORIGIN": f"https://{service_dns(DELEGATION[1], DELEGATION[0])}:{HTTPS}",
        "MCP_AUTHORITY_TLS_KEYSTORE": MOUNT + "/identity.p12",
        "MCP_AUTHORITY_TLS_TRUSTSTORE": MOUNT + "/trust.p12",
        "MCP_AUTHORITY_TLS_PASSWORD_FILE": MOUNT + "/password",
    }
    return resource("apps/v1", "Deployment", args.deployment, {"template": {"spec": {
        "containers": [{
            "name": args.container,
            "env": [{"name": key, "value": value} for key, value in values.items()],
            "volumeMounts": [{"name": VOLUME, "mountPath": MOUNT, "readOnly": True}],
        }],
        "volumes": [{"name": VOLUME, "secret": {
            "secretName": args.tls_secret,
            "items": [{"key": key, "path": key} for key in ("identity.p12", "trust.p12", "password")],
        }}],
    }}})


def policy_objects(args):
    targets = (DELEGATION, SCA)
    yield resource("networking.k8s.io/v1", "NetworkPolicy", "mcp-keycloak-authority-egress", {
        **peer(args.deployment), "policyTypes": ["Egress"],
        "egress": [{
            "to": [peer(workload, namespace) for namespace, workload in sorted(targets)],
            "ports": [port(HTTPS)],
        }],
    })
    for namespace, workload in sorted(targets):
        yield resource("networking.k8s.io/v1", "NetworkPolicy", f"{workload}-from-mcp-keycloak", {
            **peer(workload), "policyTypes": ["Ingress"],
            "ingress": [{"from": [peer(args.deployment, NAMESPACE)], "ports": [port(HTTPS)]}],
        }, namespace=namespace)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--deployment", required=True, type=name, help="Dedicated sandbox Keycloak Deployment")
    parser.add_argument("--container", required=True, type=name, help="Keycloak container name")
    parser.add_argument("--staff-service", required=True, type=name, help="Approved canonical staff HTTPS Service")
    parser.add_argument("--staff-namespace", required=True, type=name, help="Staff HTTPS Service namespace")
    parser.add_argument("--staff-realm", required=True, type=name)
    parser.add_argument("--tls-secret", required=True, type=name, help="Outbound workload identity and trust stores")
    args = parser.parse_args()
    if args.staff_namespace == NAMESPACE or args.tls_secret in {"mcp-synthetic-root-ca", "read-issuer-client-trust"}:
        parser.error("use the existing staff issuer and a separate outbound workload identity Secret")

    args.output.mkdir(parents=True, exist_ok=False)
    write_resources(args.output, policy_objects(args))
    (args.output / "deployment.patch.yaml").write_text(yaml.safe_dump(authority_patch(args), sort_keys=False))
    (args.output / "kustomization.yaml").write_text(yaml.safe_dump({
        "apiVersion": "kustomize.config.k8s.io/v1alpha1", "kind": "Component",
        "resources": ["resources.yaml"], "patches": [{"path": "deployment.patch.yaml"}],
    }, sort_keys=False))


if __name__ == "__main__":
    main()
