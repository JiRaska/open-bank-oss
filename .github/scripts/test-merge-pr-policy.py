#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Permanent adversarial regressions for immutable PR-scoped owner-policy evidence."""
import base64
import copy
import hashlib
import gzip
import importlib.util
from pathlib import Path
import os
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch, Mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import merge_pr_policy as policy
import merge_authorization_proof as proof

spec = importlib.util.spec_from_file_location('old_tests', Path(__file__).with_name('test-merge-authorization-proof.py'))
old = importlib.util.module_from_spec(spec)
spec.loader.exec_module(old)
SOURCE = "EXCLUDED_BRANCH_PREFIXES = ('codex/',)\nOWNER_APPROVAL_CONTEXT = 'merge-train/owner-approval'\n" + policy._SUPPORTED_CHECK
PARENT, ROOT, DIRECTORY, SCRIPTS = ('c' * 40, 'd' * 40, 'e' * 40, 'f' * 40)


def git(repo, *args):
    # Hermetic: no inherited GIT_* (a runner or hook may point them at the real repo), no global or
    # system config (signing, hooks, templates), so a fixture repo behaves the same everywhere.
    env = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
    env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull)
    done = subprocess.run(['git', '-C', str(repo), '-c', 'user.email=t@example.invalid', '-c', 'user.name=t',
                           '-c', 'commit.gpgsign=false', '-c', f'core.hooksPath={os.devnull}', *args],
                          capture_output=True, text=True, env=env)
    if done.returncode:
        raise AssertionError(f'git {args} failed: {done.stderr.strip()}')
    return done.stdout


def commit_policy(repo, text):
    if not Path(repo, '.git').exists():
        git(repo, 'init', '-q', '-b', 'main')
    target = Path(repo, policy.POLICY_PATH)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text)
    git(repo, 'add', policy.POLICY_PATH)
    git(repo, 'commit', '-q', '-m', 'policy')
    return git(repo, 'rev-parse', f'HEAD:{policy.POLICY_PATH}').strip()



class PolicyGitHub(old.FakeGitHub):
    def __init__(self):
        super().__init__()
        self.pull['head']['ref'] = 'agent/audit'
        self.statuses = []
        page = old.legacy_page()
        pr = page['repository']['pullRequest']
        pr['autoMergeRequest']['enabledAt'] = '2026-10-24T11:55:00Z'
        pr['timelineItems']['nodes'][0]['createdAt'] = self.pull['merged_at']
        self.graph_pages = [page]
        raw = SOURCE.encode()
        self.blob_sha = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
        self.responses = {
            f'/git/commits/{old.MERGE}': {'sha': old.MERGE, 'parents': [{'sha': PARENT}]},
            f'/compare/{PARENT}...main': {'status': 'ahead', 'base_commit': {'sha': PARENT}, 'merge_base_commit': {'sha': PARENT}},
            f'/git/commits/{PARENT}': {'sha': PARENT, 'tree': {'sha': ROOT}},
            f'/git/trees/{ROOT}': {'sha': ROOT, 'tree': [{'path': '.github', 'type': 'tree', 'mode': '040000', 'sha': DIRECTORY}]},
            f'/git/trees/{DIRECTORY}': {'sha': DIRECTORY, 'tree': [{'path': 'scripts', 'type': 'tree', 'mode': '040000', 'sha': SCRIPTS}]},
            f'/git/trees/{SCRIPTS}': {'sha': SCRIPTS, 'tree': [{'path': 'merge-train.py', 'type': 'blob', 'mode': '100644', 'sha': self.blob_sha}]},
            f'/git/blobs/{self.blob_sha}': {'sha': self.blob_sha, 'size': len(raw), 'encoding': 'base64', 'content': base64.b64encode(raw).decode()},
        }

    def get(self, path):
        suffix = path.removeprefix(f'/repos/{old.REPO}')
        if suffix in self.responses:
            self.calls.append(path)
            return copy.deepcopy(self.responses[suffix])
        return super().get(path)


