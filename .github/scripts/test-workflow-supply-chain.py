#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
import copy
import importlib.util
import unittest
from pathlib import Path

import yaml

spec = importlib.util.spec_from_file_location('guard', Path(__file__).with_name('check-workflow-supply-chain.py'))
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class SupplyChainTest(unittest.TestCase):
    def test_tags_fail_for_steps_and_reusable_jobs(self):
        for job in ({'uses': 'owner/action@v1'}, {'steps': [{'uses': 'actions/checkout@v4'}]}):
            self.assertTrue(guard.findings('example.yml', {'jobs': {'test': job}}))

    def test_pins_and_local_actions_pass(self):
        job = {'steps': [{'uses': './local'}, {'uses': 'actions/checkout@' + 'a' * 40}]}
        self.assertFalse(guard.findings('example.yml', {'jobs': {'test': job}}))

    def test_slsa_exception_cannot_spread(self):
        doc = {'jobs': {'provenance': {'uses': guard.SLSA}}}
        self.assertFalse(guard.findings('release-please.yml', doc))
        self.assertTrue(guard.findings('other.yml', doc))
        doc['jobs']['provenance']['uses'] = guard.SLSA.replace('v2.1.0', 'main')
        self.assertTrue(guard.findings('release-please.yml', doc))

    def test_trigger_filter_regression(self):
        for name in ('main-red-watch.yml', 'admin-ui-deploy.yml'):
            self.assertTrue(guard.findings(name, {'on': {'workflow_run': {}}, 'jobs': {}}))
            self.assertFalse(guard.findings(name, {'on': {'workflow_run': {'branches': ['main']}}, 'jobs': {}}))

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

    def test_services_ci_dispatch_and_fail_closed_contract(self):
        original = yaml.safe_load((guard.ROOT / '.github/workflows/services-ci.yml').read_text())
        self.assertFalse(guard.findings('services-ci.yml', original))
        for mutation in ('missing-plan-output', 'unbounded-verifier', 'detector-failure-passes'):
            doc = copy.deepcopy(original)
            if mutation == 'missing-plan-output':
                doc['jobs']['changes']['outputs'].pop('verification-modules')
            elif mutation == 'unbounded-verifier':
                doc['jobs']['verification-metadata']['if'] = 'always()'
            else:
                doc['jobs']['all-green']['steps'][0]['run'] = 'echo green'
            with self.subTest(mutation=mutation):
                self.assertTrue(guard.findings('services-ci.yml', doc))


if __name__ == '__main__':
    unittest.main()
