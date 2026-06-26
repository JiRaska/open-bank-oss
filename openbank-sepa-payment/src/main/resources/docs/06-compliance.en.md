# Compliance

`openbank-sepa-payment` is a **money-path** service (`rules.yaml: money_path_services`). It carries the full money-path control set: 2-approval review, an up-to-date threat model ([`docs/threat-models/openbank-sepa-payment.md`](../../../../docs/threat-models/openbank-sepa-payment.md), ADR-0030), and the synchronous screening gate (ADR-0032).

## Regulatory framework

| Regulation | Relation to this service | Implementation |
|---|---|---|
| **SEPA Credit Transfer Rulebook (EPC)** | SCT instruction structure, charge bearer, end-to-end id | `charge_bearer` defaults to `SLEV` with `chk_sepa_charge_bearer`; `end_to_end_id`, `purpose_code`, `category_purpose` columns |
| **ISO 20022 (pain/pacs)** | Payment field semantics | `purpose_code`, `regulatory_reporting`, `instructed_agent_bic` align to ISO 20022 |
| **PSD2 (Reg. (EU) 2015/2366) + RTS** | SCA, TPP consent, fraud controls | `sca_reference` (RTS Art. 97), `consent_id` (TPP), role-gated initiation; SCA itself in `sca-service` (ADR-0021) |
| **AMLD / sanctions** | Screen names before release; freeze on hit | synchronous sanctions screen on create, fail-closed (ADR-0032); AML case opened on hit/hold |
| **GDPR** | IBANs and names are PII | confidential classification, 7-year retention overriding erasure for payment records, log masking |
| **DORA (Reg. (EU) 2022/2554)** | Operational resilience of a payment hop | T0 always-on (ADR-0057), fault-tolerant clients, health probes, audit events, runbooks |
| **NIS2** | Network & info security | mTLS in-cluster, OIDC, OPA authz, security headers (CSP/HSTS/X-Frame-Options) |

## ADR-0032 — synchronous sanctions/AML screening gate (the core control)

On create, **both** the debtor and creditor names are screened synchronously against the sanctions lists **after** the `RECEIVED` row is durably persisted, so a value-bearing instruction is never released un-screened and never lost:

- **CLEAR** → payment transitions to `VALIDATED`.
- **REVIEW** (sub-threshold potential hit) → payment **held in `RECEIVED`** for a human decision; an `AML_HOLD` case (HIGH) is opened.
- **BLOCK** (HIT / ESCALATED / score > 0.85) → payment `REJECTED` with `SANCTIONS_HIT`; a `SANCTIONS_HIT` case (CRITICAL) is opened.
- **Screening unavailable** → **fail-closed**: payment held in `RECEIVED`, a `SCREENING_UNAVAILABLE` case (MEDIUM) is opened. The gate never releases on a screening outage.

Opening the AML case is best-effort and never flips the verdict already rendered by `ScreeningPolicy`.

## GDPR mapping

### Lawful basis (Art. 6)
- **Contract** (Art. 6(1)(b)) — executing the customer's payment instruction.
- **Legal obligation** (Art. 6(1)(c)) — AML screening, PSD2/SEPA record-keeping, sanctions compliance.

### Data subject rights

| Right | Application |
|---|---|
| Access (Art. 15) | `GET /api/v1/sepa-payments?debtorAccountId=…` returns the subject's payments |
| Rectification (Art. 16) | payment instructions are immutable once accepted; corrections via compensating status transition |
| Erasure (Art. 17) | **Not applicable** to settled payment records — payment/AML record-keeping (7 years) overrides |
| Restriction (Art. 18) | a payment can be held (`RECEIVED`) or rejected; no further processing |
| Portability (Art. 20) | export of the subject's payment list (admin tooling) |

### Data flows out

- → **clearing-service / ledger-service** (Kafka `openbank.sepa.payment.events`): payment id, amounts, IBANs — intra-OpenBank, same controller.
- → **audit-service** (Kafka): full event payload for the tamper-evident audit trail.
- → **sanctions-service** (REST, synchronous): debtor/creditor **names** for screening — processor relationship, intra-OpenBank.
- → **aml-service** (REST): customer reference (name / IBAN), matched entity, alert code — for the AML case.

No data leaves the EU/EEA region.

### Retention (Art. 5(1)(e))

Declared **7 years** (`governance.yaml`). Payment records and screening evidence are retained for the AML/PSD2 statutory period regardless of GDPR erasure requests.

## DORA mapping (Reg. (EU) 2022/2554)

| Article | Topic | Implementation |
|---|---|---|
| Art. 5/6 | ICT risk management | money-path service in the central register; dependency = openbank-libs |
| Art. 9 | Identification | `BuildInfo` (gitCommit, buildTime, version) in `/api/v1/info` |
| Art. 10 | Detection | Micrometer/Prometheus metrics; outbox lag + 5xx alerting |
| Art. 11 | Response & recovery | T0 always-on; fault-tolerant screening clients; runbooks in `05-operations.md` |
| Art. 16/17 | Incident management & reporting | domain events to audit-service for evidence; major incidents reported via audit pipeline |
| Art. 28 | Third-party risk | no third-party SaaS — sanctions/AML are self-hosted OpenBank services |

## PSD2 — SCA & TPP

- **SCA** (RTS Art. 97): customer-initiated transfers reference SCA evidence via `sca_reference`; the decoupled approval flow lives in `sca-service` (ADR-0021, no auto-approve).
- **TPP consent**: `consent_id` references the consent validated by `consent-service`.
- **Roles**: initiation is restricted to `ROLE_OPERATOR` / `ROLE_ADMIN` / `ROLE_PAYMENTS`; `ROLE_VIEWER` is read-only — a distinct payments role prevents a viewer initiating a transfer (threat model EoP control).

## STRIDE summary (from the threat model)

| Threat | Mitigation |
|---|---|
| Spoofing | OIDC + role; mTLS for service callers |
| Tampering | server-validated, immutable once accepted; audit trail |
| Repudiation | AuditEvent + SCA evidence + correlation id |
| Info disclosure | role-scoped reads; PII masking |
| DoS | rate limit (`openbank.rate-limit`), idempotency |
| Elevation of privilege | distinct `ROLE_PAYMENTS`, deny-by-default, OPA enforce |

## Audit trail

Every create and every status change produces a domain event drained to `audit-service`, which persists it with a tamper-evident chain. The screening verdict and any AML case are part of the evidence (`evidenceExported: true`).

## Security controls

- ✅ Input validation (DTO + domain state-machine guards)
- ✅ AuthN: Keycloak OIDC (JWT)
- ✅ AuthZ: `@RolesAllowed` + OPA `@Authorize` (ADR-0034, advisory→enforce)
- ✅ Idempotency: required on create (Redis + DB UNIQUE)
- ✅ Synchronous sanctions/AML screening gate, fail-closed (ADR-0032)
- ✅ Transactional outbox (no lost or un-audited state change)
- ✅ Security headers: CSP, HSTS, X-Frame-Options DENY, X-Content-Type-Options nosniff
- ✅ Secrets: dev placeholders MUST be overridden via Vault in prod
- ⚠️ IBAN tokenisation: not implemented — tracked as a residual risk in the threat model
