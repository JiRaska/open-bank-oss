# SPDX-License-Identifier: Apache-2.0
import base64
import importlib.util
import json
import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "wait_dependency_review_snapshot.py"
SPEC = importlib.util.spec_from_file_location("wait_dependency_review_snapshot", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def response(base=None, head=None):
    warning = ""
    if base is not None:
        message = f"The number of snapshots compared for the base SHA ({base}) and the head SHA ({head}) do not match."
        warning = (
            "X-GitHub-Dependency-Graph-Snapshot-Warnings: "
            + base64.b64encode(message.encode()).decode()
            + "\n"
        )
    return f"HTTP/2.0 200 OK\n{warning}\n[]"


def missing_response(side):
    message = f"No snapshots were found for the {side} SHA {'a' * 40}."
    warning = base64.b64encode(message.encode()).decode()
    return f"HTTP/2.0 200 OK\nX-GitHub-Dependency-Graph-Snapshot-Warnings: {warning}\n\n[]"


class SnapshotWaitTests(unittest.TestCase):
    def test_head_producer_finishes_before_index_wait_begins(self):
        producer = MODULE.PRODUCER
        replies = iter([
            json.dumps({"check_runs": [{"name": producer, "status": "in_progress"}]}),
            json.dumps({"check_runs": [{"name": producer, "status": "completed", "conclusion": "success"}]}),
        ])
        sleeps = []
        self.assertTrue(MODULE.wait_for_head_producer(lambda: next(replies), sleeps.append, (0, 60)))
        self.assertEqual(sleeps, [60])

    def test_head_producer_failure_is_not_an_indexing_delay(self):
        failed = json.dumps({"check_runs": [
            {"name": MODULE.PRODUCER, "status": "completed", "conclusion": "failure"}
        ]})
        calls = []

        def query():
            calls.append(1)
            return failed

        with self.assertRaises(MODULE.TerminalHeadGraphError):
            MODULE.wait_for_head_producer(query, lambda _: None, (0, 60))
        self.assertEqual(len(calls), 2)

    def test_head_producer_rerun_can_replace_a_terminal_check(self):
        def page(status, conclusion=None):
            return json.dumps({"check_runs": [
                {"name": MODULE.PRODUCER, "status": status, "conclusion": conclusion}
            ]})

        replies = iter([page("completed", "failure"), page("queued"), page("completed", "success")])
        self.assertTrue(
            MODULE.wait_for_head_producer(lambda: next(replies), lambda _: None, (0, 60, 120))
        )

    def test_missing_head_producer_is_bounded(self):
        calls = []

        def query():
            calls.append(1)
            return json.dumps({"check_runs": []})

        self.assertFalse(MODULE.wait_for_head_producer(query, lambda _: None))
        self.assertEqual(len(calls), 10)
        self.assertEqual(sum(MODULE.PRODUCER_DELAYS), 1800)
        self.assertLessEqual(max(MODULE.PRODUCER_DELAYS), 300)
        probe_seconds = [
            sum(MODULE.PRODUCER_DELAYS[:i])
            for i in range(1, len(MODULE.PRODUCER_DELAYS) + 1)
        ]
        self.assertIn(960, probe_seconds)

    def test_main_queries_head_producer_before_snapshot(self):
        base = "a" * 40
        head = "b" * 40
        calls = []

        def fake_gh(*args):
            calls.append(args)
            if "/compare/" in args[0]:
                return json.dumps({"merge_base_commit": {"sha": base}})
            if "check-runs" in args[-1]:
                return json.dumps({"check_runs": [
                    {"name": MODULE.PRODUCER, "status": "completed", "conclusion": "success"}
                ]})
            return response()

        with patch.dict(os.environ, {"GITHUB_REPOSITORY": "o/r", "BASE_SHA": base, "HEAD_SHA": head}), \
             patch.object(sys, "argv", ["wait_dependency_review_snapshot.py"]), \
             patch.object(MODULE, "_gh", side_effect=fake_gh):
            self.assertEqual(MODULE.main(), 0)
        self.assertIn(f"repos/o/r/commits/{head}/check-runs?per_page=100", calls[1])
        self.assertIn("dependency-graph/compare", calls[2][-1])

    def test_missing_head_waits_then_accepts_indexed_graph(self):
        replies = iter([response(1, 0), response(1, 0), response()])
        sleeps = []
        self.assertTrue(
            MODULE.wait_for_snapshot(lambda: next(replies), sleeps.append, (0, 60, 120))
        )
        self.assertEqual(sleeps, [60, 120])

    def test_current_github_missing_head_warning_then_indexed(self):
        replies = iter([missing_response("head"), response()])
        sleeps = []
        self.assertTrue(
            MODULE.wait_for_snapshot(
                lambda: next(replies), sleeps.append, MODULE.FINAL_DELAYS[:2]
            )
        )
        self.assertEqual(sleeps, [10])

    def test_current_github_missing_base_warning_fails_closed(self):
        self.assertEqual(MODULE._snapshot_state(missing_response("base")), "missing_base")
        self.assertFalse(
            MODULE.wait_for_snapshot(
                lambda: missing_response("base"), lambda _: None, MODULE.FINAL_DELAYS
            )
        )

    def test_similar_unrecognised_warning_is_not_accepted(self):
        message = f"No snapshots were found for the head SHA {'x' * 40}."
        warning = base64.b64encode(message.encode()).decode()
        reply = f"HTTP/2.0 200 OK\nX-GitHub-Dependency-Graph-Snapshot-Warnings: {warning}\n\n[]"
        with self.assertRaisesRegex(ValueError, "unknown dependency snapshot warning"):
            MODULE._snapshot_state(reply)

    def test_missing_graph_is_bounded_and_fails_closed(self):
        calls = []

        def query():
            calls.append(1)
            return response(1, 0)

        self.assertFalse(MODULE.wait_for_snapshot(query, lambda _: None))
        self.assertEqual(len(calls), 7)
        self.assertEqual(sum(MODULE.DELAYS), 1800)

    def test_unknown_or_malformed_response_is_not_success(self):
        for reply in [
            "HTTP/2.0 503\n\n[]",
            "HTTP/2.0 200 OK\n\n{}",
            "HTTP/2.0 200 OK\nX-GitHub-Dependency-Graph-Snapshot-Warnings: bad!\n\n[]",
        ]:
            with self.subTest(reply=reply):
                self.assertFalse(
                    MODULE.wait_for_snapshot(
                        lambda reply=reply: reply, lambda _: None, (0,)
                    )
                )

    def test_exhausted_quota_stops_without_retry(self):
        calls = []

        def query():
            calls.append(1)
            raise RuntimeError("GitHub installation API quota is exhausted")

        with self.assertRaisesRegex(RuntimeError, "quota is exhausted"):
            MODULE.wait_for_snapshot(query, lambda _: None)
        self.assertEqual(len(calls), 1)

    def test_nonzero_snapshot_warning_is_not_missing_basis(self):
        self.assertTrue(MODULE._indexed(response(1, 2)))

    def test_terminal_base_stops_after_first_missing_comparison(self):
        calls = []

        def verdict():
            calls.append(1)
            return "terminal"

        with self.assertRaises(MODULE.TerminalBaseGraphError):
            MODULE.wait_for_snapshot(
                lambda: response(0, 1), lambda _: None, (0, 60), verdict
            )
        self.assertEqual(len(calls), 1)

    def test_pending_base_can_finish_during_sparse_wait(self):
        verdicts = iter(["pending", "terminal"])
        calls = []

        def query():
            calls.append(1)
            return response(0, 1)

        with self.assertRaises(MODULE.TerminalBaseGraphError):
            MODULE.wait_for_snapshot(
                query, lambda _: None, (0, 60, 120), lambda: next(verdicts)
            )
        self.assertEqual(len(calls), 2)

    def test_transient_checks_api_error_keeps_bounded_wait(self):
        def verdict():
            raise RuntimeError("GitHub dependency API unavailable")

        self.assertFalse(
            MODULE.wait_for_snapshot(
                lambda: response(0, 1), lambda _: None, (0, 60), verdict
            )
        )

    def test_successful_base_does_not_hide_missing_head(self):
        calls = []

        def verdict():
            calls.append(1)
            return "success"

        self.assertFalse(
            MODULE.wait_for_snapshot(
                lambda: response(1, 0), lambda _: None, (0, 60), verdict
            )
        )
        self.assertEqual(calls, [])

    def test_base_producer_verdict_requires_success_or_all_terminal(self):
        def page(*runs):
            return json.dumps({"check_runs": list(runs)})

        producer = MODULE.PRODUCER
        other = {"name": "other", "status": "completed", "conclusion": "failure"}
        cancelled = {"name": producer, "status": "completed", "conclusion": "cancelled"}
        timed_out = {"name": producer, "status": "completed", "conclusion": "timed_out"}
        pending = {"name": producer, "status": "in_progress", "conclusion": None}
        success = {"name": producer, "status": "completed", "conclusion": "success"}
        cases = [
            (page(other), "unknown"),
            (page(cancelled, timed_out), "terminal"),
            (page(cancelled, pending), "pending"),
            (page(cancelled) + page(other, success), "success"),
        ]
        for payload, expected in cases:
            with self.subTest(expected=expected):
                self.assertEqual(MODULE._base_producer_verdict(payload), expected)

    def test_malformed_checks_response_is_not_terminal_proof(self):
        for payload in ["", "{}", '{"check_runs":null}', "not-json"]:
            with self.subTest(payload=payload):
                with self.assertRaises(ValueError):
                    MODULE._base_producer_verdict(payload)


if __name__ == "__main__":
    unittest.main()
