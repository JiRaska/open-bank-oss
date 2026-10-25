#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Exercise the actual default-branch collector and its credential boundary."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / '.github/workflows/ruleset-bypass-evidence.yml'


class EvidenceCollectorTest(unittest.TestCase):
    def test_default_branch_and_token_boundary(self):
        workflow = yaml.safe_load(WORKFLOW.read_text())
        self.assertEqual(workflow.get('on', workflow.get(True)), {
            'workflow_run': {'workflows': ['CI'], 'types': ['in_progress']}})
        self.assertEqual(workflow['permissions'], {})
        job, = workflow['jobs'].values()
        self.assertEqual(job['runs-on'], 'ubuntu-latest')
        steps = job['steps']
        self.assertEqual(len(steps), 3)
        self.assertTrue(steps[0]['uses'].startswith('actions/create-github-app-token@'))
        self.assertEqual(steps[0]['with'], {
            'app-id': '${{ secrets.GITOPS_APP_ID }}',
            'private-key': '${{ secrets.GITOPS_APP_PRIVATE_KEY }}',
            'owner': '${{ github.repository_owner }}',
            'repositories': '${{ github.event.repository.name }}',
            'permission-administration': 'write',
        })
        self.assertTrue(steps[1]['uses'].startswith('actions/github-script@'))
        self.assertEqual(steps[1]['with']['github-token'], '${{ steps.token.outputs.token }}')
        self.assertTrue(steps[2]['uses'].startswith('actions/upload-artifact@'))
        self.assertEqual(steps[2]['with']['path'], '${{ runner.temp }}/snapshot.json')
        for step in steps:
            self.assertNotIn('run', step)
            self.assertNotIn('continue-on-error', step)
            self.assertRegex(step['uses'], r'@[0-9a-f]{40}$')
        self.assertNotIn('checkout', WORKFLOW.read_text())
        self.assertNotIn('secrets: inherit', WORKFLOW.read_text())

    def test_real_collector_script(self):
        job, = yaml.safe_load(WORKFLOW.read_text())['jobs'].values()
        script = job['steps'][1]['with']['script']
        harness = r'''
const fs = require('fs');
const input = JSON.parse(fs.readFileSync(0, 'utf8'));
const requests = [];
const github = {
  paginate: async (route, args) => {
    requests.push({route, args});
    if (input.denied) throw new Error('Resource not accessible by integration (403)');
    return input.summaries;
  },
  request: async (route, args) => {
    requests.push({route, args});
    if (route !== 'GET /repos/{owner}/{repo}/rulesets/{ruleset_id}') throw new Error('Unexpected API operation');
    return {data: input.details[String(args.ruleset_id)]};
  }
};
const context = {repo: {owner: 'fixture', repo: 'bank'},
  payload: {workflow_run: {id: 321, run_attempt: 2}}};
const AsyncFunction = Object.getPrototypeOf(async function(){}).constructor;
(async () => {
  let error = null;
  try { await new AsyncFunction('github', 'context', 'require', input.script)(github, context, require); }
  catch (e) { error = e.message; }
  const path = `${process.env.RUNNER_TEMP}/snapshot.json`;
  console.log(JSON.stringify({error, requests,
    snapshot: fs.existsSync(path) ? JSON.parse(fs.readFileSync(path, 'utf8')) : null}));
})();
'''
        active = {'id': 1, 'enforcement': 'active', 'target': 'branch'}
        cases = [
            ('empty', [active], {'1': {'bypass_actors': []}}, False, False),
            ('missing-field', [active], {'1': {}}, False, False),
            ('denied', [active], {}, True, True),
            ('bad-id', [{**active, 'id': '../other'}], {}, False, True),
            ('bad-list', None, {}, False, True),
            ('inactive', [{**active, 'enforcement': 'disabled'}], {}, False, False),
            ('all-pages', [active, {**active, 'id': 2}],
             {'1': {'bypass_actors': []}, '2': {'bypass_actors': [{'actor_id': 9}]}}, False, False),
        ]
        for name, summaries, details, denied, fails in cases:
            with self.subTest(case=name), tempfile.TemporaryDirectory() as temp:
                run = subprocess.run(['node', '-e', harness], input=json.dumps({
                    'script': script, 'summaries': summaries, 'details': details, 'denied': denied}),
                    env={**os.environ, 'RUNNER_TEMP': temp, 'GITHUB_RUN_ID': '900',
                         'GITHUB_RUN_ATTEMPT': '1'}, text=True, capture_output=True, timeout=10)
                self.assertEqual(run.returncode, 0, run.stderr)
                result = json.loads(run.stdout)
                self.assertEqual(result['error'] is not None, fails, result)
                self.assertTrue(all(r['route'].startswith('GET ') for r in result['requests']))
                if fails:
                    self.assertIsNone(result['snapshot'])
                else:
                    snapshot = result['snapshot']
                    self.assertEqual(snapshot['repository'], 'fixture/bank')
                    self.assertEqual((snapshot['run_id'], snapshot['run_attempt']), ('321', '2'))
                    self.assertEqual((snapshot['source_run_id'], snapshot['source_run_attempt']), ('900', '1'))
                    if name == 'missing-field':
                        self.assertNotIn('bypass_actors', snapshot['details']['1'])
                    if name == 'empty':
                        self.assertEqual(snapshot['details']['1']['bypass_actors'], [])
                    if name == 'all-pages':
                        self.assertEqual(len(snapshot['details']), 2)


if __name__ == '__main__':
    unittest.main()
