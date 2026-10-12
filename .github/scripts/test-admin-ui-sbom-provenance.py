# SPDX-License-Identifier: Apache-2.0
"""Offline producer/consumer regressions for Admin UI SBOM provenance."""
import hashlib
import importlib.util
import io
import json
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).with_name('admin-ui-sbom-provenance.py')
spec = importlib.util.spec_from_file_location('admin_ui_sbom_provenance', SCRIPT)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

SBOM = json.dumps({'bomFormat': 'CycloneDX', 'specVersion': '1.6',
                   'components': [{'name': 'library'}], 'metadata': {'component': {
                       'name': 'openbank-a-service',
                       'purl': 'pkg:maven/com.openbank/openbank-a-service@1.0?project_path=%3Aopenbank-a-service'}}}).encode()
NAME = 'openbank-a-service-sbom.cdx.json'


def zip_content(proof, files=None):
    files = {NAME: SBOM} if files is None else files
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, 'w') as archive:
        archive.writestr('provenance.json', json.dumps(proof))
        for name, body in files.items():
            archive.writestr(name, body)
    return stream.getvalue()


class SbomProvenanceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / 'openbank-admin-ui').mkdir()
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        subprocess.run(['git', 'config', 'user.name', 'Test'], cwd=self.root, check=True)
        subprocess.run(['git', 'config', 'user.email', 'test@example.invalid'], cwd=self.root, check=True)
        subprocess.run(['git', 'config', 'commit.gpgsign', 'false'], cwd=self.root, check=True)
        module = self.root / 'openbank-a-service'
        module.mkdir()
        (module / 'build.gradle.kts').write_text('plugins { id("openbank.quarkus-service") }\n')
        other = self.root / 'openbank-b-service'
        other.mkdir()
        (other / 'build.gradle.kts').write_text('plugins {}\n')
        subprocess.run(['git', 'add', 'openbank-a-service/build.gradle.kts',
                        'openbank-b-service/build.gradle.kts'], cwd=self.root, check=True)
        subprocess.run(['git', 'commit', '-qm', 'initial'], cwd=self.root, check=True)
        self.sha = mod.source_sha(self.root)
        self.fp = mod.input_digest(self.root)
        self.proof = {'schema': 1, 'source_sha': self.sha, 'dependency_input_sha256': self.fp,
                      'reports': {NAME: hashlib.sha256(SBOM).hexdigest()}}
        self.artifact = {'id': 81, 'name': 'per-service-sboms',
                         'created_at': '2026-10-12T00:00:00Z', 'expired': False,
                         'workflow_run': {'id': 44, 'head_sha': self.sha}}
        self.run = {'id': 44, 'path': '.github/workflows/security.yml', 'head_branch': 'main',
                    'event': 'push', 'status': 'completed', 'conclusion': 'success', 'head_sha': self.sha}

    def candidate(self, body=None, run=None, artifact=None):
        body = zip_content(self.proof) if body is None else body
        artifact = dict(self.artifact if artifact is None else artifact)
        artifact['digest'] = 'sha256:' + hashlib.sha256(body).hexdigest()
        with patch.object(mod, 'json_api', return_value=self.run if run is None else run), \
             patch.object(mod, 'http', return_value=body):
            return mod.validate_candidate(self.root, 'owner/repo', 'token', artifact,
                                          self.sha, self.fp, {'openbank-a-service'})

    def test_valid_artifact_and_provenance(self):
        proof, reports = self.candidate()
        self.assertEqual(proof['producer_run_id'], 44)
        self.assertEqual(reports, {NAME: SBOM})

    def test_wrong_workflow_failed_or_pr_run_is_rejected(self):
        for field, value in [('path', '.github/workflows/other.yml'),
                             ('conclusion', 'failure'), ('event', 'pull_request'),
                             ('head_branch', 'feature')]:
            with self.subTest(field=field):
                self.assertIsNone(self.candidate(run={**self.run, field: value}))

    def test_nonancestor_rejected(self):
        other = self.root / 'other'
        other.write_text('new branch')
        subprocess.run(['git', 'checkout', '-qb', 'other'], cwd=self.root, check=True)
        subprocess.run(['git', 'add', 'other'], cwd=self.root, check=True)
        subprocess.run(['git', 'commit', '-qm', 'other'], cwd=self.root, check=True)
        other_sha = mod.source_sha(self.root)
        subprocess.run(['git', 'checkout', '-q', self.sha], cwd=self.root, check=True)
        self.assertIsNone(self.candidate(run={**self.run, 'head_sha': other_sha},
                                         artifact={**self.artifact, 'workflow_run': {'id': 44, 'head_sha': other_sha}}))

    def test_stale_dependency_inputs_rejected(self):
        self.proof['dependency_input_sha256'] = '0' * 64
        self.assertIsNone(self.candidate())

    def test_bad_digest_and_corrupt_zip_fail(self):
        with self.assertRaisesRegex(ValueError, 'digest mismatch'):
            bad = {**self.artifact, 'digest': 'sha256:' + '0' * 64}
            with patch.object(mod, 'json_api', return_value=self.run), patch.object(mod, 'http', return_value=b'bad'):
                mod.validate_candidate(self.root, 'owner/repo', 'token', bad, self.sha, self.fp,
                                       {'openbank-a-service'})
        with self.assertRaises(zipfile.BadZipFile):
            self.candidate(body=b'bad')

    def test_empty_unknown_or_changed_report_fail(self):
        with self.assertRaisesRegex(ValueError, 'incomplete SBOM set'):
            self.candidate(body=zip_content(self.proof, {}))
        with self.assertRaisesRegex(ValueError, 'unknown SBOM'):
            self.candidate(body=zip_content(self.proof, {'openbank-unknown-sbom.cdx.json': SBOM}))
        with self.assertRaisesRegex(ValueError, 'producer report digest mismatch'):
            self.candidate(body=zip_content(self.proof, {NAME: SBOM + b' '}))

    def test_missing_module_and_wrong_component_identity_fail(self):
        with self.assertRaisesRegex(ValueError, 'incomplete SBOM set'):
            mod.inventory({NAME: SBOM}, {'openbank-a-service', 'openbank-b-service'})
        renamed = {'openbank-b-service-sbom.cdx.json': SBOM}
        with self.assertRaisesRegex(ValueError, 'metadata component does not match'):
            mod.inventory(renamed, {'openbank-b-service'})
        invalid_purl = json.loads(SBOM)
        invalid_purl['metadata']['component']['purl'] = 'pkg:maven/com.openbank/other@1.0?project_path=%3Aother'
        with self.assertRaisesRegex(ValueError, 'metadata purl does not match'):
            mod.inventory({NAME: json.dumps(invalid_purl).encode()}, {'openbank-a-service'})

    def test_path_traversal_fail(self):
        with self.assertRaisesRegex(ValueError, 'unexpected artifact member'):
            self.candidate(body=zip_content(self.proof, {'../escape': SBOM}))

    def test_listing_reads_more_than_twenty(self):
        old = [{**self.artifact, 'id': n} for n in range(100)]
        with patch.object(mod, 'json_api', side_effect=[{'artifacts': old},
                                                       {'artifacts': [{**self.artifact, 'id': 101}]}]) as api:
            result = mod.artifact_candidates('owner/repo', 'token')
        self.assertEqual(len(result), 101)
        self.assertEqual(api.call_count, 2)

    def test_security_trigger_uses_fingerprint_predicate(self):
        for path in ('gradle.properties', 'gradle/verification-metadata.xml',
                     'gradle/osv-scanner.toml', 'openbank-a-service/version.txt'):
            with self.subTest(path=path):
                self.assertTrue(mod.dependency_path(path))
        self.assertFalse(mod.dependency_path('docs/adr/README.md'))
        source = SCRIPT.parents[1] / 'workflows/security.yml'
        self.assertIn('admin-ui-sbom-provenance.py inputs-changed', source.read_text())

    def test_git_dependency_diff_detects_verification_metadata(self):
        metadata = self.root / 'gradle/verification-metadata.xml'
        metadata.parent.mkdir()
        metadata.write_text('<verification-metadata/>')
        subprocess.run(['git', 'add', 'gradle/verification-metadata.xml'], cwd=self.root, check=True)
        subprocess.run(['git', 'commit', '-qm', 'metadata'], cwd=self.root, check=True)
        self.assertTrue(mod.inputs_changed(self.root, self.sha, mod.source_sha(self.root)))

    def test_stage_clears_stale_unselected_report(self):
        old = self.root / 'openbank-b-service/build/reports/bom.json'
        old.parent.mkdir(parents=True)
        old.write_bytes(SBOM)
        proof = {'schema': 1, 'kind': 'github-security-run', 'source_sha': self.sha,
                 'dependency_input_sha256': self.fp, 'reports': self.proof['reports'],
                 'producer_run_id': 44}
        with patch.object(mod, 'artifact_candidates', return_value=[self.artifact]), \
             patch.object(mod, 'validate_candidate', return_value=(proof, {NAME: SBOM})):
            mod.stage(self.root, 'owner/repo', 'token')
        self.assertFalse(old.exists())
        self.assertEqual((self.root / 'openbank-a-service/build/reports/bom.json').read_bytes(), SBOM)
        mod.verify(self.root)

    def test_verify_detects_stale_local_output(self):
        report = self.root / 'openbank-a-service/build/reports/bom.json'
        report.parent.mkdir(parents=True)
        report.write_bytes(SBOM)
        out = self.root / mod.PROVENANCE
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps({'schema': 1, 'kind': 'github-security-run',
                                   'source_sha': self.sha, 'dependency_input_sha256': self.fp,
                                   'reports': self.proof['reports']}))
        mod.verify(self.root)
        with self.assertRaisesRegex(ValueError, '--no-sbom requires'):
            out.write_text(json.dumps({'schema': 1, 'kind': 'local-sbomAll',
                                       'source_sha': self.sha, 'dependency_input_sha256': self.fp,
                                       'reports': self.proof['reports']}))
            mod.verify(self.root, github_only=True)
        out.write_text(json.dumps({'schema': 1, 'kind': 'github-security-run',
                                   'source_sha': self.sha, 'dependency_input_sha256': self.fp,
                                   'reports': self.proof['reports']}))
        report.write_bytes(SBOM + b' ')
        with self.assertRaisesRegex(ValueError, 'staged SBOMs differ'):
            mod.verify(self.root)

    def test_github_only_verification_refetches_artifact(self):
        report = self.root / 'openbank-a-service/build/reports/bom.json'
        report.parent.mkdir(parents=True)
        report.write_bytes(SBOM)
        proof = {'schema': 1, 'kind': 'github-security-run', 'source_sha': self.sha,
                 'dependency_input_sha256': self.fp, 'reports': self.proof['reports'],
                 'artifact_id': 81, 'producer_run_id': 44}
        (self.root / mod.PROVENANCE).write_text(json.dumps(proof))
        with patch.object(mod, 'json_api', return_value=self.artifact), \
             patch.object(mod, 'validate_candidate', return_value=(proof, {NAME: SBOM})) as validate:
            mod.verify(self.root, github_only=True, repo='owner/repo', token='token')
            validate.assert_called_once()
        with patch.object(mod, 'json_api', return_value=self.artifact), \
             patch.object(mod, 'validate_candidate', return_value=None):
            with self.assertRaisesRegex(ValueError, 'differs from live'):
                mod.verify(self.root, github_only=True, repo='owner/repo', token='token')


if __name__ == '__main__':
    unittest.main()
