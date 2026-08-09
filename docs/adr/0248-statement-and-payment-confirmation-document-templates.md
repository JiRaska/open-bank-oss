---
date: 2026-08-07
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [documents, statements, psd2-api]
summary: "Adds monthly statement, PAD Art. 5 annual fee statement, and payment confirmation as document-service templates, rendered on demand via the non-persisting preview endpoint and never stored, off the money path."
---

# ADR-0248 — Statement and payment confirmation document templates

<!--
Front-matter fields, enums and rules: docs/adr/SCHEMA.md. The number and title are
NOT repeated in the front-matter — they come from the filename and this H1.

`docs/adr/new.sh "Title"` writes the block above for you with a collision-free
number. Before pushing:
    bash docs/adr/gen-index.sh && bash .github/scripts/check-adr-registry.sh

Write `summary` last, once you know what you actually decided. It is the line that
represents this ADR in DIGEST.md, which is what people and agents read instead of
the ~225k-word fleet — so it has to state the DECISION, not the topic.
-->

## Context

`openbank-statement-service` renders monthly per-pocket statements
(camt.053.001.08 / MT940 / a plain-text "PDF") as deterministic, on-demand
projections off a persisted `StatementPeriod` — nothing is warehoused, by
deliberate design (ADR-0035). That design satisfies PSD2 (EU) 2015/2366 Art.
58(2): a statement only has to be *made available*, reproducibly, not
pushed. ADR-0035 and `openbank-statement-service/CLAUDE.md` both explicitly
carve the **PAD (EU) 2014/92 Art. 5 annual statement of fees** out of scope
("owned by the fee/billing domain — not produced here"), and note a
"styled/eIDAS-sealed PDF" as a documented follow-up neither built yet.

Two customer-facing document types are missing from the platform entirely:

- An annual statement of fees (PAD Art. 5) — a genuine **push** duty,
  legally distinct from the monthly statement's make-available duty, and
  currently produced by no service.
- A payment confirmation — no template, no endpoint, no trigger anywhere.

`openbank-document-service` already has a working, reusable templating
stack (Handlebars → HTML → PDF, content-addressed object store, outbox) but
today only seeds three onboarding legal-agreement templates
(`DocumentTemplateSeed.kt`: VOP, RAMCOVA_SMLOUVA, UCET_SMLOUVA). ADR-0162,
which established that service, already names "account statements" and
"payment confirmations" as intended non-product-bound use cases — this ADR
is the follow-through on that placeholder.

An audit of the platform found no reference anywhere to PSD2 Art. 60
(unauthorized-transaction liability) or to the payment-information duties
in Art. 45/48 — this ADR only closes the statement/confirmation gap; Art.
60 disclosure content is out of scope here and tracked separately.

## Decision

We will add three new customer-facing document template families to
`openbank-document-service`. None of the three is ever persisted:
document-service's existing **non-persisting**
`POST /api/v1/documents/templates/preview` endpoint (already used today
for template-authoring previews, reused here as-is) renders each one on
demand, hands the bytes straight to the caller or the delivery channel,
and discards them — nothing rendered from any of these three templates is
ever written to document-service's object store, and no `Document` row or
`document.generated` outbox event is created for them.

`openbank-statement-service`'s own camt.053/MT940/pocket-PDF rendering is
**unchanged** — this ADR adds a document layer on top of it, it does not
replace the "persist the model, not the files" design of ADR-0035. All
three new templates extend that same guarantee to this new styled/PDF
layer.

Two of the three (monthly statement, payment confirmation) are rendered
**synchronously, only when the customer explicitly asks for them** — a
download click in the customer-facing edge — directly against
document-service's `preview` endpoint, with no Kafka involved at all: the
data they need (`StatementModel` for the statement, the payment's own
persisted status record for the confirmation) already lives in the owning
service, so there is nothing to trigger in advance. The third (annual
statement) is a genuine **push** duty — PAD Art. 5 requires the bank to
send it, not wait for the customer to ask — so it alone keeps an
asynchronous trigger: a new document-service Kafka consumer on
billing-service's annual-aggregation outbox event fires the render, at
send time, through the same non-persisting `preview` endpoint, then hands
the bytes to the delivery channel and discards them exactly like the
other two.

