#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Offline adversarial regressions for merge executor and independent authorization."""
import contextlib
import copy
import importlib.util
import io
import http.server
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
import urllib.request

sys.path.insert(0, str(Path(__file__).resolve().parent))
import merge_authorization_proof as proof

spec = importlib.util.spec_from_file_location("merge_recorder", Path(__file__).with_name("record-agent-merge.py"))
recorder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recorder)
REPO = "example/bank"
OWNER = {"login": "example", "type": "User", "id": 101}
BOT = {"login": proof.BOT, "type": "Bot", "id": 202}
HEAD, MERGE = "a" * 40, "b" * 40
URL = "https://github.com/example/bank/actions/runs/42"


def fixtures():
    pull = {"number": 7, "merged": True, "state": "closed", "merged_at": "2026-10-24T12:00:00Z",
            "merge_commit_sha": MERGE, "merged_by": copy.deepcopy(BOT),
            "html_url": "https://github.com/example/bank/pull/7", "title": "Fix audit provenance",
            "head": {"sha": HEAD, "ref": "codex/audit", "repo": {"full_name": REPO}},
            "base": {"ref": "main", "repo": {"full_name": REPO}}, "user": {"login": "example"}}
    status = {"id": 10, "context": proof.CONTEXT, "creator": copy.deepcopy(BOT), "state": "success",
              "description": "owner-approved PR #7", "created_at": "2026-10-24T11:59:30Z", "target_url": URL}
    run = {"id": 42, "path": proof.WORKFLOW, "event": "workflow_dispatch", "head_branch": "main",
           "run_attempt": 1, "status": "completed", "conclusion": "success", "html_url": URL,
           "repository": {"full_name": REPO}, "actor": copy.deepcopy(OWNER),
           "triggering_actor": copy.deepcopy(OWNER), "created_at": "2026-10-24T11:59:00Z",
           "updated_at": "2026-10-24T11:59:45Z"}
    job = {"name": "decide", "status": "completed", "conclusion": "success",
           "started_at": "2026-10-24T11:59:10Z", "completed_at": "2026-10-24T11:59:40Z"}
    return pull, status, run, job


class FakeGitHub:
    def __init__(self):
        self.pull, status, self.run, job = fixtures()
        self.metadata = {"full_name": REPO, "owner": copy.deepcopy(OWNER)}
        self.statuses, self.jobs = [status], [job]
        self.graph_pages = []
        self.calls = []

    def get(self, path):
        self.calls.append(path)
        if path.startswith(f"/repos/{REPO}/pulls/"):
            return copy.deepcopy(self.pull)
        if path == f"/repos/{REPO}":
            return copy.deepcopy(self.metadata)
        if path == f"/repos/{REPO}/actions/runs/42/attempts/1":
            return copy.deepcopy(self.run)
        raise AssertionError("unexpected GitHub read: " + path)

    def pages(self, path, key=None):
        self.calls.append(path)
        if path == f"/repos/{REPO}/commits/{HEAD}/statuses":
            return copy.deepcopy(self.statuses)
        if path == f"/repos/{REPO}/actions/runs/42/attempts/1/jobs" and key == "jobs":
            return copy.deepcopy(self.jobs)
        raise AssertionError("unexpected GitHub page: " + path)

    def graphql(self, query, variables):
        if not self.graph_pages:
            raise AssertionError("unexpected extra GraphQL page")
        return copy.deepcopy(self.graph_pages.pop(0))


def legacy_page(nodes=None, next_page=False, cursor=None):
    return {"repository": {"pullRequest": {
        "id": "PR_node_7", "autoMergeRequest": {"enabledAt": "2026-10-23T19:40:00Z",
            "enabledBy": {"__typename": "User", "login": "example", "databaseId": 101}},
        "timelineItems": {"nodes": nodes if nodes is not None else [{
            "__typename": "MergedEvent", "id": "MERGE_node_7", "createdAt": "2026-10-23T19:42:16Z",
            "commit": {"oid": MERGE}, "actor": {"__typename": "Bot", "login": proof.BOT, "databaseId": 202}}],
            "pageInfo": {"hasNextPage": next_page, "endCursor": cursor}}}}}


