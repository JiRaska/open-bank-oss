#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Offline result, partial-rerun and generated-capacity controls; no runner execution."""
import copy
import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('results', Path(__file__).with_name('check-service-build-results.py'))
results = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(results)
graph = results.service_jobs
MODULES = ['openbank-a', 'openbank-b']


def fixture(selected=None, event='pull_request', ref='refs/pull/1/merge', modules=None):
    modules = MODULES if modules is None else modules
    selected = modules if selected is None else selected
    lane = 'pr' if event == 'pull_request' else 'other'
    needs = {'changes': {'result': 'success', 'outputs': {'any': 'true' if selected else 'false'}}}
    for branch in graph.LANES:
        for module in modules:
            active = branch == lane and module in selected
            needs[graph.job_id(module, branch)] = {
                'result': 'success' if active else 'skipped',
                'outputs': {'platform_results': json.dumps({
                    'build': {'result': 'success', 'outputs': {}},
                    'contract': {'result': 'success' if event in {'push', 'workflow_dispatch'} and ref == 'refs/heads/main' else 'skipped', 'outputs': {}},
                    'publish-evidence': {'result': 'success' if event == 'push' and ref == 'refs/heads/main' else 'skipped', 'outputs': {}},
                })} if active else {},
            }
    return needs


def change_result(needs, module, lane, job, value):
    outputs = needs[graph.job_id(module, lane)]['outputs']
    body = json.loads(outputs['platform_results'])
    body[job]['result'] = value
    outputs['platform_results'] = json.dumps(body)


