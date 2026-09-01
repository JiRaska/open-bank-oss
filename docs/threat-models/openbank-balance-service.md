<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — openbank-balance-service

STRIDE/DFD threat model for the balance bounded context, per ADR-0030 D2.
Money-path service. Reviewed in PR; referenced from ADR-0039.

- **Status:** Draft (lightweight, ADR-0039-aware)
- **Last reviewed:** 2026-07-25 (ADR-0178 Phase 3 — reconciliation publishes the future-value-dated
  pipeline; read-only reporting, no write path and no change to the drift arithmetic)
- **Owner:** balance CODEOWNERS
- **Related ADRs:** ADR-0002 (hexagonal), ADR-0017 (Vault), ADR-0018 (OPA authz),
  ADR-0024/0025 (single IBAN + currency pockets), ADR-0034 (OPA unified authz),
  ADR-0039 (balance as projection of ledger), ADR-0160 (sustained-drift alerting),
  ADR-0178 (value-date-correct reconciliation)

## 1. Scope & assets

Under ADR-0039 the ledger is the golden source and **balance is a projection** — the fast,
queryable view of available/booked funds per currency pocket, plus overdraft (N2) headroom.
It is on the money path because **authorization-to-spend decisions** (`BalanceCoverPort`) are made
here: an over-stated balance permits an unfunded debit; an under-stated one wrongly declines a payment.
Per-account, per-currency operations: credit, debit, holds (place/release), initialize.

Assets protected, in priority order:

1. **Balance correctness per currency pocket** — booked + available, the basis of spend authorization.
2. **Overdraft limits (N2)** — per-pocket headroom that bounds permitted negative balance.
3. **Hold records** — placed/released funds reservations.
4. **Reconciliation truth (Phase A)** — per-currency tie-out of balance projection ⇄ ledger
   deposit-control accounts; the control that detects projection drift.
5. **Idempotency of applied movements** — exactly-once application of booked changes.

## 2. Data-flow diagram (textual)

```
                ┌─────────────────── trust boundary: balance-service ───────────────────┐
 [Payment svc]  │                                                                        │
  BalanceCover  │── 1 ─▶ REST / port (BalanceResource, BalanceCoverPort)                 │
  JWT           │            │                                                           │
                │            ▼                                                           │
 [Reader / UI]  │── 2 ─▶ BalanceService (use case) ── overdraft check ──▶ [Postgres] ─3─ │
  JWT           │            │                                  balances, balance_holds   │
                │            ▼                                  reconciliation             │
 [Scheduler] ─4─┼─▶ BalanceReconciliationService ──▶ Ledger REST client ──▶ [ledger-svc] │
                │      (per-currency tie-out)        balance_outbox ──▶ [Kafka]          5│
                └────────────────────────────────────────────────────────────────────────┘
```

Trust boundaries: (1) caller → REST/port (mTLS + OIDC + OPA); (3) service → Postgres;
(4/5) service → Kafka / ledger-service REST.
Domain layer has zero framework imports (ADR-0002); overdraft + reconciliation math are unit-testable.

## 3. Authn / Authz

