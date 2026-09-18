"""Reject a customer capability switch whose declared backend cannot serve it.

This is a desired-state check, not a runtime health probe. It catches the GitOps
ordering error where an edge switch is enabled while its backend remains at zero
replicas (or has no workload at all). Runtime failures remain the route's 502/503.
"""

import argparse
import re
import sys
import tempfile
from pathlib import Path
from urllib.parse import urlsplit

import yaml

CAPABILITIES = {
    "loyalty": ("LOYALTY_SERVICE_URL", "loyalty-service", "loyalty"),
    "referrals": ("REFERRAL_SERVICE_URL", "referral-service", "referral"),
}
EDGE_CONFIG = Path("openbank-customer-edge/src/main/resources/application.yaml")
EDGE_GITOPS = Path("openbank-infra/gitops/components/customer-edge/customer-edge.yaml")
COMPONENTS = Path("openbank-infra/gitops/components")


def documents(path):
    if not path.is_file():
        raise ValueError(f"missing required manifest: {path}")
    return [doc for doc in yaml.safe_load_all(path.read_text()) if isinstance(doc, dict)]


def workload(root, name, namespace):
    matches = []
    for path in (root / COMPONENTS / namespace).glob("*.yaml"):
        for doc in documents(path):
            if doc.get("kind") not in {"Deployment", "Rollout", "StatefulSet"}:
                continue
            meta = doc.get("metadata") or {}
            if meta.get("name") == name and meta.get("namespace") == namespace:
                matches.append(doc)
    if len(matches) > 1:
        raise ValueError(f"multiple {namespace}/{name} workloads")
    return matches[0] if matches else None


def edge_environment(root):
    matches = [
        doc for doc in documents(root / EDGE_GITOPS)
        if doc.get("kind") in {"Deployment", "Rollout"}
        and (doc.get("metadata") or {}).get("name") == "customer-edge"
    ]
    if len(matches) != 1:
        raise ValueError(f"expected one customer-edge workload, found {len(matches)}")
    containers = (((matches[0].get("spec") or {}).get("template") or {}).get("spec") or {}).get("containers") or []
    edge = [container for container in containers if container.get("name") == "customer-edge"]
    if len(edge) != 1:
        raise ValueError(f"expected one customer-edge container, found {len(edge)}")
    env = edge[0].get("env") or []
    if len(env) != len({item.get("name") for item in env}):
        raise ValueError("duplicate customer-edge environment variable")
    return {item["name"]: item for item in env}


def configured_value(value, env):
    """Resolve a literal or `${NAME:default}` from the declared Pod environment."""
    if not isinstance(value, str):
        raise TypeError(f"expected a scalar config value, got {value!r}")
    match = re.fullmatch(r"\$\{([A-Z][A-Z0-9_]*):(.*)}", value)
    if not match:
        return value
    name, default = match.groups()
    item = env.get(name)
    if item is None:
        return default
    if "valueFrom" in item or "value" not in item:
        raise ValueError(f"{name} cannot be resolved from a literal GitOps value")
    return str(item["value"])


def verify(root):
    app = documents(root / EDGE_CONFIG)
    if len(app) != 1:
        raise ValueError("expected one customer-edge application document")
    edge = (app[0].get("openbank") or {}).get("edge") or {}
    declared = edge.get("capabilities") or {}
    # Older branches without the endpoint have no switches to activate.
    if not declared:
        return []
    env = edge_environment(root)
    findings = []
    for capability, (url_name, service, namespace) in CAPABILITIES.items():
        entry = declared.get(capability)
        if not isinstance(entry, dict) or "enabled" not in entry:
            findings.append(f"{capability}: missing explicit enabled declaration")
            continue
        raw_enabled = entry["enabled"]
        enabled = str(raw_enabled).lower() if isinstance(raw_enabled, bool) else configured_value(raw_enabled, env).strip().lower()
        if enabled not in {"true", "false"}:
            findings.append(f"{capability}: enabled must resolve to literal true or false")
            continue
        if enabled == "false":
            continue
        url_key = "loyalty-service-url" if capability == "loyalty" else "referral-service-url"
        url = configured_value(edge.get(url_key, ""), env).strip()
        if not url:
            findings.append(f"{capability}: enabled but {url_name} is blank")
        else:
            parsed = urlsplit(url)
            allowed_hosts = {f"{service}.{namespace}.svc", f"{service}.{namespace}.svc.cluster.local"}
            if parsed.scheme not in {"http", "https"} or parsed.hostname not in allowed_hosts:
                findings.append(f"{capability}: {url_name} must target {service}.{namespace}.svc")
        backend = workload(root, service, namespace)
        if backend is None:
            findings.append(f"{capability}: enabled but {namespace}/{service} has no workload")
            continue
        replicas = (backend.get("spec") or {}).get("replicas")
        if not isinstance(replicas, int) or isinstance(replicas, bool) or replicas < 1:
            findings.append(f"{capability}: enabled but {namespace}/{service} replicas={replicas!r}")
    return findings


def self_test():
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        app = root / EDGE_CONFIG
        app.parent.mkdir(parents=True)
        edge = root / EDGE_GITOPS
        edge.parent.mkdir(parents=True)
        backend = root / COMPONENTS / "referral" / "referral-service.yaml"
        backend.parent.mkdir(parents=True)
        app.write_text("""openbank:\n  edge:\n    referral-service-url: ${REFERRAL_SERVICE_URL:https://referral-service.referral.svc:8443}\n    capabilities:\n      loyalty: {enabled: '${CAPABILITY_LOYALTY_ENABLED:false}'}\n      referrals: {enabled: '${CAPABILITY_REFERRALS_ENABLED:false}'}\n""")
        edge.write_text("""kind: Rollout\nmetadata: {name: customer-edge}\nspec:\n  template:\n    spec:\n      containers:\n        - name: customer-edge\n          env: [{name: REFERRAL_SERVICE_URL, value: 'https://referral-service.referral.svc:8443'}]\n""")
        backend.write_text("kind: Deployment\nmetadata: {name: referral-service, namespace: referral}\nspec: {replicas: 0}\n")
        assert verify(root) == [], "disabled capability must pass"
        original = edge.read_text()
        edge.write_text(original.replace("REFERRAL_SERVICE_URL, value:", "CAPABILITY_REFERRALS_ENABLED, value: 'true'}, {name: REFERRAL_SERVICE_URL, value:"))
        assert "replicas=0" in " ".join(verify(root)), "zero-replica activation must fail"
        backend.write_text(backend.read_text().replace("replicas: 0", "replicas: 1"))
        assert verify(root) == [], "deployed and enabled backend must pass"
        backend.unlink()
        assert "no workload" in " ".join(verify(root)), "missing deployment must fail"
        edge.write_text(edge.read_text().replace("https://referral-service.referral.svc:8443", ""))
        assert "blank" in " ".join(verify(root)), "missing URL must fail"
        edge.write_text(edge.read_text().replace("value: ''", "value: 'https://wrong.referral.svc:8443'"))
        assert "must target" in " ".join(verify(root)), "wrong backend must fail"
    print("self-test OK: disabled, zero replicas, live, missing workload, missing/wrong URL")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    try:
        if args.self_test:
            self_test()
            return 0
        findings = verify(args.root)
    except (OSError, TypeError, ValueError, yaml.YAMLError) as exc:
        print(f"customer-capability-activation: undetermined: {exc}", file=sys.stderr)
        return 2
    for finding in findings:
        print(f"customer-capability-activation: {finding}", file=sys.stderr)
    if findings:
        return 1
    print("customer-capability-activation: desired state is consistent")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
