#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Unit tests for generate-service-runbooks.py (run by ci.yml's governance-script step).

Covers the two behaviours a runbook is actively harmful without, both found by issue #2255:

* **namespace resolution** — the runbook used to interpolate the service short name as its
  namespace, so every `kubectl -n <ns>` line in a third of the fleet named a namespace that
  does not exist (document-service runs in `documents`, ap2/mcp in `platform`,
  settlement/vop/card-issuance/standing-order in `payments`).
* **the stateless DR branch** — a service declaring no datastore used to be handed the
  generic "restore from the datastore's managed backup" text, sending an on-call engineer
  hunting for a backup that does not exist, mid-incident.
"""
from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent


def load_module(repo_root: Path):
    """Import the hyphenated generator with REPO/GITOPS/RUNBOOKS pointed at a fixture."""
    spec = importlib.util.spec_from_file_location(
        "gen_runbooks", HERE / "generate-service-runbooks.py"
    )
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    mod.REPO = repo_root
    mod.GITOPS = repo_root / "openbank-infra" / "gitops"
    mod.RUNBOOKS = repo_root / "docs" / "runbooks"
    return mod


GOVERNANCE = """dataDomain: platform
primaryDatastore: {datastore}
{owns_or_db_line}dataClassification: internal
retentionPolicy: 1 year
dataLineageRole: consumer
lineage:
  upstream:
    - serviceName: ledger-service
      relationType: api
      description: reads balances
"""

WORKLOAD = """apiVersion: apps/v1
kind: Deployment
metadata:
  name: {short}-service
{ns_line}spec:
  template:
    spec:
      containers:
        - name: {short}-service
          image: openbank-{short}-service:1.0.0
"""

# A NetworkPolicy in a DIFFERENT namespace that merely names the service as a peer. If the
# resolver ever counted a mention instead of the workload, this would win and every kubectl
# line in the runbook would point at the caller's namespace.
DECOY = """apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-egress-to-widget
  namespace: some-caller-namespace
spec:
  egress:
    - to:
        - podSelector:
            matchLabels:
              app.kubernetes.io/name: openbank-widget-service
