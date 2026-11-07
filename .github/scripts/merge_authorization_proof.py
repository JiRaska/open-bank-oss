#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Verify GitHub merge execution and independent human authorization; never infer a human from a bot."""
import datetime as dt
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request

from merge_pr_policy import premerge_policy_context

API = "https://api.github.com"
BOT = "openbank-gitops-bot[bot]"
CONTEXT = "merge-train/owner-approval"
WORKFLOW = ".github/workflows/merge-train-owner-approval.yml"
SHA = re.compile(r"[0-9a-f]{40}")
# This bounds reconstruction, not a retrospective compliance exemption. Older PR-scoped
# auto-merge requests must never be presented as approval of their final head SHA.
LEGACY_CUTOFF = "2026-10-23T19:42:17Z"


def instant(value):
    if not isinstance(value, str):
        raise ValueError("missing timestamp")
    try:
        result = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError("invalid timestamp") from error
    if result.tzinfo is None:
        raise ValueError("timestamp must carry a timezone")
    return result


def positive(value):
    return type(value) is int and value > 0


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urllib.parse.urlsplit(newurl).scheme != "https":
            raise ValueError("GitHub redirected to a non-HTTPS endpoint")
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if urllib.parse.urlsplit(newurl).netloc != "api.github.com":
            redirected.remove_header("Authorization")
        return redirected


class GitHub:
    def request(self, path, body=None):
        if not path.startswith("/repos/") and path != "/graphql":
            raise ValueError("unexpected GitHub API path")
        token = os.environ.get("GH_TOKEN", "")
        if not token:
            raise ValueError("GH_TOKEN required to verify GitHub authorization")
        request = urllib.request.Request(API + path, data=json.dumps(body).encode() if body else None, headers={
            "Accept": "application/vnd.github+json", "Authorization": "Bearer " + token,
            "X-GitHub-Api-Version": "2022-11-28",
            "Content-Type": "application/json",
        })
        try:
            with urllib.request.build_opener(SafeRedirect()).open(request, timeout=30) as response:
                raw = response.read(5_000_001)
            if len(raw) > 5_000_000:
                raise ValueError("GitHub evidence response exceeds bounded size")
            return json.loads(raw)
        except urllib.error.HTTPError as error:
            error.close()
            raise ValueError("GitHub evidence API unavailable") from None
        except (OSError, ValueError) as error:
            raise ValueError("GitHub evidence API unavailable or malformed") from error

    def get(self, path):
        return self.request(path)

    def graphql(self, query, variables):
        value = self.request("/graphql", {"query": query, "variables": variables})
        if not isinstance(value, dict) or value.get("errors") or not isinstance(value.get("data"), dict):
            raise ValueError("incomplete GitHub GraphQL evidence")
        return value["data"]

    def pages(self, path, key=None):
        result = []
        for page in range(1, 101):
            sep = "&" if "?" in path else "?"
            value = self.get(f"{path}{sep}per_page=100&page={page}")
            items = value.get(key) if key and isinstance(value, dict) else value
            if not isinstance(items, list):
                raise ValueError("incomplete GitHub evidence page")
            result.extend(items)
            if len(items) < 100:
                return result
        raise ValueError("GitHub evidence pagination limit reached")


def identity(value, login, kind, account_id=None):
    return (isinstance(value, dict) and value.get("login") == login
            and value.get("type") == kind and positive(value.get("id"))
            and (account_id is None or value["id"] == account_id))


def authorize_backfill(repo, github, env):
    metadata = github.get(f"/repos/{repo}")
    owner = metadata.get("owner") or {}
    if (metadata.get("full_name") != repo or not identity(owner, repo.split("/")[0], "User")
            or env.get("GITHUB_EVENT_NAME") != "workflow_dispatch"
            or env.get("GITHUB_REF") != "refs/heads/main" or env.get("GITHUB_RUN_ATTEMPT") != "1"
            or env.get("GITHUB_ACTOR") != owner["login"]
            or env.get("GITHUB_TRIGGERING_ACTOR") != owner["login"]
            or env.get("GITHUB_ACTOR_ID") != str(owner["id"])):
        raise ValueError("backfill writes require a first-attempt main dispatch by the live User owner")


