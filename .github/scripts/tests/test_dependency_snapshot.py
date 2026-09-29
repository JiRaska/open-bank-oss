# SPDX-License-Identifier: Apache-2.0
import copy
import json
import os
import sys
import tempfile
import unittest
from contextlib import nullcontext
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import dependency_snapshot as subject

SHA = '1' * 40
IDENTITY = {'version': 0, 'sha': SHA, 'ref': 'refs/heads/example',
                'job': {'id': '123', 'correlator': 'Dependency submission-submit'},
                'detector': {'name': 'GitHub Dependency Graph Gradle Plugin', 'version': '1.4.2',
                              'url': 'https://github.com/gradle/github-dependency-graph-gradle-plugin'}}


def snapshot():
    return dict(copy.deepcopy(IDENTITY), scanned='2026-09-16T00:00:00Z', manifests={
        'example': {'name': 'example', 'file': {'source_location': 'settings.gradle.kts'}, 'resolved': {
            'a': {'package_url': 'pkg:maven/example/a@1', 'relationship': 'direct', 'dependencies': ['b']},
            'b': {'package_url': 'pkg:maven/example/b@1', 'relationship': 'indirect', 'dependencies': []},
        }}})


class MergeTests(unittest.TestCase):
    def merge(self, parts):
        return subject.merge_snapshots(parts, ['one', 'two'], IDENTITY)

    def test_merge_preserves_edges_and_direct_relationship(self):
        a, b = snapshot(), snapshot()
        b['manifests']['example']['resolved']['b']['relationship'] = 'direct'
        merged = self.merge({'one': a, 'two': b})
        self.assertEqual(merged['manifests']['example']['resolved']['a']['dependencies'], ['b'])
        self.assertEqual(merged['manifests']['example']['resolved']['b']['relationship'], 'direct')
        self.assertEqual(a, snapshot(), 'merge mutated its source')

    def test_incomplete_or_duplicate_inventory(self):
        for parts in ({}, {'one': snapshot()}, {'one': snapshot(), 'two': snapshot(), 'three': snapshot()}):
            with self.subTest(parts=list(parts)), self.assertRaises(ValueError):
                self.merge(parts)
        with self.assertRaises(ValueError):
            subject.merge_snapshots({'one': snapshot()}, ['one', 'one'], IDENTITY)

    def test_wrong_identity(self):
        for key in subject.IDENTITY:
            part = snapshot()
            part[key] = 'wrong'
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.merge({'one': snapshot(), 'two': part})

    def test_conflicting_package_or_manifest(self):
        part = snapshot()
        part['manifests']['example']['resolved']['a']['package_url'] = 'pkg:maven/different/a@1'
        with self.assertRaises(ValueError):
            self.merge({'one': snapshot(), 'two': part})
        part = snapshot()
        part['manifests']['example']['file']['source_location'] = 'different.gradle'
        with self.assertRaises(ValueError):
            self.merge({'one': snapshot(), 'two': part})

    def test_dangling_edge_and_malformed_schema(self):
        for change in ('dangling', 'edges', 'scope', 'relationship', 'empty'):
            part = snapshot()
            node = part['manifests']['example']['resolved']['a']
            if change == 'dangling':
                node['dependencies'] = ['missing']
            elif change == 'edges':
                node['dependencies'] = 'b'
            elif change == 'empty':
                part['manifests'] = {}
            else:
                node[change] = 'invalid'
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.merge({'one': snapshot(), 'two': part})

    def test_duplicate_json_fields_are_rejected(self):
        with tempfile.NamedTemporaryFile(mode='w') as stream:
            stream.write('{"sha":"first","sha":"second"}')
            stream.flush()
            with self.assertRaises(ValueError):
                subject.load_snapshot(stream.name)


