# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
import importlib.util
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "focused_overlap_subject", Path(__file__).resolve().parents[1] / "check-pr-file-overlap.py"
)
subject = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(subject)


def node(number, paths, more=False):
    return {"id": f"PR{number}", "number": number, "title": str(number), "headRefOid": f"head{number}",
            "files": {"nodes": [{"path": p} for p in paths],
                      "pageInfo": {"hasNextPage": more, "endCursor": "files-next" if more else None}}}


class FocusedOidsTest(unittest.TestCase):
    def fixture(self, fail_identity=False):
        calls = []

        def graphql(query, variables):
            calls.append((query, dict(variables)))
            if "pullRequests" in query:
                return {"data": {"repository": {"pullRequests": {
                    "nodes": [node(1, ["shared.txt"]), node(2, ["unrelated.txt"], more=True),
                              node(3, ["foreign.txt"]), node(4, ["foreign.txt"])],
                    "pageInfo": {"hasNextPage": False, "endCursor": None},
                }}}}
            if "... on PullRequest" in query:
                self.assertEqual(variables, {"id": "PR2", "after": "files-next"})
                return {"data": {"node": {"files": {
                    "nodes": [{"path": "shared.txt"}],
                    "pageInfo": {"hasNextPage": False, "endCursor": None},
                }}}}
            if "object(expression" in query:
                if fail_identity:
                    raise RuntimeError("identity fetch failed")
                expressions = [v for k, v in variables.items() if k.startswith("expr")]
                self.assertEqual(expressions, ["head1:shared.txt", "head2:shared.txt"])
                return {"data": {"repository": {"f0": {"oid": "same"}, "f1": {"oid": "same"}}}}
            raise AssertionError("unexpected query")

        return graphql, calls

    def test_later_file_page_is_kept_and_foreign_pairs_need_no_identities(self):
        graphql, calls = self.fixture()
        prs = subject.fetch_open_prs(graphql, focus_pr=1)
        self.assertEqual([p["number"] for p in prs], [1, 2, 3, 4])
        self.assertEqual(len(calls), 3)
        self.assertEqual(prs[2]["files"]["foreign.txt"], "")
        divergent, agreeing = subject.classify(prs[0], prs)
        self.assertFalse(divergent)
        self.assertEqual([(r["pr"], r["path"]) for r in agreeing], [(2, "shared.txt")])

    def test_missing_focus_still_enumerates_all_pages(self):
        graphql, calls = self.fixture()
        prs = subject.fetch_open_prs(graphql, focus_pr=99)
        self.assertEqual(len(prs), 4)
        self.assertEqual(len(calls), 2)
        self.assertIn("shared.txt", prs[1]["files"])

    def test_identity_failure_remains_an_enumeration_failure(self):
        graphql, _ = self.fixture(fail_identity=True)
        with self.assertRaisesRegex(RuntimeError, "identity fetch failed"):
            subject.fetch_open_prs(graphql, focus_pr=1)


if __name__ == "__main__":
    unittest.main()
