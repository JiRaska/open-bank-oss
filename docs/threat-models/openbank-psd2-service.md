<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — openbank-psd2-service

- **Date:** 2026-06-15
- **Status:** Lightweight STRIDE/DFD (ADR-0030 D2). **Money-path-adjacent** TPP boundary.
- **Service ADR:** ADR-0090 (Berlin Group XS2A base + ČOBS profile); platform controls per ADR-0029/0030/0034.

## 1. Scope & purpose

The PSD2/XS2A facade exposed to **external Third Party Providers** (TPPs): AIS (account information,
P1) and PIS (payment initiation, P2). It is the bank's *only* internet-facing API for third parties,
so it is the primary external attack surface. It does **not** hold the ledger or move money itself —
PIS delegates value transfer to `transaction-service` (a gated money-path service) and consent checks
to `consent-service`. This service is therefore *money-path-adjacent*: it authorises and shapes
requests, but the irreversible action lives downstream.

## 2. Data flow (DFD)

```
[External TPP] --(eIDAS QWAC mTLS + X-TPP-ID)--> (REST /v1/consents, /v1/accounts, /v1/payments) --> [openbank-psd2-service]
                                                                                                          |
                                            EidasMtlsFilter (QWAC + AISP/PISP role via tpp-registry)      |
                                                                                                          +--> [consent-service]   (validateConsent)
                                                                                                          +--> [transaction-service] (initiatePayment — money path)
                                                                                                          +--> [account/balance] (AIS reads)
```

- **External entities:** TPPs (AISP/PISP), the eIDAS trust chain, downstream consent/transaction/account services.
- **Trust boundaries:** Internet↔service (eIDAS QWAC mTLS + TPP-registry authorisation); service↔internal services (cluster mTLS+OIDC+OPA).
- **Assets:** consents, account/transaction data, **payment instructions** (debtor/creditor/amount), TPP identity.

## 3. Authn/Authz

- **TPP authentication:** eIDAS **QWAC** (mTLS) terminated at ingress; `EidasMtlsFilter` requires
  `X-TPP-ID`/`SSL-CLIENT-S-DN` and calls `tpp-registry` to confirm the TPP is authorised for the role.
- **Role gate:** path `/payments` ⇒ **PISP**, everything else ⇒ **AISP** (deny-by-default; registry
  circuit-open ⇒ 503, never fail-open).
- **Consent gate:** AIS data calls require a valid `Consent-ID`; PIS requires a consent that authorises
  payment initiation (`consent-service.validateConsent`, fail-closed) before any downstream call.
- The sandbox (`/open-banking/sandbox/`) is intentionally open for conformance testing; it never
  reaches real money or customer data.

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing | Forged/lifted TPP identity | eIDAS QWAC mTLS + tpp-registry authorisation per request; role-scoped |
| **T**ampering | Alter amount/creditor in a payment in flight | TLS in transit; server-validated instruction; idempotency key (`X-Request-ID`) binds the request; downstream transaction-service is authoritative; **QSEAL `Digest`+`Signature` verification** (`QsealSignatureFilter` / `QsealVerifier`, P4) binds the body and signing string per message |
| **R**epudiation | TPP denies initiating a payment | AuditEvent + `X-Request-ID` correlation + SCA evidence (sca-service, ADR-0021); **QSEAL signature** over the request gives per-message non-repudiation (P4, advisory→enforce) |
| **I**nfo disclosure | Account/transaction harvesting across consents | Per-`Consent-ID` scoping; AISP role; reads are owner/consent-bounded; amounts rendered without added precision |
| **I**nfo disclosure | Error bodies / metrics leak PII | Berlin `tppMessages` carry codes not PII; `/q/metrics` cluster-internal, low-cardinality (ADR-0077/0079) — no IBAN/amount/payment-id labels |
| **D**oS | Initiation/AIS flooding from a TPP | Idempotency replay; consent `frequencyPerDay` cap; ingress rate limit; registry circuit breaker |
| **E**oP | AISP initiates a payment, or consent reused beyond scope | Distinct PISP role on `/payments`; consent validated for the specific action+debtor before initiation |

## 5. Residual risks / assumptions

- **`X-Request-ID` idempotency required** — replays must not double-initiate; enforced via `IdempotencyStore`.
- **SCA** (sca-service, ADR-0021) must gate customer authorisation of the payment (redirect/decoupled).
- **QSEAL is advisory by default** (`openbank.psd2.qseal.enforce=false`): sandboxes have no real
  QSEAL chain, so a missing/invalid signature is logged but allowed. Production flips `enforce=true`
  to reject unsigned/forged writes. The verifier is real JCA (digest + RSA/EC signature over the
  canonical signing string), unit-tested in `QsealVerifierTest`.
