# SPDX-License-Identifier: Apache-2.0
"""Active producer wiring controls; no workflow, shell helper or external service execution."""
import copy
import importlib.util
import tempfile
import unittest
from pathlib import Path

import yaml

SPEC = importlib.util.spec_from_file_location('ecosystem', Path(__file__).with_name('check-test-intelligence-ecosystem.py'))
ecosystem = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ecosystem)
ROOT = Path(__file__).resolve().parents[2]
NEEDLES = ('docker events', '--filter type=container', '--filter event=start', '--filter event=die')


class ActiveObserverTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.helper = self.root / '.github/scripts/service-ci-steps/build-test.sh'
        self.helper.parent.mkdir(parents=True)
        self.body = (ROOT / '.github/scripts/service-ci-steps/build-test.sh').read_text()
        self.helper.write_text(self.body)
        self.document = yaml.safe_load((ROOT / '.github/workflows/_service-ci.yml').read_text())
        self.steps = self.document['jobs']['build']['steps']
        self.build = next(step for step in self.steps if step.get('name') == 'Build + test (:${{ inputs.service }})')
        self.stage = next(step for step in self.steps if step.get('name') == 'Stage trusted service CI step helpers')

    def observed(self):
        source = ecosystem.service_ci_observer_source(self.root, yaml.safe_dump(self.document))
        return all(needle in source for needle in NEEDLES)

    def test_actual_recognized_staged_helper(self):
        self.assertTrue(self.observed())

    def test_original_inline_producer_remains_supported(self):
        self.build['run'] = self.body
        self.assertTrue(self.observed())

    def test_unused_good_helper_cannot_attest_bad_active_body(self):
        self.build['run'] = 'echo no-observer'
        self.assertFalse(self.observed())

    def test_each_missing_observer_filter_is_rejected(self):
        for needle in NEEDLES:
            with self.subTest(needle=needle):
                self.helper.write_text(self.body.replace(needle, 'removed'))
                self.assertFalse(self.observed())
        self.helper.write_text(self.body)

    def test_comments_and_echoes_are_not_observer_execution(self):
        for source in ['# ' + ' '.join(NEEDLES), 'echo "' + ' '.join(NEEDLES) + '"']:
            with self.subTest(source=source):
                self.helper.write_text(source)
                self.assertFalse(self.observed())

    def test_filter_text_inside_format_string_is_not_filter_arguments(self):
        self.helper.write_text('docker events --format "--filter type=container --filter event=start --filter event=die"')
        self.assertFalse(self.observed())

    def test_split_observers_cannot_combine_filter_evidence(self):
        self.helper.write_text('docker events --filter type=container --filter event=start\n'
                               'docker events --filter event=die\n')
        self.assertFalse(self.observed())

    def test_unquoted_staging_expansions_are_not_the_approved_recipe(self):
        original = self.stage['run']
        for quoted in ['"$helper_dir/"', '"$GITHUB_ENV"', '"${RUNNER_TEMP}/service-ci-helpers.XXXXXX"']:
            with self.subTest(quoted=quoted):
                self.stage['run'] = original.replace(quoted, quoted[1:-1])
                self.assertFalse(self.observed())
        self.stage['run'] = original

    def test_inexact_or_commented_invocation_is_rejected(self):
        original = self.build['run']
        for source in ['# ' + original, original.replace('build-test', 'unused'),
                       original.replace('build-test', '../build-test'),
                       original.replace('bash -e', 'bash')]:
            with self.subTest(source=source):
                self.build['run'] = source
                self.assertFalse(self.observed())

    def test_unique_active_build_required(self):
        self.steps.append(copy.deepcopy(self.build))
        self.assertFalse(self.observed())

    def test_disabled_build_or_missing_scalar_binding_rejected(self):
        for change in [{'if': 'false'}, {'continue-on-error': True}, {'env': {}}, {'shell': 'python'}]:
            with self.subTest(change=change):
                original = copy.deepcopy(self.build)
                self.build.update(change)
                self.assertFalse(self.observed())
                self.build.clear()
                self.build.update(original)

    def test_staging_must_be_unique_unconditional_and_before_checkout(self):
        for change in [{'if': 'false'}, {'continue-on-error': True}]:
            with self.subTest(change=change):
                original = copy.deepcopy(self.stage)
                self.stage.update(change)
                self.assertFalse(self.observed())
                self.stage.clear()
                self.stage.update(original)
        self.steps.remove(self.stage)
        self.steps.append(self.stage)
        self.assertFalse(self.observed())
        self.steps.insert(1, self.stage)
        self.assertFalse(self.observed())

    def test_missing_or_commented_copy_and_export_are_rejected(self):
        original = self.stage['run']
        for needle in ['cp .github/scripts/service-ci-steps/', 'echo "SERVICE_CI_HELPERS=', 'mktemp -d']:
            for comment in [False, True]:
                with self.subTest(needle=needle, comment=comment):
                    self.stage['run'] = '\n'.join(
                        ('# ' + line if comment else '') if needle in line else line
                        for line in original.splitlines())
                    self.assertFalse(self.observed())
        self.stage['run'] = original

    def test_wrong_copy_glob_and_untrusted_checkout_are_rejected(self):
        self.stage['run'] = self.stage['run'].replace('service-ci-steps/*.sh', '*.sh')
        self.assertFalse(self.observed())
        self.stage['run'] = next(s['run'] for s in yaml.safe_load(
            (ROOT / '.github/workflows/_service-ci.yml').read_text())['jobs']['build']['steps']
            if s.get('name') == self.stage['name'])
        self.steps[0]['with']['ref'] = '${{ inputs.ref }}'
        self.assertFalse(self.observed())

    def test_trusted_checkout_cannot_be_conditional_or_tolerate_failure(self):
        checkout = self.steps[0]
        for change in [{'if': 'false'}, {'continue-on-error': True}]:
            with self.subTest(change=change):
                original = copy.deepcopy(checkout)
                checkout.update(change)
                self.assertFalse(self.observed())
                checkout.clear()
                checkout.update(original)

    def test_missing_or_symlink_helper_is_rejected(self):
        self.helper.unlink()
        self.assertFalse(self.observed())
        outside = self.root / 'unused-good.sh'
        outside.write_text(self.body)
        self.helper.symlink_to(outside)
        self.assertFalse(self.observed())


if __name__ == '__main__':
    unittest.main()