class ExactHeadAuthorizationTests(unittest.TestCase):
    def setUp(self):
        self.github = FakeGitHub()
        self.pull = copy.deepcopy(self.github.pull)

    def authorize(self):
        return proof.app_authorization(self.pull, REPO, self.github)

    def reject(self):
        with self.assertRaises(ValueError):
            self.authorize()

    def test_app_executor_and_human_owner_remain_distinct(self):
        result = self.authorize()
        self.assertEqual(result["mergedByType"], "GITHUB_APP")
        self.assertEqual(result["mergedById"], BOT["id"])
        self.assertEqual(result["headSha"], HEAD)
        self.assertEqual(result["mergeCommitSha"], MERGE)
        self.assertEqual(result["authorization"], {
            "kind": "OWNER_WORKFLOW_EXACT_HEAD", "scope": "HEAD", "approvedBy": "example",
            "approvedById": 101, "approvedAt": "2026-10-24T11:59:30Z", "evidenceUrl": URL,
            "statusId": 10, "workflowRunId": 42})

    def test_latest_premerge_decision_wins_even_with_prior_success(self):
        for state in ("failure", "pending", "error"):
            with self.subTest(state=state):
                later = dict(self.github.statuses[0], id=11, state=state, created_at="2026-10-24T11:59:31Z")
                self.github.statuses = [fixtures()[1], later]
                self.reject()

    def test_same_second_latest_status_id_wins(self):
        self.github.statuses.append(dict(self.github.statuses[0], id=11, state="failure"))
        self.reject()

    def test_postmerge_approval_does_not_repair_missing_premerge_authorization(self):
        self.github.statuses[0]["created_at"] = "2026-10-24T12:00:01Z"
        self.reject()

    def test_foreign_status_creator_cannot_authorize(self):
        for actor in (dict(BOT, id=999), dict(BOT, type="User"), dict(BOT, login="other[bot]")):
            with self.subTest(actor=actor):
                self.github.statuses[0]["creator"] = actor
                self.reject()

    def test_wrong_pr_description_rejected(self):
        self.github.statuses[0]["description"] = "owner-approved PR #8"
        self.reject()

    def test_noncanonical_evidence_urls_rejected(self):
        for url in (URL + "?attempt=1", URL + "/attempts/1", URL.replace("github.com", "evil.example"),
                    URL.replace("https:", "http:"), URL.replace("/42", "/042")):
            with self.subTest(url=url):
                self.github.statuses[0]["target_url"] = url
                self.reject()

    def test_event_must_match_canonical_pr(self):
        for field, value in (("number", 8), ("merge_commit_sha", "c" * 40),
                             ("merged_at", "2026-10-24T12:00:01Z"), ("html_url", "https://evil.example"),
                             ("merged_by", dict(BOT, id=999))):
            with self.subTest(field=field):
                self.pull = copy.deepcopy(self.github.pull)
                self.pull[field] = value
                self.reject()
        self.pull = copy.deepcopy(self.github.pull)
        self.pull["head"]["sha"] = "c" * 40
        self.reject()

    def test_canonical_merge_constraints(self):
        mutations = [lambda p: p.update(merged=False), lambda p: p.update(state="open"),
                     lambda p: p["head"].update(sha="invalid"),
                     lambda p: p["head"]["repo"].update(full_name="fork/bank"),
                     lambda p: p["base"]["repo"].update(full_name="other/bank"),
                     lambda p: p["base"].update(ref="release"),
                     lambda p: p.update(html_url="https://github.com/other/bank/pull/7")]
        for change in mutations:
            with self.subTest(change=change):
                self.github = FakeGitHub()
                change(self.github.pull)
                self.pull = copy.deepcopy(self.github.pull)
                self.reject()

    def test_repository_owner_must_be_human_and_match_repository(self):
        for mutation in (dict(OWNER, type="Organization"), dict(OWNER, id=0), dict(OWNER, login="other")):
            with self.subTest(owner=mutation):
                self.github.metadata["owner"] = mutation
                self.reject()

    def test_workflow_configuration_and_execution_are_verified(self):
        fields = {"id": 43, "path": ".github/workflows/untrusted.yml", "event": "pull_request",
                  "head_branch": "codex/audit", "run_attempt": 2, "status": "in_progress",
                  "conclusion": "skipped", "html_url": URL + "?x=1", "repository": {"full_name": "fork/bank"}}
        for field, value in fields.items():
            with self.subTest(field=field):
                self.github.run = fixtures()[2]
                self.github.run[field] = value
                self.reject()

    def test_owner_and_triggering_actor_must_both_be_canonical_human(self):
        for role in ("actor", "triggering_actor"):
            for actor in (dict(OWNER, id=999), dict(OWNER, type="Bot"), dict(OWNER, login="other")):
                with self.subTest(role=role, actor=actor):
                    self.github.run = fixtures()[2]
                    self.github.run[role] = actor
                    self.reject()

    def test_workflow_and_status_must_complete_before_merge(self):
        for field, value in (("created_at", "2026-10-24T11:59:31Z"),
                             ("updated_at", "2026-10-24T11:59:29Z"),
                             ("updated_at", "2026-10-24T12:00:01Z")):
            with self.subTest(field=field, value=value):
                self.github.run = fixtures()[2]
                self.github.run[field] = value
                self.reject()

    def test_writer_job_missing_ambiguous_skipped_or_outside_window(self):
        job = fixtures()[3]
        invalid = [[], [job, job], [dict(job, name="verify")], [dict(job, conclusion="skipped")],
                   [dict(job, status="in_progress")], [dict(job, started_at="2026-10-24T11:59:31Z")],
                   [dict(job, completed_at="2026-10-24T11:59:29Z")],
                   [dict(job, completed_at="2026-10-24T12:00:01Z")]]
        for jobs in invalid:
            with self.subTest(jobs=jobs):
                self.github.jobs = jobs
                self.reject()

    def test_malformed_writer_job_is_rejected(self):
        self.github.jobs = [None]
        self.reject()

    def test_api_failure_does_not_authorize(self):
        with patch.object(self.github, "get", side_effect=ValueError("unavailable")):
            self.reject()


