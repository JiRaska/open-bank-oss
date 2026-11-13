#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""merge train closes the issues a merged PR links with `Closes #N` (#14510). No network: `api` is stubbed."""
import importlib.util
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("merge_train_close_test", Path(__file__).with_name("merge-train.py"))
train = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = train
spec.loader.exec_module(train)

REPO, SHA = "owner/repo", "b" * 40


def node(number, state="OPEN", repo=REPO):
    return {"number": number, "state": state, "repository": {"nameWithOwner": repo}}


class FakeApi:
    def __init__(self, nodes, fail=None):
        self.nodes, self.fail, self.calls = nodes, fail or {}, []

    def __call__(self, method, path, body=None):
        self.calls.append((method, path, body))
        for (m, frag), outcome in self.fail.items():
            if m == method and frag in path:
                if isinstance(outcome, Exception):
                    raise outcome
                return outcome, "denied"
        if path == "/graphql":
            return 200, {"data": {"repository": {"pullRequest": {"closingIssuesReferences": {"nodes": self.nodes}}}}}
        if method == "POST" and path.endswith("/comments"):
            return 201, {}
        if method == "PATCH":
            return 200, {}
        if method == "PUT" and path.endswith("/merge"):
            return 200, {"merged": True, "sha": SHA}
        raise AssertionError(f"unexpected {method} {path}")

    def closed(self):
        return [int(p.rsplit("/", 1)[1]) for m, p, b in self.calls
                if m == "PATCH" and b == {"state": "closed", "state_reason": "completed"}]


class CloseLinkedIssuesTest(unittest.TestCase):
    def run_close(self, fake, n=7):
        with patch.object(train, "api", fake), patch.object(train, "log"):
            return train.close_linked_issues(REPO, n, SHA)

    def test_open_same_repo_issue_is_commented_and_closed(self):
        fake = FakeApi([node(11)])
        self.assertEqual(self.run_close(fake), [11])
        self.assertEqual(fake.closed(), [11])
        comment = [b for m, p, b in fake.calls if p == f"/repos/{REPO}/issues/11/comments"]
        self.assertEqual(comment, [{"body": f"Closed by #7 ({SHA}) — merged by the merge train."}])

    def test_already_closed_and_foreign_repo_are_skipped(self):
        fake = FakeApi([node(11, "CLOSED"), node(12, repo="other/repo"), node(13)])
        self.assertEqual(self.run_close(fake), [13])
        self.assertFalse([c for c in fake.calls if "/issues/11" in c[1] or "/issues/12" in c[1]])

    def test_rerun_is_idempotent(self):
        self.assertEqual(self.run_close(FakeApi([node(11, "CLOSED")])), [])

    def test_errors_never_raise(self):
        cases = [{("POST", "/graphql"): 502}, {("POST", "/graphql"): train.ApiAbort("transient", "x")},
                 {("POST", "/comments"): 403}, {("PATCH", "/issues/"): 403},
                 {("PATCH", "/issues/11"): train.ApiAbort("transient", "x")}]
        for fail in cases:
            with self.subTest(fail=fail):
                fake = FakeApi([node(11), node(12)], fail)
                self.run_close(fake)  # must not raise
        # an error on one issue does not stop the next
        fake = FakeApi([node(11), node(12)], {("PATCH", "/issues/11"): train.ApiAbort("transient", "x")})
        self.assertEqual(self.run_close(fake), [12])

    def test_comment_refused_means_no_close(self):
        fake = FakeApi([node(11)], {("POST", "/comments"): 403})
        self.assertEqual(self.run_close(fake), [])
        self.assertEqual(fake.closed(), [])

    def test_real_merge_path_closes_linked_issue(self):
        """GitHub.merge itself must call the close — the wiring, not only the helper."""
        fake = FakeApi([node(11)])
        gh = train.GitHub(REPO, "main", "https://example.invalid/run")
        with patch.object(train, "api", fake), patch.object(train, "log"), \
                patch.object(train.GitHub, "unverified", lambda *a, **k: None), \
                patch.object(train, "live_source_intent", lambda *a: True):
            self.assertEqual(gh.merge(7, "a" * 40), SHA)
        self.assertEqual(fake.closed(), [11])

    def test_refused_merge_closes_nothing(self):
        fake = FakeApi([node(11)], {("PUT", "/merge"): 405})
        gh = train.GitHub(REPO, "main", "https://example.invalid/run")
        with patch.object(train, "api", fake), patch.object(train, "log"), \
                patch.object(train.GitHub, "unverified", lambda *a, **k: None), \
                patch.object(train, "live_source_intent", lambda *a: True):
            self.assertIsNone(gh.merge(7, "a" * 40))
        self.assertEqual(fake.closed(), [])
        self.assertFalse([c for c in fake.calls if c[1] == "/graphql"])


if __name__ == "__main__":
    unittest.main()
