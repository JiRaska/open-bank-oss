# Compliance

> **Scope note:** the product catalog holds **reference data only — no personal data and no funds flow through it**. It is **not** a money-path service (not in `rules.yaml: money_path_services`), so it does **not** require the 2-approval + threat-model gate (ADR-0030). Its compliance relevance is **pricing transparency and product-information accuracy**, not transaction or PII processing.

## Regulatory framework

| Regulation | Relation to this service | Implementation / status |
|---|---|---|
| **GDPR** | No personal data is processed or stored. | `dataClassification: internal`; product/pricing reference data only — no PII, no data-subject dimension. |
| **DORA** (Reg. (EU) 2022/2554) | Operational resilience of an ICT dependency used during onboarding and billing. | SmallRye Health probes, PostgreSQL persistence, `BuildInfo`/`/api/v1/info`, and runbooks in [05 — Operations](./05-operations.md). Restore and publication evidence remain delivery work. |
| **NIS2** | Network & info security. | mTLS in-cluster (Istio), CORS allowlist + security response headers (CSP, HSTS, X-Frame-Options) in `application.yaml`. |
| **Consumer Credit Directive (2008/48/EC) / CCD2 (EU) 2023/2225** | Declared APR/rates and fee transparency for loan, mortgage, overdraft and credit-card products. | Catalog declares `baseRate`, `overdraftConfig` and the fee schedule. The mutable v1 `versionHistory` is not audit evidence; immutable approved revisions are required by ADR-0257. |
| **PAD — Payment Accounts Directive (2014/92/EU)** | Comparable fee information for payment accounts. | `GET /api/v1/fees` exposes a single, structured, filterable fee schedule (the FID source data). |
| **MiFID II** | Investment product information. | `INVESTMENT_BASIC` modelled as DRAFT/non-public; management/transaction fees declared. (Investment go-live is out of scope until the product is published.) |
| **CNB consumer-protection / transparency rules** | Accurate product and price information for the Czech market. | CZK products (`CURRENT_CZK`, `SAVINGS_CZK`, `TERM_DEPOSIT_6M_CZK`) carry Czech-language `termsAndConditions`. |

## GDPR mapping

No lawful-basis / data-subject-rights table applies in the usual sense: **there is no personal data in this service**. The catalog stores product definitions and pricing (commercial/internal data). If a future feature were to attach customer-specific pricing or eligibility decisions, that processing would belong in a different service (offer/eligibility), and this section would be revisited.

| GDPR aspect | Application here |
|---|---|
| Personal data categories | None |
| Lawful basis | N/A (no personal data) |
| Data-subject rights | N/A (no data subjects) |
| Retention | `indefinite` for product/version history — transparency evidence, no PII to erase |
| International transfers | None (no personal data leaves anywhere) |

## Data flows

```
admin-ui  ──GET/POST/PUT /products, GET /fees──►  product-catalog
account / interest / fx / card services  ──read product defs──►  product-catalog
```

- All flows are **intra-OpenBank, reference data**. No personal data, no money movement, no external (TPP/PSD2) exposure.
- The catalog makes **no downstream calls** and publishes **no events** today.

## DORA mapping (Reg. (EU) 2022/2554)

| Article | Topic | Implementation |
|---|---|---|
| Art. 5/6 | ICT risk management framework | dependency centralised on openbank-libs; service in the governance catalog (ADR-0029). |
| Art. 9 | Protection & prevention | security headers, CORS allowlist; gateway-fronted. |
| Art. 9 | Identification | `BuildInfo` (gitCommit, buildTime, version) at `/api/v1/info`. |
| Art. 10 | Detection | metrics + health probes. |
| Art. 11 | Response & recovery | runbooks in [05 — Operations](./05-operations.md); PostgreSQL owns durable state and the seeder never overwrites a non-empty store. Restore proof remains a gap. |
| Art. 28 | Third-party risk | no third-party SaaS — self-hosted. |

## Security controls

- Input validation: `ProductRequest` required fields enforced; unknown product id → 404; duplicate code → 409.
- Output encoding: Jackson (automatic).
- Security headers: CSP `default-src 'self'`, HSTS, `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy`, `Permissions-Policy` — set in `application.yaml`.
- CORS: restricted allowlist (admin-ui origins only).
- TLS: mTLS in-cluster (Istio), TLS termination at gateway.
- AuthN/AuthZ: the service validates OIDC bearer tokens; reads require authentication and mutations require OPERATOR/ADMIN. `@Authorize` is present, but OPA is advisory until the deployment ships an enforcing policy sidecar/profile.
- Audit: no audit-event emission today (no outbox/Kafka). If product/pricing changes must be auditable for regulatory evidence, an audit trail is a follow-up.

## Known gaps / follow-ups (maturity)

- Immutable approved revisions and durable publication evidence.
- Enforced OPA profile for the bank deployment and provider-neutral scopes for standalone OIDC.
- Audit trail for product/pricing changes (regulatory evidence of who changed a price and when).
- Shared error envelope alignment (RFC-7807 problem+json).

These are framed as the service's maturity roadmap, not as exploitable specifics.
