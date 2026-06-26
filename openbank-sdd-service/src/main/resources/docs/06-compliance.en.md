# Compliance

`openbank-sdd-service` is **not** a money-path service (`rules.yaml: money_path_services` does not list it) — v1 never executes an irreversible posting. It is nonetheless a payments-regulated service: it is the system of record for direct-debit mandates and the fail-closed gate that protects a debtor from unauthorised collections. The money-path classification will change when the actual debit/refund posting is added (a fast-follow that will need a threat model per ADR-0030).

## Regulatory framework

| Regulation | Relation to this service | Implementation |
|---|---|---|
| **PSD2** (Reg. (EU) 2015/2366) | Authorisation of payment transactions, debtor blocking/limiting rights (Art. 64, 79), unauthorised-transaction remedies (Art. 73), direct-debit refund right (Art. 76/77) | fail-closed `CollectionAuthorisationPolicy`; `DebtorControls` (block-all / block-list / amount cap); `RefundPolicy` (8-week unconditional, 13-month unauthorised) |
| **EPC SEPA Direct Debit Rulebooks** (EPC016 Core, EPC222 B2B) | mandate lifecycle, scheme rules, sequence types, pre-notification duty, EPC reason codes | `MandateLifecycle` state machine; CORE vs B2B birth status & verification; EPC reason codes (`MD01`, `FF05`, `MS02`, `MD06`); pre-notification tracked |
| **CZ Act 370/2017 Sb. (Zákon o platebním styku) §177** | unauthorised-transaction window in CZ transposition | 13-month unauthorised refund window in `RefundPolicy` |
| **GDPR** (Reg. (EU) 2016/679) | debtor IBAN/name are PII | record-keeping basis overrides erasure; PII confined to mandate row + `collection.authorised` event |
| **AMLD / AML record-keeping** | direct-debit authorisations are payment records | 7-year retention (`governance.yaml`) |
| **DORA** (Reg. (EU) 2022/2554) | operational resilience | health probes, resilient outbox dispatch (circuit breaker / retry / DEAD parking), audit events, SLO, runbooks |
| **NIS2** | network & information security | security headers, in-cluster TLS, OIDC auth, RBAC |

## GDPR mapping

### Lawful basis (Art. 6)

- **Contract** (Art. 6(1)(b)) — primary: holding a mandate is necessary to perform direct-debit collection for the customer.
- **Legal obligation** (Art. 6(1)(c)) — secondary: payment record-keeping (AMLD, payment-services law) requires retention of the authorisation.

### Personal data held

| Field | Where | Classification |
|---|---|---|
| `debtor_iban` | `sdd_mandate`, `collection.authorised` event | PII (financial account) |
| `debtor_name` | `sdd_mandate` | PII |
| `account_id` | `sdd_mandate`, every event | pseudonymous link to the customer |
| amended IBAN/name | `sdd_mandate.amendments` (JSON) | PII |

### Data subject rights

| Right | Application |
|---|---|
| Access (Art. 15) | `GET /api/v1/sdd/mandates?accountId=...` returns the subject's mandates |
| Rectification (Art. 16) | `PATCH /api/v1/sdd/mandates/{id}` records an auditable AMDT amendment |
| Erasure (Art. 17) | **Restricted** — payment/AML record-keeping (7-year retention) overrides erasure of a settled mandate |
| Restriction (Art. 18) | `suspend` parks a mandate (SUSPENDED); `cancel` terminates it |
| Portability (Art. 20) | mandate data is structured JSON via the read API |
| Object (Art. 21) | the debtor's right to block/refuse is implemented as `DebtorControls` and `cancel` |

### Data flows out

- → **ledger / payment posting** (Kafka `openbank.sdd.event`, `sdd.collection.authorised.v1`): `accountId`, `debtorIban`, `amount`, `currency`, `dueDate` — same controller, intra-OpenBank, for the downstream debit.
- → **audit-service** (Kafka): full event payload — same controller.
- → **notification** (Kafka): mandate lifecycle events.

