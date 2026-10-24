#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# RECORD AGENT MERGE — ADR-0031 phase 3 execution and human authorization evidence.
#
# Actual GitHub merge executors are preserved. Direct human merges retain the legacy
# payload. Trusted App merges carry separate, verified exact-head owner workflow proof.
# A bounded historical PR-scoped reconstruction is explicitly labelled and never claims
# approval of a SHA or historical policy compliance (see merge_authorization_proof.py).
#
# Agent scope comes from the charter registry, declared agent accounts and historical
# agent/ or codex/ branch shapes; live queue prefix scope is not the evidence scope.
# A missing/invalid proof or failed write/read-back fails closed and keeps the tracking
# issue open. A new green run does not repair an older missing record.
#
# --pr N fetches a canonical merged PR for an owner/main workflow backfill.
# --dry-run never writes. --verify-record checks backend capability before writing and
# independently reads the committed record back. App writes always require verification.
# --token-file contains the freshly minted Authorization header; env token is test/local
# fallback only. Never log or put a bearer token in a process argument.
import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path
from merge_authorization_proof import BOT, GitHub, app_authorization, authorize_backfill, instant

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


class BackendNoRedirect(urllib.request.HTTPRedirectHandler):
    """Recorder credentials may reach only the configured endpoint, never a redirect."""
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise urllib.error.HTTPError(req.full_url, code, "backend redirect refused", headers, fp)


def backend_open(request, timeout=10):
    return urllib.request.build_opener(BackendNoRedirect()).open(request, timeout=timeout)


def post_record(base_url, token, payload, timeout=10):
    """POST the record; returns None on success or a human-readable failure reason."""
    suffix = "/app" if payload.get("mergedByType") == "GITHUB_APP" else ""
    if payload.get("policyAuthorization") is not None:
        suffix = "/app-policy"
    url = base_url.rstrip("/") + ENDPOINT_PATH + suffix
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
        with backend_open(request, timeout=timeout) as response:
            if 200 <= response.status < 300:
                return None
            return f"HTTP {response.status} from {url}"
    except urllib.error.HTTPError as error:
        failure = f"HTTP {error.code} from {url}: {error.reason}"
        error.close()
        return failure
    except (urllib.error.URLError, OSError) as error:
        return f"could not reach {url}: {error}"


def read_backend(base_url, token, suffix):
    request = urllib.request.Request(base_url.rstrip("/") + ENDPOINT_PATH + suffix,
                                     headers={"Authorization": f"Bearer {token}"})
    try:
        with backend_open(request, timeout=10) as response:
            if response.status != 200:
                raise ValueError(f"backend read returned HTTP {response.status}")
            raw = response.read(1_000_001)
            if len(raw) > 1_000_000:
                raise ValueError("backend evidence response exceeds bounded size")
            value = json.loads(raw)
        if not isinstance(value, dict):
            raise ValueError("backend evidence read is not an object")
        return value
    except urllib.error.HTTPError as error:
        code = error.code
        error.close()
        raise ValueError(f"backend evidence read failed: HTTP {code}") from None
    except (OSError, ValueError) as error:
        raise ValueError("backend evidence read failed: " + str(error)) from error


def verify_stored(payload, stored):
    """A successful write is insufficient: independently read the committed evidence back."""
    for key in ("agentId", "prNumber", "prUrl", "headBranch", "mergedBy", "mergedAt"):
        same = (instant(stored.get(key)) == instant(payload[key])) if key == "mergedAt" else stored.get(key) == payload[key]
        if not same:
            raise ValueError("stored merge identity differs: " + key)
    if payload.get("mergedByType") != "GITHUB_APP":
        if stored.get("mergedByType") != "HUMAN":
            raise ValueError("stored human merger has incorrect actor type")
        if stored.get("authorization") is not None or stored.get("policyAuthorization") is not None:
            raise ValueError("stored human merge carries App authorization")
        return
    for key in ("mergedByType", "mergedById", "headSha", "mergeCommitSha"):
        if stored.get(key) != payload[key]:
            raise ValueError("stored App merge identity differs: " + key)
    family = "policyAuthorization" if payload.get("policyAuthorization") is not None else "authorization"
    other = "authorization" if family == "policyAuthorization" else "policyAuthorization"
    if stored.get(other) is not None or payload.get(other) is not None:
        raise ValueError("ambiguous authorization families")
    expected, actual = payload[family], stored.get(family)
    if not isinstance(actual, dict):
        raise ValueError("stored human authorization is missing")
    for key, value in expected.items():
        if key == "observedAt":
            # A retry returns the original observation timestamp, not a fabricated new record.
            if not (instant(payload["mergedAt"]) <= instant(actual.get(key)) <= instant(value)):
                raise ValueError("stored observation is outside the verified reconstruction interval")
        elif key in ("approvedAt", "mergeEventAt"):
            if instant(actual.get(key)) != instant(value):
                raise ValueError("stored authorization timestamp differs: " + key)
        elif actual.get(key) != value:
            raise ValueError("stored authorization differs: " + key)