### 1. Monthly statement — `MESICNI_VYPIS_CS` / `MESICNI_VYPIS_EN`

- Legal basis: PSD2 Art. 58(2) — "provided or made available … in a way
  that allows the payer to store and reproduce it unchanged" — satisfied by
  on-demand rendering, no storage of the rendered file required.
- Trigger: synchronous, on customer download request only. No
  pre-generation at period-close, no document-service Kafka consumer for
  this template. Statement-service (or the customer-facing edge calling
  it) invokes document-service's non-persisting
  `POST /api/v1/documents/templates/preview` at click time with data
  assembled from `StatementModel`; the response is streamed to the
  customer and never persisted.
- Data owner: statement-service's `StatementModel`.
- Required fields: provider identification; account IBAN and pocket
  currency; statement period (from/to); opening and closing balance;
  itemized transaction list (booking date, value date, amount, currency,
  counterparty, reference); legal and electronic sequence number;
  generation timestamp taken from `StatementModel.closedAt` (never wall
  clock, to keep re-renders byte-identical); a durable-medium
  reproducibility notice.

### 2. Annual statement of fees — `ROCNI_VYPIS_POPLATKU_CS` / `ROCNI_VYPIS_POPLATKU_EN`

- Legal basis: PAD (EU) 2014/92 Art. 5 and Annex II — a push duty,
  distinct from and in addition to PSD2 Art. 58(2).
- Trigger: a new annual aggregation use case in `openbank-billing-service`
  (does not exist today — `BillingCycle` is a generic, non-annual-specific
  period) publishing a new outbox event, `billing.annual-fee-summary.ready`,
  consumed by document-service. Unlike the payment-confirmation consumer,
  this one calls the same non-persisting `.../templates/preview` endpoint
  at send time and hands the bytes straight to the push-delivery channel —
  render-and-discard, not render-and-store, for the same reason as the
  monthly statement.
- Data owner: `openbank-billing-service`'s `AssessedFee`/`BillingCycle`
  history. `StatementModel` has no fee data and is not the source.
- Required fields: provider identification; account IBAN; calendar year;
  itemized list of every fee charged in the year, each under its Annex II
  standardized name/category; total fees charged for the year; the
  debit/credit interest rate applied to the account in the period, if any;
  currency; issue date; a notice identifying the document as the PAD Art.
  5 annual statement of fees.
- Open item: the exact mapping from this platform's fee catalog to PAD
  Annex II's standardized terminology needs legal review before this
  template goes to production; delivery-status stays `planned` until that
  review is scheduled.

### 3. Payment confirmation — `POTVRZENI_O_PLATBE_CS` / `POTVRZENI_O_PLATBE_EN`

- Legal basis: PSD2 Art. 45/48 — information to be given to the payer/payee
  after a payment is executed.
