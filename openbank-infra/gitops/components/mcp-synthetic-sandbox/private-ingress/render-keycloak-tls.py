#!/usr/bin/env python3
"""Render the split Keycloak transport as a private sandbox Kustomize Component."""

import argparse
import shutil
from pathlib import Path

import yaml

from render import (
    APP_LABEL, FRONTEND, HTTPS, IMAGE, ISSUER, NAMESPACE, OIDC_PORT, OIDC_SERVICE, ROUTER,
    name, peer, policy, port, resource, service_dns, workload_ref, write_resources,
)

READ_SERVICE = "sandbox-read-issuer"
FRONT_CERT = "mcp-synthetic-keycloak-frontchannel"
READ_CERT = "mcp-synthetic-keycloak-native"
FRONT_CODE = "mcp-synthetic-keycloak-frontchannel-code"
TRUST = "read-issuer-client-trust"


def server_certificate(secret, service):
    return resource("cert-manager.io/v1", "Certificate", secret, {
        "secretName": secret,
        "dnsNames": [service_dns(service)],
        "duration": "2160h", "renewBefore": "360h",
        "privateKey": {"algorithm": "ECDSA", "size": 256, "rotationPolicy": "Always"},
        "usages": ["server auth"],
        "issuerRef": {"name": ISSUER, "kind": "Issuer", "group": "cert-manager.io"},
    }, annotations={"argocd.argoproj.io/sync-wave": "2"})


def environment(values):
    return [{"name": key, "value": str(value)} for key, value in values.items()]


def objects(args):
    for service, certificate, port_name in (
        (OIDC_SERVICE, FRONT_CERT, "sandbox-oauth"),
        (READ_SERVICE, READ_CERT, "sandbox-read"),
    ):
        yield server_certificate(certificate, service)
        yield resource("v1", "Service", service, {
            "type": "ClusterIP",
            "selector": {APP_LABEL: args.deployment},
            "ports": [{"name": "https", "port": HTTPS, "targetPort": port_name}],
        })
    discovery_peers = [peer(workload, namespace if namespace != NAMESPACE else None)
                       for namespace, workload in sorted(set(args.backchannel_peer))]
    native_peer = peer(args.mcp_service, args.mcp_namespace if args.mcp_namespace != NAMESPACE else None)
    yield policy("mcp-synthetic-keycloak-private-input", args.deployment, [
        {"from": [peer(ROUTER), *discovery_peers], "ports": [port(OIDC_PORT)]},
        {"from": [native_peer], "ports": [port(HTTPS)]},
    ])
    resolver = {
        "namespaceSelector": {"matchLabels": {"kubernetes.io/metadata.name": args.dns_namespace}},
        "podSelector": {"matchLabels": {"k8s-app": args.dns_workload}},
    }
    yield resource("networking.k8s.io/v1", "NetworkPolicy", "mcp-synthetic-keycloak-dns", {
        **peer(args.deployment), "policyTypes": ["Egress"],
        "egress": [{"to": [resolver], "ports": [port(53, "UDP"), port(53)]}],
    })
    for namespace, client in sorted({args.native_workload, *args.backchannel_peer}):
        egress = [port(OIDC_PORT)] if (namespace, client) in args.backchannel_peer else []
        if (namespace, client) == args.native_workload:
            egress.append(port(HTTPS))
        keycloak = peer(args.deployment, NAMESPACE if namespace != NAMESPACE else None)
        yield resource("networking.k8s.io/v1", "NetworkPolicy", f"{client}-to-keycloak-private", {
            **peer(client), "policyTypes": ["Egress"],
            "egress": [{"to": [keycloak], "ports": egress}],
        }, namespace=namespace)


