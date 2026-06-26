# BIAN Service Domain Mapping

> Last updated: 2026-05-26
> Status: **Draft v0.1** — based on BIAN Service Landscape v13 (2023) and v12 conventions. Subject to revision once BIAN v14 hierarchy is published.
> This mapping is **descriptive**, not certified by BIAN. We are not (yet) a BIAN member.

## What is BIAN

The Banking Industry Architecture Network (BIAN) maintains a vendor-neutral, hierarchical decomposition of banking capabilities into **Business Areas → Business Domains → Service Domains**. Service Domains are the atomic units — each is a discrete, reusable banking capability with a defined functional pattern (Initiate / Update / Execute / Request / Retrieve / Notify / Control).

For OpenBank, BIAN alignment matters because:
1. It anchors our service boundaries to an industry-standard ontology rather than ad-hoc taste.
2. It makes integration with BIAN-aligned third parties (TPPs, core banking add-ons) drastically simpler.
3. It signals to enterprise buyers and regulators that we speak the banking industry's language.
4. It surfaces capability **gaps** we would otherwise rationalise away.

## Mapping: OpenBank services → BIAN Service Domains

| OpenBank service | Primary BIAN Service Domain | Business Area | BIAN functional pattern | Notes |
|---|---|---|---|---|
| `openbank-account-service` | Current Account | Customer Products | Initiate / Update / Execute | Also touches Customer Position |
| `openbank-ledger-service` | Position Keeping | Operations | Execute / Notify | Double-entry general ledger; underpins all product Service Domains |
| `openbank-transaction-service` | Transaction Engine | Operations | Execute / Notify | Posting, idempotency, transaction state machine |
| `openbank-balance-service` | Customer Position | Operations | Retrieve / Notify | Real-time projection over ledger |
| `openbank-party-service` | Party Reference Data Directory | Reference Data | Initiate / Update / Retrieve | Customer master data |
| `openbank-kyc-service` | Customer Offer Process (KYC sub-domain) + Party Lifecycle Management | Customer Management | Initiate / Update / Execute | Onboarding flow |
| `openbank-aml-service` | Fraud Resolution + Customer Behavior Insights | Operations / Risk & Compliance | Execute / Notify | Real-time transaction monitoring |
| `openbank-sanctions-service` | Regulatory Compliance | Risk & Compliance | Execute / Notify | Watchlist screening |
| `openbank-consent-service` | Customer Agreement | Customer Management | Initiate / Update / Retrieve | PSD2 consent objects |
| `openbank-psd2-service` | Open Banking Services | Channel | Initiate / Execute / Retrieve | AISP + PISP API surface |
| `openbank-tpp-registry-service` | Servicing Position | Channel | Retrieve | TPP eIDAS QWAC/QSeal verification |
| `openbank-sca-service` | Party Authentication | Cross-Product Operations | Execute | SCA enrolment, challenge, verification |
| `openbank-domestic-payment` | Payment Execution + Payment Order | Payments | Initiate / Execute / Notify | National payment scheme |
| `openbank-sepa-payment` | SEPA Credit Transfer (under Payment Execution) | Payments | Initiate / Execute / Notify | SCT pacs.008 / pacs.002 |
| `openbank-sepa-instant` | SEPA Instant Credit Transfer | Payments | Initiate / Execute / Notify | SCT-Inst, 10-second SLA |
| `openbank-swift-service` | Correspondent Bank | Wholesale & Treasury | Execute / Notify | MT/MX messaging |
| `openbank-fx-service` | Currency Trading | Treasury | Execute | Foreign exchange |
| `openbank-clearing-service` | Clearing Position + Settlement | Operations | Execute / Notify | T2 / TIPS / national ACH |
| `openbank-standing-order-service` | Customer Recurring Payments | Customer Products | Initiate / Update / Execute | Schedule-driven payments |
| `openbank-card-issuance-service` | Card Authorization Type + Card Network | Customer Products | Initiate / Execute | Card lifecycle |
| `openbank-dispute-service` | Dispute Resolution | Customer Management | Initiate / Update / Execute | Chargebacks |
| `openbank-interest-service` | Interest Calculation | Operations | Execute / Notify | Accrual + posting |
| `openbank-notification-service` | Customer Communication | Customer Management | Execute / Notify | Multi-channel notifications |
| `openbank-audit-service` | Internal Audit | Compliance | Execute / Notify | Centralised audit trail |
| `openbank-pid-service` | Payment Instrument | Payments | Update / Retrieve | Payment instrument directory |
| `openbank-agent-service` | (no direct BIAN equivalent) | — | — | AI agent integration (MCP); experimental, outside BIAN |

