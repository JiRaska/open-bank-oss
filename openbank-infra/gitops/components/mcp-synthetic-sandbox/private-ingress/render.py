#!/usr/bin/env python3
"""Render the private router as a standalone Kustomize component."""
import argparse
import re
import shutil
from pathlib import Path

import yaml

NAMESPACE = "mcp-synthetic-sandbox"
ISSUER = "mcp-synthetic-private-ca"
ROUTER = "mcp-synthetic-router"
FRONTEND = "sandbox"
OIDC_SERVICE = "sandbox-oidc"
OIDC_PORT = 8444
HTTPS = 8443
APP_LABEL = "app.kubernetes.io/name"
SERVICE_NAME = re.compile(r"[a-z][a-z0-9-]{0,62}(?<!-)")
IMAGE = re.compile(r"[a-z0-9.-]+(?::[0-9]+)?/(?:[a-z0-9._-]+/)*openbank-mcp-harness@sha256:[a-f0-9]{64}")
TRUST_LEAF = "mcp-synthetic-router-server"
CLIENT_LEAF = "mcp-synthetic-router-client"


def name(value):
    if not SERVICE_NAME.fullmatch(value):
        raise argparse.ArgumentTypeError("expected a DNS Service or realm name")
    return value


def workload_ref(value):
    namespace, separator, workload = value.partition("/")
    return (name(namespace), name(workload)) if separator else (NAMESPACE, name(namespace))


def service_dns(service, namespace=NAMESPACE):
    return ".".join((service, namespace, "svc", "cluster", "local"))


def resource(api, kind, object_name, spec, *, namespace=NAMESPACE, **metadata):
    return {"apiVersion": api, "kind": kind, "metadata": {"name": object_name, "namespace": namespace, **metadata}, "spec": spec}


def peer(workload, namespace=None):
    selector = {"podSelector": {"matchLabels": {APP_LABEL: workload}}}
    if namespace is not None:
        selector["namespaceSelector"] = {"matchLabels": {"kubernetes.io/metadata.name": namespace}}
    return selector


def port(number, protocol="TCP"):
    return {"protocol": protocol, "port": number}


def certificate(object_name, usage):
    return resource("cert-manager.io/v1", "Certificate", object_name, {
        "secretName": object_name,
        "dnsNames": [service_dns(FRONTEND)],
        "duration": "2160h",
        "renewBefore": "360h",
        "privateKey": {"algorithm": "ECDSA", "size": 256, "rotationPolicy": "Always"},
        "usages": [usage],
        "issuerRef": {"name": ISSUER, "kind": "Issuer", "group": "cert-manager.io"},
    }, annotations={"argocd.argoproj.io/sync-wave": "2"})


def policy(object_name, workload, ingress, egress=None, *, namespace=NAMESPACE):
    spec = {**peer(workload), "policyTypes": ["Ingress"], "ingress": ingress}
    if egress is not None:
        spec["policyTypes"].append("Egress")
        spec["egress"] = egress
    return resource("networking.k8s.io/v1", "NetworkPolicy", object_name, spec, namespace=namespace)


def write_resources(output, rendered):
    local, external = [], []
    for item in rendered:
        (local if item["metadata"]["namespace"] == NAMESPACE else external).append(item)
    (output / "resources.yaml").write_text(yaml.safe_dump_all(local, sort_keys=False))
    if external:
        owner = output / "external-policies"
        owner.mkdir()
        (owner / "resources.yaml").write_text(yaml.safe_dump_all(external, sort_keys=False))
        (owner / "kustomization.yaml").write_text(yaml.safe_dump({
            "apiVersion": "kustomize.config.k8s.io/v1beta1",
            "kind": "Kustomization", "resources": ["resources.yaml"],
        }, sort_keys=False))


