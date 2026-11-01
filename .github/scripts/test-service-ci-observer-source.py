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



class BoundedCoverageTest(unittest.TestCase):
    setUp = ActiveObserverTest.setUp

    def bounded(self):
        return ecosystem.bounded_kover_report(yaml.safe_dump(self.document), self.root)

    def prepare_report(self):
        self.report = next(step for step in self.steps if step.get('name') == 'Generate Kover XML report')
        self.report_helper = self.helper.with_name('render-coverage-report.sh')
        self.report_body = (ROOT / '.github/scripts/service-ci-steps/render-coverage-report.sh').read_text()
        self.report_helper.write_text(self.report_body)
        self.shared_helper = self.helper.with_name("coverage-evidence.sh")
        self.shared_body = (ROOT / ".github/scripts/service-ci-steps/coverage-evidence.sh").read_text()
        self.shared_helper.write_text(self.shared_body)

    def test_real_staged_helper_is_bounded(self):
        self.prepare_report()
        self.assertTrue(self.bounded())

    def test_missing_changed_or_symlinked_shared_validator_is_rejected(self):
        self.prepare_report()
        self.shared_helper.unlink()
        self.assertFalse(self.bounded())
        self.shared_helper.write_text('coverage_run() { "$@"; }')
        self.assertFalse(self.bounded())
        self.shared_helper.unlink()
        outside = self.root / 'outside-validator.sh'
        outside.write_text(self.shared_body)
        self.shared_helper.symlink_to(outside)
        self.assertFalse(self.bounded())

    def test_missing_or_changed_timeout_and_inexact_call_are_rejected(self):
        self.prepare_report()
        original = self.report['run']
        for command in [original.replace('600s', '900s'), original.replace('--kill-after=10s ', ''),
                        original.replace('timeout --kill-after=10s 600s ', ''),
                        original + ' &', '# ' + original, original.replace('bash -e', 'bash'),
                        original.replace('render-coverage-report', 'unused')]:
            with self.subTest(command=command):
                self.report['run'] = command
                self.assertFalse(self.bounded())
        self.report['run'] = original

    def test_disabled_or_ambiguous_report_and_missing_binding_are_rejected(self):
        self.prepare_report()
        original = copy.deepcopy(self.report)
        for change in [{'if': 'false'}, {'shell': 'python'}, {'env': {}}, {'continue-on-error': False}]:
            with self.subTest(change=change):
                self.report.update(change)
                self.assertFalse(self.bounded())
                self.report.clear()
                self.report.update(original)
        self.steps.append(copy.deepcopy(self.report))
        self.assertFalse(self.bounded())

    def test_untrusted_staging_is_rejected(self):
        self.prepare_report()
        self.stage['run'] = 'echo SERVICE_CI_HELPERS=untrusted >> "$GITHUB_ENV"'
        self.assertFalse(self.bounded())

    def test_heredoc_and_dead_report_text_cannot_attest_execution(self):
        self.prepare_report()
        command = './gradlew --no-daemon --configure-on-demand :$SERVICE:koverXmlReport'
        for body in ["cat <<'UNUSED'\n" + command + '\nUNUSED\n', 'exit 0\n' + command + '\n']:
            with self.subTest(body=body):
                self.report_helper.write_text(body)
                self.assertFalse(self.bounded())

    def test_report_must_follow_trusted_stage_and_enforcing_build(self):
        self.prepare_report()
        self.steps.remove(self.report)
        for before in [0, self.steps.index(self.stage), self.steps.index(self.build)]:
            with self.subTest(before=before):
                self.steps.insert(before, self.report)
                self.assertFalse(self.bounded())
                self.steps.remove(self.report)

    def test_missing_symlinked_or_nonreport_helper_is_rejected(self):
        self.prepare_report()
        self.report_helper.unlink()
        self.assertFalse(self.bounded())
        outside = self.root / 'unused-good.sh'
        outside.write_text(self.report_body)
        self.report_helper.symlink_to(outside)
        self.assertFalse(self.bounded())
        self.report_helper.unlink()
        for body in ['echo no-report', '# ' + self.report_body.replace('\n', '\n# '),
                     self.report_body.replace('--no-daemon', ''),
                     self.report_body.replace('koverXmlReport', 'koverXmlReportOther')]:
            with self.subTest(body=body):
                self.report_helper.write_text(body)
                self.assertFalse(self.bounded())


