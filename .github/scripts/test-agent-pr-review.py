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


if __name__ == '__main__':
    unittest.main()
