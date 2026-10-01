---
date: 2026-10-01
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [observability, ai-agents, security, privacy-gdpr]
summary: "Self-hosted Langfuse moves off v2, unpatched since 2025-11-07 while it stores every prompt and completion, to v4 with its own ClickHouse, Valkey and S3. Prompts and evals stay in git (ADR-0148)."
---

# ADR-0328 — Upgrade self-hosted Langfuse from the unmaintained v2 line to v4

## Context

ADR-0265 decision 3 chose **self-hosted Langfuse v2**, fed server-side by the LiteLLM gateway.
It deliberately rejected v3 ("for now") on operational cost. v3 adds web, worker, ClickHouse,
Redis and S3, which is five new workloads on a cluster "where a single-replica rollout has
already deadlocked for want of room to surge". It named v3 the upgrade path. This ADR is
needed because both premises of that deferral have changed:

- **v2 is no longer maintained.** The newest v2 image on Docker Hub is `2.95.11`, published
  **2025-11-07**. None of the last 100 GitHub releases is a v2 tag, and the current line is
  `4.49.0` (2026-10-01). Upstream's SECURITY.md says only that the latest version receives
  security updates. We run `docker.io/langfuse/langfuse:2.95.11`
  (`openbank-infra/gitops/components/ai-platform/langfuse.yaml`). That is eleven months
  without fixes on the component that holds **every prompt and completion** the gateway
  traces, including copilot conversations.
- **The capacity premise no longer holds.** Measured 2026-10-01 (`kubectl get nodepool`,
  pod requests summed):
  - the `default` NodePool is capped at 72 CPU / 288 Gi, no longer the 48 / 128 Gi of
    ADR-0173, and a separate `stateful` pool has 28 CPU / 112 Gi;
  - the cluster has 90 allocatable CPU, of which 62.1 are requested, and 324 Gi
    allocatable, of which 148 Gi are requested.

Adjacent decisions this ADR does **not** reopen:

- **ADR-0148** keeps the prompt registry and the evals gate in git
  (`openbank-libs/governance/prompts/`, `governance/evals/`, gate `evals-registry-integrity`).
  It rejected storing money-path-adjacent prompts in Langfuse. v4's prompt management and
  datasets are therefore **not** adopted as sources of truth.
- **ADR-0174 / ADR-0175**: the gateway stays the only egress, and Langfuse stays in-cluster
  and in eu-north-1.
- **ADR-0022**: the analytics ClickHouse stays the analytics warehouse. Langfuse does not share
  it (see Decision 2).

Licensing (langfuse.com/self-hosting/license-key, read 2026-10-01): data-retention policies,
server-side data masking, audit logs and project-level RBAC are **Enterprise-only** when
self-hosted. Core tracing, LLM-as-a-judge evaluators, annotation queues and datasets are
**not** on that list, but the page does not affirmatively confirm them either. Decision 4 says
what follows from that.

## Decision

We will move self-hosted Langfuse from v2.95.11 to the current v4 line.

1. **Path: v2 → v3.29.0 → v4.** Upstream's v2→v3 guide warns that later v3 releases drop
   database entities v2 still uses, and recommends v3.29.0 as the step that runs alongside v2.
   Historic data moves by Langfuse's own background migrations (cost backfill, then traces,
   observations and scores into ClickHouse, newest first). Background migrations are disabled
   while traffic shifts, as the guide requires. Then we upgrade minor-by-minor to the latest v4.
2. **Langfuse gets its own ClickHouse, Valkey and S3 bucket. It shares none of them.**
   - **ClickHouse:** a single-node `clickhouse/clickhouse-server` StatefulSet in `ai-platform`,
     same pattern as `components/analytics/clickhouse.yaml`. It is not the analytics instance:
     that one owns a different data class (ADR-0022) and is the volume that hit 100% in the
     2026-09-30 observability audit.
   - **Valkey:** one Valkey Deployment in `ai-platform`, the fleet's existing `redis.yaml`
     pattern.
   - **S3:** one OpenTofu-managed bucket in eu-north-1 with SSE-KMS, Block Public Access, and a
     lifecycle rule equal to the retention window. Access is through EKS Pod Identity, with no
     static keys.
   - Langfuse runs as **web + worker**, both scheduled with requests.
3. **Retention stays ours, rewritten for the new store.** Retention policies are Enterprise-only,
   so `langfuse-retention-cronjob.yaml` (30 days, nightly) is rewritten:
   - it issues ClickHouse `DELETE`s for traces, observations and scores older than the window,
     and a Postgres sweep for what remains there;
   - the S3 lifecycle rule enforces the same window as a backstop;
   - it keeps the existing rule of reporting what it actually freed.
