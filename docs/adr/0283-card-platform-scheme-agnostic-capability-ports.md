---
date: 2026-09-05
decision-status: accepted
delivery-status: partial
authors: [Jiri Raska, Claude]
supersedes: [0190]
superseded-by: []
delivery-repos: []
tags: [cards, architecture, payments, ai-agents]
summary: "The platform becomes a scheme-agnostic card platform: every network capability is a port with an in-repo sandbox simulator and a real adapter behind it, card-processing is a new money-path bounded context, and the bank picks the network per product from one control plane. Supersedes ADR-0190."
followup: "#8808 — phases 1-4 (#8809 card-processing, #8810 Visa/Mastercard adapters, #8811 Card Center, #8812 card AI agents) are unbuilt; phase 0 governance shipped with this ADR"
---

# ADR-0283 — Card platform: scheme-agnostic capability ports and card-processing as a bounded context

## Context

`openbank-card-issuance-service` (ADR-0113, ADR-0194, ADR-0262) is a good **card issuer registry**:
a card state machine, synthetic PANs in an envelope-encrypted vault, SCA-gated card operations,
limits, channel toggles, an MCC taxonomy, category rules, delegated cards (ADR-0232, ADR-0249) and
a pure `CardAuthorizationPolicy`. ADR-0190 then recorded, deliberately, that authorisation, 3-D
Secure, PIN processing and any processor or scheme connection are **out of scope**.

Measured on `origin/main` on 2026-09-05, that boundary is now the wrong one:

- `POST /api/v1/cards/{id}/authorizations` exists and decides approve/decline. **Nothing calls
  it** — no service, no simulator, no test outside the module. The channel toggles a customer sets
  ("payments abroad off") are enforced by a function with no input. ADR-0190's premise, "nothing in
  the platform declines a live card transaction, because there are none", no longer describes the
  code: the decision point shipped (threat model §4a), only the traffic did not.
- `openbank.cards.events` is consumed by campaign-service and audit-service only. **The ledger has
  never seen a card transaction.** Every other instrument here (SEPA, instant, domestic, SWIFT,
  SDD, standing orders, lending, interest) posts to the ledger; cards end at issuance.
- `CardNetwork { VISA, MASTERCARD, AMEX, UNIONPAY }` is an enum with no behaviour behind it. There
  is no tokenisation (wallets, network tokens), no card dispute or chargeback flow (ADR-0117 is
  instrument-agnostic), no account updater, no push-to-card, no BIN management, no scheme
  reporting, no interchange or scheme-fee model.
- card-issuance was **not** in `rules.yaml: money_path_services`, although it ships an
  authorisation decision and SCA-gated limit changes. A service that decides whether money may
  move is money-path for the same reason sca, consent, vop and delegation are.

The business forces: the platform's owner intends to pursue **partnerships with both Visa and
Mastercard**. A bank running this platform must be able to (a) know what each network offers
through its developer programme, (b) choose between them — per product, not per codebase — and
(c) operate the whole card estate from one place, under PCI DSS, PSD2/SCA, DORA, GDPR, the
Interchange Fee Regulation and scheme rules. Both networks publish free developer sandboxes
(Visa Developer Platform; Mastercard Developers), so the engineering track does not wait on the
commercial one (scheme membership, BIN sponsorship, processor contract, certification).

The engineering constraint that ADR-0190 got right and this ADR keeps: **a licensed
issuer-processor, an EMV 3DS ACS and PIN/HSM operations are a certification programme, not a
reference-architecture deliverable.** They stay behind a port. What changes is that the port now
exists, has an in-repo simulator, and the platform owns everything on its side of it.

## Decision

We will build a **scheme-agnostic card platform**: one card domain, N network adapters, the bank's
choice of network made per product in one control plane. Concretely:

1. **Capability ports, not a "Visa integration".** Every network capability is a port in
   `openbank-libs-domain` (`com.openbank.libs.cards.scheme`): `BinLookupPort`, `MerchantDataPort`,
   `TokenisationPort`, `PushPaymentPort`, `DisputePort`, `AccountUpdaterPort`,
   `NetworkControlsPort`, `FxRatePort`, `ThreeDsPort`. Each port ships with an **in-repo sandbox
   simulator** (the default binding, the same idea as synthetic PANs) and a **real adapter** per
   network behind it. Visa and Mastercard are the first two adapters; Amex, UnionPay and a European
   scheme are later adapters against the same ports, not new designs. The adapter, never the
   caller, owns the portal's authentication (Visa: mTLS + API key, MLE where required; Mastercard:
   OAuth 1.0a request signing) and its credentials live in OpenBao.
