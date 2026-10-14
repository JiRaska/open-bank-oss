#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# RECORD AGENT MERGE — ADR-0031 phase 3 "recorded human merge" evidence (CI-side producer).
#
# WHY THIS EXISTS
#   Phase 3 of ADR-0031 requires evidence that a HUMAN merged an agent-authored PR — the
#   merge event itself, recorded out-of-band of the agent's own telemetry (an agent that
#   reports "a human merged me" about itself proves nothing). This script is the producer:
#   it runs from .github/workflows/record-agent-merge.yml on every closed PR targeting
#   main, decides whether the PR was agent-authored, and POSTs the record to
#   POST /api/v1/agent-merge-records in openbank-agent-service (ROLE_ADMIN-protected).
#
# SCOPE — what counts as agent-authored
#   * head branch under `agent/`  — the fleet's charter branch (branch -> agentId below)
#   * head branch under `codex/`  — external coding sessions; no in-repo charter id, so
#                                   the agentId is the constant `external-coding-agent`
#   * author login declared in rules.yaml `autonomous_agent_prs.agent_accounts`
#     (normalised the same way check-agent-pr-guard.py normalises: `app/` prefix and
#     `[bot]` suffix are spelling variants of one machine account)
#   rules.yaml `agent_branch_prefixes` is deliberately NOT the scope source here: the live
#   value was emptied by owner decision (2026-10-14) when the queue stopped gating on
#   branch names, while merge recording still has to cover the historical `agent/` and
#   `codex/` shapes — those two prefixes are this recorder's own constant scope.
#
# agentId MAPPING
#   agent/<id>-<rest>  -> <id>   (e.g. agent/adr-0031-... -> "adr", the charter-style id)
#   agent/<id>         -> <id>   (no dash: the whole segment is the id)
#   codex/<anything>   -> "external-coding-agent" (constant)
#   prefix-less branch with a declared-agent author -> the author login, verbatim
#
# FAIL-SOFT, ALWAYS
#   Merge recording must never block a merge. Any local defect (malformed event, missing
#   config) and any remote failure (unreachable endpoint, non-2xx) prints a `::warning::`
#   and exits 0. The one exception is a malformed pull_request event, which is a CI bug
#   and fails loud (exit 1) — a silent skip there would look exactly like "no agent PRs
#   merged", the un-falsifiable state.
#
# USAGE
#   python3 .github/scripts/record-agent-merge.py [--event PATH] [--rules PATH] [--dry-run]
#
#   --event   pull_request closed event JSON (default: $GITHUB_EVENT_PATH)
#   --rules   governance rules.yaml (default: openbank-libs/governance/rules.yaml)
#   --dry-run build and print the payload, never POST (for humans inspecting a run)
#
# ENV
#   AGENT_SERVICE_URL            base URL of openbank-agent-service; the record is POSTed to
#                                $AGENT_SERVICE_URL/api/v1/agent-merge-records. Sourced from
#                                the repo variable of the same name in the workflow.
#   AGENT_MERGE_RECORDER_TOKEN   bearer token for the ROLE_ADMIN endpoint; sourced from the
#                                repo secret of the same name (workflow header comment).
#   AGENT_MERGE_PROPOSAL_ID      optional override for the optional proposalId field; when
#                                unset the script looks for a `Proposal-Id: <id>` line in the
#                                PR body and otherwise omits the field.
#
# EXIT CODES
#   0  recorded, or nothing to record, or fail-soft warning emitted
#   1  malformed pull_request event (a CI bug, not a merge-recording outcome)
import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

RULES = "openbank-libs/governance/rules.yaml"
ENDPOINT_PATH = "/api/v1/agent-merge-records"
SOURCE = "github-actions.record-agent-merge"
CODEX_AGENT_ID = "external-coding-agent"
# Historical charter prefixes — see header: rules.yaml's live prefix scope is empty by
# owner decision, but merge recording still has to recognise the branches agents used.
AGENT_BRANCH_PREFIXES = ("agent/", "codex/")
PROPOSAL_ID_RE = re.compile(r"^Proposal-Id:\s*(\S+)\s*$", re.IGNORECASE | re.MULTILINE)


def norm_login(login):
    """One spelling for a machine account (same normalisation as check-agent-pr-guard.py:
    GitHub spells one App as `name[bot]`, `app/name` depending on the surface)."""
    login = (login or "").strip()
    if login.startswith("app/"):
        login = login[len("app/"):]
    if login.endswith("[bot]"):
        login = login[: -len("[bot]")]
    return login


def load_agent_accounts(path=RULES):
    """Declared autonomous agent accounts from rules.yaml — never a hard-kept list here."""
    import yaml

    doc = yaml.safe_load(Path(path).read_text())
    cfg = (doc or {}).get("autonomous_agent_prs")
    accounts = cfg.get("agent_accounts") if isinstance(cfg, dict) else None
    if not isinstance(accounts, list) or any(not isinstance(a, str) or not a for a in accounts):
        raise ValueError(f"{path}: autonomous_agent_prs.agent_accounts must be a string list")
    return {norm_login(a) for a in accounts}


