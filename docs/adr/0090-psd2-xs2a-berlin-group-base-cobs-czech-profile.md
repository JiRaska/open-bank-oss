# ADR-0090 — PSD2 XS2A access: Berlin Group NextGenPSD2 base + ČOBS Czech profile

**Status:** Accepted (2026-06-15 — all four phases implemented and released as
psd2-service 0.5.0: P1 Berlin XS2A consent+AIS (#1117), P2 PIS (#1120), P3 ČOBS
Czech products (#1121), P4 eIDAS QSEAL signing + bespoke-API deprecation (#1123).
Sandbox deploy runs with QSEAL enforcement OFF — no TPP eIDAS certs in the sandbox.)
**Date:** 2026-06-15
**Relates to:** ADR-0021 (decoupled SCA / device approval), ADR-0030 (threat-model discipline for money-path), ADR-0034 (unified OPA authz), ADR-0048 (two version axes — API contract vs release), ADR-0039 (ledger golden source), ADR-0069 (party / `party_id`), consent-service (ČOBS-aware `ConsentScope`)

## Context

`openbank-psd2-service` today exposes a **bespoke** `/open-banking/v2` TPP interface (custom
`ObModels.kt`, AIS `accounts|balances|transactions`, consents, PIS `sepa-credit-transfers |
instant-sepa-credit-transfers | domestic-cz | sipo`, eIDAS QWAC + `X-TPP-ID` TPP auth,
fail-closed consent-gating via consent-service, stub downstreams for the RTS Art. 30 sandbox).
It is ČOBS-*flavoured* (Czech products, symbols, standing-orders/direct-debits scopes) but it
**conforms to no published standard** — its paths, headers and payloads are ours.

That blocks two goals at once:
- **Pan-European usability.** PSD2 RTS Art. 30 obliges a dedicated interface; TPPs across the EU
  integrate against published standards, overwhelmingly the **Berlin Group NextGenPSD2 XS2A
  Framework** (>75% of EU banks). A bespoke API means every TPP writes a one-off integration.
- **Czech market fit.** Czech TPPs and aggregators target **ČOBS** (Czech Standard for Open
  Banking, published by ČBA) — Czech payment products, the variabilní/specifický/konstantní
  symbols, SIPO, ČOBS callback/error semantics.

There is **no PSD2 ADR** today — this surface, a regulated money-adjacent boundary, has been
evolving without a recorded decision. This ADR fixes that and sets the target shape.

## Decision

Make the public TPP interface a **Berlin Group NextGenPSD2 XS2A base with a layered ČOBS Czech
profile on top** — base for EU reach, profile for Czech clients. The bespoke `/open-banking/v2`
API is deprecated, not extended.

- **D1 — Base = Berlin Group NextGenPSD2 XS2A 1.3.12** (latest finalised IG, `2022-07-01`).
  New `/v1/...` surface conforming to the Berlin spec: `/v1/consents`, `/v1/accounts`,
  `/v1/accounts/{account-id}/balances|transactions`, `/v1/payments/{payment-product}`,
  `/v1/periodic-payments/{payment-product}`, `/v1/funds-confirmations`; the Berlin header set
  (`X-Request-ID`, `PSU-ID`, `PSU-IP-Address`, `TPP-Redirect-URI`, `TPP-Redirect-Preferred`,
  `Consent-ID`, `Digest`, `Signature`, `TPP-Signature-Certificate`); Berlin payloads, `_links`,
  `scaStatus`, and ISO-20022 `transactionStatus` (`RCVD/ACTC/ACCP/ACSC/RJCT`).

- **D2 — Overlay = ČOBS v7.0** (the latest published by ČBA; v8 in preparation) as a **profile,
  not a fork.** Czech specifics ride on the Berlin model: a `czech-domestic-credit-transfers`
  payment-product and `sipo`; the symbols (VS/SS/KS) carried in Berlin's
  `remittanceInformationStructured`; ČOBS access extensions (standing orders, direct debits)
  mapped to the existing consent-service scopes; ČOBS callback/error-state semantics. Selected
  by payment-product (and a profile content-type where ČOBS diverges).

- **D3 — SCA = redirect + decoupled.** Decoupled reuses the customer app's device-approval flow
  (ADR-0021) — the PSU approves in-app, no TPP-hosted credential entry. Embedded SCA is
  **deferred** (highest fraud surface, lowest TPP demand here).

- **D4 — Consent model = Berlin.** The `access` object (accounts/balances/transactions lists or
  `allPsd2`), `recurringIndicator`, `validUntil` (capped 90 days, RTS Art. 10), `frequencyPerDay`,
  `combinedServiceIndicator`. Backed by consent-service, whose `ConsentScope` is already ČOBS-aware.

- **D5 — TPP identity = eIDAS QWAC (mTLS) + QSEAL message signing.** Add Berlin's
  `Digest`/`Signature`/`TPP-Signature-Certificate` verification (QSEAL) on top of today's QWAC +
  `X-TPP-ID`; AISP/PISP/PIISP role check stays in tpp-registry. Fail-closed.

- **D6 — Additive migration.** Ship Berlin `/v1` alongside; mark `/open-banking/v2` **Deprecated**
  and remove it once no sandbox client depends on it. It is sandbox-only (stub downstreams), so
  there are no production TPPs to break. The RTS Art. 30 sandbox stays (stub clients behind `/v1`).

**Scope of this ADR:** AIS (consents + accounts/balances/transactions), PIS
(single + periodic payments), CBPII (funds-confirmations). **Deferred:** bulk payments,
signing baskets, embedded SCA, the openFinance successor framework.

## Architecture (base + overlay)

```
                 TPP (eIDAS QWAC mTLS + QSEAL signed)
                          │  Berlin NextGenPSD2 1.3.12  /v1/...
                          ▼
   ┌──────────────────────────────────────────────────────────────────┐
   │ openbank-psd2-service (XS2A facade)                                │
   │  • Berlin core: consents, accounts, balances, transactions,        │
   │    payments/{payment-product}, periodic-payments, funds-confirm    │
   │  • payment-product registry:                                       │
   │      sepa-credit-transfers, instant-sepa-credit-transfers,         │
   │      cross-border-credit-transfers           ← Berlin base         │
   │      czech-domestic-credit-transfers, sipo   ← ČOBS profile        │
   │  • ČOBS overlay: VS/SS/KS → remittanceInformationStructured;       │
   │    standing-orders / direct-debits access; ČOBS error/callback     │
   │  • SCA redirect + decoupled (ADR-0021); consent fail-closed        │
   └───────┬───────────────────────────────────────────────────────────┘
           ▼ (in-cluster, mTLS)
   consent-service · sepa/domestic/instant payment services · ledger (golden source) · sca-service · tpp-registry
```

The facade stays a thin, stateless translator (it stores no PII): Berlin/ČOBS request → existing
domain services → Berlin/ČOBS response. Czech behaviour is a profile branch on the same core, not
a parallel stack.

## Alternatives considered

- **Keep the bespoke `/open-banking/v2`.** Rejected: no TPP integrates against a one-off API; fails
  the spirit of RTS Art. 30 (a *usable* dedicated interface).
- **ČOBS-only (no Berlin).** Rejected: ČOBS is Czech-only; the bank's ambition is EU-wide, and
  Berlin is the de-facto EU base. ČOBS as a profile gets both.
- **Berlin-only (no ČOBS).** Rejected: Czech TPPs expect the local products/symbols/SIPO; Berlin
  alone misses the home market.
- **openFinance (Berlin's successor).** Deferred: not yet the integration target for live TPPs;
  revisit when adoption warrants. NextGenPSD2 1.3.x remains the mature PSD2 standard.

## Consequences

**Positive**
- One published, conformant interface any EU TPP can integrate against + first-class Czech support.
- Recorded PSD2 decision (closes the missing-ADR gap); a base for a threat model and conformance.
- Reuses what exists: consent-service (ČOBS scopes), ADR-0021 decoupled SCA, ledger, tpp-registry.

**Negative / trade-offs**
- Real work: the bespoke models/paths/headers are replaced by the full Berlin contract + QSEAL
  signing + SCA `_links` state machine. Multi-phase, money-adjacent → careful.
- Two payment-product families (Berlin + ČOBS) to keep aligned as ČOBS moves to v8.
- QSEAL signature verification adds a crypto path on the request hot-path.

**Neutral**
- API-contract version axis (ADR-0048): the XS2A contract version tracks Berlin (`1.3.12`),
  independent of the service release version and of `/api/v{N}`.

## Compliance impact

- **PSD2 RTS Art. 30** (dedicated interface) — satisfied by a standard, documented, sandboxed XS2A.
- **RTS Art. 10/36** — consent 90-day cap + frequency carried (already in consent-service).
- **RTS SCA (Art. 4)** — redirect + decoupled; embedded deferred.
- **ADR-0030** — psd2-service is a customer-/money-adjacent public boundary ⇒ a **threat model is
  required** before the Berlin `/v1` goes past sandbox (`docs/threat-models/openbank-psd2-service.md`).
- **eIDAS** — QWAC (transport) + QSEAL (message) per Berlin.

## Implementation phases (each a build-verified PR; threat model gates go-live)

1. **P1 — Consent + AIS to Berlin.** `/v1/consents` (create/get/status/delete, `access` model) +
   `/v1/accounts`, `/v1/accounts/{id}/balances|transactions` in Berlin shape; consent fail-closed.
2. **P2 — PIS to Berlin.** `/v1/payments/{payment-product}` (sepa-credit-transfers first) +
   payment status (`transactionStatus`) + SCA redirect/decoupled `_links`.
3. **P3 — ČOBS profile overlay.** `czech-domestic-credit-transfers` + `sipo` payment-products,
   VS/SS/KS in `remittanceInformationStructured`, periodic-payments (standing orders),
   direct-debits access, ČOBS error/callback semantics.
4. **P4 — QSEAL signing + conformance.** `Digest`/`Signature`/`TPP-Signature-Certificate`
   verification; threat model; Berlin Group test-case suite + ČOBS sandbox readiness;
   deprecate-then-remove `/open-banking/v2`.

## Open questions (confirm before P1)

1. **ČOBS v7.0 now, or target v8** when ČBA publishes it? (Proposed: build to v7.0, design the
   overlay so v8 deltas are additive.)
2. **Embedded SCA** — leave deferred? (Proposed: yes.)
3. **Signing baskets & bulk payments** — in or out of the first conformance pass? (Proposed: out.)
4. **Retire `/open-banking/v2`** immediately on P1, or keep until P4? (Proposed: deprecate at P1,
   remove at P4.)

## References

- Berlin Group NextGenPSD2 Downloads — <https://www.berlin-group.org/nextgenpsd2-downloads> (XS2A IG 1.3.12)
- Czech Standard for Open Banking (ČOBS) v7.0, ČBA — <https://www.cbaonline.cz>
- PSD2 (Dir. (EU) 2015/2366) + RTS on SCA & CSC (Reg. (EU) 2018/389)
- Current surface: `openbank-psd2-service/src/main/resources/openapi.yaml`, `docs/06-compliance.*`
