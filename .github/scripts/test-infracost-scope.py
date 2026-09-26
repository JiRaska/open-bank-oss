# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Offline scope tests: no pricing API, cloud credentials, or GitHub requests."""
import importlib.util
import unittest
from pathlib import Path

import yaml

spec = importlib.util.spec_from_file_location('scope', Path(__file__).with_name('infracost-scope.py'))
scope = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scope)


class InfracostScopeTest(unittest.TestCase):
    def test_workflow_only_change_does_not_call_pricing_api(self):
        files = [{'filename': '.github/workflows/platform-tofu.yml'},
                 {'filename': '.github/workflows/substrate-tofu.yml'}]
        for target in scope.PREFIXES:
            self.assertFalse(scope.cost_scope(files, 2, target))

    def test_each_environment_prices_only_relevant_changes(self):
        platform = [{'filename': 'openbank-infra/aws/envs/sandbox-platform/main.tf'}]
        substrate = [{'filename': 'openbank-infra/aws/envs/sandbox-substrate/main.tf'}]
        module = [{'filename': 'openbank-infra/aws/modules/network/main.tf'}]
        self.assertTrue(scope.cost_scope(platform, 1, 'platform'))
        self.assertFalse(scope.cost_scope(platform, 1, 'substrate'))
        self.assertFalse(scope.cost_scope(substrate, 1, 'platform'))
        self.assertTrue(scope.cost_scope(substrate, 1, 'substrate'))
        self.assertTrue(scope.cost_scope(module, 1, 'substrate'))
        self.assertFalse(scope.cost_scope(module, 1, 'platform'))
        self.assertFalse(scope.cost_scope(
            [{'filename': 'openbank-infra/aws/envs/web-prod/main.tf'}], 1, 'platform'))

    def test_rename_out_of_environment_still_prices_removal(self):
        files = [{'filename': 'docs/retired.tf',
                  'previous_filename': 'openbank-infra/aws/envs/sandbox-platform/retired.tf'}]
        self.assertTrue(scope.cost_scope(files, 1, 'platform'))

    def test_incomplete_or_duplicate_file_inventory_fails_closed(self):
        files = [{'filename': 'docs/one.md'}]
        with self.assertRaises(ValueError):
            scope.cost_scope(files, 2, 'platform')
        with self.assertRaises(ValueError):
            scope.cost_scope(files * 2, 2, 'platform')
        with self.assertRaises(ValueError):
            scope.cost_scope(files, 1, 'unknown')

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