def decide_agent_id(branch, author, agent_accounts):
    """agentId for the record, or None when the PR is not agent-authored."""
    for prefix in AGENT_BRANCH_PREFIXES:
        if branch.startswith(prefix):
            rest = branch[len(prefix):]
            if prefix == "codex/":
                return CODEX_AGENT_ID
            head, _, _ = rest.partition("-")
            return head or None
    if norm_login(author) in agent_accounts:
        return author
    return None


def build_payload(pull, agent_id, proposal_id=None):
    """The exact JSON shape of POST /api/v1/agent-merge-records."""
    payload = {
        "agentId": agent_id,
        "prNumber": pull["number"],
        "prUrl": pull["html_url"],
        "headBranch": pull["head"]["ref"],
        "mergedBy": (pull.get("merged_by") or {}).get("login"),
        "mergedAt": pull.get("merged_at"),
        "title": pull.get("title") or "",
        "source": SOURCE,
    }
    if proposal_id:
        payload["proposalId"] = proposal_id
    return payload


def extract_proposal_id(pull, env=None):
    """Optional link back to the agent's proposal: env override wins, then a
    `Proposal-Id: <id>` line in the PR body. Absent is fine — the field is optional."""
    env = os.environ if env is None else env
    override = (env.get("AGENT_MERGE_PROPOSAL_ID") or "").strip()
    if override:
        return override
    body = pull.get("body") or ""
    match = PROPOSAL_ID_RE.search(body)
    return match.group(1) if match else None


def post_record(base_url, token, payload, timeout=10):
    """POST the record; returns None on success or a human-readable failure reason."""
    url = base_url.rstrip("/") + ENDPOINT_PATH
    request = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {token}",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            if 200 <= response.status < 300:
                return None
            return f"HTTP {response.status} from {url}"
    except urllib.error.HTTPError as error:
        return f"HTTP {error.code} from {url}: {error.reason}"
    except (urllib.error.URLError, OSError) as error:
        return f"could not reach {url}: {error}"


def main(argv=None):
    parser = argparse.ArgumentParser(description="record a human merge of an agent PR")
    parser.add_argument("--event", help="pull_request closed event JSON (default: $GITHUB_EVENT_PATH)")
    parser.add_argument("--rules", default=RULES, help="governance rules.yaml")
    parser.add_argument("--dry-run", action="store_true", help="print the payload, never POST")
    args = parser.parse_args(argv)

    event_path = args.event or os.environ.get("GITHUB_EVENT_PATH", "")
    try:
        event = json.loads(Path(event_path).read_text())
    except (OSError, ValueError) as error:
        print(f"::error::record-agent-merge: cannot read pull_request event at "
              f"'{event_path}': {error} — this is a CI wiring bug, not a merge outcome", file=sys.stderr)
        return 1
    pull = event.get("pull_request")
    if not isinstance(pull, dict):
        print("::error::record-agent-merge: event has no pull_request object — CI wiring bug", file=sys.stderr)
        return 1

    try:
        agent_accounts = load_agent_accounts(args.rules)
    except (OSError, ValueError) as error:
        print(f"::warning::record-agent-merge: cannot read declared agent accounts: {error}; "
              "recording nothing rather than guessing scope")
        return 0

    branch = (pull.get("head") or {}).get("ref") or ""
    author = ((pull.get("user") or {}).get("login")) or ""
    agent_id = decide_agent_id(branch, author, agent_accounts)
    if agent_id is None:
        print(f"record-agent-merge: PR #{(pull.get('number'))} (branch '{branch}', author "
              f"'{author}') is not agent-authored — nothing to record.")
        return 0

    merged_by = (pull.get("merged_by") or {}).get("login")
    if not merged_by:
        print(f"::warning::record-agent-merge: PR #{pull.get('number')} merged but merged_by "
              "login is absent; recording nothing rather than a record with no human identity")
        return 0

    payload = build_payload(pull, agent_id)
    proposal_id = extract_proposal_id(pull)
    if proposal_id:
        payload["proposalId"] = proposal_id

    if args.dry_run:
        print(json.dumps(payload, indent=2, sort_keys=True))
        return 0

    base_url = (os.environ.get("AGENT_SERVICE_URL") or "").strip()
    if not base_url:
        print("::warning::record-agent-merge: AGENT_SERVICE_URL is not set (repo variable "
              f"'AGENT_SERVICE_URL' unset?) — agent merge of PR #{payload['prNumber']} NOT recorded")
        return 0
    token = (os.environ.get("AGENT_MERGE_RECORDER_TOKEN") or "").strip()
    if not token:
        print("::warning::record-agent-merge: AGENT_MERGE_RECORDER_TOKEN is not set (repo secret "
              "'AGENT_MERGE_RECORDER_TOKEN' must be provisioned) — agent merge of PR "
              f"#{payload['prNumber']} NOT recorded")
        return 0

    failure = post_record(base_url, token, payload)
    if failure:
        # Fail-soft on purpose: merge recording must never block or un-do a merge.
        print(f"::warning::record-agent-merge: {failure}; merge of agent PR "
              f"#{payload['prNumber']} happened but was NOT recorded — investigate, do not re-run "
              "the merge")
        return 0
    print(f"record-agent-merge: agent PR #{payload['prNumber']} ({agent_id}) merged by "
          f"{merged_by} — recorded at {base_url.rstrip('/')}{ENDPOINT_PATH}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
