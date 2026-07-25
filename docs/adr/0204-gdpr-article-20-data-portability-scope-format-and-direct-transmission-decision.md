---
date: 2026-07-25
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [privacy-gdpr, compliance, psd2-api]
summary: "GDPR Art. 20 portability is decided as a scoped, filtered projection of the Art. 15 export — consent/contract-basis data only, no Art. 20(2) direct transmission for now — closing the gap ADR-0118 left unaddressed."
---

# ADR-0204 — GDPR Article 20 data portability — scope, format and direct-transmission decision

## Context

ADR-0118 is this platform's GDPR lifecycle policy. It settles Art. 15 (right of access — shipped,
issue #268 closed) and Art. 17 (erasure — anonymise-and-cascade, shipped). It does not mention
Art. 20 (right to data portability) once. That is not a considered exclusion; it is an absence found
by accident (issue #2371) while auditing an unrelated admin-ui compliance dashboard, whose "Data
portability" row had been citing a false claim ("no export endpoint exists") for a right the platform
had actually half-solved under a different article number.

What exists today, verified against `origin/main`:

- **Art. 15 is real and complete.** `GET /api/v1/parties/{id}/gdpr-export` in `party-service`
  (`PartyResource.kt:241`) aggregates the subject's PII across party-service, kyc-service and
  card-issuance-service via `GdprAggregationAdapter`, returns it as JSON, and audits the access as
  `gdprArticle = "15"`. Access is `@Authenticated` with an explicit `ROLE_ADMIN` / `ROLE_DPO` /
  self-JWT check. This is the artifact issue #2371 found being miscited as absent.
- **Art. 20 has no equivalent.** No endpoint, no filtered payload, no decision on format, no decision
  on Art. 20(2) (direct controller-to-controller transmission "where technically feasible").

Why Art. 15 is not simply relabelled as satisfying Art. 20, which would be the cheapest possible
answer: the two rights have different scopes, and conflating them either over- or under-discloses.

- **Art. 20 is narrower than Art. 15 on lawful basis.** Portability applies only to data the subject
  provided, processed by automated means, on a **consent (Art. 6(1)(a))** or **contract
  (Art. 6(1)(b))** basis. Access (Art. 15) has no such restriction — it reaches everything the
  controller holds regardless of basis, including data processed under legal obligation. The Art. 15
  export today pulls party + kyc + card data, a meaningful share of which (AML/KYC due-diligence
  fields, risk ratings, PEP flags) is processed under Art. 6(1)(c) legal obligation, not consent or
  contract. Returning that set under an Art. 20 request would over-disclose relative to the right
  being exercised.
- **Art. 20 is broader than the current Art. 15 export on data type.** The canonical portable dataset
  in banking is account and transaction history — precisely what `psd2-service`'s AIS endpoints
  already serve to TPPs under consent (`AisResource`: `/accounts`, `/accounts/{id}/balances`,
  `/accounts/{id}/transactions`; mirrored in `BerlinAisResource` for the Berlin Group standard). The
  Art. 15 aggregation does not include transactions at all. A subject exercising Art. 20 to move to a
  competitor almost certainly wants transaction history first; today's export cannot give it to them.
- **Art. 20(4)** requires that portability "shall not adversely affect the rights and freedoms of
  others." A transaction row routinely contains a counterparty's name and IBAN — a third party who
  has not consented to their data leaving via someone else's portability request. Art. 15's existing
  export sidesteps this because access rights are more permissive here than portability's; a fresh
  Art. 20 payload cannot inherit that permissiveness silently.

Why now: ADR-0199 (Customer 360 read model, proposed in PR #2367) needs an accurate compliance
posture to build on, and #2370's fix to the admin-ui compliance page (merged, PR #2378) already had
to describe this row honestly as "undecided" rather than either "ok" or the old false "missing"
claim. This ADR is that decision.

## Decision

We will implement GDPR Art. 20 as a **purpose-built, filtered projection**, not a relabelling of the
Art. 15 export and not a new aggregation from scratch.

**D1 — Scope filter: consent- and contract-basis data only.** The Art. 20 payload is assembled by
starting from the same per-service aggregation pattern `GdprAggregationAdapter` already uses for
Art. 15, then applying an explicit basis filter: only fields the owning service can attribute to
Art. 6(1)(a) or Art. 6(1)(b) processing are included. AML/KYC due-diligence fields, risk ratings, PEP
flags, sanctions-screening state — all Art. 6(1)(c) legal-obligation data — are excluded by
construction, the same way ADR-0118's five-tier PII classification already excludes statutory-override
data from erasure. Each owning service (party-service, kyc-service) declares its own field-level
basis; this is not a blanket allow-list maintained in one place, because the basis is a property of
the field's *purpose*, which only the owning service knows.

**D2 — Add transaction history as the primary new data source, filtered for third-party rights.**
Account and transaction data becomes part of the Art. 20 payload, sourced the same way `psd2-service`
already serves it to consented TPPs — reusing the existing AIS read path rather than building a
second one. Per Art. 20(4), a counterparty's name is retained (the subject needs to recognise their
own transaction) but the counterparty's IBAN is redacted to its issuing-bank BIC-equivalent prefix
only, mirroring the level of detail a statement already discloses to the account holder versus what
would identify the counterparty's own account to a third party receiving the export.

**D3 — Format: structured JSON, matching what Art. 20(1) requires and what the Art. 15 export already
does.** `PartyGdprExport.toResponse()` already emits "structured, commonly used, machine-readable"
JSON — the format requirement is met by reuse, not by building a new serializer. No CSV/XML variant is
built for a first version; if a future consumer needs one, that is a format-conversion problem
downstream of a correct JSON payload, not a reason to duplicate the aggregation logic.

**D4 — Art. 20(2) direct transmission is explicitly declined for now, not silently unimplemented.**
The platform will **not** offer machine-to-machine transmission of a portability export directly to
another controller "where technically feasible" in this iteration. Recording this as a decision rather
than an omission matters: Art. 20(2) is conditional on technical feasibility, which the controller
gets to assess, and the honest assessment today is that this platform has no controller-to-controller
handshake, no receiving-controller authentication, and no precedent for one (ADR-0181's MCP server
exposes read access to *governed AI agents*, not to arbitrary receiving banks). Building one
speculatively, ahead of any request or regulatory pressure to do so, would be exactly the "build for a
hypothetical future requirement" this codebase's own engineering conventions warn against. This is
revisited if and when a real receiving party (e.g. a specific competitor bank, under a data-sharing
agreement) is named.

**D5 — Delivery: a new endpoint, not an extension of the existing one.** `party-service` gains
`GET /api/v1/parties/{id}/gdpr-portability-export`, alongside the existing `/gdpr-export`, with the
same `@Authenticated` three-shape access check (`ROLE_ADMIN` / `ROLE_DPO` / self-JWT) and its own
audit event, `gdprArticle = "20"`, distinct from the existing `"15"` events — so the two rights remain
separable in the Art. 30 record of processing, which is the whole reason ADR-0198's sibling decision
(marketing consent) insists on per-scope granularity rather than one shared flag.

**D6 — No customer-edge self-service route in this ADR.** Whether the mobile app or admin-ui exposes
this to a customer directly, versus routing it through an operator-mediated process (the way #2370's
GDPR Data Processing Agreement row already flags legal documentation as partially out of this
platform's scope), is left to the service teams that own those surfaces. What this ADR fixes is that
the capability *exists* and is *correctly scoped*; who gets a button for it is a product decision this
ADR does not need to make to be useful.

## Alternatives considered

- **Relabel the existing Art. 15 export as satisfying Art. 20.** Zero build cost — literally change a
  UI label. Rejected: this is the exact conflation issue #2371 found and #2370's PR corrected away
  from. It over-discloses (legal-obligation data leaves under a consent-scoped right) and
  under-delivers (no transaction history, the data a portability requester actually wants).
- **Build Art. 20(2) direct transmission now, since it's "more complete."** Would make the platform
  look further along on paper. Rejected per D4 — there is no real receiving controller to design a
  handshake against, and a speculative one is unlikely to match whatever a real one eventually
  requires; better to decide this when a concrete counterparty exists.
- **A dedicated `openbank-gdpr-service`.** Would centralise Art. 15/17/20 in one place instead of each
  owning service doing its own aggregation contribution. Rejected as disproportionate: the existing
  `GdprAggregationAdapter` pattern in party-service already works, is tested, and a new service adds
  an OPA sidecar, a `version.txt`, and a deployment for a capability that is fundamentally "ask each
  service for its slice" — the coordination cost of a new service exceeds the coordination cost of
  extending an existing adapter.
- **Skip the basis filter and export everything Art. 15 exports, accepting the over-disclosure.**
  Simplest possible D1. Rejected: Art. 20 is a right with a defined scope in the GDPR text itself: an
  export that ignores that scope is not a compliant Art. 20 response, it is an Art. 15 response with a
  different name on the button, and an auditor or regulator reading the ADR would catch the mismatch
  immediately — the entire reason this ADR exists is to stop that exact category of claim.

## Consequences

**Positive**
- Closes the specific inaccuracy issue #2371 found, with the actual capability rather than another
  relabelling.
- Reuses two already-shipped subsystems (`GdprAggregationAdapter`, psd2-service's AIS read path)
  rather than building new aggregation logic, keeping the marginal cost close to the filter and the
  transaction-inclusion work.
- Art. 20(2) gets a recorded, reasoned "not yet" instead of a silent gap that could be mistaken for an
  oversight in a future audit.
- The `gdprArticle = "20"` audit event, distinct from `"15"`, gives the Art. 30 record the granularity
  a real DPO review will expect.

**Negative**
- Each owning service (party-service, kyc-service, and now transitively psd2-service/account-service
  for transaction data) must correctly declare which of its fields are consent/contract-basis versus
  legal-obligation-basis. Getting this wrong in either direction is a real compliance risk: too narrow
  under-serves the right, too broad recreates the over-disclosure this ADR exists to prevent. This is
  not a one-time classification — a new field added to any contributing service needs its basis
  declared at the point it's added, or it silently defaults to whichever a careless implementation
  assumes.
- Redacting counterparty IBANs to a partial prefix (D2) is a judgment call about how much detail
  Art. 20(4) permits; it has not been reviewed by counsel and should be before this ships.
- A new endpoint plus a new audit article code is a small but real surface increase on
  `party-service`, which is already the aggregation point for two GDPR rights and is becoming a
  natural target for a third-party data request.

**Neutral**
- Whether a future `openbank-app` release surfaces this to the customer directly is unscoped here by
  design (D6).
- If a genuine Art. 20(2) receiving-controller request ever arrives, it becomes its own ADR rather
  than an amendment to this one — this ADR's "not yet" is a decision, and decisions get superseded
  by new ADRs, not edited.

## Compliance impact

- PCI DSS: not applicable — no cardholder data (PAN) is included; transaction rows carry counterparty
  IBAN under the D2 redaction, not card data.
- DORA: not applicable — no ICT third-party or resilience dimension; this is an internal capability
  reusing internal services.
- GDPR: Art. 20(1) (structured, commonly used, machine-readable format — met via reuse of the
  existing JSON export shape); Art. 20(2) (direct transmission — explicitly declined for now per D4,
  a reasoned position rather than a gap); Art. 20(4) (rights of others — the counterparty-IBAN
  redaction in D2 is the mechanism, pending counsel review); Art. 6(1)(a)/(b) (the lawful-basis scope
  filter in D1, which is the entire mechanism this ADR adds); Art. 30 (the new `gdprArticle = "20"`
  audit trail, distinct from the existing Art. 15 record).
- PSD2: not applicable as a PSD2 obligation, but D2 technically reuses the AIS read path that PSD2
  governs for TPP access — this ADR does not grant TPP access; it reuses the *read mechanism*
  internally for a GDPR-driven, not PSD2-driven, disclosure to the data subject themselves.
- CNB: not applicable.

The counterparty-IBAN redaction level and the lawful-basis classification of specific fields are
engineering judgment calls made to keep this ADR concrete, not legal advice; both should be confirmed
with counsel before the filter rules are encoded, per the same caveat ADR-0198 carries for its own
lawful-basis reasoning.

## References

- [ADR-0118](0118-gdpr-data-lifecycle-and-retention.md) — the GDPR lifecycle ADR this extends; Art. 15
  and Art. 17 are settled there, Art. 20 was absent and is settled here.
- [ADR-0198](0198-marketing-consent-as-a-first-class-consent-service-scope.md) — the sibling decision
  in the same PR batch (#2367) establishing per-scope granularity over a shared flag, and the source
  of the "confirm with counsel" caveat pattern this ADR follows.
- [ADR-0199](0199-customer-360-read-model-in-a-new-crm-service.md) — the read model this ADR's
  compliance-accuracy work supports.
- [ADR-0181](0181-mcp-server-exposing-psd2-and-admin-read-apis-to-governed-ai-agents.md) — cited in D4
  as the platform's only existing "share data with an external party" precedent, and why it is not one
  for Art. 20(2).
- Issue #2371 — the finding that produced this ADR: Art. 20 absent from ADR-0118, discovered while
  correcting a false "endpoint missing" claim that actually described Art. 15, which does exist.
- Issue #268 — Art. 15 export gaps, closed; the shipped capability this ADR builds alongside rather
  than replaces.
- `openbank-party-service/src/main/kotlin/com/openbank/party/infrastructure/rest/PartyResource.kt` —
  the existing `/gdpr-export` endpoint and its `GdprAggregationAdapter`.
- `openbank-psd2-service/src/main/kotlin/com/openbank/psd2/infrastructure/rest/AisResource.kt` and
  `BerlinAisResource.kt` — the AIS read path D2 reuses for transaction data.
