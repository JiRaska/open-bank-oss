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
            if "node(id:$id0)" in query:
                self.assertEqual(variables, {"id0": "PR2", "after0": "files-next"})
                return {"data": {"p0": {"id": "PR2", "headRefOid": "head2", "files": {
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

    def test_file_pages_batch_distinct_prs_and_repeat_rounds_with_head_pinning(self):
        calls = []
        initial_nodes = [
            {"id": f"PR{number}", "number": number, "title": str(number),
             "headRefOid": f"head{number}", "files": {
                 "nodes": [{"path": f"initial/{number}.txt"}],
                 "pageInfo": {"hasNextPage": True, "endCursor": f"cursor-{number}-1"},
             }}
            for number in range(1, 12)
        ]

        def graphql(query, variables):
            calls.append((query, dict(variables)))
            if "pullRequests" in query:
                return {"data": {"repository": {"pullRequests": {
                    "nodes": initial_nodes,
                    "pageInfo": {"hasNextPage": False, "endCursor": None},
                }}}}
            if "node(id:$id0)" in query:
                data = {}
                for index in range(len(variables) // 2):
                    number = int(variables[f"id{index}"].removeprefix("PR"))
                    cursor = variables[f"after{index}"]
                    if number == 1 and cursor == "cursor-1-1":
                        data[f"p{index}"] = {"id": f"PR{number}", "headRefOid": "head1", "files": {
                            "nodes": [{"path": "continued/1/second.txt"}],
                            "pageInfo": {"hasNextPage": True, "endCursor": "cursor-1-2"},
                        }}
                    elif number == 1 and cursor == "cursor-1-2":
                        data[f"p{index}"] = {"id": f"PR{number}", "headRefOid": "head1", "files": {
                            "nodes": [{"path": "continued/1/third.txt"}],
                            "pageInfo": {"hasNextPage": False, "endCursor": None},
                        }}
                    else:
                        data[f"p{index}"] = {"id": f"PR{number}", "headRefOid": f"head{number}", "files": {
                            "nodes": [{"path": f"continued/{number}/second.txt"}],
                            "pageInfo": {"hasNextPage": False, "endCursor": None},
                        }}
                return {"data": data}
            raise AssertionError("unexpected query")

        prs = subject.fetch_open_prs(graphql, focus_pr=1)

        self.assertEqual([pr["number"] for pr in prs], list(range(1, 12)))
        self.assertEqual(len(calls), 4)  # inventory + batches(10, 1) + second page for PR 1
        file_calls = [call for call in calls if "node(id:$id0)" in call[0]]
        self.assertEqual([len(call[1]) // 2 for call in file_calls], [10, 1, 1])
        self.assertEqual(file_calls[0][1]["after0"], "cursor-1-1")
        self.assertEqual(file_calls[1][1]["after0"], "cursor-11-1")
        self.assertEqual(file_calls[2][1], {"id0": "PR1", "after0": "cursor-1-2"})
        self.assertEqual(len(prs[0]["files"]), 3)
        self.assertTrue(all(len(pr["files"]) == 2 for pr in prs[1:]))

    def test_missing_alias_partial_errors_and_changed_head_fail_closed(self):
        def inventory():
            return {"data": {"repository": {"pullRequests": {
                "nodes": [{"id": "PR1", "number": 1, "title": "one", "headRefOid": "head1",
                           "files": {"nodes": [], "pageInfo": {
                               "hasNextPage": True, "endCursor": "cursor-1"}}}],
                "pageInfo": {"hasNextPage": False, "endCursor": None},
            }}}}

        scenarios = (
            ("missing alias", {"data": {}}, "omitted alias"),
            ("partial GraphQL errors", {"data": {"p0": {"id": "PR1", "headRefOid": "head1", "files": {
                "nodes": [{"path": "would-be-hidden.txt"}],
                "pageInfo": {"hasNextPage": False, "endCursor": None},
            }}}, "errors": [{"message": "partial response"}]}, "partial errors"),
            ("wrong node identity", {"data": {"p0": {"id": "PR2", "headRefOid": "head1", "files": {
                "nodes": [], "pageInfo": {"hasNextPage": False, "endCursor": None},
            }}}}, "wrong node"),
            ("changed head", {"data": {"p0": {"id": "PR1", "headRefOid": "new-head", "files": {
                "nodes": [], "pageInfo": {"hasNextPage": False, "endCursor": None},
            }}}}, "head changed"),
        )
        for label, continuation, message in scenarios:
            with self.subTest(label=label):
                def graphql(query, variables, continuation=continuation):
                    return inventory() if "pullRequests" in query else continuation

                with self.assertRaisesRegex(RuntimeError, message):
                    subject.fetch_open_prs(graphql, focus_pr=1)

    def test_repeated_file_cursor_fails_closed(self):
        def graphql(query, variables):
            if "pullRequests" in query:
                return {"data": {"repository": {"pullRequests": {
                    "nodes": [{"id": "PR1", "number": 1, "title": "one", "headRefOid": "head1",
                               "files": {"nodes": [], "pageInfo": {
                                   "hasNextPage": True, "endCursor": "cursor-1"}}}],
                    "pageInfo": {"hasNextPage": False, "endCursor": None},
                }}}}
            return {"data": {"p0": {"id": "PR1", "headRefOid": "head1", "files": {
                "nodes": [], "pageInfo": {"hasNextPage": True, "endCursor": "cursor-1"},
            }}}}

        with self.assertRaisesRegex(RuntimeError, "repeated cursor"):
            subject.fetch_open_prs(graphql, focus_pr=1)

    def test_repeated_pr_cursor_cycle_fails_closed(self):
        cursors = iter(("cursor-a", "cursor-b", "cursor-a"))

        def graphql(query, variables):
            cursor = next(cursors)
            return {"data": {"repository": {"pullRequests": {
                "nodes": [],
                "pageInfo": {"hasNextPage": True, "endCursor": cursor},
            }}}}

        with self.assertRaisesRegex(RuntimeError, "repeated or omitted its cursor"):
            subject.fetch_open_prs(graphql, focus_pr=1)

    def test_missing_or_nonpositive_noninteger_pr_number_fails_closed(self):
        invalid_nodes = []
        for number in (None, 0, True):
            invalid_nodes.append({"id": "PR1", "number": number, "title": "one",
                                  "headRefOid": "head1", "files": {
                                      "nodes": [], "pageInfo": {
                                          "hasNextPage": False, "endCursor": None}}})
        missing = dict(invalid_nodes[0])
        missing.pop("number")
        invalid_nodes.append(missing)

        for node in invalid_nodes:
            with self.subTest(number=node.get("number", "missing")):
                def graphql(query, variables, node=node):
                    return {"data": {"repository": {"pullRequests": {
                        "nodes": [node],
                        "pageInfo": {"hasNextPage": False, "endCursor": None},
                    }}}}

                with self.assertRaisesRegex(RuntimeError, "invalid PR number"):
                    subject.fetch_open_prs(graphql, focus_pr=1)


if __name__ == "__main__":
    unittest.main()
