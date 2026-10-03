#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Unit tests for gitops_facts (run by ci.yml's governance-script step).

Covers the module-naming and money-path resolution added by #2364. The bug being guarded is
narrow and was expensive: `openbank-sepa-payment`, `openbank-sepa-instant` and
`openbank-domestic-payment` drop the `-service` suffix every other module carries, so every
helper that interpolated `openbank-{short}-service` silently answered about a directory that does
not exist. That produced a confident WRONG answer rather than an error — reading a missing
governance.yaml as an empty string made three PostgreSQL-backed payment services look stateless.
"""
from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent

RULES = """# governance rules
money_path_services:
    - openbank-ledger-service
    - openbank-sepa-payment
    - openbank-domestic-payment  # inline comment must not break the parse
    - openbank-vop-service

other_key:
    - openbank-not-money-path-service
"""


def load():
    spec = importlib.util.spec_from_file_location("gitops_facts_ut", HERE / "gitops_facts.py")
    mod = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = mod
    spec.loader.exec_module(mod)
    return mod


class GitopsFactsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.mod = load()

    def tearDown(self):
        self.tmp.cleanup()

    def write_rules(self, body=RULES):
        p = self.root / "openbank-libs" / "governance" / "rules.yaml"
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(body)

    # -- module_dir: both naming shapes ------------------------------------
    def test_module_dir_finds_the_service_suffixed_shape(self):
        (self.root / "openbank-ledger-service").mkdir()
        self.assertEqual(self.mod.module_dir("ledger", self.root).name, "openbank-ledger-service")

    def test_module_dir_finds_the_unsuffixed_shape(self):
        """The sepa-payment / sepa-instant / domestic-payment shape."""
        (self.root / "openbank-sepa-payment").mkdir()
        self.assertEqual(
            self.mod.module_dir("sepa-payment", self.root).name, "openbank-sepa-payment"
        )

    def test_module_dir_prefers_the_service_shape_when_both_exist(self):
        (self.root / "openbank-thing-service").mkdir()
        (self.root / "openbank-thing").mkdir()
        self.assertEqual(self.mod.module_dir("thing", self.root).name, "openbank-thing-service")

    def test_module_dir_falls_back_without_raising(self):
        """A caller reporting about a missing module still needs a path to name."""
        self.assertEqual(self.mod.module_dir("ghost", self.root).name, "openbank-ghost-service")

    # -- declared_datastore must follow the resolved directory -------------
    def test_declared_datastore_reads_an_unsuffixed_module(self):
        """The concrete #2364 miss: this returned '' and three PostgreSQL services read stateless."""
        d = self.root / "openbank-sepa-payment"
        d.mkdir()
        (d / "governance.yaml").write_text("primaryDatastore: PostgreSQL\nschemaName: sepa_schema\n")
        self.assertEqual(self.mod.declared_datastore("sepa-payment", self.root), "PostgreSQL")
        self.assertFalse(self.mod.is_stateless("PostgreSQL"))

    # -- money_path_services: parsed, never copied ------------------------
    def test_money_path_services_parses_both_naming_shapes(self):
        self.write_rules()
        self.assertEqual(
            self.mod.money_path_services(self.root),
            {"ledger", "sepa-payment", "domestic-payment", "vop"},
        )

    def test_money_path_services_stops_at_the_next_key(self):
        """`other_key`'s entry must not leak into the money-path set."""
        self.write_rules()
        self.assertNotIn("not-money-path", self.mod.money_path_services(self.root))

    def test_money_path_services_raises_when_the_key_is_absent(self):
        """An empty set would mark the WHOLE fleet non-money-path and relax every gate."""
        self.write_rules("some_other_key:\n    - openbank-x-service\n")
        with self.assertRaises(RuntimeError) as ctx:
            self.mod.money_path_services(self.root)
        self.assertIn("refusing to score", str(ctx.exception))

    def test_money_path_services_raises_when_the_list_is_empty(self):
        self.write_rules("money_path_services: []\n")
        with self.assertRaises(RuntimeError):
            self.mod.money_path_services(self.root)

    def test_money_path_services_raises_when_rules_yaml_is_missing(self):
        with self.assertRaises(RuntimeError):
            self.mod.money_path_services(self.root)

    # -- the live repo: the two sets must not diverge ----------------------
    def test_the_live_money_path_set_is_reachable_and_non_trivial(self):
        """Guards the wiring end to end: a regex that stops matching rules.yaml's real formatting
        would raise rather than silently shrink the set."""
        repo = HERE.parents[1]
        live = self.mod.money_path_services(repo)
        self.assertGreaterEqual(len(live), 15, f"suspiciously small money-path set: {sorted(live)}")
        for expected in ("ledger", "sepa-payment", "domestic-payment", "sepa-instant"):
            self.assertIn(expected, live)

    def test_every_live_money_path_module_resolves_to_a_real_directory(self):
        """A declared money-path module with no directory means rules.yaml and the tree disagree."""
        repo = HERE.parents[1]
        missing = [
            s for s in sorted(self.mod.money_path_services(repo))
            if not self.mod.module_dir(s, repo).is_dir()
        ]
        self.assertEqual(missing, [], f"declared money-path but no module directory: {missing}")


if __name__ == "__main__":
    unittest.main()