2. **Card-processing is a new bounded context and money-path from day one.**
   `openbank-card-processing-service` owns the authorisation flow (request → issuance policy →
   hold on the account → clearing → settlement posting via transaction-service on rail `CARD`,
   ADR-0103), authoritative spend counters, holds ageing and reversal, and a `CardProcessorPort`
   whose sandbox binding is an ISO 8583-shaped acquirer simulator. A licensed processor (Marqeta,
   Thredd, Paymentology, Enfuce, Worldline, Fiserv or another) is a real binding of that port,
   selected by configuration. card-issuance stays the registry (ADR-0113) and the policy owner
   (ADR-0194); it does not grow processing.
3. **card-issuance joins `money_path_services` now** (phase 0, this ADR's PR): SLO pair (rules.yaml
   `slo`, Pyrra), journey accountability (ADR-0252, `journeys.yaml`), the existing threat model
   refreshed for the authorisation decision point, four-eyes assessed. Its operator verbs
   (`card.block`, `card.suspend`, `card.resume`, `card.outbox.requeue`) reduce or repair money
   movement and stay ungated; `card.create`/`card.activate` are SCA-bound customer ceremonies
   (ADR-0194). The gate that WOULD deserve four-eyes is an operator raise of a limit or a control
   without the cardholder's SCA — no such endpoint exists today; add a verb when one does.
4. **One control plane.** "Card Center" in admin-ui, backed by MCP providers (ADR-0228 pattern):
   products and networks (product-catalog gains card product attributes; the network is chosen per
   product and that choice binds the adapter), a generated capability matrix, a disputes desk
   (per scheme reason-code family, on top of ADR-0117, `dispute.decide` four-eyes), token
   lifecycle, processing and adapter health.
5. **A capability registry is the source of truth**
   (`openbank-libs/governance/card-capabilities.yaml`): per capability, per network, what is
   offered, what it costs, which certification it needs, which port implements it. The knowledge
   base in Service Docs and the admin-ui matrix are **generated** from it (rule 6: derived data is
   never hand-edited).
6. **AI where it earns its place**, under ADR-0203 (read-only or proposal-only, human
   disposition): a dispute evidence agent, a scheme-bulletin compliance agent (Visa/Mastercard
   release mandates mapped to our ports, filed as issues with effective dates), a card-ops copilot
   over MCP, and an agentic-commerce proof of concept (Visa Intelligent Commerce / Trusted Agent
   Protocol, Mastercard Agent Pay) on the existing AP2 / MCP / delegation stack — an agent holds a
   scoped, SCA-bound mandate, the network token carries it, card-processing enforces the ceiling.
   No card data beyond the masked PAN ever enters a prompt.
