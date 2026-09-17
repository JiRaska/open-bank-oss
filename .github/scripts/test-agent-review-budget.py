#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Offline regression tests: no provider calls or credentials."""
import importlib.util
import json
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('budget', Path(__file__).with_name('agent-review-budget.py'))
budget = importlib.util.module_from_spec(spec)
spec.loader.exec_module(budget)
NOW = datetime(2026, 1, 10, tzinfo=timezone.utc)


def run(identity, **kwargs):
    return dict(dict(id=identity, event='workflow_dispatch', created_at=NOW.isoformat(),
                     run_attempt=1, status='completed', conclusion='success'), **kwargs)


def pages(*runs):
    return [dict(total_count=len(runs), workflow_runs=list(runs))]


class BudgetTest(unittest.TestCase):
    def test_first_and_second_dispatch_admitted(self):
        budget.admit(pages(run(1)), 1, NOW)
        budget.admit(pages(run(1), run(2)), 2, NOW)

    def test_failure_cancellation_and_inflight_all_consume_slots(self):
        for status in ('failure', 'cancelled', 'in_progress', 'success'):
            with self.subTest(status=status), self.assertRaises(ValueError):
                budget.admit(pages(run(1, conclusion=status), run(2), run(3)), 3, NOW)

    def test_one_previous_failure_opens_circuit_before_weekly_limit(self):
        for overrides in ({'conclusion': 'failure'}, {'conclusion': 'cancelled'},
                          {'status': 'in_progress', 'conclusion': None}):
            with self.subTest(overrides=overrides), self.assertRaises(ValueError):
                budget.admit(pages(run(1, **overrides), run(2)), 2, NOW)

    def test_missing_current_history_duplicate_and_pagination_fail_closed(self):
        for history in ([], [{}], pages(run(2)), pages(run(1), run(1)),
                        [dict(total_count=2, workflow_runs=[run(1)])],
                        [dict(total_count=1000, workflow_runs=[run(1)])]):
            with self.subTest(history=history), self.assertRaises(ValueError):
                budget.admit(history, 1, NOW)

    def test_rerun_and_automatic_event_rejected(self):
        for key, value in (('run_attempt', 2), ('event', 'pull_request')):
            candidate = run(1)
            candidate[key] = value
            with self.assertRaises(ValueError):
                budget.admit(pages(candidate), 1, NOW)

    def test_window_boundary_and_future_rejected(self):
        for delta in (timedelta(days=-7, seconds=-1), timedelta(seconds=1)):
            candidate = run(1)
            candidate['created_at'] = (NOW + delta).isoformat()
            with self.assertRaises(ValueError):
                budget.admit(pages(candidate), 1, NOW)
        candidate['created_at'] = (NOW - timedelta(days=7)).isoformat()
        budget.admit(pages(candidate), 1, NOW)

    def test_usage_unknown_never_zero(self):
        for raw in ('', 'broken', '{"type":"result"}\n{"type":"result"}'):
            self.assertIsNone(budget.usage(raw)['estimated_cost_usd'])
            self.assertTrue(all(v is None for v in budget.usage(raw)['tokens'].values()))

    def test_usage_extracts_numbers_only(self):
        result = budget.usage(json.dumps(dict(type='result', total_cost_usd=.25,
                                             usage=dict(input_tokens=123, output_tokens=-1),
                                             result='private content')))
        self.assertEqual(result['tokens']['input_tokens'], 123)
        self.assertIsNone(result['tokens']['output_tokens'])
        self.assertEqual(result['estimated_cost_usd'], .25)
        self.assertNotIn('private content', json.dumps(result))

    def test_nonfinite_and_boolean_accounting_rejected(self):
        for value in (True, -1, float('inf'), float('nan')):
            result = budget.usage(json.dumps(dict(type='result', total_cost_usd=value,
                                                 usage=dict(input_tokens=value))))
            self.assertIsNone(result['estimated_cost_usd'])
            self.assertIsNone(result['tokens']['input_tokens'])

    def test_cli_rejects_automatic_and_rerun_before_network(self):
        for env in ({'GITHUB_EVENT_NAME': 'pull_request'},
                    {'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_RUN_ATTEMPT': '2'}):
            with patch.dict('os.environ', env, clear=True), patch.object(budget, 'gh') as remote:
                with self.assertRaises(ValueError):
                    budget.main()
                remote.assert_not_called()

    def test_workflow_isolated_and_workers_parked(self):
        root = Path(__file__).resolve().parents[2]
        workflow = (root / '.github/workflows/agent-review.yml').read_text()
        self.assertNotIn('CLAUDE_CODE_OAUTH_TOKEN', workflow)
        self.assertNotIn('Reply with exactly the word ALIVE', workflow)
        self.assertIn('AGENT_REVIEW_ANTHROPIC_API_KEY', workflow)
        self.assertIn('needs: budget-tests', workflow)
        self.assertIn('python3 .github/scripts/agent-review-budget.py', workflow)
        self.assertIn('--max-turns 2 --max-budget-usd 0.50', workflow)
        self.assertIn('cancel-in-progress: false', workflow)
        for name in ('agent-issue-worker.yml', 'agent-pr-steward.yml'):
            worker = (root / '.github/workflows' / name).read_text()
            self.assertNotIn('CLAUDE_CODE_OAUTH_TOKEN', worker)
            self.assertIn('ANTHROPIC_API_KEY: ""', worker)
            self.assertIn("vars.AGENT_MUTATING_WORKERS_ENABLED == 'true'", worker)


if __name__ == '__main__':
    unittest.main()
