<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->

# Runbook 0018 — Attesting the first recorded human merge (ADR-0031 phase 3)

**Audience:** the human operator (or internal auditor) who must prove that the phase-3
"recorded human merge" evidence chain works **in production** — not just in CI — before the
AI-governance maturity score advances past 2/5 (tracker: issue #5261).

**Prerequisites:** PR `#12883` (recorder + `ROLE_MERGE_RECORDER`) merged and deployed, the
`record-agent-merge` workflow provisioned per the merge-day comment in that PR (Keycloak
client-credentials client carrying exactly `ROLE_MERGE_RECORDER`, `AGENT_MERGE_RECORDER_*`
vars/secrets, `AGENT_SERVICE_URL` reachable from the ARC runner).

---

## 1. What is being attested

Three properties, all observable from outside the agent:

1. **Evidence lands.** When a human merges an agent-authored PR, the `record-agent-merge`
   workflow POSTs the merge to `POST /api/v1/agent-merge-records` and a durable row exists in
   `agent_merge_record`.
2. **The evidence is audit-chained.** The same ingest emits exactly one `agent.pr.merged`
   audit event with `actorType=HUMAN` under the *merging human* (not the agent), delivered
   through the durable `agent_audit_outbox` into the hash-chained audit store.
3. **Least privilege holds.** A token with `ROLE_ADMIN` but **without** `ROLE_MERGE_RECORDER`
   is rejected with 403 — the evidence endpoint is not reachable by the platform's most
   privileged role.

Until all three are demonstrated against the running deployment, the maturity claim is
"mechanically ready", not "attested". Do not advance the score on a dry run.

## 2. Trigger the evidence

Merge any agent-authored PR (branch prefix `agent/` or `codex/`) through the normal
squash-merge flow. Then watch the workflow:

```
gh run list --workflow record-agent-merge --limit 5
```

The recorder is **fail-closed**: a missing credential, a rejected POST or an unreachable
service fails the run loudly (`::error::`), it does not silently skip. A green run with no
record means the PR was not agent-authored or the gate did not fire — check the run log's
classification line first.

## 3. Verify the row (evidence)

Via the API (`ROLE_OPERATOR` / `ROLE_ADMIN` / `ROLE_COMPLIANCE` token):

```
curl -s -H "Authorization: Bearer $TOKEN" \
  "$AGENT_SERVICE_URL/api/v1/agent-merge-records?agentId=<agentId>" | jq .
```

Or directly in Postgres (the queryable source of truth):

```sql
SELECT agent_id, pr_number, merged_by, merged_at, source, recorded_at
FROM agent_merge_record
WHERE pr_number = <prNumber>;
```

Expected: exactly one row, `merged_by` = the GitHub login of the human who pressed merge,
`source = 'github-actions.record-agent-merge'`, `recorded_at` within seconds of the workflow run.

## 4. Verify the audit chain

```sql
SELECT actor_type, actor_id, event_type, occurred_at
FROM audit_entries
WHERE event_type = 'agent.pr.merged'
ORDER BY occurred_at DESC LIMIT 5;
```

Expected: one event per merge, `actor_type = 'HUMAN'`, `actor_id = merged_by` from §3. The
agent never self-reports: an event with an agent actor here is a defect — file it against
`agent-service`. Redelivering the same POST (same `prNumber`) must NOT create a second event:
`ON CONFLICT DO NOTHING` returns the stored row with `inserted=false` and no audit write
(covered by `AgentMergeRecordApiIT` "duplicate redelivery returns the stored record without a
second audit event"; re-check it live when re-attesting after an incident).

## 5. Verify least privilege (negative check)

Mint a token for any client that holds `ROLE_ADMIN` but not `ROLE_MERGE_RECORDER` (or strip
the role from a test client):

```
curl -s -o /dev/null -w "%{http_code}" -X POST \
  -H "Authorization: Bearer $ADMIN_ONLY_TOKEN" \
  -H "Content-Type: application/json" \
  "$AGENT_SERVICE_URL/api/v1/agent-merge-records" \
  -d '{"agentId":"x","prNumber":999999999,"prUrl":"https://example.invalid","headBranch":"x","mergedBy":"x","mergedAt":"2026-01-01T00:00:00Z","title":"negative-check"}'
```

Expected: **403**, and no row with `pr_number = 999999999` exists after the call. A 2xx here
is a security regression — the `@RolesAllowed` change was lost; block the maturity advance
and file against `agent-service` as a P1.

## 6. Record the attestation

Open a comment on issue #5261 containing, verbatim:

- the workflow run URL (§2),
- the `agent_merge_record` row as returned by the §3 query,
- the `audit_entries` row from §4,
- the §5 negative-check HTTP status,
- the attestor's identity and date.

The attestation is a claim others can re-verify with the same four commands — keep it
copy-paste runnable. Absence of a record must never be read as "no agent PRs were merged"
(see the workflow's UNCONFIGURED error path): an empty table after a real merge is itself a
finding.

## 7. Failure triage (merge missing from evidence)

In order (from `openbank-agent-service` docs 05-operations):

1. **Workflow run log** — a rejected or unauthenticated POST fails loudly there
   (`401`/`403`/mint failure). Fail-closed means the run is red, not silent.
2. **Role** — confirm the CI client's token carries exactly `ROLE_MERGE_RECORDER`: mint a
   client-credentials token as the workflow does and decode its payload
   (`jq -R 'split(".")[1] | @base64d | fromjson' <<< "$tok"` → `realm_access.roles`). The most
   common drift: provisioned with `ROLE_ADMIN` per an outdated comment — the endpoint rejects
   that with 403 by design.
3. **Token TTL** — the minted token lives 5 minutes (`accessTokenLifespan: 300`); a recorder
   that mints long before posting will write nothing. The workflow mints immediately before
   the POST; a custom re-run after a long queue stall can exceed the window.
4. **Reachability** — `AGENT_SERVICE_URL` must be the in-cluster service address from an ARC
   runner inside the cluster; a hosted `ubuntu-latest` runner cannot resolve
   `*.svc.cluster.local` and the POST dies on DNS.
5. **Postgres** — the service's DB must be reachable and the `V6__agent_merge_record`
   migration applied (`\d agent_merge_record` in the service database).
