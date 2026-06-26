# Compliance

`openbank-clearing-service` is a **money-path** service (`rules.yaml: money_path_services`). It aggregates many payments into settlement — a high-blast-radius operation — so the stricter governance regime applies: **2 approvals + a maintained threat model** (`docs/threat-models/openbank-clearing-service.md`, ADR-0030) on every change to `src/main/**`.

## Regulatory framework

| Regulation | Relation to this service | Implementation |
|---|---|---|
| **PSD2 / SEPA Regulation (260/2012)** | Clearing & settlement of SEPA SCT / SCT Inst payments; rail-aware cycles | `payment_rail` enum, per-rail cycle trigger, net positions |
| **SEPA Instant / SCT Inst scheme** | Instant rail clearing | `SEPA_SCT_INST` rail |
| **AMLD (Anti-Money Laundering Directive)** | Settlement records are AML-relevant evidence; screening is enforced upstream (ADR-0032 gate at payment surfaces) | 7-year retention; immutable audit via outbox events |
| **GDPR** | IBAN + remittance info are PII held on clearing items | confidential classification, mask-in-logs, retention-over-erasure |
| **DORA** (Reg. (EU) 2022/2554) | Operational resilience of a critical settlement function | health probes, outbox circuit-breaker/bulkhead/retry, metrics, runbooks |
| **NIS2** | Network & info security | mTLS in-cluster, OIDC, OPA authz, security response headers |
| **Settlement finality (Dir. 98/26/EC)** | Net/gross settlement semantics, position finality | `settlement_type` (GROSS/NET/DEFERRED_NET), `SettlementPosition.settled` |

## Access control (money-path hardening)

Per-operation least-privilege (ADR-0018, replacing a prior class-level `@PermitAll`):

| Operation | Roles | Extra |
|---|---|---|
| `submit` | `SERVICE`, `PAYMENTS`, `ADMIN` | service/payment-ops identity |
| reads (batches/items/positions) | `SERVICE`, `VIEWER`, `OPERATOR`, `PAYMENTS`, `ADMIN` | broad read |
| `settle` | `PAYMENTS`, `ADMIN` | `@Authorize(clearingBatch.settle)` (OPA, advisory → enforce in Phase 5) |
| `cycle/trigger` | `PAYMENTS`, `ADMIN` | high-blast-radius |

Settle and cycle-trigger are the high-blast-radius actions; four-eyes (MakerChecker, ADR-0034) is a tracked follow-up. Enforcement is locked by `ClearingResourceSecurityTest` / `ClearingSecurityContractTest`.

## GDPR mapping

### Lawful basis (Art. 6)
- **Contract** (Art. 6(1)(b)) — clearing a payment is necessary to execute the payment instruction the customer initiated.
- **Legal obligation** (Art. 6(1)(c)) — AML and payment record-keeping.

### Personal data held
- `debtor_iban`, `creditor_iban` — account identifiers (PII).
- `remittance_info` — free-text payment reference, may contain PII.
- BICs and `participant_bic` are institution identifiers (low sensitivity).

### Data subject rights

| Right | Application |
|---|---|
| Access (Art. 15) | clearing items located via `GET /items/by-payment/{paymentId}` (joined upstream to the data subject) |
| Rectification (Art. 16) | not applicable — clearing items are immutable records of an executed instruction |
| Erasure (Art. 17) | **Not applicable** — AML + settlement record-keeping (7-year retention) overrides |
| Restriction (Art. 18) | upstream payment hold; clearing operates on already-authorised instructions |
| Portability (Art. 20) | N/A (no direct customer relationship here) |

### Data flows out
- → **transaction-service** (declared downstream, relation `api`, "settles") — settlement results.
- → **Kafka** `openbank.clearing.batch.event` (via `clearing_outbox`) — batch settlement events for downstream/audit consumers; same controller, intra-OpenBank.
- No data leaves the EU/EEA region.

### Retention (Art. 5(1)(e))
`governance.yaml: retentionPolicy: 7 years`. Clearing items, batches and settlement positions are retained for the statutory AML/accounting period. Outbox rows are operational and pruned after successful delivery.

## DORA mapping (Reg. (EU) 2022/2554)

| Article | Topic | Implementation |
|---|---|---|
| Art. 5/6 | ICT risk management framework | central `openbank-libs`, governance.yaml, money-path classification |
| Art. 9 | Identification | `BuildInfo` in `/api/v1/info` |
| Art. 10 | Detection | Micrometer/Prometheus metrics, outbox FAILED rows with `last_error` |
| Art. 11 | Response & recovery | outbox `@CircuitBreaker`/`@Bulkhead`/`@Retry`/`@Timeout`, runbooks in `05-operations.md`, Flyway repair procedure |
| Art. 16/17 | Incident management & reporting | settlement events emitted to the audit pipeline via outbox |
| Art. 28 | Third-party risk | no third-party SaaS — Postgres/Kafka/Keycloak/OPA all self-hosted |

## Settlement integrity controls

- ✅ **Positive-amount invariant** — DB CHECK `amount > 0` on items, `total_debit/credit >= 0` on batches (V4).
- ✅ **Transactional outbox** — settlement events are written in the same transaction as the aggregate change, drained at-least-once to Kafka.
- ✅ **Idempotent id allocation** — V3 sequence fix prevents runtime INSERT failures.
- ✅ **Unique constraints** — `batch_reference` unique; `(participant_bic, currency, cycle_id)` unique on positions.
- ✅ **Reactive resilience** — `@Retry`/`@Timeout` on submit/cycle; circuit-breaker on the dispatcher.

## Security controls

- ✅ AuthN: Keycloak OIDC, bearer JWT.
- ✅ AuthZ: per-operation `@RolesAllowed` (least-privilege) + `@Authorize`/OPA on settle (advisory, ADR-0034).
- ✅ Security headers: nosniff, DENY framing, CSP `default-src 'self'`, HSTS.
- ✅ Secrets: `CHANGE_ME_LOCAL_DEV_ONLY` placeholders → Vault in prod (ADR-0017).
- ✅ Threat model: maintained STRIDE/DFD at `docs/threat-models/openbank-clearing-service.md`.
- ⚠️ Idempotency-store guard on mutations: header + Redis wired, explicit enforcement partial — tracked.
- ⚠️ Four-eyes (MakerChecker) on settle/trigger: tracked follow-up (ADR-0034).
- ⚠️ OPA enforce mode: advisory today (`AUTHZ_ENFORCE=false`), graduating to enforce in Phase 5.

## Audit trail

Batch settlement produces a domain event written to `clearing_outbox` and published to `openbank.clearing.batch.event`; downstream audit consumers persist it for the statutory period. Outbox rows carry `event_id`, `aggregate_id`, `event_type`, `attempt_count` and `last_error` for forensic traceability.
