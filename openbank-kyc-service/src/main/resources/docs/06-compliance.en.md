# Compliance

`openbank-kyc-service` is the platform's KYC/CDD case-management service. It is **not** on the `money_path_services` list in `rules.yaml` (it moves no money), but it is a **compliance-critical, restricted-data** service: it is the audit-grade record of how a customer cleared Customer Due Diligence.

## Regulatory framework

| Regulation | Relation to this service | Implementation |
|---|---|---|
| **AMLD (4/5/6)** — Anti-Money Laundering Directives | Core: CDD/EDD, PEP screening, sanctions check, record-keeping | KYC case lifecycle, `pep_declaration`, `due_diligence_level` (SDD/CDD/EDD), 10-year retention |
| **EBA AML/CFT Guidelines** | Risk-based due diligence, periodic review | `risk_level`, `next_review_date` (HIGH 1yr / MEDIUM 2yr / LOW 3yr), escalation fields |
| **FATF Recommendations** (esp. R.10, R.12) | CDD, source of funds/wealth, PEP | `source_of_funds`, `source_of_wealth`, `business_purpose`, `expected_turnover` |
| **CNB / Czech AML Act (253/2008 Sb.)** | National AML transposition — identification & control | identity/address checks, beneficial owner (`beneficial_owner_id`) |
| **GDPR** | KYC data is restricted / special-category-adjacent | role-gated access, pseudonymous `party_id`, 10-year AML retention overrides erasure |
| **PSD2** | KYC clearance precedes account/payment onboarding | KYC status consumed by onboarding; not a direct PSD2 surface |
| **DORA** (Reg. (EU) 2022/2554) | Operational resilience | health probes, outbox resilience, audit events, SLO, runbooks |
| **NIS2** | Network & info security | mTLS in-cluster, security response headers, OPA authz, audit log |

## Four-eyes control (ADR-0068)

Approval and rejection are **dual-control** actions: `POST /cases/{id}/approve` and `/reject` require `ROLE_ADMIN`/`ROLE_KYC` **and** pass through `@Authorize` (OPA, ADR-0034). The operator who opened/worked a case must not be the one approving it — enforced via the onboarding cockpit four-eyes policy. The sandbox straight-through path (`openbank.kyc.auto-approve`) bypasses this and **must be false in production**; an approval attributed to `sandbox-auto-approval` outside sandbox is a compliance incident.

## GDPR mapping

### Lawful basis (Art. 6)

- **Legal obligation** (Art. 6(1)(c)) — primary: AML/CFT identification and due diligence are statutory obligations (AMLD, Czech AML Act).
- **Contract** (Art. 6(1)(b)) — secondary: KYC is a precondition of the banking contract.

KYC findings can touch **special-category-adjacent** data (adverse media, PEP). Access is restricted to KYC/compliance/admin roles.

### Data subject rights

| Right | Application |
|---|---|
| Access (Art. 15) | `GET /api/v1/kyc/cases/party/{partyId}` returns the subject's case state |
| Rectification (Art. 16) | check re-evaluation via `PUT …/checks/{checkType}` (audit logged) |
| Erasure (Art. 17) | **Not applicable** during the statutory window — AMLD 10-year retention overrides |
| Restriction (Art. 18) | case can be held in a non-terminal state (e.g. `UNDER_REVIEW` / escalation) |
| Portability (Art. 20) | N/A — AML records are legal-obligation processing, not portable |
| Object (Art. 21) | N/A — no marketing/consent-based processing here |

### Data flows out

- → **party-service** (events / API): KYC outcome drives party activation — `partyId`, `status`. Same controller, intra-OpenBank.
- → **aml-service / sanctions-service** (Kafka `openbank.kyc.events`): `kycCaseId`, `partyId`, `status`, `riskLevel` to trigger / correlate screening.
- → **notification-service** (Kafka): event metadata for customer/ops notifications.
- → **audit-service** (Kafka / events): full decision trail for evidence.

Inbound: ← **party-service** (`openbank.party.events`, `PARTY_CREATED`) to auto-open a case.

No data leaves the EU/EEA region. No third-party SaaS in the request path (the actual screening provider integration is in `sanctions-service`).

### Retention (Art. 5(1)(e))

| Case status | Retention |
|---|---|
| Active (OPEN … UNDER_REVIEW) | ongoing |
| APPROVED / REJECTED / EXPIRED | **10 years** after relationship end (AMLD record-keeping, governance.yaml) |

## DORA mapping (Reg. (EU) 2022/2554)

| Article | Topic | Implementation |
|---|---|---|
| Art. 5 | ICT risk management | service in the central operations register |
| Art. 6 | Risk framework | dependency = openbank-libs (centralized) |
| Art. 9 | Identification | `BuildInfo` (gitCommit, buildTime, version) on `/api/v1/info` |
| Art. 10 | Detection | Micrometer metrics + alerting on error rate / latency / outbox lag |
| Art. 11 | Response & recovery | runbooks in `05-operations.md`, RTO 15 min / RPO 5 min; outbox `@CircuitBreaker`/`@Retry` |
| Art. 16 | Incident management | domain events to audit-service for evidence |
| Art. 28 | Third-party risk | no third-party SaaS in this service — screening provider isolated in sanctions-service |

## Audit trail

Every state transition emits a domain event (`KYC_CASE_OPENED` / `_STATUS_CHANGED` / `_APPROVED` / `_REJECTED`) via the transactional outbox → `audit-service`, which persists it for the statutory period. The four-eyes decision (`reviewed_by`, `reviewed_at`, rejection `notes`) is stored on the case itself.

## Security controls

- ✅ AuthN: Keycloak OIDC, RS256 JWT bearer
- ✅ AuthZ: Quarkus `@RolesAllowed` per endpoint + `@Authorize` (OPA, ADR-0034) on four-eyes / check mutations
- ✅ Dual control: four-eyes approve/reject (ADR-0068)
- ✅ Domain idempotency: `uq_kyc_cases_active_party` prevents duplicate active cases under replay/scale-out
- ✅ Input validation: Bean Validation on request DTOs; enum-bounded `checkType` / status
- ✅ Output security headers: HSTS, CSP `default-src 'self'`, X-Frame-Options DENY, X-Content-Type-Options nosniff
- ✅ Resilience: outbox `@Bulkhead`/`@CircuitBreaker`/`@Retry`/`@Timeout`, poison-pill-safe consumer
- ✅ Secrets: DB/OIDC secrets via env, Vault in prod; dev placeholders must be overridden
- ✅ Audit: every state change → audit-service via event
- ⚠️ OPA authz is **advisory** by default (`authz.enforce=false`) — flip to enforce per the ADR-0034 rollout
- ⚠️ Sandbox auto-approve (`openbank.kyc.auto-approve`) must be verified `false` in every non-sandbox environment