- Service-to-service callers authenticated (mTLS + OIDC); OPA policy gates credit / debit / hold (ADR-0034).
- Money-moving endpoints are role-gated: `@RolesAllowed(SERVICE, OPERATOR, ADMIN)` on
  credit/debit/hold/initialize, supervisor/admin on overdraft-limit override; **no endpoint is
  `@PermitAll`** (locked by `BalanceResourceSecurityTest`). On top of role gating, every endpoint now
  carries `@Authorize` (OPA, ADR-0034 Phase 5, issue #266) and `AUTHZ_ENFORCE=true` is set in gitops —
  a denied decision now 403s instead of only logging (advisory). **Residual risk:** every verified
  in-repo M2M caller (settlement-service, transaction-service, account-service, statement-service,
  billing-service, agent-service) authenticates as the same `openbank-services` client, so OPA's
  `input.principal` cannot distinguish "settlement-service asking for credit" from "any other
  ROLE_SERVICE caller asking for credit" — the SERVICE rule is scoped to the action-class the
  verified callers collectively need (no blanket allow across every balance action), but not to the
  specific caller. Tightening this needs per-caller identity (mTLS SPIFFE, ADR-0017, or a dedicated
  OIDC client per caller) — tracked as a follow-up, not solved here.
- Four-eyes approval-decide endpoint: same role set as the gated actions (`OPERATOR`/`ADMIN`),
  plus a domain-level segregation-of-duties check (checker id != maker id) — see §4a.

## 4. STRIDE analysis

| # | Element | Threat (STRIDE) | Mitigation | Residual |
|---|---------|-----------------|------------|----------|
| S1 | REST/port in | **Spoofing** — unauthenticated caller posts a credit/debit, or forges identity | mTLS + OIDC; reject anonymous; bearer JWT (Keycloak); role-gated mutations; OPA fine-grained authz (ADR-0018/0034) now **enforced** on every balance endpoint | Cannot distinguish SERVICE callers beyond ROLE_SERVICE (see §3 residual risk) |
| T1 | Cover check | **Tampering** — manipulated request authorizes an unfunded debit / negative balance | Server-side overdraft evaluation against stored limit; available = booked − holds + overdraft; optimistic locking / row versioning; per-currency rows; pure-domain, unit-tested | Low |
| T2 | Balance rows | **Tampering** — direct DB mutation desynchronizes from ledger | App-only write path; DB creds in Vault (ADR-0017); **Phase A reconciliation** detects drift vs ledger deposit-control per currency | Drift detected, not prevented — by design (projection); Phase D cutover hardens |
| R1 | Movements | **Repudiation** — actor denies a balance change it applied | AuditEvent per credit/debit/hold; movements carry origin/actor + idempotency key; outbox event with correlation id; reconciliation run timestamped/persisted | Strengthen with signed audit (ADR-0029) — *planned* |
| I1 | Reads | **Information disclosure** — balance harvesting across accounts/pockets | AuthZ scoped to account owner/role; per-account server-side scoping; no bulk export/enumeration; JWT required. **A1 (issue #628):** `X-Customer-Party-Id` triggers M2M ownership lookup via `AccountServiceClient`; mismatch → 404 (existence oracle protection). OPA read-path (`balance.read`) now enforced | Low |
| I2 | Domain metrics | **Information disclosure** — domain metrics leak PII / enable per-account inference via high-cardinality labels | `DomainMetrics` low-cardinality contract (ADR-0077): the outbox-backlog gauge (`openbank.outbox.backlog`) is tagged only by `service="balance"` — never an account id, IBAN, currency-pocket value, balance, or party id. The gauge reads a read-only `count(*)` of PENDING/FAILED outbox rows refreshed off the Prometheus scrape thread by a scheduled tick (no per-scrape reactive query); `/q/metrics` is cluster-internal | Low |
| D1 | Reconciliation / writes | **DoS** — hold exhaustion / write storm / expensive tie-out scans | Rate limits; idempotency drops retries; per-currency aggregation; scheduled reconciliation cadence; reactive non-blocking stack | Gateway rate-limit — infra scope |
| E1 | Roles | **Elevation** — read role triggers a debit / raises own overdraft | Deny-by-default; explicit role for money movement; cover check cannot mutate; OPA now enforced (a viewer/read-only reason never grants `balance.credit`/`balance.debit`) | Low |
| T3 | Ledger client | **Tampering / spoofing of source** — projection trusts a forged ledger response | Authenticated ledger-service inside trust mesh; reconciliation compares against ledger as golden source, flags mismatch | mTLS/service-identity hardening — infra scope |

## 4a. Four-eyes approval (ADR-0155) — STRIDE supplement

Both `POST /{accountId}/credit` (`balance.credit`) and `POST /{accountId}/debit`
(`balance.debit`) are money-path actions OPA (`rest.rego`) can flag `four_eyes_required`. New
endpoint `PATCH /api/v1/balances/approvals/{id}` lets a DIFFERENT operator decide the resulting
`PendingApproval`; the maker retries the original credit/debit call with an `X-Approval-Id`
header. A single decide endpoint handles approvals for BOTH gated actions — `ApprovalStore.decide`
resolves by approval id regardless of which action created the pending record. Note both actions
also admit `ROLE_SERVICE` (M2M) callers, same as today — this mechanism does not change that;
**`authz.four-eyes.enforce` stays `false` in this PR** — the `ApprovalStore`/endpoint are wired
(mirroring the account-service rollout, issue #413), but blocking is a deliberate follow-up flip,
not bundled here (see ADR-0155).

This is also balance-service's **first** Redis dependency: a new namespace-local `redis`
Deployment/Service (`openbank-infra/gitops/components/balances/redis.yaml`) backs the
`RedisApprovalStore` producer — the service's existing money-movement idempotency (V8
`balance_movement` dedup ledger) stays DB-based and is untouched by this change.

| STRIDE | Threat | Mitigation |
|---|---|---|
| **S**poofing | A caller other than an operator decides an approval | `@RolesAllowed(Roles.OPERATOR, Roles.ADMIN)` + OPA `@Authorize(action="balance.approval.decide")` on the decide endpoint |
| **E**oP | The maker approves their own credit/debit request (self-approval defeats maker-checker) | `ApprovalStore.decide` throws `SelfApprovalNotAllowedException` (mapped to 403) when `decidedBy == makerId` — enforced in the domain port itself, not just the REST layer, and `makerId`/`decidedBy` both resolve via the same `.principal.name` extraction (interceptor vs. `SecurityIdentity`) so the comparison can't silently mismatch for the same real person |
| **T**ampering | A stale, mismatched, or already-consumed `X-Approval-Id` is replayed to unlock a different request | `AuthorizeInterceptor` requires the approval's `action` + `resourceId` + `makerId` to match the CURRENT request exactly, `status == APPROVED`, and marks it `EXECUTED` (one-time use) on success; any mismatch re-issues a fresh pending approval instead of proceeding |
| **R**epudiation | No record of who approved a gated credit/debit | `PendingApproval.decidedBy` + `decidedAt` recorded in the approval record itself (Redis, TTL-bounded — see ADR-0155 Negative consequences: not yet a permanent audit trail) |
| **I**nfo disclosure | Approval id enumeration reveals account/action metadata to an unauthorized caller | `find`/`decide` require the caller to already hold a valid, role-gated session; the id itself is a random id (`RedisApprovalStore`, not sequential) |
| **D**oS | Flooding `POST /{accountId}/credit`\|`/debit` to exhaust Redis with pending approvals | Bounded by the same rate-limit/idempotency controls as the gated endpoints themselves; each `PendingApproval` is TTL-bounded (86400s) so abandoned records expire |

**DFD update:** adds `Operator (checker) → PATCH /api/v1/balances/approvals/{id} → Redis
(approval:*)` alongside the existing `POST /{accountId}/credit`|`/debit` edges; the maker's retry
reuses the existing DFD edges. New same-namespace-only trust boundary: `balance-service pod →
redis.balances.svc:6379` (NetworkPolicy `redis-ingress-allow-list`, in-namespace callers only).
**Risk class:** integrity (segregation of duties) + confidentiality (approval record scope).
**Rollback:** `authz.four-eyes.enforce=false` (default) — the endpoint and store exist but do not
change any existing request's outcome until explicitly flipped; the Redis deployment itself can
also be deleted (nothing else in balance-service depends on it).

## 5. Key invariants (must never regress)

- A debit is authorized only if `available ≥ amount` where `available` includes the configured
  overdraft headroom for that currency pocket — never beyond it.
- Balance is a **projection**: on divergence, the **ledger wins** (ADR-0039); reconciliation must flag,
  never silently "fix" toward the projection.
- **Idempotency is critical** — a duplicate credit/debit on retry must be impossible. Enforced on the
  direct path by the `balance_movement` dedup ledger (PK `(account_id, currency, reference_id,
  operation)`, V8), whose marker insert and balance mutation share one transaction — so a Kafka
  at-least-once redelivery or a saga retry that replays a referenceId is a no-op. Mirrors
  `ledger_projection_event` for the projection path (ADR-0039 Phase D).
- Reconciliation ties out **per currency** against ledger deposit-control accounts (2100–2103).
- Reconciliation compares **on the ledger's value-date basis** (ADR-0178): the sub-ledger figure is the
  materialized booked balance minus the future-value-dated tail (`Σ bookedAmount − Σ delta with
  entry_date > asOf`), mirroring the ledger trial balance's `entry_date <= :asOf`. A future-value-dated
  journal (booked now, effective later) must be excluded on **both** sides until its value date, or the
  control raises a self-resolving false drift for the whole pre-value-date window — masking a genuine
  mismatch and eroding trust in the control (a value-date gap is *sustained*, so the ADR-0160 `for:`
  dampener does not contain it). Anchoring on the materialized `balances` (not on the audit sum) keeps
  the tie-out able to catch a `balances`⇄projection-audit desync — it is not blind to a broken write
  path (T2).
- **The future-value-dated pipeline is published, never subtracted** (ADR-0178 Phase 3). The
  reconciliation reports `futureValueDatedPipeline` per currency — the same `Σ delta with
  entry_date > asOf` tail the value-date basis already excludes — so an operator sees the expected
  upcoming movement instead of an unexplained gap between the tie-out figure and the raw materialized
  total. It must stay **read-only and out of the drift arithmetic**: both sides of the tie-out already
  exclude that tail, so folding it into `difference` would double-count and re-create the false drift
  Phase 1 removed, while *subtracting* it from a genuine mismatch would let a real divergence hide
  behind an outstanding pipeline. The remaining `difference` is therefore UNEXPLAINED drift by
  construction — that, plus the ADR-0160 sustained-duration `for:` clause, is the alerting signal.
  Locked by `BalanceReconciliationServiceTest` (a purely future-value-dated batch raises zero drift; a
  genuine shortfall still drifts while a pipeline is outstanding) and by `BalanceReconciliationAsOfIT`
  against a real database.

## 6. Open items / follow-ups

- ~~Confirm authz annotations vs OPA coverage on money-moving endpoints (see §3 finding); enforce
  OPA authz on balance read + write + cover paths (ADR-0034) — currently advisory.~~ **Done** (ADR-0034
  Phase 5, issue #266): every endpoint carries `@Authorize`, `AUTHZ_ENFORCE=true` in gitops. Follow-up:
  per-caller SERVICE identity so OPA can distinguish settlement-service from other M2M callers on
  `balance.credit`/`balance.debit` (see §3 residual risk).
- Signed audit / evidence bundle (ADR-0029 D2) for movement non-repudiation.
- ~~Phase D: cut the projection over to ledger-emitted `AccountBookedChangedEvent` (ADR-0039),
  retiring any independent balance write path.~~ **Done 2026-06-17** (Phase D-2): projection enabled
  as the sole booked-mover; the transaction saga's direct debit/credit is removed (see change log).
- Mutation testing (pitest) on overdraft + reconciliation math (ADR-0030 D3).
- Four-eyes now has the *mechanism* wired (§4a) but not enforced (`authz.four-eyes.enforce=false`)
  on `balance.credit`/`balance.debit` — flipping it is a deliberate follow-up once the
  maker/checker runbook is reviewed, tracked under issue #413.

## 7. Change log

- **2026-09-01** — The operator approval inbox gains a bounded, read-only
  `GET /api/v1/balances/approvals` edge (issue #5679, ADR-0227 D2). It returns only the pending
  approval id, action, resource id, maker id and creation time, to callers already holding
  `ROLE_OPERATOR` or `ROLE_ADMIN`, behind the same OPA boundary as the checker decision endpoint
  (`balance.approval.read`, admitted by base `rest.rego`'s `operator-read-any` on the `.read`
  suffix — no policy change). Results are capped at 200 and ordered oldest first; the endpoint
  cannot approve, reject or execute anything, and `ApprovalStore.decide`'s maker != checker
  invariant, the random ids, the 24-hour Redis TTL and one-time `EXECUTED` consumption are all
  untouched. **Risk class:** confidentiality of operator workflow metadata (account ids of parked
  credit/debit requests become visible to any operator, not only the one holding the id out of
  band) plus a bounded Redis SCAN load. No new principal, no service-to-service edge, no money
  mutation. The countervailing risk it removes is the larger one: until now a parked
  `balance.credit`/`balance.debit` decision was findable only by whoever had been handed its id,
  and expired silently after 24 hours — a maker-checker control nobody can see is a control that
  fails open by attrition. Deliberately NOT added: a decide button on the inbox — money-path
  disposal additionally requires SCA (ADR-0227 D4) and stays on the per-domain flow. Rollback:
  remove the GET route; the decision path is unaffected and the admin UI reports the balance
  source unavailable.

- **2026-08-24** — Synthetic-journey taint now propagates over this service's existing internal REST clients through `SyntheticTaintClientFilter` (ADR-0252, #4348). This adds no caller, endpoint, network-policy edge, privilege or control bypass: downstream controls still see the journey. It prevents synthetic activity from becoming indistinguishable before a downstream persistence/event boundary; a fleet gate now requires every new client to choose propagation or a reasoned external boundary.

- **2026-08-05** — Prohibit the customer-edge M2M principal from balance writes (#3734). The
  `operator-balance-write` ext rule was role-only, and `rules.yaml`'s `role_action_matrix` grants
  the balance writes (`hold`/`holdRelease`/`credit`/`debit`/`initialize`/`reconciliation.run`) to
  `ROLE_OPERATOR`. Two realm service accounts carry that role and are classified HUMAN by
  `AuthorizeInterceptor`: `service-account-openbank-services` (shared backend client — a
  legitimate writer, graduated via `service-balance-m2m` + `shared_m2m_write_prohibition`) and
  `service-account-openbank-edge` — customer-facing, and since 2026-08-02 genuinely reachable from
  the public edge (the entry above). A role-only rule plus the matrix grant therefore handed the
  *internet-reachable* edge identity the fleet's money-moving primitives (`balance.credit` /
  `balance.debit`) — the exact escalation class fixed for interest in #3698. Fleet caller audit:
  customer-edge only ever GETs `/api/v1/balances/{accountId}` (three call sites in
  `CustomerEdgeResource.kt`); no edge write path exists. The tightening is two-layered:
  `operator-balance-write` and `supervisor-overdraft-limit` now exclude every `service-account-*`
  principal, AND an edge-scoped `prohibited` clause vetoes all seven write actions at the allow
  head — so neither the matrix grant nor any future reason can admit the edge to a write. The
  veto is deliberately edge-scoped, not interest's all-service-accounts shape: the shared client
  IS a legitimate writer here. Reads untouched. Falsified by `balance_rest_ext_test.rego`
  (stripping either layer turns 7 of 12 tests red); the ext moved from a generator heredoc to a
  standalone `balance_rest_ext.rego` so `opa test` can load it (interest/delegation pattern).
  Rollback: revert the ext to the pre-#3734 shape — no live caller is lost, as no edge write
  path exists.
- **2026-08-02** — **balance-service is now genuinely reachable from the public edge**, and the
  honest framing is that the *intent* did not change while the *reality* did. `accounts/accounts-api`
  had declared `api.open-bank.tech/api/v1/balances` since the Ingress was written, so the exposure was
  always designed and threat-modelled as public — but an Ingress backend resolves only inside its own
  namespace, and it named `balance-service` in `accounts`, where no such Service exists. The path
  returned **503 for ~60 days** and nginx logged `no object matching key "accounts/balance-service"`
  ~1,700×/hour. The route now lives in `components/balances/ingress.yaml` beside the Service it
  targets (the shape `payments-api`/`sanctions-api` already use on this host).
  **Trust-boundary delta:** `balance-service-ingress-allow-list` gains one derived rule,
  `TCP:8103 FROM ingress-nginx` — the first non-`openbank-*` namespace admitted to the service port.
  Without it the fix would have swapped the 503 for a hang, so it is load-bearing, not incidental;
  it was produced by `gen-network-policies.py` (which derives ingress-nginx edges from Ingress
  backends) rather than hand-written, and no other component's policies moved.
  **Why the residual risk does not move:** every §4 mitigation on S1/I1/E1 is authn/authz at the
  application layer — OIDC bearer JWT, role-gated mutations, OPA enforced on every endpoint, and the
  A1 `X-Customer-Party-Id` ownership scoping — none of which depended on the caller being in-cluster.
  This is the same posture `account-service` already carries on the same host and the same Ingress
  class. D1 (DoS) is the one row that changes in practice rather than in principle: the new Ingress
  carries the same `limit-rps: 20` / `limit-burst-multiplier: 3` / `limit-connections: 10`
  annotations as its three siblings, deliberately identical so one host does not present four
  different limits depending on which path a client happens to hit. The pre-existing
  "Gateway rate-limit — infra scope" residual is now actually exercised instead of theoretical.
  **What this does NOT change:** no application code, no schema, no role, no OPA policy, no
  in-cluster caller. Rollback is deleting `components/balances/ingress.yaml` and re-running the
  NetworkPolicy generator; the path returns to 503, which is where it has been all along.

- **2026-07-12** — Wired the four-eyes (maker-checker) enforcement *mechanism* (ADR-0155) onto
  `balance.credit`/`balance.debit`, mirroring the account-service rollout (issue #413). New
  `ApprovalConfig` (`RedisApprovalStore` producer) and `PATCH /api/v1/balances/approvals/{id}`
  checker-decide endpoint (`@RolesAllowed(Roles.OPERATOR, Roles.ADMIN)`, `@Authorize(action =
  "balance.approval.decide")`); two new exception mappers (`SelfApprovalNotAllowedMapper` → 403,
  `InvalidApprovalStateMapper` → 409). Also adds balance-service's **first** Redis dependency (a
  new namespace-local `redis` Deployment/Service under `openbank-infra/gitops/components/balances/`
  plus a `redis-ingress-allow-list` NetworkPolicy, same-namespace-only) — the pre-existing DB-based
  movement idempotency (V8 `balance_movement` ledger) is untouched. STRIDE supplement added in §4a
  above. **`authz.four-eyes.enforce` stays `false`** — no behavior change to any existing request;
  this PR only wires the mechanism. Rollback: revert the commit (no DB/schema change on the balance
  schema; `ApprovalStore` records live in the new Redis with a TTL; the Redis deployment can also be
  deleted, nothing else depends on it).
- **2026-06-19** — A1 defense-in-depth (issue #628): added per-account ownership check on `getBalances` /
  `getBalance` via new `AccountServiceClient` (M2M REST call to account-service). When the caller
  supplies `X-Customer-Party-Id` the returned balance is scoped to the requesting party — mismatch
  or unknown account → 404 to deny existence oracle. Operator/service callers without the header are
  unaffected. NetworkPolicy updated to permit `balances→accounts` egress. STRIDE row I1 updated.
- **2026-06-17** — ADR-0039 Phase D-2 cutover: flipped `openbank.balance.projection.enabled` ON.
  The balance `bookedAmount` is now derived **solely** from the ledger's `AccountBookedChanged`
  projection (`LedgerProjectionConsumer` → `LedgerProjectionService`); the transaction saga no longer
  debits/credits balance directly (coupled transaction-service PR). The projection apply is idempotent
  (`ledger_projection_event` dedup on `(journalEntry, account, currency)`) and, as it applies the delta,
  releases the originating payment's cover hold (`referenceId == transactionId`) — this is what closes
  the overspend window during the saga-debit removal (invariant §5). Touches **integrity**: `bookedAmount`
  becomes eventually consistent (projection lag), but overspend stays prevented synchronously by holds
  (the cover decision runs at payment time, not on `bookedAmount`). **Deploy in lock-step** with the
  transaction-service change — running the saga debit and this projection together double-counts the
  booked movement. Rollback: set the flag back to `false` (and re-enable the saga debit). Config-only
  change; no DB/schema/flow/boundary change.
- **2026-06-11** — Added the outbox-backlog gauge (`openbank.outbox.backlog`, tagged only by
  `service="balance"`) + `countProcessable()` on the outbox port (ADR-0077 / ADR-0079). New STRIDE
  row **I2**: domain-metric cardinality. The gauge exposes only a read-only count of processable
  (PENDING + FAILED) outbox rows — never an account id, IBAN, currency-pocket value, balance, or
  party id — and the count is refreshed on a scheduled `suspend` tick (off the scrape thread), so a
  Prometheus scrape never triggers a reactive DB query. **This change also wires Micrometer's
  Prometheus registry** (`quarkus-micrometer-registry-prometheus`): balance-service previously had
  **no MeterRegistry**, so the shared `DomainMetrics` was a silent no-op — the gauge now actually
  emits and the service gains a `/q/metrics` surface (cluster-internal). No new external trust
  boundary, endpoint, or data flow; the metrics surface is internal-only. Risk class =
  **confidentiality** (metric-label leakage), mitigated by the low-cardinality `DomainMetrics`
  contract + `BalanceOutboxBacklogGaugeTest`. No DB or schema change; rollback = revert the commit.
- **2026-06-08** — Closed the credit/debit idempotency gap on the direct money-movement path
  (`BalanceUseCase.credit/debit`, used by the transaction saga; surfaced by the welcome-bonus work,
  which newly exercises the credit leg). The path was idempotent only at the saga entry point; a
  Kafka redelivery or a saga retry after a crash between the COMPLETED write and the event ack could
  replay a referenceId and double-apply. Added the `balance_movement` dedup ledger (V8) keyed by
  `(account_id, currency, reference_id, operation)` and a `BalanceMovementPort` whose marker insert +
  balance mutation share one transaction (mirrors R1 / invariant §5, and the `ledger_projection_event`
  pattern); the event is published only on first application. Concurrent duplicates are also blocked
  by the PK constraint + the balance row version. Additive DDL only; no new flow/surface/boundary.
  Risk class = **integrity** (double credit/debit on a money-path balance). Rollback:
  `DROP TABLE balance_movement` (only with the credit/debit path quiesced — see migration note).
  Implemented + verified live in PR #590.
- **2026-05-30** — Reframed around ADR-0039 (balance as projection of ledger): added projection/
  reconciliation assets, `BalanceCoverPort` spend-authorization surface, overdraft (N2) invariants,
  ledger-client trust boundary. No new runtime surface — documentation/governance pass.
- **2026-05-30** — Added `balances_seq`, `balance_holds_seq`, `balance_outbox_seq` (Hibernate fix);
  added `assertj` test dep for the guard. Additive DDL only — no new flow/surface/boundary.
  Risk class = **availability** (missing sequence breaks all balance writes), mitigated by
  `HibernateSequenceGuardTest`. Rollback: `DROP SEQUENCE`.
