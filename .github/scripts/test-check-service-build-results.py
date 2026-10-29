#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Offline result, partial-rerun and generated-capacity controls; no runner execution."""
import copy
import importlib.util
import json
import shutil
import tempfile
import unittest
from contextlib import contextmanager
from pathlib import Path
from typing import ClassVar

SPEC = importlib.util.spec_from_file_location('results', Path(__file__).with_name('check-service-build-results.py'))
results = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(results)
graph = results.service_jobs
MODULES = ['openbank-a', 'openbank-b']


def fixture(selected=None, event='pull_request', ref='refs/pull/1/merge', modules=None):
    modules = MODULES if modules is None else modules
    selected = modules if selected is None else selected
    lane = 'pr' if event == 'pull_request' else 'other'
    needs = {'changes': {'result': 'success', 'outputs': {
        'any': 'true' if selected else 'false', 'renderer-changed': 'false'}},
        'renderer-tests': {'result': 'skipped'}}
    for branch in graph.LANES:
        for module in modules:
            active = branch == lane and module in selected
            needs[graph.job_id(module, branch)] = {
                'result': 'success' if active else 'skipped',
                'outputs': {'platform_results': json.dumps({name: result for name, result in {
                    'build': {'result': 'success', 'outputs': {}},
                    'contract': {'result': 'success' if event in {'push', 'workflow_dispatch'} and ref == 'refs/heads/main' else 'skipped', 'outputs': {}},
                    'publish-evidence': {'result': 'success' if event == 'push' and ref == 'refs/heads/main' else 'skipped', 'outputs': {}},
                }.items() if name == 'build' or branch != 'pr'})} if active else {},
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

    def test_renderer_changed_scope_requires_successful_direct_result(self):
        for value in ['success', 'failure', 'cancelled', 'skipped', '', None]:
            with self.subTest(value=value):
                needs = fixture()
                needs['changes']['outputs']['renderer-changed'] = 'true'
                needs['renderer-tests']['result'] = value
                self.assertEqual(bool(self.check(needs)), value != 'success')
        needs = fixture()
        needs['changes']['outputs']['renderer-changed'] = 'true'
        del needs['renderer-tests']
        self.assertTrue(self.check(needs))

    def test_renderer_scope_and_unselected_result_fail_closed(self):
        for value in ['success', 'failure', 'cancelled', '', None]:
            needs = fixture()
            needs['renderer-tests']['result'] = value
            self.assertTrue(self.check(needs))
        needs = fixture()
        del needs['renderer-tests']
        self.assertTrue(self.check(needs))
        for value in ['True', '', None, True, 1, [], {}]:
            needs = fixture()
            needs['changes']['outputs']['renderer-changed'] = value
            self.assertTrue(self.check(needs))
        needs = fixture()
        del needs['changes']['outputs']['renderer-changed']
        self.assertTrue(self.check(needs))

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

    def test_each_lane_requires_its_exact_native_child_roster(self):
        for event, ref, lane in [('pull_request', 'refs/pull/1/merge', 'pr'),
                                 ('push', 'refs/heads/main', 'other')]:
            with self.subTest(event=event):
                needs = fixture(event=event, ref=ref)
                record = needs[graph.job_id(MODULES[0], lane)]
                body = json.loads(record['outputs']['platform_results'])
                self.assertEqual(set(body), {'build'} if lane == 'pr' else {'build', 'contract', 'publish-evidence'})
                body['build'] = {}
                record['outputs']['platform_results'] = json.dumps(body)
                self.assertTrue(self.check(needs, event=event, ref=ref))
                body.pop('build')
                record['outputs']['platform_results'] = json.dumps(body)
                self.assertTrue(self.check(needs, event=event, ref=ref))
        needs = fixture()
        record = needs[graph.job_id(MODULES[0], 'pr')]
        body = json.loads(record['outputs']['platform_results'])
        body['contract'] = {'result': 'skipped', 'outputs': {}}
        record['outputs']['platform_results'] = json.dumps(body)
        self.assertTrue(self.check(needs))

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


class RendererSelectionTest(unittest.TestCase):
    def test_renderer_source_dependency_test_and_proof_paths_select(self):
        for event in ['pull_request', 'push']:
            for path in ['openbank-document-renderer/app.py',
                         'openbank-document-renderer/requirements.in',
                         'openbank-document-renderer/requirements.txt',
                         'openbank-document-renderer/Dockerfile',
                         'openbank-document-renderer/tests/test_app.py',
                         'openbank-document-renderer/tests/test_woff2.py',
                         *results.RENDERER_PROOF_PATHS]:
                self.assertTrue(results.renderer_selected([path], event), (event, path))

    def test_unrelated_paths_do_not_select(self):
        for paths in [[], ['docs/guide.md'], ['openbank-document-service/src/main/App.kt'],
                      ['openbank-document-renderer-notes.md'],
                      ['.github/scripts/check-service-build-results.py.backup']]:
            self.assertFalse(results.renderer_selected(paths, 'pull_request'), paths)

    def test_scheduled_and_manual_runs_always_select(self):
        for event in ['schedule', 'workflow_dispatch']:
            self.assertTrue(results.renderer_selected([], event))
            self.assertTrue(results.renderer_selected(['docs/guide.md'], event))

    def test_unknown_event_and_malformed_inventory_reject(self):
        with self.assertRaises(ValueError):
            results.renderer_selected([], 'unknown')
        for paths in ['openbank-document-renderer/app.py', [None], [''],
                      ['/openbank-document-renderer/app.py'], ['a/../app.py'],
                      ['a//app.py'], ['a/\x00app.py']]:
            with self.subTest(paths=paths), self.assertRaises(ValueError):
                results.renderer_selected(paths, 'pull_request')

    def test_cli_selection_and_missing_environment(self):
        import os
        import subprocess
        import sys
        env = dict(os.environ, FILES='openbank-document-renderer/tests/test_woff2.py',
                   GITHUB_EVENT_NAME='pull_request')
        command = [sys.executable, results.__file__, '--select-renderer']
        selected = subprocess.run(command, env=env, capture_output=True, text=True, check=False)
        self.assertEqual(selected.returncode, 0, selected.stderr)
        self.assertEqual(selected.stdout, 'renderer-changed=true\n')
        env['FILES'] = 'docs/guide.md'
        unselected = subprocess.run(command, env=env, capture_output=True, text=True, check=False)
        self.assertEqual(unselected.returncode, 0, unselected.stderr)
        self.assertEqual(unselected.stdout, 'renderer-changed=false\n')
        del env['FILES']
        missing = subprocess.run(command, env=env, capture_output=True, text=True, check=False)
        self.assertNotEqual(missing.returncode, 0)
        self.assertNotIn('renderer-changed=', missing.stdout)


class GraphTest(unittest.TestCase):
    def test_required_graph_includes_direct_renderer_result(self):
        import yaml
        needs = yaml.safe_load(graph.needs_block(MODULES))['needs']
        self.assertIn('renderer-tests', needs)
        self.assertEqual(needs.count('renderer-tests'), 1)

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

    def test_new_module_rejects_old_roster_until_canonical_regeneration(self):
        import yaml
        modules = graph.inventory(graph.ROOT)
        self.assertIn('openbank-products', modules)
        previous = [module for module in modules if module != 'openbank-products']
        old_needs = fixture(selected=['openbank-libs'], modules=previous)
        findings = results.verify(['openbank-libs'], old_needs,
                                  'pull_request', 'refs/pull/1/merge', modules)
        self.assertTrue(findings)
        self.assertIn('build_pr_openbank_products', str(findings))
        self.assertIn('build_other_openbank_products', str(findings))
        source = (graph.ROOT / graph.WORKFLOW).read_text()
        old_graph = graph.generated(source, previous)
        regenerated = graph.generated(old_graph, modules)
        self.assertNotEqual(old_graph, regenerated)
        self.assertEqual(graph.generated(regenerated, modules), regenerated)
        callers = yaml.safe_load(graph.caller_block(modules))
        self.assertEqual(len(callers), 2 * len(modules))
        for lane in graph.LANES:
            self.assertIn(graph.job_id('openbank-products', lane), callers)
        self.assertEqual(results.verify(['openbank-libs'],
                                       fixture(selected=['openbank-libs'], modules=modules),
                                       'pull_request', 'refs/pull/1/merge', modules), [])

    def test_finite_caller_permissions_match_reviewed_baseline(self):
        import yaml
        spec = importlib.util.spec_from_file_location(
            'supply_chain', Path(__file__).with_name('check-workflow-supply-chain.py'))
        policy = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(policy)
        modules = graph.inventory(graph.ROOT)
        jobs = yaml.safe_load(graph.caller_block(modules))
        for job in jobs.values():
            self.assertEqual(job['permissions'], {'contents': 'read', 'actions': 'read', 'id-token': 'write'})
        expected = {line for line in policy.WRITE_BASELINE.read_text().splitlines()
                    if line.startswith('services-ci.yml|job:build')}
        actual = policy.write_grants('services-ci.yml', {'jobs': jobs})
        self.assertEqual(len(expected), 2 * len(modules))
        self.assertEqual(policy.grant_drift(actual, expected), [])
        unexpected = 'services-ci.yml|job:foreign|id-token'
        self.assertEqual(policy.grant_drift(actual | {unexpected}, expected),
                         [f'unexpected workflow write grant: {unexpected}'])
        removed = min(actual)
        self.assertEqual(policy.grant_drift(actual - {removed}, expected),
                         [f'stale workflow write baseline entry: {removed}'])
        expanded = yaml.safe_load(graph.caller_block(modules + ['openbank-zz-fixture']))
        new_grants = policy.write_grants('services-ci.yml', {'jobs': expanded})
        self.assertEqual(len(policy.grant_drift(new_grants, expected)), 2)
        changed = copy.deepcopy(jobs)
        changed[next(iter(changed))]['permissions']['contents'] = 'write'
        self.assertEqual(len(policy.grant_drift(
            policy.write_grants('services-ci.yml', {'jobs': changed}), expected)), 1)

    def test_exact_pr_projection_and_forbidden_drift(self):
        import yaml
        graph.validate_callee(graph.ROOT)
        full_path = graph.ROOT / graph.CALLEES['other']
        pr_path = graph.ROOT / graph.CALLEES['pr']
        full = yaml.safe_load(full_path.read_text())
        projected = yaml.safe_load(pr_path.read_text())
        self.assertEqual(set(projected['jobs']), {'build'})
        self.assertEqual(projected['jobs']['build'], full['jobs']['build'])
        self.assertEqual(projected.get('on', projected.get(True)), full.get('on', full.get(True)))
        self.assertEqual(projected['permissions'], full['permissions'])
        for mutation in ['timeout', 'runner', 'permissions', 'steps', 'extra-job', 'output']:
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                directory = root / '.github/workflows'
                directory.mkdir(parents=True)
                (root / graph.CALLEES['other']).write_text(full_path.read_text())
                candidate = copy.deepcopy(projected)
                build = candidate['jobs']['build']
                if mutation == 'timeout':
                    build['timeout-minutes'] = 1
                elif mutation == 'runner':
                    build['runs-on'] = 'untrusted-runner'
                elif mutation == 'permissions':
                    build['permissions']['id-token'] = 'write'
                elif mutation == 'steps':
                    build['steps'].pop()
                elif mutation == 'extra-job':
                    candidate['jobs']['foreign'] = {'runs-on': 'ubuntu-latest'}
                else:
                    trigger = candidate.get('on', candidate.get(True))
                    trigger['workflow_call']['outputs']['platform_results']['value'] = 'success'
                (root / graph.CALLEES['pr']).write_text(yaml.safe_dump(candidate))
                with self.assertRaises(ValueError):
                    graph.validate_callee(root)

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
                self.assertEqual(job['uses'], './' + graph.CALLEES[branch])
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


@contextmanager
def preserve_fixture_outputs(*names):
    saved = {}
    for name in names:
        path = Path(name)
        if path.is_symlink():
            raise AssertionError('fixture output must not be a symlink')
        saved[path] = (path.read_bytes(), path.stat().st_mode) if path.exists() else None
    try:
        yield
    finally:
        for path, previous in saved.items():
            if previous is None:
                path.unlink(missing_ok=True)
            else:
                path.write_bytes(previous[0])
                path.chmod(previous[1])


class ThinCalleeTest(unittest.TestCase):
    # Reviewed helper bodies; build-test carries both the context-only deployment recipe pilot
    # and the PR12587 profiling port. Other hashes are the reviewed extraction hashes, updated
    # where PR12587 ports (provider proof reuse, strict HAL) changed a helper body.
    BODIES: ClassVar[dict] = {'build-test.sh': ('build', 'Build + test (:${{ inputs.service }})', '1ca38c5b95c69d67d8be3951246b810e87218da1e06ab12fa6f355f5bdf0b280', {'SERVICE': '${{ inputs.service }}', 'DEPLOY_ARTIFACT_PILOT': "${{ inputs.service == 'openbank-context-service' && github.event_name == 'push' && github.ref == 'refs/heads/main' && inputs.ref == '' }}"}), 'checkout-provider-ref.sh': ('contract', 'Check out a requested provider version only after proving it is on main', '9359271c9fbb762b315b2cc0b0da49b326d93fab2901a15e4b1ca21b294495fd', {}), 'prepare-provider-overlay.sh': ('contract', 'Check out P and overlay the provider test tree from F (equivalence-proven)', '0f445699cbeda8ff0dbad68672a9a71bc53219d33bbf96b23a43f4c9371708f0', {}), 'prewarm-images.sh': ('contract', 'Pre-warm Testcontainers image cache via ECR', '36f9392b6a9fd8ab7df2d878ba0b936420502a88fccfa77085755f22103b0bfc', {'ECR_LOGIN_OUTCOME': '${{ steps.ecr_login.outcome }}'}), 'configure-maven-mirror.sh': ('contract', 'Configure CodeArtifact Maven mirror', '8a6a6c1a11a5623f49afbd15dff4af80c338c295e695c27f7959a71aeedb15bf', {}), 'warm-gradle-cache.sh': ('contract', 'Warm Gradle dependency cache (self-heal corrupted entries)', 'd34b7783f113651bdbf88431aceec68318d0a8d5a5df0b478f1eb709f4a805a9', {'SERVICE': '${{ inputs.service }}'}), 'publish-consumer-pacts.sh': ('contract', 'Publish consumer pacts to broker', '6bdbc08d0209bd0dd7c545679063a38479c14b3e835ceab2f744ab1ea9d3844f', {'SERVICE': '${{ inputs.service }}'}), 'discover-provider-task.sh': ('contract', 'Discover provider Pact task', '0e74a3f67a9bc2cb9cd3823873aba2afcc051093cfc66355ae75ede4d0b7c9ec', {'REQUIRE_PROVIDER_PACT_TEST': '${{ inputs.require_provider_pact_test }}'}), 'preflight-pact-broker.sh': ('contract', 'Preflight Pact Broker selector/HAL response', '3c0cc41a6127180ce4a2e4d305c8108adbbbea707ea59032117f7a76517dac25', {})}

    BODIES['render-coverage-report.sh'] = ('build', 'Generate Kover XML report', '51c948df0397703c3039daf387f172b0e0028e159ab4b797b96830f459f614e0', {'SERVICE': '${{ inputs.service }}'})

    def test_exact_bodies_and_explicit_environment(self):
        import hashlib
        import subprocess

        import yaml
        workflow = yaml.safe_load((graph.ROOT / '.github/workflows/_service-ci.yml').read_text())
        folder = graph.ROOT / '.github/scripts/service-ci-steps'
        self.assertEqual({p.name for p in folder.glob('*.sh')}, set(self.BODIES))
        for filename, (job, name, digest, bindings) in self.BODIES.items():
            with self.subTest(helper=filename):
                path = folder / filename
                body = path.read_text().split('\n', 2)[2]
                self.assertEqual(hashlib.sha256(body.encode()).hexdigest(), digest)
                self.assertNotIn('${{', body)
                subprocess.run(['bash', '-n', str(path)], check=True)
                step = next(s for s in workflow['jobs'][job]['steps'] if s.get('name') == name)
                expected = f'bash -e "$SERVICE_CI_HELPERS/{filename}"'
                if filename == 'render-coverage-report.sh':
                    expected = 'timeout --kill-after=10s 600s ' + expected
                    self.assertTrue(step['continue-on-error'])
                self.assertEqual(step['run'], expected)
                for key, expression in bindings.items():
                    self.assertEqual(step['env'][key], expression)

    def test_build_recipe_pilot_is_context_only_and_default_recipe_is_preserved(self):
        import os
        import subprocess
        import sys

        helper = graph.ROOT / '.github/scripts/service-ci-steps/build-test.sh'
        for service, pilot in [('openbank-context-service', 'true'),
                               ('openbank-context-service', 'false'),
                               ('openbank-context-service', None),
                               ('openbank-ledger-service', 'false'),
                               ('openbank-ledger-service', 'true')]:
            with self.subTest(service=service, pilot=pilot), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                commands = root / 'commands'
                commands.mkdir()
                docker = commands / 'docker'
                docker.write_text('#!/bin/sh\nexit 0\n')
                docker.chmod(0o700)
                scripts = root / '.github/scripts'
                scripts.mkdir(parents=True)
                for name in ['retry-testcontainers-ebusy.sh', 'retry-gradle-verification-cache.sh']:
                    (scripts / name).write_text('shift\nexec "$@"\n')
                capture = root / 'invocation.json'
                gradle = root / 'gradlew'
                gradle.write_text(f'#!{sys.executable}\nimport json,os,pathlib,sys\n'
                                  'pathlib.Path(os.environ["CAPTURE"]).write_text(json.dumps({\n'
                                  ' "args":sys.argv[1:],\n'
                                  ' "sink":os.environ.get("ANALYTICS_SINK_TYPE"),\n'
                                  ' "nonce":os.environ.get("AGENT_SVID_NONCE_STORE") }))\n')
                gradle.chmod(0o700)
                env = dict(os.environ, SERVICE=service, CAPTURE=str(capture),
                           PATH=str(commands) + os.pathsep + os.environ['PATH'])
                for key in ('DEPLOY_ARTIFACT_PILOT', 'ANALYTICS_SINK_TYPE', 'AGENT_SVID_NONCE_STORE'):
                    env.pop(key, None)
                if pilot is not None:
                    env['DEPLOY_ARTIFACT_PILOT'] = pilot
                result = subprocess.run(['bash', '-e', str(helper)], cwd=root, env=env,
                                        capture_output=True, text=True, timeout=10)
                if pilot == 'true' and service != 'openbank-context-service':
                    self.assertNotEqual(result.returncode, 0)
                    self.assertFalse(capture.exists(), 'invalid pilot must not invoke Gradle')
                    continue
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                observed = json.loads(capture.read_text())
                expected = ['--configure-on-demand', f':{service}:build']
                if pilot == 'true':
                    expected.append(f':{service}:cyclonedxBom')
                expected.extend(['--build-cache', '--console=plain', '--stacktrace'])
                if pilot == 'true':
                    expected.append('-Dquarkus.package.jar.type=fast-jar')
                expected.append('-Dquarkus.smallrye-openapi.store-schema-directory=build/openapi-generated')
                self.assertEqual(observed['args'], expected)
                self.assertEqual(observed['sink'], 'clickhouse' if pilot == 'true' else None)
                self.assertEqual(observed['nonce'], 'redis' if pilot == 'true' else None)

    def test_helpers_staged_before_historical_checkout(self):
        import yaml
        workflow = yaml.safe_load((graph.ROOT / '.github/workflows/_service-ci.yml').read_text())
        for job in ['build', 'contract']:
            steps = workflow['jobs'][job]['steps']
            stage = next(i for i, s in enumerate(steps)
                         if s.get('name') == 'Stage trusted service CI step helpers')
            self.assertNotIn('if', steps[stage])
            self.assertIn('mktemp -d "${RUNNER_TEMP}/service-ci-helpers.XXXXXX"', steps[stage]['run'])
            self.assertIn('chmod 600', steps[stage]['run'])
            self.assertIn('>> "$GITHUB_ENV"', steps[stage]['run'])
            for i, s in enumerate(steps):
                if 'git checkout' in s.get('run', '') or '$SERVICE_CI_HELPERS/' in s.get('run', ''):
                    self.assertLess(stage, i)
            checkout = next(s for s in steps[:stage] if 'actions/checkout@' in s.get('uses', ''))
            self.assertIn('github.sha', checkout['with']['ref'])
        self.assertEqual(set(workflow['jobs']), {'build', 'contract', 'publish-evidence'})

    def test_staged_helpers_survive_historical_workspace_replacement(self):
        import os
        import subprocess

        import yaml
        workflow = yaml.safe_load((graph.ROOT / '.github/workflows/_service-ci.yml').read_text())
        stage = next(s['run'] for s in workflow['jobs']['contract']['steps']
                     if s.get('name') == 'Stage trusted service CI step helpers')
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / 'workspace/.github/scripts/service-ci-steps'
            source.mkdir(parents=True)
            for filename in self.BODIES:
                shutil.copyfile(graph.ROOT / '.github/scripts/service-ci-steps' / filename,
                                source / filename)
            shutil.copyfile(graph.ROOT / '.github/scripts/heal-gradle-verification-failure.sh',
                            source.parent / 'heal-gradle-verification-failure.sh')
            runner = root / 'runner'
            runner.mkdir()
            environment = root / 'environment'
            subprocess.run(['bash', '-e', '-c', stage], cwd=root / 'workspace',
                           env=dict(os.environ, RUNNER_TEMP=str(runner), GITHUB_ENV=str(environment)),
                           check=True, capture_output=True)
            staged = Path(environment.read_text().strip().removeprefix('SERVICE_CI_HELPERS='))
            self.assertEqual(staged.parent, runner)
            self.assertEqual(staged.stat().st_mode & 0o777, 0o700)
            for filename in self.BODIES:
                before = (source / filename).read_bytes()
                (source / filename).write_text('exit 99\n')
                self.assertEqual((staged / filename).read_bytes(), before)
                self.assertEqual((staged / filename).stat().st_mode & 0o777, 0o600)
            (source.parent / 'heal-gradle-verification-failure.sh').unlink()
            missing = subprocess.run(['bash', '-e', '-c', stage], cwd=root / 'workspace',
                                     env=dict(os.environ, RUNNER_TEMP=str(runner), GITHUB_ENV=str(environment)),
                                     capture_output=True, check=False)
            self.assertNotEqual(missing.returncode, 0)

    def test_build_failure_preserves_exit_and_cleans_owned_observer(self):
        import os
        import subprocess
        import sys
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            commands = root / 'commands'
            commands.mkdir()
            docker = commands / 'docker'
            docker.write_text(f'#!{sys.executable}\nimport os,pathlib,signal\n'
                              'pathlib.Path(os.environ["OBSERVER_PID"]).write_text(str(os.getpid()))\n'
                              'signal.pause()\n')
            docker.chmod(0o700)
            scripts = root / '.github/scripts'
            scripts.mkdir(parents=True)
            for name in ['retry-testcontainers-ebusy.sh', 'retry-gradle-verification-cache.sh']:
                (scripts / name).write_text('shift\nexec "$@"\n')
            gradle = root / 'gradlew'
            gradle.write_text(f'#!{sys.executable}\nimport os,pathlib,time\n'
                              'deadline=time.monotonic()+2\n'
                              'while not pathlib.Path(os.environ["OBSERVER_PID"]).exists():\n'
                              ' if time.monotonic()>deadline: raise SystemExit(98)\n'
                              ' time.sleep(0.001)\n'
                              'raise SystemExit(7)\n')
            gradle.chmod(0o700)
            pidfile = root / 'observer'
            env = dict(os.environ, PATH=str(commands) + os.pathsep + os.environ['PATH'],
                       SERVICE='openbank-fixture', OBSERVER_PID=str(pidfile))
            result = subprocess.run(['bash', '-e', str(graph.ROOT / '.github/scripts/service-ci-steps/build-test.sh')],
                                    cwd=root, env=env, capture_output=True, text=True, check=False, timeout=5)
            self.assertEqual(result.returncode, 7, result.stderr)
            with self.assertRaises(ProcessLookupError):
                os.kill(int(pidfile.read_text()), 0)

    def test_warmup_uses_trusted_healer_not_historical_workspace(self):
        import os
        import subprocess
        with tempfile.TemporaryDirectory() as tmp, preserve_fixture_outputs('/tmp/gradle-warmup.log'):
            root = Path(tmp)
            commands = root / 'commands'
            commands.mkdir()
            timeout = commands / 'timeout'
            timeout.write_text('#!/bin/sh\necho unrelated-fixture-failure\nexit 1\n')
            timeout.chmod(0o700)
            historical = root / '.github/scripts'
            historical.mkdir(parents=True)
            poison = root / 'historical-executed'
            (historical / 'heal-gradle-verification-failure.sh').write_text(f'touch "{poison}"\nexit 0\n')
            trusted = root / 'trusted'
            trusted.mkdir()
            shutil.copyfile(graph.ROOT / '.github/scripts/heal-gradle-verification-failure.sh',
                            trusted / 'heal-gradle-verification-failure.sh')
            env = dict(os.environ, PATH=str(commands) + os.pathsep + os.environ['PATH'],
                       SERVICE='openbank-fixture', SERVICE_CI_HELPERS=str(trusted))
            result = subprocess.run(['bash', '-e', str(graph.ROOT / '.github/scripts/service-ci-steps/warm-gradle-cache.sh')],
                                    cwd=root, env=env, capture_output=True, text=True, check=False, timeout=5)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('reason other than cache corruption', result.stdout)
            self.assertFalse(poison.exists())

    def test_consumer_publication_zero_and_exact_selected_files(self):
        import os
        import subprocess
        script = graph.ROOT / '.github/scripts/service-ci-steps/publish-consumer-pacts.sh'
        for nonempty in [False, True]:
            with (self.subTest(nonempty=nonempty), tempfile.TemporaryDirectory() as tmp,
                  preserve_fixture_outputs('/tmp/pact-publish.json')):
                root = Path(tmp)
                (root / 'pacts').mkdir()
                fixture = {'consumer': {'name': 'fixture-consumer'}, 'provider': {'name': 'fixture-provider'}}
                (root / 'pacts/openbank-foreign-contract.json').write_text(json.dumps(fixture))
                if nonempty:
                    (root / 'pacts/openbank-fixture-contract.json').write_text(json.dumps(fixture))
                runner = root / 'runner'
                runner.mkdir()
                capture = root / 'request'
                (runner / 'pact-broker-post.sh').write_text(
                    'cp "$2" "$CAPTURE"\nprintf \'{"notices":[]}\\n\' > "$3"\necho 200\n')
                env = dict(os.environ, SERVICE='openbank-fixture', BRANCH='fixture', GITHUB_SHA='a' * 40,
                           GITHUB_SERVER_URL='https://example.invalid', GITHUB_REPOSITORY='fixture/repo',
                           GITHUB_RUN_ID='42', RUNNER_TEMP=str(runner), CAPTURE=str(capture),
                           PACT_BROKER_URL='https://broker.invalid', PACT_BROKER_PASSWORD='secret-canary')
                result = subprocess.run(['bash', '-e', str(script)], cwd=root, env=env,
                                        capture_output=True, text=True, check=False)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertNotIn('secret-canary', result.stdout + result.stderr)
                self.assertEqual(capture.exists(), nonempty)
                if nonempty:
                    body = json.loads(capture.read_text())
                    self.assertEqual(body['pacticipantName'], 'openbank-fixture')
                    self.assertEqual(body['pacticipantVersionNumber'], 'a' * 40)
                    self.assertEqual(body['branch'], 'fixture')
                    self.assertEqual(len(body['contracts']), 1)

    def test_provider_discovery_success_absence_and_failure(self):
        import os
        import subprocess
        script = graph.ROOT / '.github/scripts/service-ci-steps/discover-provider-task.sh'
        for owns_test, has_task, required, expected in [
                (True, True, False, 0), (True, False, False, 1),
                (False, True, False, 0), (False, True, True, 1)]:
            with self.subTest(owns_test=owns_test, has_task=has_task, required=required), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                tests = root / 'openbank-fixture/src/test'
                tests.mkdir(parents=True)
                if owns_test:
                    (tests / 'FixtureProviderVerificationTest.kt').touch()
                gradle = root / 'gradlew'
                gradle.write_text('#!/bin/sh\n' + ('echo providerPactTest\n' if has_task else 'echo tasks\n'))
                gradle.chmod(0o700)
                output = root / 'output'
                runner = root / 'runner'
                runner.mkdir()
                for helper in ['service-ci-discover-provider-pact-task.sh', 'service-ci-provider-task-list.sh']:
                    shutil.copyfile(graph.ROOT / '.github/scripts' / helper, runner / helper)
                    (runner / helper).chmod(0o700)
                env = dict(os.environ, RUNNER_TEMP=str(runner), SERVICE='openbank-fixture', PACT_PROVIDER_VERSION='fixture',
                           REQUIRE_PROVIDER_PACT_TEST=str(required).lower(), GITHUB_OUTPUT=str(output))
                result = subprocess.run(['bash', '-e', str(script)], cwd=root, env=env,
                                        capture_output=True, text=True, check=False)
                self.assertEqual(result.returncode, expected, result.stderr)
                if expected == 0:
                    self.assertEqual(output.read_text().strip(),
                                     'available=' + str(owns_test and has_task).lower())


class CoverageReportTaskDiscoveryTests(unittest.TestCase):
    def run_report(self, tasks, *, module='openbank-libs-runtime', raw=True, discovery_rc=0, report_rc=0):
        import os
        import subprocess
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        raw_file = root / module / 'build/kover/bin-reports/test.ic'
        if raw:
            raw_file.parent.mkdir(parents=True)
            raw_file.write_bytes(b'immutable-enforcing-coverage')
        fake = root / 'gradlew'
        fake.write_text('''#!/usr/bin/env python3
import json, os, pathlib, sys
log = pathlib.Path('calls.json')
calls = json.loads(log.read_text()) if log.exists() else []
calls.append(sys.argv[1:]); log.write_text(json.dumps(calls))
if any(a.endswith(':openbankCoverageReportTestTasks') for a in sys.argv):
    init = pathlib.Path(sys.argv[sys.argv.index('--init-script') + 1])
    pathlib.Path('discovery-init.gradle').write_text(init.read_text())
    if int(os.environ['DISCOVERY_RC']): sys.exit(int(os.environ['DISCOVERY_RC']))
    print('OPENBANK_COVERAGE_TEST_TASKS=' + os.environ['TASK_PLAN'])
else:
    sys.exit(int(os.environ['REPORT_RC']))
''')
        fake.chmod(0o700)
        env = dict(os.environ, SERVICE=module, TASK_PLAN=json.dumps(tasks),
                   DISCOVERY_RC=str(discovery_rc), REPORT_RC=str(report_rc), RUNNER_TEMP=str(root))
        helper = graph.ROOT / '.github/scripts/service-ci-steps/render-coverage-report.sh'
        result = subprocess.run(['bash', str(helper)], cwd=root, env=env, capture_output=True, text=True)
        calls_file = root / 'calls.json'
        calls = json.loads(calls_file.read_text()) if calls_file.exists() else []
        if raw:
            self.assertEqual(raw_file.read_bytes(), b'immutable-enforcing-coverage')
        self.assertFalse(list(root.glob('coverage-report.*')), 'temporary discovery scripts must be removed')
        return result, calls, root

    def test_library_does_not_exclude_nonexistent_provider_or_native_tasks(self):
        result, calls, root = self.run_report([':openbank-libs-runtime:test'])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(calls), 2)
        exclusions = [calls[1][i + 1] for i, arg in enumerate(calls[1]) if arg == '--exclude-task']
        self.assertEqual(exclusions, [':openbank-libs-runtime:test'])
        self.assertIn('tasks.withType(org.gradle.api.tasks.testing.Test)',
                      (root / 'discovery-init.gradle').read_text())
        self.assertIn(':openbank-libs-runtime:koverXmlReport', calls[1])

    def test_service_excludes_every_discovered_test_variant_without_running_tests(self):
        tasks = [f':openbank-document-service:{name}' for name in
                 ['test', 'providerPactTest', 'quarkusIntTest', 'testNative', 'customDatabaseTest']]
        result, calls, _ = self.run_report(tasks, module='openbank-document-service')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(calls), 2)
        self.assertEqual([calls[1][i + 1] for i, arg in enumerate(calls[1]) if arg == '--exclude-task'], tasks)
        self.assertNotIn('--rerun-tasks', calls[1])

    def test_missing_coverage_data_never_starts_gradle(self):
        result, calls, _ = self.run_report([':openbank-libs-runtime:test'], raw=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(calls, [])

    def test_invalid_discovery_never_renders_empty_or_foreign_evidence(self):
        for tasks in [[], [':other:test'], [':openbank-libs-runtime:test'] * 2,
                      [':openbank-libs-runtime:providerPactTest'],
                      [':openbank-libs-runtime:test', ':openbank-libs-runtime:test;touch marker']]:
            with self.subTest(tasks=tasks):
                result, calls, _ = self.run_report(tasks)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(len(calls), 1)

    def test_discovery_error_is_not_a_successful_report(self):
        result, calls, _ = self.run_report([':openbank-libs-runtime:test'], discovery_rc=23)
        self.assertEqual(result.returncode, 23)
        self.assertEqual(len(calls), 1)

    def test_report_failure_is_propagated(self):
        result, calls, _ = self.run_report([':openbank-libs-runtime:test'], report_rc=24)
        self.assertEqual(result.returncode, 24)
        self.assertEqual(len(calls), 2)


if __name__ == '__main__':
    unittest.main()