def patch(args):
    canonical_host = service_dns(FRONTEND)
    native_env = environment({
        "KC_HOSTNAME": f"https://{canonical_host}",
        "KC_HOSTNAME_BACKCHANNEL_DYNAMIC": "true",
        "KC_PROXY_HEADERS": "xforwarded",
        "KC_PROXY_TRUSTED_ADDRESSES": "127.0.0.1",
        "KC_HTTP_ENABLED": "true",
        "KC_HTTP_PORT": 8080,
        "KC_HTTP_MANAGEMENT_SCHEME": "http",
        "KC_HTTPS_PORT": HTTPS,
        "KC_HTTPS_CERTIFICATE_FILE": "/tls/native/tls.crt",
        "KC_HTTPS_CERTIFICATE_KEY_FILE": "/tls/native/tls.key",
        "KC_HTTPS_PROTOCOLS": "TLSv1.3",
        "KC_HTTPS_CLIENT_AUTH": "required",
        "KC_HTTPS_TRUST_STORE_FILE": "/tls/read-issuer-client-trust/truststore.p12",
        "KC_HTTPS_TRUST_STORE_TYPE": "PKCS12",
    })
    native_env.append({"name": "KC_HTTPS_TRUST_STORE_PASSWORD", "valueFrom": {
        "secretKeyRef": {"name": args.trust_secret, "key": "password"},
    }})
    return resource("apps/v1", "Deployment", args.deployment, {"template": {"spec": {
        "containers": [
            {
                "name": args.container, "env": native_env,
                "ports": [{"name": "sandbox-read", "containerPort": HTTPS}],
                "volumeMounts": [
                    {"name": READ_CERT, "mountPath": "/tls/native", "readOnly": True},
                    {"name": TRUST, "mountPath": "/tls/read-issuer-client-trust", "readOnly": True},
                ],
            },
            {
                "name": "sandbox-oauth-frontchannel", "image": args.relay_image,
                "command": ["node", "/app/keycloak-frontchannel.mjs"],
                "env": environment({
                    "FRONTCHANNEL_HOST": canonical_host, "FRONTCHANNEL_SERVICE": service_dns(OIDC_SERVICE),
                    "FRONTCHANNEL_CUSTOMER_REALM": args.customer_realm,
                    "FRONTCHANNEL_STAFF_REALM": args.staff_realm or "",
                }),
                "ports": [{"name": "sandbox-oauth", "containerPort": OIDC_PORT}],
                "readinessProbe": {"tcpSocket": {"port": "sandbox-oauth"}},
                "securityContext": {
                    "runAsNonRoot": True, "runAsUser": 1001, "runAsGroup": 1001,
                    "allowPrivilegeEscalation": False, "readOnlyRootFilesystem": True,
                    "capabilities": {"drop": ["ALL"]}, "seccompProfile": {"type": "RuntimeDefault"},
                },
                "resources": {
                    "requests": {"cpu": "25m", "memory": "32Mi"},
                    "limits": {"cpu": "125m", "memory": "96Mi"},
                },
                "volumeMounts": [
                    {"name": FRONT_CERT, "mountPath": "/tls/frontchannel", "readOnly": True},
                    {"name": FRONT_CODE, "mountPath": "/app", "readOnly": True},
                ],
            },
        ],
        "volumes": [
            {"name": FRONT_CERT, "secret": {"secretName": FRONT_CERT}},
            {"name": READ_CERT, "secret": {"secretName": READ_CERT}},
            {"name": TRUST, "secret": {"secretName": args.trust_secret, "items": [
                {"key": "truststore.p12", "path": "truststore.p12"},
            ]}},
            {"name": FRONT_CODE, "configMap": {"name": FRONT_CODE}},
        ],
    }}})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--relay-image", required=True, help="Signed runner image reference with SHA-256 digest")
    parser.add_argument("--deployment", type=name, required=True, help="Sandbox Keycloak Deployment name and pod app label")
    parser.add_argument("--container", type=name, default="keycloak")
    parser.add_argument("--mcp-service", type=name, required=True, help="Sandbox MCP pod app label")
    parser.add_argument("--mcp-namespace", type=name, default=NAMESPACE)
    parser.add_argument("--backchannel-peer", type=workload_ref, action="append", default=[])
    parser.add_argument("--customer-realm", type=name, required=True)
    parser.add_argument("--staff-realm", type=name, help="Optional staff realm on this isolated server")
    parser.add_argument("--dns-namespace", type=name, default="kube-system")
    parser.add_argument("--dns-workload", type=name, default="kube-dns",
                        help="Exact k8s-app label of the cluster resolver pods")
    parser.add_argument("--trust-secret", type=name, required=True, help="Secret holding the MCP-only issuing CA truststore")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    if not IMAGE.fullmatch(args.relay_image):
        parser.error("use the registry-qualified openbank-mcp-harness image with its SHA-256 digest")
    args.native_workload = (args.mcp_namespace, args.mcp_service)
    reserved = {(NAMESPACE, item) for item in (ROUTER, FRONTEND, OIDC_SERVICE, READ_SERVICE, args.deployment)}
    if reserved & {args.native_workload, *args.backchannel_peer}:
        parser.error("use exact upstream workload labels, separate from Keycloak and the ingress Services")
    if args.customer_realm == args.staff_realm or "master" in {args.customer_realm, args.staff_realm}:
        parser.error("the customer and staff realms must be separate and exclude the admin realm")
    if args.trust_secret in {FRONT_CERT, READ_CERT, "mcp-synthetic-root-ca"}:
        parser.error("the native truststore must hold only the constrained MCP client CA")

    args.output.mkdir()
    write_resources(args.output, objects(args))
    (args.output / "deployment-patch.yaml").write_text(yaml.safe_dump(patch(args), sort_keys=False))
    shutil.copyfile(Path(__file__).with_name("keycloak-frontchannel.mjs"), args.output / "keycloak-frontchannel.mjs")
    component = {
        "apiVersion": "kustomize.config.k8s.io/v1alpha1", "kind": "Component",
        "resources": ["resources.yaml"], "patches": [{"path": "deployment-patch.yaml"}],
        "configMapGenerator": [{"name": FRONT_CODE, "files": ["keycloak-frontchannel.mjs"]}],
    }
    (args.output / "kustomization.yaml").write_text(yaml.safe_dump(component, sort_keys=False))


if __name__ == "__main__":
    main()