No data leaves the EU/EEA region.

## PSD2 — debtor protection

The debtor's PSD2 rights are first-class in the domain:

```
collection instruction ─► CollectionAuthorisationPolicy (fail-closed, in order)
   1. mandate present & ACTIVE      ─ no  ─► REJECT MD01
   2. scheme match                  ─ no  ─► REJECT MD01
   3. EUR-only                      ─ no  ─► REJECT FF05
   4. B2B verified                  ─ no  ─► REJECT MD01
   5. one-off not already used      ─ no  ─► REJECT MD01
   6. debtor controls (Art. 79):
        block-all / block-list / amount cap  ─► REFUSE MS02
   else                                       ─► ACCEPT (emit, delegate posting)
```

- **REJECT** = a bank-side technical rejection (mandate fault).
- **REFUSE** = the debtor exercised a PSD2 Art. 79 control — block all direct debits, block a specific creditor, or cap the per-collection amount.

## Refund windows (PSD2 Art. 73/76/77, CZ §177)

| Case | Window | Outcome |
|---|---|---|
| Authorised **Core** collection | ≤ 8 weeks (56 days) from debit | `UNCONDITIONAL` refund (`MD06`) |
| Authorised **Core** beyond 8 weeks | — | ineligible |
| Authorised **B2B** collection | — | no post-settlement refund right |
| **Unauthorised** collection (no/invalid mandate) | ≤ 13 months from debit | `UNAUTHORISED` refund (`MD06`) |

The arithmetic is a pure, unit-tested domain function (`RefundPolicy`); the `refund-assessment` endpoint exposes it.

## Pre-notification (EPC duty)

The creditor's duty to pre-notify the debtor at least 14 days before the due date is **tracked, not enforced** (`last_pre_notification_date`, `MandateLifecycle.recordPreNotification`). A missing pre-notification is a documented refusal ground rather than an automatic hard block in v1.

## DORA mapping (Reg. (EU) 2022/2554)

| Article | Topic | Implementation |
|---|---|---|
| Art. 9 | Identification | `BuildInfo` (gitCommit, buildTime, version) in `/api/v1/info` |
| Art. 10 | Detection | Prometheus metrics + alerting on error rate, latency, outbox lag |
| Art. 11 | Response & recovery | resilient outbox (circuit breaker / retry / `DEAD` parking); runbooks in `05-operations.md` |
| Art. 16 | Incident management | lifecycle + `collection.authorised` events emitted to audit-service |
| Art. 28 | Third-party risk | no third-party SaaS — all self-hosted |

## Audit trail

Every mutation and every accepted collection produces a domain event written to the transactional outbox and published to `openbank.sdd.event`; `audit-service` persists it. The event carries `event_id` (idempotency id), `aggregate_id` (mandate) and the typed payload.

## Security controls

- ✅ Input validation (Bean Validation on DTOs; enum-constrained scheme/sequence/field/decision values)
- ✅ AuthN: Keycloak OIDC, RS256 JWT
- ✅ AuthZ: Quarkus `@RolesAllowed` — mutations gated to operator/admin/payments/service; reads add viewer
- ✅ Fail-closed authorisation — the default decision is reject/refuse, never silent accept
- ✅ Idempotency: registration is idempotent on the rulebook `(CID, UMR)` key; outbox `event_id` deduplicates downstream
- ✅ Security headers: CSP `default-src 'self'`, HSTS, X-Frame-Options DENY, nosniff, Referrer-Policy, Permissions-Policy
- ✅ Secrets: dev placeholders only; prod via Vault (ADR-0017)
- ✅ Resilient eventing: circuit breaker + bounded retry + terminal `DEAD` parking for poison rows
- ⚠️ Money-path posting (debit/refund execution): **not implemented in v1** — delegated; the fast-follow that adds it will be money-path and require a threat model (ADR-0030)
- ⚠️ IBAN tokenisation: not implemented; IBAN is stored in clear in the mandate row and the `collection.authorised` event (tracked as a regulatory-audit risk, consistent with the rest of the fleet)