- Trigger: synchronous, on customer request only (e.g. "download
  confirmation" in payment/transaction history) — no pre-generation on
  `SepaPaymentStatusChangedEvent`/`DomesticPaymentStatusChangedEvent`, no
  document-service Kafka consumer for this template. The originating
  payment service (or the customer-facing edge calling it) invokes
  document-service's non-persisting
  `POST /api/v1/documents/templates/preview` at request time with data
  read from its own already-persisted payment record; the response is
  streamed to the customer and never persisted a second time.
- Data owner: the originating payment service's own persisted payment
  record (SEPA or domestic) — that record is already the durable
  evidentiary copy; document-service never becomes a second copy of it.
- Required fields: payment reference and unique end-to-end ID; execution
  or settlement date and time; amount and currency; payer and payee IBAN;
  payee name; remittance information / variable symbol; terminal status;
  an optional reference to the SCA evidence that authorized the payment.

All six rows (three families × CS/EN) follow the existing
`DocumentTemplateSeed.kt` pattern: `engine = HANDLEBARS`,
`status = PUBLISHED`, `classification = "restricted"`, the shared
`LETTERHEAD_CS`/`LETTERHEAD_EN` inline-SVG header, and no external
resource fetch (the existing SSRF mitigation). Adding new `templateCode`
string values to the existing, unconstrained `CreateTemplateRequest` /
preview-request schema is not an OpenAPI-breaking or even additive
change — no `info.version` bump is required, and no new endpoint is
added: `/api/v1/documents/templates/preview` already exists and is the
only render path any of the six rows use.

This is still a trust-boundary change under `rules.yaml:
trust_boundary_diff_change` — one new inbound Kafka consumer (the
annual-statement trigger) plus two new synchronous inbound call paths
into document-service's `preview` endpoint (from statement-service, and
from each of SEPA/domestic payment services) — even though none of
statement-service, document-service, billing-service, sepa-payment or
domestic-payment is on the `money_path_services` list. `openbank-statement-service`
has no threat model today (`docs/threat-models/statement-service.md` does
not exist) — this decision requires authoring one, and updating
`docs/threat-models/document-service.md` for the new consumer and the new
synchronous callers.

Implementation (the new Kafka consumer, the billing-service annual
aggregation use case, the actual Handlebars template bodies, and the
threat model documents) is tracked as a follow-up issue per ADR-0052,
not delivered by this ADR itself.

## Alternatives considered

- **Route statement PDFs synchronously through document-service's
  `/api/v1/documents/render` REST endpoint from statement-service /
  billing-service / payment services.** Rejected: document-service's own
  openapi spec and `AccountCreatedConsumer` doctrine are explicit that it
  must never sit on a synchronous money-path call; a REST call from
  statement-service on period-close would make a customer-facing statement
  render a hard dependency of the close operation, which ADR-0035's
  fail-closed reconciliation model does not want.
- **Keep the annual fee statement inside `openbank-statement-service`
  instead of `openbank-billing-service`.** Rejected: `StatementModel` has
  no fee or interest-rate fields, and ADR-0035 and this service's own
  CLAUDE.md already assign PAD Art. 5 to the fee/billing domain; building
  it in statement-service would mean either duplicating billing data or
  giving statement-service a new dependency on billing-service, both worse
  than billing-service owning the trigger and the data it already has.
- **Persist the monthly camt.053/MT940 export itself in document-service's
  object store**, not just a new styled/sealed customer-facing PDF.
  Rejected: this would contradict ADR-0035's "persist the model, not the
  files" determinism guarantee (every re-render must stay byte-identical
  off `StatementPeriod`); this ADR only adds a new, separately-generated
  customer document, it does not change what statement-service persists.