class RunnerTests(unittest.TestCase):
    def test_serial_shards_obey_total_resolution_budget(self):
        self.assertEqual(subject.shard_timeout(1200, 0, 240), 240)
        self.assertEqual(subject.shard_timeout(1200, 1195, 180), 5)
        with self.assertRaisesRegex(RuntimeError, '20-minute budget'):
            subject.shard_timeout(1200, 1200, 180)

    def run_case(self, case):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for i in range(5):
                module = root / f'openbank-test-{i}'
                module.mkdir()
                (module / 'build.gradle.kts').write_text('')
            output = root / 'output'
            env = dict(os.environ, GITHUB_DEPENDENCY_GRAPH_SHA=SHA,
                       GITHUB_DEPENDENCY_GRAPH_REF=IDENTITY['ref'],
                       GITHUB_DEPENDENCY_GRAPH_JOB_ID='123',
                       GITHUB_DEPENDENCY_GRAPH_JOB_CORRELATOR=IDENTITY['job']['correlator'],
                       GITHUB_DEPENDENCY_GRAPH_WORKSPACE=str(root),
                       DEPENDENCY_GRAPH_EXCLUDE_CONFIGURATIONS='^(detachedConfiguration.*|classpath)$')
            for key in ('DEPENDENCY_GRAPH_INCLUDE_PROJECTS', 'DEPENDENCY_GRAPH_EXCLUDE_PROJECTS',
                        'DEPENDENCY_GRAPH_INCLUDE_CONFIGURATIONS'):
                env.pop(key, None)
            if case == 'wrong-checkout':
                env['GITHUB_DEPENDENCY_GRAPH_SHA'] = '2' * 40
            if case == 'filtered':
                env['DEPENDENCY_GRAPH_INCLUDE_PROJECTS'] = ':only-one'
            calls = []

            timeouts = []

            def execute(command, repo, child_env, logfile, timeout=180):
                calls.append(command)
                timeouts.append(timeout)
                if case == 'second-fails' and len(calls) == 2:
                    raise RuntimeError('producer failed')
                coverage = Path(child_env['DEPENDENCY_GRAPH_COVERAGE_DIR'])
                tasks = [arg for arg in command if arg.endswith(':ForceDependencyResolutionPlugin_resolveProjectDependencies')]
                for index, task in enumerate(tasks):
                    if case == 'missing-receipt' and index == 0:
                        continue
                    project = task.removesuffix(':ForceDependencyResolutionPlugin_resolveProjectDependencies') or ':'
                    receipt = {'version': 1, 'sha': SHA, 'project': project, 'configurations': ['runtimeClasspath']}
                    if case == 'wrong-receipt':
                        receipt['sha'] = '2' * 40
                    (coverage / f'{index}.json').write_text(json.dumps(receipt))
                reports = Path(child_env['DEPENDENCY_GRAPH_REPORT_DIR'])
                part = snapshot()
                if case == 'wrong-snapshot':
                    part['sha'] = '2' * 40
                (reports / 'snapshot.json').write_text(json.dumps(part))
                if case == 'duplicate-snapshot':
                    (reports / 'other.json').write_text(json.dumps(part))

            clock = (patch('time.monotonic', side_effect=[0, 0, 0, 0, 1190, 1190, 1190])
                     if case == 'budget-truncated' else
                     patch('time.monotonic', side_effect=[0, 0, 0, 0, 1200, 1200])
                     if case == 'budget-expired' else nullcontext())
            with patch.object(subject, 'run_bounded', side_effect=execute), \
                    patch('subprocess.check_output', return_value=SHA+'\n'), clock:
                if case in ('success', 'budget-truncated'):
                    result = subject.generate(root, output, env)
                    self.assertEqual(result['sha'], SHA)
                    self.assertEqual(len(calls), 2)
                    self.assertEqual(timeouts, [240, 10] if case == 'budget-truncated' else [240, 180])
                    self.assertTrue((output / 'merged.json').is_file())
                    self.assertTrue(all('--continue' not in cmd for cmd in calls))
                else:
                    with self.assertRaises((ValueError, RuntimeError)):
                        subject.generate(root, output, env)
                    self.assertFalse((output / 'merged.json').exists())
                    if case == 'budget-expired':
                        self.assertEqual(len(calls), 1)
                        self.assertEqual(timeouts, [240])

    def test_real_failed_process_is_not_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaisesRegex(RuntimeError, r'failed \(7\)'):
                subject.run_bounded([sys.executable, '-c', 'raise SystemExit(7)'],
                                    root, os.environ, root / 'failed.log')

    def test_real_timeout_stops_owned_process(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaisesRegex(RuntimeError, 'timed out'):
                subject.run_bounded([sys.executable, '-c', 'import time; time.sleep(60)'],
                                    root, os.environ, root / 'timeout.log', timeout=0.05)

    def test_complete_generation(self):
        self.run_case('success')

    def test_serial_shards_share_one_deadline(self):
        self.run_case('budget-truncated')
        self.run_case('budget-expired')

    def test_failed_or_incomplete_generation_never_publishes_candidate(self):
        for case in ('second-fails', 'wrong-checkout', 'filtered', 'missing-receipt',
                     'wrong-receipt', 'wrong-snapshot', 'duplicate-snapshot'):
            with self.subTest(case=case):
                self.run_case(case)

    def test_workflow_only_submits_after_generator(self):
        source = (Path(__file__).resolve().parents[2] / 'workflows/dependency-submission.yml').read_text()
        self.assertIn('dependency-graph: generate\n', source)
        self.assertNotIn('dependency-graph: generate-and-submit', source)
        self.assertIn('ref: ${{ github.event.pull_request.head.sha || github.sha }}', source)
        self.assertLess(source.index('name: Resolve and validate fleet'), source.index('name: Submit complete fleet'))
        submission = source.split('name: Submit complete fleet snapshot', 1)[1].split('\n      - name:', 1)[0]
        self.assertNotIn('always()', submission)
        self.assertNotIn('continue-on-error', submission)
        self.assertIn('/merged.json', submission)


if __name__ == '__main__':
    unittest.main()
