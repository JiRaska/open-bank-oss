import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('guard', Path(__file__).with_name('check-agent-pr-guard.py'))
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)
SHA = 'a' * 40


def review(i=1, login='reviewer', state='APPROVED', sha=SHA, kind='User'):
    return dict(id=i, user=dict(login=login, type=kind), state=state, commit_id=sha)


class ReviewTests(unittest.TestCase):
    def candidates(self, *reviews):
        return guard.approved_reviewers(reviews, 'author', SHA, {'agent'})

    def test_current_human(self):
        self.assertEqual(['reviewer'], self.candidates(review()))

    def test_self_bot_and_declared_agent(self):
        for r in (review(login='AUTHOR'), review(kind='Bot'), review(login='agent')):
            self.assertEqual([], self.candidates(r))

    def test_stale(self):
        self.assertEqual([], self.candidates(review(sha='b' * 40)))

    def test_dismissed(self):
        self.assertEqual([], self.candidates(review(state='DISMISSED')))

    def test_latest_change_request_revokes(self):
        self.assertEqual([], self.candidates(review(), review(2, state='CHANGES_REQUESTED')))

    def test_other_change_request_blocks(self):
        self.assertEqual([], self.candidates(review(), review(2, login='other', state='CHANGES_REQUESTED')))

    def test_comment_preserves(self):
        self.assertEqual(['reviewer'], self.candidates(review(), review(2, state='COMMENTED')))

    def test_duplicate_not_double_counted(self):
        self.assertEqual(['reviewer'], self.candidates(review(), review(2)))

    def test_bad_sha_fails_closed(self):
        with self.assertRaises(guard.Undetermined):
            guard.approved_reviewers([], 'author', '', set())

    def test_policy_cannot_authorize_itself(self):
        for path in guard.REVIEW_POLICY_PATHS:
            self.assertFalse(guard.human_review_allows(1, [path], {}))

    def test_live_permissions_and_two_reviewers(self):
        pr = dict(head=dict(sha=SHA), state='open', draft=False, user=dict(login='author'))
        reviews = [[review(login='one'), review(2, login='two')]]
        for permission, expected in [('write', True), ('read', False)]:
            responses = [pr, reviews, dict(permission=permission), dict(permission=permission)]
            if expected:
                responses.append(pr)
            with patch.dict(guard.os.environ, {}, clear=True), patch.object(guard, '_gh', side_effect=responses):
                self.assertEqual(expected, guard.human_review_allows(1, ['.github/scripts/example.py'], {}))

    def test_head_changes_during_evaluation(self):
        pr = dict(head=dict(sha=SHA), state='open', draft=False, user=dict(login='author'))
        changed = dict(pr, head=dict(sha='b' * 40))
        responses = [pr, [[review(login='one'), review(2, login='two')]],
                     dict(permission='write'), dict(permission='admin'), changed]
        with patch.dict(guard.os.environ, {}, clear=True), patch.object(guard, '_gh', side_effect=responses):
            with self.assertRaises(guard.Undetermined):
                guard.human_review_allows(1, ['.github/scripts/example.py'], {})

    def test_owner_decision_is_bound_to_pr_head_and_identity(self):
        def comment(i, command, login='JiRaska', sha=SHA, number=11585, when='2026-10-03T12:00:00Z'):
            return dict(id=i, user=dict(login=login, type='User'),
                        body=f'/agent-pr {command} {number} {sha}', created_at=when)
        approve = comment(1, 'approve')
        self.assertTrue(guard.owner_decision_allows([approve], 'JiRaska', 11585, SHA,
                                                    '2026-10-03T11:00:00Z'))
        for altered in (comment(1, 'approve', login='other'),
                        comment(1, 'approve', sha='b' * 40),
                        comment(1, 'approve', number=11586),
                        comment(1, 'approve', when='2026-10-03T10:00:00Z')):
            self.assertFalse(guard.owner_decision_allows([altered], 'JiRaska', 11585, SHA,
                                                         '2026-10-03T11:00:00Z'))
        self.assertFalse(guard.owner_decision_allows([approve, comment(2, 'revoke')],
                                                     'JiRaska', 11585, SHA, '2026-10-03T11:00:00Z'))
        self.assertTrue(guard.owner_decision_allows([approve, comment(2, 'revoke'),
                                                     comment(3, 'approve')],
                                                    'JiRaska', 11585, SHA, '2026-10-03T11:00:00Z'))

    def test_owner_route_cannot_approve_its_own_code(self):
        for path in guard.REVIEW_POLICY_PATHS:
            self.assertFalse(guard.owner_approval_allows(11856, [path]))

    def test_owner_route_checks_live_head_again(self):
        pr = dict(head=dict(sha=SHA), state='open', draft=False)
        comment = dict(id=1, user=dict(login='JiRaska', type='User'),
                       body=f'/agent-pr approve 11585 {SHA}',
                       created_at='2026-10-03T12:00:00Z')
        responses = [pr, dict(owner=dict(login='JiRaska')),
                     dict(commit=dict(committer=dict(date='2026-10-03T11:00:00Z'))),
                     [[comment]], dict(pr, head=dict(sha='b' * 40))]
        with patch.dict(guard.os.environ, {}, clear=True), patch.object(guard, '_gh', side_effect=responses):
            with self.assertRaises(guard.Undetermined):
                guard.owner_approval_allows(11585, ['.github/scripts/example.py'])