- **Payment-information `GET` + full QSEAL trust validation (cert-chain to an eIDAS root, revocation)
  remain residual** — current verification trusts the presented `TPP-Signature-Certificate` and checks
  the signature, not the chain/OCSP. Chain validation is future work.
- The bespoke `/open-banking/v2` surface is **deprecated** (RFC 8594 `Deprecation`/`Sunset` headers via
  `BespokeDeprecationFilter`) and kept until the sunset date — admin-ui health probes still target it;
  it shares the same `EidasMtlsFilter` gate. Hard removal is gated on the sunset (tracked in #1118).

## 6. Change log

- **2026-08-24** — Synthetic-journey taint now propagates over this service's existing internal REST clients through `SyntheticTaintClientFilter` (ADR-0252, #4348). This adds no caller, endpoint, network-policy edge, privilege or PSD2-control bypass. It preserves the marker before a downstream persistence/event boundary; a fleet gate requires every new client to choose propagation or a reasoned external boundary.

- **2026-08-07 (#3658, recorded retroactively 2026-08-14)** — The required Berlin Group headers on
  `AisResource` and `PisResource` changed from non-nullable `String` to `String?` plus an explicit
  guard, so a **missing** `Consent-ID` / `Idempotency-Key` answers **400** instead of **500**.
  Previously the non-nullable Kotlin signature made JAX-RS inject `null` and the request died in
  `GenericExceptionMapper`; the exact case those headers exist to gate was the case the API answered
  worst.

  **Security posture is unchanged or improved, and this was verified rather than assumed:**
  - Every REQUIRED header still rejects. `AisResource` guards inline
    (`if (consentId.isNullOrBlank()) throw Psd2RequestFormatException(CONSENT_ID_REQUIRED)`);
    all four `PisResource` initiation endpoints delegate to the shared `initiatePayment`, which
    guards both `Consent-ID` and `Idempotency-Key` before any other work. Checked per endpoint, not
    per file.
  - The rejection is a `Psd2RequestFormatException`, i.e. the Berlin Group error shape, not a bare
    `IllegalArgumentException` — correct for this surface.
  - The other newly-nullable parameters (`dateFrom`, `dateTo`, `bookingStatus`, `limit`,
    `afterCursor`) are genuinely optional query parameters and correctly carry no guard.
  - No consent check is weakened, no new data flow, no new store, no endpoint added or removed.
    The **I**nformation-disclosure and **S**poofing rows are untouched; what improves is the error
    contract on the request-format boundary.

  **Why this entry is late.** ADR-0030 D2's `threat-model-updated-on-trust-boundary-change` gate
  fired on the PR and named this file. The PR was merged past it — the ruleset recorded a
  `required_status_checks` bypass — so the gate's demand was never met and, unlike a red build,
  nothing would ever ask again: the gate evaluates a diff, and that diff is long merged. Surfaced by
  the `merged-past-red-check` watch (#4828) once it could read the bypass log for the first time
  (#4791).

  Rollback: none applicable — this records a change already on `main`.

- **2026-06-15 (ADR-0090 P4)** — Added **QSEAL** message-signature verification
  (`QsealSignatureFilter` after the QWAC gate; `QsealVerifier` pure-JCA: `Digest` body binding +
  `Signature` verification over the canonical signing string via the `TPP-Signature-Certificate`
  public key). **Advisory by default**; `openbank.psd2.qseal.enforce` flips to reject. Strengthens
  the **T**ampering + **R**epudiation rows. Marked the bespoke `/open-banking/v2` surface deprecated
  via RFC 8594 headers (`BespokeDeprecationFilter`). No new data store/flow; residual = no
  cert-chain/revocation validation yet. Rollback = revert.
- **2026-06-15 (ADR-0090 P2)** — Added the Berlin **PIS** surface (`POST /v1/payments/{product}`,
  `GET …/status`) for the pan-EU SEPA products. New external money-path-adjacent flow: PIS request →
  consent validation → transaction-service. Mitigations: PISP role gate, fail-closed consent check,
  `X-Request-ID` idempotency, audit correlation. No new persistent store in this service (delegates
  downstream). Risk class = **integrity/EoP**; residual = no per-message QSEAL yet (P4). Rollback =
  revert; the bespoke `/open-banking/v2/payments` path is unaffected.
- **2026-06-15 (ADR-0090 P1)** — Berlin **AIS + consent** surface (`/v1/consents`, `/v1/accounts`).
  Read-only + consent reuse, no money path. Extended `EidasMtlsFilter` to gate `v1/`. Risk class =
  **confidentiality**, mitigated by per-consent scoping.