class HistoricalAuthorizationTests(unittest.TestCase):
    def setUp(self):
        self.github = FakeGitHub()
        self.github.statuses = []
        self.github.pull["merged_at"] = proof.LEGACY_CUTOFF
        self.pull = copy.deepcopy(self.github.pull)
        self.page = legacy_page()
        self.github.graph_pages = [self.page]

    def authorize(self):
        return proof.app_authorization(self.pull, REPO, self.github)

    def reject(self):
        with self.assertRaises(ValueError):
            self.authorize()

    def test_observed_historical_intent_remains_pr_scoped_and_preserves_timestamps(self):
        result = self.authorize()["authorization"]
        self.assertEqual(result["kind"], "LEGACY_PR_AUTO_MERGE")
        self.assertEqual(result["scope"], "PR")
        self.assertNotIn("headSha", result)
        self.assertEqual(result["approvedAt"], "2026-10-23T19:40:00Z")
        self.assertEqual(result["mergeEventAt"], "2026-10-23T19:42:16Z")
        self.assertNotEqual(result["mergeEventAt"], self.pull["merged_at"])
        self.assertEqual(result["mergeEventNodeId"], "MERGE_node_7")
        self.assertGreaterEqual(proof.instant(result["observedAt"]), proof.instant(self.pull["merged_at"]))

    def test_graphql_bot_slug_without_rest_suffix_is_same_verified_executor(self):
        actor = self.page["repository"]["pullRequest"]["timelineItems"]["nodes"][0]["actor"]
        actor["login"] = proof.BOT.removesuffix("[bot]")
        result = self.authorize()
        self.assertEqual(result["mergedById"], BOT["id"])
        self.assertEqual(result["authorization"]["scope"], "PR")

    def test_graphql_slug_normalization_does_not_admit_foreign_id_or_bot(self):
        for actor in ({"__typename": "Bot", "login": proof.BOT.removesuffix("[bot]"), "databaseId": 999},
                      {"__typename": "Bot", "login": "foreign-gitops-bot", "databaseId": 202},
                      {"__typename": "User", "login": proof.BOT.removesuffix("[bot]"), "databaseId": 202}):
            with self.subTest(actor=actor):
                page = legacy_page()
                page["repository"]["pullRequest"]["timelineItems"]["nodes"][0]["actor"] = actor
                self.github.graph_pages = [page]
                self.reject()

    def test_legacy_cutoff_is_inclusive_and_no_later_merge_is_reconstructed(self):
        self.github.pull["merged_at"] = "2026-10-23T19:42:18Z"
        self.pull = copy.deepcopy(self.github.pull)
        self.reject()

    def test_missing_late_or_foreign_human_request_rejected(self):
        for request in (None, {"enabledAt": "2026-10-23T19:42:18Z", "enabledBy": {"__typename": "User", "login": "example", "databaseId": 101}},
                        {"enabledAt": "2026-10-23T19:40:00Z", "enabledBy": {"__typename": "Bot", "login": "example", "databaseId": 101}},
                        {"enabledAt": "2026-10-23T19:40:00Z", "enabledBy": {"__typename": "User", "login": "example", "databaseId": 999}}):
            with self.subTest(request=request):
                page = legacy_page()
                page["repository"]["pullRequest"]["autoMergeRequest"] = request
                self.github.graph_pages = [page]
                self.reject()

    def test_disabled_after_enable_request_is_not_authorization(self):
        self.page["repository"]["pullRequest"]["timelineItems"]["nodes"].append({
            "__typename": "AutoMergeDisabledEvent", "createdAt": "2026-10-23T19:41:00Z"})
        self.reject()

    def test_historical_merge_must_match_commit_executor_and_unique_event(self):
        for field, value in (("commit", {"oid": "c" * 40}),
                             ("actor", {"__typename": "Bot", "login": proof.BOT, "databaseId": 999}),
                             ("actor", {"__typename": "User", "login": proof.BOT, "databaseId": 202})):
            with self.subTest(field=field, value=value):
                page = legacy_page()
                page["repository"]["pullRequest"]["timelineItems"]["nodes"][0][field] = value
                self.github.graph_pages = [page]
                self.reject()
        for nodes in ([], legacy_page()["repository"]["pullRequest"]["timelineItems"]["nodes"] * 2):
            self.github.graph_pages = [legacy_page(nodes)]
            self.reject()

    def test_unrelated_or_future_merged_event_time_is_rejected(self):
        for timestamp in ("2026-10-23T19:43:18Z", "2099-01-01T00:00:00Z"):
            with self.subTest(timestamp=timestamp):
                page = legacy_page()
                page["repository"]["pullRequest"]["timelineItems"]["nodes"][0]["createdAt"] = timestamp
                self.github.graph_pages = [page]
                self.reject()

    def test_changing_paginated_request_rejected(self):
        first = legacy_page([], True, "cursor1")
        second = legacy_page()
        second["repository"]["pullRequest"]["autoMergeRequest"]["enabledAt"] = "2026-10-23T19:39:00Z"
        self.github.graph_pages = [first, second]
        self.reject()

    def test_incomplete_or_repeating_timeline_cursor_rejected(self):
        for pages in ([legacy_page([], True, None)], [legacy_page([], True, "cursor1"), legacy_page([], True, "cursor1")],
                      [{"repository": {"pullRequest": {"id": "PR_node_7", "timelineItems": {"nodes": []}}}}]):
            with self.subTest(pages=pages):
                self.github.graph_pages = pages
                self.reject()


