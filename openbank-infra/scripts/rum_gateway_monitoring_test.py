#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""RUM self-metrics contract, discovered by the existing governance unit suite.

For the real chart round trip (no download or cluster access):
  python3 openbank-infra/scripts/rum_gateway_monitoring_test.py \
    --chart PATH_TO_PINNED_TGZ --prometheus-snapshot PATH_TO_PROMETHEUS_JSON

Ordinary discovery checks source and policy. The explicit round trip additionally
checks real Helm output against the supplied Prometheus selectors and mutations.
"""
from __future__ import annotations

import argparse
import copy
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
GITOPS = ROOT / "openbank-infra/gitops"
APP = GITOPS / "apps/rum-gateway.yaml"
POLICY = GITOPS / "components/observability/networkpolicy-rum-gateway.yaml"
PROMETHEUS = GITOPS / "apps/kube-prometheus-stack.yaml"
PROM_LABELS = {
    "app.kubernetes.io/name": "prometheus",
    "app.kubernetes.io/instance": "kube-prometheus-stack-prometheus",
}


def load(path):
    return yaml.safe_load(path.read_text())


def matches(selector, labels):
    # These manifests use matchLabels; an unfamiliar selector must not pass.
    assert not selector.get("matchExpressions"), "unsupported selector expression"
    return all(labels.get(k) == v for k, v in selector.get("matchLabels", {}).items())


def permits(policy, namespace, labels, port):
    for rule in policy["spec"]["ingress"]:
        if not any(p.get("protocol", "TCP") == "TCP" and p["port"] == port
                   for p in rule.get("ports", [])):
            continue
        for peer in rule.get("from", []):
            ns_selector = peer.get("namespaceSelector")
            ns_ok = (namespace == policy["metadata"]["namespace"] if ns_selector is None
                     else matches(ns_selector, {"kubernetes.io/metadata.name": namespace}))
            if ns_ok and matches(peer.get("podSelector", {}), labels):
                return True
    return False


def render(chart, values):
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "values.yaml"
        path.write_text(yaml.safe_dump(values))
        result = subprocess.run(
            ["helm", "template", "rum-gateway", str(chart), "--namespace", "observability",
             "--values", str(path)], capture_output=True, text=True, timeout=30, check=False,
        )
    assert result.returncode == 0, "pinned chart did not render"
    return [doc for doc in yaml.safe_load_all(result.stdout) if isinstance(doc, dict)]


def check_chain(documents, prometheus, policy):
    def one(kind):
        found = [d for d in documents if d.get("kind") == kind]
        assert len(found) == 1, f"expected one {kind}"
        return found[0]

    monitor, service, deployment = one("ServiceMonitor"), one("Service"), one("Deployment")
    spec = prometheus["spec"]
    ns = monitor["metadata"]["namespace"]
    assert matches(spec["serviceMonitorSelector"], monitor["metadata"].get("labels", {})), \
        "Prometheus does not select the ServiceMonitor"
    ns_selector = spec.get("serviceMonitorNamespaceSelector")
    assert (ns == prometheus["metadata"]["namespace"] if ns_selector is None else
            matches(ns_selector, {"kubernetes.io/metadata.name": ns})), "monitor namespace excluded"
    assert service["metadata"]["namespace"] == ns, "Service outside monitor namespace"
    assert matches(monitor["spec"]["selector"], service["metadata"]["labels"]), \
        "monitor does not select the Service"
    endpoint = monitor["spec"]["endpoints"][0]
    port = next((p for p in service["spec"]["ports"] if p["name"] == endpoint["port"]), None)
    assert port and port["port"] == 8888, "missing metrics Service port"
    pod = deployment["spec"]["template"]
    assert all(pod["metadata"]["labels"].get(k) == v for k, v in service["spec"]["selector"].items()), \
        "Service does not select the collector Pod"
    ports = [p for c in pod["spec"]["containers"] for p in c.get("ports", [])]
    assert any(p["containerPort"] == 8888 and
               (port["targetPort"] == 8888 or p["name"] == port["targetPort"]) for p in ports), \
        "Service target does not reach collector metrics port"
    relay = yaml.safe_load(one("ConfigMap")["data"]["relay"])
    readers = relay["service"]["telemetry"]["metrics"]["readers"]
    assert any(r.get("pull", {}).get("exporter", {}).get("prometheus", {}).get("port") == 8888
               for r in readers), "collector listener is not configured"
    assert matches(policy["spec"]["podSelector"], pod["metadata"]["labels"]), "policy misses collector"
    assert permits(policy, ns, PROM_LABELS, 8888), "NetworkPolicy blocks the scrape"
    assert not permits(policy, "other", PROM_LABELS, 8888), "cross-namespace metrics exposure"
    assert not permits(policy, ns, {"app.kubernetes.io/name": "prometheus"}, 8888), \
        "wrong Prometheus instance admitted"
    assert not permits(policy, ns, {"app.kubernetes.io/name": "other"}, 8888), "other Pod admitted"


class RumMonitoringTest(unittest.TestCase):
    def setUp(self):
        self.values = load(APP)["spec"]["source"]["helm"]["valuesObject"]
        self.policy = load(POLICY)

    def test_native_chart_monitoring_is_enabled(self):
        self.assertTrue(self.values["ports"].get("metrics", {}).get("enabled", False))
        self.assertTrue(self.values.get("serviceMonitor", {}).get("enabled", False))

    def test_prometheus_does_not_require_release_labels(self):
        values = load(PROMETHEUS)["spec"]["source"]["helm"]["valuesObject"]
        self.assertIs(values["prometheus"]["prometheusSpec"]["serviceMonitorSelectorNilUsesHelmValues"], False)

    def test_metrics_peer_is_only_this_prometheus(self):
        rules = [r for r in self.policy["spec"]["ingress"]
                 if any(p["port"] == 8888 for p in r["ports"])]
        self.assertEqual(rules, [{"from": [{"podSelector": {"matchLabels": PROM_LABELS}}],
                                 "ports": [{"protocol": "TCP", "port": 8888}]}])
        self.assertTrue(permits(self.policy, "observability", PROM_LABELS, 8888))

    def test_other_namespace_and_pods_cannot_scrape(self):
        for namespace, labels in [("other", PROM_LABELS), ("observability", {}),
                                  ("observability", {"app.kubernetes.io/name": "prometheus"}),
                                  ("observability", {**PROM_LABELS, "app.kubernetes.io/instance": "other"})]:
            with self.subTest(namespace=namespace, labels=labels):
                self.assertFalse(permits(self.policy, namespace, labels, 8888))

    def test_scrape_permission_does_not_admit_otlp(self):
        self.assertFalse(permits(self.policy, "observability", PROM_LABELS, 4318))
        self.assertTrue(permits(self.policy, "ingress-nginx", {}, 4318))
        self.assertTrue(permits(self.policy, "envoy-gateway-system", {}, 4318))
        self.assertFalse(permits(self.policy, "ingress-nginx", {}, 8888))

    def test_metrics_is_not_added_to_public_ingress(self):
        paths = [p for h in self.values["ingress"]["hosts"] for p in h["paths"]]
        self.assertTrue(all(p["port"] == 4318 for p in paths))


def real_chart_roundtrip(chart, prometheus_path):
    values = load(APP)["spec"]["source"]["helm"]["valuesObject"]
    policy, prometheus = load(POLICY), json.loads(prometheus_path.read_text())
    documents = render(chart, values)
    check_chain(documents, prometheus, policy)
    print("PASS: pinned chart + supplied Prometheus selectors + Service/Pod/listener + policy")
    mutations = {}
    for key in ("metrics", "serviceMonitor"):
        changed = copy.deepcopy(values)
        if key == "metrics":
            changed["ports"][key]["enabled"] = False
        else:
            changed[key]["enabled"] = False
        mutations[key] = (changed, policy, prometheus)
    no_scrape = copy.deepcopy(policy)
    no_scrape["spec"]["ingress"] = [r for r in no_scrape["spec"]["ingress"]
                                    if not any(p["port"] == 8888 for p in r["ports"])]
    mutations["policy permission"] = (values, no_scrape, prometheus)
    excluded = copy.deepcopy(prometheus)
    excluded["spec"]["serviceMonitorSelector"] = {"matchLabels": {"required-monitor-label": "missing"}}
    mutations["Prometheus selector"] = (values, policy, excluded)
    for name, (changed, changed_policy, changed_prometheus) in mutations.items():
        try:
            check_chain(render(chart, changed), changed_prometheus, changed_policy)
        except AssertionError:
            print(f"PASS: {name} mutation rejected")
        else:
            raise AssertionError(f"{name} mutation was accepted")
    # Mutate real rendered bindings as well as the source flags: each discovery
    # stage must reject an otherwise valid but disconnected chain.
    for name in ("monitor selector", "Service port", "Service selector", "listener"):
        changed = copy.deepcopy(documents)
        if name == "monitor selector":
            next(d for d in changed if d["kind"] == "ServiceMonitor")["spec"]["selector"] = {
                "matchLabels": {"app.kubernetes.io/instance": "other"}}
        elif name == "Service port":
            service = next(d for d in changed if d["kind"] == "Service")
            next(p for p in service["spec"]["ports"] if p["port"] == 8888)["port"] = 9999
        elif name == "Service selector":
            next(d for d in changed if d["kind"] == "Service")["spec"]["selector"] = {
                "app.kubernetes.io/instance": "other"}
        else:
            config = next(d for d in changed if d["kind"] == "ConfigMap")
            relay = yaml.safe_load(config["data"]["relay"])
            relay["service"]["telemetry"]["metrics"]["readers"] = []
            config["data"]["relay"] = yaml.safe_dump(relay)
        try:
            check_chain(changed, prometheus, policy)
        except AssertionError:
            print(f"PASS: {name} mutation rejected")
        else:
            raise AssertionError(f"{name} mutation was accepted")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--chart", type=Path)
    parser.add_argument("--prometheus-snapshot", type=Path)
    args, remaining = parser.parse_known_args()
    if bool(args.chart) != bool(args.prometheus_snapshot):
        parser.error("--chart and --prometheus-snapshot are required together")
    if args.chart:
        real_chart_roundtrip(args.chart, args.prometheus_snapshot)
    unittest.main(argv=[__file__, *remaining])
