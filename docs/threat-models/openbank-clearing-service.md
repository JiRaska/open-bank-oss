<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — clearing-service

- **Date:** 2026-05-30
- **Status:** Lightweight STRIDE/DFD (ADR-0030 D2). **Money-path** bounded context.
- **Service ADR:** see `docs/adr/`; platform controls per ADR-0029/0030/0034.

## 1. Scope & purpose

Payment clearing and settlement: batch submission, cycle triggering, settlement position
management, item lifecycle. Aggregates many payments into settlement — high blast radius.

## 2. Data flow (DFD)

```
[Payment services] --> (REST /api/v1/clearing/submit) --> [clearing-service] --> [(Postgres: batches, items, positions)]
[Operator] --> (cycle/trigger, settle) ----------------------^                       |
                                                                                     +--> [(clearing_outbox)] --> [Kafka settlement events]
```

- **External entities:** payment services (submit items), operators (trigger cycle / settle).
- **Trust boundaries:** caller↔service (mTLS+OIDC+OPA); service↔Postgres; service↔Kafka.
- **Assets:** clearing batches, settlement positions, cycle state.

## 3. Authn/Authz

- The prior class-level `@PermitAll` was replaced with per-operation least-privilege roles (K7 /
  ADR-0018): submit is service/payment-ops, reads are payment-ops/viewer/operator, and **settle +
  cycle/trigger are restricted to `@RolesAllowed(PAYMENTS, ADMIN)`** (locked by
  `ClearingSecurityContractTest`). `settle` additionally carries `@Authorize(clearingBatch.settle)`
  (OPA, ADR-0034) in **advisory** mode, graduating to enforce in Phase 5.
- Four-eyes approval-decide endpoint: same role set as the gated `settle` action, plus a
  domain-level segregation-of-duties check (checker id != maker id) — see §4a.

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing | Forged submit from non-payment caller | mTLS service identity allow-list |
| **T**ampering | Alter batch items / settlement position | Immutable items once cycle starts; position recomputed, not client-supplied; audit |
| **R**epudiation | Deny triggering a settlement cycle | AuditEvent on submit/trigger/settle with actor |
| **I**nfo disclosure | Cross-institution position leakage | AuthZ scoping; positions keyed by cycle, access-controlled |
| **D**oS | Batch flooding delays a cycle | Rate limit submit; bounded batch size |
| **E**oP | Submitter triggers settlement | Distinct role for `cycle/trigger` + `settle`; deny-by-default |

## 4a. Four-eyes approval (ADR-0155) — STRIDE supplement

