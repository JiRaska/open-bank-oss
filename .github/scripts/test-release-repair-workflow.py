# SPDX-License-Identifier: Apache-2.0
"""Offline entrypoint controls; no signing, uploads or crypto success are simulated."""
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SCRIPTS = Path(__file__).parent

def load(name, file):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / file)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module

PREPARE = load('repair_workflow_prepare', 'prepare-release-repair.py')
VERIFY = load('repair_workflow_verify', 'verify-release-repair.py')
APPLY = load('repair_workflow_apply', 'apply-release-repair.py')


class WorkflowTests(unittest.TestCase):
    def test_paginated_objects_require_complete_stable_unique_inventory(self):
        from release_repair_api import ReadOnlyGitHub
        api = ReadOnlyGitHub('example/repo', lambda tag: 'a' * 40)
        hundred = [{'id': n} for n in range(1, 101)]
        for responses in (
            [{'jobs': []}], [{'total_count': True, 'jobs': []}], [{'total_count': 1001, 'jobs': []}],
            [{'total_count': 2, 'jobs': [{'id': 1}]}],
            [{'total_count': 101, 'jobs': hundred}, {'total_count': 102, 'jobs': [{'id': 101}]}],
            [{'total_count': 101, 'jobs': hundred}, {'total_count': 101, 'jobs': [{'id': 1}]}],
        ):
            with self.subTest(responses=responses), patch.object(api, 'get', side_effect=responses):
                with self.assertRaises(ValueError): api.pages('actions/jobs', 'jobs')
        with patch.object(api, 'get', side_effect=[
            {'total_count': 101, 'jobs': hundred}, {'total_count': 101, 'jobs': [{'id': 101}]},
        ]):
            self.assertEqual(len(api.pages('actions/jobs', 'jobs')), 101)
        with patch.object(api, 'get', return_value={'total_count': 0, 'jobs': []}):
            self.assertEqual(api.pages('actions/jobs', 'jobs'), [])

    def test_asset_pages_follow_links_and_reject_duplicates(self):
        from release_repair_api import ReadOnlyGitHub
        import types
        api = ReadOnlyGitHub('example/repo', lambda tag: 'a' * 40)
        with patch('release_repair_api.subprocess.run', return_value=types.SimpleNamespace(
                stdout=b'[[{"id":1}],[{"id":2}]]')) as run:
            self.assertEqual(api.assets(3), [{'id': 1}, {'id': 2}])
            self.assertIn('--paginate', run.call_args.args[0])
            self.assertIn('--slurp', run.call_args.args[0])
        with patch('release_repair_api.subprocess.run', return_value=types.SimpleNamespace(
                stdout=b'[[{"id":1}],[{"id":1}]]')):
            with self.assertRaises(ValueError): api.assets(3)

    def test_append_only_transport_binds_release_and_refuses_redirects(self):
        from release_repair_api import AppendOnlyGitHub
        import types
        api = AppendOnlyGitHub('example/repo', lambda tag: 'a' * 40, 3)
        response = unittest.mock.MagicMock()
        response.__enter__.return_value.status = 201
        opener = unittest.mock.Mock(); opener.open.return_value = response
        with patch.dict(os.environ, {'GH_TOKEN': 'test-only'}), patch(
                'release_repair_api.urllib.request.build_opener', return_value=opener) as factory:
            api.upload(3, 'example.cdx.json', b'content')
            request = opener.open.call_args.args[0]
            self.assertEqual(request.full_url, 'https://uploads.github.com/repos/example/repo/releases/3/assets?name=example.cdx.json')
            self.assertEqual(request.method, 'POST')
            self.assertIsNone(factory.call_args.args[0].redirect_request(None, None, 302, '', {}, 'https://other.example'))
            with self.assertRaises(ValueError): api.upload(4, 'example.cdx.json', b'content')
            self.assertEqual(opener.open.call_count, 1)

    def test_prior_success_requires_distinct_attempt_trust_and_verification_job(self):
        import copy
        from release_repair_api import ReadOnlyGitHub
        api = ReadOnlyGitHub('example/repo', lambda tag: 'a' * 40)
        producer = {'id': 10, 'run_attempt': 2, 'head_sha': 'b' * 40, 'head_branch': 'main',
            'event': 'workflow_dispatch', 'path': APPLY.WORKFLOW, 'status': 'completed',
            'conclusion': 'success', 'repository': {'full_name': 'example/repo'}}
        jobs = [{'id': 30, 'name': 'verify', 'run_id': 10, 'run_attempt': 2,
            'status': 'completed', 'conclusion': 'success'}]
        with patch.object(api, 'get', return_value=producer), patch.object(api, 'pages', return_value=jobs), \
                patch.object(APPLY.subprocess, 'run') as ancestry, \
                patch.object(APPLY.subprocess, 'check_output', return_value=b'100644 blob ' + b'a' * 40 + b'\tfile\n'):
            self.assertEqual(APPLY.prior_run(api, 10, 2, 11, 'b' * 40, 'c' * 40)[0], producer)
            ancestry.assert_called_once_with(['git', 'merge-base', '--is-ancestor', 'b' * 40, 'c' * 40], check=True)
            with self.assertRaises(ValueError): APPLY.prior_run(api, 10, 2, 10, 'b' * 40, 'c' * 40)
        for field, value in [('conclusion', 'failure'), ('status', 'in_progress'), ('run_attempt', 1),
                             ('head_sha', 'd' * 40), ('event', 'push'), ('path', 'other')]:
            changed = copy.deepcopy(producer); changed[field] = value
            with patch.object(api, 'get', return_value=changed), patch.object(api, 'pages') as pages:
                with self.assertRaises(ValueError): APPLY.prior_run(api, 10, 2, 11, 'b' * 40, 'c' * 40)
                pages.assert_not_called()
        with patch.object(api, 'get', return_value=producer), patch.object(APPLY.subprocess, 'run'), \
                patch.object(APPLY.subprocess, 'check_output', side_effect=[b'100644 blob ' + b'a' * 40 + b'\tfile\n', b'100755 blob ' + b'a' * 40 + b'\tfile\n']), \
                patch.object(api, 'pages') as pages:
            with self.assertRaises(ValueError): APPLY.prior_run(api, 10, 2, 11, 'b' * 40, 'c' * 40)
            pages.assert_not_called()
        for invalid in ([], jobs + jobs, [dict(jobs[0], conclusion='failure')], [dict(jobs[0], run_attempt=1)]):
            with patch.object(api, 'get', return_value=producer), patch.object(api, 'pages', return_value=invalid), \
                    patch.object(APPLY.subprocess, 'run'), patch.object(APPLY.subprocess, 'check_output', return_value=b'100644 blob ' + b'a' * 40 + b'\tfile\n'):
                with self.assertRaises(ValueError): APPLY.prior_run(api, 10, 2, 11, 'b' * 40, 'c' * 40)
        self.assertIn('openbank-libs/governance/rules.yaml', APPLY.TRUSTED_PATHS)

    def test_repository_dot_segments_rejected_before_transport(self):
        from release_repair_api import ReadOnlyGitHub, AppendOnlyGitHub
        from release_evidence_repair import bind_bundle
        for repository in ('../repo', './repo', 'owner/..', 'owner/.'):
            with self.subTest(repository=repository):
                with self.assertRaises(ValueError): ReadOnlyGitHub(repository, lambda tag: '')
                with self.assertRaises(ValueError): AppendOnlyGitHub(repository, lambda tag: '', 1)
                with self.assertRaises(ValueError): bind_bundle({}, {}, repository=repository, producer_run_id=1, producer_run_attempt=1)
        self.assertEqual(ReadOnlyGitHub('example/.github', lambda tag: '').repository, 'example/.github')

    def test_apply_driver_never_posts_when_crypto_or_archive_binding_fails(self):
        import types
        from release_evidence_assets import expected_release_assets
        from release_evidence_repair import bind_bundle, binding_digest
        tag = 'example-v1.0.0'
        data = {n: n.encode() for n in expected_release_assets(tag)}
        bound = bind_bundle(dict(schemaVersion=1, tag=tag, commit='a' * 40, release_id=7,
            workflow_revision='b' * 40, originalAssets=[]), data, repository='example/repo', producer_run_id=19, producer_run_attempt=1)
        saved = dict(data, **{'repair-binding.json': json.dumps(bound).encode(), 'verification.json': json.dumps({
            'bindingSha256': binding_digest(bound), 'published': False, 'claim': 'verified-current-repair-generation',
            'provenanceArtifactId': 21, 'generatorJobId': 22}).encode()})
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, 'w') as z:
            for name, content in saved.items(): z.writestr(name, content)
        raw = archive.getvalue(); digest = hashlib.sha256(raw).hexdigest()
        class API:
            repository = 'example/repo'; release_id = 7; posts = 0
            def get(self, endpoint): return {'status': 'ahead', 'ahead_by': 1, 'behind_by': 0, 'merge_base_commit': {'sha': 'a' * 40}, 'base_commit': {'sha': 'a' * 40}}
            def release(self, t): return {'id': 7, 'tag_name': tag}
            def tag_commit(self, t): return 'a' * 40
            def assets(self, release_id): return []
            def upload(self, *args): self.posts += 1; raise AssertionError('must never publish')
        api = API()
        env = dict(self.env, GITHUB_REPOSITORY='example/repo', GITHUB_RUN_ID='20',
            REPAIR_TAG=tag, REPAIR_COMMIT='a' * 40, REPAIR_RELEASE_ID='7', REPAIR_PRIOR_RUN_ID='19',
            REPAIR_PRIOR_ATTEMPT='1', REPAIR_PRODUCER_REVISION='b' * 40, REPAIR_VERIFIED_ARTIFACT_ID='23',
            REPAIR_VERIFIED_ARTIFACT_SHA256=digest, RUNNER_TEMP=str(self.root))
        helper = types.SimpleNamespace(remote_commit=lambda *args: 'a' * 40)
        tools = types.SimpleNamespace(tool=lambda *args: self.root / 'never-executed')
        for mismatch in (False, True):
            metadata = {'id': 23, 'digest': 'sha256:' + ('0' * 64 if mismatch else digest)}
            verifier = types.SimpleNamespace(PROVENANCE_LIMIT=VERIFY.PROVENANCE_LIMIT,
                ZIP_OVERHEAD_LIMIT=VERIFY.ZIP_OVERHEAD_LIMIT, members=VERIFY.members,
                archive=unittest.mock.Mock(side_effect=[(metadata, raw), ({'id': 21}, b'original')]))
            with patch.dict(os.environ, env), patch.object(APPLY.subprocess, 'check_output', return_value='b'*40), \
                    patch.object(APPLY, 'load', side_effect=[helper, verifier, tools]), \
                    patch.object(APPLY, 'AppendOnlyGitHub', return_value=api), \
                    patch.object(APPLY, 'prior_run', return_value=({}, [{'id': 22}])), \
                    patch.object(APPLY, 'verify_bundle', side_effect=ValueError('crypto rejected')) as crypto:
                with self.assertRaisesRegex(ValueError, 'digest mismatch' if mismatch else 'crypto rejected'):
                    APPLY.main()
                if mismatch: crypto.assert_not_called()
                else: crypto.assert_called_once()
                self.assertEqual(api.posts, 0)

    def test_generation_and_apply_share_exact_member_boundary(self):
        from release_evidence_repair import MAX_REPAIR_MEMBER, require_member_size
        require_member_size(MAX_REPAIR_MEMBER)
        with self.assertRaises(ValueError): require_member_size(MAX_REPAIR_MEMBER + 1)
        self.assertEqual(VERIFY.PROVENANCE_LIMIT, MAX_REPAIR_MEMBER)
        for size in (MAX_REPAIR_MEMBER, MAX_REPAIR_MEMBER + 1):
            archive = io.BytesIO()
            with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED) as z:
                z.writestr('expected', b'a' * size)
            if size == MAX_REPAIR_MEMBER:
                self.assertEqual(len(VERIFY.members(archive.getvalue(), {'expected'}, maximum_member=MAX_REPAIR_MEMBER)['expected']), size)
            else:
                with self.assertRaises(ValueError): VERIFY.members(archive.getvalue(), {'expected'}, maximum_member=MAX_REPAIR_MEMBER)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        scripts = self.root / '.github/scripts'; scripts.mkdir(parents=True)
        (scripts / 'prepare-release-evidence.py').write_text(
            "def remote_commit(root, tag): return 'a' * 40\n"
            "def prepare(*args): raise AssertionError('plan must not execute released source')\n")
        (self.root / 'release-please-config.json').write_text(json.dumps({'packages': {'openbank-example': {'component': 'example'}}}))
        self.env = dict(GITHUB_EVENT_NAME='workflow_dispatch', GITHUB_REF='refs/heads/main',
            GITHUB_WORKFLOW_SHA='b' * 40, REPAIR_WORKFLOW_REVISION='b' * 40,
            GITHUB_WORKFLOW_REF='example/project/.github/workflows/backfill-release-evidence.yml@refs/heads/main',
            GITHUB_REPOSITORY='example/project', REPAIR_TAG='example-v1.0.0', REPAIR_COMMIT='a' * 40,
            REPAIR_RELEASE_ID='7', GITHUB_RUN_ID='19', GITHUB_RUN_ATTEMPT='1')

    def plan(self, assets=None, release_id=7, comparison_status="ahead"):
        class API:
            def __init__(self, repository, resolver): self.tag_commit = resolver
            def get(self, endpoint): return {'status': comparison_status, 'ahead_by': 1, 'behind_by': 0, 'merge_base_commit': {'sha': 'a' * 40}, 'base_commit': {'sha': 'a' * 40}}
            def release(self, tag): return {'id': release_id, 'tag_name': tag}
            def assets(self, release_id): return [] if assets is None else assets
        with patch.dict(os.environ, self.env, clear=True), patch('sys.argv', ['prepare', 'plan']), \
             patch.object(PREPARE.Path, 'cwd', return_value=self.root), \
             patch.object(PREPARE.subprocess, 'check_output', return_value='b' * 40 + '\n'), \
             patch.object(PREPARE, 'ReadOnlyGitHub', API), \
             patch.object(PREPARE, 'tool', side_effect=AssertionError('plan installs no tools')):
            PREPARE.main()

    def test_all_unsigned_documents_checked_before_first_sign(self):
        from release_evidence_repair import MAX_REPAIR_MEMBER
        helper = self.root / '.github/scripts/prepare-release-evidence.py'
        helper.write_text("def remote_commit(root, tag): return 'a' * 40\ndef prepare(*args, **kwargs): return 'a' * 40\n")
        tag = self.env['REPAIR_TAG']
        for suffix in ('.cdx.json', '.slsa.json', '.vex.json', '.evidence.json'):
            path = self.root / (tag + suffix)
            with path.open('wb') as stream: stream.truncate(MAX_REPAIR_MEMBER + 1 if suffix == '.evidence.json' else 1)
        class API:
            def __init__(self, repository, resolver): self.tag_commit = resolver
            def get(self, endpoint): return {'status': 'ahead', 'ahead_by': 1, 'behind_by': 0, 'merge_base_commit': {'sha': 'a' * 40}, 'base_commit': {'sha': 'a' * 40}}
            def release(self, tag): return {'id': 7, 'tag_name': tag}
            def assets(self, release_id): return []
        with patch.dict(os.environ, self.env, clear=True), patch('sys.argv', ['prepare', 'generate']), \
                patch.object(PREPARE.Path, 'cwd', return_value=self.root), \
                patch.object(PREPARE.subprocess, 'check_output', return_value='b' * 40), \
                patch.object(PREPARE, 'ReadOnlyGitHub', API), patch.object(PREPARE, 'tool') as tool, \
                patch.object(PREPARE.subprocess, 'run') as run:
            with self.assertRaisesRegex(ValueError, 'supported repair-lane size'): PREPARE.main()
            tool.assert_not_called(); run.assert_not_called()

    def test_repair_only_main_inclusion_fails_before_build(self):
        from release_repair_api import require_main_inclusion, ReadOnlyGitHub
        api = ReadOnlyGitHub('example/repo', lambda tag: 'a' * 40)
        good = {'status': 'ahead', 'ahead_by': 1, 'behind_by': 0,
            'merge_base_commit': {'sha': 'a' * 40}, 'base_commit': {'sha': 'a' * 40}}
        for change in ({'status': 'behind'}, {'status': 'diverged'}, {'behind_by': 1},
                       {'behind_by': False}, {'merge_base_commit': {}}, {'base_commit': {}}, {'ahead_by': None},
                       {'ahead_by': 0}, {'status': 'identical', 'ahead_by': 0}, {'status': 'identical'}):
            with patch.object(api, 'get', return_value=good | change), self.assertRaises(ValueError):
                require_main_inclusion(api, 'a' * 40, 'b' * 40)
        with patch.object(api, 'get', return_value={}):
            with self.assertRaises(ValueError): require_main_inclusion(api, 'a' * 40, 'b' * 40)
        with patch.object(api, 'get', return_value=good | {'status': 'identical', 'ahead_by': 0}):
            require_main_inclusion(api, 'a' * 40, 'a' * 40)
        for status in ('behind', 'diverged', 'unknown'):
            with self.assertRaisesRegex(ValueError, 'not proven included'): self.plan(comparison_status=status)
            self.assertFalse((self.root / '.repair-bundle').exists())

    def test_actual_plan_entrypoint_does_not_build_install_or_sign(self):
        self.plan()
        entries = list((self.root / '.repair-bundle').iterdir())
        self.assertEqual([p.name for p in entries], ['repair-plan.json'])
        self.assertEqual(json.loads(entries[0].read_text())['operation'], 'plan')

    def test_actual_plan_rejects_wrong_workflow_revision_before_build(self):
        self.env['GITHUB_WORKFLOW_SHA'] = 'c' * 40
        with self.assertRaisesRegex(ValueError, 'approved exact main'): self.plan()
        self.assertFalse((self.root / '.repair-bundle').exists())

    def test_actual_plan_refuses_partial_original_and_changed_release_id(self):
        with self.assertRaises(ValueError): self.plan([{'id': 1, 'name': 'existing'}])
        with self.assertRaises(ValueError): self.plan(release_id=8)
        self.assertFalse((self.root / '.repair-bundle').exists())

    def test_generation_driver_cannot_publish(self):
        with patch('sys.argv', ['prepare', 'apply']), self.assertRaises(SystemExit): PREPARE.main()

    def test_archive_rejects_duplicate_traversal_and_symlink_members(self):
        for names, symlink in [(['expected', 'expected'], False), (['../expected'], False), (['expected'], True)]:
            data = io.BytesIO()
            with zipfile.ZipFile(data, 'w') as archive:
                for name in names:
                    info = zipfile.ZipInfo(name)
                    if symlink: info.external_attr = 0o120777 << 16
                    archive.writestr(info, b'content')
            with self.subTest(names=names, symlink=symlink), self.assertRaises(ValueError):
                VERIFY.members(data.getvalue(), {'expected'}, maximum_member=7)

    def test_regular_file_archive_control_succeeds(self):
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w') as archive: archive.writestr('expected', b'content')
        self.assertEqual(VERIFY.members(data.getvalue(), {'expected'}, maximum_member=7), {'expected': b'content'})

    def test_archive_rejects_declared_special_file_types(self):
        for mode in [0o010600, 0o020600, 0o060600, 0o140600]:
            data = io.BytesIO()
            with zipfile.ZipFile(data, 'w') as archive:
                info = zipfile.ZipInfo('expected'); info.external_attr = mode << 16
                archive.writestr(info, b'content')
            with self.subTest(mode=mode), self.assertRaises(ValueError):
                VERIFY.members(data.getvalue(), {'expected'}, maximum_member=7)

    def test_artifact_transport_size_digest_and_run_binding(self):
        raw = b'archive bytes'
        metadata = {'id': 31, 'expired': False, 'workflow_run': {'id': 19}, 'name': 'bundle',
                    'size_in_bytes': len(raw), 'digest': 'sha256:' + hashlib.sha256(raw).hexdigest()}
        class API:
            repository = 'example/project'
            def get(self, endpoint): return metadata
        class Process:
            def __init__(self): self.stdout = io.BytesIO(raw)
            def wait(self): return 0
            def kill(self): pass
        with patch.object(VERIFY.subprocess, 'Popen', side_effect=lambda *a, **k: Process()):
            self.assertEqual(VERIFY.archive(API(), 31, 19, 'bundle', 1000)[1], raw)
            for key, value in [('digest', 'sha256:' + '0' * 64), ('size_in_bytes', len(raw) + 1),
                               ('workflow_run', {'id': 20}), ('id', 32)]:
                saved = metadata[key]; metadata[key] = value
                with self.subTest(key=key), self.assertRaises(ValueError): VERIFY.archive(API(), 31, 19, 'bundle', 1000)
                metadata[key] = saved

    def test_truncated_archive_fails_closed(self):
        with self.assertRaises(zipfile.BadZipFile): VERIFY.members(b'PKbroken', {'expected'})

    def test_provenance_limit_and_limit_plus_one(self):
        for length in [VERIFY.PROVENANCE_LIMIT, VERIFY.PROVENANCE_LIMIT + 1]:
            data = io.BytesIO()
            with zipfile.ZipFile(data, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr('expected', b'x' * length)
            if length == VERIFY.PROVENANCE_LIMIT:
                self.assertEqual(len(VERIFY.members(data.getvalue(), {'expected'}, maximum_member=VERIFY.PROVENANCE_LIMIT)['expected']), length)
            else:
                with self.assertRaisesRegex(ValueError, 'resource limit'):
                    VERIFY.members(data.getvalue(), {'expected'}, maximum_member=VERIFY.PROVENANCE_LIMIT)

    def test_source_member_length_requires_exact_trusted_output(self):
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w') as archive: archive.writestr('expected', b'1234')
        for expected_length in [3, 5]:
            with self.subTest(length=expected_length), self.assertRaises(ValueError):
                VERIFY.members(data.getvalue(), {'expected'}, sizes={'expected': expected_length})
        self.assertEqual(VERIFY.members(data.getvalue(), {'expected'}, sizes={'expected': 4})['expected'], b'1234')

    def test_workflow_has_separate_explicit_apply_and_safe_defaults(self):
        source = (SCRIPTS.parent / 'workflows/backfill-release-evidence.yml').read_text()
        self.assertIn('default: plan', source)
        self.assertIn('options: [plan, generate, apply]', source)
        import yaml
        jobs = yaml.safe_load(source)['jobs']
        self.assertEqual(jobs['apply']['if'], "inputs.operation == 'apply'")
        self.assertEqual(jobs['plan']['if'], "inputs.operation != 'apply'")
        self.assertEqual(jobs['generate']['if'], "inputs.operation == 'generate'")
        self.assertNotIn('needs', jobs['apply'])
        self.assertEqual(jobs['verify']['permissions']['contents'], 'read')
        self.assertIn('upload-assets: false', source)
        self.assertIn('cancel-in-progress: false', source)
        self.assertNotIn('upload_asset()', source)
        self.assertNotIn('--clobber', source)
        self.assertNotIn('git show origin/main', source)
        self.assertIn('needs: [generate, provenance]', source)


if __name__ == '__main__':
    unittest.main()
