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
#   * head branch carrying a declared charter id from openbank-libs/governance/agents.yaml
#     (`<id>/<rest>`, `<id>-<rest>`, or `agent/<id>-<rest>`) — the registry is authoritative
#   * head branch under `agent/` or `codex/` that matches NO charter id — an unchartered
#     coding session; the agentId is the constant `external-coding-agent` (never a made-up
#     segment id: an ADR number is not an agent)
#   * author login declared in rules.yaml `autonomous_agent_prs.agent_accounts`
#     (normalised the same way check-agent-pr-guard.py normalises: `app/` prefix and
#     `[bot]` suffix are spelling variants of one machine account)
#   rules.yaml `agent_branch_prefixes` is deliberately NOT the scope source here: the live
#   value was emptied by owner decision (2026-10-14) when the queue stopped gating on
#   branch names, while merge recording still has to cover the historical `agent/` and
#   `codex/` shapes — those two prefixes are this recorder's own constant scope.
#
# agentId MAPPING (registry-first; #12822 audit finding — the old first-dash-segment rule
# recorded `agent/adr-0031-...` as agent "adr" and missed flaky-test-hunter's
# `flaky-test-hunter/unit-return-<n>` branches entirely, so a hunter merge would have
# passed green UNRECORDED)
#   <id>/<rest>              -> <id>   (e.g. flaky-test-hunter/unit-return-42 -> "flaky-test-hunter")
#   <id>-<rest>, agent/<id>-<rest> -> <id>   (longest registry prefix wins)
#   codex/<anything>         -> "external-coding-agent" (constant; no charter id exists)
#   agent/<seg>-<rest>, no registry match -> "external-coding-agent" (unchartered session)
#   prefix-less branch with a declared-agent author -> the author login, verbatim
#
# FAIL CLOSED ON PURPOSE
#   This producer runs on pull_request CLOSED — the merge has ALREADY happened, so a
#   red exit here cannot block or un-do anything. The earlier fail-soft design (::warning::
#   + exit 0 on every failure) therefore protected nothing and cost everything: a green
#   check with a missing record reads exactly like "no agent PRs merged", the
#   un-falsifiable state phase 3 exists to eliminate. Every path where a record SHOULD
#   exist but does not (unreadable rules/agents registry, absent merged_by, missing
#   config, unreachable endpoint, non-2xx) now exits 1 with a `::error::`, and the
#   workflow opens one tracking issue on top. The only exit-0 outcomes are "recorded"
#   and "legitimately nothing to record" (the PR is not agent-authored).
#
# USAGE
#   python3 .github/scripts/record-agent-merge.py [--event PATH] [--rules PATH]
#                                                 [--agents PATH] [--token-file PATH | --dry-run]
#
#   --event       pull_request closed event JSON (default: $GITHUB_EVENT_PATH)
#   --rules       governance rules.yaml (default: openbank-libs/governance/rules.yaml)
#   --agents      governance agents.yaml charter registry (default:
#                 openbank-libs/governance/agents.yaml) — the only valid agentId source
#   --token-file  0600 file holding `Authorization: Bearer <token>` (the workflow mints a
#                 client_credentials token per run — realm accessTokenLifespan is 300 s,
#                 so a static JWT would be dead on arrival). Wins over the env fallback.
#   --dry-run     build and print the payload, never POST (for humans inspecting a run)
#
# ENV
#   AGENT_SERVICE_URL            base URL of openbank-agent-service; the record is POSTed to
#                                $AGENT_SERVICE_URL/api/v1/agent-merge-records. Sourced from
#                                the repo variable of the same name in the workflow.
#   AGENT_MERGE_RECORDER_TOKEN   bearer token fallback used only when --token-file is
#                                absent — for tests and local dry inspection. The workflow
#                                deliberately does NOT source it from a repo secret: a
#                                statically provisioned JWT dies with the realm's 300-second
#                                accessTokenLifespan (see workflow header).
#   AGENT_MERGE_PROPOSAL_ID      optional override for the optional proposalId field; when
#                                unset the script looks for a `Proposal-Id: <id>` line in the
#                                PR body and otherwise omits the field.
#
# EXIT CODES
#   0  recorded, or legitimately nothing to record (PR not agent-authored), or --dry-run
#   1  anything else: malformed event, unreadable rules, absent merged_by, missing
#      config/token, or the POST did not succeed — a record that should exist but does not
import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

