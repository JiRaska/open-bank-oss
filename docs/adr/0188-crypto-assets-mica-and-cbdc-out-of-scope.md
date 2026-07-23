---
date: 2026-07-23
decision-status: accepted
delivery-status: n-a
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [architecture]
summary: "Crypto-assets (MiCA tokens, stablecoins, custody) and a digital-euro CBDC are out of scope; the platform is a fiat retail core, and a future integration enters as a new bounded context behind a rail-style port, not by extending the ledger."
---

# ADR-0188 — Crypto-assets (MiCA) and CBDC out of scope

## Context

Two adjacent domains are sometimes expected of a modern banking platform and are deliberately absent
here:

- **Crypto-assets under MiCA** (Regulation (EU) 2023/1114) — issuance, custody or exchange of
  crypto-assets, including e-money tokens and asset-referenced tokens (stablecoins), with the
  attendant custody, safeguarding and CASP-authorisation obligations.
- **CBDC / the digital euro** — holding, transacting or distributing a central-bank digital currency
  as an intermediary, under the ECB's digital-euro scheme (still in preparation, with its own draft
  Regulation and rulebook).

The platform is a **fiat retail core**: multi-currency fiat accounts on a single IBAN with per-
currency pockets (ADR-0024), a double-entry ledger as golden source (ADR-0039), and SEPA fiat rails
(ADR-0104, ADR-0184). It holds no crypto-assets, integrates no wallet or on-chain settlement, and is
not a digital-euro intermediary. As with treasury (ADR-0185), the absence is intentional but was
never recorded, so it reads as a gap rather than a decision. This ADR records the boundary and,
importantly, the *shape* a future integration would take so it is not treated as impossible.

## Decision

We record that **crypto-assets and CBDC are out of scope**, deliberately, with a defined future
integration boundary:

1. **The ledger stays fiat.** `openbank-ledger-service` books fiat positions (ADR-0039/0024/0025);
   it will not be extended to custody crypto-assets or to hold native digital-euro balances. Crypto
   custody carries safeguarding, key-management and settlement-finality semantics fundamentally
   different from fiat double-entry, and forcing them into the fiat ledger would corrupt its
   invariants.

2. **No CASP/custody function is provided.** The platform performs no MiCA-regulated activity
   (issuance, custody, exchange) and makes no claim to a CASP authorisation. Stablecoin/token
   handling is external.

3. **A future integration enters as a new bounded context behind a port.** Should crypto or CBDC
   ever be added, the design boundary is the same one the payment rails already use: a dedicated
   bounded context (its own service and database) that settles against an external network through a
   rail-style gateway port — mirroring how `openbank-sepa-instant` talks to a scheme through
   `SchemeGatewayPort` (ADR-0104/0181) and how ADR-0027 keeps the substrate integration-agnostic.
   The fiat ledger would see only fiat legs (e.g. the fiat side of an on/off-ramp), never on-chain
   state.

4. **CBDC intermediation, if it arrives, is a distribution role, not a ledger change.** The digital-
   euro model has the intermediary distributing and servicing wallets under the ECB scheme; that is a
   new context and external scheme membership, not an extension of customer fiat pockets.

## Alternatives considered

- **Add a crypto-asset ledger/custody module now.** Rejected: it is a large regulated domain (MiCA
  CASP authorisation, safeguarding, key custody) irrelevant to the platform's purpose of
  demonstrating a licensable *fiat* retail core, and it would introduce settlement and custody
  semantics that risk the fiat ledger's correctness.
- **Model a digital-euro balance as another currency pocket.** Rejected: a CBDC is not just another
  ISO-4217 currency — it carries holding limits, offline/online modes and a distinct scheme
  rulebook; treating it as an FX pocket would misrepresent both the asset and the intermediary role.
- **Say nothing (leave it an implicit gap).** Rejected: without a recorded boundary, evaluators
  cannot tell deliberate scope from missing feature, and a future contributor might wire crypto into
  the fiat ledger — exactly the coupling this ADR forbids.

## Consequences

**Positive**
- The fiat-core boundary is explicit; the ledger keeps its fiat double-entry invariants intact.
- A future crypto/CBDC integration has a pre-agreed shape (new context + gateway port), so it is a
  known extension rather than an open question.

**Negative**
- The platform cannot serve crypto-asset or digital-euro use cases without net-new, regulated
  components; it is not a full-service digital-asset bank.

**Neutral**
- If crypto or CBDC is ever brought in scope, that decision supersedes this ADR, preserving the
  history of the boundary.

## Compliance impact

- PCI DSS: not applicable.
- DORA:    not applicable to this scope decision.
- GDPR:    not applicable — no personal data introduced by declaring crypto/CBDC out of scope.
- PSD2:    not applicable — crypto-assets and CBDC fall outside PSD2's payment-services scope.
- CNB:     MiCA (Regulation (EU) 2023/1114) CASP activities and any digital-euro intermediary role
           are explicitly *not* performed by this platform; no related authorisation is claimed.

## References

- ADR-0039 — ledger as golden source, balance as projection
- ADR-0024 — multi-currency account, single IBAN, per-currency pockets
- ADR-0104 — production-faithful payment rails + scheme simulator (the gateway-port pattern)
- ADR-0184 — SEPA Instant Credit Transfer scheme adoption
- ADR-0027 — cloud-agnostic in-cluster substrate
- ADR-0185 — treasury and liquidity management out of scope (companion boundary ADR)
- Regulation (EU) 2023/1114 (MiCA); ECB digital-euro programme