`POST /batches/{id}/settle` (`clearingBatch.settle`) is the money-path action this rollout
targets (issue #413). New endpoint `PATCH /api/v1/clearing/approvals/{id}` lets a DIFFERENT
operator decide the resulting `PendingApproval`; the maker retries `POST
/batches/{id}/settle` with an `X-Approval-Id` header. **`authz.four-eyes.enforce` stays
`false` in this PR** — the `ApprovalStore`/endpoint are wired, but blocking is a deliberate
follow-up flip, not bundled here (see ADR-0155; also note `rules.yaml`'s `clearingBatch.*`
→ `clearing` scope normalisation, tracked separately in issue #395/#396, gates when
`four_eyes_required` can actually auto-fire from OPA for this rail).

| STRIDE | Threat | Mitigation |
|---|---|---|
| **S**poofing | A caller other than payment-ops/admin decides an approval | `@RolesAllowed(Roles.PAYMENTS, Roles.ADMIN)` + OPA `@Authorize(action="clearingBatch.approval.decide")` on the decide endpoint |
| **E**oP | The maker approves their own settlement request (self-approval defeats maker-checker) | `ApprovalStore.decide` throws `SelfApprovalNotAllowedException` (mapped to 403) when `decidedBy == makerId` — enforced in the domain port itself, not just the REST layer, and `makerId`/`decidedBy` both resolve via the same `.principal.name` extraction (interceptor vs. `SecurityIdentity`) so the comparison can't silently mismatch for the same real person |
| **T**ampering | A stale, mismatched, or already-consumed `X-Approval-Id` is replayed to unlock a different batch settlement | `AuthorizeInterceptor` requires the approval's `action` + `resourceId` + `makerId` to match the CURRENT request exactly, `status == APPROVED`, and marks it `EXECUTED` (one-time use) on success; any mismatch re-issues a fresh pending approval instead of proceeding |
| **R**epudiation | No record of who approved a gated settlement | `PendingApproval.decidedBy` + `decidedAt` recorded in the approval record itself (Redis, TTL-bounded — see ADR-0155 Negative consequences: not yet a permanent audit trail) |
| **I**nfo disclosure | Approval id enumeration reveals batch/action metadata to an unauthorized caller | `find`/`decide` require the caller to already hold a valid, role-gated session; the id itself is a random UUID (`RedisApprovalStore`, not sequential) |
| **D**oS | Flooding `POST /batches/{id}/settle` to exhaust Redis with pending approvals | Bounded by the same rate-limit/idempotency controls as the gated endpoint itself; each `PendingApproval` is TTL-bounded (86400s) so abandoned records expire |
| **I**nfo disclosure | (issue #5679) `GET /api/v1/clearing/approvals` lists every pending four-eyes request with its `makerId` and age | Same role gate as `decide` (`@RolesAllowed(Roles.PAYMENTS, Roles.ADMIN)` + OPA `@Authorize(action="clearingBatch.approval.read")`); verified with a real `opa eval` that `clearingBatch.approval.read` resolves `allow=true` for ROLE_OPERATOR/ROLE_ADMIN/ROLE_PAYMENTS via the existing `operator-clearing-write` prefix rule and `allow=false` for ROLE_VIEWER — no rules.yaml change needed. The payload carries approval metadata only — action, resource id and who asked — never batch contents. Limit clamped to 200 — an unbounded query parameter over a Redis scan is a trivially reachable amplification. Deliberately NOT filtered to exclude the caller's own requests: hiding a maker's request from them would not stop them attempting it (the guard is in `RedisApprovalStore.decide`, server-side) and would only make the queue lie about its own depth |

**DFD update:** adds `Operator (checker) → GET /api/v1/clearing/approvals → Redis
(approval:*)` and `Operator (checker) → PATCH /api/v1/clearing/approvals/{id} → Redis
(approval:*)` alongside the existing `settle` edge; the maker's retry reuses the existing DFD
edge.
**Risk class:** integrity (segregation of duties) + confidentiality (approval record scope).
**Rollback:** `authz.four-eyes.enforce=false` (default) — the endpoint and store exist but do
not change any existing request's outcome until explicitly flipped.

## 5. Residual risks / assumptions

- **Double-settlement** must be impossible — idempotent cycle/settle keyed by cycle id.
- Consider four-eyes (MakerChecker, ADR-0034) for `settle`.
- Graduate OPA authz from advisory to enforce (Phase 5) so `@Authorize` denies are blocked, not just logged.
- **Four-eyes `PendingApproval` records are TTL-bounded (Redis), not a permanent audit
  trail** (ADR-0155) — a durable-audit requirement for "who approved what, forever" would
  need an additional store; not implemented in this PR.

## 6. Change log

- **2026-09-26** — **AuthzProducer replaced by the shared libs-runtime OPA PDP producer** (PR
  #10952). The service-local `infrastructure/authz/AuthzProducer.kt` is deleted;
  `application.yaml` now sets `openbank.authz.opa-pdp-producer.enabled: true` to opt into
  `OpaPolicyDecisionPointProducer` (openbank-libs-runtime), gated by that build
  property (`enableIfMissing = false`), so the wiring stays off for any service that does not set
  it. The producer is NOT `@DefaultBean`: if this service's own `src/main` ever produces a second
  `PolicyDecisionPoint` bean, `quarkusBuild` fails loudly on an ambiguous CDI dependency instead of
  one silently displacing the other. Same `opa.url`/`opa.path`/`opa.timeout-ms` defaults as the deleted producer
  (`http://localhost:8181`, `/v1/data/openbank/rest/allow`, 500 ms) and the same fail-closed
  behaviour on OPA sidecar failure — `OpaSidecarPolicyDecisionPoint` itself is unchanged, only its
  construction site moved from a per-service copy to the shared producer. No new caller, endpoint,
  network edge or privilege; no new trust boundary.

- **2026-09-25** — **Transport control tightened: OIDC TLS verification is `required` outside `%dev` (#10865).** `quarkus.oidc(-client).tls.verification: none` sat at the top level of `application.yaml`, so it applied to `%prod` too; inert while the in-cluster Keycloak leg is plain http, it would have skipped certificate and hostname validation of the token issuer / JWKS the moment that leg moved to https (Spoofing of the IdP). It now lives under `"%dev":` only, and gate `oidc-tls-verification-profile-scoped` keeps it there.

- **2026-09-14** — Clearing acknowledgement evidence (ADR-0306): every item transition increments
  its persisted `aggregate_revision`, and batch settlement commits one
  `openbank.clearing.item.cleared` outbox row per settled item in the same database transaction as
  the batch, item rows and net-settlement command. The payload exposes only existing opaque item,
  payment and batch references, status, currency, amount and revision; context-service minimizes
  this further to references and a bounded label. Risk class = integrity and bounded
  confidentiality. `V10` is additive; rollback is to stop consuming the field/event and leave the
  column in place, avoiding a destructive down migration. Availability is bounded by the existing
  1,000-item cycle ceiling plus a 250-row atomic outbox claim every 2 seconds: one maximum cycle is
  four claims rather than forty default claims, while each claim remains bounded and cross-pod safe
  through `FOR UPDATE SKIP LOCKED`. The 30-second scheduler timeout remains the overload fuse;
  rollback is a configuration-only reduction of `openbank.outbox.batch-size`.

- **2026-09-02** — Doc correction, no behavior change: §3 credited the role-gating regression guard
  to `ClearingResourceSecurityTest`, a class that is in no Kotlin source in this repository. **The
  guard is real** and is `ClearingSecurityContractTest`, which asserts by reflection that
  `ClearingResource` carries no class-level `@PermitAll`, that every HTTP endpoint on it is
  `@RolesAllowed` and never `@PermitAll`, and — matching the claim in §3 exactly — that `settleBatch`
  and `triggerCycle` resolve to exactly `ROLE_PAYMENTS` + `ROLE_ADMIN`. Only the name was wrong; the
  access-control contract described in §3 is in place and locked. The same stale name is corrected in
  the `ClearingResource` KDoc in this change. No DB, schema, endpoint or policy change.

- **2026-05-30** — Added `clearing_outbox_seq` (Hibernate fix). Additive DDL only — no new flow/
  surface/boundary. Risk class = **availability**, mitigated by `HibernateSequenceGuardTest`.
  Rollback: `DROP SEQUENCE`.
- **2026-07-08** — ADR-0155 four-eyes enforcement rollout (issue #413), mirroring the
  sepa-payment pilot. New endpoint `PATCH /api/v1/clearing/approvals/{id}` +
  `ApprovalConfig` (Redis-backed `ApprovalStore`) + `AuthorizeInterceptor` four-eyes gate
  (openbank-libs-runtime, shared, opt-in) on `clearingBatch.settle`. New STRIDE supplement
  §4a. `authz.four-eyes.enforce` defaults `false` — no behavior change to any existing
  request in this PR; flipping it is a tracked follow-up. No DB schema change (Redis,
  TTL-bounded); rollback = revert the commit (or leave `authz.four-eyes.enforce=false`, its
  default).
- **2026-08-19** — `ApprovalResource` served only `PATCH /{id}` (decide), so a
  `clearingBatch.settle`/`clearingBatch.triggerCycle` four-eyes decision parked at 202 was
  discoverable only by whoever had been handed its approval id out of band — the ceremony
  completed only if the two operators were already talking, and the 24h Redis TTL then expired
  the request silently otherwise (issue #5679, mirroring sanctions #3472, lending, ledger and
  balance). Added `GET /api/v1/clearing/approvals` (§4a new I row); no new trust boundary
  crossed — same `RedisApprovalStore`, same role gate shape as the existing decide endpoint,
  additive-only OpenAPI change (1.2.0 -> 1.3.0, ADR-0048). Verified with a real `opa eval`
  against the regenerated `clearing-opa-bundle.yaml` that the existing `operator-clearing-write`
  prefix rule (ROLE_OPERATOR/ROLE_ADMIN/ROLE_PAYMENTS) already covers the new
  `clearingBatch.approval.read` action with no `rules.yaml` change — unlike balance-service
  (#5690), which needed a `role_action_matrix` entry because its authz shape is matrix-based
  rather than prefix-based.
- **2026-09-04** — ADR-0281 net-settlement ledger leg (issue #8361). `settleBatch` now commits a
  second outbox row (`openbank.clearing.net_settlement.post`) atomically with the batch flip, and
  `NetSettlementPostingConsumer` posts the balanced DEBIT cash-clearing / CREDIT scheme-settlement
  journal to ledger-service with idempotency key `clearing-net-settlement-{batchId}`. New trust
  boundary crossed: clearing-service -> ledger-service `POST /api/v1/journals` (OidcC client-
  credentials, SyntheticTaint header filter) — journal content is server-derived from the settled
  batch row, not caller input, so the injection surface is the batch's own validated amounts.
  Failure mode by design: retry with backoff, then DLQ
  `openbank.dlq.clearing-service.clearing-net-settlement-in` (nested-YAML topic + KafkaTopic CR +
  KafkaUser Write ACL in the same change — a rethrow without any of the three wedges the channel,
  #5745). A DLQ record means "batch SETTLED, journal not booked" — reconciliation alert, manual
  re-drive; the ledger idempotency key makes replay collapse onto the one journal. Reversal of a
  settled batch stays a manual reversing journal (documented limit, ADR-0281). No DB schema change
  in clearing-service; ledger gains V26 seed accounts (additive). Rollback: revert the commit —
  unsettled batches post nothing; already-committed outbox rows drain or dead-letter harmlessly.
- **2026-09-09** — Submit idempotency (ADR-0298, burn-down #8351): `POST /api/v1/clearing/submit`
  dedups on the payment natural key — a retry replays the existing item check-first, and V9's
  `uq_clearing_items_payment` unique index backstops the true-concurrency race (the loser
  re-reads the winner). The closed threat is a double-settlement reachable from one transport
  retry: previously a retried submit stacked a second PENDING row that the clearing cycle swept
  into a batch. No new caller, route, role or endpoint shape; the response for a retry is the
  original item, so nothing downstream can distinguish replay from first submit. Rollback:
  revert the commit and `DROP INDEX IF EXISTS uq_clearing_items_payment` — pre-duplicate data
  must be cleaned before V9 (detection query in the migration).

- **2026-09-20** — **New outbound edge: clearing-service → ledger-service over ledger's new
  private-CA mTLS listener** (8443, client auth REQUIRED, TLSv1.3; client cert
  `clearing-internal-tls`, `%prod` TLS bucket `ledger-authority`). The ADR-0281 net-settlement
  journal posting had no `LEDGER_SERVICE_URL` in this service's gitops manifest, so
  `ClearingLedgerRestClient` used the `application.yaml` fallback `http://localhost:8101` inside its
  own pod: every `POST /api/v1/journals` was a connection refused and the net-settlement leg never
  reached the book of record. The action (`ledger.create`) and the identity (the shared
  `service-account-openbank-services` bearer minted by `OidcClientRequestReactiveFilter`) are
  unchanged — `ledger_rest_ext.rego`'s `service-ledger-post` already admits them, so no policy
  change. **Risk class:** integrity/availability of net-settlement journal posting (a money-path
  write that has never landed now lands); the new material is a private-CA client key mounted
  read-only from a cert-manager Secret, scoped to this one upstream. No inbound surface, no new
  role, no new data class. Rollback: drop the env var and the `%prod` bucket.
- **2026-09-21** — **Own machine identity for the net-settlement journal (#10486 batch 1).** `ClearingLedgerRestClient` now mints its bearer from the NAMED oidc-client `m2m`, Keycloak client `openbank-clearing` (`ROLE_API` only); ledger-service grants it exactly `ledger.create` (`service-clearing-ledger-post`). **STRIDE-S:** a new credential. Its secret is generated by Keycloak in the live realm, stored by the owner's provisioning script at Vault KV `keycloak/clearing-service` (`client_secret`), projected by the `clearing-service-m2m-oidc` ExternalSecret and never seen by the repo; the env ref is `optional: false`, so an unseeded entry blocks the new pod loudly (CreateContainerConfigError) rather than calling with an empty credential. Compromise of this secret reaches only `ledger.create`, against the shared secret's 54 money-path writes. **Repudiation improves:** the upstream's OPA decision reason and principal now name this service instead of "some caller on the shared client". The service's other rest-clients stay on the shared `openbank-services` client until their own edges migrate (asserted by `M2mOidcClientIdentityWiringTest`). Rollback: revert the commit (the clients return to the shared token).
