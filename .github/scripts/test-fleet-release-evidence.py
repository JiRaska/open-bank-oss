# SPDX-License-Identifier: Apache-2.0
"""Offline regression proof for the release inventory; no credentials or network."""
from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import subprocess
import tempfile
import time
import unittest
from pathlib import Path
from typing import ClassVar
from unittest import mock

SCRIPT = Path(__file__).with_name('check-fleet-release-evidence.py')
SPEC = importlib.util.spec_from_file_location('fleet_release_evidence', SCRIPT)
fleet = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fleet)


def release(version='1.2.3', *, release_id=1, **changes):
    tag = f'fixture-service-v{version}'
    return {'id': release_id, 'tag_name': tag, 'published_at': '2020-01-01T00:00:00Z',
            'draft': False, 'prerelease': False,
            'assets': [{'name': f'{tag}{suffix}', 'size': 10} for suffix in fleet.ASSET_SUFFIXES],
            **changes}


class InventoryTest(unittest.TestCase):
    def fixture(self, root):
        (root / 'release-please-config.json').write_text(json.dumps({'packages': {
            'openbank-known': {'component': 'known'}, 'openbank-unmapped': {},
            'openbank-admin-ui': {'component': 'admin-ui'}}}))
        for name in ('openbank-known', 'openbank-unmapped', 'openbank-admin-ui'):
            (root / name).mkdir()
            (root / name / 'version.txt').write_text('1.0.0\n')
        gitops = root / 'openbank-infra/gitops'
        gitops.mkdir(parents=True)
        (gitops / 'deployment.yaml').write_text('image: registry/openbank-known:sandbox-abc\n')

    def test_unversioned_docker_and_gitops_workloads_are_unresolved(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root)
            (root / 'openbank-future').mkdir()
            (root / 'openbank-future/Dockerfile.deploy').write_text('FROM scratch\n')
            (root / 'openbank-infra/gitops/extra.yaml').write_text(
                'initContainers:\n- image: registry/openbank-sidecar@sha256:' + 'a' * 64 + '\n')
            _components, findings = fleet.expected_components(root)
            for name in ('openbank-future', 'openbank-sidecar'):
                self.assertTrue(any(name in message for message in findings), findings)

    def test_denominator_includes_unmapped_and_explicit_exemption(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root)
            original_read = Path.read_text
            with (mock.patch.object(fleet, 'ROOT', root),
                  mock.patch.object(fleet, 'fetch_releases', return_value=[]),
                  mock.patch.object(fleet, 'workflow_findings', return_value=[]),
                  mock.patch.object(Path, 'read_text', autospec=True) as read):
                # Preserve actual config reads; supply only the workflow input.
                read.side_effect = lambda path, *a, **kw: ('unused' if path.name == 'verify-release-evidence.yml'
                                                         else original_read(path, *a, **kw))
                output = io.StringIO()
                with mock.patch('sys.argv', [str(SCRIPT)]), contextlib.redirect_stdout(output):
                    self.assertNotEqual(fleet.main(), 0)
            self.assertIn('SUBJECTS=3', output.getvalue())
            self.assertIn('EXEMPT openbank-admin-ui', output.getvalue())

    def test_missing_gitops_inventory_is_unresolved(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root)
            (root / 'openbank-infra/gitops/deployment.yaml').unlink()
            with self.assertRaises(ValueError):
                fleet.expected_components(root)


