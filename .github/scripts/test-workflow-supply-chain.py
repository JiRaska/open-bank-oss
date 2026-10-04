#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
import copy
import importlib.util
import json
import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

import yaml

spec = importlib.util.spec_from_file_location('guard', Path(__file__).with_name('check-workflow-supply-chain.py'))
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class SupplyChainTest(unittest.TestCase):
    def test_gitops_pr_refreshes_its_actual_base_before_rewriting(self):
        def refreshed_before_pr(doc):
            for job in doc['jobs'].values():
                steps = job.get('steps', [])
                creator = next((i for i, step in enumerate(steps)
                                if step.get('uses', '').startswith('peter-evans/create-pull-request@')), None)
                if creator is None:
                    continue
                return any('bash .github/scripts/refresh-gitops-pr-base.sh' in step.get('run', '')
                           for step in steps[:creator])
            return False

        for name in ('auto-deploy.yml', 'admin-ui-deploy.yml'):
            doc = yaml.safe_load((guard.ROOT / '.github/workflows' / name).read_text())
            self.assertTrue(refreshed_before_pr(doc), name)
            mutated = copy.deepcopy(doc)
            for job in mutated['jobs'].values():
                for step in job.get('steps', []):
                    if 'run' in step:
                        step['run'] = step['run'].replace(
                            'bash .github/scripts/refresh-gitops-pr-base.sh',
                            'git reset --hard origin/main')
            self.assertFalse(refreshed_before_pr(mutated), name)

        # Reproduce a push landing after the workflow checked out an old commit.
        # The bot commit must have the *new* main as its sole parent and touch only
        # the manifest it rewrote, rather than carry intervening fleet tag changes.
        env = dict(os.environ, GIT_CONFIG_GLOBAL=os.devnull)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            remote, writer, runner = (root / name for name in ('remote.git', 'writer', 'runner'))

            def git(cwd, *args):
                return subprocess.check_output(['git', *args], cwd=cwd, env=env, text=True).strip()

            git(root, 'init', '--bare', '--initial-branch=main', str(remote))
            git(root, 'clone', str(remote), str(writer))
            (writer / 'manifest.yaml').write_text('image: old\n')
            git(writer, 'add', 'manifest.yaml')
            git(writer, '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                '-c', 'commit.gpgsign=false', 'commit', '-m', 'initial')
            git(writer, 'push', 'origin', 'main')
            git(root, 'clone', str(remote), str(runner))
            git(runner, 'checkout', '--detach')
            (writer / 'unrelated.yaml').write_text('image: later\n')
            git(writer, 'add', 'unrelated.yaml')
            git(writer, '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                '-c', 'commit.gpgsign=false', 'commit', '-m', 'intervening deploy')
            git(writer, 'push', 'origin', 'main')
            latest = git(writer, 'rev-parse', 'HEAD')

            subprocess.check_call(['bash', str(guard.ROOT / '.github/scripts/refresh-gitops-pr-base.sh')],
                                  cwd=runner, env=env, stdout=subprocess.DEVNULL)
            self.assertEqual(git(runner, 'symbolic-ref', '--short', 'HEAD'), 'main')
            self.assertEqual(git(runner, 'rev-parse', 'HEAD'), latest)
            (runner / 'manifest.yaml').write_text('image: new\n')
            git(runner, 'add', 'manifest.yaml')
            git(runner, '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                '-c', 'commit.gpgsign=false', 'commit', '-m', 'bot rewrite')
            self.assertEqual(git(runner, 'rev-parse', 'HEAD^'), latest)
            self.assertEqual(git(runner, 'diff', '--name-only', 'HEAD^', 'HEAD'), 'manifest.yaml')

    def test_personal_model_credential_is_rejected_in_every_workflow(self):
        for name in ('agent-review.yml', 'other.yml'):
            doc = {'jobs': {'test': {'steps': [{
                'run': 'echo ${{ secrets.CLAUDE_CODE_OAUTH_TOKEN }}',
            }]}}}
            self.assertIn('personal model subscription credential is forbidden in workflows',
                          guard.findings(name, doc))

    def test_tags_fail_for_steps_and_reusable_jobs(self):
        for job in ({'uses': 'owner/action@v1'}, {'steps': [{'uses': 'actions/checkout@v4'}]}):
            self.assertTrue(guard.findings('example.yml', {'permissions': {}, 'jobs': {'test': job}}))

    def test_pins_and_local_actions_pass(self):
        job = {'steps': [{'uses': './local'}, {'uses': 'actions/checkout@' + 'a' * 40}]}
        self.assertFalse(guard.findings('example.yml', {'permissions': {}, 'jobs': {'test': job}}))

    def test_top_level_permissions_must_be_declared_and_read_only(self):
        job = {'permissions': {'issues': 'write'}, 'steps': []}
        self.assertFalse(guard.findings('example.yml', {'permissions': {}, 'jobs': {'test': job}}))
        self.assertFalse(guard.findings('example.yml', {'permissions': 'read-all', 'jobs': {}}))
        for top in (None, {'contents': 'read', 'issues': 'write'}, 'write-all', {'id-token': 'write'}):
            doc = {'jobs': {'test': job}}
            if top is not None:
                doc['permissions'] = top
            with self.subTest(top=top):
                self.assertTrue(guard.top_level_findings(doc))
                self.assertTrue(guard.findings('example.yml', doc))

    def test_every_real_workflow_regresses_when_a_write_moves_to_the_top(self):
        for path in sorted((guard.ROOT / '.github/workflows').glob('*.y*ml')):
            original = yaml.safe_load(path.read_text())
            self.assertFalse(guard.top_level_findings(original), path.name)
            for mutation in ('drop', 'write'):
                doc = copy.deepcopy(original)
                if mutation == 'drop':
                    doc.pop('permissions')
                else:
                    doc['permissions'] = {'contents': 'write'}
                with self.subTest(name=path.name, mutation=mutation):
                    self.assertTrue(guard.top_level_findings(doc))

    def test_slsa_exception_cannot_spread(self):
        doc = {'permissions': {}, 'jobs': {'provenance': {'uses': guard.SLSA}}}
        self.assertFalse(guard.findings('release-please.yml', doc))
        self.assertTrue(guard.findings('other.yml', doc))
        doc['jobs']['provenance']['uses'] = guard.SLSA.replace('v2.1.0', 'main')
        self.assertTrue(guard.findings('release-please.yml', doc))

    def test_trigger_filter_regression(self):
        for name in ('main-red-watch.yml', 'admin-ui-deploy.yml'):
            self.assertTrue(guard.findings(name, {'permissions': {}, 'on': {'workflow_run': {}}, 'jobs': {}}))
            self.assertFalse(guard.findings(name, {'permissions': {}, 'on': {'workflow_run': {'branches': ['main']}},
                                                   'jobs': {}}))

    def test_pull_request_workflow_requires_concurrency(self):
        path = guard.ROOT / '.github/workflows/dependency-review.yml'
        original = yaml.safe_load(path.read_text())
        self.assertFalse(guard.findings(path.name, original))
        mutated = copy.deepcopy(original)
        mutated.pop('concurrency')
        self.assertIn('pull_request workflow must bound superseded runs with concurrency',
                      guard.findings(path.name, mutated))

    def test_agent_regressions_against_real_workflows(self):
        for name, key in [('agent-issue-worker.yml', 'worker'), ('agent-pr-steward.yml', 'steward')]:
            original = yaml.safe_load((guard.ROOT / '.github/workflows' / name).read_text())
            self.assertFalse(guard.findings(name, original))
            for mutation in ('permission', 'pr-secret-job', 'floating-cli', 'admission'):
                if mutation == 'admission' and key != 'worker':
                    continue
                doc = copy.deepcopy(original)
                job = doc['jobs'][key]
                if mutation == 'permission':
                    job['permissions']['contents'] = 'write'
                elif mutation == 'pr-secret-job':
                    job.pop('if')
                elif mutation == 'floating-cli':
                    job['steps'].append({'run': 'npm install -g @anthropic-ai/claude-code'})
                else:
                    job.pop('needs')
                with self.subTest(name=name, mutation=mutation):
                    self.assertTrue(guard.findings(name, doc))

            concurrency_mutation = copy.deepcopy(original)
            concurrency_mutation['concurrency'] = {
                'group': name,
                'cancel-in-progress': False,
            }
            self.assertIn('agent PR validation must use a superseding per-PR concurrency lane',
                          guard.findings(name, concurrency_mutation))

    def test_steward_scope_uses_the_same_authority_as_admission(self):
        workflow = yaml.safe_load(
            (guard.ROOT / '.github/workflows/agent-pr-steward.yml').read_text())
        rules = yaml.safe_load(
            (guard.ROOT / 'openbank-libs/governance/rules.yaml').read_text())
        prompt = (guard.ROOT / '.github/agent-prompts/pr-steward.md').read_text()
        self.assertFalse(guard.steward_scope_findings(prompt, rules, workflow))

        bad_prompt = prompt.replace('openbank-libs/governance/rules.yaml', 'a-local-list')
        self.assertTrue(guard.steward_scope_findings(bad_prompt, rules, workflow))

        bad_workflow = copy.deepcopy(workflow)
        events = bad_workflow.get('on', bad_workflow.get(True))
        events['pull_request']['paths'].remove('.github/agent-prompts/pr-steward.md')
        self.assertTrue(guard.steward_scope_findings(prompt, rules, bad_workflow))

        bad_rules = copy.deepcopy(rules)
        bad_rules['autonomous_agent_prs']['agent_branch_prefixes'] = []
        self.assertTrue(guard.steward_scope_findings(prompt, bad_rules, workflow))

    def test_platform_image_tag_follows_scan_and_attestation(self):
        workflow = yaml.safe_load((guard.ROOT / '.github/workflows/platform-images.yml').read_text())
        gate_manifest = yaml.safe_load((guard.ROOT / '.github/gates/gates.yaml').read_text())
        supply_chain_gate = next(gate for gate in gate_manifest['gates'] if gate['id'] == 'workflow-supply-chain')
        self.assertIn('.github/workflows/platform-images.yml', supply_chain_gate['selftest_inputs'])

        def defects(doc):
            steps = doc['jobs']['build']['steps']
            names = [step.get('name') for step in steps]
            required = ('Build + push', 'Trivy image scan (gate fixable CRITICAL, report HIGH)',
                        'Sign + attest (cosign + KMS, shared lib)', 'Tag attested image',
                        'Record result')
            if any(name not in names for name in required):
                return ['required release step missing']
            errors = []
            if [names.index(name) for name in required] != sorted(names.index(name) for name in required):
                errors.append('tag must follow scan and attestation')
            by_name = {step.get('name'): step for step in steps}
            def commands(name):
                return [line.strip() for line in by_name[name]['run'].splitlines()
                        if line.strip() and not line.lstrip().startswith('#')]

            push = '\n'.join(commands('Build + push'))
            scan = commands('Trivy image scan (gate fixable CRITICAL, report HIGH)')
            attest = commands('Sign + attest (cosign + KMS, shared lib)')
            tag = by_name['Tag attested image']['run']
            if 'push-by-digest=true' not in push or re.search(r'(^|\s)-t\s+', push):
                errors.append('build must push an untagged digest')
            if not scan or '--exit-code 1' not in scan[-1] or '|| true' in scan[-1]:
                errors.append('critical vulnerability scan must stop the release')
            if not attest or not attest[-1].startswith('cosign_sign_and_attest ') or '|| true' in attest[-1]:
                errors.append('failed signing or attestation must stop the release')
            if '--prefer-index=false' not in tag or '[ "$got" = "$DIGEST" ]' not in tag:
                errors.append('tag must preserve and verify the attested digest')
            return errors

        self.assertEqual(defects(workflow), [])
        for mutation in ('early-tag', 'tagged-build', 'scan-bypass', 'attest-bypass',
                         'index-wrap', 'no-digest-check'):
            doc = copy.deepcopy(workflow)
            steps = doc['jobs']['build']['steps']
            named = {step.get('name'): step for step in steps}
            if mutation == 'early-tag':
                tag_step = named['Tag attested image']
                steps.remove(tag_step)
                steps.insert(steps.index(named['Trivy image scan (gate fixable CRITICAL, report HIGH)']), tag_step)
            elif mutation == 'tagged-build':
                named['Build + push']['run'] += '\ndocker buildx build -t "$IMAGE:$TAG" --push "$CONTEXT"'
            elif mutation == 'scan-bypass':
                named['Trivy image scan (gate fixable CRITICAL, report HIGH)']['run'] = (
                    named['Trivy image scan (gate fixable CRITICAL, report HIGH)']['run'].replace(
                        '--exit-code 1', '--exit-code 0'))
            elif mutation == 'attest-bypass':
                named['Sign + attest (cosign + KMS, shared lib)']['run'] += '\necho attestation skipped'
            elif mutation == 'index-wrap':
                named['Tag attested image']['run'] = named['Tag attested image']['run'].replace(
                    '--prefer-index=false', '')
            else:
                named['Tag attested image']['run'] = named['Tag attested image']['run'].replace(
                    '[ "$got" = "$DIGEST" ]', 'true')
            with self.subTest(mutation=mutation):
                self.assertTrue(defects(doc))

    def test_services_ci_dispatch_and_fail_closed_contract(self):
        original = yaml.safe_load((guard.ROOT / '.github/workflows/services-ci.yml').read_text())
        self.assertFalse(guard.findings('services-ci.yml', original))
        for mutation in ('missing-plan-output', 'missing-matrix-output', 'unsharded-verifier',
                         'fail-fast-verifier', 'wrong-shard-target', 'unbounded-verifier',
                         'detector-failure-passes', 'missing-shard-verdict-passes'):
            doc = copy.deepcopy(original)
            if mutation == 'missing-plan-output':
                doc['jobs']['changes']['outputs'].pop('verification-modules')
            elif mutation == 'missing-matrix-output':
                doc['jobs']['changes']['outputs'].pop('verification-modules-json')
            elif mutation == 'unsharded-verifier':
                doc['jobs']['verification-metadata']['strategy']['matrix']['module'] = '[]'
            elif mutation == 'fail-fast-verifier':
                doc['jobs']['verification-metadata']['strategy']['fail-fast'] = True
            elif mutation == 'wrong-shard-target':
                doc['jobs']['verification-metadata']['steps'][-1]['run'] = 'echo skipped'
            elif mutation == 'unbounded-verifier':
                doc['jobs']['verification-metadata']['if'] = 'always()'
            elif mutation == 'missing-shard-verdict-passes':
                doc['jobs']['all-green']['steps'][0]['run'] = (
                    'if [ "${{ needs.changes.result }}" != "success" ]; then '
                    'echo "scope is unknown"; exit 1; fi')
            else:
                doc['jobs']['all-green']['steps'][0]['run'] = 'echo green'
            with self.subTest(mutation=mutation):
                self.assertTrue(guard.findings('services-ci.yml', doc))


    def test_security_regression_partial_graphql_errors_fail_closed(self):
        workflow = yaml.safe_load(
            (guard.ROOT / '.github/workflows/security-regression-test.yml').read_text())
        script = workflow['jobs']['security-regression-test']['steps'][0]['with']['script']
        runner = r"""
const {script} = JSON.parse(require('node:fs').readFileSync(0, 'utf8'));
const run = new (Object.getPrototypeOf(async function() {}).constructor)(
  'github', 'context', 'core', script);
const clone = x => JSON.parse(JSON.stringify(x));
const connection = {nodes: [{path: 'src/main/Fix.kt'}],
  pageInfo: {hasNextPage: false, endCursor: null}};
const data = {repository: {issue0: null,
  issue1: {labels: {nodes: [{name: 'security'}]}},
  pullRequest: {files: connection}}};
const missing = {type: 'NOT_FOUND', path: ['repository', 'issue0']};
const fixtures = [];
function add(name, errors, mutate, expected) {
  const response = clone(data);
  mutate(response.repository);
  fixtures.push({name, errors, response, expected});
}
add('missing reference does not hide real security issue', [missing], () => {}, 'failed');
add('missing reference plus regression test', [missing], r => {
  r.pullRequest.files.nodes.push({path: 'src/test/FixTest.kt'});
}, 'passed');
for (const type of ['FORBIDDEN', 'RATE_LIMITED', 'INTERNAL', undefined]) {
  add(`partial ${type}`, [{...missing, type}], r => {r.issue1.labels.nodes = [];}, 'threw');
}
for (const path of [undefined, ['repository', 'issue9'],
  ['repository', 'issue0', 'labels'], ['repository', 'pullRequest', 'files']]) {
  add(`invalid error path ${path}`, [{type: 'NOT_FOUND', path}], () => {}, 'threw');
}
add('mixed errors', [missing, {...missing, type: 'FORBIDDEN'}], () => {}, 'threw');
add('empty errors', [], () => {}, 'threw');
add('non-null failed alias', [missing], r => {r.issue0 = r.issue1;}, 'threw');
add('missing labels', [missing], r => {r.issue1 = {};}, 'threw');
add('missing requested alias', [missing], r => {delete r.issue1;}, 'threw');
add('null alias without error', null, () => {}, 'threw');
add('ordinary security response', null, r => {r.issue0 = r.issue1;}, 'failed');
const pricingTest = 'openbank-infra/gitops/components/pricing/pricing_rest_ext_test.rego';
const pricingPolicy = 'openbank-infra/gitops/components/pricing/pricing_rest_ext.rego';
add('Rego regression test from pricing security fix', null, r => {
  r.issue0 = r.issue1;
  r.pullRequest.files.nodes.push({path: pricingTest});
}, 'passed');
add('production Rego policy is not a test', null, r => {
  r.issue0 = r.issue1;
  r.pullRequest.files.nodes.push({path: pricingPolicy});
}, 'failed');
add('arbitrary Rego file is not a test', null, r => {
  r.issue0 = r.issue1;
  r.pullRequest.files.nodes.push({path: 'openbank-infra/opa/policies/pricing.rego'});
}, 'failed');
(async () => {
  for (const fixture of fixtures) {
    let result = 'passed';
    try {
      await run({graphql: async () => {
        if (fixture.errors !== null) throw {errors: fixture.errors, data: fixture.response};
        return fixture.response;
      }}, {repo: {owner: 'example', repo: 'example'},
        payload: {pull_request: {number: 3, body: 'Closes #1, fixes #2'}}},
      {info() {}, setFailed() { result = 'failed'; }});
    } catch { result = 'threw'; }
    require('node:assert/strict').equal(result, fixture.expected, fixture.name);
  }
  console.log(`${fixtures.length} real workflow GraphQL cases passed`);
})().catch(error => {console.error(error); process.exitCode = 1;});
"""
        result = subprocess.run(['node', '-e', runner], input=json.dumps({'script': script}),
                                text=True, capture_output=True, timeout=20)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
