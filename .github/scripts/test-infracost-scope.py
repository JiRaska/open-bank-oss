# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Offline scope tests: no pricing API, cloud credentials, or GitHub requests."""
import importlib.util
import json
import os
import shutil
import sys
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import yaml

spec = importlib.util.spec_from_file_location('scope', Path(__file__).with_name('infracost-scope.py'))
scope = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scope)


class InfracostScopeTest(unittest.TestCase):
    def test_workflow_only_change_does_not_call_pricing_api(self):
        files = [{'filename': '.github/workflows/platform-tofu.yml', 'status': 'modified'},
                 {'filename': '.github/workflows/substrate-tofu.yml', 'status': 'modified'}]
        for target in scope.PREFIXES:
            self.assertFalse(scope.cost_scope(files, 2, target))

    def test_each_environment_prices_only_relevant_changes(self):
        platform = [{'filename': 'openbank-infra/aws/envs/sandbox-platform/main.tf', 'status': 'modified'}]
        substrate = [{'filename': 'openbank-infra/aws/envs/sandbox-substrate/main.tf', 'status': 'modified'}]
        module = [{'filename': 'openbank-infra/aws/modules/network/main.tf', 'status': 'modified'}]
        self.assertTrue(scope.cost_scope(platform, 1, 'platform'))
        self.assertFalse(scope.cost_scope(platform, 1, 'substrate'))
        self.assertFalse(scope.cost_scope(substrate, 1, 'platform'))
        self.assertTrue(scope.cost_scope(substrate, 1, 'substrate'))
        self.assertTrue(scope.cost_scope(module, 1, 'substrate'))
        self.assertFalse(scope.cost_scope(module, 1, 'platform'))
        self.assertFalse(scope.cost_scope(
            [{'filename': 'openbank-infra/aws/envs/web-prod/main.tf', 'status': 'modified'}], 1, 'platform'))

    def test_rename_out_of_environment_still_prices_removal(self):
        files = [{'filename': 'docs/retired.tf', 'status': 'renamed',
                  'previous_filename': 'openbank-infra/aws/envs/sandbox-platform/retired.tf'}]
        self.assertTrue(scope.cost_scope(files, 1, 'platform'))

    def test_incomplete_or_duplicate_file_inventory_fails_closed(self):
        files = [{'filename': 'docs/one.md', 'status': 'modified'}]
        with self.assertRaises(ValueError):
            scope.cost_scope(files, 2, 'platform')
        with self.assertRaises(ValueError):
            scope.cost_scope(files * 2, 2, 'platform')
        with self.assertRaises(ValueError):
            scope.cost_scope(files, 1, 'unknown')

    def test_runner_recipe_only_does_not_call_pricing_api(self):
        recipe = 'openbank-infra/aws/envs/sandbox-platform/runner-image/Dockerfile'
        for status in ('added', 'modified', 'removed'):
            with self.subTest(status=status):
                self.assertFalse(scope.cost_scope([{'filename': recipe, 'status': status}],
                                                  1, 'platform'))
        self.assertFalse(scope.cost_scope(
            [{'filename': recipe, 'status': 'renamed', 'previous_filename': 'docs/old-Dockerfile'}], 1, 'platform'))
        self.assertFalse(scope.cost_scope(
            [{'filename': 'docs/old-Dockerfile', 'status': 'renamed', 'previous_filename': recipe}], 1, 'platform'))

    def test_other_platform_inputs_and_mixed_changes_still_price(self):
        root = 'openbank-infra/aws/envs/sandbox-platform/'
        recipe = {'filename': root + 'runner-image/Dockerfile', 'status': 'modified'}
        for name in ('main.tf', 'main.tf.json', 'variables.tf', 'terragrunt.hcl',
                     '.terraform.lock.hcl', 'terraform.tfvars', 'terraform.tfvars.json',
                     'runner-image/variables.tf', 'new-unknown-input', 'README.md'):
            for status in ('added', 'modified', 'removed'):
                with self.subTest(name=name, status=status):
                    item = {'filename': root + name, 'status': status}
                    self.assertTrue(scope.cost_scope([item], 1, 'platform'))
                    self.assertTrue(scope.cost_scope([recipe, item], 2, 'platform'))

    def test_recipe_rename_preserves_both_sides_of_cost_change(self):
        recipe = 'openbank-infra/aws/envs/sandbox-platform/runner-image/Dockerfile'
        terraform = 'openbank-infra/aws/envs/sandbox-platform/main.tf'
        for filename, previous in ((recipe, terraform), (terraform, recipe)):
            with self.subTest(filename=filename):
                self.assertTrue(scope.cost_scope(
                    [{'filename': filename, 'status': 'renamed', 'previous_filename': previous}], 1, 'platform'))
        self.assertTrue(scope.cost_scope(
            [{'filename': 'docs/retired.tf', 'status': 'renamed', 'previous_filename': terraform}], 1, 'platform'))
        self.assertTrue(scope.cost_scope(
            [{'filename': terraform, 'status': 'renamed', 'previous_filename': 'docs/retired.tf'}], 1, 'platform'))

    def test_malformed_inventory_is_unknown_not_no_cost(self):
        bad_paths = (None, '', 42, [], '/main.tf', '../main.tf', 'a/../main.tf',
                     'a//main.tf', 'invalid\0path')
        for name in bad_paths:
            with self.subTest(filename=name), self.assertRaises(ValueError):
                scope.cost_scope([{'filename': name, 'status': 'modified'}], 1, 'platform')
        for previous in bad_paths[1:]:
            with self.subTest(previous=previous), self.assertRaises(ValueError):
                scope.cost_scope([{'filename': 'docs/file', 'status': 'modified', 'previous_filename': previous}],
                                 1, 'platform')
        for files, count in (([{}], 1), ([None], 1), (None, 0), ([], -1), ([], '0'), ([], False)):
            with self.subTest(files=files, count=count), self.assertRaises(ValueError):
                scope.cost_scope(files, count, 'platform')

    def test_renamed_recipe_requires_known_source_path(self):
        recipe = 'openbank-infra/aws/envs/sandbox-platform/runner-image/Dockerfile'
        for previous in ({}, {'previous_filename': None}, {'previous_filename': ''}):
            with self.subTest(previous=previous), self.assertRaises(ValueError):
                scope.cost_scope([{'filename': recipe, 'status': 'renamed', **previous}],
                                 1, 'platform')
        self.assertTrue(scope.cost_scope([{
            'filename': recipe, 'status': 'renamed',
            'previous_filename': 'openbank-infra/aws/envs/sandbox-platform/main.tf',
        }], 1, 'platform'))
        self.assertFalse(scope.cost_scope([{
            'filename': recipe, 'status': 'renamed',
            'previous_filename': 'docs/old-Dockerfile',
        }], 1, 'platform'))

    def test_all_documented_statuses_preserve_cost_scope(self):
        recipe = 'openbank-infra/aws/envs/sandbox-platform/runner-image/Dockerfile'
        terraform = 'openbank-infra/aws/envs/sandbox-platform/main.tf'
        for status in ('added', 'removed', 'modified', 'renamed', 'copied',
                       'changed', 'unchanged'):
            previous = {'previous_filename': 'docs/old-input'} if status == 'renamed' else {}
            with self.subTest(status=status):
                self.assertFalse(scope.cost_scope(
                    [{'filename': recipe, 'status': status, **previous}], 1, 'platform'))
                self.assertTrue(scope.cost_scope(
                    [{'filename': terraform, 'status': status, **previous}], 1, 'platform'))

    def test_cli_rejects_invalid_status_after_actual_jq_projection(self):
        recipe = 'openbank-infra/aws/envs/sandbox-platform/runner-image/Dockerfile'
        jq = shutil.which('jq')
        self.assertIsNotNone(jq, 'jq is required to exercise the production projection')
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            gh = directory / 'gh'
            gh.write_text(f'#!{sys.executable}\n' + '''
import json
import os
import subprocess
import sys

args = sys.argv[1:]
if args == ['api', 'repos/example/repo/pulls/123', '--jq', '.changed_files']:
    print(1)
elif args[:4] == ['api', '--paginate',
                 'repos/example/repo/pulls/123/files?per_page=100', '--jq'] and len(args) == 5:
    response = [json.loads(os.environ['SCOPE_TEST_FILE'])]
    result = subprocess.run([os.environ['SCOPE_TEST_JQ'], '-c', args[-1]],
                            input=json.dumps(response), text=True, check=False)
    sys.exit(result.returncode)
else:
    raise AssertionError(f'unexpected API request: {args}')
''')
            gh.chmod(0o755)
            invalid = ({}, {'status': None}, {'status': ''}, {'status': 'mystery'},
                       {'status': 42}, {'status': False}, {'status': []}, {'status': {}})
            for index, status in enumerate(invalid):
                with self.subTest(status=status):
                    output = directory / f'output-{index}'
                    env = {**os.environ, 'PATH': f'{directory}{os.pathsep}{os.environ["PATH"]}',
                           'GITHUB_REPOSITORY': 'example/repo', 'PR_NUMBER': '123',
                           'GITHUB_OUTPUT': str(output), 'SCOPE_TEST_JQ': jq,
                           'SCOPE_TEST_FILE': json.dumps({'filename': recipe, **status})}
                    result = subprocess.run([sys.executable, scope.__file__, 'platform'],
                                            env=env, capture_output=True, text=True, check=False)
                    self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                    self.assertIn('invalid PR file status', result.stderr)
                    self.assertFalse(output.exists(), result.stdout + result.stderr)

    def test_cli_writes_skip_only_after_complete_read(self):
        recipe = 'openbank-infra/aws/envs/sandbox-platform/runner-image/Dockerfile'
        good = ['1', json.dumps({'filename': recipe, 'status': 'modified'})]
        cases = (
            ('valid', good, None),
            ('missing', ['2', good[1]], ValueError),
            ('duplicate', ['2', good[1] + '\n' + good[1]], ValueError),
            ('malformed', ['1', '{'], ValueError),
            ('null-name', ['1', '{"filename":null}'], ValueError),
            ('rename-origin-missing', ['1', json.dumps({
                'filename': recipe, 'status': 'renamed',
            })], ValueError),
            ('transport', [subprocess.CalledProcessError(1, ['gh', 'api'])],
             subprocess.CalledProcessError),
        )
        for name, responses, error in cases:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as tmp:
                output = Path(tmp) / 'outputs'
                env = {'GITHUB_REPOSITORY': 'example/repo', 'PR_NUMBER': '123',
                       'GITHUB_OUTPUT': str(output)}
                with patch.dict(os.environ, env), patch.object(
                        scope.subprocess, 'check_output', side_effect=responses) as transport:
                    if error is None:
                        scope.main('platform')
                        self.assertEqual(output.read_text(), 'run=false\n')
                        self.assertEqual(transport.call_count, 2)
                        self.assertIn('--paginate', transport.call_args_list[1].args[0])
                        self.assertEqual(transport.call_args_list[1].args[0][-1],
                                         '.[] | {filename, previous_filename, status}')
                    else:
                        with self.assertRaises(error):
                            scope.main('platform')
                        self.assertFalse(output.exists())

    def test_both_workflows_gate_every_pricing_step_on_scope(self):
        root = Path(__file__).resolve().parents[2]
        for name, target in (('platform-tofu.yml', 'platform'),
                             ('substrate-tofu.yml', 'substrate')):
            workflow = yaml.safe_load((root / '.github/workflows' / name).read_text())
            steps = workflow['jobs']['infracost']['steps']
            detector = next(step for step in steps if step.get('id') == 'scope')
            self.assertEqual(detector['run'],
                             f'python3 .github/scripts/infracost-scope.py {target}')
            self.assertIn('GH_TOKEN', detector['env'])
            self.assertTrue(all(step.get('if') == "steps.scope.outputs.run == 'true'"
                                for step in steps[steps.index(detector) + 1:]))

    def test_platform_workflow_triggers_only_for_its_root(self):
        root = Path(__file__).resolve().parents[2]
        workflow = yaml.safe_load((root / '.github/workflows/platform-tofu.yml').read_text())
        for event in ('pull_request', 'push'):
            paths = workflow[True][event]['paths']  # PyYAML 1.1 parses "on" as bool
            self.assertIn('openbank-infra/aws/envs/sandbox-platform/**', paths)
            self.assertNotIn('openbank-infra/aws/envs/**', paths)


if __name__ == '__main__':
    unittest.main()