# ---- approval carry-over across base merges ------------------------------------------------
R = guard.REPO
MAIN = 'f' * 40          # current tip of main
M1 = 'e' * 40            # a main commit merged into the PR
M2 = 'd' * 40            # a later main commit merged into the PR
APPROVED = '1' * 40      # the head the owner approved
OTHER = '2' * 40         # an unrelated, non-main commit
HEAD = '3' * 40          # the current PR head
MID = '4' * 40           # an intermediate base-merge head
N = 11740
PATCH = [dict(filename='openbank-ledger-service/A.kt', status='modified',
              patch='@@ -1 +1 @@\n-a\n+b', sha='a' * 40)]


class FakeGitHub:
    """Answers the GETs the owner route makes, from an explicit commit graph."""

    def __init__(self, parents, patches, comments, main_has=(M1, MAIN)):
        self.parents, self.patches, self.comments, self.main_has = parents, patches, comments, set(main_has)

    def __call__(self, args):
        url = args[1]
        if url == f'repos/{R}/pulls/{N}':
            return dict(head=dict(sha=HEAD), base=dict(ref='main'), state='open', draft=False)
        if url == f'repos/{R}':
            return dict(owner=dict(login='JiRaska'))
        if url.startswith(f'repos/{R}/issues/{N}/comments'):
            return [self.comments]
        if url == f'repos/{R}/commits/main':
            return dict(sha=MAIN)
        if url.startswith(f'repos/{R}/commits/'):
            sha = url.rsplit('/', 1)[1]
            return dict(sha=sha, parents=[dict(sha=p) for p in self.parents.get(sha, [OTHER])],
                        commit=dict(committer=dict(date='2026-10-10T09:00:00Z')))
        if url.startswith(f'repos/{R}/compare/{MAIN}...'):
            sha = url.rsplit('...', 1)[1]
            if sha in self.main_has:
                return dict(status='behind', files=[])
            files = self.patches.get(sha, [])
            if files == 'ERROR':
                raise guard.Undetermined('simulated API failure')
            return dict(status='diverged', files=files)
        raise AssertionError(f'unexpected call {args}')


def decision(i, action, sha, when='2026-10-10T10:00:00Z'):
    return dict(id=i, user=dict(login='JiRaska', type='User'),
                body=f'/agent-pr {action} {N} {sha}', created_at=when)