## Gaps — missing BIAN Service Domains for a full retail bank

A retail bank operating under EU regulation typically has roughly 60-80 Service Domains in production. We currently cover 25-26. The most material missing domains, in priority order:

| # | Missing Service Domain | Business Area | Why it matters |
|---|---|---|---|
| 1 | Customer Credit Rating | Risk & Compliance | Required for any lending product, scoring before account opening |
| 2 | Loan Origination | Customer Products | Personal loans, mortgages, overdrafts — primary income for retail banks |
| 3 | Loan Servicing | Operations | Repayment scheduling, prepayment, restructuring |
| 4 | Collections Recovery | Risk & Compliance | Delinquent loan management |
| 5 | Regulatory Reporting | Compliance | FINREP, COREP, AnaCredit, MiFID II reporting |
| 6 | ICT Risk Management | Compliance | DORA Art. 6 requires this as first-class capability |
| 7 | Treasury Management | Treasury | Cash management, liquidity planning |
| 8 | Asset and Liability Management (ALM) | Treasury | Interest rate risk, liquidity coverage ratio |
| 9 | Customer Tax Handling | Compliance | FATCA / CRS reporting, withholding tax |
| 10 | Product Inventory | Customer Products | Product catalogue (currently a stub in `attic/`) |
| 11 | Customer Case | Customer Management | Customer service tickets, complaint handling |
| 12 | Branch Network Management | Channel | If physical branches; can be skipped for pure digital |
| 13 | Marketing Plan Activity | Marketing | Campaign management |
| 14 | Investment Portfolio | Customer Products | If offering investment products |
| 15 | Bank Guarantee | Customer Products | Letters of credit, guarantees |
| 16 | Wholesale Lending | Customer Products | Corporate lending |
| 17 | Mortgage Loan | Customer Products | Mortgage-specific lifecycle |
| 18 | Customer Tax Handling | Compliance | FATCA/CRS |
| 19 | Document Management | Operations | Statement generation, archive |
| 20 | Customer Workbench | Channel | Operator UI workflows (partially covered by `openbank-admin-ui`) |

**Reading the gap honestly:** OpenBank today covers ~33% of typical retail bank Service Domains. The missing 67% includes the most economically important ones (lending). This is fine for an MVP and reference platform; it is a blocker for a full-service licenced retail bank deployment.

## BIAN Service Operation pattern alignment

BIAN defines a small set of standard operations that every Service Domain exposes:

| Pattern | Verb | OpenBank REST mapping |
|---|---|---|
| Initiate | POST | `POST /<resource>` returns the new instance |
| Update | PATCH / PUT | `PATCH /<resource>/{id}` for partial; `PUT` for full replace |
| Execute | POST | `POST /<resource>/{id}/execute` or `POST /<resource>/{id}/<action>` |
| Request | POST | `POST /<resource>/{id}/request-<thing>` (asynchronous trigger) |
| Retrieve | GET | `GET /<resource>` or `GET /<resource>/{id}` |
| Notify | (event) | Kafka topic `<domain>-events-out` per Service Domain |
| Control | POST | `POST /<resource>/{id}/{suspend|resume|terminate}` |

Each REST resource exposed by an OpenBank service SHOULD follow this convention. Deviations should be documented in the service README and justified.

## Recommended next steps for BIAN alignment

1. **Validate this mapping** with a BIAN-savvy reviewer (paid consultation, ~1 day).
2. **Become BIAN member** once production-grade (membership is free for individuals, fee-based for orgs).
3. **Adopt BIAN Semantic API** (Coreless / Sandbox endpoints) for top 10 inter-service calls.
4. **Cover top 5 missing Service Domains** in the M8-M10 roadmap window (Loan Origination, Customer Credit Rating, Regulatory Reporting, ICT Risk Management, Customer Case).
5. **Publish a BIAN Conformance Statement** in `docs/strategy/bian-conformance.md` once mapping is reviewed.

## Sources

- BIAN Service Landscape v13 (2023) — https://bian.org/servicelandscape-13-0-0/
- BIAN Semantic APIs v13 — https://github.com/bian-official/public
- BIAN How-to Guide for Service Domain identification

## Disclaimer

This mapping is OpenBank's interpretation of BIAN structure. It is **not BIAN-certified**. For official conformance, engage BIAN directly.