class ResultsTest(unittest.TestCase):
    def check(self, needs, selected=None, event='pull_request', ref='refs/pull/1/merge'):
        return results.verify(MODULES if selected is None else selected, needs, event, ref, MODULES)

    def test_cancelled_before_runner_rejects_even_when_wrapper_says_success(self):
        needs = fixture()
        change_result(needs, MODULES[0], 'pr', 'build', 'cancelled')
        self.assertTrue(self.check(needs))

    def test_missing_output_never_uses_old_success_artifact(self):
        needs = fixture()
        needs[graph.job_id(MODULES[0], 'pr')]['outputs'] = {}
        self.assertTrue(self.check(needs))

    def test_all_non_success_selected_results_reject(self):
        for value in ['failure', 'cancelled', 'skipped', '', None]:
            for field in ['wrapper', 'build']:
                with self.subTest(value=value, field=field):
                    needs = fixture()
                    record = needs[graph.job_id(MODULES[0], 'pr')]
                    if field == 'wrapper':
                        record['result'] = value
                    else:
                        change_result(needs, MODULES[0], 'pr', 'build', value)
                    self.assertTrue(self.check(needs))

    def test_missing_foreign_and_malformed_rosters_reject(self):
        needs = fixture()
        del needs[graph.job_id(MODULES[0], 'pr')]
        self.assertTrue(self.check(needs))
        needs = fixture()
        needs['build_pr_foreign'] = {'result': 'success'}
        self.assertTrue(self.check(needs))
        self.assertTrue(self.check([]))

    def test_invalid_selected_scope_rejects(self):
        for selected in ['a', ['openbank-foreign'], [MODULES[0], MODULES[0]], [{}]]:
            self.assertTrue(self.check(fixture(), selected))
        needs = fixture()
        needs['changes']['outputs']['any'] = 'false'
        self.assertTrue(self.check(needs))
        needs['changes']['result'] = 'failure'
        self.assertTrue(self.check(needs))

    def test_empty_valid_scope_passes_but_selected_skip_does_not(self):
        self.assertEqual(self.check(fixture([]), []), [])
        self.assertTrue(self.check(fixture([])))

    def test_exact_results_pass(self):
        self.assertEqual(self.check(fixture()), [])

    def test_partial_rerun_carries_forward_authoritative_success(self):
        # GitHub's needs context resolves both carried-forward and retried direct results.
        # No attempt-number filter rejects the valid carried-forward dependency.
        old = fixture()
        retried = copy.deepcopy(old)
        retried[graph.job_id(MODULES[1], 'pr')] = fixture()[graph.job_id(MODULES[1], 'pr')]
        self.assertEqual(self.check(retried), [])
        retried[graph.job_id(MODULES[1], 'pr')]['result'] = 'cancelled'
        self.assertTrue(self.check(retried))

    def test_main_contract_required_and_other_events_keep_skip(self):
        for event, ref, required in [('push', 'refs/heads/main', True),
                                     ('workflow_dispatch', 'refs/heads/main', True),
                                     ('schedule', 'refs/heads/main', False),
                                     ('workflow_dispatch', 'refs/heads/feature', False)]:
            needs = fixture(event=event, ref=ref)
            self.assertEqual(self.check(needs, event=event, ref=ref), [])
            change_result(needs, MODULES[0], 'other', 'contract', 'skipped' if required else 'success')
            self.assertTrue(self.check(needs, event=event, ref=ref))

    def test_inactive_branch_and_unselected_callers_must_skip(self):
        needs = fixture([MODULES[0]])
        self.assertEqual(self.check(needs, [MODULES[0]]), [])
        needs[graph.job_id(MODULES[1], 'pr')]['result'] = 'success'
        self.assertTrue(self.check(needs, [MODULES[0]]))
        needs = fixture()
        needs[graph.job_id(MODULES[0], 'other')]['result'] = 'success'
        self.assertTrue(self.check(needs))

    def test_duplicate_json_fails_closed(self):
        with self.assertRaises(ValueError):
            json.loads('{"result":"failure","result":"success"}', object_pairs_hook=results.unique_object)

    def test_unknown_event_rejects(self):
        self.assertTrue(self.check(fixture(), event='unknown'))

    def test_negative_control_detects_removed_build_result_check(self):
        text = Path(results.__file__).read_text().replace("if jobs['build']['result'] != 'success':", 'if False:')
        self.assertNotEqual(text, Path(results.__file__).read_text())
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'mutant.py'
            path.write_text(text)
            shutil.copyfile(graph.__file__, Path(tmp) / 'generate-service-ci-jobs.py')
            spec = importlib.util.spec_from_file_location('mutant', path)
            mutant = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(mutant)
            needs = fixture()
            change_result(needs, MODULES[0], 'pr', 'build', 'cancelled')
            self.assertEqual(mutant.verify(MODULES, needs, 'pull_request', 'refs/pull/1/merge', MODULES), [])
            self.assertTrue(self.check(needs))

    def test_platform_schema_rejects_unknown_jobs_states_and_child_outputs(self):
        for mutation in ['foreign-job', 'private-output', 'unknown-state', 'duplicate-json', 'not-object']:
            needs = fixture()
            outputs = needs[graph.job_id(MODULES[0], 'pr')]['outputs']
            jobs = json.loads(outputs['platform_results'])
            if mutation == 'foreign-job':
                jobs['foreign'] = {'result': 'success', 'outputs': {}}
            elif mutation == 'private-output':
                jobs['build']['outputs'] = {'private': 'canary-must-not-log'}
            elif mutation == 'unknown-state':
                jobs['build']['result'] = 'unknown'
            outputs['platform_results'] = json.dumps(jobs)
            if mutation == 'duplicate-json':
                outputs['platform_results'] = '{"build":{},"build":{}}'
            elif mutation == 'not-object':
                outputs['platform_results'] = '[]'
            self.assertTrue(self.check(needs))