- **Pre-render all three documents and persist them in document-service's
  object store** (the ADR's original shape for payment confirmation).
  Rejected across the board, including for payment confirmation: it would
  store a second full copy of personal financial data the owning service
  (statement-service or the payment service) already holds durably, for
  documents PSD2/PAD only require *making available*, *sending*, or
  *informing* — never retaining a rendered copy of. Render-on-demand via
  the existing non-persisting `preview` endpoint gives the same customer
  experience with strictly less personal data at rest, and needs no
  object-store retention policy for any of the three template families.
- **Keep payment confirmation on the persisted `/render` + Kafka-consumer
  path while only the two statement documents go non-persisting.**
  Considered as the ADR's first draft. Rejected on reflection: the
  payment's own status record in sepa-payment/domestic-payment-service is
  already the durable evidentiary copy, so a second stored PDF in
  document-service adds PII at rest and its own retention/threat-model
  burden for no compliance benefit — the same data-minimisation argument
  that ruled out persisting the statements applies identically here, once
  actually examined instead of assumed away by "it documents a specific
  event."

## Consequences

**Positive**
- Closes the two document-type gaps ADR-0162 already flagged as intended
  but unbuilt, using document-service's existing generic templating
  pipeline rather than a new one.
- All three documents stay off every money path — none of the three
  trigger sources block payment execution, period-close, or fee
  assessment.
- One rendering discipline for all three template families: render via
  the non-persisting `preview` endpoint, hand off, discard. No per-family
  retention policy to design or audit.
- No new PII document store anywhere in this ADR: every rendered document
  is generated and discarded, never written to document-service's object
  store — zero new `Document` rows, zero new `document.generated` outbox
  events, one fewer service holding a copy of the customer's transaction
  and payment history at rest.
- Simpler trigger surface than the original draft: two of the three
  templates need no Kafka consumer at all, since rendering happens
  synchronously at the moment the customer asks — only the annual
  statement's push duty still needs one.

**Negative**
- Adds a genuinely new cross-service dependency: billing-service must ship
  an annual aggregation use case it does not have today before the annual
  statement can exist.
- One new Kafka consumer (the annual-statement trigger) and three new
  synchronous caller relationships into document-service's `preview`
  endpoint (from statement-service, sepa-payment-service and
  domestic-payment-service) each add a trust-boundary surface that has to
  be covered by a threat model before this can ship as `delivery-status:
  shipped`.
- The annual statement's push-delivery path has no persisted copy to
  retry from on a failed send — a delivery failure must re-invoke the
  synchronous render, not retry a stored file; the annual-aggregation use
  case needs to be designed with that retry shape in mind.
- The synchronous "render on customer click" path makes document-service
  a hard dependency of the download action itself (monthly statement,
  payment confirmation): if document-service is unreachable, that
  specific download fails, though nothing upstream (payment execution,
  period-close) is affected. Acceptable because a failed download is
  retryable by the customer with no data lost, unlike a blocked payment.

**Neutral**
- Does not change anything about how statement-service's existing
  camt.053/MT940/pocket-PDF rendering works, persists, or is triggered.
- Does not address PSD2 Art. 60 (unauthorized-transaction liability)
  disclosures; that remains a separate, currently untracked gap.

## Compliance impact

- PCI DSS: not applicable — no cardholder data involved.
- DORA:    not applicable — no ICT third-party or resilience change; new
           Kafka consumers are internal, non-critical, off the money path.
- GDPR:    Art. 5(1)(c) data minimisation motivates never persisting any
           of the three rendered documents (render-and-discard via the
           non-persisting preview endpoint, for the statement documents
           and payment confirmation alike); the underlying personal data
           itself is already covered by existing statement/payment
           processing lawful bases, no new lawful basis introduced.
- PSD2:    Art. 58(2) (monthly statement, make-available, durable medium)
           and Art. 45/48 (post-execution payment information) are the
           legal basis for the monthly-statement and payment-confirmation
           templates respectively.
- CNB:     not applicable — no new ČNB reporting obligation; retention of
           the underlying statement record is unchanged from ADR-0035.

Note: the annual statement of fees is a **PAD** (EU) 2014/92 Art. 5
obligation, not a PSD2 one — it has no row above because PSD2 is the only
regulatory framework this template enumerates; treat the PAD Art. 5
citation in the Decision section as the governing basis for that template
and flag it to legal/compliance review before production use, per the open
item noted there.

## References

- ADR-0035 — Multi-currency account statements (PSD2 Art. 58(2), PAD Art.
  5 scope boundary, "persist the model, not the files").
- ADR-0162 — Document management, templating and e-signature architecture
  (names account statements and payment confirmations as intended,
  unbuilt use cases; establishes the Handlebars/PDF/outbox pipeline reused
  here).
- ADR-0052 — Issues as the actionable backlog (governs how this ADR's
  implementation work is tracked).
- `openbank-statement-service/CLAUDE.md` — "Out of scope" section.
- PSD2 (EU) 2015/2366, Art. 45, 48, 58(2).
- PAD (EU) 2014/92, Art. 5 and Annex II.
