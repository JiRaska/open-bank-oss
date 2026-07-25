#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Unit tests for prod-readiness-collector.py's repaired scorers (issue #2255).

The collector had never had a test, and four of its nine scorers were answering a different
question from the one the dimension asks. Each test below is written so it FAILS against the
scorer as it stood:

* **C1** required a FILE NAME containing `Port`, so anacredit and onboarding — both carrying a
  textbook `application/port/{in,out}` package with files named `*Repository.kt` / `*UseCases.kt`
  — scored as having no hexagonal ports at all.
* **C4** returned a hardcoded 1 for "no flyway (stateless?)", making 2 UNREACHABLE for a service
  that correctly has no datastore, and hiding the opposite case: copilot declares
  `primaryDatastore: PostgreSQL` and ships no migration, no entity and no datasource config.
* **C5** scored 0 ("no CNPG cluster") for a service that must not have one.
* **C8** asked `short in read(podmonitor.yaml)` — a substring match over the whole file,
  COMMENTS INCLUDED. A false comment scored sdd as scraped while its metrics reached nothing,
  and six genuinely-scraped services scored as unscraped because their names appear nowhere in
  that file.

The scorers are pure functions of the tree, so every case here is a fixture tree, never the real
repo: a test that reads the live fleet passes or fails for reasons that have nothing to do with
the code under test.
"""
from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent

PODMONITOR = """apiVersion: monitoring.coreos.com/v1
kind: PodMonitor
metadata:
  name: openbank-services
  namespace: observability
spec:
  namespaceSelector:
    matchNames:
      - payments
      - platform
  podMetricsEndpoints:
    - port: management
"""

# The comment is the trap: it names a service that is NOT covered. The old C8 scorer matched the
# file as a plain substring, so this sentence alone was enough to score `sdd` as scraped.
PODMONITOR_WITH_LYING_COMMENT = """# sdd-service is covered via `payments`.
""" + PODMONITOR

WORKLOAD = """apiVersion: apps/v1
kind: Deployment
metadata:
  name: {short}-service
  namespace: {ns}
spec:
  template:
    spec:
      containers:
        - name: {short}-service
          image: ghcr.io/jiraska/openbank-{short}-service:1.0.0