class CarryOverTests(unittest.TestCase):
    def allows(self, parents, patches, comments, main_has=(M1, MAIN)):
        with patch.dict(guard.os.environ, {}, clear=True), \
                patch.object(guard, '_gh', side_effect=FakeGitHub(parents, patches, comments, main_has)):
            return guard.owner_approval_allows(N, ['openbank-ledger-service/A.kt'])

    def test_a_pure_base_merge_keeps_the_approval(self):
        self.assertTrue(self.allows({HEAD: [APPROVED, M1]}, {APPROVED: PATCH, HEAD: PATCH},
                                    [decision(1, 'approve', APPROVED)]))

    def test_b_merge_that_also_edits_a_pr_file_voids_it(self):
        edited = [dict(PATCH[0], patch='@@ -1 +1 @@\n-a\n+EVIL')]
        self.assertFalse(self.allows({HEAD: [APPROVED, M1]}, {APPROVED: PATCH, HEAD: edited},
                                     [decision(1, 'approve', APPROVED)]))

    def test_b2_merge_that_adds_a_file_voids_it(self):
        extra = PATCH + [dict(filename='x.kt', status='added', patch='+x', sha='b' * 40)]
        self.assertFalse(self.allows({HEAD: [APPROVED, M1]}, {APPROVED: PATCH, HEAD: extra},
                                     [decision(1, 'approve', APPROVED)]))

    def test_b3_equal_rendered_patch_with_different_blob_voids_it(self):
        # GitHub does not attest that files[].patch contains the whole change. An equal
        # rendered prefix cannot override a different content-addressed blob identity.
        hidden_tail = [dict(PATCH[0], sha='b' * 40)]
        self.assertFalse(self.allows({HEAD: [APPROVED, M1]},
                                     {APPROVED: PATCH, HEAD: hidden_tail},
                                     [decision(1, 'approve', APPROVED)]))

    def test_b4_intermediate_content_change_cannot_be_restored_to_keep_approval(self):
        mutated = [dict(PATCH[0], patch='@@ -1 +1 @@\n-a\n+EVIL', sha='b' * 40)]
        self.assertFalse(self.allows({MID: [APPROVED, M1], HEAD: [MID, M2]},
                                     {APPROVED: PATCH, MID: mutated, HEAD: PATCH},
                                     [decision(1, 'approve', APPROVED)],
                                     main_has=(M1, M2, MAIN)))

    def test_two_content_neutral_base_merges_keep_approval(self):
        self.assertTrue(self.allows({MID: [APPROVED, M1], HEAD: [MID, M2]},
                                    {APPROVED: PATCH, MID: PATCH, HEAD: PATCH},
                                    [decision(1, 'approve', APPROVED)],
                                    main_has=(M1, M2, MAIN)))

    def test_c_normal_commit_voids_it_even_with_an_equal_patch(self):
        self.assertFalse(self.allows({HEAD: [APPROVED]}, {APPROVED: PATCH, HEAD: PATCH},
                                     [decision(1, 'approve', APPROVED)]))

    def test_c2_merge_of_a_non_main_branch_voids_it(self):
        self.assertFalse(self.allows({HEAD: [APPROVED, OTHER]}, {APPROVED: PATCH, HEAD: PATCH},
                                     [decision(1, 'approve', APPROVED)]))

    def test_d_approved_sha_not_an_ancestor_voids_it(self):
        # force-push: HEAD's first-parent line never reaches APPROVED
        self.assertFalse(self.allows({HEAD: [OTHER, M1], OTHER: [MAIN]}, {APPROVED: PATCH, HEAD: PATCH},
                                     [decision(1, 'approve', APPROVED)]))

    def test_e_truncated_or_failed_compare_is_undetermined(self):
        capped = [dict(filename=f'f{i}', status='modified', patch='+', sha='a' * 40)
                  for i in range(guard.COMPARE_FILE_CAP)]
        for bad in (capped, 'ERROR', [dict(filename='bin.png', status='modified', sha='a' * 40)],
                    [dict(filename='x.kt', status='modified', patch='+x')]):
            with self.assertRaises(guard.Undetermined):
                self.allows({HEAD: [APPROVED, M1]}, {APPROVED: PATCH, HEAD: bad},
                            [decision(1, 'approve', APPROVED)])

    def test_e2_too_long_merge_chain_is_undetermined(self):
        chain = {HEAD: [HEAD, M1]}   # a cycle never reaches APPROVED
        with self.assertRaises(guard.Undetermined):
            self.allows(chain, {APPROVED: PATCH, HEAD: PATCH}, [decision(1, 'approve', APPROVED)])

    def test_f_revoke_after_approval_voids_it(self):
        for revoked in (APPROVED, HEAD):
            self.assertFalse(self.allows({HEAD: [APPROVED, M1]}, {APPROVED: PATCH, HEAD: PATCH},
                                         [decision(1, 'approve', APPROVED), decision(2, 'revoke', revoked)]))

    def test_g_exact_head_approval_still_works(self):
        self.assertTrue(self.allows({}, {}, [decision(1, 'approve', HEAD)]))

    def test_approval_older_than_its_commit_does_not_carry(self):
        self.assertFalse(self.allows({HEAD: [APPROVED, M1]}, {APPROVED: PATCH, HEAD: PATCH},
                                     [decision(1, 'approve', APPROVED, when='2026-10-10T08:00:00Z')]))


if __name__ == '__main__':
    unittest.main()