class GraphTest(unittest.TestCase):
    def test_inactive_names_do_not_classify_small_scope_as_full_fleet(self):
        spec = importlib.util.spec_from_file_location('updater', graph.ROOT / '.github/scripts/auto-update-armed-prs.py')
        updater = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(updater)
        modules = graph.inventory(graph.ROOT)
        checks = {f'Not selected (pr, {module})': 'skipped' for module in modules[1:]}
        checks[f'build ({modules[0]}) / {modules[0]} (build)'] = 'success'
        self.assertEqual(updater.classify(checks, 20), ('small', False))
        unsafe = {f'build ({module})': 'skipped' for module in modules}
        self.assertEqual(updater.classify(unsafe, 20), ('full-fleet', False))

    def test_callee_output_guard_rejects_sensitive_child_output(self):
        graph.validate_callee(graph.ROOT)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            path = root / '.github/workflows/_service-ci.yml'
            path.parent.mkdir(parents=True)
            import yaml
            document = yaml.safe_load((graph.ROOT / '.github/workflows/_service-ci.yml').read_text())
            document['jobs']['build']['outputs'] = {'sensitive': '${{ steps.x.outputs.value }}'}
            path.write_text(yaml.safe_dump(document))
            with self.assertRaises(ValueError):
                graph.validate_callee(root)

    def test_finite_caller_permissions_match_reviewed_baseline(self):
        import yaml
        spec = importlib.util.spec_from_file_location(
            'supply_chain', Path(__file__).with_name('check-workflow-supply-chain.py'))
        policy = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(policy)
        modules = graph.inventory(graph.ROOT)
        jobs = yaml.safe_load(graph.caller_block(modules))
        for job in jobs.values():
            self.assertEqual(job['permissions'], {'contents': 'read', 'id-token': 'write'})
        expected = {line for line in policy.WRITE_BASELINE.read_text().splitlines()
                    if line.startswith('services-ci.yml|job:build')}
        actual = policy.write_grants('services-ci.yml', {'jobs': jobs})
        self.assertEqual(len(expected), 2 * len(modules))
        self.assertEqual(policy.grant_drift(actual, expected), [])
        unexpected = 'services-ci.yml|job:foreign|id-token'
        self.assertEqual(policy.grant_drift(actual | {unexpected}, expected),
                         [f'unexpected workflow write grant: {unexpected}'])
        removed = sorted(actual)[0]
        self.assertEqual(policy.grant_drift(actual - {removed}, expected),
                         [f'stale workflow write baseline entry: {removed}'])
        expanded = yaml.safe_load(graph.caller_block(modules + ['openbank-zz-fixture']))
        new_grants = policy.write_grants('services-ci.yml', {'jobs': expanded})
        self.assertEqual(len(policy.grant_drift(new_grants, expected)), 2)
        changed = copy.deepcopy(jobs)
        changed[next(iter(changed))]['permissions']['contents'] = 'write'
        self.assertEqual(len(policy.grant_drift(
            policy.write_grants('services-ci.yml', {'jobs': changed}), expected)), 1)

    def test_full_canonical_inventory(self):
        modules = graph.inventory(graph.ROOT)
        self.assertEqual(results.verify(modules, fixture(modules=modules), 'pull_request', 'refs/pull/1/merge', modules), [])

    def test_generator_new_module_and_repeat_controls(self):
        self.assertEqual(graph.self_test(), 0)

    def test_fixed_lane_graph_preserves_caps_and_status_overrides(self):
        import yaml
        modules = graph.inventory(graph.ROOT)
        jobs = yaml.safe_load(graph.caller_block(modules))
        self.assertEqual(len(jobs), 2 * len(modules))
        for branch, width in graph.LANES.items():
            for i, module in enumerate(modules):
                job = jobs[graph.job_id(module, branch)]
                self.assertIn('!cancelled()', job['if'])
                self.assertEqual(job['with']['service'], module)
                wanted = ['changes', 'publication-ready'] + ([graph.job_id(modules[i-width], branch)] if i >= width else [])
                self.assertEqual(job['needs'], wanted)
                self.assertIn("|| 'Not selected (", job['name'])
                self.assertNotIn('strategy', job)
            # Every edge is exactly one earlier whole caller in its lane: even sparse
            # selections and failed/skipped predecessors cannot create a second slot.
            chains = [[modules[i] for i in range(start, len(modules), width)] for start in range(width)]
            self.assertEqual(sorted(m for chain in chains for m in chain), modules)
            for selected in [set(modules), set(modules[::2]), {modules[-1]}, set()]:
                self.assertLessEqual(sum(any(m in selected for m in chain) for chain in chains), width)


if __name__ == '__main__':
    unittest.main()