7. **The compliance perimeter is a precondition, not a feature.** The cardholder-data environment
   stays synthetic-PAN-only until a licensed processor is bound; a real PAN path means HSM/P2PE and
   full PCI DSS 4.0.1 scope, decided in its own ADR when that binding is made. PCI 3DS, EMVCo 3DS
   2.x and EMV tokenisation apply to the adapters that touch them. PSD2 RTS SCA and its exemptions
   are enforced at card-processing (the ACS remains the processor's). Each real adapter registers
   its provider in the DORA ICT third-party register. Scheme fees respect the Interchange Fee
   Regulation caps in the fee model.

Delivery is phased and tracked in #8808: phase 1 card-processing (#8809), phase 2 Visa and
Mastercard sandbox adapters (#8810), phase 3 Card Center (#8811), phase 4 agents (#8812); phase 5
is the commercial go-live track (membership or BIN sponsor, processor selection, PCI audit, scheme
certification) and is not an engineering deliverable of this repo.

## Alternatives considered

- **Keep ADR-0190 (issuer only, no processing).** Rejected: the authorisation decision has already
  shipped and is unreachable, the ledger cannot account for card spend, and the owner's stated
  direction is network partnerships. The boundary would now be describing a platform that does not
  exist.
- **Integrate one network directly (Visa first, Mastercard later "if needed").** Rejected: a direct
  integration puts Visa's request shapes into the domain, and the second network then becomes a
  rewrite. Two adapters against one port from the start is what gives the bank the choice; it also
  matches how the payment rails are already built (ADR-0103, clearing and SWIFT ports).
- **Buy the whole thing from an issuer-processor (Marqeta-style API as the domain).** Rejected as
  the *domain*, kept as a *binding*: a processor's API is one implementation of
  `CardProcessorPort`. Making it the domain model would make the platform a thin client of one
  vendor, which is the opposite of "the bank chooses".
- **Build an in-platform ACS / PIN / HSM path.** Rejected, unchanged from ADR-0190: certification
  programme, not reference architecture; enormous PCI scope for synthetic traffic.
- **Extend card-issuance with processing instead of a new service.** Rejected: issuance is a
  registry with a customer-facing SCA surface; processing is a high-volume, acquirer-facing,
  money-moving flow with different scaling, different callers and its own threat model. Same
  reason transaction-service and the rails are separate.

## Consequences

**Positive**
- The card estate becomes a first-class instrument: authorisation, holds, clearing and ledger
  posting exist, and the customer's controls actually control.
- Network choice is a product-configuration decision made in one place, with the trade-offs
  visible in a generated matrix rather than in someone's head.
- Both networks' developer programmes are exercised against real sandboxes in CI, so the
  commercial track starts from working adapters.
- Card-issuance's existing decision point is governed as money-path from now on.

**Negative**
- A new money-path service and up to nine ports with two adapters each is a large surface: more
  threat models, more SLOs, more Pact contracts, more gates. The phasing exists to pay it
  incrementally.
- Sandbox adapters prove shape and contract, not scheme certification; nothing here shortens the
  certification programme in phase 5.
- Real-sandbox credentials are a new secret class (OpenBao) and a new external dependency in CI;
  the simulator binding must remain the default so a portal outage cannot redden a PR.

**Neutral**
- ADR-0113 (registry), ADR-0194 (lifecycle, vault, SCA) and ADR-0262 (envelope encryption) are
  unchanged; this ADR adds contexts around them.
- ADR-0190's *reasoning* about certification survives as decision 7; its *boundary* is
  superseded.

## Compliance impact

- PCI DSS: the cardholder-data environment stays synthetic-PAN-only in this ADR; the ports are
  designed so that a real PAN path (HSM/P2PE, full PCI DSS 4.0.1 scope) is a separate, later
  decision taken when a licensed processor is bound. Adapters that carry network tokens fall under
  the EMV tokenisation and PCI 3DS programmes at that point, not before.
- DORA: each real network or processor adapter registers its provider in the ICT third-party
  register when it is bound; the simulator binding registers nothing.
- GDPR: card-processing adds transaction data about a natural person (merchant, amount, country);
  retention and erasure follow ADR-0118 and the existing card PII retention scheduler.
- PSD2: strong customer authentication for card payments and its exemptions are enforced in
  card-processing on the issuer side; the 3-D Secure ACS stays with the processor. Card-issuance's
  SCA-bound card operations (ADR-0194) are unchanged.
- CNB: not applicable until real card traffic exists; scheme and regulatory reporting are phase 3
  placeholders generated from the same registry.

## References

- ADR-0113 — Card issuance bounded context (registry, lifecycle, synthetic PANs).
- ADR-0190 — Card authorisation, 3DS and PIN processing out of scope (superseded by this ADR;
  its certification reasoning is retained as decision 7).
- ADR-0194 — Card lifecycle, synthetic PAN vault and SCA-gated card operations.
- ADR-0262 — Envelope encryption for the card PAN vault via OpenBao Transit.
- ADR-0103 — Transaction rail and instruction type at origination (rail `CARD`).
- ADR-0117 — Dispute and complaint lifecycle (the disputes desk builds on it).
- ADR-0203 — Business-plane AI agents (the agent constraints in decision 6).
- ADR-0228 — Unified entity resolution and global search (MCP-provider pattern for Card Center).
- ADR-0232, ADR-0249 — Delegated access and the dispositor model (ceilings counted in one place).
- ADR-0252 — Synthetic assurance (journey accountability for the new money-path entries).
- Issues: #8808 (epic), #8809, #8810, #8811, #8812, #8573 (merchant catalogue writer),
  #4348 (synthetic parties), #1302 (accounting-day E2E).