def app_authorization(pull, repo, github):
    """Return verified exact-head owner authorization and actual App execution identity."""
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo or ""):
        raise ValueError("invalid repository")
    number = pull.get("number")
    if not positive(number):
        raise ValueError("invalid PR number")
    canonical = github.get(f"/repos/{repo}/pulls/{number}")
    if not isinstance(canonical, dict):
        raise ValueError("canonical PR evidence is missing")
    # The event is not permission to substitute a different PR/head/merge in a backfill.
    for field in ("number", "merge_commit_sha", "merged_at", "html_url"):
        if canonical.get(field) != pull.get(field):
            raise ValueError("event differs from canonical merged PR")
    if any((canonical.get("head") or {}).get(field) != (pull.get("head") or {}).get(field)
           for field in ("sha", "ref")):
        raise ValueError("event head differs from canonical merged PR")
    metadata = github.get(f"/repos/{repo}")
    if not isinstance(metadata, dict):
        raise ValueError("repository metadata is missing")
    owner = metadata.get("owner") or {}
    if metadata.get("full_name") != repo or not identity(owner, repo.split("/")[0], "User"):
        raise ValueError("repository owner must be a verified GitHub User")
    # Canonical API identity and status creator must be the same trusted Bot account.
    merger = canonical.get("merged_by")
    if not identity(merger, BOT, "Bot") or not identity(pull.get("merged_by"), BOT, "Bot", merger["id"]):
        raise ValueError("merge executor is not the trusted GitHub App")
    head = canonical.get("head") or {}
    base = canonical.get("base") or {}
    if (canonical.get("merged") is not True or canonical.get("state") != "closed"
            or canonical.get("html_url") != f"https://github.com/{repo}/pull/{number}"
            or not SHA.fullmatch(head.get("sha") or "")
            or not SHA.fullmatch(canonical.get("merge_commit_sha") or "")
            or (head.get("repo") or {}).get("full_name") != repo
            or (base.get("repo") or {}).get("full_name") != repo or base.get("ref") != "main"):
        raise ValueError("App merge requires a same-repository merged PR to main")
    merged_at = instant(canonical.get("merged_at"))
    statuses = github.pages(f"/repos/{repo}/commits/{head['sha']}/statuses")
    if not isinstance(statuses, list):
        raise ValueError("status evidence is incomplete")
    relevant = []
    for status in statuses:
        if not isinstance(status, dict):
            raise ValueError("malformed status evidence")
        if (status.get("context") != CONTEXT
                or not identity(status.get("creator"), BOT, "Bot", merger["id"])):
            continue
        if instant(status.get("created_at")) <= merged_at:
            relevant.append(status)
    if not relevant:
        if merged_at <= instant(LEGACY_CUTOFF):
            authorization = legacy_authorization(canonical, repo, owner, github)
            return {
                "mergedByType": "GITHUB_APP", "mergedById": merger["id"],
                "headSha": head["sha"], "mergeCommitSha": canonical["merge_commit_sha"],
                "authorization": authorization,
            }
        # codex remains SHA-scoped. Other branches need independently observed owner
        # intent AND an immutable supported pre-merge prefix rule, never current-main policy.
        if not isinstance(head.get("ref"), str) or head["ref"].startswith("codex/"):
            raise ValueError("no exact-head human authorization before merge")
        policy = premerge_policy_context(canonical, repo, github, CONTEXT)
        authorization = pr_request_observation(canonical, repo, owner, github)
        authorization.update(kind="POLICY_PR_AUTO_MERGE", **policy)
        return {
            "mergedByType": "GITHUB_APP", "mergedById": merger["id"],
            "headSha": head["sha"], "mergeCommitSha": canonical["merge_commit_sha"],
            "policyAuthorization": authorization,
        }
    decision = max(relevant, key=lambda item: (instant(item["created_at"]), item.get("id", 0)))
    if (not positive(decision.get("id")) or decision.get("state") != "success"
            or decision.get("description") != f"owner-approved PR #{number}"):
        raise ValueError("latest trusted premerge decision is not approval for this PR")
    url = decision.get("target_url") or ""
    match = re.fullmatch(re.escape(f"https://github.com/{repo}/actions/runs/") + r"([1-9][0-9]*)", url)
    if not match:
        raise ValueError("approval evidence URL is not canonical")
    run_id = int(match[1])
    run = github.get(f"/repos/{repo}/actions/runs/{run_id}/attempts/1")
    if not isinstance(run, dict):
        raise ValueError("approval workflow is missing")
    if (run.get("id") != run_id or run.get("path") != WORKFLOW
            or run.get("event") != "workflow_dispatch" or run.get("head_branch") != "main"
            or run.get("run_attempt") != 1 or run.get("status") != "completed"
            or run.get("conclusion") != "success" or run.get("html_url") != url
            or (run.get("repository") or {}).get("full_name") != repo
            or not identity(run.get("actor"), owner["login"], "User", owner["id"])
            or not identity(run.get("triggering_actor"), owner["login"], "User", owner["id"])):
        raise ValueError("approval workflow identity or outcome is not trusted")
    if not (instant(run.get("created_at")) <= instant(decision["created_at"])
            <= instant(run.get("updated_at")) <= merged_at):
        raise ValueError("approval is outside its successful premerge workflow")
    jobs = github.pages(f"/repos/{repo}/actions/runs/{run_id}/attempts/1/jobs", "jobs")
    if not isinstance(jobs, list) or any(not isinstance(job, dict) for job in jobs):
        raise ValueError("approval writer job evidence is malformed")
    writers = [job for job in jobs if job.get("name") == "decide"]
    if (len(writers) != 1 or writers[0].get("status") != "completed"
            or writers[0].get("conclusion") != "success"
            or not (instant(writers[0].get("started_at")) <= instant(decision["created_at"])
                    <= instant(writers[0].get("completed_at")) <= merged_at)):
        raise ValueError("exact-head authorization writer did not succeed before merge")
    return {
        "mergedByType": "GITHUB_APP", "mergedById": merger["id"],
        "headSha": head["sha"], "mergeCommitSha": canonical["merge_commit_sha"],
        "authorization": {
            "kind": "OWNER_WORKFLOW_EXACT_HEAD", "scope": "HEAD",
            "approvedBy": owner["login"], "approvedById": owner["id"],
            "approvedAt": decision["created_at"], "evidenceUrl": url,
            "statusId": decision["id"], "workflowRunId": run_id,
        },
    }


