<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — openbank-dispute-service

STRIDE/DFD threat model for the dispute and complaints bounded context, per ADR-0030 D2.
The service is **not** a money-path service (it does not originate or authorise payments) but it
handles sensitive customer data, regulatory-deadline obligations, and controls that can admit or
suppress PSD2 redress — making it a high-sensitivity target.

- **Status:** Draft (Phase 1 scope — complaints + existing disputes; ADR-0085 §1–§2 implemented)
- **Last reviewed:** 2026-06-23
- **Owner:** dispute CODEOWNERS
- **Related ADRs:** ADR-0002 (hexagonal), ADR-0009 (Postgres-per-service), ADR-0029 (governance),
  ADR-0030 (threat-model requirement), ADR-0034 (OPA unified authz), ADR-0049 (outbox libs),
  ADR-0067 (feature flags), ADR-0077 (DomainMetrics), ADR-0085 (complaints handling — this service)

---

## §0 Scope

`openbank-dispute-service` is the bounded context for:

- **Disputes (chargebacks):** internal ledger-side contested-transaction workflow.
- **Complaints:** regulatory complaints under PSD2 Art. 100–101 (ADR-0085 Phase 1).
  A complaint has a statutory deadline clock: 15 business days from receipt (`dueDate`), extendable
  to 35 business days on an interim reply. `breached` is derived at read-time against the injected
  clock — it is never persisted, so the source of truth for the deadline is `due_date` in Postgres.
- **Outbox events:** every complaint state change (received / interim-reply / resolved / closed)
  writes a `complaint.*` event to the shared `dispute_outbox` table, dispatched to Kafka via the
  `DisputeOutboxDispatcher`. No outbound HTTP calls are made; the service is purely reactive.

Assets in priority order:

1. **Deadline integrity** — `due_date` and `received_date` in Postgres. An off-by-one here is a
   regulatory breach (PSD2 Art. 101 fine exposure).
2. **Status trail** — the immutable sequence of status transitions (RECEIVED → RESOLVED / CLOSED).
   A tampered trail enables fraudulent complaint suppression or manufactured false-redress.
3. **Complaint payload** — `description`, `account_id`, `transaction_id`, `outcome`, `root_cause_code`.
   All fields contain PII, IBAN references, or commercial intelligence.
4. **Outbox event integrity** — `complaint.*` events fan out to downstream consumers (reporting,
   regulatory register — Phase 2). A suppressed or replayed event corrupts downstream views.

---

## §1 Regulatory context

| Regulation | Obligation | Service responsibility |
|---|---|---|
| PSD2 Art. 100 | Payment-service-related complaints must be answered (initial reply accepted, substantive required within 15 BD) | `dueDate` = `receivedDate` + 15 CZK business days via `BusinessCalendar.forCurrency("CZK")` |
| PSD2 Art. 101 | Interim reply extends deadline to 35 BD (only where justified) | `interimReply` endpoint records reason + extends `dueDate`; status remains RECEIVED (open) |
| EBA/ESMA JC 2018 35 | Complaint taxonomy: PAYMENT\_SERVICE / FEES / ACCOUNT\_SERVICE / LENDING / CONDUCT / DATA\_PROTECTION / OTHER | `ComplaintCategory` enum; persisted in `complaint_category` PG type |
| DORA Art. 17–19 | Significant ICT incidents (including mass complaint surges as operational disruption indicators) must be reported | `ComplaintDeadlineGauge` metric surfaces breach count; a spike in `breached=true` complaints is an incident signal |
| GDPR Art. 5(1)(e) | Storage limitation — complaint records contain PII | Retention policy required (Phase 2 follow-up; no purge path implemented today) |

A breach of the PSD2 deadline clock (§0, asset 1) is a direct regulatory violation; the threat
analysis gives it highest weight.

---

## §2 Data-flow diagram (textual)

```
                 ┌───────────────────── trust boundary: dispute-service ──────────────────────────┐
 [Customer /     │                                                                                 │
  Operator /     │  REST (ComplaintResource / DisputeResource)                                    │
  Service]  ──1──┼─▶  @RolesAllowed + @Authorize (OPA advisory)                                  │
   Bearer JWT    │          │                                                                      │
   (Keycloak)    │          ▼                                                                      │
                 │  ComplaintService / DisputeService (use case)                                   │
                 │      - BusinessCalendar (CZK, injected Clock)                                   │
                 │      - deadline arithmetic (domain-pure, no framework)                          │
                 │          │                           │                                          │
                 │          ▼                           ▼                                          │
                 │  [Postgres] ──2──        [Postgres] dispute_outbox ──3──                        │
                 │   complaints table        (shared with disputes)                                │
                 │   disputes table                     │                                          │
                 │                           DisputeOutboxDispatcher                               │
                 │                                      │ (periodic poll)                          │
                 └──────────────────────────────────────┼──────────────────────────────────────────┘
                                                        │
                                                        ▼
                                            [Kafka] complaint.* / dispute.* topics  ──4──▶ consumers
                                                   (regulatory register — Phase 2)
```

