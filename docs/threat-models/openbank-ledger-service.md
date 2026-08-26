<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — openbank-ledger-service

STRIDE/DFD threat model for the general-ledger bounded context, per ADR-0030 D2.
Money-path service. Reviewed in PR; referenced from ADR-0039.

- **Status:** Draft (lightweight, first pass for Phase B)
- **Last reviewed:** 2026-05-31
- **Owner:** ledger CODEOWNERS
- **Related ADRs:** ADR-0002 (hexagonal), ADR-0017 (Vault), ADR-0018 (OPA authz),
  ADR-0039 (ledger as golden source, balance as projection),
  ADR-0050 (regulatory-grade outbox dispatch — N1–N5)

## 1. Scope & assets

The ledger is the **golden source of truth for money** (ADR-0039). The double-entry journal is the
system of record from which all balances are projected. Compromise of its integrity is the highest-impact
failure in the platform.

Assets protected, in priority order:

1. **Journal integrity** — every posting is balanced (Σdebit = Σcredit per base currency) and immutable
   once `POSTED`. Corrections happen only by compensating reversal, never mutation.
2. **GL account structure** — chart of accounts, deposit-control accounts (2100–2103), FX-position
   accounts; their `type`/normal-side semantics.
3. **Sub-ledger dimension** (`subAccountId`, Phase B) — analytická evidence that lets GL control
   accounts tie out per customer (CNB zákon 563/1991 Sb., vyhláška 501/2002 Sb.).
4. **Trial balance / sub-ledger balances** — derived reporting that auditors and reconciliation rely on.
5. **Idempotency + outbox** — exactly-once posting and reliable event emission (ADR-0050).
6. **Frozen period evidence** — line-level statutory source for FINREP/COREP; V22 historical
   close hashes remain evidence anchors but do not contain reproducible lines.

## 2. Data-flow diagram (textual)

```
                     ┌────────────────────── trust boundary: ledger-service ─────────────────────┐
 [Operator / svc]    │                                                                            │
 ROLE_OPERATOR  ──1──┼─▶ REST (LedgerResource)  ──▶  LedgerService (use case)  ──▶  domain model  │
   JWT (Keycloak)    │      @RolesAllowed             validateBalance()             JournalEntry   │
                     │      (reads role-gated)        loadAndValidateGlAccounts()   .reverse()     │
                     │           │                          │                                      │
 [Reader / svc] ──2──┼─▶ GET journals / trial-balance       ▼                                      │
   JWT @RolesAllowed │                              PanacheJournalRepository ──▶ [Postgres]  ──3── │
                     │                                       │                   journal_entries   │
                     │                                       ▼                   journal_lines     │
                     │            ledger_outbox (same tx) ──▶ dispatcher ──▶ [Kafka] ──4──         │
                     │            key=aggregate_id, hdr ce-id/idempotency-key=event.id (ADR-0050)  │
                     └────────────────────────────────────────────────────────────────────────────┘
```

Trust boundaries crossed: (1) external caller → REST; (3) service → Postgres; (4) service → Kafka.
Domain layer has **zero** framework imports (ADR-0002), so business invariants are unit-testable in
isolation from transport/persistence.

## 3. STRIDE analysis

