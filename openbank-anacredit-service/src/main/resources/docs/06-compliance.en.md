# Compliance

## Regulatory framework

| Regulation | Relation to this service | Implementation |
|---|---|---|
| **AnaCredit — Reg. (EU) 2016/867** (ECB; nationally collected by ČNB) | The reason the service exists: it builds the granular credit dataset for legal-entity credit exposures | `AnaCreditReturnBuilder` + `AnaCreditMapper` render the credit/financial dataset rows; `AnaCreditEligibilityPolicy` applies scope + the €25 000 threshold |
| **ČNB statistical-reporting obligation** | OpenBank must report AnaCredit to ČNB | v1 **renders only** — no SDMX submission transport (documented v1 non-goal, ADR-0037) |
| **GDPR** | Dataset is legal-entity-only by design; natural persons are out of scope | `HOUSEHOLD_OUT_OF_SCOPE` exclusion keeps natural-person debtors out of the return; `restricted` data classification |
| **DORA — Reg. (EU) 2022/2554** | Operational resilience of a reporting service | health probes, `BuildInfo` in `/api/v1/info`, audit-friendly exclusion trail, FinOps T1 tier |
| **NIS2** | Network & info security | hardened response headers (CSP, HSTS, X-Frame-Options DENY), Keycloak OIDC, in-cluster TLS |
| **CRR / capital reporting (adjacent)** | AnaCredit data feeds supervisory credit analysis | derive-only; this service produces the granular feed, not the COREP/FINREP returns |

This service is **not** on the money-path (`rules.yaml: money_path_services`); it moves no money and emits no events, so the ADR-0030 gate (threat model + 2 approvals) does **not** apply.

## GDPR mapping

### Scope of personal data

The reportable AnaCredit dataset covers **legal entities only**. Natural-person (household / consumer) debtors are deliberately excluded from the rendered return with reason `HOUSEHOLD_OUT_OF_SCOPE`, so the output contains **no natural-person personal data by design**. A natural-person exposure may exist in the (now durable, PostgreSQL-backed) exposure store if fed in, but it never reaches the return.

### Lawful basis (Art. 6)

- **Legal obligation** (Art. 6(1)(c)) — AnaCredit reporting under Reg. (EU) 2016/867 and the ČNB collection mandate is the primary basis for processing credit-exposure data.

### Data subject rights

| Right | Application |
|---|---|
| Access (Art. 15) | for in-scope rows the subject is a legal entity (not a GDPR data subject); any incidental natural-person exposure is excluded from output |
| Rectification (Art. 16) | re-POST the exposure (`upsert` by `instrumentId`) with corrected values |
| Erasure (Art. 17) | **constrained** — regulatory record-keeping (`retentionPolicy: 10 years`, now enforced against a durable `credit_exposures` row rather than a volatile in-memory map) overrides erasure for in-scope reporting data |
| Restriction (Art. 18) | exclusion mechanism (drop from return) provides a natural restriction surface |
| Portability (Art. 20) | N/A — regulatory reporting, not a consumer-data service |

## Data flows

### In

- ← **operator / upstream feed** (REST `POST /exposures`): credit-instrument attributes incl. `debtorId`, native and EUR amounts. `committedAmountEur` is caller-supplied (sourced from `openbank-fx-service`).

### Internal

- The return is computed in-process by pure domain code; no data is sent to another service during rendering.

### Out

- → **API caller** (REST response): the rendered `AnaCreditReturn` (legal-entity records + exclusion trail). No outbound Kafka, no downstream service call.
- → **ČNB**: **not in v1** — there is no submission transport; an operator extracts the rendered return manually until the SDMX channel is built.

No data leaves the EU/EEA region.

## DORA mapping (Reg. (EU) 2022/2554)

| Article | Topic | Implementation |
|---|---|---|
| Art. 9 | Identification | `BuildInfo` (gitCommit, buildTime, version) in `/api/v1/info` |
| Art. 10 | Detection | metrics + health probes (`/q/health`) |
| Art. 11 | Response & recovery | durable Postgres store (ADR-0037 v2) ⇒ recovery is a standard DB restore, not a full re-feed; runbook in [05 — Operations](./05-operations.md); no money state at risk |
| Art. 28 | Third-party risk | no third-party SaaS — self-hosted; no external dependency at runtime |

## Audit trail

The AnaCredit return is **self-auditing**: every instrument either appears as a `CreditRecord` or as an `ExclusionNote` carrying a stable reason code (`HOUSEHOLD_OUT_OF_SCOPE` / `BELOW_THRESHOLD` / `NO_EXPOSURE`). `reportableCount` + `excludedCount` reconcile against the input exposure count, giving a complete, explainable derivation for a regulator or auditor. The service emits **no** domain events to `audit-service` in v1 (derive-only); auditability is provided by the deterministic, reproducible render over the input set.

## Security controls

- ✅ AuthN: Keycloak OIDC bearer token (realm `openbank`)
- ✅ AuthZ: Quarkus `@RolesAllowed` (`ROLE_OPERATOR / ROLE_ADMIN / ROLE_AUDITOR / ROLE_COMPLIANCE / ROLE_API`)
- ✅ Hardened HTTP headers: CSP `default-src 'self'`, HSTS, `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, Referrer-Policy, Permissions-Policy
- ✅ CORS: restricted to the admin-UI origins
- ✅ Input validation: typed enums (`CounterpartyType`, `InstrumentType`), `BigDecimal` amounts, ISO-date parsing
- ✅ Output safety: Jackson serialization; legal-entity-only output keeps natural-person PII out by construction
- ✅ Durability: PostgreSQL-backed `credit_exposures` table (ADR-0037 v2) — survives pod restarts; the v1 in-memory store has been removed
- ⚠️ Submission: no automated ČNB transport — manual extraction, tracked as the ADR-0037 non-goal
- N/A Idempotency keys / outbox: not needed — `upsert`-by-id registration and pure reads, no events
