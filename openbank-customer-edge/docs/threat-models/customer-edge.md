# Threat model — openbank-customer-edge

**Classification:** RESTRICTED  
**Owner:** Security Engineering  
**Last updated:** 2026-06-16  
**ADR references:** ADR-0065 (edge proxy), ADR-0021 (SCA), ADR-0030 (threat model governance),
ADR-0086 (customer audit chain)

---

## Service overview

`openbank-customer-edge` is the **sole ingress point** for all customer-app traffic (ADR-0065).
It is classified **money-path** because every payment instruction passes through it. Responsibilities:

1. Validate the customer JWT (Keycloak `openbank-customers` realm).
2. Enforce ownership (IDOR guard) before proxying account/balance/payment reads.
3. Enrich and forward payment instructions to domestic/SEPA/transfer services.
4. Gate money-moving operations behind a consumed SCA challenge (ADR-0021).
5. Emit tamper-evident audit events to `openbank.customer.audit` (ADR-0086).

---

## Actors

| Actor | Trust level | Notes |
|-------|-------------|-------|
| Authenticated customer | Low — JWT from mobile app | Passes OIDC validation; party_id/sub claim is the identity anchor |
| Anonymous caller | Untrusted | Only `/customer/v1/onboarding/start` is exposed unauthenticated |
| Upstream services (account, payment, SCA…) | High — M2M token | Edge is the only caller; no direct public exposure |
| audit-service | High — same cluster | Receives audit events; never calls back |
| Adversary (unauthenticated) | Hostile | Internet-facing via ingress |
| Adversary (authenticated) | Hostile | Compromised customer credential or stolen JWT |

---

## Assets

| Asset | Sensitivity | Protection goal |
|-------|-------------|-----------------|
| Customer JWT | SECRET | Integrity & confidentiality — invalidated on expiry/revocation |
| Payment instructions (amount, creditor IBAN) | CONFIDENTIAL | Integrity — SCA dynamic linking binds them to the challenge |
| Account ownership mapping | CONFIDENTIAL | Confidentiality — IDOR guard prevents cross-party reads |
| Audit log (openbank.customer.audit) | REGULATED | Integrity — hash-chain (ADR-0086), non-repudiation evidence |
| M2M service token | SECRET | Confidentiality — cached in-process; never returned to caller |
| Customer PII in transit | SENSITIVE | TLS 1.2+ enforced at ingress; edge strips before forwarding |

---

## Threats and controls

### T1 — JWT spoofing / token replay

**Threat:** Attacker forges or replays a customer JWT to impersonate another party.

**Controls:**
- OIDC signature validation by Quarkus (HS256/RS256 from Keycloak JWKS endpoint).
- Short expiry (default 5 min) + audience check (`openbank-edge`).
- `party_id`/`sub` extracted from the validated token; never from the request body.

---

### T2 — IDOR (insecure direct object reference)

**Threat:** Authenticated customer guesses another party's `accountId` to read their balance,
transactions, or statement.

**Controls:**
- Edge resolves the account and checks `partyId == JWT party` before proxying any read.
- 403 (not 404) on mismatch — no existence oracle.
- `debtorAccountId` in payment bodies parsed with Jackson (last-wins) so the ownership check
  and the upstream see the same value (double-key bypass closed).

---

### T3 — Payment without SCA (settlement gate bypass)

**Threat:** Attacker replays or fabricates a payment without a device-signed SCA challenge.

**Controls:**
- Every money-moving route (`createDomesticPayment`, `createSepaPayment`) calls
  `sca-service /challenges/{id}/consume` with dynamic linking (amount + currency + creditor).
  A missing or already-consumed challenge returns 403 before the payment rail is touched.
- Own-account transfers are SCA-exempt under PSD2 RTS Art. 15 — the exemption is injected
  server-side (`scaExemption`), never read from the body.

---

### T4 — Prompt injection via customer-controlled fields

