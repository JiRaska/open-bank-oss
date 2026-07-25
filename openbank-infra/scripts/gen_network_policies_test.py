#!/usr/bin/env python3
"""Unit tests for gen-network-policies.py's internal-only port exclusion.

Focused on the bug where any container port not literally named "management" was
unconditionally bucketed into http_ports and granted an admin-ui BFF-discovery
ingress rule — correct for a service's actual REST API, wrong for a datastore
wire-protocol port (e.g. a Redis sidecar's port 6379), which admin-ui never calls
directly. Regression coverage for the fraud-service online-feature-store Redis fix
(PR #706), which surfaced the gap.

The module under test has a hyphenated filename (not import-able as a normal
module), so it is loaded via importlib — same technique as
authz_coverage_report_test.py.

Run:  python3 -m unittest openbank-infra/scripts/gen_network_policies_test.py -v
  or: python3 openbank-infra/scripts/gen_network_policies_test.py
"""

from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path

import yaml

_SCRIPT_PATH = Path(__file__).parent / "gen-network-policies.py"
_spec = importlib.util.spec_from_file_location("gen_network_policies", _SCRIPT_PATH)
gen = importlib.util.module_from_spec(_spec)
assert _spec.loader is not None
_spec.loader.exec_module(gen)


_FIXTURE_MANIFEST = """\
apiVersion: apps/v1
kind: Deployment
metadata:
  name: svc-a
  namespace: svc-a
spec:
  template:
    metadata:
      labels:
        app.kubernetes.io/name: svc-a
    spec:
      containers:
        - name: svc-a
          ports:
            - name: http
              containerPort: 8080
            - name: management
              containerPort: 8085
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: redis
  namespace: svc-a
spec:
  template:
    metadata:
      labels:
        app.kubernetes.io/name: redis
    spec:
      containers:
        - name: redis
          ports:
            - name: redis
              containerPort: 6379
"""


class InternalOnlyPortExclusionTest(unittest.TestCase):
    """End-to-end: run main() against a fixture gitops tree, inspect the output."""

    def setUp(self) -> None:
        self._tmpdir = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmpdir.cleanup)
        root = Path(self._tmpdir.name)
        ns_dir = root / "svc-a"
        ns_dir.mkdir()
        (ns_dir / "workloads.yaml").write_text(_FIXTURE_MANIFEST, encoding="utf-8")

        self._orig_components = gen.COMPONENTS
        gen.COMPONENTS = str(root)
        self.addCleanup(setattr, gen, "COMPONENTS", self._orig_components)

        gen.main()
        self._policies = list(
            yaml.safe_load_all((ns_dir / "network-policies.yaml").read_text(encoding="utf-8"))
        )

    def _policy(self, name: str) -> dict:
        for pol in self._policies:
            if pol and pol["metadata"]["name"] == name:
                return pol
        raise AssertionError(f"no policy named {name} in {self._policies}")

    def test_http_workload_still_gets_admin_ui_rule(self) -> None:
        """Regression guard: the fix must not blanket-exclude real HTTP ports."""
        pol = self._policy("svc-a-ingress-allow-list")
        admin_ui_rules = [
            r for r in pol["spec"]["ingress"]
            if any(
                f.get("namespaceSelector", {}).get("matchLabels", {}).get(
                    "kubernetes.io/metadata.name"
                ) == "admin-ui"
                for f in r.get("from", [])
            )
        ]
        self.assertEqual(len(admin_ui_rules), 1)
        self.assertEqual(admin_ui_rules[0]["ports"], [{"protocol": "TCP", "port": 8080}])

    def test_redis_port_gets_no_admin_ui_rule(self) -> None:
        """The bug: redis's wire-protocol port must not be BFF-discoverable."""
        pol = self._policy("redis-ingress-allow-list")
        for rule in pol["spec"]["ingress"]:
            for f in rule.get("from", []):
                ns = f.get("namespaceSelector", {}).get("matchLabels", {}).get(
                    "kubernetes.io/metadata.name"
                )
                self.assertNotEqual(
                    ns, "admin-ui",
                    f"redis policy must not admit admin-ui, got rule: {rule}",
                )

    def test_redis_policy_is_same_namespace_only(self) -> None:
        """No management port + no declared callers -> exactly the same-ns rule."""
        pol = self._policy("redis-ingress-allow-list")
        self.assertEqual(pol["spec"]["ingress"], [{"from": [{"podSelector": {}}]}])


_SHARED_NS_DIR_A = """\
apiVersion: apps/v1
kind: Deployment
metadata:
  name: alpha-service
  namespace: shared
spec:
  template:
    metadata:
      labels:
        app.kubernetes.io/name: alpha-service
    spec:
      containers:
        - name: alpha-service
          ports:
            - name: http
              containerPort: 8080
"""

_SHARED_NS_DIR_Z = """\
apiVersion: apps/v1
kind: Deployment
metadata:
  name: zulu-service
  namespace: shared
spec:
  template:
    metadata:
      labels:
        app.kubernetes.io/name: zulu-service
    spec:
      containers:
        - name: zulu-service
          ports:
            - name: http
              containerPort: 8081
"""


class SharedNamespaceSplitTest(unittest.TestCase):
    """Two component dirs, one namespace -> one policy file each (issue #2207).

    Keying the output by namespace put the WHOLE namespace's allow-lists in the
    first component directory alphabetically, so `components/zulu/` looked like it
    shipped no NetworkPolicy while `zulu-service-ingress-allow-list` was in fact
    live — and, worse, was owned and pruned by the *alpha* ArgoCD Application.
    """

    def setUp(self) -> None:
        self._tmpdir = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmpdir.cleanup)
        self.root = Path(self._tmpdir.name)
        for name, manifest in (("alpha", _SHARED_NS_DIR_A), ("zulu", _SHARED_NS_DIR_Z)):
            d = self.root / name
            d.mkdir()
            (d / "workloads.yaml").write_text(manifest, encoding="utf-8")

        orig = gen.COMPONENTS
        gen.COMPONENTS = str(self.root)
        self.addCleanup(setattr, gen, "COMPONENTS", orig)
        gen.main()

    def _names(self, component_dir: str) -> list[str]:
        path = self.root / component_dir / "network-policies.yaml"
        self.assertTrue(path.exists(), f"{component_dir} got no network-policies.yaml")
        return [
            d["metadata"]["name"]
            for d in yaml.safe_load_all(path.read_text(encoding="utf-8"))
            if d
        ]

    def test_each_component_dir_gets_its_own_workloads_policy(self) -> None:
        self.assertEqual(self._names("alpha"), ["alpha-service-ingress-allow-list"])
        self.assertEqual(self._names("zulu"), ["zulu-service-ingress-allow-list"])

    def test_stale_generated_file_is_pruned(self) -> None:
        """A file the generator no longer writes must not linger and keep applying."""
        stale = self.root / "alpha" / "network-policies.yaml"
        gone = self.root / "gone"
        gone.mkdir()
        (gone / "network-policies.yaml").write_text(
            stale.read_text(encoding="utf-8"), encoding="utf-8"
        )
        hand_written = self.root / "gone" / "temporal-network-policies.yaml"
        hand_written.write_text("# hand-authored\n", encoding="utf-8")
        gen.main()
        self.assertFalse((gone / "network-policies.yaml").exists())
        self.assertTrue(hand_written.exists())


if __name__ == "__main__":
    unittest.main()