LEGACY_QUERY = """
query($owner:String!,$name:String!,$number:Int!,$cursor:String) {
  repository(owner:$owner,name:$name) {
    pullRequest(number:$number) {
      id
      autoMergeRequest { enabledAt enabledBy { __typename login ... on User { databaseId } } }
      timelineItems(first:100,after:$cursor,itemTypes:[MERGED_EVENT,AUTO_MERGE_DISABLED_EVENT]) {
        nodes {
          __typename
          ... on MergedEvent { id createdAt commit { oid } actor { __typename login ... on Bot { databaseId } } }
          ... on AutoMergeDisabledEvent { createdAt }
        }
        pageInfo { hasNextPage endCursor }
      }
    }
  }
}
"""


def legacy_authorization(pull, repo, owner, github):
    """Reconstruct observed historical PR-scoped intent. This does not certify SHA approval."""
    if instant(pull["merged_at"]) > instant(LEGACY_CUTOFF):
        raise ValueError("legacy reconstruction is unavailable after the policy cutoff")
    return pr_request_observation(pull, repo, owner, github)


def pr_request_observation(pull, repo, owner, github):
    """Observe the owner request and matching App merge without claiming SHA approval."""
    cursor, snapshot, nodes, seen = None, None, [], set()
    for _ in range(100):
        data = github.graphql(LEGACY_QUERY, {
            "owner": repo.split("/")[0], "name": repo.split("/")[1],
            "number": pull["number"], "cursor": cursor,
        })
        current = (data.get("repository") or {}).get("pullRequest")
        if not isinstance(current, dict) or not current.get("id"):
            raise ValueError("missing historical PR evidence")
        identity_snapshot = {key: current.get(key) for key in ("id", "autoMergeRequest")}
        if snapshot is not None and snapshot != identity_snapshot:
            raise ValueError("historical evidence changed during pagination")
        snapshot = identity_snapshot
        timeline = current.get("timelineItems") or {}
        if not isinstance(timeline.get("nodes"), list) or not isinstance(timeline.get("pageInfo"), dict):
            raise ValueError("incomplete historical timeline")
        nodes.extend(timeline["nodes"])
        info = timeline["pageInfo"]
        if info.get("hasNextPage") is False:
            break
        cursor = info.get("endCursor")
        if info.get("hasNextPage") is not True or not cursor or cursor in seen:
            raise ValueError("incomplete historical timeline pagination")
        seen.add(cursor)
    else:
        raise ValueError("historical timeline pagination limit reached")
    request = snapshot.get("autoMergeRequest") or {}
    actor = request.get("enabledBy") or {}
    if (actor.get("__typename") != "User" or actor.get("login") != owner["login"]
            or actor.get("databaseId") != owner["id"]):
        raise ValueError("historical auto-merge request is not from the human owner")
    enabled_at = instant(request.get("enabledAt"))
    if enabled_at > instant(pull["merged_at"]):
        raise ValueError("historical request happened after the merge")
    merged = []
    for node in nodes:
        if not isinstance(node, dict):
            raise ValueError("malformed historical timeline node")
        if node.get("__typename") == "AutoMergeDisabledEvent" and enabled_at <= instant(node.get("createdAt")) <= instant(pull["merged_at"]):
            raise ValueError("historical auto-merge request was subsequently disabled")
        if node.get("__typename") == "MergedEvent":
            merged.append(node)
    if len(merged) != 1:
        raise ValueError("historical merge event is missing or ambiguous")
    event = merged[0]
    executor = event.get("actor") or {}
    observed_at = dt.datetime.now(dt.timezone.utc)
    # GitHub REST and timeline timestamps can differ by one second. A large discrepancy
    # requires investigation, rather than reconstructing a different event's provenance.
    event_at = instant(event.get("createdAt"))
    if abs((event_at - instant(pull["merged_at"])).total_seconds()) > 60 or event_at > observed_at:
        raise ValueError("historical merge timestamp disagrees with the canonical merge")
    if (not event.get("id") or (event.get("commit") or {}).get("oid") != pull["merge_commit_sha"]
            or executor.get("__typename") != "Bot" or executor.get("login") not in (BOT, BOT.removesuffix("[bot]"))
            or executor.get("databaseId") != pull["merged_by"]["id"]
            or enabled_at > instant(event.get("createdAt"))):
        raise ValueError("historical merged event does not match the actual executor and commit")
    return {
        "kind": "LEGACY_PR_AUTO_MERGE", "scope": "PR", "approvedBy": owner["login"],
        "approvedById": owner["id"], "approvedAt": request["enabledAt"],
        "evidenceUrl": pull["html_url"], "pullRequestNodeId": snapshot["id"],
        "mergeEventNodeId": event["id"], "mergeEventAt": event["createdAt"],
        "observedAt": observed_at.isoformat(),
    }