class BackfillDispatchAuthorizationTests(unittest.TestCase):
    def setUp(self):
        self.github = FakeGitHub()
        self.env = {"GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_REF": "refs/heads/main",
                    "GITHUB_RUN_ATTEMPT": "1", "GITHUB_ACTOR": OWNER["login"],
                    "GITHUB_TRIGGERING_ACTOR": OWNER["login"], "GITHUB_ACTOR_ID": str(OWNER["id"])}

    def test_first_main_dispatch_by_live_owner_is_authorized(self):
        proof.authorize_backfill(REPO, self.github, self.env)

    def test_wrong_actor_identity_trigger_ref_event_or_attempt_is_rejected(self):
        for key, value in (("GITHUB_ACTOR_ID", "999"), ("GITHUB_ACTOR", "foreign"),
                           ("GITHUB_TRIGGERING_ACTOR", "foreign"), ("GITHUB_REF", "refs/heads/codex/audit"),
                           ("GITHUB_EVENT_NAME", "pull_request"), ("GITHUB_RUN_ATTEMPT", "2")):
            with self.subTest(key=key), self.assertRaises(ValueError):
                proof.authorize_backfill(REPO, self.github, dict(self.env, **{key: value}))

    def test_live_repository_owner_must_be_matching_user(self):
        for owner in (dict(OWNER, type="Bot"), dict(OWNER, type="Organization"), dict(OWNER, login="foreign"),
                      dict(OWNER, id=999), dict(OWNER, id=0)):
            with self.subTest(owner=owner):
                self.github.metadata["owner"] = owner
                with self.assertRaises(ValueError):
                    proof.authorize_backfill(REPO, self.github, self.env)
        self.github.metadata = {"full_name": "foreign/bank", "owner": OWNER}
        with self.assertRaises(ValueError):
            proof.authorize_backfill(REPO, self.github, self.env)


