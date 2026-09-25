# SPDX-License-Identifier: Apache-2.0
import base64
import importlib.util
import json
import unittest
from pathlib import Path

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


class SnapshotWaitTests(unittest.TestCase):
    def test_missing_head_waits_then_accepts_indexed_graph(self):
        replies = iter([response(1, 0), response(1, 0), response()])
        sleeps = []
        self.assertTrue(
            MODULE.wait_for_snapshot(lambda: next(replies), sleeps.append, (0, 60, 120))
        )
        self.assertEqual(sleeps, [60, 120])

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
        self.assertFalse(
            MODULE.wait_for_snapshot(
                lambda: response(1, 0), lambda _: None, (0,), lambda: "success"
            )
        )

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