"""

GOVERNANCE = """dataDomain: platform
primaryDatastore: {datastore}
schemaName: {schema}
dataClassification: internal
retentionPolicy: 1 year
dataLineageRole: consumer
"""


def load_collector(repo: Path):
    spec = importlib.util.spec_from_file_location(
        "prod_readiness_collector", HERE / "prod-readiness-collector.py"
    )
    mod = importlib.util.module_from_spec(spec)
    # The collector defines a @dataclass, and dataclasses resolves annotations through
    # sys.modules[cls.__module__] — so the module must be registered BEFORE exec_module or
    # every import raises AttributeError on a None module.
    sys.modules[spec.name] = mod
    spec.loader.exec_module(mod)
    mod.REPO = repo
    mod.GITOPS = repo / "openbank-infra" / "gitops"
    mod.THREAT_MODELS = repo / "docs" / "threat-models"
    mod.RUNBOOKS = repo / "docs" / "runbooks"
    mod.ATTESTATIONS = repo / "openbank-libs" / "governance" / "attestations.yaml"
    mod.RELEASE_EVIDENCE = repo / ".github" / "workflows" / "release-please.yml"
    mod.VEX_DIR = repo / "openbank-libs" / "governance" / "vex"
    return mod


class CollectorScorerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.comp = self.root / "openbank-infra" / "gitops" / "components" / "widget"
        self.comp.mkdir(parents=True)
        obs = self.root / "openbank-infra" / "gitops" / "components" / "observability"
        obs.mkdir(parents=True)
        (obs / "podmonitor-openbank-services.yaml").write_text(PODMONITOR)
        self.svc = self.root / "openbank-widget-service"
        (self.svc / "src" / "main" / "kotlin" / "com" / "openbank" / "widget").mkdir(parents=True)
        self.mod = load_collector(self.root)

    def tearDown(self):
        self.tmp.cleanup()

    # -- fixture helpers ----------------------------------------------------
    def governance(self, datastore="none", schema="n/a"):
        (self.svc / "governance.yaml").write_text(
            GOVERNANCE.format(datastore=datastore, schema=schema)
        )

    def deploy(self, ns="payments"):
        (self.comp / "widget-service.yaml").write_text(WORKLOAD.format(short="widget", ns=ns))

    def kt(self, relative: str, body: str = "class X\n"):
        p = self.svc / "src" / "main" / "kotlin" / "com" / "openbank" / "widget" / relative
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(body)

    def migration(self, name="V1__create_widgets.sql", body="CREATE TABLE widgets (id UUID);\n"):
        p = self.svc / "src" / "main" / "resources" / "db" / "migration" / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(body)

    # -- C1: ports live in the package, not the filename --------------------
    def test_c1_counts_an_application_port_package(self):
        """anacredit/onboarding shape: a port package whose files are not named *Port*."""
        self.governance()
        for i in range(10):
            self.kt(f"domain/Model{i}.kt")
        self.kt("application/port/out/WidgetRepository.kt")
        self.kt("application/port/in/WidgetUseCases.kt")
        score, evidence = self.mod.score_c1_code("widget", {}, "2026-07-25")
        self.assertEqual(score, 2, evidence)
        self.assertIn("ports=y", evidence)

    def test_c1_still_counts_the_port_suffix_naming(self):
        """The other half of the fleet names the file itself *Port*.kt — both must count."""
        self.governance()
        for i in range(10):
            self.kt(f"domain/Model{i}.kt")
        self.kt("application/LedgerPort.kt")
        score, _ = self.mod.score_c1_code("widget", {}, "2026-07-25")
        self.assertEqual(score, 2)

    def test_c1_a_service_with_no_ports_at_all_still_scores_one(self):
        """agent/copilot shape — the fix must not hand out a 2 for free."""
        self.governance()
        for i in range(10):
            self.kt(f"domain/Model{i}.kt")
        self.kt("application/WidgetService.kt")
        score, evidence = self.mod.score_c1_code("widget", {}, "2026-07-25")
        self.assertEqual(score, 1, evidence)
        self.assertIn("ports=n", evidence)

    def test_c1_a_skeleton_is_still_a_skeleton(self):
        self.governance()
        self.kt("application/port/out/WidgetRepository.kt")
        score, evidence = self.mod.score_c1_code("widget", {}, "2026-07-25")
        self.assertEqual(score, 1)
        self.assertIn("skeleton", evidence)

    # -- C4: the data dimension of a service with no data -------------------
    def test_c4_stateless_service_is_not_penalised_for_having_no_migration(self):
        self.governance(datastore="none")
        score, evidence = self.mod.score_c4_data("widget", {}, "2026-07-25")
        self.assertEqual(score, 2, evidence)
        self.assertIn("no datastore", evidence)

    def test_c4_declared_datastore_with_no_migration_is_a_finding(self):
        """copilot's real state: governance.yaml says PostgreSQL, no migration exists."""
        self.governance(datastore="PostgreSQL", schema="widget_schema")
        score, evidence = self.mod.score_c4_data("widget", {}, "2026-07-25")
        self.assertEqual(score, 1, evidence)
        self.assertIn("no Flyway migration", evidence)

    def test_c4_declared_stateless_but_migrations_exist_is_a_contradiction(self):
        """Neither fact can be trusted, so this must not silently pass as stateless."""
        self.governance(datastore="none")
        self.migration()
        score, evidence = self.mod.score_c4_data("widget", {}, "2026-07-25")
        self.assertEqual(score, 1, evidence)
        self.assertIn("CONTRADICTION", evidence)

    def test_c4_migration_without_a_rollback_note_still_scores_one(self):
        self.governance(datastore="PostgreSQL", schema="widget_schema")
        self.migration()
        score, evidence = self.mod.score_c4_data("widget", {}, "2026-07-25")
        self.assertEqual(score, 1, evidence)
        self.assertIn("rollback_note=n", evidence)

    def test_c4_migration_with_a_rollback_note_scores_two(self):
        self.governance(datastore="PostgreSQL", schema="widget_schema")
        self.migration(body="-- rollback: DROP TABLE widgets;\nCREATE TABLE widgets (id UUID);\n")
        score, evidence = self.mod.score_c4_data("widget", {}, "2026-07-25")
        self.assertEqual(score, 2, evidence)

    # -- C5: backups for a service with nothing to back up ------------------
    def test_c5_stateless_service_needs_no_backup(self):
        self.governance(datastore="none")
        score, evidence = self.mod.score_c5_backup("widget", {}, "2026-07-25")
        self.assertEqual(score, 2, evidence)
        self.assertIn("nothing to back up", evidence)

    def test_c5_stateful_service_with_no_cluster_still_scores_zero(self):
        self.governance(datastore="PostgreSQL", schema="widget_schema")
        score, evidence = self.mod.score_c5_backup("widget", {}, "2026-07-25")
        self.assertEqual(score, 0, evidence)

    # -- C8: scraped is a fact about the namespace --------------------------
    def test_c8_service_in_a_scraped_namespace_counts_as_scraped(self):
        """ap2/mcp (platform) and vop/settlement (payments) shape."""
        self.governance()
        self.deploy(ns="payments")
        self.kt("infrastructure/Metrics.kt", "import io.micrometer.core.instrument.MeterRegistry\n")
        score, evidence = self.mod.score_c8_observability("widget", {}, "2026-07-25")
        self.assertEqual(score, 2, evidence)
        self.assertIn("scraped (ns payments)", evidence)

    def test_c8_namespace_absent_from_matchnames_is_not_scraped(self):
        self.governance()
        self.deploy(ns="widgets")
        self.kt("infrastructure/Metrics.kt", "import io.micrometer.core.instrument.MeterRegistry\n")
        score, evidence = self.mod.score_c8_observability("widget", {}, "2026-07-25")
        self.assertEqual(score, 1, evidence)
        self.assertIn("absent from PodMonitor matchNames", evidence)

    def test_c8_a_comment_claiming_coverage_does_not_make_it_scraped(self):
        """The sdd bug: prose in the PodMonitor must carry no weight whatsoever."""
        obs = self.root / "openbank-infra" / "gitops" / "components" / "observability"
        (obs / "podmonitor-openbank-services.yaml").write_text(
            PODMONITOR_WITH_LYING_COMMENT.replace("sdd-service", "widget-service")
        )
        self.governance()
        self.deploy(ns="widgets")
        self.kt("infrastructure/Metrics.kt", "import io.micrometer.core.instrument.MeterRegistry\n")
        score, evidence = self.mod.score_c8_observability("widget", {}, "2026-07-25")
        self.assertEqual(score, 1, evidence)
        self.assertIn("absent from PodMonitor matchNames", evidence)

    def test_c8_scraped_but_uninstrumented_names_the_missing_half(self):
        """anacredit/ap2/finrep/mcp/vop: scraped, zero domain metrics. Different work."""
        self.governance()
        self.deploy(ns="payments")
        score, evidence = self.mod.score_c8_observability("widget", {}, "2026-07-25")
        self.assertEqual(score, 1, evidence)
        self.assertIn("NO domain metrics", evidence)
        self.assertIn("scraped (ns payments)", evidence)

    def test_c8_undeployed_service_says_so(self):
        self.governance()
        score, evidence = self.mod.score_c8_observability("widget", {}, "2026-07-25")
        self.assertEqual(score, 0, evidence)
        self.assertIn("not deployed", evidence)


if __name__ == "__main__":
    unittest.main()