Trust-boundary crossings:

- **(1)** External caller → REST: JWT-gated, every endpoint requires a role (no `@PermitAll`).
- **(2)** App → Postgres: reactive Panache, Vault-projected credentials, forward-only Flyway.
- **(3)** Outbox dispatcher → Kafka: transactional-outbox pattern (ADR-0049); at-least-once
  delivery; no direct producer call from the use case — only row insertion in the same DB tx.
- **(4)** Kafka → downstream: consumers are external; the service does **not** consume any topic
  in Phase 1. No outbound HTTP clients.

---

## §3 STRIDE analysis

| # | Element | Threat (STRIDE) | Mitigation | Residual |
|---|---------|-----------------|------------|----------|
| S1 | REST /complaints | **Spoofing** — caller forges identity to lodge a complaint on behalf of another customer's account/transaction (e.g. uses a valid JWT for a different customer to file `accountId` = victim's IBAN) | Bearer JWT (Keycloak); `ROLE_OPERATOR`/`ROLE_ADMIN`/`ROLE_SERVICE` required to POST; customer self-service endpoint not yet exposed (Phase 2); `@Authorize(action="complaint.update")` on mutating ops via OPA | OPA is advisory, not enforced — resource-level ownership check (caller's party == `accountId` owner) not yet implemented; **open** |
| T1 | complaints table | **Tampering** — status manipulation to fraudulently resolve a complaint (e.g. skip RECEIVED→RESOLVED transition or reopen a CLOSED complaint) | Status transitions enforced server-side in `ComplaintService`; `close()` requires both `outcome` + `rootCauseCode`; DB `complaint_status` PG enum prevents unknown states; row `updated_at` tracked | No DB-level CHECK constraint on transition ordering (RECEIVED→RESOLVED→CLOSED only); an operator with direct DB access can revert; infra-scope; **accepted for sandbox** |
| T2 | deadline clock | **Tampering** — deadline clock manipulation via deferred cron or system-clock skew to artificially avoid breach reporting (the `breached` flag is computed against `LocalDate.now(clock)` injected at startup; the underlying `due_date` is immutable in DB) | `due_date` and `received_date` are written once at intake and **never updated** (only `interim_reply` extends `due_date` through the proper use-case path with reason recorded); `BusinessCalendar` uses CZK calendar from `openbank-libs`, not wall-clock arithmetic; clock is injected (`Clock.system(Europe/Prague)`) so tests prove correctness | A system-clock drift on the pod or a misconfigured NTP could cause `now()` to lag, masking breaches; `ComplaintDeadlineGauge` metric provides breach-count observability; **open** (NTP / clock-source hardening in infra) |
| R1 | complaint lifecycle | **Repudiation** — operator denies a complaint was lodged or that a specific action (interim reply, close) was taken | Every state change persists an immutable outbox row (`complaint.*` event with `complaintId`, `reference`, `status`, `receivedDate`, `dueDate`) before the use-case returns; `created_at`/`updated_at` on every complaint row | Outbox events are not yet signed or archived to a tamper-evident store; Kafka topic retention is finite; for PSD2 audit evidence an append-only audit log or signed evidence bundle (ADR-0029 D2) is required — **open** |
| I1 | complaint payload | **Information disclosure** — complaint `description`, `account_id`, `transaction_id`, `outcome` fields expose PII, IBAN references, and commercial intelligence; the `GET /complaints` list endpoint returns all of this | `ROLE_VIEWER` can read all complaints (list + get); endpoint is Keycloak-gated; no `@PermitAll` path; cluster-internal (ingress not exposed to internet) | `ROLE_VIEWER` is broad — any viewer can read all complaints across all customers; attribute-level filtering (GDPR right-to-access scoping) not implemented; **open** — needs per-party scoping before customer-facing Phase 2 |
| I2 | metrics | **Information disclosure** — `ComplaintDeadlineGauge` or outbox metrics could leak complaint volume/timing patterns per category | `ComplaintDeadlineGauge` emits only breach-count with no PII labels (count of `breached=true`); `/q/metrics` is cluster-internal only | Category breakdown not currently emitted; if added later, `category` is a closed 7-value enum (safe); account/party labels must never appear — ADR-0077 cardinality contract |
| D1 | POST /complaints | **Denial of Service** — flood of complaint creation requests exhausts the regulatory queue and blocks legitimate breach-monitoring (a complaint backlog > 15 BD is itself a regulatory violation) | Reactive non-blocking stack (Mutiny/Vert.x); per-service k8s resource limits; outbox decouples Kafka fan-out from the request path | No rate limiting at the endpoint level (gateway rate-limit is infra scope); a burst of malformed requests that reach the DB will consume connections; **open** — rate-limit at gateway or via Quarkus `@RateLimited` |
| E1 | ROLE_OPERATOR | **Elevation of privilege** — operator promotes own actions to close complaints outside a four-eyes requirement (single operator can file, then immediately close the same complaint without a second approver) | `@Authorize(action="complaint.update")` on `/resolve` and `/close`; OPA advisory policy logs the action; every close records `rootCauseCode` (traceability) | Four-eyes enforcement for complaint close is **not yet implemented** (Phase 2 ADR-0085 §4); a single `ROLE_OPERATOR` can complete the full lifecycle; **open** |
| S2 | OIDC credential | **Spoofing (shared-credential blast radius)** — dispute-service reuses the shared `openbank-services` Keycloak confidential client; compromise of that credential mints tokens accepted across services | Secret Vault-projected (never in git/state); confidential (not public) client | Per-service dedicated OIDC credential not provisioned (sandbox risk-accepted; required before prod — ADR-0030) — **open** |

---

## §4 Key invariants (must never regress)

1. **`received_date` and `due_date` are written once at intake and never mutated** except through
   the `interimReply` use-case path, which requires a `reason` and extends to exactly 35 CZK
   business days from `received_date` — never an arbitrary value.
2. **Deadline arithmetic uses `BusinessCalendar.forCurrency("CZK")`**, an injected `Clock`
   (Europe/Prague), and the `openbank-libs` calendar primitive — never `LocalDate.now()` directly.
   The injected clock makes every deadline computation deterministically testable.
3. **Status transitions are one-way: RECEIVED → RESOLVED → CLOSED.** There is no path back from
   CLOSED or RESOLVED to RECEIVED. `close()` sets both `closedAt` and `resolvedAt` (if not already
   set) atomically.
4. **No endpoint is `@PermitAll`.** The class-level `@RolesAllowed("ROLE_VIEWER", ...)` ensures
   every endpoint requires a valid Keycloak token; unauthenticated callers receive 401.
5. **Every state change writes an outbox row in the same DB transaction** (`complaintRepo.save` /
   `complaintRepo.update` each accept an `OutboxMessage`). A complaint state change without a
   corresponding outbox event is impossible unless the DB transaction is rolled back.
6. **`breached` is never persisted.** It is a derived field computed at read-time from
   `due_date` vs. the injected clock. The authoritative breach truth is `due_date` in Postgres;
   in-memory derivation cannot be tampered with at the DB layer.
7. **No outbound HTTP clients.** The service has no `@RegisterRestClient` or HTTP producer;
   its only external surface is the Kafka outbox. A compromise of the service cannot directly
   exfiltrate data to an attacker-controlled HTTP endpoint.

---

## §5 Open items (Phase 2 follow-ups)

These are the known gaps that must be closed before the service is used in a production-regulated
context (corresponding to ADR-0085 §3–§5, which are **not yet implemented**):

- **Four-eyes redress (E1, ADR-0085 §4):** A single operator can complete the full complaint
  lifecycle (file → close) without a second approver. Four-eyes on `close` / `resolve` is
  required before the service handles real PSD2 complaints. Issue: `#851`.
- **Resource-level ownership check (S1):** The `@Authorize` OPA policy for `complaint.update`
  does not yet verify that the acting operator is allowed to act on the specific `accountId` /
  `partyId` linked to the complaint. This enables cross-customer complaint manipulation by a
  rogue operator. Requires OPA enforce phase (ADR-0034).
- **Complaint register projection (ADR-0085 §3):** Downstream register (Phase 2) consuming
  `complaint.*` Kafka events for regulatory reporting is not yet built; the outbox emits events
  but nothing consumes them. Until the register is live, the PSD2 Art. 100 reporting obligation
  is partially met only.
- **GDPR retention / purge path:** No automated purge or anonymisation of closed complaints past
  a retention window. Required before go-live (GDPR Art. 5(1)(e)).
- **Signed audit / evidence bundle (R1, ADR-0029 D2):** Outbox events are unverified; a
  tamper-evident signed record is needed for dispute evidence in arbitration.
- **Rate limiting (D1):** No per-endpoint rate limit; gateway-level throttle is an infra
  follow-up before customer-facing channels open.
- **Customer self-service channel (Phase 2 — S1 scope expansion):** ADR-0085 §2 plans a
  customer-facing POST path (APP channel). When that opens, the resource-level ownership check
  (S1 above) becomes critical — a customer must only be able to file a complaint against their
  own accounts/transactions.
- **DomainMetrics (ADR-0077):** `openbank_complaints_open_total` / `openbank_complaints_breached_total`
  deferred to avoid a libs fleet rebuild (TODO in `ComplaintService` source, `#851` item 2).
- **Dedicated OIDC credential (S2):** Provision a per-service Vault path + dedicated confidential
  Keycloak client before production.

---

## §6 Change log

| Date | Change | Author |
|---|---|---|
| 2026-06-23 | Initial draft — ADR-0085 Phase 1 (complaints + disputes, STRIDE §3, invariants §4) | ADR-0030 D2 requirement |
