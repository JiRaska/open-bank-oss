<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->

# Phase-3 attestation evidence — recorded human merge (ADR-0031)

**Status:** TEMPLATE — fill in the four artifacts below after running
[`runbook 0018`](../runbooks/0018-agent-merge-record-first-attestation.md) against the running
deployment, then merge. Every artifact must be copy-paste-verifiable by a third party with the
same commands from the runbook; narrative alone does not advance the phase.

**Attestor:** _<name, role>_
**Date:** _<YYYY-MM-DD>_
**Deployment:** _<cluster/env, agent-service version>_

---

## 1. Workflow run (recorder executed, fail-closed)

_<URL of the `record-agent-merge` workflow run for the first merged agent PR — from runbook §2:_
`gh run list --workflow record-agent-merge --limit 5`_. The run must be green; a yellow
"UNCONFIGURED" run is NOT evidence._

```
<paste the run's classification + record line from its log>
```

## 2. Evidence row (`agent_merge_record`, runbook §3)

```
<paste the SELECT result — agent_id, pr_number, merged_by, merged_at, source, recorded_at>
```

Expected invariants: `source = 'github-actions.record-agent-merge'`, `merged_by` = the GitHub
login of the human who pressed merge, `recorded_at` within seconds of the workflow run.

## 3. Audit chain (`audit_entries`, runbook §4)

```
<paste the SELECT result — actor_type, actor_id, event_type, occurred_at for event_type = 'agent.pr.merged'>
```

Expected invariants: `actor_type = 'HUMAN'`, `actor_id` = the merging human from §2, exactly one
event per merge (a redelivery must not double it).

## 4. Least-privilege negative check (runbook §5)

```
<paste the HTTP status from the ROLE_ADMIN-only token POST — must be 403>
```

Expected invariant: 403, and no row with the probe `pr_number` exists. A 2xx here is a P1
regression — do not merge this attestation; file against agent-service.

---

## Sign-off

By merging this document the attestor states the four artifacts above were produced by running
runbook 0018 against the named deployment on the named date, and that any third party can
reproduce them with the same commands. The snapshot generator's roadmap validation
(`gen-ai-governance-snapshot.py --self-test`) mechanically rejects an inconsistent phase
advance; this document is the human-checkable substance behind it.
