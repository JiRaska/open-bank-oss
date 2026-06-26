# Compliance

> **Money-path status:** `openbank-psd2-service` is **not** listed in `rules.yaml: money_path_services`. The money path runs through `consent-service`, `transaction-service` and the payment executors (`sepa-payment`, `sepa-instant`, `domestic-payment`), `clearing-service` and `ledger-service` — which this facade *delegates to*. PSD2 is the regulated **access channel** in front of them; its threat surface is significant (it is internet-facing and initiates payments), so it is treated with money-path-adjacent rigor even though it carries no `money-path` label and persists no value.

## Regulatory framework

| Regulation | Relation to this service | Implementation |
|---|---|---|
| **PSD2** (Dir. (EU) 2015/2366) + RTS on SCA/CSC | This **is** the PSD2 surface — AIS, PIS, consent lifecycle for TPPs | `/open-banking/v2` AIS/PIS/consents; eIDAS QWAC / `X-TPP-ID` TPP auth; AISP/PISP role check via tpp-registry; consent-gated reads/initiations |
| **ČOBS** (Czech Open Banking Standard) | Local Open Banking flavour | `DOMESTIC_CZ` + `SIPO` products; variable/specific/constant symbols; standing-orders / direct-debits consent extensions; TPP webhook events |
| **eIDAS** (Reg. (EU) 910/2014) | TPP identification via QWAC certificates | `EidasMtlsFilter` reads `SSL-CLIENT-S-DN` (gateway-terminated mTLS) |
| **GDPR** | IBANs and PSU data transit the channel | PII masked in logs (`****<last4>` for IBANs); no PII stored at rest; rights served by owning services |
| **AMLD** | Payment initiation feeds AML/sanctions screening downstream | screening runs in the executor/clearing path, not here; events emitted for audit |
| **DORA** (Reg. (EU) 2022/2554) | Operational resilience of a critical access channel | circuit breakers / retries / fallbacks, health probes, metrics, audit events, SLO, runbooks |
| **NIS2** | Network & info security | mTLS in-cluster, strict security response headers, restricted CORS, audit log |

## PSD2 specifics

### TPP authorization (Art. 30, RTS Art. 34)

TPP identity comes from an eIDAS QWAC certificate (subject DN via `SSL-CLIENT-S-DN`) or `X-TPP-ID`; the role (`AISP` for AIS/consents, `PISP` for payments) is verified against `tpp-registry-service` before any business logic runs. No identity ⇒ `401 CERTIFICATE_MISSING`; not authorized ⇒ `401 CERTIFICATE_INVALID`; registry down ⇒ `503` (fail closed).

### Consent (Art. 64–67)

Every AIS read and PIS initiation calls `consent-service.validateConsent(consentId, tppId, scope, iban)`. The **fallback denies access** (`false`) when consent-service is unavailable — the channel fails closed. Consent `validUntil` is capped to 90 days at creation; `frequencyPerDay` and `recurringIndicator` are carried through. ČOBS access extensions map to `STANDING_ORDERS_READ` / `DIRECT_DEBITS_READ` scopes.

### SCA (RTS Art. 4, ADR-0021)

Strong Customer Authentication is **not performed here**. The facade surfaces SCA links (`scaRedirect`, `startAuthorisation`) and `scaStatus`; the decoupled device-approval SCA flow (no auto-approve) lives in `sca-service` — see [ADR 0021](../../../../docs/adr/0021-sca-decoupled-device-approval-no-auto-approve.md).

### Payment initiation (PIS, Art. 66)

Supported products: SEPA credit transfer, instant SEPA, Czech domestic, SIPO. Initiation is forwarded to `transaction-service`; `initiatePayment` has **no resilience fallback**, so a downstream failure surfaces as an error rather than a false success. Idempotency (`Idempotency-Key`) makes retries replay-safe.

## GDPR mapping

### Controller / processor

For the PSD2 access channel OpenBank is the **controller** of the underlying data; this service acts as a **pass-through** — it does not persist account holders' personal data. Personal data only transits in flight while serving an AIS read or PIS initiation, under an explicit PSU consent.

### Lawful basis (Art. 6)

- **Consent / contract** (Art. 6(1)(a)/(b)) — PSD2 access is exercised under the PSU's explicit consent and the underlying account contract.
- **Legal obligation** (Art. 6(1)(c)) — PSD2 itself mandates the access channel for licensed TPPs.

### Data subject rights

| Right | Where served |
|---|---|
| Access (Art. 15) | owning services (`account-service`, `consent-service`, `party-service`) — this facade stores no PII |
| Rectification (Art. 16) | owning services |
| Erasure (Art. 17) | owning services; AMLD overrides where applicable |
| Restriction (Art. 18) | consent revocation (`DELETE /open-banking/v2/consents/{id}`) stops TPP access |
| Portability (Art. 20) | N/A here (no stored data) |

### Data flows

- → **tpp-registry-service** (REST): `tppId`, role — TPP authorization.
- → **consent-service** (REST): `consentId`, `tppId`, scope, IBAN — consent validate / lifecycle.
- → **account-service** (REST): `partyId`, `accountId` — AIS reads (accounts/balances/transactions returned in flight).
- → **transaction-service** (REST): debtor/creditor IBAN, amount, remittance — PIS initiation.
- → **Kafka** `openbank.psd2.events` (outbox): asynchronous notifications (consent revoked, payment status changed, transaction report) for audit and TPP-webhook delivery.

No data leaves the EU/EEA region. The external boundary is the TPP, reached only after eIDAS authentication and consent validation.

## DORA mapping (Reg. (EU) 2022/2554)

| Article | Topic | Implementation |
|---|---|---|
| Art. 5/6 | ICT risk management | centralized dependency on `openbank-libs`; in the operations register |
| Art. 9 | Protection & prevention | strict security headers, restricted CORS, mTLS in-cluster, fail-closed consent |
| Art. 10 | Detection | Prometheus metrics, OpenTelemetry traces, alerting on error rate / latency / circuit state |
| Art. 11 | Response & recovery | circuit breakers + retries + fallbacks isolate downstream faults; runbooks in [05 — Operations](./05-operations.md) |
| Art. 16/17 | Incident management & reporting | outbox events to the audit pipeline for evidence |
| Art. 28 | Third-party risk | TPPs vetted via tpp-registry; no third-party SaaS in the request path (all self-hosted) |

## Security controls

- ✅ TPP authN: eIDAS QWAC (`SSL-CLIENT-S-DN`) or `X-TPP-ID`, role-checked via tpp-registry (`EidasMtlsFilter`).
- ✅ AuthZ per resource: consent validation on every AIS read / PIS initiation; **fail closed** on consent-service outage.
- ✅ Idempotency: required on PIS and consent creation, Redis-backed, replay-safe.
- ✅ Resilience: MicroProfile Fault Tolerance (timeout / retry / circuit breaker / bulkhead / fallback) on every outbound call.
- ✅ Security response headers: CSP, HSTS, `X-Frame-Options: DENY`, `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy`.
- ✅ PII minimisation: IBANs masked in logs; no PII at rest.
- ✅ Audit: notifications emitted via the outbox → Kafka for the audit pipeline.
- ✅ TLS: mTLS in-cluster; QWAC terminated at the gateway.
- ⚠️ **Stub downstream clients:** the current account/consent/transaction clients are stubs (`StubClients.kt`); real REST clients are a pending follow-up before production TPP traffic.
- ⚠️ **OpenAPI ↔ code drift:** header names and the server port differ between `openapi.yaml` and the resources (see [03 — API](./03-api.md)); reconcile before publishing the contract to TPPs.
