#!/usr/bin/env python3
"""Negative controls for fast backend-to-Admin-UI contract selection."""
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

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

    def test_party_resource_change_runs_real_focused_workflow(self):
        repo = SCRIPT.parents[2]
        party = (
            'openbank-party-service/src/main/kotlin/com/openbank/party/'
            'infrastructure/rest/PartyResource.kt'
        )
        selected = selector.select(repo, {party})
        self.assertEqual(['party-rbac-alignment.guard.test.ts'], selected)

        workflow = (repo / '.github/workflows/ci.yml').read_text()
        step = workflow.split('      - name: Verify affected cross-package guards\n', 1)[1]
        step = step.split('\n  ui-build:', 1)[0].split('        run: |\n', 1)[1]
        command = '\n'.join(line[10:] for line in step.splitlines())
        env = dict(os.environ, RBAC_TESTS=' '.join(selected), REGISTRY_TEST='false')
        result = subprocess.run(
            ['bash', '-c', 'npm() { printf "%s\\n" "$@"; }\n' + command],
            env=env, capture_output=True, text=True, check=True,
        )
        self.assertEqual(
            ['run', 'test', '--', 'src/test/party-rbac-alignment.guard.test.ts'],
            result.stdout.splitlines(),
        )

    def test_undeclared_cross_package_guard_fails_detection(self):
        test = self.repo / 'openbank-admin-ui/src/test/new-backend.guard.test.ts'
        test.write_text("const resource = read('../../openbank-treasury-service/src/main/TreasuryResource.kt')")
        with self.assertRaisesRegex(ValueError, 'undeclared cross-package guard'):
            selector.dependencies(self.repo)

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


if __name__ == '__main__':
    unittest.main()
