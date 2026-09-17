# Threat model — statutory representation policy store (ADR-0284, #10247)

Status: pre-rollout evidence store. A policy snapshot is a historical statement about a signed KYB
case, **not** a bearer credential or proof that each mandate is still active.

## Assets and boundaries

- Party-service stores immutable rule revisions: principal, source case and attestation, exact
  quorum, required office combination, verified-register row count, identified human parties,
  source reference and effective time. Names and dates of birth are not copied into this table.
- Only the KYB event topic feeds the representation projection. The consumer checks the event's
  source, signed state, case/entity identity and quorum before accepting the rule. Malformed
  targeted records are retried or sent to a dedicated dead-letter topic, not silently accepted.
- The internal REST read requires an API or operational role. Its response is evidence for a future
  authority resolver, not an admission verdict; no customer-facing route exposes the raw roster.

## Threats and controls

| Threat | Current control | Residual / release gate |
|---|---|---|
| Duplicate or conflicting KYB event creates a second rule | Unique source-case key, deterministic policy id and full immutable-evidence comparison. A conflict is rejected for investigation. | Real Kafka replay and concurrent-delivery tests are still required before rollout. |
| Unknown person or wrong office satisfies JOINT | Domain validation binds each human to distinct verified register rows and matches required offices to distinct signers. JOINT_ALL requires the complete register roster. | At operation opening, intersect that roster with *live* statutory mandates and current KYB attestation; legacy incomplete JOINT rows remain ineligible. |
| Out-of-order case events overwrite a newer rule | Snapshots are append-only; latest-evidence query orders by effective time, not Kafka receipt revision. | A future authority resolver must refuse a stale or superseded attestation, including while a new case is pending. |
| Evidence is silently modified or removed | Database trigger rejects UPDATE/DELETE; application repository exposes insert/read only. | Restrict database credentials and audit privileged maintenance separately. |
| Private representative IDs leak to an ordinary reader | REST route restricts roles; HTTP tests cover denied viewer access and no-evidence 404. | OPA policy and deployed network boundary still need a canary check. |

## Rollback

Stop new policy writes and leave the immutable table for audit. Disable new JOINT admission; never
fall back to an arbitrary `requiredSignatures` count or to any-active-mandate authorization. Drop
the table only after a separately approved retention/archival process, not during image rollback.