def main(argv=None):
    parser = argparse.ArgumentParser(description="record a human merge of an agent PR")
    parser.add_argument("--event", help="pull_request closed event JSON (default: $GITHUB_EVENT_PATH)")
    parser.add_argument("--rules", default=RULES, help="governance rules.yaml")
    parser.add_argument("--agents", default=AGENTS_REGISTRY, help="governance agents.yaml charter registry")
    parser.add_argument("--token-file", help="0600 file holding 'Authorization: Bearer <token>' "
                                             "(workflow-minted client_credentials token)")
    parser.add_argument("--dry-run", action="store_true", help="print the payload, never POST")
    parser.add_argument("--pr", type=int, help="backfill one canonical merged PR from GitHub (main workflow only)")
    parser.add_argument("--verify-record", action="store_true", help="require schema capability and committed read-back")
    args = parser.parse_args(argv)

    event_path = args.event or os.environ.get("GITHUB_EVENT_PATH", "")
    try:
        if args.pr is not None:
            if args.pr < 1:
                raise ValueError("PR number must be positive")
            repo = os.environ.get("GITHUB_REPOSITORY", "")
            if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
                raise ValueError("canonical repository is required")
            if not args.dry_run:
                authorize_backfill(repo, GitHub(), os.environ)
            canonical = GitHub().get(f"/repos/{repo}/pulls/{args.pr}")
            if (not isinstance(canonical, dict) or canonical.get("merged") is not True
                    or canonical.get("state") != "closed" or canonical.get("number") != args.pr
                    or canonical.get("html_url") != f"https://github.com/{repo}/pull/{args.pr}"
                    or (canonical.get("base") or {}).get("ref") != "main"
                    or ((canonical.get("base") or {}).get("repo") or {}).get("full_name") != repo):
                raise ValueError("backfill requires an actually merged PR")
            event = {"pull_request": canonical}
        else:
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
    app_proof = None
    if not merged_by and isinstance(pull.get("merged_by"), dict) and pull["merged_by"].get("login") == BOT:
        try:
            app_proof = app_authorization(pull, os.environ.get("GITHUB_REPOSITORY", ""), GitHub())
            merged_by = pull["merged_by"]["login"]
        except (OSError, ValueError, TypeError, KeyError) as error:
            print(f"::error::record-agent-merge: merged_by App evidence rejected: {error}", file=sys.stderr)
            return 1
    if not merged_by:
        print(f"::error::record-agent-merge: PR #{pull.get('number')} merged but merged_by "
              "does not identify an eligible GitHub User account — a merge record without "
              "a human identity is worthless evidence; investigate the event shape instead "
              "of recording a stub",
              file=sys.stderr)
        return 1

    payload = build_payload(pull, agent_id)
    if app_proof:
        payload.update(app_proof)
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

    must_verify = args.verify_record or app_proof is not None
    if must_verify:
        try:
            capability = read_backend(base_url, token, "/capabilities")
            version = capability.get("evidenceSchemaVersion")
            if type(version) is not int or version != 2:
                raise ValueError("deployed backend does not support merge evidence schema 2; no write attempted")
            if payload.get("policyAuthorization") is not None and capability.get("policyPrAuthorization") is not True:
                raise ValueError("deployed backend does not support policy PR evidence; no write attempted")
        except ValueError as error:
            print(f"::error::record-agent-merge: {error}", file=sys.stderr)
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
    if must_verify:
        try:
            verify_stored(payload, read_backend(base_url, token, f"/{payload['prNumber']}"))
        except ValueError as error:
            print(f"::error::record-agent-merge: committed evidence verification failed: {error}", file=sys.stderr)
            return 1
    print(f"record-agent-merge: agent PR #{payload['prNumber']} ({agent_id}) merged by "
          f"{merged_by} — recorded at {base_url.rstrip('/')}{ENDPOINT_PATH}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
