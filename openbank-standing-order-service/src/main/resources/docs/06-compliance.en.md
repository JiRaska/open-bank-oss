# Compliance

> **Money-path status:** standing-order-service is **NOT** in `rules.yaml: money_path_services`. It records the *intent* to pay recurrently but does not move money, hold balances, or post to the ledger — the actual debit/payment is materialized downstream (`transaction-service` → SEPA/domestic payment services), where the money-path controls (2 approvals, threat model, AML/sanctions gate) apply. This service therefore needs single-approval review and no threat model, but it still handles **confidential** PII (payee IBAN, names).

## Regulatory framework

| Regulation | Relation to this service | Implementation |
|---|---|---|
| **PSD2** (Reg. (EU) 2015/2366) | Standing orders are a payment instrument; the *mandate* lives here, execution is a downstream credit transfer | order lifecycle API; SCA and execution controls are enforced at the downstream payment surface |
| **GDPR** (Reg. (EU) 2016/679) | Stores payee IBAN/name + debtor party reference (confidential PII) | classification `confidential`, log masking, AML-bounded retention |
| **AMLD** (Anti-Money Laundering Directive) | Recurring instructions are an evidence/record-keeping concern; screening is enforced when the payment is materialized (ADR-0032) | downstream screening gate; 5-year mandate record retention |
| **DORA** (Reg. (EU) 2022/2554) | Operational resilience | health probes, outbox resilience stack (circuit breaker/retry/bulkhead/timeout), metrics, `/api/v1/info` build identification |
| **NIS2** | Network & information security | OIDC auth, OPA authz, in-cluster mTLS, security response headers (CSP/HSTS/…) |
| **CNB / ISO 13616** | IBAN structure for creditor | `creditor_iban` validated per IBAN format (downstream payment service performs the authoritative mod-97 check) |

## GDPR mapping

### Lawful basis (Art. 6)

- **Contract** (Art. 6(1)(b)) — primary: maintaining the customer's recurring payment instruction is necessary to perform the payment-services contract.
- **Legal obligation** (Art. 6(1)(c)) — secondary: AML/payment record-keeping.

### Personal data held

| Data | Role | Source |
|---|---|---|
| `creditor_iban`, `creditor_name`, `creditor_bic` | payee identifiers (PII) | client request |
| `remittance_info` | free text, potentially PII | client request |
| `party_id` | debtor (pseudonymized ref to party-service) | client request |
| `debit_account_id` | pseudonymized ref to account-service | client request |

### Data subject rights

| Right | Application |
|---|---|
| Access (Art. 15) | `GET /api/v1/standing-orders/party/{partyId}` returns the subject's orders |
| Rectification (Art. 16) | cancel + recreate (orders are immutable instructions; no in-place edit endpoint yet — TBD) |
| Erasure (Art. 17) | constrained by AML record-keeping during the active retention window; cancellation sets terminal status, data retained for evidence |
| Restriction (Art. 18) | `pause` removes the order from execution without deleting it |
| Portability (Art. 20) | bulk export — not yet implemented (TBD) |
| Object (Art. 21) | N/A (no marketing processing) |

### Data flows out

- → **transaction-service** (Kafka `openbank.standing-orders.order.event`): order/execution intent — same controller, intra-OpenBank, materializes the actual payment.
- → **audit-service** (Kafka): event payload for the audit trail — same controller.
- No data leaves the EU/EEA region (intra-OpenBank only).

### Retention (Art. 5(1)(e))

`governance.yaml: retentionPolicy: 5 years`.

| Order status | Retention |
|---|---|
| ACTIVE / PAUSED | ongoing while the mandate is live |
| CANCELLED / COMPLETED | 5 years after termination (mandate evidence, dispute, AML record-keeping) |
| `standing_order_outbox` | operational only; purged after delivery |

## DORA mapping (Reg. (EU) 2022/2554)

| Article | Topic | Implementation |
|---|---|---|
| Art. 5 / 6 | ICT risk management framework | dependency on centralized `openbank-libs`; per-service governance manifest |
| Art. 9 | Identification | `BuildInfo` (gitCommit, buildTime, version) via `/api/v1/info` |
| Art. 10 | Detection | Micrometer metrics + Prometheus; outbox lag observable |
| Art. 11 | Response & recovery | outbox resilience stack (circuit breaker/retry/bulkhead/timeout); scheduler swallows errors and resumes; runbooks in [05 — Operations](./05-operations.md) |
| Art. 16/17 | Incident management & reporting | domain events to audit-service for evidence |
| Art. 28 | Third-party risk | no third-party SaaS — all self-hosted |

## PSD2 — standing orders

A standing order is a customer payment instrument. This service is the **mandate store**; strong customer authentication (SCA, ADR-0021) and the AML/sanctions screening gate (ADR-0032) are enforced at the **point of execution** in the downstream payment surface, not here. When wiring customer-facing creation through the customer app, SCA on order setup is a downstream/orchestration concern.

## Authorization (ADR-0034)

- Decisions are delegated to an **OPA sidecar** via `openbank-libs` `@Authorize`.
- Mode is **advisory by default** (`authz.enforce=false`) — decisions are logged but not enforced until the environment flips `AUTHZ_ENFORCE=true`.
- Coverage today: `pause` is annotated (`standingOrder.pause`). `create`, `resume`, `cancel`, and the read endpoints are **not yet annotated** — completing authorization coverage is a tracked follow-up (TBD).

## Security controls

- ✅ AuthN: Keycloak OIDC bearer token (RS256), realm `openbank`.
- ✅ AuthZ: OPA sidecar (`@Authorize`), advisory→enforce phased (ADR-0034).
- ✅ Idempotent creation: client `idempotencyKey`, DB-unique (replay-safe).
- ✅ Transactional outbox with at-least-once delivery + resilience stack.
- ✅ Security response headers: CSP `default-src 'self'`, HSTS, X-Frame-Options DENY, nosniff, Referrer-Policy, Permissions-Policy.
- ✅ Rate limiting: `openbank.rate-limit` (max 200 concurrent).
- ✅ Audit: lifecycle events emitted to audit-service.
- ⚠️ Authorization coverage incomplete (only `pause` annotated) — TBD.
- ⚠️ OpenAPI contract drift vs implementation (fields, port, missing list endpoint) — reconcile, see [03 — API](./03-api.md).
- ⚠️ Execution scheduler / SCA-on-setup not yet wired in this build — TBD.
