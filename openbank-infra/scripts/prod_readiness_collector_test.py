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

    # -- the scored population and the money-path set (#2364) --------------
    def write_rules(self, entries=("openbank-ledger-service", "openbank-sepa-payment")):
        p = self.root / "openbank-libs" / "governance" / "rules.yaml"
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text("money_path_services:\n" + "".join(f"    - {e}\n" for e in entries))

    def test_all_services_includes_a_money_path_module_the_glob_would_miss(self):
        """openbank-sepa-payment has no `-service` suffix, so the glob alone dropped it — and with
        it every headline the matrix printed (#2364)."""
        self.write_rules()
        (self.root / "openbank-ledger-service").mkdir(exist_ok=True)
        (self.root / "openbank-sepa-payment").mkdir(exist_ok=True)
        services = self.mod.all_services()
        self.assertIn("sepa-payment", services)
        self.assertIn("ledger", services)

    def test_all_services_skips_a_declared_module_with_no_directory(self):
        self.write_rules(("openbank-ledger-service", "openbank-ghost-service"))
        (self.root / "openbank-ledger-service").mkdir(exist_ok=True)
        self.assertNotIn("ghost", self.mod.all_services())

    def test_money_path_comes_from_rules_yaml_not_a_literal(self):
        """The hardcoded set named 14 while rules.yaml declared 20, so six declared money-path
        services were gated leniently and read GO."""
        self.write_rules(("openbank-widget-service",))
        self.assertEqual(self.mod.money_path(), {"widget"})

    def test_money_path_is_read_at_call_time_not_import_time(self):
        """A module-level constant would ignore a rebound REPO and raise on a rules-less tree."""
        self.write_rules(("openbank-alpha-service",))
        self.assertEqual(self.mod.money_path(), {"alpha"})
        self.write_rules(("openbank-beta-service",))
        self.assertEqual(self.mod.money_path(), {"beta"})

    def test_a_money_path_service_needs_three_on_the_critical_cells(self):
        """The stricter gate is what the six leniently-scored services were missing."""
        self.write_rules(("openbank-widget-service",))
        # DEPLOYED on purpose: an undeployed service takes the NOT-DEPLOYED verdict instead of
        # NO-GO, so without this the test would assert the wrong branch (#5706).
        self.deploy()
        r = self.mod.ServiceReadiness(service="widget", money_path=True)
        r.scores = {c: 2 for c, _ in self.mod.DIMENSIONS}
        r.compute_gate()
        self.assertEqual(r.gate, "NO-GO", "all-2 must not clear the money-path gate")
        for critical in ("C1", "C5", "C7"):
            r.scores[critical] = 3
        r.compute_gate()
        self.assertEqual(r.gate, "GO")

    def test_a_non_money_path_service_clears_at_all_twos(self):
        self.deploy()
        r = self.mod.ServiceReadiness(service="widget", money_path=False)
        r.scores = {c: 2 for c, _ in self.mod.DIMENSIONS}
        r.compute_gate()
        self.assertEqual(r.gate, "GO")

    # -- the third verdict: a released component with no workload (#5706, #5760) -----------
    def test_a_service_with_no_gitops_workload_is_reported_as_not_deployed(self):
        """tax-reporting sat in the NO-GO column beside 25 services that ARE deployed and failing
        a control, so its actual blocker — an undecided deployment — was invisible."""
        self.write_rules(("openbank-ledger-service",))
        r = self.mod.ServiceReadiness(service="widget", money_path=False)
        r.scores = {c: 2 for c, _ in self.mod.DIMENSIONS}
        r.scores["C5"] = 0
        r.compute_gate()
        self.assertEqual(r.gate, "NOT-DEPLOYED")

    def test_not_deployed_never_replaces_a_GO(self):
        """The falsification that matters: the new verdict must be reachable ONLY where the old
        code said NO-GO. A scorer that can no longer say NO-GO is worse than one that says it
        wrongly, so this asserts the same scores still fail for a DEPLOYED service and that a
        passing scorecard is unaffected by deployment state."""
        self.write_rules(("openbank-ledger-service",))
        failing = {c: 2 for c, _ in self.mod.DIMENSIONS} | {"C5": 0}
        undeployed = self.mod.ServiceReadiness(service="widget", money_path=False)
        undeployed.scores = dict(failing)
        undeployed.compute_gate()
        self.deploy()
        deployed = self.mod.ServiceReadiness(service="widget", money_path=False)
        deployed.scores = dict(failing)
        deployed.compute_gate()
        self.assertEqual((undeployed.gate, deployed.gate), ("NOT-DEPLOYED", "NO-GO"))
        # And an all-clear scorecard is GO either way — the branch is only ever the failure one.
        passing = self.mod.ServiceReadiness(service="widget", money_path=False)
        passing.scores = {c: 2 for c, _ in self.mod.DIMENSIONS}
        passing.compute_gate()
        self.assertEqual(passing.gate, "GO")

    def test_c5_evidence_names_the_missing_workload_not_a_missing_backup(self):
        """`no CNPG cluster` sent the reader to the backup docs for a service whose whole workload
        is absent. The SCORE stays 0 — this is about what the 0 says, not about passing."""
        self.governance(datastore="PostgreSQL", schema="widget")
        score, evidence = self.mod.score_c5_backup("widget", {}, "2026-08-20")
        self.assertEqual(score, 0, "an undeployed stateful service must not score above Absent")
        self.assertIn("no gitops workload", evidence)

    def test_threat_model_is_looked_up_under_the_resolved_module_name(self):
        """C7 read docs/threat-models/openbank-sepa-payment-service.md — a file that cannot exist."""
        (self.root / "openbank-sepa-payment").mkdir(exist_ok=True)
        tm = self.root / "docs" / "threat-models"
        tm.mkdir(parents=True, exist_ok=True)
        (tm / "openbank-sepa-payment.md").write_text("# threat model\n")
        self.mod.THREAT_MODELS = tm
        score, evidence = self.mod.score_c7_security("sepa-payment", {}, "2026-07-25")
        # NOT `assertIn("threat-model", evidence)`: the zero-score evidence string is
        # "no threat-model/netpol/sectest/provenance", which contains that substring, so the
        # loose assertion passes against the unfixed code. Same substring trap this whole sweep
        # has been pulling out of the scorers — assert the positive form.
        self.assertNotIn("no threat-model", evidence, "C7 found no threat model at all")
        self.assertTrue(
            evidence.startswith("threat-model") or ", threat-model" in evidence,
            f"threat-model not listed as present: {evidence!r}",
        )

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

    # -- C3: a contract test is an artifact, not a word in a comment --------
    def openapi(self):
        p = self.svc / "src" / "main" / "resources" / "openapi.yaml"
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text("openapi: 3.0.3\ninfo:\n  title: Widget\n  version: 1.0.0\npaths: {}\n")

    def write_test_kt(self, name: str, body: str):
        p = self.svc / "src" / "test" / "kotlin" / "com" / "openbank" / "widget" / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(body)

    def test_c3_the_word_contract_in_a_comment_is_not_a_contract_test(self):
        """The exact line that scored pid, psd2 and sanctions as Verified."""
        self.governance()
        self.openapi()
        self.write_test_kt(
            "WidgetServiceTest.kt",
            "// the mark-and-sweep reconciliation contract\nclass WidgetServiceTest\n",
        )
        score, evidence = self.mod.score_c3_api("widget", {}, "2026-07-25")
        self.assertEqual(score, 1, evidence)
        self.assertIn("NO contract test", evidence)

    def test_c3_the_word_contract_in_kdoc_is_not_a_contract_test(self):
        """psd2's actual hit was a KDoc line, not a `//` comment."""
        self.governance()
        self.openapi()
        self.write_test_kt(
            "KafkaOutboxPublisherTest.kt",
            "/**\n * exactly as the shared header contract prescribes.\n */\nclass K\n",
        )
        score, evidence = self.mod.score_c3_api("widget", {}, "2026-07-25")
        self.assertEqual(score, 1, evidence)

    def test_c3_a_pact_import_is_a_contract_test(self):
        self.governance()
        self.openapi()
        self.write_test_kt(
            "WidgetLedgerPactConsumerTest.kt",
            "import au.com.dius.pact.consumer.junit5.PactConsumerTestExt\nclass W\n",
        )
        score, evidence = self.mod.score_c3_api("widget", {}, "2026-07-25")
        self.assertEqual(score, 2, evidence)
        self.assertIn("pact test", evidence)

    def test_c3_the_fleet_test_class_naming_is_a_contract_test(self):
        """A spec-conformance test named *ContractTest.kt counts without the pact library."""
        self.governance()
        self.openapi()
        self.write_test_kt("WidgetApiContractTest.kt", "class WidgetApiContractTest\n")
        score, evidence = self.mod.score_c3_api("widget", {}, "2026-07-25")
        self.assertEqual(score, 2, evidence)
        self.assertIn("by naming", evidence)

    def test_c3_no_openapi_still_scores_zero(self):
        self.governance()
        self.write_test_kt("WidgetPactConsumerTest.kt", "import au.com.dius.pact.consumer.X\nclass W\n")
        score, evidence = self.mod.score_c3_api("widget", {}, "2026-07-25")
        self.assertEqual(score, 0, evidence)

    def test_c3_reports_committed_pacts_as_extra_evidence(self):
        self.governance()
        self.openapi()
        self.write_test_kt(
            "WidgetPactConsumerTest.kt", "import au.com.dius.pact.consumer.X\nclass W\n"
        )
        pacts = self.root / "pacts"
        pacts.mkdir(parents=True, exist_ok=True)
        (pacts / "openbank-widget-service-openbank-ledger-service.json").write_text("{}")
        (pacts / "openbank-other-service-openbank-thing-service.json").write_text("{}")
        score, evidence = self.mod.score_c3_api("widget", {}, "2026-07-25")
        self.assertEqual(score, 2, evidence)
        self.assertIn("1 committed pact", evidence)

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
