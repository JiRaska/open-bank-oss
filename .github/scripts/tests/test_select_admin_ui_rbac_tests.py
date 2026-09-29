#!/usr/bin/env python3
"""Negative controls for fast backend-to-Admin-UI contract selection."""
import importlib.util
from pathlib import Path
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
