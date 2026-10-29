# SPDX-License-Identifier: Apache-2.0
"""Executor failure-path tests; mocked subprocesses are not crypto proof."""
import subprocess
import json
import copy
import io
import hashlib
import zipfile
import unittest
from unittest.mock import patch

from release_evidence_assets import expected_release_assets
from release_evidence_repair import bind_bundle
from release_repair_crypto import verify_bundle


class CryptoFailureTests(unittest.TestCase):
    def setUp(self):
        self.bundle = {n: b'fixture' for n in expected_release_assets('example-v1.0.0')}
        self.bound = bind_bundle(dict(schemaVersion=1, tag='example-v1.0.0', commit='a' * 40,
            release_id=7, workflow_revision='b' * 40, originalAssets=[]), self.bundle,
            repository='example/project', producer_run_id=19, producer_run_attempt=2)
        self.artifact = dict(id=31, name='example-v1.0.0.cdx.json.intoto.jsonl', expired=False, workflow_run={'id': 19})
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as archive:
            archive.writestr(self.artifact['name'], self.bundle[self.artifact['name']])
        self.archive = stream.getvalue()
        self.artifact['digest'] = 'sha256:' + hashlib.sha256(self.archive).hexdigest()
        self.job = dict(id=29, name='Release repair provenance / generator', run_url='https://api.github.com/repos/example/project/actions/runs/19',
                        run_attempt=2, status='completed', conclusion='success')

    def invoke(self):
        return verify_bundle(self.bound, self.bundle, getattr(self, 'producer', {}), cosign='/abs/cosign', slsa_verifier='/abs/verifier',
            artifact=self.artifact, artifact_archive=self.archive, generator_job=self.job, expected_artifact_id=31,
            expected_generator_job_id=29)

    def test_wrong_artifact_run_or_name_never_executes(self):
        for key, bad in [('id', 32), ('name', ''), ('expired', True), ('workflow_run', {'id': 20})]:
            saved = dict(self.artifact); self.artifact[key] = bad
            with self.subTest(key=key), patch('release_repair_crypto.subprocess.run') as execute, self.assertRaises(ValueError):
                self.invoke()
            execute.assert_not_called(); self.artifact = saved

    def test_artifact_bytes_must_match_canonical_archive_digest_and_member(self):
        original = self.archive
        self.archive = b'corrupt'
        with self.assertRaisesRegex(ValueError, 'digest mismatch'): self.invoke()
        self.archive = original
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as archive:
            archive.writestr(self.artifact['name'], b'wrong!!')
        self.archive = stream.getvalue()
        self.artifact['digest'] = 'sha256:' + hashlib.sha256(self.archive).hexdigest()
        with self.assertRaises(ValueError): self.invoke()

    def test_generator_must_be_exact_successful_attempt(self):
        for key, bad in [('id', 30), ('name', 'Unrelated successful job'), ('run_attempt', 1), ('status', 'in_progress'), ('conclusion', 'failure'), ('run_url', 'wrong')]:
            saved = dict(self.job); self.job[key] = bad
            with self.subTest(key=key), patch('release_repair_crypto.subprocess.run') as execute, self.assertRaises(ValueError):
                self.invoke()
            execute.assert_not_called(); self.job = saved

    def test_untrusted_binary_never_executes(self):
        with patch('release_repair_crypto.subprocess.run') as execute, self.assertRaises(ValueError):
            self.invoke()
        execute.assert_not_called()

    def test_wrong_signed_manual_identity_rejected_after_all_four_signature_checks(self):
        from release_repair_attestation import WORKFLOW, validate_signed_manual_statement
        self.producer = {'id': 19, 'head_sha': 'b' * 40, 'path': WORKFLOW, 'repository': {'full_name': 'example/project'}}
        statement = {'_type': 'https://in-toto.io/Statement/v1', 'predicateType': 'https://slsa.dev/provenance/v1',
            'subject': [{'name': 'example-v1.0.0.cdx.json', 'digest': {'sha256': self.bound['sha256']['example-v1.0.0.cdx.json']}}],
            'predicate': {'buildDefinition': {'buildType': 'https://openbank.tech/release-please-evidence/v1',
                'externalParameters': {'component': 'example', 'version': '1.0.0', 'tag': 'example-v1.0.0'}, 'internalParameters': {},
                'resolvedDependencies': [{'uri': 'git+https://github.com/example/project@' + 'a' * 40, 'digest': {'gitCommit': 'a' * 40}}]},
                'runDetails': {'builder': {'id': 'https://github.com/example/project/' + WORKFLOW},
                    'metadata': {'invocationId': 'https://github.com/example/project/actions/runs/19'}}}}
        validate_signed_manual_statement(statement, self.bound, self.producer)
        paths = [('subject', 0, 'name'), ('predicate', 'buildDefinition', 'resolvedDependencies', 0, 'uri'),
            ('predicate', 'runDetails', 'builder', 'id'), ('predicate', 'runDetails', 'metadata', 'invocationId')]
        for path in paths:
            changed = copy.deepcopy(statement); target = changed
            for key in path[:-1]: target = target[key]
            target[path[-1]] = 'incorrect'
            self.bundle['example-v1.0.0.slsa.json'] = json.dumps(changed).encode()
            self.bound['sha256']['example-v1.0.0.slsa.json'] = hashlib.sha256(self.bundle['example-v1.0.0.slsa.json']).hexdigest()
            with patch('release_repair_crypto.pinned_binary', side_effect=lambda path, hashes: path), \
                    patch('release_repair_crypto.subprocess.run', return_value=subprocess.CompletedProcess([], 0)) as execute:
                with self.subTest(path=path), self.assertRaisesRegex(ValueError, 'manual statement'):
                    self.invoke()
                self.assertEqual(execute.call_count, 4)
                self.assertTrue(all(call.args[0][1] == 'verify-blob' for call in execute.call_args_list))

    def test_signature_command_failure_cannot_return_receipt(self):
        for failure_index in range(4):
            outcomes = [subprocess.CompletedProcess([], 0)] * failure_index + [subprocess.CalledProcessError(1, 'cosign')]
            with self.subTest(signature=failure_index), patch('release_repair_crypto.pinned_binary', side_effect=lambda path, hashes: path), \
                 patch('release_repair_crypto.subprocess.run', side_effect=outcomes) as execute, \
                 self.assertRaises(subprocess.CalledProcessError):
                self.invoke()
            self.assertEqual(execute.call_count, failure_index + 1)
            self.assertTrue(execute.call_args.kwargs['check'])


if __name__ == '__main__':
    unittest.main()
