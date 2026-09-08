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


if __name__ == '__main__':
    unittest.main()