RULES = "openbank-libs/governance/rules.yaml"
AGENTS_REGISTRY = "openbank-libs/governance/agents.yaml"
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

    try:
        doc = yaml.safe_load(Path(path).read_text())
    except yaml.YAMLError as error:
        raise ValueError(f"{path}: invalid YAML: {error}") from error
    cfg = (doc or {}).get("autonomous_agent_prs")
    accounts = cfg.get("agent_accounts") if isinstance(cfg, dict) else None
    if not isinstance(accounts, list) or any(not isinstance(a, str) or not a for a in accounts):
        raise ValueError(f"{path}: autonomous_agent_prs.agent_accounts must be a string list")
    return {norm_login(a) for a in accounts}


def load_agent_ids(path=AGENTS_REGISTRY):
    """Declared charter ids from agents.yaml — the ONLY valid agentId values for a merge
    record (the endpoint's KDoc: "the declared agent id, derived from the PR head branch").
    Never a hard-kept list here."""
    import yaml

    try:
        doc = yaml.safe_load(Path(path).read_text())
    except yaml.YAMLError as error:
        raise ValueError(f"{path}: invalid YAML: {error}") from error
    agents = (doc or {}).get("agents")
    ids = [a.get("id") for a in agents
           if isinstance(a, dict) and isinstance(a.get("id"), str) and a["id"]] \
        if isinstance(agents, list) else None
    if not ids:
        raise ValueError(f"{path}: agents must be a list of objects with a non-empty id")
    # Longest-first, so a shorter id that prefixes a longer one never wins ("finops-agent"
    # before any hypothetical "finops").
    return sorted(set(ids), key=len, reverse=True)


def decide_agent_id(branch, author, agent_accounts, agent_ids):
    """agentId for the record, or None when the PR is not agent-authored.

    Resolution order — the registry is authoritative, because an id that is not a declared
    charter id is false provenance in an audit store (#12822 audit finding):
      1. registry longest-prefix: `<id>/<rest>`, `<id>-<rest>`, `agent/<id>-<rest>`, ...
         (this is what catches flaky-test-hunter's `flaky-test-hunter/unit-return-<n>` —
         the first-dash-segment rule returned no agent at all for it, so a hunter merge
         would have passed green UNRECORDED)
      2. `codex/<anything>` -> external-coding-agent (constant; no charter id exists)
      3. `agent/<seg>-<rest>` with NO registry match -> external-coding-agent — an
         unchartered coding session records as external, NEVER as the made-up segment id
         (an ADR number is not an agent; recording `agent/adr-0031-...` as agent "adr"
         was the second half of the finding)
      4. author declared in rules.yaml `agent_accounts` -> the login, verbatim
    """
    candidates = [branch]
    if branch.startswith("agent/"):
        candidates.append(branch[len("agent/"):])
    for agent_id in agent_ids:
        for candidate in candidates:
            if candidate == agent_id or candidate.startswith(agent_id + "/") \
                    or candidate.startswith(agent_id + "-"):
                return agent_id
    for prefix in AGENT_BRANCH_PREFIXES:
        if branch.startswith(prefix):
            rest = branch[len(prefix):]
            # codex/ and unchartered agent/ sessions are both external coding sessions;
            # a bare `agent/` carries no session identity at all.
            return CODEX_AGENT_ID if rest else None
    if norm_login(author) in agent_accounts:
        return author
    return None


def human_merger(pull, agent_accounts):
    """Return an eligible GitHub User login (event data cannot prove who held credentials)."""
    merger = pull.get("merged_by")
    if not isinstance(merger, dict):
        return None
    login = merger.get("login")
    if merger.get("type") != "User" or not isinstance(login, str) or not login.strip():
        return None
    login = login.strip()
    login_key = login.casefold()
    declared_agents = {norm_login(account).casefold() for account in agent_accounts}
    if login_key.startswith("app/") or login_key.endswith("[bot]") or \
            norm_login(login).casefold() in declared_agents:
        return None
    return login


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