class PolicyEvidenceTests(unittest.TestCase):
    def authorize(self, github=None):
        github = github or PolicyGitHub()
        with patch.object(policy, 'REVIEWED_POLICY_BLOBS', {github.blob_sha}):
            return proof.app_authorization(copy.deepcopy(github.pull), old.REPO, github)

    def test_current_supported_policy_retains_codex_exact_head(self):
        source = Path(__file__).with_name('merge-train.py').read_text()
        self.assertFalse(policy.requires_exact_head(source, 'agent/test', proof.CONTEXT))
        self.assertTrue(policy.requires_exact_head(source, 'codex/test', proof.CONTEXT))

    def test_modern_agent_merge_has_separate_pr_evidence_and_real_executor(self):
        github = PolicyGitHub()
        result = self.authorize(github)
        self.assertNotIn('authorization', result)
        self.assertEqual(result['mergedById'], old.BOT['id'])
        observed = result['policyAuthorization']
        self.assertEqual((observed['kind'], observed['scope']), ('POLICY_PR_AUTO_MERGE', 'PR'))
        self.assertEqual(observed['approvedById'], old.OWNER['id'])
        self.assertEqual(observed['policyCommitSha'], PARENT)
        self.assertEqual(observed['policyBlobSha'], github.blob_sha)
        self.assertTrue(all('/contents/' not in call for call in github.calls))

    def test_reviewed_13794_first_parent_policy_preserves_pr_intent(self):
        # Public immutable source at dcca6099c35d87a57631fa9d5484e34b580d9a16,
        # archived without executing it. Exercise the real allowlist and blob digest.
        raw = gzip.decompress(Path(__file__).with_name('fixtures').joinpath(
            'merge-policy-0d061611.py.gz').read_bytes())
        sha = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
        self.assertEqual(sha, '0d0616110f79ec217cba1709a0a3908ae3c8e933')
        github = PolicyGitHub()
        github.responses[f'/git/trees/{SCRIPTS}']['tree'][0]['sha'] = sha
        github.responses[f'/git/blobs/{sha}'] = {
            'sha': sha, 'size': len(raw), 'encoding': 'base64',
            'content': base64.b64encode(raw).decode(),
        }
        observed = proof.app_authorization(copy.deepcopy(github.pull), old.REPO, github)
        self.assertNotIn('authorization', observed)
        self.assertEqual(observed['mergedById'], old.BOT['id'])
        self.assertEqual(observed['policyAuthorization']['policyBlobSha'], sha)
        self.assertEqual(observed['policyAuthorization']['kind'], 'POLICY_PR_AUTO_MERGE')
        self.assertEqual(observed['policyAuthorization']['scope'], 'PR')
        self.assertEqual(observed['policyAuthorization']['approvedById'], old.OWNER['id'])

    def historical_32eb_policy(self, parent, *, suffix=b''):
        # Public source shared by the canonical first parents of #13770 and #13847.
        # Never import/execute the archived controller; exercise the real immutable reader.
        raw = gzip.decompress(Path(__file__).with_name('fixtures').joinpath(
            'merge-policy-32eb598e.py.gz').read_bytes()) + suffix
        sha = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
        if not suffix:
            self.assertEqual(sha, '32eb598ea12065ec54e6b16769e14d870e2d21e1')
        github = PolicyGitHub()
        github.responses[f'/git/commits/{old.MERGE}']['parents'] = [{'sha': parent}]
        github.responses[f'/compare/{parent}...main'] = {
            'status': 'ahead', 'base_commit': {'sha': parent}, 'merge_base_commit': {'sha': parent}}
        github.responses[f'/git/commits/{parent}'] = {'sha': parent, 'tree': {'sha': ROOT}}
        github.responses[f'/git/trees/{SCRIPTS}']['tree'][0]['sha'] = sha
        github.responses[f'/git/blobs/{sha}'] = {
            'sha': sha, 'size': len(raw), 'encoding': 'base64',
            'content': base64.b64encode(raw).decode(),
        }
        return github

    def test_reviewed_13770_and_13847_first_parent_policy_keeps_executor_separate(self):
        for historical_pr, parent in [
            (13770, '25d14bb5dd65054634b9ed67af047120ca58fc2c'),
            (13847, 'bc4533123a2d8b52ba0b5e3bdfefaa91a712d6f2'),
        ]:
            with self.subTest(historical_pr=historical_pr):
                github = self.historical_32eb_policy(parent)
                result = proof.app_authorization(copy.deepcopy(github.pull), old.REPO, github)
                self.assertNotIn('authorization', result)
                self.assertEqual(result['mergedById'], old.BOT['id'])
                self.assertEqual(result['policyAuthorization']['approvedById'], old.OWNER['id'])
                self.assertEqual(result['policyAuthorization']['policyCommitSha'], parent)
                self.assertEqual(result['policyAuthorization']['policyBlobSha'],
                                 '32eb598ea12065ec54e6b16769e14d870e2d21e1')
                self.assertEqual(result['policyAuthorization']['kind'], 'POLICY_PR_AUTO_MERGE')
                self.assertEqual(result['policyAuthorization']['scope'], 'PR')

    def test_reviewed_32eb_policy_never_downgrades_codex_exact_head(self):
        github = self.historical_32eb_policy(PARENT)
        github.pull['head']['ref'] = 'codex/audit'
        with self.assertRaises(ValueError):
            proof.app_authorization(copy.deepcopy(github.pull), old.REPO, github)
        self.assertFalse(any('/git/' in path for path in github.calls))

    def test_unreviewed_nearby_32eb_source_still_fails_closed(self):
        github = self.historical_32eb_policy(PARENT, suffix=b'\n# Unreviewed source revision.\n')
        with self.assertRaisesRegex(ValueError, 'unreviewed immutable policy blob'):
            proof.app_authorization(copy.deepcopy(github.pull), old.REPO, github)

    def historical_589e_policy(self, parent, *, suffix=b''):
        # Public canonical first-parent source for #13697, #13772 and #13735.
        # This fixture is parsed as data only: never import or execute the controller.
        raw = gzip.decompress(Path(__file__).with_name('fixtures').joinpath(
            'merge-policy-589e9803.py.gz').read_bytes()) + suffix
        sha = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
        if not suffix:
            self.assertEqual(sha, '589e9803da67609d7bcb43e79cabaf5e73b44e37')
        github = PolicyGitHub()
        github.responses[f'/git/commits/{old.MERGE}']['parents'] = [{'sha': parent}]
        github.responses[f'/compare/{parent}...main'] = {
            'status': 'ahead', 'base_commit': {'sha': parent}, 'merge_base_commit': {'sha': parent}}
        github.responses[f'/git/commits/{parent}'] = {'sha': parent, 'tree': {'sha': ROOT}}
        github.responses[f'/git/trees/{SCRIPTS}']['tree'][0]['sha'] = sha
        github.responses[f'/git/blobs/{sha}'] = {
            'sha': sha, 'size': len(raw), 'encoding': 'base64',
            'content': base64.b64encode(raw).decode(),
        }
        return github


    def test_reviewed_589e_first_parents_keep_executor_and_pr_intent_separate(self):
        for historical_pr, parent in [
            (13697, '2947705e724626ea6811a43fc66d40780cdcc8f7'),
            (13772, 'f7ce73471d1c7d26ab266b33f974e02607c1afc3'),
            (13735, '17ca9124e98a69da3d7c49689b48b4573359b915'),
        ]:
            with self.subTest(historical_pr=historical_pr):
                github = self.historical_589e_policy(parent)
                result = proof.app_authorization(copy.deepcopy(github.pull), old.REPO, github)
                self.assertNotIn('authorization', result)
                self.assertEqual(result['mergedById'], old.BOT['id'])
                self.assertEqual(result['policyAuthorization']['approvedById'], old.OWNER['id'])
                self.assertEqual(result['policyAuthorization']['policyCommitSha'], parent)
                self.assertEqual(result['policyAuthorization']['policyBlobSha'],
                                 '589e9803da67609d7bcb43e79cabaf5e73b44e37')
                self.assertEqual(result['policyAuthorization']['kind'], 'POLICY_PR_AUTO_MERGE')
                self.assertEqual(result['policyAuthorization']['scope'], 'PR')


    def test_reviewed_589e_policy_cannot_downgrade_codex_exact_head(self):
        github = self.historical_589e_policy(PARENT)
        github.pull['head']['ref'] = 'codex/audit'
        with self.assertRaises(ValueError):
            proof.app_authorization(copy.deepcopy(github.pull), old.REPO, github)
        self.assertFalse(any('/git/' in path for path in github.calls))


    def test_unreviewed_nearby_589e_source_is_rejected(self):
        github = self.historical_589e_policy(PARENT, suffix=b'\n# Unreviewed source revision.\n')
        with self.assertRaisesRegex(ValueError, 'unreviewed immutable policy blob'):
            proof.app_authorization(copy.deepcopy(github.pull), old.REPO, github)


    def assert_reviewed_source_chain(self, identity):
        # Complete public controller source is inert data; never import or execute it.
        raw = gzip.decompress(Path(__file__).with_name('fixtures').joinpath(
            f'merge-policy-{identity[:8]}.py.gz').read_bytes())
        self.assertEqual(hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest(), identity)

        def fixture(content=raw):
            github = PolicyGitHub()
            sha = hashlib.sha1(b'blob ' + str(len(content)).encode() + b'\0' + content).hexdigest()
            github.responses[f'/git/trees/{SCRIPTS}']['tree'][0]['sha'] = sha
            github.responses[f'/git/blobs/{sha}'] = {
                'sha': sha, 'size': len(content), 'encoding': 'base64',
                'content': base64.b64encode(content).decode(),
            }
            return github

        github = fixture()
        result = proof.app_authorization(copy.deepcopy(github.pull), old.REPO, github)
        self.assertNotIn('authorization', result)
        self.assertEqual(result['mergedById'], old.BOT['id'])
        context = result['policyAuthorization']
        self.assertEqual(context['approvedById'], old.OWNER['id'])
        self.assertEqual(context['policyCommitSha'], PARENT)
        self.assertEqual(context['policyBlobSha'], identity)
        self.assertEqual((context['kind'], context['scope']), ('POLICY_PR_AUTO_MERGE', 'PR'))
        # Recognition does not widen to nearby source, off-main ancestry or codex approval.
        github = fixture(raw + b'\n# Unreviewed source revision.\n')
        with self.assertRaisesRegex(ValueError, 'unreviewed immutable policy blob'):
            proof.app_authorization(copy.deepcopy(github.pull), old.REPO, github)
        github = fixture()
        github.responses[f'/compare/{PARENT}...main']['status'] = 'diverged'
        with self.assertRaisesRegex(ValueError, 'not on canonical main'):
            proof.app_authorization(copy.deepcopy(github.pull), old.REPO, github)
        github = fixture()
        github.pull['head']['ref'] = 'codex/audit'
        with self.assertRaises(ValueError):
            proof.app_authorization(copy.deepcopy(github.pull), old.REPO, github)
        self.assertFalse(any('/git/' in path for path in github.calls))

    def test_reviewed_bdc11a54_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('bdc11a54da45ff594f12d72eb7b2b3bf35139a0f')

    def test_reviewed_9337dc5b_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('9337dc5bf351dd3c9310b1ac9dafeea6ee1f2976')

    def test_reviewed_b03f72aa_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('b03f72aa290b53f9826007b8a745fb77ea15b9ec')

    def test_reviewed_19f3b8c0_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('19f3b8c002b9f09439ca152b581121eba0e83274')

    def test_reviewed_231ad0e8_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('231ad0e824f57749b3cfa482ba10593557cbec2f')

    def test_reviewed_1a4024d2_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('1a4024d26828985dd9a620c49b7deac393bc70ed')

    def test_reviewed_eef9500b_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('eef9500b378c7630aff90490b68686b4f0f02cf8')

    def test_reviewed_0706fa38_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('0706fa384166e6be506fa7dc5393a3d69906b6ea')

    def test_reviewed_5e1458d0_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('5e1458d0abd19d575cd667e4fae834a5f5aebe00')

    def test_reviewed_3e8b1a85_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('3e8b1a854530ad67b8b2c15e6b68b32788519ef2')

    def test_reviewed_d628b9a9_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('d628b9a9c116f2274fb76e4e8cbdf5c4f80ed22e')

    def test_reviewed_948e81d7_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('948e81d73f39584e2c9672f56f5c621b50955c1c')

    def test_reviewed_4ff3f289_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('4ff3f289c41f697c1f498435a0ac7bcf4fa6b507')

    def test_reviewed_bffe20f4_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('bffe20f4c51c3b6fe3a7d961617d4f484276478e')

    def test_reviewed_82f517cd_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('82f517cde74e25753d7c498401c4cc9d861074b5')

    def test_current_policy_source_is_within_bounds(self):
        # #16187: the controller outgrew the size bound and nothing noticed until merge records
        # were already missing. The size bound stays a PR-time check on the working tree.
        raw = Path(__file__).with_name('merge-train.py').read_bytes()
        self.assertLessEqual(len(raw), policy.MAX_POLICY_BLOB_BYTES)

    def test_policy_change_carries_its_own_attestation(self):
        # Replaces the tripwire that asserted main's CURRENT blob on every PR (#16187): only a PR
        # whose diff changes merge-train.py must list its resulting blob, so its review covers it.
        repo = Path(__file__).resolve().parents[2]
        base = os.environ.get('PR_DIFF_BASE') or 'refs/remotes/origin/main'
        try:
            blob = policy.unattested_policy_change(repo, base)
        except ValueError as error:
            if os.environ.get('GITHUB_ACTIONS') or os.environ.get('PR_DIFF_BASE'):
                raise  # in CI an unanswerable comparison must not read as a pass
            self.skipTest(f'local run without a main to diff against: {error}')
        self.assertIsNone(blob, f'this change edits merge-train.py; add its blob {blob} to '
                                f'REVIEWED_POLICY_BLOBS with a fixture and chain test, so reviewing this '
                                f'PR reviews the policy')

    def test_unrelated_pr_stays_green_after_main_policy_moves(self):
        with tempfile.TemporaryDirectory() as tmp:
            commit_policy(tmp, SOURCE)
            git(tmp, 'branch', 'unrelated')
            moved = commit_policy(tmp, SOURCE + '\n# landed on main after the PR was cut\n')
            git(tmp, 'checkout', '-q', 'unrelated')
            Path(tmp, 'other.txt').write_text('x')
            git(tmp, 'add', 'other.txt')
            git(tmp, 'commit', '-q', '-m', 'unrelated')
            git(tmp, 'merge', '-q', '-m', 'merge main', 'main')  # even after merging main in
            self.assertNotIn(moved, policy.REVIEWED_POLICY_BLOBS)
            self.assertIsNone(policy.unattested_policy_change(Path(tmp), 'main'))

    def test_policy_change_without_list_entry_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            commit_policy(tmp, SOURCE)
            git(tmp, 'checkout', '-q', '-b', 'touching')
            changed = commit_policy(tmp, SOURCE + '\n# edited by this PR\n')
            with patch.object(policy, 'REVIEWED_POLICY_BLOBS', frozenset()):
                self.assertEqual(changed, policy.unattested_policy_change(Path(tmp), 'main'))
            Path(tmp, policy.POLICY_PATH).write_text(SOURCE + '\n# uncommitted edit\n')
            with patch.object(policy, 'REVIEWED_POLICY_BLOBS', frozenset({changed})):
                self.assertIsNotNone(policy.unattested_policy_change(Path(tmp), 'main'))

    def test_policy_change_with_list_entry_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            commit_policy(tmp, SOURCE)
            git(tmp, 'checkout', '-q', '-b', 'touching')
            changed = commit_policy(tmp, SOURCE + '\n# edited by this PR\n')
            with patch.object(policy, 'REVIEWED_POLICY_BLOBS', frozenset({changed})):
                self.assertIsNone(policy.unattested_policy_change(Path(tmp), 'main'))

    def test_unanswerable_comparison_fails_instead_of_passing(self):
        with tempfile.TemporaryDirectory() as tmp:
            commit_policy(tmp, SOURCE)
            with self.assertRaises(ValueError):
                policy.unattested_policy_change(Path(tmp), 'no-such-ref')

    def test_base64_bound_admits_the_largest_allowed_blob(self):
        raw = b'x' * policy.MAX_POLICY_BLOB_BYTES
        encoded = base64.encodebytes(raw).decode()  # GitHub's API wraps base64 like this
        self.assertLessEqual(len(encoded), policy.MAX_POLICY_BLOB_BASE64)

    def test_reviewed_14bd8b1e_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('14bd8b1e81b94eb0a297f645007c44d1245732cc')

    def test_reviewed_6ab73053_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('6ab73053858f41097033b13d5813ad70fcadc088')

    def test_reviewed_82d51b27_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('82d51b27e40de7e15fb7d3180cc9baaad6ebde40')

    def test_reviewed_f1dad905_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('f1dad9057e095ae790dfe89819e24ae6692e8064')

    def test_reviewed_c5fa389c_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('c5fa389c9724b4998f1c82b0281fc164194ca98b')

    def test_reviewed_a6909111_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('a6909111d6622e77549e195be58e6332b64837b6')

    def test_reviewed_2af53eb7_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('2af53eb7424798ae66558c81417190d2805943e7')

    def test_reviewed_048d8fe9_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('048d8fe9ab3f9b2b01b62d7390077c425031c1d1')

    def test_reviewed_cf542f14_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('cf542f1481d9c5481cb768b8531d361586703657')

    def test_reviewed_4a1ee1d5_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('4a1ee1d5b71c0866893c86c1d56a4f686b3f3d8d')

    def test_reviewed_6e04deb6_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('6e04deb65dc7f6594a5037592e95321b41a53bf3')

    def test_reviewed_8e5b4dfe_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('8e5b4dfe28d6bf527fa8e677f21e902b35ca3949')

    def test_reviewed_7887f105_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('7887f1058488cea4f26939b5ab39e7d9d909e538')

    def test_reviewed_d4c5ccda_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('d4c5ccdabee1e86687f096075120deb0b6df17a3')

    def test_reviewed_67641bd6_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('67641bd6ce24a54b632c50d16d229ce1bd579843')

    def test_reviewed_25f1786d_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('25f1786d171b1ab122685d39314fd850fc019b85')

    def test_reviewed_82746dd0_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('82746dd0548f1f2e352fefbe97cb2aa9a801df5b')

    def test_reviewed_15c3e151_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('15c3e1510c102bdd91e8006e1a04207b1abb83ef')

    def test_reviewed_0c35ce96_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('0c35ce9601da83a44dd580d42b451e95a6bd1f6a')

    def test_reviewed_1e5e9f5b_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('1e5e9f5b334f640d8102054b8155a9526d035e79')

    def test_reviewed_e2b6a5df_source_preserves_bounded_policy_chain(self):
        self.assert_reviewed_source_chain('e2b6a5dfdc307ad74c81f34595ac1a4d26a4c59a')

    def test_branch_spoofing_rejected(self):
        github = PolicyGitHub()
        pull = copy.deepcopy(github.pull)
        pull['head']['ref'] = 'other/branch'
        with self.assertRaises(ValueError):
            proof.app_authorization(pull, old.REPO, github)

    def test_codex_never_falls_back(self):
        github = PolicyGitHub()
        github.pull['head']['ref'] = 'codex/audit'
        with self.assertRaises(ValueError):
            self.authorize(github)
        self.assertFalse(any('/git/' in path for path in github.calls))

    def test_revoked_or_wrong_pr_decision_never_downgrades(self):
        for change in ({'state': 'failure'}, {'description': 'owner-approved PR #8'}):
            github = PolicyGitHub()
            github.statuses = [dict(old.fixtures()[1], **change)]
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.authorize(github)

    def test_unreviewed_whole_source_cannot_supply_policy_context(self):
        github = PolicyGitHub()
        with self.assertRaisesRegex(ValueError, 'unreviewed immutable policy blob'):
            proof.app_authorization(copy.deepcopy(github.pull), old.REPO, github)

    def test_immutable_allowlist_rejects_arbitrary_python_mutation(self):
        github = PolicyGitHub()
        raw = (SOURCE + "\nowner_approval_valid.__code__ = (lambda number, ref, app: False).__code__").encode()
        sha = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
        github.responses[f'/git/trees/{SCRIPTS}']['tree'][0]['sha'] = sha
        github.responses[f'/git/blobs/{sha}'] = {'sha': sha, 'size': len(raw), 'encoding': 'base64', 'content': base64.b64encode(raw).decode()}
        with self.assertRaisesRegex(ValueError, 'unreviewed immutable policy blob'):
            proof.app_authorization(copy.deepcopy(github.pull), old.REPO, github)

    def test_legacy_cutoff_does_not_change(self):
        github = PolicyGitHub()
        with self.assertRaises(ValueError):
            proof.legacy_authorization(github.pull, old.REPO, old.OWNER, github)

    def test_non_owner_request_rejected(self):
        github = PolicyGitHub()
        github.graph_pages[0]['repository']['pullRequest']['autoMergeRequest']['enabledBy']['databaseId'] = 999
        with self.assertRaises(ValueError):
            self.authorize(github)

    def test_disabled_request_rejected(self):
        github = PolicyGitHub()
        github.graph_pages[0]['repository']['pullRequest']['timelineItems']['nodes'].append({
            '__typename': 'AutoMergeDisabledEvent', 'createdAt': '2026-10-24T11:59:00Z'})
        with self.assertRaises(ValueError):
            self.authorize(github)

    def test_wrong_merge_event_rejected(self):
        github = PolicyGitHub()
        github.graph_pages[0]['repository']['pullRequest']['timelineItems']['nodes'][0]['commit']['oid'] = '0' * 40
        with self.assertRaises(ValueError):
            self.authorize(github)

    def test_parent_must_be_canonical_main_ancestor(self):
        for change in ({'status': 'diverged'}, {'base_commit': {'sha': old.HEAD}}, {'merge_base_commit': {'sha': old.HEAD}}):
            github = PolicyGitHub()
            github.responses[f'/compare/{PARENT}...main'].update(change)
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.authorize(github)

    def test_missing_or_wrong_canonical_commit_rejected(self):
        for change in ({'sha': old.HEAD}, {'parents': []}, {'parents': [{'sha': 'bad'}]}):
            github = PolicyGitHub()
            github.responses[f'/git/commits/{old.MERGE}'].update(change)
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.authorize(github)

    def test_symlinks_submodules_and_wrong_tree_identity_rejected(self):
        for change in ({'mode': '120000'}, {'type': 'commit'}, {'sha': 'bad'}):
            github = PolicyGitHub()
            github.responses[f'/git/trees/{SCRIPTS}']['tree'][0].update(change)
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.authorize(github)
        github = PolicyGitHub()
        github.responses[f'/git/trees/{ROOT}']['sha'] = old.HEAD
        with self.assertRaises(ValueError):
            self.authorize(github)

    def test_missing_duplicate_and_truncated_trees_rejected(self):
        for value in ([], None):
            github = PolicyGitHub()
            github.responses[f'/git/trees/{ROOT}']['tree'] = value
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.authorize(github)
        for mutation in ('duplicate', 'truncated'):
            github = PolicyGitHub()
            tree = github.responses[f'/git/trees/{ROOT}']
            if mutation == 'duplicate':
                tree['tree'] *= 2
            else:
                tree['truncated'] = True
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                self.authorize(github)

    def test_blob_digest_encoding_and_bounds_rejected(self):
        for change in ({'content': '!!!!'}, {'content': base64.b64encode(b'wrong').decode()},
                       {'size': True}, {'size': policy.MAX_POLICY_BLOB_BYTES + 1}, {'encoding': 'utf8'}, {'sha': old.HEAD}):
            github = PolicyGitHub()
            github.responses[f'/git/blobs/{github.blob_sha}'].update(change)
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.authorize(github)

    def test_unrecognized_predicate_or_constants_rejected(self):
        for source in (SOURCE.replace('return True', 'return False'), SOURCE.replace("('codex/',)", "('agent/',)"),
                       SOURCE.replace(proof.CONTEXT, 'other'), SOURCE + '\n' + policy._SUPPORTED_CHECK):
            with self.subTest(source=source), self.assertRaises(ValueError):
                policy.requires_exact_head(source, 'agent/test', proof.CONTEXT)

    def test_rebinding_and_dynamic_policy_rejected(self):
        for suffix in ("\nEXCLUDED_BRANCH_PREFIXES = ()", "\nglobals()['EXCLUDED_BRANCH_PREFIXES'] = ()",
                       "\nglobals().update({'EXCLUDED_BRANCH_PREFIXES': ()})", "\nf = globals",
                       "\nexec('anything')", "\nfrom other import owner_approval_valid",
                       "\ndef mutate():\n global EXCLUDED_BRANCH_PREFIXES\n EXCLUDED_BRANCH_PREFIXES = ()"):
            with self.subTest(suffix=suffix), self.assertRaises(ValueError):
                policy.requires_exact_head(SOURCE + suffix, 'agent/test', proof.CONTEXT)

    def test_predicate_builtin_rebinding_cannot_hide_head_requirement(self):
        for name in ('any', 'type', 'int', '__builtins__'):
            for suffix in (f"\n{name} = lambda value: True", f"\nglobals()['{name}'] = lambda value: True"):
                with self.subTest(suffix=suffix), self.assertRaises(ValueError):
                    policy.requires_exact_head(SOURCE + suffix, 'agent/test', proof.CONTEXT)

    def test_unrelated_literal_test_fixture_rebinding_is_supported(self):
        self.assertFalse(policy.requires_exact_head(SOURCE + "\nglobals()['api'] = fixture_api", 'agent/test', proof.CONTEXT))

    def test_readback_does_not_accept_other_or_dual_proof_family(self):
        payload = old.recorder.build_payload(PolicyGitHub().pull, 'audit')
        payload.update(self.authorize())
        stored = copy.deepcopy(payload)
        old.recorder.verify_stored(payload, stored)
        stored['authorization'] = stored.pop('policyAuthorization')
        with self.assertRaises(ValueError):
            old.recorder.verify_stored(payload, stored)
        stored['policyAuthorization'] = payload['policyAuthorization']
        with self.assertRaises(ValueError):
            old.recorder.verify_stored(payload, stored)

    def test_policy_capability_requires_literal_true_before_any_post(self):
        # Exercise the real CLI preflight, not a reimplementation of the flag check.
        harness = old.CommittedEvidenceTests()
        harness.github = PolicyGitHub()
        harness.pull = copy.deepcopy(harness.github.pull)
        for capability in ({}, {'policyPrAuthorization': False}, {'policyPrAuthorization': 1},
                           {'policyPrAuthorization': 'true'}):
            post = Mock(return_value=None)
            with self.subTest(capability=capability), patch.object(policy, 'REVIEWED_POLICY_BLOBS', {harness.github.blob_sha}):
                harness.github = PolicyGitHub()
                self.assertEqual(harness.run_main([{'evidenceSchemaVersion': 2, **capability}], post), 1)
                post.assert_not_called()

    def test_policy_cli_write_and_independent_readback_succeed(self):
        harness = old.CommittedEvidenceTests()
        harness.github = PolicyGitHub()
        harness.pull = copy.deepcopy(harness.github.pull)
        payload = old.recorder.build_payload(harness.pull, old.recorder.CODEX_AGENT_ID)
        payload.update(self.authorize())
        post = Mock(return_value=None)
        with patch.object(policy, 'REVIEWED_POLICY_BLOBS', {harness.github.blob_sha}):
            self.assertEqual(harness.run_main([
                {'evidenceSchemaVersion': 2, 'policyPrAuthorization': True}, payload], post), 0)
        post.assert_called_once()
        self.assertNotIn('authorization', post.call_args.args[2])
        self.assertEqual(post.call_args.args[2]['policyAuthorization']['policyCommitSha'], PARENT)

    def test_policy_posts_to_separate_endpoint(self):
        with old.local_backend() as (source, received):
            self.assertIsNone(old.recorder.post_record(source, 'synthetic-token', {
                'mergedByType': 'GITHUB_APP', 'policyAuthorization': {'kind': 'POLICY_PR_AUTO_MERGE'}}))
            self.assertEqual(received[0][:2], ('POST', old.recorder.ENDPOINT_PATH + '/app-policy'))

    def test_readback_policy_sha_conflict_rejected(self):
        payload = old.recorder.build_payload(PolicyGitHub().pull, 'audit')
        payload.update(self.authorize())
        for key in ('policyCommitSha', 'policyBlobSha'):
            stored = copy.deepcopy(payload)
            stored['policyAuthorization'][key] = old.HEAD
            with self.subTest(key=key), self.assertRaises(ValueError):
                old.recorder.verify_stored(payload, stored)


    def historical_6d202_policy(self, parent, *, suffix=b''):
        # Public canonical first-parent source for #13904.
        # This fixture is parsed as data only: never import or execute the controller.
        raw = gzip.decompress(Path(__file__).with_name('fixtures').joinpath(
            'merge-policy-6d20261e.py.gz').read_bytes()) + suffix
        sha = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
        if not suffix:
            self.assertEqual(sha, '6d20261e47cf64d423ac251e236f70e88db61461')
        github = PolicyGitHub()
        github.responses[f'/git/commits/{old.MERGE}']['parents'] = [{'sha': parent}]
        github.responses[f'/compare/{parent}...main'] = {
            'status': 'ahead', 'base_commit': {'sha': parent}, 'merge_base_commit': {'sha': parent}}
        github.responses[f'/git/commits/{parent}'] = {'sha': parent, 'tree': {'sha': ROOT}}
        github.responses[f'/git/trees/{SCRIPTS}']['tree'][0]['sha'] = sha
        github.responses[f'/git/blobs/{sha}'] = {
            'sha': sha, 'size': len(raw), 'encoding': 'base64',
            'content': base64.b64encode(raw).decode(),
        }
        return github


    def test_reviewed_6d202_first_parents_keep_executor_and_pr_intent_separate(self):
        for historical_pr, parent in [(13904, '15165dc8435139b5800be51d0ee6ecef069d4bc4')]:
            with self.subTest(historical_pr=historical_pr):
                github = self.historical_6d202_policy(parent)
                result = proof.app_authorization(copy.deepcopy(github.pull), old.REPO, github)
                self.assertNotIn('authorization', result)
                self.assertEqual(result['mergedById'], old.BOT['id'])
                self.assertEqual(result['policyAuthorization']['approvedById'], old.OWNER['id'])
                self.assertEqual(result['policyAuthorization']['policyCommitSha'], parent)
                self.assertEqual(result['policyAuthorization']['policyBlobSha'],
                                 '6d20261e47cf64d423ac251e236f70e88db61461')
                self.assertEqual(result['policyAuthorization']['kind'], 'POLICY_PR_AUTO_MERGE')
                self.assertEqual(result['policyAuthorization']['scope'], 'PR')


    def test_reviewed_6d202_policy_cannot_downgrade_codex_exact_head(self):
        github = self.historical_6d202_policy(PARENT)
        github.pull['head']['ref'] = 'codex/audit'
        with self.assertRaises(ValueError):
            proof.app_authorization(copy.deepcopy(github.pull), old.REPO, github)
        self.assertFalse(any('/git/' in path for path in github.calls))


    def test_unreviewed_nearby_6d202_source_is_rejected(self):
        github = self.historical_6d202_policy(PARENT, suffix=b'\n# Unreviewed source revision.\n')
        with self.assertRaisesRegex(ValueError, 'unreviewed immutable policy blob'):
            proof.app_authorization(copy.deepcopy(github.pull), old.REPO, github)



if __name__ == '__main__':
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(PolicyEvidenceTests)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    print(f'SUBJECTS={result.testsRun}')
    sys.exit(not result.wasSuccessful())
