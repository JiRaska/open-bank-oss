"""Negative controls for fast backend-to-Admin-UI contract selection."""
import importlib.util
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / 'select-admin-ui-rbac-tests.py'
spec = importlib.util.spec_from_file_location('selector', SCRIPT)
selector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(selector)


class SelectionTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = Path(self.tmp.name)
        self.backend = self.repo / 'openbank-treasury-service/src/main/TreasuryResource.kt'
        self.backend.parent.mkdir(parents=True)
        self.backend.write_text('class TreasuryResource')
        tests = self.repo / 'openbank-admin-ui/src/test'
        tests.mkdir(parents=True)
        (tests / 'treasury-rbac.guard.test.ts').write_text(
            "const resource = read('../../openbank-treasury-service/src/main/TreasuryResource.kt')"
        )

    def test_backend_change_selects_guard(self):
        self.assertEqual(['treasury-rbac.guard.test.ts'], selector.select(
            self.repo, {'openbank-treasury-service/src/main/TreasuryResource.kt'}))

    def test_unrelated_backend_change_stays_fast(self):
        self.assertEqual([], selector.select(self.repo, {'openbank-ledger-service/src/main/Ledger.kt'}))

    def test_any_gitops_change_selects_registry_guard(self):
        for path in (
            'openbank-infra/gitops/apps/pricing.yaml',
            'openbank-infra/gitops/components/pricing/workloads.yaml',
            'openbank-infra/gitops/components/pricing/kustomization.yaml',
        ):
            with self.subTest(path=path):
                self.assertTrue(selector.registry_guard_needed({path}))

    def test_unrelated_change_does_not_select_registry_guard(self):
        self.assertFalse(selector.registry_guard_needed({
            'openbank-infra/scripts/check-version-lifecycle.py',
            'openbank-admin-ui/src/lib/discovery.ts',
        }))

    def test_missing_backend_source_fails_closed(self):
        self.backend.unlink()
        with self.assertRaisesRegex(ValueError, 'source missing'):
            selector.dependencies(self.repo)

    def test_dynamic_backend_source_fails_closed(self):
        test = self.repo / 'openbank-admin-ui/src/test/treasury-rbac.guard.test.ts'
        test.write_text("const resource = read('../../openbank-' + service)")
        with self.assertRaisesRegex(ValueError, 'dynamic backend'):
            selector.dependencies(self.repo)

    def test_assembled_backend_source_fails_closed(self):
        test = self.repo / 'openbank-admin-ui/src/test/treasury-rbac.guard.test.ts'
        test.write_text("const resource = read('../../' + serviceName + '/src/main/TreasuryResource.kt')")
        with self.assertRaisesRegex(ValueError, 'dynamic backend'):
            selector.dependencies(self.repo)

    def test_indirect_backend_source_is_selected(self):
        test = self.repo / 'openbank-admin-ui/src/test/treasury-rbac.guard.test.ts'
        test.write_text("const source = '../../openbank-treasury-service/src/main/TreasuryResource.kt'\nconst resource = read(source)")
        self.assertEqual(['treasury-rbac.guard.test.ts'], selector.select(
            self.repo, {'openbank-treasury-service/src/main/TreasuryResource.kt'}))

    def test_nested_read_file_sync_is_selected(self):
        test = self.repo / 'openbank-admin-ui/src/test/treasury-rbac.guard.test.ts'
        test.write_text("const resource = readFileSync(resolve(root, '../../openbank-treasury-service/src/main/TreasuryResource.kt'), 'utf8')")
        self.assertEqual(['treasury-rbac.guard.test.ts'], selector.select(
            self.repo, {'openbank-treasury-service/src/main/TreasuryResource.kt'}))

    def test_multi_source_guard_selected_once(self):
        other = self.repo / 'openbank-risk-engine/src/main/RiskResource.kt'
        other.parent.mkdir(parents=True)
        other.write_text('class RiskResource')
        test = self.repo / 'openbank-admin-ui/src/test/treasury-rbac.guard.test.ts'
        test.write_text(test.read_text() + "\nconst other = read('../../openbank-risk-engine/src/main/RiskResource.kt')")
        self.assertEqual(['treasury-rbac.guard.test.ts'], selector.select(self.repo, {
            'openbank-treasury-service/src/main/TreasuryResource.kt',
            'openbank-risk-engine/src/main/RiskResource.kt',
        }))


class AggregateTest(unittest.TestCase):
    def test_selected_guard_must_succeed_in_required_aggregate(self):
        workflow = (SCRIPT.parents[1] / 'workflows/ci.yml').read_text()
        marker = '      - name: Check build result\n'
        self.assertEqual(workflow.count(marker), 1)
        block = workflow.split(marker, 1)[1].split('        run: |\n', 1)[1]
        lines = []
        for line in block.splitlines():
            if line and not line.startswith('          '):
                break
            lines.append(line[10:] if line else '')
        script = '\n'.join(lines)
        self.assertIn('Admin UI cross-package guards did not pass', script)

        base = dict(os.environ, DETECTION_RESULT='success', UI_CHANGED='false',
                    RBAC_TESTS='treasury-rbac.guard.test.ts', REGISTRY_TEST='false',
                    RBAC_RESULT='success', RESULT='skipped', EVENT='pull_request', REF='refs/pull/1/merge')
        cases = [
            ('selected RBAC passed', {}, 0),
            ('selected registry passed', {'RBAC_TESTS': '', 'REGISTRY_TEST': 'true'}, 0),
            ('selected RBAC skipped', {'RBAC_RESULT': 'skipped'}, 1),
            ('selected RBAC cancelled', {'RBAC_RESULT': 'cancelled'}, 1),
            ('selected RBAC failed', {'RBAC_RESULT': 'failure'}, 1),
            ('selected registry skipped', {'RBAC_TESTS': '', 'REGISTRY_TEST': 'true',
                                           'RBAC_RESULT': 'skipped'}, 1),
            ('selected registry cancelled on push', {'RBAC_TESTS': '', 'REGISTRY_TEST': 'true',
                                                     'RBAC_RESULT': 'cancelled', 'EVENT': 'push'}, 1),
            ('unselected guard skipped', {'RBAC_TESTS': '', 'RBAC_RESULT': 'skipped'}, 0),
            ('change detection failed', {'DETECTION_RESULT': 'failure'}, 1),
        ]
        for label, changes, expected in cases:
            with self.subTest(label=label):
                result = subprocess.run(['bash', '-e', '-o', 'pipefail', '-c', script],
                                        env={**base, **changes}, capture_output=True, text=True,
                                        check=False)
                self.assertEqual(result.returncode, expected, result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