class DiagnosticCompositeTest(unittest.TestCase):
    def setUp(self):
        import shutil
        ActiveObserverTest.setUp(self)
        for relative in ('.github/actions/service-ci-coverage-evidence/action.yml',
                         '.github/scripts/service-ci-steps/diagnostic-actions.sh'):
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, target)
        self.call = next(step for step in self.steps
                         if step.get('uses') == './.github/actions/service-ci-coverage-evidence')
        self.history = next(step for step in self.steps
                            if step.get('name') == 'Check out historical service source with trusted helpers')

    def observed(self):
        source = ecosystem.service_ci_evidence_source(self.root, yaml.safe_dump(self.document))
        return 'collect-test-run-evidence.py' in source and 'build/test-intelligence/run.json' in source

    def test_actual_active_composite_is_resolved(self):
        self.assertTrue(self.observed())

    def test_unused_disabled_or_wrongly_bound_composite_is_rejected(self):
        for change in [{'uses': './.github/actions/unused'}, {'if': 'false'},
                       {'continue-on-error': False}, {'env': {}},
                       {'env': {**self.call['env'], 'SERVICE_CI_REPORT_OUTCOME': 'success'}}]:
            with self.subTest(change=change):
                original = copy.deepcopy(self.call)
                self.call.update(change)
                self.assertFalse(self.observed())
                self.call.clear()
                self.call.update(original)
        self.steps.append(copy.deepcopy(self.call))
        self.assertFalse(self.observed())

    def test_missing_changed_or_symlinked_snapshot_and_action_are_rejected(self):
        for relative in ('.github/actions/service-ci-coverage-evidence/action.yml',
                         '.github/scripts/service-ci-steps/diagnostic-actions.sh'):
            path = self.root / relative
            original = path.read_bytes()
            path.unlink()
            self.assertFalse(self.observed())
            path.write_bytes(original + b'\n# different reviewed source\n')
            self.assertFalse(self.observed())
            path.unlink()
            outside = self.root / 'outside-file'
            outside.write_bytes(original)
            path.symlink_to(outside)
            self.assertFalse(self.observed())
            path.unlink()
            path.write_bytes(original)
            self.assertTrue(self.observed())

    def test_historical_restore_must_be_reachable_in_exact_reviewed_recipe(self):
        original = self.history['run']
        restore = 'bash "$SERVICE_CI_HELPERS/diagnostic-actions.sh" restore "$SERVICE_CI_HELPERS"'
        for replacement in ['exit 0\n' + restore, '# ' + restore,
                            "cat <<'IGNORED'\n" + restore + '\nIGNORED\nexit 0\n' + restore]:
            with self.subTest(replacement=replacement):
                self.history['run'] = original.replace(restore, replacement)
                self.assertFalse(self.observed())
        self.history['run'] = original
        self.assertTrue(self.observed())

    def test_historical_restore_cannot_override_trusted_environment_or_workspace(self):
        original = copy.deepcopy(self.history)
        for change in [{'env': {'REF': '${{ inputs.ref }}', 'SERVICE_CI_HELPERS': 'untrusted'}},
                       {'env': {'REF': 'different-ref'}}, {'env': {}},
                       {'working-directory': 'different-workspace'}, {'shell': 'bash'},
                       {'continue-on-error': True}]:
            with self.subTest(change=change):
                self.history.update(change)
                self.assertFalse(self.observed())
                self.history.clear()
                self.history.update(copy.deepcopy(original))
        self.assertTrue(self.observed())

    def test_inherited_run_defaults_cannot_redirect_snapshot_execution(self):
        for target in (self.document, self.document['jobs']['build']):
            for value in ({'run': {'working-directory': 'different-workspace'}},
                          {'run': {'shell': 'bash {0}'}}):
                with self.subTest(defaults=value):
                    target['defaults'] = value
                    self.assertFalse(self.observed())
                    del target['defaults']
        self.assertTrue(self.observed())

    def test_snapshot_stage_cannot_redirect_environment_or_workspace(self):
        original = copy.deepcopy(self.stage)
        for change in [{'env': {'GITHUB_ENV': 'different-environment-file'}},
                       {'working-directory': 'different-workspace'}]:
            with self.subTest(change=change):
                self.stage.update(change)
                self.assertFalse(self.observed())
                self.stage.clear()
                self.stage.update(copy.deepcopy(original))
        self.assertTrue(self.observed())

    def test_snapshot_restore_and_order_cannot_be_removed(self):
        for target, needle in [(self.stage, 'bash "$helper_dir/diagnostic-actions.sh" stage "$helper_dir"'),
                               (self.history, 'bash "$SERVICE_CI_HELPERS/diagnostic-actions.sh" restore "$SERVICE_CI_HELPERS"')]:
            original = target['run']
            for replacement in ['', '# ' + needle]:
                target['run'] = original.replace(needle, replacement)
                self.assertFalse(self.observed())
            target['run'] = original
        self.steps.remove(self.call)
        self.steps.insert(0, self.call)
        self.assertFalse(self.observed())



if __name__ == '__main__':
    unittest.main()