def objects(args):
    labels = {APP_LABEL: ROUTER}
    upstreams = {
        "MCP_ROUTER_MCP_ORIGIN": service_dns(args.mcp_service, args.mcp_namespace),
        "MCP_ROUTER_ISSUER_ORIGIN": service_dns(OIDC_SERVICE),
        "MCP_ROUTER_CUSTOMER_EDGE_ORIGIN": service_dns(args.customer_edge_service, args.customer_edge_namespace),
    }
    env = {"MCP_ROUTER_HOST": service_dns(FRONTEND), "MCP_ROUTER_CUSTOMER_REALM": args.customer_realm}
    env.update({key: f"https://{host}:{HTTPS}" for key, host in upstreams.items()})
    mounts = [("server", "/tls/server"), ("mcp-client", "/tls/mcp-client"), ("router", "/app")]
    volumes = [
        {"name": "server", "secret": {"secretName": TRUST_LEAF}},
        {"name": "mcp-client", "secret": {"secretName": CLIENT_LEAF}},
        {"name": "router", "configMap": {"name": "mcp-synthetic-router-code"}},
    ]
    for role, secret in (
        ("mcp", args.mcp_ca_secret),
        ("edge", args.edge_ca_secret),
        ("oidc", args.oidc_ca_secret),
    ):
        if secret is None:
            continue
        volume = f"{role}-server-ca"
        mount = f"/tls/{volume}"
        mounts.append((volume, mount))
        volumes.append({"name": volume, "secret": {"secretName": secret, "items": [
            {"key": "ca.crt", "path": "ca.crt"},
        ]}})
        env[f"MCP_ROUTER_{role.upper()}_CA_FILE"] = f"{mount}/ca.crt"

    yield certificate(TRUST_LEAF, "server auth")
    yield certificate(CLIENT_LEAF, "client auth")
    yield resource("v1", "Service", FRONTEND, {
        "type": "ClusterIP", "selector": labels, "ports": [{"name": "https", "port": 443, "targetPort": HTTPS}],
    })
    yield resource("apps/v1", "Deployment", ROUTER, {
        "replicas": 1,
        "selector": {"matchLabels": labels},
        "template": {"metadata": {"labels": labels}, "spec": {
            "automountServiceAccountToken": False,
            "securityContext": {
                "runAsNonRoot": True, "runAsUser": 1001, "runAsGroup": 1001,
                "seccompProfile": {"type": "RuntimeDefault"},
            },
            "containers": [{
                "name": "router", "image": args.image, "command": ["node", "/app/router.mjs"],
                "env": [{"name": k, "value": v} for k, v in env.items()],
                "ports": [{"name": "https", "containerPort": HTTPS}],
                "readinessProbe": {"tcpSocket": {"port": "https"}},
                "securityContext": {"allowPrivilegeEscalation": False, "readOnlyRootFilesystem": True,
                                    "capabilities": {"drop": ["ALL"]}},
                "resources": {"requests": {"cpu": "50m", "memory": "64Mi"},
                              "limits": {"cpu": "250m", "memory": "128Mi"}},
                "volumeMounts": [{"name": n, "mountPath": p, "readOnly": True} for n, p in mounts],
            }],
            "volumes": volumes,
        }},
    }, annotations={"argocd.argoproj.io/sync-wave": "3"})

    dns = {"namespaceSelector": {"matchLabels": {"kubernetes.io/metadata.name": "kube-system"}},
           "podSelector": {"matchLabels": {"k8s-app": "kube-dns"}}}
    yield policy(ROUTER, ROUTER, [{"from": [peer("mcp-synthetic-runner")], "ports": [port(HTTPS)]}], [
        {"to": [peer(workload, namespace if namespace != NAMESPACE else None)
                for namespace, workload in args.upstream_workloads], "ports": [port(HTTPS)]},
        {"to": [peer(args.issuer_workload)], "ports": [port(OIDC_PORT)]},
        {"to": [dns], "ports": [port(53, "UDP"), port(53)]},
    ])
    for namespace, workload in args.upstream_workloads:
        router = peer(ROUTER, NAMESPACE if namespace != NAMESPACE else None)
        yield policy(f"{workload}-from-private-router", workload,
                     [{"from": [router], "ports": [port(HTTPS)]}], namespace=namespace)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True, help="Signed runner image reference with SHA-256 digest")
    parser.add_argument("--mcp-service", type=name, required=True)
    parser.add_argument("--mcp-namespace", type=name, default=NAMESPACE)
    parser.add_argument("--mcp-workload", type=name)
    parser.add_argument("--issuer-workload", type=name, required=True)
    parser.add_argument("--customer-edge-service", type=name, required=True)
    parser.add_argument("--customer-edge-namespace", type=name, default=NAMESPACE)
    parser.add_argument("--customer-edge-workload", type=name)
    parser.add_argument("--customer-realm", type=name, required=True)
    parser.add_argument("--mcp-ca-secret", type=name, help="Public server CA for the MCP upstream")
    parser.add_argument("--edge-ca-secret", type=name, help="Public server CA for the Customer Edge upstream")
    parser.add_argument("--oidc-ca-secret", type=name, help="Public server CA for the OAuth upstream")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    if not IMAGE.fullmatch(args.image):
        parser.error("use the registry-qualified openbank-mcp-harness image with its SHA-256 digest")
    supplied_cas = {args.mcp_ca_secret, args.edge_ca_secret, args.oidc_ca_secret}
    if supplied_cas & {TRUST_LEAF, CLIENT_LEAF, "mcp-synthetic-root-ca"}:
        parser.error("use a separate public-only trust Secret for upstream CA configuration")

    args.upstream_workloads = [
        (args.mcp_namespace, args.mcp_workload or args.mcp_service),
        (args.customer_edge_namespace, args.customer_edge_workload or args.customer_edge_service),
    ]
    reserved = {(NAMESPACE, name) for name in (ROUTER, args.issuer_workload, FRONTEND, OIDC_SERVICE)}
    upstream_services = {(args.mcp_namespace, args.mcp_service), (args.customer_edge_namespace, args.customer_edge_service)}
    if len(set(args.upstream_workloads)) != 2 or len(upstream_services) != 2 or (
        reserved & (set(args.upstream_workloads) | upstream_services)
    ):
        parser.error("the private router, OIDC Service and upstream workloads must be distinct")

    args.output.mkdir()
    write_resources(args.output, objects(args))
    shutil.copyfile(Path(__file__).with_name("router.mjs"), args.output / "router.mjs")
    kustomization = {
        "apiVersion": "kustomize.config.k8s.io/v1beta1", "kind": "Kustomization",
        "resources": ["resources.yaml"],
        "configMapGenerator": [{"name": "mcp-synthetic-router-code", "files": ["router.mjs"]}],
        "namespace": NAMESPACE,
    }
    (args.output / "kustomization.yaml").write_text(yaml.safe_dump(kustomization, sort_keys=False))


if __name__ == "__main__":
    main()