| # | Element | Threat (STRIDE) | Mitigation | Residual |
|---|---------|-----------------|------------|----------|
| S1 | REST in | **Spoofing** — caller forges identity to post journals | Bearer JWT (Keycloak); `@RolesAllowed("ROLE_OPERATOR")` on `postJournal`/`reverseJournal`; reads now `@RolesAllowed(SERVICE, AUDITOR, VIEWER, OPERATOR, ADMIN)` (no longer `@PermitAll` — K7); **OPA fine-grained authz now ENFORCED (ADR-0034 Phase 5, issue #266)** — `AUTHZ_ENFORCE=true` + sidecar deployed; every `@Authorize`-annotated action is deny-by-default unless an explicit `rest.rego`/`ledger_rest_ext.rego` reason fires. M2M rules gate on `input.principal.id == "service-account-openbank-services"` (a `HUMAN`-typed client_credentials identity — `AuthorizeInterceptor` never emits `principal.type == "SERVICE"`, PR #403 / `rules.yaml: authz_policy`), not a role or the unreachable SERVICE type | transaction/lending/settlement/balance-service **all share the single `openbank-services` Keycloak client** (ADR-0104 D3) — OPA cannot distinguish WHICH of the four is calling, only that SOME caller holds that client's token; a compromised credential for that shared client can post (`ledger.create`) and reverse (`ledger.reverse`) — *open, tracked below* |
| T1 | Journal posting | **Tampering** — unbalanced or asymmetric entry corrupts the books | `validateBalance()` enforces Σdebit=Σcredit **per base currency** in the domain; rejects on mismatch; covered by unit tests | Low — invariant is in pure domain |
| T2 | Sub-ledger dim | **Tampering** — `subAccountId` stamped on a non-deposit-control leg, polluting GL tie-out | `loadAndValidateGlAccounts` rejects `subAccountId` on any non-deposit-control account (`isDepositControl`, codes 2100–2103); unit-tested (accept 2100 / reject 1100) | Low |
| T3 | Persisted rows | **Tampering** — direct DB mutation of posted lines | App-only write path; posted entries immutable (corrections via reversal `copy()` preserving dimension); DB creds in Vault (ADR-0017); migrations forward-only Flyway | DB-admin insider — covered by infra controls, out of service scope |
| R1 | All mutations | **Repudiation** — operator denies posting/reversal | `postedBy`/`reversedBy`/`createdBy` captured per entry; `entryNumber` monotone; reversal records `reason`; audit via outbox event stream | Strengthen with signed audit (ADR-0029 evidence bundle) — *planned* |
| I1 | Reads | **Information disclosure** — book-of-record / customer sub-ledger balances leak | Reads **now role-gated** to `SERVICE, AUDITOR, VIEWER, OPERATOR, ADMIN` (K7 — previously `@PermitAll`); `subAccountId` filter is server-side; no cross-customer enumeration endpoint; base `rest.rego` `operator-read-any`/`compliance-read-any` now ENFORCED on every `ledger.read`/`ledger.list` action | Role-coarse — per-tenant scoping still not resource-scoped for reads (no `party-self-service` equivalent on ledger reads; reads are role-gated, not tenant-gated) — *open* |
| I2 | Metrics / observability | **Information disclosure** — domain metrics leak PII or enable per-customer inference via high-cardinality labels | `DomainMetrics` enforces a low-cardinality tag contract: ledger postings are tagged only by `currency` + `type` (`posting`/`reversal`), the outbox-backlog gauge only by `service` — **never** an account id, IBAN, amount, party, or entry id (amounts belong in histograms, not labels). Increments happen **after** the posting commits and only for genuinely new entries (idempotent replays return early), so a replay cannot inflate counts. The backlog is a read-only `SELECT count(*) WHERE status IN ('PENDING','FAILED')` refreshed by a 10 s scheduled tick **on the event loop** — no scrape-thread DB access (`HR000068`-safe) and no new write path. `/q/metrics` is cluster-internal (not Ingress-exposed). | Low — labels bounded by ISO-4217 currency × a closed `type` set |
| D1 | Posting path | **DoS** — flood of postings / expensive trial-balance scans | Cursor pagination on journals; partial index on `(sub_account_id, base_currency)`; reactive non-blocking stack; per-service resource limits (k8s) | Rate-limiting at gateway — infra scope |
| D2 | Outbox relay | **DoS** — a poison outbox row retried forever starves the dispatch batch | **Bounded retries → terminal `DEAD` + operator alert** (ADR-0050 N5); `concurrentExecution=SKIP`; sequential per-aggregate dispatch | `FOR UPDATE SKIP LOCKED` claim for multi-writer — *planned* |
| E1 | Roles | **Elevation** — reader triggers a posting | Mutations gated by `ROLE_OPERATOR`; read roles exclude write capability; no posting logic on read endpoints; **OPA enforce mode is now ON (ADR-0034 Phase 5)** — a call with no matching `allowed_reasons` (e.g. a compliance/viewer role attempting `ledger.create`) is 403'd by `data.openbank.rest.allow`'s default-deny, in addition to the `@RolesAllowed` outer gate | Two independent gates (RBAC + OPA) both currently key off the SAME role set (`ROLE_OPERATOR`/`ROLE_ADMIN`) — OPA does not yet add attribute-based conditions (e.g. tenant/value-band) beyond role membership for ledger writes — *open* |
| E2 | Attestation / year-close | **Elevation** — a SERVICE principal or a non-operator attests a fiscal year | `ledger.approve` (year-close attest) has **NO service-\* OPA grant**, and since #3765 the `operator-year-close-attest` rule also **excludes every `service-account-*` identity outright** (`not startswith(input.principal.id, "service-account-")`). That exclusion is what makes the "no M2M path" claim true: *absence of a service-\* rule was never sufficient* — the rule was role-only, and both `service-account-openbank-services` and `service-account-openbank-edge` are classified `HUMAN` while holding `ROLE_OPERATOR` in a realm, so `opa eval` against the deployed bundle returned `allow=true, reason="operator-year-close-attest"` for both. Measured before and after: the fix denies exactly `ledger.approve` for both service-accounts and changes no other decision, with staff (`ROLE_OPERATOR`/`ROLE_ADMIN`) still allowed. In-service four-eyes (draftedBy ≠ attestedBy, see §4) is a second, independent control on top | Low — two independent controls (OPA identity+role gate and in-service four-eyes) must both be defeated. **Was Medium until #3765**: a single compromised shared-client credential satisfied the OPA gate on its own |
| T4 | Outbox/Kafka | **Tampering** — downstream consumes a non-emitted, reordered or duplicated event | Transactional outbox (single DB tx with the posting); dispatch runs on the Vert.x event loop so it actually drains (ADR-0050 N1, was `HR000068`); **deterministic Kafka key = `aggregate_id`** preserves per-account order (N2); **`event.id` carried as `ce-id`/`idempotency-key` header** for consumer dedup (N3); idempotency key on posting dedupes retries | Schema-compat on event change (advisory gate); signed event provenance — *planned* |
| S2 | OIDC client secret | **Spoofing (shared-credential blast radius)** — ledger's `OIDC_CLIENT_SECRET` is projected from the **shared** Vault key `account-service` (all services reuse the single `openbank-services` Keycloak confidential client, see `gitops/components/ledger/oidc-externalsecret.yaml`). Compromise of that one Vault key would let an attacker mint bearer tokens accepted by **both** account-service and the ledger money path — a single secret is a single point of forgery across services. | Secret is Vault-projected (never in git/state); ExternalSecret `deletionPolicy: Retain`; the Keycloak client is **confidential** (not public), so the secret alone is required and it is access-controlled in Vault; ledger write endpoints additionally require `ROLE_OPERATOR` (S1/E1), so a forged service token still cannot post without the operator role claim. | **Shared-credential blast radius is accepted for sandbox only.** Tightening = a dedicated Vault path + per-service Keycloak client for ledger (planned, §5). **Production go-live requires the second money-path approver to explicitly sign off this residual** (ADR-0030). — *open* |

## 4. Key invariants (must never regress)

- A `JournalEntry` cannot reach `POSTED` unless balanced per base currency.
- `subAccountId` is permitted **only** on deposit-control legs (2100–2103).
- Posted entries are immutable; the only correction is a balanced reversal that preserves all dimensions.
- **Period lock (#869):** once a fiscal year is `ATTESTED`, no posting **or reversal** may land in it
  (`LedgerService.requireOpenPeriod`, checked on `postJournal`/`reverseJournal` by `entryDate.year`,
  derived for a reversal from the original it preserves). A late posting into a sealed year would
  silently invalidate the attested trial-balance content hash; the lock makes that a 409, and the
  read-only re-verify endpoint (`GET /close/{year}/verify`) is the **detective** control that proves a
  sealed period is still hash-identical (or surfaces drift) without flipping state. Corrections to a
  closed year must be booked as an adjustment in the current open period.
- **Four-eyes year-close attestation (#869):** the attestor (checker) MUST differ from the draft
  author (maker, `YearCloseRecord.draftedBy` — recorded as the verified JWT subject on every
  create/refresh of the DRAFT). Enforced **fail-closed** in both `YearCloseService.attest` (409) and
  the domain `YearCloseRecord.attest` (a `check`, defense-in-depth): a self-attest is rejected, and a
  **null** `draftedBy` (a draft predating four-eyes tracking) can **never** be attested — it is a 409
  ("refresh it"), never a silent bypass. A four-eyes bypass would defeat segregation of duties on the
  statutory close, so a null or self-equal author always fails closed.
- Idempotency key ⇒ at-most-once posting per `(idempotencyKey, transactionId)`. Idempotent replay is
  checked **before** the period lock, so replaying an entry booked while the year was open stays
  idempotent even after the year is later attested.
- **No endpoint is `@PermitAll`** — read paths are role-gated and locked by `LedgerSecurityContractTest`.
- Outbox dispatch runs reactively on the event loop and is **single-writer** (`replicas: 1` + in-JVM
  `SKIP`); a posted row's event is published exactly once per successful tick or bounded to `DEAD` (ADR-0050).
- Domain-metric labels are **low-cardinality and PII-free** (currency + a closed `type` set; gauge by `service`);
  never an account id, IBAN, amount, party, or entry id (ADR-0077 cardinality contract).
- **Closed-period evidence:** a new freeze persists the re-verified lines, FROZEN status flip and
  `PeriodFrozen` outbox row in one transaction. The database rejects UPDATE/DELETE of persisted
  evidence. FINREP/COREP calls only `frozen-trial-balance`, which rejects DRAFT, missing and V22
  `HASH_ONLY` records; the operational endpoint may recompute those historical rows but is never a
  regulatory source. During rollout the count of `HASH_ONLY` frozen records is an explicit report
  availability gate. No backfill is automatic: historical reproduction needs a separately approved,
  controlled evidence-import procedure and cannot be inferred from mutable journals.

## 5. Open items / follow-ups

- ~~Read endpoints `@PermitAll`~~ — **closed (K7 / ADR-0018):** reads role-gated; declarative contract
  locked by `LedgerSecurityContractTest`.
- ~~OPA fine-grained authz advisory-only~~ — **closed (ADR-0034 Phase 5, issue #266):** sidecar deployed,
  `AUTHZ_ENFORCE=true`, `ledger_rest_ext.rego` adds the write-side operator grant
  (`operator-ledger-write`: create/reverse/trigger) plus narrow per-verified-caller M2M grants
  (`service-ledger-post` for transaction/lending/settlement-service's `postJournal`;
  `service-ledger-reverse` for transaction-service's `reverseJournal` only — lending and settlement
  have no reverse call on their clients and are NOT granted it). Gated on
  `principal.id == "service-account-openbank-services"` (identity, not the unreachable
  `principal.type == "SERVICE"` — PR #403 / `rules.yaml: authz_policy`; verified against the
  realm's Keycloak client definitions, which assign that client's service-account user NO
  realm roles). `ledger.approve` (year-close attest) and `ledger.trigger` (FX revaluation ops
  re-run) have no M2M grant at all — no in-repo caller invokes either.
  **Correction (#3765):** "no M2M grant" did not mean "not reachable by an M2M caller", and for
  `ledger.approve` it was measurably false — the role-only `operator-year-close-attest` rule
  admitted the shared and edge service-accounts, which carry `ROLE_OPERATOR` and are classified
  `HUMAN`. That rule now excludes `service-account-*`. `ledger.trigger` and `ledger.replay` are
  **still reachable** by any caller holding a `ROLE_OPERATOR` token, by TWO independent paths —
  `operator-ledger-write` and base `rest.rego`'s `matrix-allows`, since `rules.yaml:
  authz.role_action_matrix` grants both to `ROLE_OPERATOR`. Excluding service-accounts from
  `operator-ledger-write` alone would close neither, because `matrix-allows` lives in base
  `rest.rego` and no per-service extension can veto it. Tracked as the fleet decision on #3765,
  not fixable here. Residual: OPA cannot
  name WHICH of the four services sharing the `openbank-services` client is calling
  (fleet-wide principal-model limitation, not ledger-specific) — see PR body "Residual risk"
  for the accepted scope. Four-eyes
  (`four_eyes_required`) for `ledger.post`/`ledger.reverse`-shaped actions is tracked separately
  (issue #395 / PR #396 — the flag's wiring into `allow.attributes`, not this PR's scope).
- ~~Outbox dispatch fails on every tick (`HR000068`)~~ — **closed (ADR-0050 N1):** dispatch returns
  `Uni<Void>` and runs on the event loop; deterministic key + carried `event.id` (N2/N3); bounded `DEAD` (N5).
  Remaining: `FOR UPDATE SKIP LOCKED` single-writer claim for multi-writer topologies (N4 refinement); full
  `headers` JSONB CloudEvents envelope (ADR-0003).
- **Dedicated OIDC credential for ledger (S2):** ledger currently reuses the shared `account-service`
  Vault key / single `openbank-services` Keycloak client. Provision a per-service Vault path and a
  dedicated confidential client so a single key compromise no longer spans the money path. Sandbox
  risk-accepted; **prod go-live blocked on this or an explicit second-approver sign-off of S2.**
- Wire signed audit / evidence bundle (ADR-0029 D2) for non-repudiation of postings.
- ~~Mutation testing (pitest) on `validateBalance` / reversal math~~ — **verified (ADR-0030 D3,
  2026-07):** ran a real, tightly-scoped `:openbank-ledger-service:pitest` locally (188 mutations
  generated across the whole `com.openbank.ledger.domain.*` target, 124 killed = 66%, matching the
  fleet-run baseline in `rules.yaml`). Inspected `mutations.xml` specifically for `JournalEntry`
  (41 mutations, 34 killed = 83%) and confirmed **every mutant inside `reverse()` itself is
  KILLED** (conditional-negation on the `POSTED` guard and the DEBIT/CREDIT side-flip, 3/3) —
  reversal math was never the gap. The 7 `JournalEntry` survivors are either Kotlin-compiler
  `Intrinsics.checkNotNull*` scaffolding in `bookedDeltas`/`validateBalance` (not real behavior;
  standard pitest+Kotlin noise) or `getVersion` always-returns-0 (closed below). Closed the
  reversal-*adjacent* gaps a mutation-style review of `LedgerService.reverseJournal` and
  `PanacheJournalRepository.saveReversal` surfaced, none of which pitest's domain-only scope could
  have caught (both classes sit outside `targetClasses = com.openbank.ledger.domain.*`): the
  application layer's `entryNumber`/`createdAt` stamping onto the persisted reversal was previously
  asserted only indirectly; the `JournalReversedEvent` outbox payload's `originalJournalId`/`reason`
  fields were never decoded and checked; `reverse()`'s `entryDate`/`valueDate` inheritance and
  `reversalOf` FK had no dedicated assertion; `JournalEntry.version`'s round-trip was only ever
  checked at its default `0L` (indistinguishable from the surviving always-0 mutant); and the
  sequential (non-race) double-reversal path — `saveReversal`'s conditional
  `UPDATE ... WHERE status = 'POSTED'` guard plus the V12 unique-index backstop — had only a
  concurrent-race regression test (`LedgerConcurrencyIT`), not a deterministic repeat-call one. All
  now covered: `JournalEntryTest`, `JournalEntryPropertyTest`, `LedgerServiceTest`, `LedgerApiIT`.
- **Historical reversal-line corruption, generic repair (#527, 2026-07-11):** the V10-era bug
  (`reverse()` left `journalId` pointing at the original entry, so reversal lines attached to the
  wrong aggregate — the reversal persisted with zero lines, unreadable on hydration) had its code
  fix land later than the V10 data patch (V10 was a one-time hardcoded-id repair; the code fix
  landed with #528). Any reversal booked in that window re-created the same corruption with new,
  unknown ids. V13 is a generic repair, safe-by-construction: it identifies the misattached lines
  via their UUIDv7-embedded creation timestamp (ADR-0106) rather than value matching, and only acts
  when the split is unambiguous (exactly one broken reversal per original, an even line count, the
  candidate orphan half internally balanced) — anything else is left untouched with a NOTICE for
  manual review rather than guessed at. Tested against synthetic corruption + clean/correctly-
  reversed control entries in an isolated local Postgres before this PR (not run against any live
  ledger DB directly — Flyway applies it automatically on ledger-service's next normal deploy, the
  same mechanism V10/V11/V12 already used).
- Phase C: emit `AccountBookedChangedEvent` from ledger as the projection trigger (ADR-0039).

## 6. Tie-out endpoint (`GET /api/v1/control-accounts/{id}/tie-out`)

Added in Phase B to make the scheduler's daily invariant queryable by auditors and ops.

| # | Threat | Control |
|---|--------|---------|
| T5 | **Information disclosure** — tie-out response contains per-currency GL aggregates | Endpoint requires `SERVICE`, `AUDITOR`, `OPERATOR`, or `ADMIN` role (no `VIEWER`, no unauthenticated). Role enforcement locked by `LedgerSecurityContractTest`. |
| T6 | **Enumeration** — caller probes UUIDs for non-existent control accounts | Empty list returned for unknown `controlAccountId`; no 404 distinguishable from zero-activity account — timing-safe. |
| T7 | **Denial of service via large `asOf` range** — `asOf` is a single date; the query is bounded to `entry_date <= :asOf` over a single control account | Query always scans a single account; index on `(account_id, entry_date)` (V7 migration) keeps cost proportional to account volume, not total ledger size. |
| T8 | **TieOutScheduler silent failure** — exception in one currency skips remaining currencies | Per-currency `try/catch` logs `ERROR` and continues; `openbank.subledger.tieout.break` is a counter (non-zero = alert), not suppressed by exceptions. |

## 7. Four-eyes approval (ADR-0155) — STRIDE supplement

`POST /{journalId}/reverse` (`ledger.reverse`) is a money-path action OPA (`rest.rego`) can flag
`four_eyes_required`. New endpoint `PATCH /api/v1/journals/approvals/{id}` lets a DIFFERENT
operator decide the resulting `PendingApproval`; the maker retries `POST /{journalId}/reverse`
with an `X-Approval-Id` header. **`authz.four-eyes.enforce` stays `false` in this PR** — the
`ApprovalStore`/endpoint are wired (rollout tracked in issue #413, mirroring the sepa-payment
pilot), but blocking is a deliberate follow-up flip, not bundled here (see ADR-0155).

This is a NEW Redis dependency for ledger-service — previously ledger had no Redis surface at all
(see §1/namespace note, now superseded). The `ApprovalStore` (`RedisApprovalStore`) is the only
consumer; no Idempotency-Key dedup is added by this change.

Note: ledger already has an independent, in-service four-eyes control on year-close attestation
(`ledger.approve`, §4 Key invariants above — the attestor must differ from the draft author,
enforced fail-closed in both the use case and the domain). The ADR-0155 mechanism here is a
second, separate control specifically on **journal reversal** — a different action, gated through
the shared `ApprovalStore`/`AuthorizeInterceptor` path (currently advisory, `enforce=false`).

| STRIDE | Threat | Mitigation |
|---|---|---|
| **S**poofing | A caller other than an operator decides an approval | `@RolesAllowed(Roles.OPERATOR, Roles.ADMIN)` + OPA `@Authorize(action="ledger.approval.decide")` on the decide endpoint |
| **E**oP | The maker approves their own reversal request (self-approval defeats maker-checker) | `ApprovalStore.decide` throws `SelfApprovalNotAllowedException` (mapped to 403) when `decidedBy == makerId` — enforced in the domain port itself, not just the REST layer, and `makerId`/`decidedBy` both resolve via the same `.principal.name` extraction (interceptor vs. `SecurityIdentity`) so the comparison can't silently mismatch for the same real person |
| **T**ampering | A stale, mismatched, or already-consumed `X-Approval-Id` is replayed to unlock a different reversal | `AuthorizeInterceptor` requires the approval's `action` + `resourceId` + `makerId` to match the CURRENT request exactly, `status == APPROVED`, and marks it `EXECUTED` (one-time use) on success; any mismatch re-issues a fresh pending approval instead of proceeding |
| **R**epudiation | No record of who approved a gated reversal | `PendingApproval.decidedBy` + `decidedAt` recorded in the approval record itself (Redis, TTL-bounded — see Residual risk below: not yet a permanent audit trail) |
| **I**nfo disclosure | Approval id enumeration reveals journal/action metadata to an unauthorized caller | `find`/`decide` require the caller to already hold a valid, role-gated session; the id itself is a random id (`RedisApprovalStore`, not sequential) |
| **D**oS | Flooding `POST /{journalId}/reverse` to exhaust Redis with pending approvals | Bounded by the same rate-limit/idempotency controls as the gated endpoint itself; each `PendingApproval` is TTL-bounded (86400s) so abandoned records expire |
| **I**nfo disclosure | (issue #5679) `GET /api/v1/journals/approvals` lists every pending four-eyes request with its `makerId` and age | Role-gated `Roles.OPERATOR`/`Roles.ADMIN` + `@Authorize(action = "ledger.approval.read")`; the payload carries approval metadata only — the action name, the resource id and who asked — never journal line content or account balances, which stay behind the existing read-role gate (I1 above). Limit clamped to 200 — an unbounded query parameter over a Redis scan is a trivially reachable amplification. Deliberately NOT filtered to exclude the caller's own requests: hiding a maker's request from them would not stop them attempting it (the guard is in `RedisApprovalStore.decide`, server-side) and would only make the queue lie about its own depth |

**DFD update:** adds `Operator (checker) → GET /api/v1/journals/approvals → Redis (approval:*)`
and `Operator (checker) → PATCH /api/v1/journals/approvals/{id} → Redis (approval:*)` alongside
the existing `POST /{journalId}/reverse` edge; the maker's retry reuses the existing DFD edge.
**Risk class:** integrity (segregation of duties, reversal) + confidentiality (approval record scope).
**Rollback:** `authz.four-eyes.enforce=false` (default) — the endpoint and store exist but do not
change any existing request's outcome until explicitly flipped.

**Residual risk:** four-eyes `PendingApproval` records are TTL-bounded (Redis), not a permanent
audit trail (ADR-0155) — a durable-audit requirement for "who approved what, forever" would need
an additional store; not implemented in this PR. Also open (unchanged by this PR): S1 above (the
`openbank-services` shared-credential blast radius) and E1 (RBAC + OPA both key off the same role
set) apply equally to the new `ledger.approval.decide` action.

## 8. Change log

- **2026-08-26** — `POST /api/v1/journals` now copies the trusted synthetic classification from
  `SyntheticTaintRequestFilter` into the `JournalPosted` and derived `AccountBookedChanged` outbox
  rows for that posting. The REST resource reads only the server-side request property after the
  filter authenticates a configured canary principal; it does not trust a request header, JWT
  claim, coroutine MDC value or unconfigured caller. **STRIDE-S/T:** a normal caller cannot label
  a real ledger event synthetic, because the default is false and only the filter's fail-closed
  decision is copied. No principal is configured and no regulatory consumer is changed here; this
  neither activates a money-moving synthetic journey nor claims FINREP/COREP/AML exclusion.
  Rollback: revert propagation, restoring false on newly written rows.

- **2026-08-24** — Synthetic-journey taint now reaches this service over its existing internal FX REST edge through `SyntheticTaintClientFilter` (ADR-0252, #4348). This adds no caller, endpoint, network-policy edge, privilege or ledger-control bypass. It is the prerequisite for correctly classifying synthetic postings at a later persistence-backed ledger boundary; a fleet gate requires every new client to choose propagation or a reasoned external boundary.

- **2026-08-19** — `ApprovalResource` served only `PATCH /{id}` (decide), so a `ledger.reverse`
  four-eyes decision parked at 202 was discoverable only by whoever had been handed its approval
  id out of band — the ceremony completed only if the two operators were already talking, and the
  24h Redis TTL then expired the request silently otherwise (issue #5679, mirroring sanctions
  #3472). Added `GET /api/v1/journals/approvals` (§7 new I row); no new trust boundary crossed —
  same `RedisApprovalStore`, same role gate shape as the existing decide endpoint, additive-only
  OpenAPI change (1.15.0 -> 1.16.0, ADR-0048).
- **2026-08-16** — Outbound OIDC client was never configured (#3921, follow-up to the 2026-08-09
  entry below). That entry's "Same … OIDC client-credentials posture" line described a posture
  that did not exist: `application.yaml` declared `quarkus.oidc` (inbound, validates tokens
  arriving here) and no `quarkus.oidc-client` (outbound, mints the token `FxServiceClient`'s
  `OidcClientRequestReactiveFilter` attaches). The two blocks share three field names and differ
  by one hyphen, so the gap read as configured on inspection. Effect: every `FxServiceClient` call
  left with no `Authorization` header at all — a 401 from fx-service, not a 403 — and the daily FX
  revaluation had failed on **every run it ever made**
  (`openbank_workflow_success_recorded{workflow="ledger-fx-revaluation"} = 0`; caught only by
  `FxFixingAgeAbsent`, an `absent()` alert on a gauge the run never got far enough to register).
  Fixed by adding `quarkus.oidc-client` with `auth-server-url:
  ${QUARKUS_OIDC_AUTH_SERVER_URL:http://localhost:8080/realms/openbank}` — the same variable the
  inbound `quarkus.oidc` block already reads, and which the deployed workload already sets, so no
  gitops change was needed. **No new trust boundary**: the edge, host, route, authz posture and
  Vault-projected `OIDC_CLIENT_SECRET` (S2 above) are unchanged — this restores the M2M identity
  the edge was always meant to present, rather than adding a capability. Enforced fleet-wide by
  `check-oidc-client-configured.py` (six services fixed; ledger and settlement money-path).
- **2026-08-09** — Outbound client edge + posting semantics (#3921, correctness half). Two changes to
  the money path, and the second is the one that needs the scrutiny.
  **(a) Outbound edge.** `FxServiceClient.getRate` now sends `asOf=<business day>` alongside
  `source=CNB`. Same host, route, method, authz and OIDC client-credentials posture — one query
  parameter, no new dependency and no new trust boundary. It removes a *correctness* exposure
  rather than adding one: a belated or manual revaluation of an older day used to be marked at
  **today's** fixing, because the port had no date to ask with. A day with no fixing in effect now
  answers 404, which the adapter maps to `null` exactly as it already did for an un-ingested
  currency, so the leg skips loudly (`FxRevaluationSkippedAllLegs`) instead of marking at a wrong
  rate.
  **(b) A corrected fixing can now post.** `fx-reval-{date}` keyed the entry on the business day
  alone, so `postJournal`'s idempotency short-circuit returned the ORIGINAL entry on any re-run —
  a corrected ČNB fixing could never be incorporated, and the run reported `posted = true` having
  changed nothing. The key now carries a 12-hex-char SHA-256 digest of the fixing set the posting
  was built from, so a different fixing is a different key.
  **Why this does not double-count, which is the whole risk.** `FxRevaluationPosting.movement` is
  carry-relative — `round(position × rate) − carryCzk`, where `carryCzk` is the counter-value
  account's trial-balance net — and the trial balance is cumulative to the business day
  (`entry_date <= :asOf`). The first posting carries `entryDate = command.date`, so a correcting
  run reads its own predecessor and posts only the DIFFERENCE. Invariant §4 ("the position after
  n postings equals the latest mark") is preserved by construction, not by convention, and
  `a corrected fixing posts a superseding entry for the difference, under a different key` asserts
  it arithmetically (25,145,000 + 355,000 = 25,500,000).
  **Rejected alternatives**, on failure mode rather than taste: *reversal-and-repost* uses the
  available `reverseJournal` to produce three entries whose net is identical, and adds a failure
  mode the superseding form lacks — a reversal that commits without its repost leaves the position
  marked at nothing, whereas an interrupted superseding run leaves it merely stale. *Overwriting
  the original entry* is unavailable and correctly so: entries are append-only and an attested
  year refuses new activity (`requireOpenPeriod`), so a correction into a closed period fails
  loudly as a posting into a locked day, governed by the existing day/period locks — those are
  unchanged and still apply to the correcting entry.
  **Repudiation:** improved. The entry description already named the fixings (step 2); the key is
  now derived from them, so "which fixing valued this position, and which superseded it" is
  answerable from the ledger for the first time. **EoP/authn:** unchanged — the correcting entry
  is posted by the same `SYSTEM_USER` under the same day and period locks as any other
  revaluation. **Degradation:** with no `validFrom` on any leg there is no identity to key on and
  the key falls back to exactly `fx-reval-{date}`; corrections stay impossible, deliberately,
  since a fabricated identity would be worse than an honest inability, and the state is visible
  via `openbank_fx_fixing_age_seconds` and the missing `[fixings …]` suffix.
  Rollback: revert. Entries already posted under either key form remain valid and independently
  correct, because each one only ever booked a delta.

- **2026-08-05** — Prohibit the customer-edge M2M principal from every ledger write, including
  year-close attestation (#3734). `operator-ledger-write` and `operator-year-close-attest` were
  role-only, and `rules.yaml`'s `role_action_matrix` grants `ledger.create/reverse/trigger/
  replay` to `ROLE_OPERATOR`. The customer-facing edge identity
  (`service-account-openbank-edge`, HUMAN-classified, ROLE_OPERATOR) was therefore admitted to
  the **book of record's** writes — post/reverse a journal, re-run an FX revaluation — via base
  `matrix-allows`, and to `ledger.approve` via the attest rule, whose own comment has always
  said no SERVICE principal must ever reach it. This is the single most sensitive row in the
  #3734 matrix. Fleet caller audit: **no `ledgerServiceUrl` exists anywhere in customer-edge** —
  no edge ledger caller at all; the legitimate M2M writers (transaction/lending/settlement via
  the shared client) keep their identity-scoped `service-ledger-post` / `service-ledger-reverse`
  rules. Tightening is two-layered: both operator rules now exclude every `service-account-*`
  principal (also closing `ledger.trigger`/`replay` to the shared client, which the ext already
  documented as intentionally unmapped — the matrix grant is a separate pre-existing over-grant
  this PR does not touch), and an edge-scoped `prohibited` clause vetoes all five write actions
  — `approve` included despite no matrix grant, so no future matrix edit can hand the edge a
  statutory close event — at the allow head. Falsified by `ledger_rest_ext_test.rego` (stripping
  either layer turns 7 of 12 tests red); the ext moved from a generator heredoc to a standalone
  `ledger_rest_ext.rego` so `opa test` can load it. Rollback: revert the ext — no live caller is
  lost, as no edge ledger path exists.
- **2026-07-12** — Wired the four-eyes (maker-checker) enforcement *mechanism* (ADR-0155) onto
  `ledger.reverse`, mirroring the account/sepa-payment/lending rollouts (issue #413). This is
  ledger-service's first-ever Redis dependency: new in-namespace `redis` Deployment/Service
  (`openbank-infra/gitops/components/ledger/redis.yaml`) plus a `redis-ingress-allow-list`
  NetworkPolicy, `QUARKUS_REDIS_HOSTS` wired on the Rollout, and `quarkus-redis-client` added to
  the build. New `ApprovalConfig` (`RedisApprovalStore` producer) and `PATCH
  /api/v1/journals/approvals/{id}` checker-decide endpoint (`@RolesAllowed(Roles.OPERATOR,
  Roles.ADMIN)`, `@Authorize(action = "ledger.approval.decide")`); two new exception mappers
  (`SelfApprovalNotAllowedMapper` → 403, `InvalidApprovalStateMapper` → 409) appended to the
  existing `ExceptionMappers.kt`. STRIDE supplement added in §7 above. **`authz.four-eyes.enforce`
  stays `false`** — no behavior change to any existing request; this PR only wires the mechanism.
  Rollback: revert the commit (no DB/schema change; `ApprovalStore` records live in Redis with a TTL).
- **2026-08-08** — **The ČNB fixing's `validFrom` now crosses the fx-service seam** (issue #3921).
  `CnbRateProvider` returned a bare `BigDecimal?`, and `FxServiceClient`'s DTO declared only
  `baseCurrency/quoteCurrency/bidRate/askRate` — fx-service was already serving `validFrom`, and
  `@JsonIgnoreProperties(ignoreUnknown = true)` silently dropped it. The port now returns
  `CnbFixing(rate, validFrom)` and `FxServiceCnbRateAdapter` carries it through. **Risk class =
  information disclosure**, and it is a bounded *decrease* in blind trust rather than an increase:
  no new endpoint, no new caller, no authorization or NetworkPolicy change, and the field was
  already on the wire — this side simply stops discarding it. It is a published reference rate, so
  it discloses nothing about any customer or position. The only new outbound artifact is a metric,
  `openbank.fx.fixing.age_seconds{currency}`, which carries a currency label and an age in seconds
  and no amount, account or party.
  **Why it matters here**: nothing anywhere compared a fixing's age to now. The service-side bound
  is date-blind (`CNB_VALIDITY_DAYS = 3`, `validTo > :now`), so a stale rate revalues silently, and
  the one alert meant to catch the silent case — `FxRevaluationSkippedAllLegs` — selected
  `{namespace="fx"}` while the log line it matches is emitted only by this service, deployed to
  namespace `ledger`. It could never fire. That selector is corrected in the same change.
  **Explicitly NOT addressed**: a late or corrected fixing still cannot be incorporated.
  `idempotencyKey = "fx-reval-{date}"` carries no rate identity, so a re-run returns the existing
  entry and reports success while every position marked that day keeps the superseded rate's CZK
  counter-value. That needs a correcting-entry decision, not a patch, and #3921 stays open for it.
  Rollback: revert; the field is read-only on this side and nothing persists it.

- **2026-08-14** — Closed-period freezes now retain their exact trial-balance lines for FINREP/COREP
  (ADR-0096 D1 expand stage). The security boundary is the evidence write: `freeze` re-computes and
  hash-compares the DRAFT, then writes the exact reverified lines, status transition and outbox row
  in one reactive transaction. **Tampering:** the new table has a database trigger rejecting update
  and delete, its FK is `ON DELETE RESTRICT`, and FROZEN reads use those rows rather than a mutable
  journal aggregate. **Repudiation:** the stable existing hash is retained and callers can reproduce
  the lines it anchored; the endpoint's DRAFT/no-record behavior remains live computation. **DoS:**
  line inserts occur only during an operator four-eyes freeze, not on the posting path. **Rollback:**
  before FINREP depends on the source, stop writers and archive frozen evidence, then remove the
  trigger/function/table; never delete attested evidence as a convenience rollback.