class LatestReleaseTest(unittest.TestCase):
    components: ClassVar[dict[str, str]] = {'fixture-service': 'openbank-fixture-service'}

    def check(self, releases):
        return fleet.check_fleet(self.components, releases)

    def test_semver_precedence_wins_over_republication_and_lexical_order(self):
        high = release('1.10.0')
        low = release('1.9.0', release_id=2, published_at='2022-01-01T00:00:00Z')
        tags, findings = self.check([low, high])
        self.assertEqual(tags, ['fixture-service-v1.10.0'])
        self.assertEqual(findings, [])

    def test_missing_or_nonboolean_flags_are_findings_even_with_older_good_release(self):
        for field in ('draft', 'prerelease'):
            for value in (None, 'false', 0, [], {}):
                with self.subTest(field=field, value=value):
                    newer = release('2.0.0', release_id=2, **{field: value})
                    self.assertTrue(self.check([release(), newer])[1])
            newer = release('2.0.0', release_id=2)
            del newer[field]
            self.assertTrue(self.check([release(), newer])[1])

    def test_real_drafts_and_prereleases_do_not_replace_stable_release(self):
        self.assertEqual(self.check([release(), release('2.0.0', release_id=2, draft=True),
                                     release('3.0.0-rc.1', release_id=3, prerelease=True)]),
                         (['fixture-service-v1.2.3'], []))

    def test_bad_stable_versions_are_findings(self):
        for version in ('not-a-version', '01.2.3', '1.2', '1.2.3-rc.1'):
            with self.subTest(version=version):
                self.assertTrue(self.check([release(version)])[1])

    def test_build_metadata_does_not_resolve_equal_precedence_ambiguity(self):
        self.assertTrue(self.check([release(), release('1.2.3+build', release_id=2)])[1])

    def test_duplicate_release_identity_is_unresolved(self):
        self.assertTrue(self.check([release(), release()])[1])

    def test_timestamp_and_identity_must_be_valid(self):
        for changes in ({'published_at': 'not-a-date'}, {'published_at': '2020-01-01'},
                        {'published_at': '2020-01-01T00:00:00'}, {'id': True}, {'id': None}):
            with self.subTest(changes=changes):
                self.assertTrue(self.check([release(**changes)])[1])

    def test_missing_or_bad_asset_metadata_is_not_clean(self):
        good = release()
        for assets in (None, good['assets'][:-1], good['assets'] + [good['assets'][0]],
                       [{**a, 'size': -1} for a in good['assets']],
                       [{**a, 'size': True} for a in good['assets']],
                       [{**a, 'size': 0} for a in good['assets']]):
            with self.subTest(assets=assets):
                self.assertTrue(self.check([{**good, 'assets': assets}])[1])

    def test_latest_inside_grace_is_pending_without_older_substitution(self):
        newer = release('2.0.0', release_id=2,
                        published_at=fleet.dt.datetime.now(fleet.dt.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'))
        tags, findings = self.check([release(), newer])
        self.assertEqual(tags, ['fixture-service-v2.0.0'])
        self.assertTrue(any('pending' in finding for finding in findings))


class FetchTest(unittest.TestCase):
    def test_hard_total_deadline_interrupts_slow_response(self):
        def stalled(*_args, **_kwargs):
            time.sleep(.3)
            raise AssertionError('fetch deadline did not interrupt stalled opener')
        started = time.monotonic()
        with (mock.patch.object(fleet.urllib.request, 'urlopen', side_effect=stalled),
              self.assertRaises(TimeoutError)):
            fleet.fetch_releases('fixture/repo', 'fixture-token', budget_seconds=.03)
        self.assertLess(time.monotonic() - started, .2)

    def test_paginated_repeated_release_identity_is_unresolved(self):
        page = [release(release_id=i + 1) for i in range(100)]
        responses = [mock.MagicMock(), mock.MagicMock()]
        for response, content in zip(responses, (page, [page[0]]), strict=True):
            response.__enter__.return_value = io.BytesIO(json.dumps(content).encode())
        with (mock.patch.object(fleet.urllib.request, 'urlopen', side_effect=responses),
              self.assertRaises(ValueError)):
            fleet.fetch_releases('fixture/repo', 'fixture-token')

    def test_malformed_page_is_unresolved(self):
        response = mock.MagicMock()
        response.__enter__.return_value = io.BytesIO(b'{}')
        with (mock.patch.object(fleet.urllib.request, 'urlopen', return_value=response),
              self.assertRaises(TypeError)):
            fleet.fetch_releases('fixture/repo', 'fixture-token')


class ReportTest(unittest.TestCase):
    def test_every_expected_subject_is_reported_without_crypto_claim(self):
        subjects = {'openbank-fixture-service', 'openbank-unmapped', 'openbank-admin-ui'}
        components: ClassVar[dict[str, str]] = {'fixture-service': 'openbank-fixture-service'}
        selected = release()
        report = fleet.inventory_report(subjects, components, [selected['tag_name']],
                                        [selected], ['UNRESOLVED openbank-unmapped: no release component'])
        self.assertEqual(report['expectedSubjects'], 3)
        rows = {row['package']: row for row in report['subjects']}
        self.assertEqual(set(rows), subjects)
        self.assertEqual(rows['openbank-admin-ui']['status'], 'EXEMPT')
        self.assertEqual(rows['openbank-unmapped']['status'], 'UNRESOLVED')
        self.assertEqual(rows['openbank-fixture-service']['tag'], selected['tag_name'])
        self.assertEqual(rows['openbank-fixture-service']['version'], '1.2.3')
        self.assertEqual(rows['openbank-fixture-service']['status'], 'ASSETS_PRESENT')
        self.assertEqual(rows['openbank-fixture-service']['cryptography'], 'not_checked')
        self.assertEqual(report['status'], 'UNRESOLVED')
        self.assertRegex(report['observedAt'], r'Z$')
        self.assertRegex(report['verifierScriptSha256'], r'^[0-9a-f]{64}$')

    def test_malformed_newer_release_never_reports_older_tag_as_clean_latest(self):
        releases = [release(), release('2.0.0', release_id=2, draft='false')]
        components = {'fixture-service': 'openbank-fixture-service'}
        tags, findings = fleet.check_fleet(components, releases)
        self.assertTrue(findings)
        report = fleet.inventory_report({'openbank-fixture-service'}, components, tags, releases, findings)
        self.assertEqual(report['status'], 'UNRESOLVED')
        self.assertEqual(report['subjects'][0]['status'], 'UNRESOLVED')
        self.assertIsNone(report['subjects'][0]['tag'])
        self.assertEqual(tags, [])

    def test_unattributable_malformed_release_makes_all_nonexempt_subjects_unresolved(self):
        components = {'fixture-service': 'openbank-fixture-service', 'other': 'openbank-other'}
        other = json.loads(json.dumps(release(release_id=3)).replace('fixture-service', 'other'))
        releases = [release(), other, {'id': 2, 'draft': 'false', 'prerelease': False}]
        tags, findings = fleet.check_fleet(components, releases)
        subjects = {'openbank-fixture-service', 'openbank-other', 'openbank-admin-ui'}
        report = fleet.inventory_report(subjects, components, tags, releases, findings)
        self.assertEqual(report['status'], 'UNRESOLVED')
        rows = {row['package']: row for row in report['subjects']}
        self.assertEqual(rows['openbank-fixture-service']['status'], 'UNRESOLVED')
        self.assertIsNone(rows['openbank-fixture-service']['tag'])
        self.assertEqual(rows['openbank-other']['status'], 'UNRESOLVED')
        self.assertIsNone(rows['openbank-other']['tag'])
        self.assertEqual(rows['openbank-admin-ui']['status'], 'EXEMPT')

    def test_scoped_malformed_release_preserves_other_component_observation(self):
        components = {'fixture-service': 'openbank-fixture-service', 'other': 'openbank-other'}
        other = json.loads(json.dumps(release(release_id=3)).replace('fixture-service', 'other'))
        releases = [release(), other, release('2.0.0', release_id=2, draft='false')]
        tags, findings = fleet.check_fleet(components, releases)
        report = fleet.inventory_report(set(components.values()), components, tags, releases, findings)
        rows = {row['package']: row for row in report['subjects']}
        self.assertEqual(report['status'], 'UNRESOLVED')
        self.assertEqual(rows['openbank-fixture-service']['status'], 'UNRESOLVED')
        self.assertIsNone(rows['openbank-fixture-service']['tag'])
        self.assertEqual(rows['openbank-other']['status'], 'ASSETS_PRESENT')
        self.assertEqual(rows['openbank-other']['tag'], 'other-v1.2.3')

    def test_cli_emits_report_on_unreadable_api(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            InventoryTest().fixture(root)
            report_path, tags_path = root / 'report.json', root / 'tags.txt'
            (root / '.github/workflows').mkdir(parents=True)
            (root / '.github/workflows/verify-release-evidence.yml').write_text('unused')
            tags_path.write_text('stale-v1.0.0\n')
            with (mock.patch.object(fleet, 'ROOT', root),
                  mock.patch.object(fleet, 'fetch_releases', side_effect=TimeoutError('deadline')),
                  mock.patch.object(fleet, 'workflow_findings', return_value=[]),
                  mock.patch('sys.argv', [str(SCRIPT), '--report-output', str(report_path),
                                          '--tags-output', str(tags_path)]),
                  contextlib.redirect_stdout(io.StringIO())):
                self.assertEqual(fleet.main(), 2)
            report = json.loads(report_path.read_text())
            self.assertEqual(report['status'], 'UNRESOLVED')
            self.assertEqual(report['expectedSubjects'], 3)
            self.assertEqual(len(report['subjects']), 3)
            self.assertEqual(tags_path.read_text(), '')

    def test_unknown_local_denominator_is_never_zero_clean(self):
        report = fleet.inventory_report(None, {}, [], [], ['UNRESOLVED missing inventory'], unresolved=True)
        self.assertIsNone(report['expectedSubjects'])
        self.assertEqual(report['status'], 'UNRESOLVED')
        self.assertEqual(report['subjects'], [])


class WorkflowContractTest(unittest.TestCase):
    def test_identity_download_or_verification_loop_cannot_disappear(self):
        source = (SCRIPT.parents[1] / 'workflows/verify-release-evidence.yml').read_text()
        self.assertEqual(fleet.workflow_findings(source), [])
        for missing in ('done < tags.txt', 'done < release-identities.tsv', 'verify-blob'):
            with self.subTest(missing=missing):
                self.assertTrue(fleet.workflow_findings(source.replace(missing, 'removed', 1)))


class SharedAssetContractTest(unittest.TestCase):
    def test_standalone_import_has_no_yaml_dependency(self):
        probe = '''import importlib.util, sys
sys.modules['yaml'] = None
spec = importlib.util.spec_from_file_location('fleet', sys.argv[1])
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
assert module.ASSET_SUFFIXES == module.release_assets.REQUIRED_ASSET_SUFFIXES
'''
        result = subprocess.run(['python3', '-c', probe, str(SCRIPT)], capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == '__main__':
    unittest.main()