"""


class RunbookGeneratorTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.comp = self.root / "openbank-infra" / "gitops" / "components" / "widget"
        self.comp.mkdir(parents=True)
        (self.root / "docs" / "runbooks").mkdir(parents=True)
        self.mod = load_module(self.root)

    def tearDown(self):
        self.tmp.cleanup()

    def write_service(self, datastore="none", database=None, owns_no_database=None, ns="widgets", inherit=False):
        svc = self.root / "openbank-widget-service"
        (svc / "src" / "main" / "resources").mkdir(parents=True, exist_ok=True)
        # At most one of database / owns_no_database is set per fixture — same as a real
        # governance.yaml (ADR-0196), where the two are mutually exclusive.
        if owns_no_database:
            owns_or_db_line = "ownsNoDatabase: true\n"
        elif database:
            owns_or_db_line = f"databaseName: {database}\n"
        else:
            owns_or_db_line = ""
        (svc / "governance.yaml").write_text(
            GOVERNANCE.format(datastore=datastore, owns_or_db_line=owns_or_db_line)
        )
        (svc / "src" / "main" / "resources" / "application.yaml").write_text(
            "quarkus:\n  http:\n    port: 8199\n"
        )
        ns_line = "" if inherit else f"  namespace: {ns}\n"
        (self.comp / "widget-service.yaml").write_text(
            WORKLOAD.format(short="widget", ns_line=ns_line)
        )
        (self.comp / "decoy-netpol.yaml").write_text(DECOY)
        if inherit:
            (self.comp / "kustomization.yaml").write_text(f"namespace: {ns}\nresources: []\n")

    # -- namespace resolution ------------------------------------------------
    def test_namespace_comes_from_the_workload_not_the_service_name(self):
        self.write_service(ns="widgets")
        self.assertEqual(self.mod.service_namespace("widget"), "widgets")

    def test_namespace_inherited_from_kustomization(self):
        self.write_service(ns="platform", inherit=True)
        self.assertEqual(self.mod.service_namespace("widget"), "platform")

    def test_a_mere_mention_does_not_resolve_the_namespace(self):
        """The decoy NetworkPolicy peer must never win over the real workload."""
        self.write_service(ns="widgets")
        self.assertNotEqual(self.mod.service_namespace("widget"), "some-caller-namespace")

    def test_rendered_kubectl_lines_use_the_resolved_namespace(self):
        self.write_service(ns="widgets")
        out = self.mod.render("widget")
        self.assertIn("kubectl logs -n widgets deploy/widget-service", out)
        self.assertIn('{namespace="widgets"}', out)
        self.assertNotIn("-n widget ", out)

    def test_namespace_falls_back_to_the_short_name_when_undeployed(self):
        """A service with no workload in gitops still renders — it just cannot do better."""
        self.write_service(ns="widgets")
        (self.comp / "widget-service.yaml").unlink()
        self.assertEqual(self.mod.service_namespace("widget"), "widget")

    # -- stateless DR branch -------------------------------------------------
    def test_stateless_service_is_not_told_to_restore_a_database(self):
        self.write_service(datastore="none")
        out = self.mod.render("widget")
        self.assertIn("no primary datastore", out)
        self.assertIn("redeploy from the GitOps manifests", out)
        self.assertIn("**RPO: n/a**", out)
        self.assertNotIn("managed backup", out)
        self.assertNotIn("Flyway checksum", out)
        self.assertNotIn("connection-pool metrics", out)

    # -- ownsNoDatabase but a real datastore (ADR-0196: copilot, customer-edge) --------------
    def test_owns_no_database_with_a_real_store_is_not_told_it_has_nothing_to_lose(self):
        """copilot/customer-edge shape: ownsNoDatabase: true + primaryDatastore: Redis. The old
        code inferred statelessness from the datastore string alone, so 'Redis' (not in the
        none/n/a/empty set) fell through to the generic Postgres-shaped 'restore from the
        datastore's managed backup' text — and separately, if it HAD matched the stateless
        branch, that branch's blanket 'holds no state to lose' claim would be actively wrong
        for a service whose Redis entries include durable, TTL-less credentials."""
        self.write_service(datastore="Redis", owns_no_database=True)
        out = self.mod.render("widget")
        self.assertIn("owns no database", out)
        self.assertIn("Redis", out)
        self.assertNotIn("holds no state to lose", out)
        self.assertNotIn("restore from the datastore's managed backup", out)
        self.assertNotIn("connection-pool metrics", out)

    def test_owns_no_database_is_read_as_an_explicit_assertion_not_inferred(self):
        """A service that owns a database must NOT take the owns-no-database branch merely
        because ownsNoDatabase is absent — the whole point of the flag is that absence means
        nothing (ADR-0196)."""
        self.write_service(datastore="postgresql", database="openbank_widget")
        self.assertFalse(self.mod.owns_no_database(self.mod.gov_facts("widget")))

    def test_owns_no_database_true_flag_wins_over_a_stateful_looking_datastore(self):
        self.write_service(datastore="Redis", owns_no_database=True)
        self.assertTrue(self.mod.owns_no_database(self.mod.gov_facts("widget")))

    def test_is_stateless_accepts_the_spellings_the_fleet_actually_uses(self):
        for value in ("none", "None", "n/a", "", "  ", "—", "-"):
            self.assertTrue(self.mod.is_stateless(value), f"{value!r} should be stateless")
        for value in ("postgresql", "PostgreSQL 18", "cassandra"):
            self.assertFalse(self.mod.is_stateless(value), f"{value!r} should be stateful")

    def test_stateful_service_keeps_the_datastore_recovery_text(self):
        self.write_service(datastore="postgresql", database="openbank_widget")
        out = self.mod.render("widget")
        self.assertIn("Disaster recovery", out)
        self.assertIn("connection-pool metrics", out)
        self.assertNotIn("no primary datastore", out)

    def test_stateful_service_without_a_backup_says_dr_is_not_achievable(self):
        """The honest case: a Postgres service whose cluster has no barmanObjectStore."""
        self.write_service(datastore="postgresql", database="openbank_widget")
        out = self.mod.render("widget")
        self.assertIn("no backup configured", out)
        self.assertIn("RPO/RTO: undefined", out)

    def test_stateful_service_with_a_backup_states_the_rpo_target(self):
        self.write_service(datastore="postgresql", database="openbank_widget")
        (self.comp / "widget-db.yaml").write_text(
            "apiVersion: postgresql.cnpg.io/v1\n"
            "kind: Cluster\n"
            "metadata:\n  name: widget-db\n  namespace: widgets\n"
            "spec:\n  backup:\n    barmanObjectStore:\n      destinationPath: s3://backups/widget\n"
        )
        out = self.mod.render("widget")
        self.assertIn("**RPO target:**", out)
        self.assertIn("barmanObjectStore", out)
        self.assertNotIn("RPO/RTO: undefined", out)

    # -- the C6 contract the readiness collector reads ----------------------
    def test_every_rendered_runbook_carries_the_disaster_recovery_heading(self):
        """prod-readiness C6=2 is detected by this exact heading — stateless included."""
        for datastore in ("none", "postgresql", "cassandra"):
            self.write_service(datastore=datastore)
            out = self.mod.render("widget")
            self.assertRegex(out, r"(?m)^#+\s*Disaster recovery")


if __name__ == "__main__":
    unittest.main()
