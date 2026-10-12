#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Keep an application HTTP listener until its deployment uses separate management probes.

In Quarkus, `quarkus.http.insecure-requests` defaults to `disabled` whenever
`quarkus.http.ssl.client-auth` is `required`, and `disabled` removes the HTTP listener
altogether. #10441 baked `client-auth: required` into seven services' `%prod` profile and
the first image built from it (ledger) listened on 8443 only: its `tcp-socket :http`
readiness probe got `connection refused` and the rollout stalled. Every plaintext
in-cluster caller would have failed the same way.

Rule: in every profile where client-auth resolves to `required`, insecure-requests must
resolve to `enabled` (explicitly). The sole exception is pension-service's production
profile after its deployment, Service and probes have moved to HTTPS-only application
traffic with a separate management listener. A profile inherits top-level values it
does not override.

Usage: check-mtls-keeps-http-port.py [--enforce] [--self-test] [ROOT]
"""
from __future__ import annotations

import glob
import os
import sys
import tempfile
from copy import deepcopy
from pathlib import Path

import yaml


def _get(node, dotted):
    """Resolve a dotted key through nested maps, accepting dotted keys at any level."""
    if not isinstance(node, dict):
        return None
    if dotted in node:
        return node[dotted]
    parts = dotted.split(".")
    for i in range(len(parts) - 1, 0, -1):
        head = ".".join(parts[:i])
        if head in node:
            found = _get(node[head], ".".join(parts[i:]))
            if found is not None:
                return found
    return None


CLIENT_AUTH = "quarkus.http.ssl.client-auth"
INSECURE = "quarkus.http.insecure-requests"


def findings_for(doc, *, pension_management_ready: bool = False) -> list[str]:
    if not isinstance(doc, dict):
        return []
    # Inspect every declaration. A combined profile must not overwrite a separate
    # %prod block and hide its TLS setting (or its management override).
    profiles = [(None, doc)]
    for k, v in doc.items():
        if isinstance(k, str) and k.startswith("%") and isinstance(v, dict):
            for name in k.split(","):
                profiles.append((name.strip(), v))
    base_ca, base_ins = _get(doc, CLIENT_AUTH), _get(doc, INSECURE)
    out = []
    for name, node in profiles:
        ca = _get(node, CLIENT_AUTH) if name else base_ca
        ins = _get(node, INSECURE) if name else base_ins
        if name and ca is None:
            ca = base_ca
        if name and ins is None:
            ins = base_ins
        if str(ca).strip().lower() == "required" and str(ins).strip().lower() != "enabled":
            if name == "%prod" and str(ins).strip().lower() == "disabled" and pension_management_ready:
                continue
            out.append(
                f"profile {name or '(default)'}: client-auth=required but insecure-requests="
                f"{ins!r} — Quarkus then drops the plain-HTTP port; set insecure-requests: enabled"
            )
    return out


def pension_management_ready(root: str, doc: dict) -> bool:
    """Require the complete, explicit HTTPS-only deployment shape before the exception.

    Config and Deployment env have different precedence. Check both, plus Service and
    the real probe ports, so a green source config cannot hide a dead production pod.
    """
    try:
        deployment_path = Path(root) / "openbank-infra/gitops/components/pension/pension-service.yaml"
        resources = list(yaml.safe_load_all(deployment_path.read_text(encoding="utf-8")))
        deployment = next(r for r in resources if r.get("kind") == "Deployment" and r.get("metadata", {}).get("name") == "pension-service")
        service = next(r for r in resources if r.get("kind") == "Service" and r.get("metadata", {}).get("name") == "pension-service")
        if deployment["metadata"].get("namespace") != "pension" or service["metadata"].get("namespace") != "pension":
            return False
        pod_labels = deployment["spec"]["template"]["metadata"]["labels"]
        deployment_selector = deployment["spec"]["selector"]
        deployment_labels = deployment_selector["matchLabels"]
        if deployment_selector.get("matchExpressions") or not deployment_labels:
            return False
        if any(pod_labels.get(key) != value for key, value in deployment_labels.items()):
            return False
        service_selector = service["spec"]["selector"]
        if not service_selector or service_selector.get("app.kubernetes.io/name") != "pension-service":
            return False
        if any(pod_labels.get(key) != value for key, value in service_selector.items()):
            return False
        container = next(c for c in deployment["spec"]["template"]["spec"]["containers"] if c.get("name") == "pension-service")
        env = {e["name"]: e.get("value") for e in container["env"]}
        if len(env) != len(container["env"]):
            return False
        management = _get(doc, "quarkus.management")
        management_port = int(management["port"])
        application_port = int(_get(doc, "quarkus.http.port"))
        prod_profiles = [
            value for name, value in doc.items()
            if isinstance(name, str) and "%prod" in [part.strip() for part in name.split(",")]
        ]
        if not prod_profiles or any(not isinstance(profile, dict) for profile in prod_profiles):
            return False
        for key, expected in (
            ("quarkus.management.enabled", True),
            ("quarkus.management.host", "0.0.0.0"),
            ("quarkus.management.port", management_port),
            ("quarkus.management.root-path", "/q"),
            ("quarkus.smallrye-health.root-path", "/q/health"),
            ("quarkus.http.port", application_port),
        ):
            for profile in prod_profiles:
                override = _get(profile, key)
                if override is not None and override != expected:
                    return False
        policy_path = Path(root) / "openbank-infra/gitops/components/pension/network-policies.yaml"
        policies = list(yaml.safe_load_all(policy_path.read_text(encoding="utf-8")))
        policy = next(
            p for p in policies
            if p.get("kind") == "NetworkPolicy"
            and p.get("metadata", {}).get("name") == "pension-service-ingress-allow-list"
            and p.get("metadata", {}).get("namespace") == "pension"
        )
        policy_selector = policy["spec"]["podSelector"]
        policy_labels = policy_selector["matchLabels"]
        if policy_selector.get("matchExpressions") or not policy_labels:
            return False
        if policy_labels.get("app.kubernetes.io/name") != "pension-service":
            return False
        if any(pod_labels.get(key) != value for key, value in policy_labels.items()):
            return False
        admin_rule = False
        for rule in policy["spec"]["ingress"]:
            ports = rule.get("ports", [])
            if any(int(p.get("port", 0)) == application_port for p in ports):
                return False
            from_admin = any(
                peer.get("namespaceSelector", {}).get("matchLabels", {}).get("kubernetes.io/metadata.name") == "admin-ui"
                for peer in rule.get("from", [])
            )
            if from_admin:
                for peer in rule.get("from", []):
                    namespace = peer.get("namespaceSelector", {})
                    if namespace.get("matchLabels", {}).get("kubernetes.io/metadata.name") == "admin-ui":
                        if peer != {"namespaceSelector": {"matchLabels": {"kubernetes.io/metadata.name": "admin-ui"}}}:
                            return False
                admin_rule = True
                if ports != [{"protocol": "TCP", "port": 8443}]:
                    return False
        if not admin_rule:
            return False
        container_ports = {p["name"]: int(p["containerPort"]) for p in container["ports"]}
        service_ports = {p["name"]: p for p in service["spec"]["ports"]}
        if len(container_ports) != len(container["ports"]) or len(service_ports) != len(service["spec"]["ports"]):
            return False
        if management.get("enabled") is not True or management_port == application_port:
            return False
        if management.get("root-path") != "/q" or _get(doc, "quarkus.smallrye-health.root-path") != "/q/health":
            return False
        if management.get("host") != "0.0.0.0" or env.get("QUARKUS_MANAGEMENT_HOST", "0.0.0.0") != "0.0.0.0":
            return False
        if env.get("QUARKUS_MANAGEMENT_ROOT_PATH", "/q") != "/q":
            return False
        if env.get("QUARKUS_SMALLRYE_HEALTH_ROOT_PATH", "/q/health") != "/q/health":
            return False
        if env.get("QUARKUS_MANAGEMENT_ENABLED", "true") != "true":
            return False
        if int(env.get("QUARKUS_MANAGEMENT_PORT", management_port)) != management_port:
            return False
        if env.get("QUARKUS_HTTP_INSECURE_REQUESTS") != "disabled":
            return False
        if env.get("QUARKUS_HTTP_SSL_CLIENT_AUTH", "required") != "required":
            return False
        if int(env.get("QUARKUS_HTTP_PORT", application_port)) != application_port:
            return False
        if int(env.get("QUARKUS_HTTP_SSL_PORT", 8443)) != 8443:
            return False
        if "http" in container_ports or "http" in service_ports:
            return False
        if container_ports.get("management") != management_port or container_ports.get("https") != 8443:
            return False
        if service_ports["management"]["port"] != management_port or service_ports["management"]["targetPort"] != "management":
            return False
        if service_ports["https"]["port"] != 8443 or service_ports["https"]["targetPort"] != "https":
            return False
        for name, path in (("readinessProbe", "/q/health/ready"), ("livenessProbe", "/q/health/live")):
            probe = container[name]["httpGet"]
            if probe.get("port") != management_port or probe.get("path") != path or probe.get("scheme", "HTTP") != "HTTP":
                return False
        return True
    except (AttributeError, IndexError, KeyError, TypeError, ValueError, StopIteration, OSError, yaml.YAMLError):
        return False


def scan(root: str) -> tuple[int, list[str]]:
    files = sorted(glob.glob(os.path.join(root, "openbank-*/src/main/resources/application.yaml")))
    bad = []
    for f in files:
        with open(f, encoding="utf-8") as fh:
            doc = yaml.safe_load(fh)
        ready = (
            os.path.relpath(f, root) == "openbank-pension-service/src/main/resources/application.yaml"
            and pension_management_ready(root, doc)
        )
        bad += [f"{os.path.relpath(f, root)}: {m}" for m in findings_for(doc, pension_management_ready=ready)]
    return len(files), bad


def self_test() -> int:
    bad = yaml.safe_load('"%prod":\n  quarkus:\n    http:\n      ssl:\n        client-auth: required\n')
    good = yaml.safe_load(
        '"%prod":\n  quarkus:\n    http:\n      insecure-requests: enabled\n'
        "      ssl:\n        client-auth: required\n"
    )
    dotted = yaml.safe_load('quarkus.http.ssl.client-auth: required\n"%dev":\n  quarkus.http.insecure-requests: enabled\n')
    disabled = yaml.safe_load('"%prod":\n  quarkus:\n    http:\n      insecure-requests: disabled\n      ssl:\n        client-auth: required\n')
    none = yaml.safe_load("quarkus:\n  http:\n    port: 8080\n")
    checks = [
        ("missing insecure-requests flagged", len(findings_for(bad)) == 1),
        ("explicit enabled passes", findings_for(good) == []),
        ("top-level required inherited, default profile flagged", len(findings_for(dotted)) >= 1),
        ("explicit disabled flagged", len(findings_for(disabled)) == 1),
        ("no client-auth passes", findings_for(none) == []),
    ]
    pension = yaml.safe_load(
        'quarkus:\n  http:\n    port: 8171\n  management:\n'
        '    enabled: true\n    host: 0.0.0.0\n    port: 8090\n    root-path: /q\n'
        '  smallrye-health:\n    root-path: /q/health\n'
        '"%prod":\n  quarkus:\n    http:\n      insecure-requests: disabled\n'
        '      ssl:\n        client-auth: required\n'
    )
    container = {
        "name": "pension-service",
        "ports": [{"name": "https", "containerPort": 8443}, {"name": "management", "containerPort": 8090}],
        "env": [{"name": "QUARKUS_HTTP_INSECURE_REQUESTS", "value": "disabled"}],
        "readinessProbe": {"httpGet": {"path": "/q/health/ready", "port": 8090}},
        "livenessProbe": {"httpGet": {"path": "/q/health/live", "port": 8090}},
    }
    deployment = {
        "kind": "Deployment", "metadata": {"name": "pension-service", "namespace": "pension"},
        "spec": {"selector": {"matchLabels": {"app.kubernetes.io/name": "pension-service"}}, "template": {
            "metadata": {"labels": {"app.kubernetes.io/name": "pension-service"}},
            "spec": {"containers": [container]},
        }},
    }
    service = {
        "kind": "Service", "metadata": {"name": "pension-service", "namespace": "pension"},
        "spec": {"selector": {"app.kubernetes.io/name": "pension-service"}, "ports": [
            {"name": "https", "port": 8443, "targetPort": "https"},
            {"name": "management", "port": 8090, "targetPort": "management"},
        ]},
    }
    policy = {
        "kind": "NetworkPolicy",
        "metadata": {"name": "pension-service-ingress-allow-list", "namespace": "pension"},
        "spec": {
            "podSelector": {"matchLabels": {"app.kubernetes.io/name": "pension-service"}},
            "ingress": [{
                "from": [{"namespaceSelector": {"matchLabels": {"kubernetes.io/metadata.name": "admin-ui"}}}],
                "ports": [{"protocol": "TCP", "port": 8443}],
            }],
        },
    }
    with tempfile.TemporaryDirectory() as root:
        app_path = Path(root) / "openbank-pension-service/src/main/resources/application.yaml"
        deployment_path = Path(root) / "openbank-infra/gitops/components/pension/pension-service.yaml"
        policy_path = Path(root) / "openbank-infra/gitops/components/pension/network-policies.yaml"
        app_path.parent.mkdir(parents=True)
        deployment_path.parent.mkdir(parents=True)

        def result(app=pension, dep=deployment, svc=service, net=policy):
            app_path.write_text(yaml.safe_dump(app), encoding="utf-8")
            deployment_path.write_text(yaml.safe_dump_all([dep, svc]), encoding="utf-8")
            policy_path.write_text(yaml.safe_dump(net), encoding="utf-8")
            return scan(root)[1]

        def with_container(**changes):
            copy = deepcopy(deployment)
            copy["spec"]["template"]["spec"]["containers"][0].update(changes)
            return copy

        no_management = deepcopy(pension)
        no_management["quarkus"]["management"]["enabled"] = False
        prod_management_disabled = deepcopy(pension)
        prod_management_disabled["%prod"]["quarkus"]["management"] = {"enabled": False}
        combined_management_disabled = deepcopy(pension)
        combined_management_disabled["%prod,%test"] = {"quarkus": {"management": {"enabled": False}}}
        stale_policy = deepcopy(policy)
        stale_policy["spec"]["ingress"][0]["ports"] = [{"protocol": "TCP", "port": 8171}]
        unmatched_policy = deepcopy(policy)
        unmatched_policy["spec"]["podSelector"]["matchLabels"]["app.kubernetes.io/tier"] = "missing"
        unmatched_service = deepcopy(service)
        unmatched_service["spec"]["selector"]["app.kubernetes.io/tier"] = "missing"
        unmatched_deployment = deepcopy(deployment)
        unmatched_deployment["spec"]["selector"]["matchLabels"]["app.kubernetes.io/tier"] = "missing"
        unmatched_admin_namespace = deepcopy(policy)
        unmatched_admin_namespace["spec"]["ingress"][0]["from"][0]["namespaceSelector"]["matchLabels"]["environment"] = "missing"
        unmatched_admin_pod = deepcopy(policy)
        unmatched_admin_pod["spec"]["ingress"][0]["from"][0]["podSelector"] = {"matchLabels": {"app.kubernetes.io/name": "missing"}}
        checks.extend([
            ("pension HTTPS-only with separate management probes passes", result() == []),
            ("management disabled is rejected", len(result(app=no_management)) == 1),
            ("production management override is rejected", len(result(app=prod_management_disabled)) == 1),
            ("combined production profile cannot hide disabled management", bool(result(app=combined_management_disabled))),
            ("probe on application port is rejected", len(result(dep=with_container(readinessProbe={"httpGet": {"path": "/q/health/ready", "port": 8171}}))) == 1),
            ("liveness probe on application port is rejected", len(result(dep=with_container(livenessProbe={"httpGet": {"path": "/q/health/live", "port": 8171}}))) == 1),
            ("deployment retaining HTTP port is rejected", len(result(dep=with_container(ports=[*container["ports"], {"name": "http", "containerPort": 8171}]))) == 1),
            ("deployment overriding HTTP to enabled is rejected", len(result(dep=with_container(env=[{"name": "QUARKUS_HTTP_INSECURE_REQUESTS", "value": "enabled"}]))) == 1),
            ("deployment disabling management is rejected", len(result(dep=with_container(env=[*container["env"], {"name": "QUARKUS_MANAGEMENT_ENABLED", "value": "false"}]))) == 1),
            ("Service retaining HTTP port is rejected", len(result(svc={**service, "spec": {"ports": [*service["spec"]["ports"], {"name": "http", "port": 8171, "targetPort": "http"}]}})) == 1),
            ("missing Service is rejected", len(result(svc={"kind": "Service", "metadata": {"name": "other"}})) == 1),
            ("stale HTTP NetworkPolicy is rejected", len(result(net=stale_policy)) == 1),
            ("NetworkPolicy selector missing the pod is rejected", len(result(net=unmatched_policy)) == 1),
            ("Service selector missing the pod is rejected", len(result(svc=unmatched_service)) == 1),
            ("Deployment selector missing the pod is rejected", len(result(dep=unmatched_deployment)) == 1),
            ("admin namespace selector with extra labels is rejected", len(result(net=unmatched_admin_namespace)) == 1),
            ("admin peer with unmatched pod selector is rejected", len(result(net=unmatched_admin_pod)) == 1),
            ("missing NetworkPolicy is rejected", len(result(net={"kind": "NetworkPolicy", "metadata": {"name": "other"}})) == 1),
        ])
        result()
        other_path = Path(root) / "openbank-other-service/src/main/resources/application.yaml"
        other_path.parent.mkdir(parents=True)
        other_path.write_text(yaml.safe_dump(pension), encoding="utf-8")
        checks.append(("another service gets no exception", len(scan(root)[1]) == 1))
    ok = True
    for label, passed in checks:
        print(f"  {'PASS' if passed else 'FAIL'}: {label}")
        ok &= passed
    return 0 if ok else 1


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        return self_test()
    enforce = "--enforce" in argv
    args = [a for a in argv if not a.startswith("--")]
    root = args[0] if args else "."
    n, bad = scan(root)
    print(f"SUBJECTS={n}")
    print(f"mtls-keeps-http-port: {n} application.yaml subjects, {len(bad)} findings")
    for b in bad:
        print(f"  {b}")
    if n == 0:
        print("no subjects found — refusing to report clean")
        return 1
    return 1 if (bad and enforce) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