4. **New capability is adopted only where it does not contradict ADR-0148.**
   - **Adopted:** LLM-as-a-judge evaluators on *live* traces as an additional, advisory quality
     signal. They are routed through the gateway like any other model call, on their own key and
     budget, which is a consequence of ADR-0175.
   - **Adopted:** annotation queues for human review of agent outputs.
   - **Not adopted:** Langfuse prompt management or datasets as a source of truth. Any evaluator
     or queue we adopt is first checked to work on an instance with **no** license key; if it
     needs one, it is dropped rather than licensed.
5. **PII handling does not rely on Langfuse.** Server-side masking is Enterprise-only, so the
   control is upstream: the gateway's Presidio guardrail (#11756, #11759) masks before both the
   provider and the trace callback. That ordering is verified end to end in #11759, which showed
   the logged messages already carry the placeholders.

## Alternatives considered

- **Stay on v2 and accept the risk.** Pros: zero work, no new state. Cons: an internet-adjacent
  web UI holding prompts and completions, with no upstream fixes since 2025-11-07, and the gap
  only grows. Rejected: an unpatched store of conversational PII contradicts the posture the
  gateway's controls exist to keep.
- **Drop Langfuse and trace LLM calls into Tempo through OpenTelemetry GenAI semantic
  conventions.** Pros: no new stateful systems, and it reuses the existing Tempo/Grafana
  stack. Cons: prompt and completion bodies would land in the general trace store, with its
  wider readership and retention, and there is no LLM review UI, no evaluator and no annotation
  workflow. Rejected: it moves sensitive content into a less restricted store and loses the
  review capability.
- **Langfuse Cloud (EU region).** Pros: no operations. Cons: prompts and completions leave the
  cluster to another third party, which ADR-0175's residency posture and ADR-0174's register
  would both have to absorb. ADR-0265 already rejected it on the same grounds. Rejected.
- **Share the analytics ClickHouse.** Pros: one fewer StatefulSet. Cons: it mixes a regulated
  analytics warehouse with LLM traces under one volume and one blast radius, and that volume
  has already filled once. Rejected.

## Consequences

**Positive**
- The component holding prompts and completions is back on a patched line.
- LLM-as-a-judge and annotation queues become available for agent-output review, without moving
  any source of truth out of git.
- Trace queries move from Postgres to ClickHouse, which is what Langfuse's UI is now built for.

**Negative**
- Four new stateful pieces to run (ClickHouse, Valkey, S3, worker), each needing backup or
  lifecycle review. S3 is only verified by listing objects (see CLAUDE.md on CNPG backups).
- The retention sweep has to be rewritten and re-proven against ClickHouse.
- A multi-hour background migration, with no upstream rollback procedure. Rollback is "keep v2
  and its database untouched until v4 is verified", which is why v2 is not deleted in the same
  change.

**Neutral**
- Gate: no new CI gate. The decision is enforced by the pinned image. The delivery check below
  is what an audit runs, and the existing `evals-registry-integrity` gate keeps ADR-0148's
  boundary.

### Delivery check

```
git grep -nE 'langfuse/langfuse:2\.' -- openbank-infra   # expect: no output
git grep -nE 'langfuse/langfuse(-worker)?:4\.' -- openbank-infra/gitops/components/ai-platform   # expect: web and worker
kubectl -n ai-platform get statefulset,deploy -l app.kubernetes.io/part-of=ai-platform   # expect: langfuse-clickhouse, langfuse-valkey, langfuse-web, langfuse-worker Ready
aws s3 ls s3://<langfuse-bucket>/ --recursive | head   # expect: event objects, not an empty bucket
```

## Compliance impact

- PCI DSS: not applicable — no cardholder data is in scope of LLM traces (sandbox PANs are synthetic).
- DORA: the ICT third-party register (ADR-0174) is unchanged, since Langfuse stays self-hosted; patch currency of an ICT component improves.
- GDPR: prompts and completions are personal data when customers write them. This ADR keeps them in-cluster, masks them upstream (Presidio guardrail), and keeps a 30-day retention sweep.
- PSD2: not applicable — no payment initiation or account-information interface changes.
- CNB: not applicable — no reporting obligation touched.

## References

- ADR-0265 decision 3 (Langfuse v2) and its v3 alternative
- ADR-0148 (prompt registry and evals gate stay in git)
- ADR-0174, ADR-0175 (gateway egress and residency), ADR-0022 (analytics ClickHouse)
- #11745 (stack feature adoption umbrella), #11756 / #11759 (Presidio guardrail)
- Langfuse: v2→v3 upgrade guide; self-hosting license-key page (read 2026-10-01)
