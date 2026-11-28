#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Finite replay grammar and freshness regression tests; no database required."""
import tempfile
import unittest
from pathlib import Path
import clickhouse_replay_plan as plan


class ReplayPlanTest(unittest.TestCase):
    def test_quoted_semicolon_and_comments(self):
        self.assertEqual(plan.statements("-- ignored;\nSELECT 'a;\nb', 'it''s'; /* ; */"), ["SELECT 'a;\nb', 'it''s';"])

    def test_incomplete_input_rejected(self):
        for sql in ("SELECT 1", "SELECT 'x;", "/* unfinished", "/* nested /* */ SELECT 1;"):
            with self.subTest(sql=sql), self.assertRaises(plan.PlanError):
                plan.statements(sql)

    def test_known_relations_subquery_array_join(self):
        sql = "CREATE OR REPLACE VIEW openbank_analytics.v AS SELECT * FROM (SELECT * FROM OPENBANK_ANALYTICS.t) x LEFT JOIN openbank_analytics.u u ON x.id=u.id ARRAY JOIN splitByChar(',', x.a) a;"
        self.assertEqual(plan.view_dependencies(sql), {"openbank_analytics.t", "openbank_analytics.u"})

    def test_unknown_relation_forms_rejected(self):
        for relation in ("missing_table", "other.table", "`openbank_analytics`.`t`", "numbers(10)", "openbank_analytics.t()", "openbank_analytics.t, missing", 'openbank_analytics.t AS "b", outside.secret', 'openbank_analytics.t FINAL AS b, outside.secret'):
            with self.subTest(relation=relation), self.assertRaises(plan.PlanError):
                plan.view_dependencies("CREATE OR REPLACE VIEW openbank_analytics.v AS SELECT * FROM " + relation + ";")

    def test_current_source_plan_is_fresh_and_single_statement(self):
        root = Path(__file__).resolve().parents[2]
        self.assertEqual(plan.check(root), [])
        compiled = plan.compile_plan(root)
        self.assertEqual(len(compiled), 61)
        self.assertEqual(sum(bool(plan.VIEW.match(sql)) for _, sql in compiled), 44)
        for _, sql in compiled:
            self.assertEqual(plan.statements(sql), [sql])
        self.assertTrue(any("ADD INDEX IF NOT EXISTS" in sql for _, sql in compiled))

    def test_negative_source_extensions_fail_closed(self):
        import shutil
        root = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory() as tmp:
            fixture = Path(tmp)
            shutil.copytree(root / plan.SQL_DIRECTORY, fixture / plan.SQL_DIRECTORY)
            (fixture / plan.DIRECTORY).mkdir(parents=True)
            for name in (*plan.SUPPLEMENTS, plan.OBSOLETE):
                shutil.copyfile(root / plan.DIRECTORY / name, fixture / plan.DIRECTORY / name)
            added = fixture / plan.SQL_DIRECTORY / 'V999__negative.sql'
            for sql in (
                'CREATE VIEW openbank_analytics.bad AS SELECT * FROM openbank_analytics.bad;',
                'CREATE VIEW openbank_analytics.bad AS SELECT * FROM openbank_analytics.missing;',
                'CREATE VIEW openbank_analytics.a AS SELECT * FROM openbank_analytics.b; CREATE VIEW openbank_analytics.b AS SELECT * FROM openbank_analytics.a;',
                'CREATE TABLE IF NOT EXISTS openbank_analytics.bad AS SELECT * FROM openbank_analytics.bronze_events;',
                'DROP TABLE openbank_analytics.bronze_events;',
                'ALTER TABLE openbank_analytics.bronze_events ADD COLUMN IF NOT EXISTS x UInt8, DROP COLUMN event_id;',
                'ALTER TABLE openbank_analytics.bronze_events ADD COLUMN IF NOT EXISTS x UInt8, DELETE WHERE 1;',
                'CREATE TABLE IF NOT EXISTS openbank_analytics.bad (x UInt8) ENGINE=MergeTree ORDER BY x AS WITH 1 AS x SELECT x;',
            ):
                added.write_text(sql)
                with self.subTest(sql=sql), self.assertRaises(plan.PlanError):
                    plan.compile_plan(fixture)
            added.unlink()
            obsolete = fixture / plan.DIRECTORY / plan.OBSOLETE
            obsolete.write_text(obsolete.read_text().replace('SELECT', 'SELECT 999 AS unexpected,', 1))
            with self.assertRaises(plan.PlanError):
                plan.compile_plan(fixture)


if __name__ == '__main__':
    unittest.main()
