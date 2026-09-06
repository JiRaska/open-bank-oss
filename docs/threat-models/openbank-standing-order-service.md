<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — openbank-standing-order-service

- **Date:** 2026-08-03
- **Status:** Lightweight STRIDE (ADR-0030 D2). **Money-path-adjacent** (recurring payment instruction, execution initiation) — listed in `rules.yaml: money_path_services` (issue #2188).
- **Purpose:** Standing orders — recurring payment instruction lifecycle (create/amend/pause/resume/cancel) and the daily due-date sweep that initiates the actual transfers (ADR-0114).

## 1. Scope & purpose

The standing-order service stores a customer's recurring payment instruction and, once a day,
sweeps for due orders (`StandingOrderExecutionScheduler`), writes a transactional-outbox row per
due order, and dispatches `standing-order.due.v1` to Kafka. `StandingOrderDueConsumer` (#889)
picks each due event up and, for a `SEPA_CREDIT` order, initiates the real credit transfer via
`openbank-sepa-payment`'s `POST /api/v1/sepa-payments` — a gated money-path service that owns the
irreversible debit (sanctions/AML gate, Temporal workflow, ledger booking). For `DOMESTIC`/
`INTERNAL` orders, a creditor IBAN resolving to an account of the SAME party books directly via
`openbank-transaction-service` as a same-day `TRANSFER` (own-account move, no screening gap — see
§4); any other outcome (a different party's account, or no resolvable account) is recorded as a
failure, never silently dropped or mis-routed.

The service is **money-path-adjacent**: it authorises and schedules the instruction and triggers
the transfer, but the fund movement itself lives downstream — the same class as
openbank-sdd-service.

## 2. Data flow (DFD)

```
[Customer Edge / Admin-UI] --HTTPS--> [openbank-standing-order-service]
                                           |
                     daily sweep (Quarkus scheduler)
                                           |---> [outbox -> Kafka: standing-order.due.v1 / standing-order.failed.v1]
                                           |
                     SEPA execution   -----|---> [openbank-sepa-payment] (SCT, money-path)
                     DOMESTIC/INTERNAL -----|---> [openbank-account-service] (IBAN -> accountId/partyId lookup)
                     (own-account only) ----|---> [openbank-transaction-service] (TRANSFER, money-path)
                     outcome           --- |---> [confirmExecution / recordFailure -> order state + outbox]
```

- **External entities:** customer-edge (authenticated customer), admin-UI (ROLE_OPERATOR),
  sepa-payment / account-service / transaction-service (M2M callees).
- **Trust boundaries:** edge → service (OIDC + OPA sidecar, currently AUTHZ_ENFORCE=false
  advisory); service → Kafka (mTLS via Strimzi); service → sepa-payment (OIDC client credentials,
  `openbank-services` client, ROLE_OPERATOR accepted by sepa-payment's createPayment); service →
  account-service (same client, ROLE_OPERATOR accepted by `account.read`, read-only IBAN lookup,
  new — #889 follow-up); service → transaction-service (same client, ROLE_OPERATOR accepted by
  `transaction.create`, new — #889 follow-up, see §4 for why this path is scope-limited).
- **Assets:** standing-order instructions (debtor/creditor IBAN, BIC, amount, currency, schedule,
  remittance info), execution history, idempotency keys.

## 3. Authn/Authz

- All REST endpoints: `@RolesAllowed` (ROLE_CUSTOMER for own orders, ROLE_OPERATOR/ROLE_ADMIN for
  operator surface), verified by `StandingOrderSecurityTest` and `StandingOrderSecurityContractTest`.
- OPA sidecar deployed (ADR-0034 Phase 5); `AUTHZ_ENFORCE=false` (advisory) — decisions are
  evaluated and logged but not enforced yet; the flip is a deliberate follow-up with an
  observation window (rules.yaml AUTHZ_ENFORCE guardrail, issue #3679 cohort).
- Execution calls to sepa-payment are service-to-service (OIDC client credentials via
  `OidcClientRequestReactiveFilter`), not ambient authority.
- **`standingOrder.pause` is identity-scoped, not role-only (GHSA-58jq-9hq3-66jr, #4228).**
  `standing_order_rest_ext.rego` grants it two ways and only two: `operator-standing-order-pause`
  for HUMANS holding ROLE_OPERATOR/ROLE_ADMIN, now carrying
  `not startswith(input.principal.id, "service-account-")`; and `m2m-standing-order-pause`, pinned
  to the single client id `service-account-openbank-edge`. Before this, the role branch was the
  ONLY reason and it admitted every backend service — measured against
  `standing-order-opa-bundle.yaml`, `service-account-openbank-services` + ROLE_OPERATOR resolved
  exactly `["operator-standing-order-pause"]`. The identity-scoped grant had to land in the same
  change rather than after it, because unlike vop/fx/ledger this action has a real M2M caller and
  an exclusion alone would have broken customer self-service pause. The pin is one client id
  rather than `startswith("service-account-")`: pausing mutates a customer's payment schedule and
  has exactly one legitimate caller, so any-service-account would re-create the exposure.
  Ownership is still enforced in the handler (`X-Customer-Party-Id`); OPA grants the action class.
- The scheduler/consumer path has no human in the loop by design; there is no operator
  force-execute endpoint.

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing | Forged `standing-order.due.v1` event initiates a transfer | Kafka mTLS (Strimzi) on the topic; consumer validates orderId shape and required debtor fields, records failure on malformed events; sepa-payment re-authenticates the M2M caller and applies its own sanctions/AML gate |
| **T**ampering | Alter amount or creditor IBAN on the stored instruction or in flight | Order mutations go through the authenticated REST surface with `@RolesAllowed`; TLS in transit; amount is carried in minor units with currency-explicit conversion (`CurrencyCode.defaultFractionDigits`) — no float path |
| **R**epudiation | Customer denies creating or amending the order | AuditEvent per order action; outbox-backed execution history (confirmExecution / recordFailure) ties every transfer attempt to the order and its deterministic `so-exec-{orderId}-{executionDate}` key |
| **I**nfo disclosure | Leak creditor/debtor IBANs via logs, errors, or metrics | Consumer logs truncate payloads (`%.300s`) and never log full request bodies; error bodies carry codes only; metrics are low-cardinality (ADR-0077/0079) |
| **D**oS | Duplicate execution from Kafka redelivery or scheduler re-run; poison-pill event wedges the group | Deterministic idempotency key `so-exec-{orderId}-{executionDate}` reused as sepa-payment's `Idempotency-Key` (SEPA) and as `InitiateTransactionRequest.idempotencyKey` (own-account transfer, #889 follow-up) — a redelivery replays the same outcome instead of double-paying either way; consumer swallows and acks any failure (parse, rail, DB) so one bad event cannot wedge the group |
| **E**oP | Abuse an operator role to redirect a standing order to an attacker's IBAN | Order amendment is an authenticated write on the customer's own order (customer) or a logged operator action; the SEPA execution step re-runs sepa-payment's sanctions/AML screening on every execution, so a redirected creditor is screened at debit time, not only at order-creation time. **The own-account transfer path (DOMESTIC/INTERNAL, #889 follow-up) has no screening of its own** — `transaction-service`'s raw TRANSFER carries none — so it is deliberately restricted to creditor accounts belonging to the SAME party as the order (`resolveOwnAccountCreditor` compares `creditorPartyId` from the account-service lookup against the order's own `partyId`); a creditor resolving to a DIFFERENT party is refused rather than silently auto-routed around the screening `domestic-payment`'s `INTERNAL_CLIENT` scope would otherwise apply |

## 5. Residual risks / assumptions

- **Advisory authz:** `AUTHZ_ENFORCE=false` means the OPA sidecar logs but does not block; RBAC
  (`@RolesAllowed`) is the only enforced fine-grained gate until the enforce flip (tracked in the
  #3679 cohort).
- **Partially-wired rails (#889):** `DOMESTIC`/`INTERNAL` orders now execute when the creditor IBAN
  resolves to an account of the SAME party as the order (own-account move, transaction-service
  `TRANSFER`, no screening needed — the same case `domestic-payment` itself skips AML/sanctions for).
  A creditor IBAN that resolves to a DIFFERENT party, or to no internal account at all (genuinely
  external CZ payment), still records a failure each due day rather than being wired — the former
  because it would bypass `domestic-payment`'s `INTERNAL_CLIENT` screening, the latter because the
  external CZ clearing rail needs an IBAN→BBAN conversion this service does not implement. Both are
  visible as repeated failure events, never silent; tracked as a #889 follow-up.
- **Scheduler single-writer:** the daily sweep relies on the outbox claim protocol
  (`StandingOrderOutboxClaimIT`) to avoid double-dispatch across replicas; idempotency at
  sepa-payment is the backstop if that ever regresses.

## 6. Change log

- **2026-09-06** — Create DTO rejects a non-positive amount at the trust boundary (#8351
  burn-down). `CreateStandingOrderRequest.amountMinorUnits` is a Kotlin primitive, so Jackson
  silently substitutes 0 when the field is omitted — an undocumented bypass that would have
  created a zero-amount order; a new `init { require(amountMinorUnits > 0) }` turns the
  omission (and an explicit 0/negative) into a 400 via libs-runtime. The same PR corrects
  the POST request schema in openapi.yaml (1.6.0): it previously named fields the DTO never
  had (`debtorAccountId`, `amount`, `currencyCode`) and omitted five the DTO requires —
  including `idempotencyKey`, whose dedup replay the use case already enforced. No new
  endpoint, caller, role or network edge; the schema now understates nothing the code
  demands, so the change removes ambiguity rather than adding surface.

- **2026-08-24** — Synthetic-journey taint now propagates over this service's existing internal REST clients through `SyntheticTaintClientFilter` (ADR-0252, #4348). This adds no caller, endpoint, network-policy edge, privilege or payment-control bypass. It preserves the marker before a downstream persistence/event boundary; a fleet gate requires every new client to choose propagation or a reasoned external boundary.

- **2026-08-17** — DOMESTIC/INTERNAL execution routing + premature-completion fix (#889).
  **New trust boundaries added to §2:** service → account-service (`AccountServiceClient`,
  read-only IBAN lookup) and service → transaction-service (`TransactionServiceClient`, `TRANSFER`).
  Before this, every real standing order (the app only ever sends `DOMESTIC`/`INTERNAL`) fell to
  the unrouted `else` branch and failed on every due date, forever — accepted, shown ACTIVE,
  silently dead. Now: a creditor IBAN resolving to an account of the SAME party as the order books
  as a same-day `TRANSFER` (own-account move, no AML/sanctions gap — see §4 EoP row for why the
  same-party check is load-bearing, not incidental). A different party, or no resolvable account,
  still fails cleanly — the former needs `domestic-payment`'s screened `INTERNAL_CLIENT` path, the
  latter needs a BBAN-clearing rail this change does not build; neither is silently mis-routed.
  Independently, `StandingOrder.recordExecution` no longer completes a `ONCE` order at scheduling
  time (before the payment is even attempted) — only `confirmExecution` does, once the payment is
  actually confirmed, closing a gap where a failed one-off payment ended up `COMPLETED` with zero
  money moved and no failure ever recorded (`recordFailure`'s `ACTIVE`-only guard threw and was
  swallowed). Updated §2 (DFD, trust boundaries), §4 (DoS/EoP rows), §5 (residual risk reworded from
  "unwired" to "partially wired, and why the remaining gap is deliberate"). Rollback: revert the
  commit — the consumer falls back to recording a failure for every DOMESTIC/INTERNAL order, same
  as before this change, not a partial or inconsistent state.
- **2026-08-09** — `standingOrder.pause` narrowed from a role-only grant to an identity-scoped
  one (GHSA-58jq-9hq3-66jr, issue #4228): added `m2m-standing-order-pause` pinned to
  `service-account-openbank-edge`, then the `not startswith(input.principal.id,
  "service-account-")` exclusion on `operator-standing-order-pause`. Covered by
  `standing_order_rest_ext_test.rego` (9 cases; stripping either half reddens a disjoint subset).
- **2026-08-03** — Initial lightweight threat model (ADR-0030 D2), written for the
  `money_path_services` classification (issue #2188).
