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
irreversible debit (sanctions/AML gate, Temporal workflow, ledger booking). `DOMESTIC`/`INTERNAL`
rails are not wired yet and are recorded as failures, never silently dropped.

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
                     outcome           --- |---> [confirmExecution / recordFailure -> order state + outbox]
```

- **External entities:** customer-edge (authenticated customer), admin-UI (ROLE_OPERATOR),
  sepa-payment (M2M callee).
- **Trust boundaries:** edge → service (OIDC + OPA sidecar, currently AUTHZ_ENFORCE=false
  advisory); service → Kafka (mTLS via Strimzi); service → sepa-payment (OIDC client credentials,
  `openbank-services` client, ROLE_OPERATOR accepted by sepa-payment's createPayment).
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
| **D**oS | Duplicate execution from Kafka redelivery or scheduler re-run; poison-pill event wedges the group | Deterministic idempotency key `so-exec-{orderId}-{executionDate}` reused as sepa-payment's `Idempotency-Key` — a redelivery replays the cached 201 instead of double-paying; consumer swallows and acks any failure (parse, rail, DB) so one bad event cannot wedge the group |
| **E**oP | Abuse an operator role to redirect a standing order to an attacker's IBAN | Order amendment is an authenticated write on the customer's own order (customer) or a logged operator action; the money-moving step re-runs sepa-payment's sanctions/AML screening on every execution, so a redirected creditor is screened at debit time, not only at order-creation time |

## 5. Residual risks / assumptions

- **Advisory authz:** `AUTHZ_ENFORCE=false` means the OPA sidecar logs but does not block; RBAC
  (`@RolesAllowed`) is the only enforced fine-grained gate until the enforce flip (tracked in the
  #3679 cohort).
- **Unwired rails:** `DOMESTIC`/`INTERNAL` orders record an execution failure each due day until
  the rail clients land — visible as repeated failure events, never silent; tracked as a #889
  follow-up.
- **Scheduler single-writer:** the daily sweep relies on the outbox claim protocol
  (`StandingOrderOutboxClaimIT`) to avoid double-dispatch across replicas; idempotency at
  sepa-payment is the backstop if that ever regresses.

## 6. Change log

- **2026-08-09** — `standingOrder.pause` narrowed from a role-only grant to an identity-scoped
  one (GHSA-58jq-9hq3-66jr, issue #4228): added `m2m-standing-order-pause` pinned to
  `service-account-openbank-edge`, then the `not startswith(input.principal.id,
  "service-account-")` exclusion on `operator-standing-order-pause`. Covered by
  `standing_order_rest_ext_test.rego` (9 cases; stripping either half reddens a disjoint subset).
- **2026-08-03** — Initial lightweight threat model (ADR-0030 D2), written for the
  `money_path_services` classification (issue #2188).
