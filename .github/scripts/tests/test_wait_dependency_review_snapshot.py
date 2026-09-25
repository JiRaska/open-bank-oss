# SPDX-License-Identifier: Apache-2.0
import base64
import importlib.util
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


if __name__ == "__main__":
    unittest.main()
