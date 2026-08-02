<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — openbank-card-issuance-service

- **Date:** 2026-06-30
- **Status:** Lightweight STRIDE/DFD (ADR-0030 D2). Payment-instrument data context.
- **Service ADR:** [ADR-0113](../adr/0113-card-issuance-bounded-context.md) (card issuance bounded context — virtual-first, no external processor)

## 1. Scope & purpose

Card registry and lifecycle manager: issue virtual/physical cards (debit, credit, prepaid),
enforce lifecycle transitions (PENDING→ACTIVE→SUSPENDED/BLOCKED→CANCELLED), store reference
spending limits. **Not** a card processor — no real-time authorisation, no PIN management, no 3DS,
no external network connection in sandbox. Sandbox `maskedPan` is synthetic (no real PAN stored).

## 2. Data flow (DFD)

```
[Operator / Admin UI] --OIDC--> (REST /api/v1/cards*) --> [card-issuance-service] --> [(Postgres: cards)]
                                                                   |
                                                                   +--> [(card_outbox)] --outbox--> [Kafka card events]
                                                                              card.issued.v1
                                                                              card.status_changed.v1
```

- **External entities:** operators/admins (human, OIDC via Keycloak), compliance officers
  (`ROLE_COMPLIANCE` on block); downstream event consumers (fraud-service, analytics).
- **Trust boundaries:** UI↔service (mTLS + OIDC + OPA authz, ADR-0034);
  service↔Postgres (connection pool, credentials from Vault/ExternalSecret);
  service↔Kafka (outbox relay, ADR-0050).
- **Assets:** card identity (`id`, `maskedPan`), cardholder PII (`cardholderName`,
  `embossedName`), lifecycle state, spending limits, `partyId`/`accountId` linkage.

## 3. Authn/Authz

| Endpoint | Allowed roles |
|---|---|
| `POST /api/v1/cards` (issue) | `ROLE_OPERATOR`, `ROLE_ADMIN` |
| `GET /api/v1/cards`, `GET /{id}`, `GET /account/{id}`, `GET /party/{id}` | `ROLE_VIEWER`, `ROLE_OPERATOR`, `ROLE_ADMIN` |
| `POST /{id}/activate`, `/{id}/suspend`, `/{id}/resume` | `ROLE_OPERATOR`, `ROLE_ADMIN` |
| `POST /{id}/block` | `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_COMPLIANCE` |

All mutations require `Idempotency-Key` or `X-Operator-Id` header; resource-level
`@Authorize` enforces OPA policy (ADR-0034, advisory→enforce).

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing | Caller impersonates operator to issue or block a card | OIDC bearer + mTLS; no anonymous mutation; `ROLE_COMPLIANCE` scoped to block only |
| **T**ampering | Forced lifecycle skip (e.g. PENDING→BLOCKED direct), limit mutation | Domain state-machine guards (`Card.activate/block/suspend/resume`) enforce legal transitions; DB constraints; `version` for optimistic locking (if added — see §5) |
| **R**epudiation | Operator denies issuing or changing card status | `card.issued.v1` + `card.status_changed.v1` emitted for every mutation through transactional outbox (ADR-0050); `changedBy` field on status-change event carries operator identity |
| **I**nfo disclosure | Cardholder name / maskedPan / limits leaked via list endpoint | `ROLE_VIEWER` read access; OPA `card.read/list` policy gates on operator; no PII in event subjects; sandbox: maskedPan is synthetic — not a mask of a real PAN |
| **I**nfo disclosure | Card enumeration via `GET /party/{partyId}` returns another party's cards | OPA `card.list` resource policy scoped to `#partyId` — operators see all; customer-facing access requires a customer-edge ownership check (see §5) |
| **I**nfo disclosure | `cardholderName` / `embossedName` in Kafka events observed by unauthorized consumer | Kafka topic ACLs restrict consumption to authorized services; outbox events carry only `partyId`, `accountId`, `cardType`, `network`, `maskedPan`, `previousStatus`/`newStatus`, `changedBy` — no full name in event payload |
| **D**oS | Mass card issuance churn | Idempotency on `issueCard` (duplicate `idempotencyKey` returns existing card, no second DB write); gateway rate limits; outbox decouples event load |
| **E**oP | Viewer escalates to issue or block | Distinct roles enforced at JAX-RS layer (`@RolesAllowed`) + OPA; deny-by-default |
| **T**ampering | Race between `suspend` and `block` on same card | Domain `require` guards throw `IllegalArgumentException` on illegal state; a concurrent block on a SUSPENDED card is valid by design; concurrent suspend+block both succeed on ACTIVE → last writer wins idempotently (both move toward frozen state) — acceptable; optimistic locking would make this exact (see §5) |

## 5. Residual risks / assumptions

- **No optimistic locking today.** `Card` lacks a `version` column; two concurrent lifecycle
  mutations could overwrite each other at the DB layer. Acceptable in sandbox (single-operator
  workflow); add `@Version` / optimistic lock before production deployment.
- **Customer-edge ownership gap.** `GET /party/{partyId}` returns all cards for that party to
  any role. A customer-facing edge would need to validate that the requesting customer IS that
  party before proxying — this service does not enforce customer-identity binding.
- **`ROLE_COMPLIANCE` can block any card.** Compliance-initiated block has no four-eyes check.
  Consider MakerChecker (ADR-0034) for BLOCKED→permanent transitions in production.
- **Spending limits are reference data only.** `dailyLimitMinorUnits` / `monthlyLimitMinorUnits`
  are stored but not enforced in real time. A future authorisation component must consume them
  and be the enforcement point.
- **Relies on Keycloak realm integrity and OPA policy correctness.**
- **GDPR erasure.** `cardholderName` and `embossedName` are PII. The PARTY_ERASED event
  (ADR-0117) must trigger erasure of these fields from the `cards` table; until that subscriber
  is implemented, erasure is manual.

## 6. Change log

- **2026-06-30** — Initial threat model authored (ADR-0113 delivery gate).
  Sandbox: maskedPan synthetic, no real PAN stored, PCI DSS CHD scope not triggered.
- **2026-08-01** — Card delegation-grant enforcement projection (ADR-0232 D3, issue #2990):
  `card_delegation_projection` fed by `CardDelegationEventConsumer` from
  `openbank.delegation.events` (CARD-scoped events only), and a `CardDelegationGuard` answering
  holder OR an ACTIVE in-window grant for VIEW / MANAGE_LIMITS intents, exposed as
  `GET /api/v1/cards/{id}/delegation/check` (reuses the existing `card.read` OPA action — no rego
  change). **Risk class = elevation of privilege** (a delegate seeing card metadata they should
  not; PAN/secure-details stay outside delegation scope entirely — no CARD capability maps to
  `secure-details`). Same structural properties as the account slice: local-only enforcement (no
  synchronous call to delegation-service, tripwire test), additive guard (holder path untouched),
  DLQ so a close event is never destroyed, idempotent upsert per grant id. Residual: seconds-level
  revoke propagation per ADR-0232; customer-edge adoption of the check endpoint is its own slice.
  Rollback: revert; the projection tables are droppable without touching `cards`.
