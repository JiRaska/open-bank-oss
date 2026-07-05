# ADR-0126 — Unified Consent Lifecycle and GDPR Linkage

| Field            | Value                          |
|------------------|--------------------------------|
| Status           | Accepted                       |
| Decision-Status  | Accepted                       |
| Delivery-Status  | Partial                        |
| Deciders         | Jiri Raska                     |
| Date             | 2026-06-29                     |
| Supersedes       | —                              |
| Superseded by    | —                              |

## Context

OpenBank handles three overlapping consent regimes that previously lacked a unified ADR:

1. **PSD2 Art. 65/66/67 account-access consents** (AISP/PISP/CBPII) — regulated by the Berlin Group and ČOBS profile, managed in `openbank-consent-service` (port 8106).
2. **GDPR Art. 7 data-processing consents** — e.g. RUM telemetry opt-in (`TELEMETRY_RUM` scope), which requires demonstrable, auditable consent and a withdrawal path that propagates to all processors.
3. **AI Agent delegation scopes** (`AGENT_QUERY`, `AGENT_INITIATE`, …) — bank extension on top of PSD2 consents for the AI Copilot (ADR-0089).

Without a single ADR codifying the lifecycle, key delivery gaps accumulated:
- No scheduled job to transition `ACTIVE` consents past their `valid_to` date to `EXPIRED`, leaving `isActive()` returning `false` with no downstream event for resource servers to react.
- No explicit GDPR linkage: consent withdrawal (`DELETE /consents/{id}`) must propagate to all processors via `ConsentRevoked` / `ConsentExpired` Kafka events.
- OPA authorization is in advisory mode (`authz.enforce=false`) — lacks explicit rationale and a flip plan.

## Decision

`openbank-consent-service` is the **single consent authority** for all three regimes.

### State machine

```
PENDING_SCA ──► ACTIVE ──► EXPIRED   (sweeper, §D4)
    │               │
    ▼               ▼
REJECTED        REVOKED
    │               │
    └──── SUPERSEDED (same grantee + scopes, new grant)
```

All transitions are append-only in the audit trail; status column is the current state.

### Scope partitions and validity caps (PSD2 RTS Art. 10)

| Scope group                     | Max validity | SCA-gated |
|---------------------------------|--------------|-----------|
| AISP (ACCOUNTS_READ, BALANCES_READ, TRANSACTIONS_READ, STATEMENTS_READ) | 90 days | Yes |
| PISP, CBPII, ČOBS extensions     | 365 days     | Yes       |
| AI Agent scopes                  | 365 days     | Yes (per-transaction for AGENT_INITIATE) |
| TELEMETRY_RUM                    | 365 days     | No        |

The `Consent` aggregate root enforces these caps in its `init` block.

### GDPR Art. 7 linkage

- `TELEMETRY_RUM` consent is the demonstrable record gating the public RUM ingest endpoint (ADR-0088 D4b). Its `status` field is the single source of truth: `ACTIVE` = consent given, any other status = consent absent.
- Customer-triggered revocation (`DELETE /api/v1/consents/{id}`) publishes `ConsentRevoked` to `consent.events` topic. All processors (notification-service, analytics-sink, psd2-service) must react and cease processing within 72 h (GDPR Art. 17).
- Scheduled expiration (§D4) publishes `ConsentExpired` with the same semantics — downstream processors treat it identically to `ConsentRevoked`.

### D1 — Consent creation and SCA binding (Shipped)

`POST /api/v1/consents` creates a `PENDING_SCA` record. The TPP (or bank agent) then drives SCA via `openbank-sca-service`; `POST /api/v1/consents/{id}/activate` completes the binding after SCA succeeds. SCA session ID is stored on the consent for non-repudiation.

### D2 — Consent validation by resource servers (Shipped)

`POST /api/v1/consents/{id}/validate` is the machine-to-machine gate called by `account-service`, `balance-service`, `transaction-service` etc. before returning data. Response: `{valid: true/false}` with `scope`, `grantedAccounts`, `frequencyPerDay`. No PII in the response.

### D3 — Revocation propagation (Shipped)

`DELETE /api/v1/consents/{id}` (customer-initiated) or `POST /api/v1/consents/{id}/reject` (SCA failure) publishes `ConsentRevoked` / `ConsentRejected` via the transactional outbox to the `consent.events` Kafka topic. Idempotency key prevents duplicate processing.

### D4 — Scheduled expiration sweep (✅ Shipped)

A cron job (`ConsentExpirationJob`) runs hourly at minute 5. It finds all `ACTIVE` consents where `valid_to < now`, transitions them to `EXPIRED`, and persists a `ConsentExpired` outbox entry per consent in the same Hibernate Reactive transaction. The outbox dispatcher then publishes to `consent.events`.

Without this job, expired consents remain `ACTIVE` in the DB — `isActive(now)` returns `false` correctly for in-process validation, but downstream consumers never receive the expiration event. D4 closes that gap.

**Implemented** in `openbank-consent-service/src/main/kotlin/com/openbank/consent/infrastructure/ConsentExpirationJob.kt` — reactive Uni pipeline, `Clock`-injected for testability, logs `consent.expiration.sweep expired=%d` on each sweep.

### D5 — OPA enforcement (Planned)

OPA sidecar is wired (`authz.enforce=true` default in consent-service config, advisory mode inherited from libs default `false`). Flip `AUTHZ_ENFORCE=true` in gitops after the OPA policy for consent endpoints is validated in staging. Tracked as a follow-up to ADR-0034, actionable in issue #263.

## Consequences

- **resource servers** must subscribe to `consent.events` and cache the validation result for the configured `frequencyPerDay` window to avoid hammering the consent API (ADR-0090 N3).
- **ConsentExpired** and **ConsentRevoked** are semantically equivalent for downstream: both mean "cease processing". Consumers must handle both event types.
- The 90-day AISP cap is enforced at consent creation time by the domain model (hard constraint). A TPP re-consent flow is required after expiration — this is by PSD2 design, not a gap.
- `TELEMETRY_RUM` consents are NOT subject to the SCA requirement or the 90-day cap. They are opt-in UX consents, not account-access consents.
- `openbank-consent-service` is a **money-path service** (rules.yaml). Any change requires 2 approvals + threat model review (docs/threat-models/openbank-consent-service.md).

## References

- PSD2 Directive (EU) 2015/2366, Art. 65/66/67
- EBA RTS on SCA (EU) 2018/389, Art. 10
- Berlin Group NextGenPSD2 Framework
- ČOBS 2.x CZ national profile
- GDPR (EU) 2016/679, Art. 7, 17
- ADR-0034 (OPA unified authz)
- ADR-0050 (transactional outbox)
- ADR-0059 (oversight webhooks — includes CONSENT_REVOKED signal)
- ADR-0088 D4b (RUM telemetry consent gate)
- ADR-0089 (customer AI copilot — AGENT_* scopes)
- ADR-0090 (PSD2 Berlin Group + ČOBS profile)
- ADR-0118 (GDPR data lifecycle — downstream processing cessation)
