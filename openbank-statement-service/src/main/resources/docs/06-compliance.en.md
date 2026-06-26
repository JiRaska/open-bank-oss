# Compliance

statement-service is a **compliance-domain** service (governance.yaml: `dataDomain: compliance`, `dataClassification: restricted`, 10-year retention). It produces the legally-significant account statement. It is **not** a money-path service (`rules.yaml: money_path_services`) — it reconciles against balances but never moves money.

## Regulatory framework

| Regulation | Relation to this service | Implementation |
|---|---|---|
| **PSD2** (Reg. (EU) 2015/2366) | Art. 58(2): statement of payment transactions provided *or made available* at least monthly, reproducible unchanged | "make available" model — only the period-close record is stored; camt.053/MT940/PDF rendered **deterministically on demand**, byte-identical |
| **ČNB / Czech accounting & AML retention** | 10-year keeping of the statement record | retention on the reproducible `statement_period` record |
| **ISO 20022 / SWIFT MT** | machine-readable statement formats | `Camt053Renderer` (camt.053.001.08), `Mt940Renderer` (MT940) |
| **GDPR** (Reg. (EU) 2016/679) | IBAN + holder name are PII | not persisted as rows — present only transiently at render time; AML retention overrides erasure for the record |
| **AMLD** (Anti-Money Laundering Directives) | statement = audit-grade financial record | fail-closed reconciliation guarantees integrity; 10-year retention |
| **DORA** (Reg. (EU) 2022/2554) | operational resilience | health probes, metrics/alerting, durable close-run telemetry, runbooks, resilient outbox |
| **NIS2** | network & info security | OIDC auth, mTLS in-cluster, security headers, audit via events |
| **PAD** (Payment Accounts Directive) | Art. 5 annual statement of fees | **out of scope** — that *push* obligation is owned by the fee/billing domain, not produced here |

## Integrity guarantee — fail-closed reconciliation (ADR-0035 §E)

The defining compliance control: a period-close's closing balance is `opening + booked net movement` and it **must equal** the closing balance reported independently by balance-service for that pocket.

```
opening ± Σ(booked entries)  ==  balance-service closing  ?
        │                                  │
        └────────── match ─────────────────┘ → close (assign legal sequence, emit event)
        └────────── mismatch ──────────────┘ → ReconciliationException → HTTP 409
                                                 (NO period record, NO event, recorded as a failure)
```

A self-inconsistent legal statement is **never** issued. The comparison is exact (`BigDecimal.compareTo`, scale-insensitive). Per-pocket failures during the scheduled cadence are persisted to `statement_close_failure` with `reason ∈ {RECONCILIATION, UPSTREAM, UNKNOWN}` and emitted as `period.close_failed`.

## Determinism guarantee (ADR-0035 §D/§F)

Every rendered format takes all timestamps from `StatementModel.closedAt` (stamped once at close and stored), never the wall clock. A re-render of the same closed period is byte-identical — guarded by the renderer unit tests. This is what makes the "store the record, not the file" model legal under PSD2 Art. 58(2) "reproducible unchanged".

## GDPR mapping

### Lawful basis (Art. 6)
- **Legal obligation** (Art. 6(1)(c)) — primary: PSD2 statement provision, ČNB/AML record-keeping.
- **Contract** (Art. 6(1)(b)) — secondary: the statement is part of performing the account contract.

### Data subject rights
| Right | Application |
|---|---|
| Access (Art. 15) | `GET /api/v1/statements/{accountId}` + on-demand render returns the subject's statements |
| Rectification (Art. 16) | corrections are issued as a **superseding** close (`SUPERSEDED` status, `supersedes_sequence`) — the legal sequence is never silently edited |
| Erasure (Art. 17) | **Not applicable** — AML/ČNB 10-year retention overrides for the record |
| Restriction (Art. 18) | handled upstream (account freeze in account-service) |
| Portability (Art. 20) | the ad-hoc export (`/{accountId}/{currency}/export`) and camt.053/MT940 machine formats |
| Object (Art. 21) | N/A (no marketing processing) |

### Personal data footprint
- **Stored:** `account_id`, `party_id` (pseudonymous identifiers), balance anchors, sequence metadata. **No IBAN, no holder name, no line-item descriptions are persisted as rows.**
- **Transient:** IBAN, holder name and entry descriptions appear only in the in-memory `StatementModel` during a render, and in the rendered output returned to the caller.

### Data flows out
- → **Kafka** `openbank.statement.event` (`account.statement.period.closed.v1`): `accountId`, `iban`, `pocketCurrency`, period, sequences, opening/closing balances, entry count, `closedAt`. Same data controller, intra-OpenBank (consumed by audit/downstream).
- → **callers** (admin-ui/customer app via Keycloak): rendered statement bytes for the authenticated subject.
- ← **inbound** from account-service `AccountCreated` (`accountId`, `partyId`, `currency`) into the local registry.
- **Upstream reads** (transaction/balance/account/party) stay intra-OpenBank over M2M tokens.

No data leaves the EU/EEA region.

## DORA mapping (Reg. (EU) 2022/2554)

| Article | Topic | Implementation |
|---|---|---|
| Art. 5/6 | ICT risk management | hexagonal isolation, centralised `openbank-libs` |
| Art. 9 | Identification | `BuildInfo` (gitCommit, buildTime, version) in `/api/v1/info` |
| Art. 10 | Detection | Micrometer/Prometheus metrics, close-cadence counters, `ServiceMonitor`/`PrometheusRule` alerting on close failures |
| Art. 11 | Response & recovery | self-healing close cadence, durable `statement_close_run`/`failure`, runbooks in [05-operations](./05-operations.md), resilient outbox (retry/circuit-breaker/DEAD) |
| Art. 16/17 | Incident management & reporting | `period.close_failed` events; outbox DEAD WARN; events to audit pipeline |
| Art. 28 | Third-party risk | no third-party SaaS — all self-hosted |

## Security controls

- ✅ AuthN: Keycloak OIDC, RS256 JWT (inbound bearer + outbound client-credentials M2M)
- ✅ AuthZ: Quarkus `@RolesAllowed` — reads vs mutations split by role
- ✅ Integrity: fail-closed reconciliation; transactional outbox (period + event commit atomically)
- ✅ Determinism: byte-identical re-render (no wall-clock leakage), guarded by tests
- ✅ Input validation: typed path/query params (UUID, ISO date, currency length)
- ✅ TLS: mTLS in-cluster; security headers (CSP, HSTS, X-Frame-Options, nosniff) set globally
- ✅ Auditability: every clean close emits a domain event; every failed pocket is recorded and emitted
- ⚠️ eIDAS-sealed / styled PDF and the styled consolidated envelope are documented follow-ups (ADR-0035)