def resolve_token(token_file):
    """Bearer token from the mint step's 0600 header file (--token-file), with the env
    fallback for tests/local runs. Returns (token, error-reason-or-None)."""
    if token_file:
        try:
            content = Path(token_file).read_text().strip()
        except OSError as error:
            return None, f"cannot read token file '{token_file}': {error}"
        # The mint step writes an HTTP header line; accept a bare token too.
        match = re.match(r"^Authorization:\s*Bearer\s+(\S+)\s*$", content)
        token = match.group(1) if match else content
        if not token:
            return None, f"token file '{token_file}' is empty"
        return token, None
    token = (os.environ.get("AGENT_MERGE_RECORDER_TOKEN") or "").strip()
    if token:
        return token, None
    return None, ("AGENT_MERGE_RECORDER_TOKEN is not set and --token-file was not given "
                  "(the workflow mints a client_credentials token per run — see header)")


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
    parser.add_argument("--agents", default=AGENTS_REGISTRY, help="governance agents.yaml charter registry")
    parser.add_argument("--token-file", help="0600 file holding 'Authorization: Bearer <token>' "
                                             "(workflow-minted client_credentials token)")
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
        agent_ids = load_agent_ids(args.agents)
    except (OSError, ValueError) as error:
        print(f"::error::record-agent-merge: cannot read governance scope ({args.rules} / "
              f"{args.agents}): {error} — the agent-authorship scope is undecidable, and an "
              "undecidable scope must not read as 'nothing to record'", file=sys.stderr)
        return 1

    branch = (pull.get("head") or {}).get("ref") or ""
    author = ((pull.get("user") or {}).get("login")) or ""
    agent_id = decide_agent_id(branch, author, agent_accounts, agent_ids)
    if agent_id is None:
        print(f"record-agent-merge: PR #{(pull.get('number'))} (branch '{branch}', author "
              f"'{author}') is not agent-authored — nothing to record.")
        return 0

    merged_by = human_merger(pull, agent_accounts)
    if not merged_by:
        print(f"::error::record-agent-merge: PR #{pull.get('number')} merged but merged_by "
              "does not identify an eligible GitHub User account — a merge record without "
              "a human identity is worthless evidence; investigate the event shape instead "
              "of recording a stub",
              file=sys.stderr)
        return 1

    payload = build_payload(pull, agent_id)
    proposal_id = extract_proposal_id(pull)
    if proposal_id:
        payload["proposalId"] = proposal_id

    if args.dry_run:
        print(json.dumps(payload, indent=2, sort_keys=True))
        return 0

    base_url = (os.environ.get("AGENT_SERVICE_URL") or "").strip()
    if not base_url:
        print("::error::record-agent-merge: AGENT_SERVICE_URL is not set (repo variable "
              f"'AGENT_SERVICE_URL' unset?) — agent merge of PR #{payload['prNumber']} NOT "
              "recorded", file=sys.stderr)
        return 1
    token, token_error = resolve_token(args.token_file)
    if token is None:
        print(f"::error::record-agent-merge: no bearer token: {token_error} — agent merge "
              f"of PR #{payload['prNumber']} NOT recorded", file=sys.stderr)
        return 1

    failure = post_record(base_url, token, payload)
    if failure:
        # Fail closed on purpose: the merge already happened (pull_request closed), so red
        # here blocks nothing — but a green check with a missing record is the
        # un-falsifiable state this evidence exists to eliminate.
        print(f"::error::record-agent-merge: {failure}; merge of agent PR "
              f"#{payload['prNumber']} happened but was NOT recorded — investigate, do not "
              "re-run the merge", file=sys.stderr)
        return 1
    print(f"record-agent-merge: agent PR #{payload['prNumber']} ({agent_id}) merged by "
          f"{merged_by} — recorded at {base_url.rstrip('/')}{ENDPOINT_PATH}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
