#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Exercise admission boundaries and workflow wiring without credentials or network."""
import importlib.util
import io
import json
import subprocess
import sys
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

import yaml

spec = importlib.util.spec_from_file_location('admission', Path(__file__).with_name('agent-admission.py'))
admission = importlib.util.module_from_spec(spec)
spec.loader.exec_module(admission)


def pr(number, branch='agent/fix', **extra):
    item = dict(number=number, head={'ref': branch}, state='open', created_at='2026-10-03T20:00:00Z')
    item.update(extra)
    return item


class AdmissionTest(unittest.TestCase):
    prefixes = ('agent/', 'codex/')
    limit = 3
    cutoff = '2026-10-03T19:00:00Z'

    def test_review_events_use_the_same_ci_admission_as_pr_pushes(self):
        workflow = yaml.safe_load((Path(__file__).parents[1] / 'workflows/ci.yml').read_text())
        steps = workflow['jobs']['agent-admission']['steps']
        checkout = steps[0]
        admission = steps[1]
        self.assertIn('pull_request_review', checkout['if'])
        self.assertIn("= 'push'", admission['run'])
        self.assertIn('--current-pr "$PR_NUMBER"', admission['run'])

    def test_empty_queue(self):
        self.assertEqual(admission.admit([[]], self.prefixes, self.limit), (True, 0))

    def test_capacity_boundary(self):
        self.assertEqual(admission.admit([[pr(1), pr(2, 'codex/fix')]], self.prefixes, self.limit), (True, 2))
        self.assertEqual(admission.admit([[pr(1), pr(2, 'codex/fix'), pr(3)]], self.prefixes, self.limit), (False, 3))

    def test_pagination_drafts_and_other_branches(self):
        self.assertEqual(admission.admit([[pr(1, draft=True), pr(2, 'codex/fix')],
                                          [pr(3), pr(4, 'fix/human')]], self.prefixes, self.limit), (False, 3))

    def test_invalid_snapshot_fails_closed(self):
        for pages in (None, {}, [], [None], [[{}]], [[pr(1), pr(1)]],
                      [[{'number': 1, 'state': 'closed', 'head': {'ref': 'agent/x'}}]]):
            with self.subTest(pages=pages), self.assertRaises(ValueError):
                admission.admit(pages, self.prefixes, self.limit)

    def test_rules_are_the_prefix_authority(self):
        prefixes, limit, cutoff = admission.load_policy()
        self.assertIn('agent/', prefixes)
        self.assertIn('codex/', prefixes)
        self.assertEqual(limit, 3)
        self.assertEqual(cutoff, '2026-10-03T20:00:00Z')

    def test_invalid_rules_fail_closed(self):
        import tempfile
        for content in ('{}', '[]', 'autonomous_agent_prs: {}',
                        'autonomous_agent_prs:\n  agent_branch_prefixes: []',
                        'autonomous_agent_prs:\n  agent_branch_prefixes: [agent/]\n  max_open_prs: 0',
                        'autonomous_agent_prs:\n  agent_branch_prefixes: [agent/]\n  max_open_prs: true',
                        'autonomous_agent_prs:\n  agent_branch_prefixes: [agent/]\n  max_open_prs: 3\n  grandfather_created_before: bad'):
            with tempfile.NamedTemporaryFile(mode='w', suffix='.yaml') as rules:
                rules.write(content)
                rules.flush()
                with self.assertRaises(ValueError):
                    admission.load_policy(rules.name)

    def test_caller_cannot_supply_a_future_grandfather_cutoff(self):
        import tempfile
        with tempfile.NamedTemporaryFile(mode='w', suffix='.json') as snapshot:
            json.dump([[pr(1)]], snapshot)
            snapshot.flush()
            result = subprocess.run(
                [sys.executable, str(Path(__file__).with_name('agent-admission.py')),
                 snapshot.name, '--current-pr', '1', '2999-01-01T00:00:00Z'],
                capture_output=True, text=True, check=False,
            )
        self.assertEqual(result.returncode, 1)
        self.assertIn('usage:', result.stderr)

    def test_current_pr_first_three_win_even_with_concurrent_openings(self):
        pages = [[pr(10, draft=True), pr(11, 'codex/fix')], [pr(12), pr(13)]]
        self.assertEqual(admission.admit_current_pr(pages, self.prefixes, self.limit, 12, self.cutoff), (True, 3))
        self.assertEqual(admission.admit_current_pr(pages, self.prefixes, self.limit, 13, self.cutoff), (False, 4))

    def test_current_pr_can_proceed_after_an_older_pr_closes(self):
        pages = [[pr(10), pr(11, 'codex/fix'), pr(13)]]
        self.assertEqual(admission.admit_current_pr(pages, self.prefixes, self.limit, 13, self.cutoff), (True, 3))

    def test_grandfathered_pr_does_not_deadlock_the_existing_queue(self):
        pages = [[pr(1, created_at='2026-10-03T18:00:00Z'), pr(2), pr(3), pr(4)]]
        self.assertEqual(admission.admit_current_pr(pages, self.prefixes, self.limit, 1, self.cutoff), (True, 1))
        self.assertEqual(admission.admit_current_pr(pages, self.prefixes, self.limit, 4, self.cutoff), (False, 4))

    def test_human_branch_is_out_of_scope(self):
        pages = [[pr(1), pr(2), pr(3), pr(4, 'fix/human')]]
        self.assertEqual(admission.admit_current_pr(pages, self.prefixes, self.limit, 4, self.cutoff), (True, 0))

    def test_current_pr_missing_or_timestamp_unreadable_fails_closed(self):
        pages = [[pr(1)]]
        for number, cutoff in ((2, self.cutoff), (1, 'bad')):
            with self.subTest(number=number, cutoff=cutoff), self.assertRaises(ValueError):
                admission.admit_current_pr(pages, self.prefixes, self.limit, number, cutoff)
        with self.assertRaises(ValueError):
            admission.admit_current_pr([[pr(1, created_at=None)]], self.prefixes, self.limit, 1, self.cutoff)

    def test_only_later_exact_head_owner_admission_counts(self):
        head = 'a' * 40
        created = '2026-10-05T10:00:00Z'
        def comment(number, body, login='JiRaska', when='2026-10-05T10:01:00Z', kind='User'):
            return {'id': number, 'body': body, 'user': {'login': login, 'type': kind}, 'created_at': when}
        command = f'/agent-pr admit 12162 {head}'
        good = comment(5, command)
        self.assertTrue(admission.owner_admission_allows([good], 'JiRaska', 12162, head, created))
        for bad in (comment(1, command, login='someone-else'),
                    comment(2, command, kind='Bot'),
                    comment(3, command, when='2026-10-05T09:59:59Z'),
                    comment(4, f'/agent-pr admit 12163 {head}'),
                    comment(6, f'/agent-pr admit 12162 {"b" * 40}')):
            with self.subTest(bad=bad):
                self.assertFalse(admission.owner_admission_allows([bad], 'JiRaska', 12162, head, created))
        revoke = comment(6, f'/agent-pr revoke-admission 12162 {head}')
        self.assertFalse(admission.owner_admission_allows([good, revoke], 'JiRaska', 12162, head, created))

    def test_owner_admission_rechecks_live_head(self):
        head = 'a' * 40
        current = pr(12162, 'codex/fix', head={'ref': 'codex/fix', 'sha': head})
        repo = {'owner': {'login': 'JiRaska'}}
        live = {'state': 'open', 'head': {'sha': head}}
        commit = {'commit': {'committer': {'date': '2026-10-05T10:00:00Z'}}}
        comments = [[{'id': 1, 'body': f'/agent-pr admit 12162 {head}',
                      'user': {'login': 'JiRaska', 'type': 'User'}, 'created_at': '2026-10-05T10:01:00Z'}]]
        with patch.dict('os.environ', {'GITHUB_REPOSITORY': 'JiRaska/open-bank-oss', 'GH_TOKEN': 'test'}), \
                patch.object(admission, '_gh_api', side_effect=[live, repo, commit, comments, live]):
            self.assertTrue(admission.approved_owner_admission(current))
        changed = {'state': 'open', 'head': {'sha': 'b' * 40}}
        with patch.dict('os.environ', {'GITHUB_REPOSITORY': 'JiRaska/open-bank-oss', 'GH_TOKEN': 'test'}), \
                patch.object(admission, '_gh_api', side_effect=[live, repo, commit, comments, changed]):
            with self.assertRaises(ValueError):
                admission.approved_owner_admission(current)

    def test_exact_head_owner_admission_only_unblocks_current_pr(self):
        import tempfile
        head = 'a' * 40
        pages = [[pr(1), pr(2), pr(3), pr(4, 'codex/fix', head={'ref': 'codex/fix', 'sha': head})]]
        with tempfile.NamedTemporaryFile(mode='w', suffix='.json') as snapshot:
            json.dump(pages, snapshot)
            snapshot.flush()
            with patch.object(sys, 'argv', ['agent-admission.py', snapshot.name, '--current-pr', '4']), \
                    patch.object(admission, 'approved_owner_admission', return_value=True) as owner_check, \
                    redirect_stdout(io.StringIO()) as output, redirect_stderr(io.StringIO()):
                self.assertEqual(admission.main(), 0)
            self.assertIn('proceed=true', output.getvalue())
            owner_check.assert_called_once()
            with patch.object(sys, 'argv', ['agent-admission.py', snapshot.name, '--current-pr', '1']), \
                    patch.object(admission, 'approved_owner_admission') as owner_check, \
                    redirect_stdout(io.StringIO()) as output, redirect_stderr(io.StringIO()):
                self.assertEqual(admission.main(), 0)
            self.assertIn('proceed=true', output.getvalue())
            owner_check.assert_not_called()


if __name__ == '__main__':
    unittest.main()
