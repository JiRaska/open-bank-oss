#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Falsifiable inventory and graph-union checks; no network or Gradle execution."""

import copy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    'resolver', Path(__file__).resolve().parents[1] / 'resolve-fleet-dependencies.py')
resolver = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(resolver)


def snapshot(nodes):
    return {'version': 0, 'job': {'id': '1', 'correlator': 'fleet'},
            'sha': 'a' * 40, 'ref': 'refs/heads/main', 'detector': {'version': '1.4.2'},
            'scanned': '2026-01-01T00:00:00Z',
            'manifests': {'fleet': {'name': 'fleet', 'file': {'source_location': 'settings.gradle.kts'},
                                    'resolved': nodes}}}


def node(name, relationship='direct', dependencies=None):
    return {'package_url': f'pkg:maven/example/{name}@1', 'relationship': relationship,
            'dependencies': dependencies or []}


class MergeTest(unittest.TestCase):
    def test_union_preserves_test_only_packages_edges_and_direct_relationship(self):
        first = snapshot({'runtime': node('runtime', dependencies=['shared']),
                          'shared': node('shared', 'indirect')})
        second = snapshot({'shared': node('shared', dependencies=['test-only']),
                           'test-only': node('test-only')})
        original = copy.deepcopy([first, second])
        merged = resolver.merge_snapshots([first, second])['manifests']['fleet']['resolved']
        self.assertEqual(set(merged), {'runtime', 'shared', 'test-only'})
        self.assertEqual(merged['shared']['relationship'], 'direct')
        self.assertEqual(merged['shared']['dependencies'], ['test-only'])
        self.assertEqual(merged['runtime']['dependencies'], ['shared'])
        self.assertEqual([first, second], original)

    def test_dependency_edge_union_is_order_independent(self):
        a = snapshot({'a': node('a', dependencies=['b']), 'b': node('b'), 'c': node('c')})
        b = snapshot({'a': node('a', dependencies=['c']), 'b': node('b'), 'c': node('c')})
        self.assertEqual(resolver.merge_snapshots([a, b]), resolver.merge_snapshots([b, a]))

    def test_wrong_source_or_detector_blocks(self):
        for field in ('sha', 'ref', 'job', 'detector'):
            with self.subTest(field=field):
                a = snapshot({'a': node('a')})
                b = copy.deepcopy(a)
                b[field] = 'different'
                with self.assertRaisesRegex(ValueError, 'provenance mismatch'):
                    resolver.merge_snapshots([a, b])

    def test_changed_repository_url_blocks(self):
        a = snapshot({'a': node('a')})
        b = copy.deepcopy(a)
        b['manifests']['fleet']['resolved']['a']['package_url'] += '?repository_url=other'
        with self.assertRaisesRegex(ValueError, 'inconsistent dependency identity'):
            resolver.merge_snapshots([a, b])

    def test_unknown_scope_cannot_be_silently_dropped(self):
        a = snapshot({'a': node('a')})
        b = copy.deepcopy(a)
        b['manifests']['fleet']['resolved']['a']['scope'] = 'development'
        with self.assertRaisesRegex(ValueError, 'inconsistent dependency identity or scope'):
            resolver.merge_snapshots([a, b])

    def test_dangling_edge_and_empty_graph_block(self):
        for nodes in ({}, {'a': node('a', dependencies=['missing'])}):
            with self.subTest(nodes=nodes), self.assertRaises(ValueError):
                resolver.merge_snapshots([snapshot(nodes)])
        with self.assertRaisesRegex(ValueError, 'no dependency snapshots'):
            resolver.merge_snapshots([])


class InventoryTest(unittest.TestCase):
    def setUp(self):
        self.repository = Path('/synthetic/repository')
        self.plans = [
            {'directory': str(self.repository), 'projects': [':', ':a', ':b', ':c'],
             'included': [{'name': 'build-logic', 'directory': str(self.repository / 'build-logic')}]},
            {'directory': str(self.repository / 'build-logic'), 'projects': [':'], 'included': []}]

    def test_every_project_is_covered_with_bounded_module_count(self):
        batches = resolver.project_batches(self.plans, self.repository, size=2)
        self.assertEqual(batches, [[':', ':build-logic', ':a', ':b'], [':', ':build-logic', ':c']])

    def test_new_module_cannot_disappear(self):
        self.plans[0]['projects'].append(':new-service')
        batches = resolver.project_batches(self.plans, self.repository, size=2)
        self.assertIn(':new-service', set(sum(batches, [])))

    def test_unknown_included_build_blocks(self):
        self.plans[0]['included'].append({'name': 'new-build', 'directory': '/synthetic/new-build'})
        with self.assertRaisesRegex(ValueError, 'included-build layout'):
            resolver.project_batches(self.plans, self.repository)

    def test_included_subproject_cannot_disappear(self):
        self.plans[1]['projects'].append(':new-plugin')
        with self.assertRaisesRegex(ValueError, 'included-build projects'):
            resolver.project_batches(self.plans, self.repository)

    def test_duplicate_project_inventory_blocks(self):
        self.plans[0]['projects'].append(':a')
        with self.assertRaisesRegex(ValueError, 'invalid project inventory'):
            resolver.project_batches(self.plans, self.repository)


class PublicationTest(unittest.TestCase):
    def test_failed_later_batch_never_publishes_earlier_snapshot(self):
        repository = Path(resolver.__file__).resolve().parents[2]
        plans = [
            {'directory': str(repository), 'projects': [':', *[f':module-{i}' for i in range(20)]],
             'included': [{'name': 'build-logic', 'directory': str(repository / 'build-logic')}]},
            {'directory': str(repository / 'build-logic'), 'projects': [':'], 'included': []}]
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / 'published'
            environment = {
                'DEPENDENCY_GRAPH_REPORT_DIR': str(destination),
                'GITHUB_DEPENDENCY_GRAPH_SHA': 'a' * 40,
                'GITHUB_DEPENDENCY_GRAPH_REF': 'refs/heads/main',
                'GITHUB_DEPENDENCY_GRAPH_JOB_ID': '1',
                'GITHUB_DEPENDENCY_GRAPH_JOB_CORRELATOR': 'fleet'}
            batches = []

            def run(command, *, env, **kwargs):
                if 'openbankDependencyResolutionPlan' in command:
                    target = Path(env['OPENBANK_DEPENDENCY_PLAN_DIRECTORY'])
                    target.mkdir()
                    for i, plan in enumerate(plans):
                        (target / f'{i}.json').write_text(json.dumps(plan))
                    return
                batches.append(command)
                reports = Path(env['DEPENDENCY_GRAPH_REPORT_DIR'])
                self.assertNotEqual(reports, destination)
                reports.mkdir()
                (reports / 'partial.json').write_text(json.dumps(snapshot({'a': node('a')})))
                if len(batches) == 2:
                    raise subprocess.CalledProcessError(1, command)

            with patch.dict(os.environ, environment, clear=True), \
                    patch('sys.argv', ['resolve-fleet-dependencies.py']), \
                    patch.object(resolver.subprocess, 'run', side_effect=run):
                with self.assertRaises(subprocess.CalledProcessError):
                    resolver.main()
            self.assertEqual(len(batches), 2)
            self.assertFalse(destination.exists())


if __name__ == '__main__':
    unittest.main()