**Threat:** A customer embeds adversarial text in payment reference fields that could manipulate
the AI copilot (ADR-0089) if those fields are reflected into a model context.

**Controls:**
- All user-supplied string fields that pass through the edge are treated as data, not
  instructions, in the copilot layer (ADR-0089 D3).
- The edge does not expose any LLM-adjacent route; prompt injection at this layer is
  out-of-scope (mitigated upstream in copilot-service).

---

### T5 — Kafka audit channel failure (silent audit gap)

**Threat:** A Kafka outage silently drops audit events, leaving a gap in the regulated trail.

**Controls (residual risk — accepted per ADR-0086):**
- `EdgeAuditPublisher` catches all exceptions and logs ERROR; the operation is never blocked.
- Overflow buffer (1024 messages in-process) absorbs short broker hiccups.
- Kafka replication factor ≥ 2 in production.
- **Residual risk:** a prolonged Kafka outage during money-moving operations produces an audit
  gap. Mitigation: audit-service integrity endpoint (`GET /api/v1/audit/integrity`) detects
  gaps via the hash chain; DORA Art. 17 incident response procedures cover the remediation.

---

## ADR-0086 Audit Chain

### Overview

Every customer-initiated action emits a structured JSON event to Kafka topic
`openbank.customer.audit`. audit-service consumes these alongside domain events and appends them
to a SHA-256 hash-chained log (`audit_entries`). Any in-place edit, deletion or re-ordering of
audit rows is detectable by recomputing the chain from the genesis hash.

### Actors (audit chain context)

| Actor | Role |
|-------|------|
| Customer (JWT party) | Initiator — `actorPartyId` and `partyId` in every event |
| customer-edge | Publisher — the only service that knows the customer identity |
| audit-service | Custodian — appends, hash-chains, and serves the integrity endpoint |
| DORA incident responder | Reader — `GET /api/v1/audit/integrity?fromEventId=<uuid>` |

### Assets

| Asset | Threat | Control |
|-------|--------|---------|
| Audit log integrity | Log tampering (row edit, delete, re-order) | SHA-256 hash chain per ADR-0086; `verifyChain()` recomputes every link |
| Audit log completeness | Replay / missing events | Chain break detected at the first gap; `unchained` counter covers pre-chain rows |
| Audit log confidentiality | Unauthorised read of regulated evidence | `GET /api/v1/audit/*` endpoints are `@RolesAllowed(ROLE_AUDITOR, ROLE_ADMIN, ROLE_COMPLIANCE)` — never `@PermitAll` |

### Threats

**TM-A1 — Log tampering:** An operator or DBA edits an `audit_entries` row to remove evidence
of a fraudulent payment. **Control:** hash chain — the record_hash of any modified row diverges
from the recomputed value; `GET /api/v1/audit/integrity` reports `chainStatus: BROKEN` and
identifies `firstBrokenAt`.

**TM-A2 — Event replay injection:** An attacker replays an old audit event with the same
eventId to create a false trail. **Control:** `entry_id` is UNIQUE in the DB; duplicate inserts
are rejected at the persistence layer. The hash chain also detects insertion of duplicate rows
(the chain sequence is broken).

**TM-A3 — Audit denial-of-service:** An attacker floods the `openbank.customer.audit` topic
to exhaust audit-service DB capacity. **Control:** Kafka consumer group rate-limited; audit
topic ACLs restrict write access to the edge service account only. audit-service has its own
connection pool and reactive write path.

### Compliance

| Regulation | Requirement | How met |
|------------|-------------|---------|
| DORA Art. 17 | Tamper-evident ICT incident log | Hash-chain + integrity endpoint |
| GDPR Art. 30 | Processing activity record | `actorId` + `eventType` + `occurredAt` retained 10 years (GDPR audit retention) |
| PSD2 RTS 2018/389 | Payment instruction non-repudiation | SCA dynamic linking + audit event per payment attempt |