class EvidenceTransportTests(unittest.TestCase):
    def test_cross_host_redirect_strips_authorization(self):
        request = urllib.request.Request("https://api.github.com/repos/example/bank", headers={"Authorization": "Bearer secret"})
        target = proof.SafeRedirect().redirect_request(request, None, 302, "Found", {}, "https://foreign.example/resource")
        self.assertIsNone(target.get_header("Authorization"))

    def test_same_github_host_redirect_retains_authorization(self):
        request = urllib.request.Request("https://api.github.com/repos/example/bank", headers={"Authorization": "Bearer secret"})
        target = proof.SafeRedirect().redirect_request(request, None, 302, "Found", {}, "https://api.github.com/repos/example/renamed")
        self.assertEqual(target.get_header("Authorization"), "Bearer secret")

    def test_non_https_redirect_is_rejected(self):
        request = urllib.request.Request("https://api.github.com/repos/example/bank")
        with self.assertRaises(ValueError):
            proof.SafeRedirect().redirect_request(request, None, 302, "Found", {}, "http://api.github.com/repos/example/bank")

    def test_malformed_rest_pagination_rejected(self):
        github = proof.GitHub()
        for value, key in (({"statuses": []}, None), ({"jobs": "bad"}, "jobs"), (None, None)):
            with self.subTest(value=value), patch.object(github, "get", return_value=value):
                with self.assertRaises(ValueError):
                    github.pages("/repos/example/bank/statuses", key)

    def test_rest_pagination_collects_all_pages_and_is_bounded(self):
        github = proof.GitHub()
        with patch.object(github, "get", side_effect=[[{}] * 100, [{"last": True}]]) as get:
            self.assertEqual(len(github.pages("/repos/example/bank/statuses")), 101)
            self.assertIn("page=2", get.call_args.args[0])
        with patch.object(github, "get", return_value=[{}] * 100) as get:
            with self.assertRaises(ValueError):
                github.pages("/repos/example/bank/statuses")
            self.assertEqual(get.call_count, 100)

    def test_graphql_partial_errors_are_not_evidence(self):
        github = proof.GitHub()
        for value in ({"data": {}, "errors": [{"message": "partial failure"}]}, {"data": None}, []):
            with self.subTest(value=value), patch.object(github, "request", return_value=value):
                with self.assertRaises(ValueError):
                    github.graphql("query {}", {})


@contextlib.contextmanager
def local_backend(redirect_to=None, redirect_code=302):
    """A real HTTP endpoint; request logs contain synthetic credentials only."""
    received = []

    class Handler(http.server.BaseHTTPRequestHandler):
        def handle_request(self):
            received.append((self.command, self.path, self.headers.get("Authorization")))
            if self.headers.get("Content-Length"):
                self.rfile.read(int(self.headers["Content-Length"]))
            if redirect_to:
                self.send_response(redirect_code)
                self.send_header("Location", redirect_to)
                self.send_header("Content-Length", "0")
                self.end_headers()
            else:
                body = b'{"evidenceSchemaVersion":2}'
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        do_GET = handle_request
        do_POST = handle_request

        def log_message(self, *_args):
            pass

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=lambda: server.serve_forever(poll_interval=0.01), daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}", received
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


