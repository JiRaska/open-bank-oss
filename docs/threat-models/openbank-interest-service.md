<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — openbank-interest-service

- **Date:** 2026-07-18
- **Status:** Lightweight STRIDE (ADR-0030 D2). **Money-path** (capitalization + write-off GL journals, withholding-tax remittance).
- **Purpose:** Interest accrual, capitalization, and withholding-tax remittance for customer accounts.

## 1. Scope & purpose

The interest service accrues interest on eligible accounts, capitalizes it into the customer's
balance (`capitalize()`), posts the corresponding write-off/capitalization journals to the ledger,
and remits withholding tax on interest paid to the tax authority. Both capitalization and
withholding-tax remittance move real value: capitalization credits the customer's balance via a
ledger posting, and remittance debits an amount owed to the state (#999). This is a direct
money-path service, not adjacent.

## 2. Data flow (DFD)

```
[Scheduler / Admin-UI] --HTTPS/internal--> [openbank-interest-service]
                                                |
                     account lookup        ---|---> [account-service] (AccountDirectoryAdapter)
                     published rate snapshot ---|---> [product-catalog] (immutable revision lookup)
                     capitalization journal ---|---> [ledger-service] (RestLedgerPostingAdapter, CapitalizationJournalFactory)
                     debit remittance       ---|---> [transaction-service] (TransactionServiceClient)
                     settlement ack         <---|--- [Kafka: withholding-remittance-settlement]
                     outbox events          ---|---> [Kafka: interest.capitalized, interest.withholding.remitted]
```

- **External entities:** scheduler (internal trigger, no external caller), admin-UI (ROLE_OPERATOR/ADMIN for read/ops endpoints).
- **Trust boundaries:** service → ledger-service (mTLS + OIDC, fail-closed via `LedgerCallGuard`); service → transaction-service (mTLS + OIDC); service → product-catalog (OIDC, immutable published-revision lookup only); service → Kafka (mTLS, consumer/producer ACLs).
- **Assets:** accrued/capitalized interest amounts, withholding tax rate and remittance amounts, account balances (indirectly, via the ledger postings this service issues).

## 3. Authn/Authz

- Operator-facing REST endpoints: `@RolesAllowed` (ROLE_OPERATOR/ADMIN).
- Capitalization and remittance runs are triggered internally (scheduled job), not by an external caller — no unauthenticated inbound trigger surface.
- Calls to `ledger-service` and `transaction-service` are service-to-service (OIDC client credentials, OPA policy, four-eyes verbs per `rules.yaml`).
- OPA enforcement is a property of the **service**, not of a manifest: `authz.enforce` defaults to
  `${AUTHZ_ENFORCE:true}` in `application.yaml` (#3679). The `%test` profile is the single
  documented exception — no OPA sidecar runs in the test JVM, and the interceptor fails closed
  (503) on an unreachable PDP.

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing | Rogue caller triggers capitalization or remittance for an arbitrary account | Internal scheduler trigger only; `@RolesAllowed` gate on any manual/admin trigger endpoint; OIDC service identity on downstream calls |
| **T**ampering | Alter capitalized amount or withholding rate in flight | TLS in transit; journal amounts derived server-side from `WithholdingTaxPolicy`/`WithholdingRemittancePolicy`, never accepted as caller input; idempotency key on ledger posting |
| **R**epudiation | Dispute over whether interest was capitalized or tax remitted for a period | AuditEvent per capitalization/remittance action; outbox event (`interest.capitalized`, `interest.withholding.remitted`) is the durable record; ledger journal itself is the authoritative, immutable trail |
| **I**nfo disclosure | Expose per-account interest/withholding amounts via error bodies or metrics | Error bodies carry codes only; metrics are low-cardinality (no account-id/amount labels, ADR-0077/0079) |
| **D**oS | Flood the manual capitalization/remittance trigger, or replay settlement events | `@RolesAllowed` gate on manual triggers; idempotency key on both the ledger posting and the transaction-service debit guards duplicate runs |
| **E**oP | Use the withholding remittance path to move funds unrelated to actual accrued tax | Remittance amount computed solely from `WithholdingRemittancePolicy` against the service's own accrual ledger, not from caller-supplied input; downstream `transaction-service` is the authoritative amount boundary |
| **T**ampering | A catalog change makes historical or future accruals use an unintended rate | Consume only a maker-checker-published immutable revision; persist its content hash, effective interval and source revision locally; never resolve the latest catalog document during accrual; reject unsupported tiered profiles until their calculation is explicitly implemented |

## 5. Residual risks / assumptions

- **Withholding-tax remittance to the tax authority is not yet wired to an external filing system** (#999 tracks actual remittance-to-authority; today the debit lands in an internal remittance-holding account). The money-path risk this threat model covers is the *internal* debit/capitalization flow, which is live.
- Catalog-projected rate snapshots are an additional inbound reference-data boundary. A missing, malformed, stale or unsupported snapshot must prevent accrual for its affected product/currency; it must never fall back to a different current rate.
- **Settlement ack consumer** (`WithholdingRemittanceSettlementConsumer`) trusts the Kafka topic's mTLS/ACL boundary as its authentication; no additional payload-level signature.

## 6. Change log

- **2026-09-07** — Natural-key idempotency on the creation POSTs (ADR-0291, burn-down #8351).
  `accrue` and `rates` gained check-first replay on their natural keys (accrual: V12's
  `(account, date, product, currency)`; rate config: `(product, account, currency,
  effectiveFrom)`), each with the unique constraint as race backstop and a constraint-name-scoped
  recovery that re-reads the winner's row. No new endpoint, caller, privilege or control bypass:
  the change only converts a retry's 500-on-constraint into a replay of the original row, and a
  retry's duplicate-active-config into a no-op. The trust boundaries are unchanged.
- **2026-09-03** — Four-eyes assessment (#8359, ADR-0034 D-criteria as applied in the #938 sweep).
  Per-verb caller audit: **`interest.create` and `interest.trigger` are now four-eyes-gated** via
  `rules.yaml: four_eyes.actions`. `interest.create` bundles every operator write on the money path
  (manual accrue, capitalize → real GL journals, rate-config create → shapes all future accrual
  amounts, withholding remittance assemble → triggers the real cash leg to the finanční úřad);
  `interest.trigger` (accrueAll) is the manual mass accrual. Both caller sets are human-only, twice
  over: `operator-interest-write` excludes `service-account-*` and the `interest_rest_ext.rego`
  prohibition vetoes the write set for any service account at the allow head; the fleet audit found
  no M2M writer (agent-service's client is read-only). The accrual/capitalization schedulers and the
  remittance settlement consumer call the use cases in-process, so the HTTP-layer gate can never
  pause automation. **`interest.delete` (deactivateRateConfig) NOT gated:** it stops future accrual
  — reduces money movement (standing-order pause/cancel precedent). `interest.read/list` are reads.
  Four-eyes enforcement itself remains off fleet-wide (`authz.four-eyes.enforce`, ADR-0155) — the
  wiring sets the decision flag, nothing pauses yet.

- **2026-09-03** — `authz.enforce` now defaults to **true** in `application.yaml` (#3679). Until
  now it read `${AUTHZ_ENFORCE:false}`, so enforcement was a property of one gitops manifest
  rather than of the service: the deployed Rollout sets the variable to `"true"` (since #3695), so
  the cluster was enforcing, but **any environment that does not set the variable ran this
  money-path service in advisory mode** — a new cluster, a local run, an ad-hoc container, a
  restored namespace. In advisory mode `AuthorizeInterceptor` evaluates every `@Authorize`
  decision, logs the deny at WARN and lets the request through, so the failure is silent: the
  `prohibition` on service-account writes recorded in the 2026-08-03 entry below is **inert** in
  any such environment, and so is every other reason in the bundle. This closes that gap at the
  source. **No new caller, endpoint, network edge or grant** — the change can only ever move a
  request from allowed-while-denied to denied. Grantability was measured before flipping, since
  enforcing an action that no reason grants turns it into a 403: all five gated actions
  (`interest.create/.trigger/.delete/.read/.list`) were evaluated with `opa eval` against the
  deployed bundle ConfigMap, each probe carrying a must-DENY and a must-ALLOW control, and every
  one resolves `allow=true` for at least one real principal. `four_eyes_required` is false for all
  five. Residual: an operator running this service with no OPA sidecar reachable now gets 503
  rather than an unauthorized success — the intended fail-closed direction, but it makes a missing
  sidecar a hard outage instead of a silent control bypass. Rollback: set `AUTHZ_ENFORCE=false` in
  the environment, which needs no code change.

- **2026-08-24** — Synthetic-journey taint now propagates over this service's existing internal REST clients through `SyntheticTaintClientFilter` (ADR-0252, #4348). This adds no caller, endpoint, network-policy edge, privilege or control bypass. It preserves the marker before a downstream persistence/event boundary; a fleet gate requires every new client to choose propagation or a reasoned external boundary.

- **2026-08-20** — Added catalog fixed-rate snapshot boundary. Only immutable maker-checker-published
  revisions with a content hash and UTC-day-aligned effective interval can materialize a local rate.
  Unsupported or malformed profiles are durably rejected; catalog outages do not advance the cursor.

- **2026-08-03** — Missing required query/header parameter answered 500, not 400 (#3104). A required `@QueryParam`/`@HeaderParam` declared with a non-nullable Kotlin type was fed `null` by JAX-RS when the caller omitted it, and answered **500** rather than 400 (#3104). Kotlin's null-safety is compile-time only, so the declared type only decided where the failure landed: a non-suspend handler threw `Intrinsics.checkNotNullParameter` at the method boundary, and a **suspend** handler got no intrinsic at all, so the null flowed into the body. `productId` on capitalize and effectiveRate — #3104's own reproduction case. Both handlers are non-suspend, so `POST /api/v1/interest/capitalize/{accountId}` without `?productId=` threw at the method boundary and rendered 500 before any authorization-independent work ran. No fund movement is reachable without the parameter in either the old or the new behaviour — the change is purely which status the rejected request carries, and 5xx here also burnt this service's SLO error budget. No new caller or boundary. Rollback: revert.
- **2026-08-03** — Prohibit service-account principals from interest writes (#3679 follow-up). The
  `#3695` `AUTHZ_ENFORCE=true` flip exposed a trust-boundary widening the ext file itself did not
  document: `operator-interest-write` was role-only, and the `rules.yaml` `role_action_matrix`
  grants `interest.create/trigger/delete` to `ROLE_OPERATOR`. Two realm service accounts carry
  that role — `service-account-openbank-edge` (customer-reachable via the edge namespace on 8125)
  and `service-account-openbank-services` (shared backend client) — so both could reach these
  writes via `matrix-allows`, even had the ext rule excluded them. Interest is money-path (posts
  real GL journals, #1478) and the writes are bank-side actions invoked by admin-ui operators
  only (fleet caller audit in the ext file). The tightening is two-layered: `operator-interest-
  write` now excludes every `service-account-*` principal (delegation idiom), AND a prohibition
  vetoes the three write actions for any service account at the allow head — so no reason,
  present or future, can admit a backend client to a bank-side write. Reads are untouched (the
  edge legitimately serves customer interest views). Falsified by
  `interest_rest_ext_test.rego` — stripping either layer turns five tests red — and a new
  `opa-policy.yml` step now runs every `*_rest_ext_test.rego` trio in CI, closing an existing
  gap (those tests previously ran only by hand; the same sweep found a stale aml test whose
  header cited a base-less invocation, corrected in this PR). Rollback: revert the ext file to
  the pre-#3679-follow-up shape (role-only `operator-interest-write`, no prohibition, heredoc in
  the generator) — the edge/M2M paths were never used, so revert loses no live caller.
- **2026-07-24** — Freeze the tax profile at claim time (issue #1355). `capitalize()`'s claim froze the
  accrual SET (gross) before the ledger post but re-called `taxProfilePort.resolve(accountId)` fresh on
  every attempt, including retries. The ledger idempotency key is amount-blind, so a retry after a
  crashed post replays the ORIGINAL journal (P1's amounts) while the withholding row was recomputed from
  a freshly-resolved profile (P2 if the account's tax attributes changed in between) — a row-vs-GL
  divergence on the **tax** axis (the same class #1316 fixed for gross). Dormant today only because
  `DefaultTaxProfileProvider` returns a constant; the account→party tax-attribute fast-follow would make
  it reachable. Fix: the resolved profile is now snapshotted with the claim (V13, five nullable columns
  on `interest_accruals`) and a retry replays it instead of re-resolving; a claim already in flight at
  deploy carries no snapshot and safely falls back to a fresh resolve while resolution is constant. No
  new trust boundary or external caller — an internal correctness hardening of an existing money path;
  a fabricated profile change cannot move money, only make a retry recompute from the frozen inputs.
  Rollback: revert the commit + V13 (reverts to re-resolving on retry, the pre-#1355 behaviour).
- **2026-07-24** — Bind currency to the rate config (issue #1265). Previously nothing tied a currency
  to `InterestRateConfig`, and `interest_accruals.currency` defaulted to `'EUR'` while the seeded
  product is CZK, so an account could accumulate a mixed-currency ACCRUING set (the scheduler reads
  each account's booked-balance currency). `capitalize()` correctly refuses to sum incommensurable
  currencies, but there was **no operator or API path to unwedge the set** — a permanent
  availability/correctness hole on the money path (interest silently never capitalized for that
  account). Fix: `InterestRateConfig` gains a `currency`; `accrue` resolves a rate only in the
  accrual's own currency and fails closed with `RateConfigNotFoundException` (HTTP 422) when the
  account has no rate in that currency; the `interest_accruals` UNIQUE key now includes `currency`
  (V12), so two same-date rows in different currencies can never collapse into one capitalize set.
  The `mixedCurrencyFailure` guard is retained as an unreachable defence-in-depth assertion — the
  last check before a GL journal is posted. No new trust boundary or external caller; the 422 is a
  fail-closed on a config gap, never a fund movement. Rollback: revert the commit + V12 (safe only
  before two currencies coexist for one account/product/date).
- **2026-07-22** — Activate monthly capitalization (issue #999). `capitalizeAll` was a stub returning 0,
  so the already-assessed `capitalize()` money-path (claims accruals, posts a GL journal via
  `LedgerPostingPort`, records withholding) had never run at scale. A new `InterestCapitalizationScheduler`
  now drives it monthly (cron `0 0 2 1 * ?`), over a work-list read from the accrual table
  (`findAccountsWithPendingCapitalization`). No new trust boundary or external caller — this activates
  existing money-path logic on a schedule; per-pair failures are recovered (a wedged account can't stop
  the batch) and each capitalization keeps its existing ledger idempotency key, so a retry is safe. The
  downstream effect is that withholding tax is now actually assembled and remitted (the §38d statutory
  filing owner is decided separately in ADR-0180). Rollback: revert the commit (the scheduler stops).
- **2026-07-18** — Initial lightweight threat model (ADR-0030 D2), added alongside `openbank-interest-service`'s addition to `money_path_services` (#1478).

- **2026-09-20** — **New outbound edge: interest-service → ledger-service over ledger's new
  private-CA mTLS listener** (8443, client auth REQUIRED, TLSv1.3; client cert
  `interest-internal-tls`, `%prod` TLS bucket `ledger-authority`). The ADR-0033 §D capitalization
  credit leg had no `LEDGER_SERVICE_URL` in this service's gitops manifest — the `application.yaml`
  comment already warned that a deployment MUST set it — so `LedgerRestClient` dialled the fallback
  `http://localhost:8101` inside its own pod and every capitalization journal (interest expense /
  deposit-control pocket / withholding-tax payable) was a connection refused. The action
  (`ledger.create`) and the identity (the shared `service-account-openbank-services` bearer) are
  unchanged — `ledger_rest_ext.rego`'s `service-ledger-post` already admits them, so no policy
  change. **Risk class:** integrity/availability of interest capitalization posting (a money-path
  write that has never landed now lands); the new material is a private-CA client key mounted
  read-only from a cert-manager Secret, scoped to this one upstream. No inbound surface, no new
  role, no new data class. The separate `TRANSACTION_SERVICE_URL` gap (the withholding-tax
  remittance leg) is NOT addressed here — it is its own change against transaction-service's own
  listener. Rollback: drop the env var and the `%prod` bucket.

- **2026-09-20** — **New outbound edge: interest-service → transaction-service over
  transaction-service's new private-CA mTLS listener** (8443, client auth REQUIRED, TLSv1.3; the
  existing `interest-internal-tls` client certificate, mounted a second time at
  `/mnt/transaction-tls`, `%prod` TLS bucket `transaction-authority`). One client identity and one
  private CA serve both upstreams; the separate bucket and mount path keep each one naming the
  service it authenticates to. This closes the transport half of the #999 withholding-tax
  remittance leg, whose `TransactionServiceClient` previously dialled `http://localhost:8102`
  inside this pod — `POST /api/v1/transactions` was a connection refused, retried three times and
  dead-lettered, so a due tax remittance never moved money.
  **It does not close the authorization half, and OPA is not the gate that decides it.**
  `POST /api/v1/transactions` carries `@RolesAllowed(Roles.OPERATOR)` as well as
  `@Authorize(transaction.create)`, and RBAC runs first — `AuthorizeInterceptor` is a CDI
  interceptor at `@Priority(PLATFORM_AFTER + 100)` while Quarkus's `@RolesAllowed` check runs at
  platform-before priority — so no rego rule can admit a principal RBAC has already rejected. At
  the inner layer, measured against the committed `transaction-opa-bundle`, the shared
  `service-account-openbank-services` is allowed `transaction.create` with `ROLE_OPERATOR` and
  denied with `ROLE_API`; the deployed realm template grants it `ROLE_API` only, but the
  `keycloak-realm-drift` job's parity finding covers realm roles and explicitly not client-role
  assignments, so the live role set is unresolved. Tracked in #10404. See the matching entry in
  `docs/threat-models/openbank-transaction-service.md`.
  **Risk class:** transport only — no new action, principal or role for this service; the new
  material is the same private-CA client key already mounted for ledger-service, scoped read-only.
  Rollback: drop `TRANSACTION_SERVICE_URL`, the second mount and the `transaction-authority` bucket.
- **2026-09-20** — **New outbound edge: product-catalog over private-CA mTLS (8443).** `CatalogInterestProfileSynchronizer`
  now reaches `product-catalog.accounts.svc:8443` with the client certificate `interest-internal-tls` (the same client
  certificate #10397 introduced, mounted a second time at `/mnt/catalog-tls`)
  (`%prod` TLS bucket `catalog-authority`, TLSv1.3). Previously `PRODUCT_CATALOG_URL` was unset in
  gitops and the client dialled `localhost:8104` inside this pod, so the scheduled catalog snapshot never reached the catalog (#10383).
  The calls are reads of the `/api/v2` catalog surface only; no mutation, no new principal, no money
  movement. **Risk class:** confidentiality and integrity of product/offering data in transit, now
  protected by mutual TLS rather than plaintext. Rollback: drop `PRODUCT_CATALOG_URL` and the
  `catalog-tls` volume.

- **2026-09-20** — **New scheduled job: capitalization-claim recovery** (`recoverStrandedClaims`,
  every 15 min, `openbank.interest.claim-recovery-interval`). It completes capitalization claims a
  previous attempt stranded, at **their own frozen period**, and starts no new capitalization.
  **The defect it closes.** A claim is committed `ACCRUING → CAPITALIZING` in its own transaction
  before the ledger post; if the post fails, the set stays claimed. `capitalize` documents the
  recovery — retry at the claimed period, the ledger collapses the replay — but the only automatic
  caller, `capitalizeAll`, always passes *today*, so a claim frozen for an earlier period took the
  `inFlightClaimFailure` branch on every tick, forever. Sandbox had 124 accruals across 7 pairs
  wedged since 2026-08-01, each tick refusing all 7 and logging `capitalized 0 pair(s)` — which
  reads as "no work to do" (#10404). Interest that had been claimed for credit was never credited.
  **Why completing and not releasing.** Releasing a stale claim back to `ACCRUING` and re-claiming
  under a later period is the obvious "reclaim" shape and is a double-credit: the ledger idempotency
  key is `(account, product, periodTo)`, so a later period mints a new key and books a SECOND
  journal for accruals the first attempt may already have credited. Recovery therefore replays the
  original period, which makes it idempotent by construction — same claimed set, same frozen tax
  profile, same derived gross/net/tax, same key.
  **Risk class:** integrity of interest credit — this *restores* money movement that was stuck
  mid-credit; it does not create a new payment path, principal, role, endpoint or external edge. The
  ledger call is the one `capitalize` already made, under the identity it already used. The new
  exposure is timing: journals that would have waited for the monthly run are now posted within
  15 minutes of a stranded claim being detected. Bounded by construction — the sweep can only touch
  sets a previous attempt already claimed.
  **Observability** (previously log-only, which is why this hid for seven weeks):
  `openbank_interest_capitalization_claims_recovered_total`,
  `openbank_interest_capitalization_claims_recovery_failed_total`, and the gauge
  `openbank_interest_capitalization_claims_outstanding`. Alert on the gauge sustained above zero
  across more than one sweep — recovery is meant to drive it to zero, so a persistent value means
  recovery is failing, not merely that a claim exists. A failed recovery logs at ERROR.
  Rollback: remove `claim-recovery-interval` handling / revert the commit; claims then simply
  remain stranded as before, which is the pre-change behaviour and loses nothing already recovered.