class BackendCredentialIsolationTests(unittest.TestCase):
    def test_app_and_legacy_human_use_distinct_ingest_operations(self):
        for actor, suffix in (("HUMAN", ""), ("GITHUB_APP", "/app")):
            with self.subTest(actor=actor), local_backend() as (source, received):
                self.assertIsNone(recorder.post_record(source, "synthetic-token", {"mergedByType": actor}))
                self.assertEqual(received, [("POST", recorder.ENDPOINT_PATH + suffix, "Bearer synthetic-token")])

    def test_real_capability_get_refuses_cross_origin_redirect_without_leaking_credentials(self):
        for code in (301, 302, 303, 307, 308):
            with self.subTest(code=code), local_backend() as (sink, leaked):
                with local_backend(sink + "/capture", code) as (source, received):
                    with self.assertRaises(ValueError):
                        recorder.read_backend(source, "synthetic-recorder-token", "/capabilities")
                    self.assertEqual(received, [("GET", recorder.ENDPOINT_PATH + "/capabilities",
                                                 "Bearer synthetic-recorder-token")])
                    self.assertEqual(leaked, [], "redirect destination must receive no request or credential")

    def test_real_record_post_refuses_cross_origin_redirect_without_leaking_credentials(self):
        for code in (301, 302, 303, 307, 308):
            with self.subTest(code=code), local_backend() as (sink, leaked):
                with local_backend(sink + "/capture", code) as (source, received):
                    failure = recorder.post_record(source, "synthetic-recorder-token", {"prNumber": 7})
                    self.assertIsNotNone(failure)
                    self.assertIn(f"HTTP {code}", failure)
                    self.assertEqual(received, [("POST", recorder.ENDPOINT_PATH,
                                                 "Bearer synthetic-recorder-token")])
                    self.assertEqual(leaked, [], "redirect destination must receive no request or credential")

    def test_redirect_handler_rejects_same_origin_and_https_downgrade(self):
        request = urllib.request.Request("https://service.example/api/v1/agent-merge-records",
                                         headers={"Authorization": "Bearer synthetic-recorder-token"})
        for target in ("https://service.example/renamed", "http://service.example/downgrade",
                       "https://foreign.example/capture"):
            with self.subTest(target=target), self.assertRaises(urllib.error.HTTPError) as caught:
                recorder.BackendNoRedirect().redirect_request(request, None, 302, "Found", {}, target)
            caught.exception.close()


class CommittedEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.github = FakeGitHub()
        self.pull = copy.deepcopy(self.github.pull)
        self.payload = recorder.build_payload(self.pull, "external-coding-agent")
        self.payload.update(proof.app_authorization(self.pull, REPO, self.github))

    def test_exact_head_app_record_read_back_preserves_independent_human(self):
        recorder.verify_stored(self.payload, copy.deepcopy(self.payload))
        self.assertEqual(self.payload["mergedBy"], proof.BOT)
        self.assertEqual(self.payload["authorization"]["approvedBy"], "example")

    def test_human_record_requires_actual_human_type(self):
        payload = recorder.build_payload(dict(self.pull, merged_by=OWNER), "external-coding-agent")
        recorder.verify_stored(payload, dict(payload, mergedByType="HUMAN"))
        for kind in (None, "GITHUB_APP"):
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                recorder.verify_stored(payload, dict(payload, mergedByType=kind))

    def test_missing_or_changed_stored_identity_and_proof_rejected(self):
        for key, value in (("mergedBy", "example"), ("mergedById", 999), ("headSha", "c" * 40),
                           ("mergeCommitSha", "c" * 40), ("authorization", None), ("prNumber", 8)):
            with self.subTest(key=key), self.assertRaises(ValueError):
                recorder.verify_stored(self.payload, dict(self.payload, **{key: value}))
        for key, value in (("approvedBy", proof.BOT), ("approvedById", 999), ("scope", "PR"),
                           ("workflowRunId", 999), ("statusId", 999), ("evidenceUrl", URL + "?bad=1")):
            stored = copy.deepcopy(self.payload)
            stored["authorization"][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                recorder.verify_stored(self.payload, stored)

    def test_legacy_retry_accepts_original_observation_not_new_timestamp(self):
        github = FakeGitHub()
        github.statuses = []
        github.pull["merged_at"] = proof.LEGACY_CUTOFF
        github.graph_pages = [legacy_page()]
        payload = recorder.build_payload(github.pull, "external-coding-agent")
        payload.update(proof.app_authorization(github.pull, REPO, github))
        stored = copy.deepcopy(payload)
        stored["authorization"]["observedAt"] = "2026-10-24T01:00:00Z"
        recorder.verify_stored(payload, stored)
        stored["authorization"]["observedAt"] = "2026-10-23T19:42:16Z"
        with self.assertRaises(ValueError):
            recorder.verify_stored(payload, stored)

    def test_future_stored_observation_cannot_verify_as_historical_evidence(self):
        github = FakeGitHub()
        github.statuses = []
        github.pull["merged_at"] = proof.LEGACY_CUTOFF
        github.graph_pages = [legacy_page()]
        payload = recorder.build_payload(github.pull, "external-coding-agent")
        payload.update(proof.app_authorization(github.pull, REPO, github))
        stored = copy.deepcopy(payload)
        stored["authorization"]["observedAt"] = "9999-01-01T00:00:00Z"
        with self.assertRaises(ValueError):
            recorder.verify_stored(payload, stored)

    def run_main(self, backend, post):
        with tempfile.TemporaryDirectory() as directory:
            event = Path(directory) / "event.json"
            event.write_text(json.dumps({"pull_request": self.pull}))
            with patch.dict("os.environ", {"GITHUB_REPOSITORY": REPO, "AGENT_SERVICE_URL": "https://service.example",
                                           "AGENT_MERGE_RECORDER_TOKEN": "test-token"}), \
                    patch.object(recorder, "GitHub", return_value=self.github), \
                    patch.object(recorder, "load_agent_accounts", return_value=set()), \
                    patch.object(recorder, "load_agent_ids", return_value=["test-agent"]), \
                    patch.object(recorder, "read_backend", side_effect=backend), \
                    patch.object(recorder, "post_record", post), \
                    contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                return recorder.main(["--event", str(event), "--verify-record"])

    def test_old_backend_capability_prevents_any_write(self):
        from unittest.mock import Mock
        post = Mock(return_value=None)
        self.assertEqual(self.run_main([{"evidenceSchemaVersion": 1}], post), 1)
        post.assert_not_called()

    def test_noninteger_capability_version_prevents_any_write(self):
        from unittest.mock import Mock
        for version in (2.0, "2", True, None):
            with self.subTest(version=version):
                post = Mock(return_value=None)
                self.assertEqual(self.run_main([{"evidenceSchemaVersion": version}], post), 1)
                post.assert_not_called()

    def test_wrong_readback_after_successful_write_fails(self):
        from unittest.mock import Mock
        post = Mock(return_value=None)
        stored = copy.deepcopy(self.payload)
        stored["headSha"] = "c" * 40
        self.assertEqual(self.run_main([{"evidenceSchemaVersion": 2}, stored], post), 1)
        post.assert_called_once()

    def test_app_write_and_readback_succeeds_with_real_bot_and_separate_owner(self):
        from unittest.mock import Mock
        post = Mock(return_value=None)
        self.assertEqual(self.run_main([{"evidenceSchemaVersion": 2}, copy.deepcopy(self.payload)], post), 0)
        submitted = post.call_args.args[2]
        self.assertEqual(submitted["mergedBy"], proof.BOT)
        self.assertEqual(submitted["mergedByType"], "GITHUB_APP")
        self.assertEqual(submitted["authorization"]["approvedBy"], "example")
        self.assertEqual(submitted["headSha"], HEAD)


if __name__ == "__main__":
    # Report the actual discovered case count; removing tests lowers the gate's evidence.
    suite = unittest.defaultTestLoader.loadTestsFromModule(sys.modules["__main__"])
    print(f"SUBJECTS={suite.countTestCases()}")
    unittest.main()
