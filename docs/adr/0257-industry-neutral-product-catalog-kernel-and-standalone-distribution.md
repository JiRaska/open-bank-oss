---
date: 2026-08-12
decision-status: accepted
delivery-status: shipped
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [product-catalog, architecture, api-contract, database]
summary: "Product-catalog gains an industry-neutral specification/offering/revision kernel beside its compatible banking API, then ships as a single-tenant standalone product with banking and insurance packs."
---

# ADR-0257 — Industry-neutral product catalog kernel and standalone distribution

## Context

`openbank-product-catalog` is the authority for the stable product UUID that account-service stores
(ADR-0105), for product availability checked during account opening (ADR-0158), and for configuration
read by billing, card issuance and document generation. Those integrations make its existing
`/api/v1` identity and banking fields a compatibility boundary, even though the service itself is
not money-path.

The current aggregate mixes four different concepts in one mutable JSON document: the reusable
definition of a product, the commercial offer, its effective-dated version and its bank-specific
runtime configuration. It can represent accounts, cards and deposits, but adding insurance,
telecommunications or other industries by appending another nullable `*Config` block would make the
aggregate a closed list of industries. A `PUT` also overwrites the live document, so a contract
cannot pin and later reconstruct the exact approved terms under which it was opened.

Running the catalog outside OpenBank currently requires the monorepo build, OpenBank runtime
conventions, a bank seed and OpenBank-specific deployment manifests. That is source-code separation,
not a standalone product boundary.

This change must preserve the following existing decisions:

- the catalog owns one stable canonical product UUID and existing accounts keep resolving it
  (ADR-0105);
- each deployment is single-tenant and no tenant column, claim or policy dimension enters the model
  (ADR-0152);
- APIs remain design-first and contract versions are independent of release versions (ADR-0005,
  ADR-0048);
- all emitted events use a same-transaction outbox and versioned AsyncAPI contract (ADR-0003,
  ADR-0006, ADR-0050);
- browser access continues through the admin BFF in the bank deployment (ADR-0056);
- product-derived fee execution remains owned by billing-service, not by the catalog (ADR-0143).

## Decision

We will evolve the service through an additive `v2` model while keeping `/api/v1` and the existing
`products` table readable throughout the migration. Delivery follows expand, migrate, verify,
contract; contraction is a separate future decision and never an implicit cleanup step.

### 1. Industry-neutral catalog kernel

The framework-free domain owns these concepts:

- **ProductType** — a versioned schema of typed attribute definitions and validation constraints;
- **ProductSpecification** — what a product is and which type/schema governs it;
- **ProductOffering** — where, when, to whom and through which channel a specification is offered;
- **ProductRevision** — an immutable snapshot of offering content, prices, rules, documents and
  relationships;
- **PriceComponent** — a decimal amount or rate with explicit currency, unit, cadence, tax treatment
  and effective interval;
- **EligibilityRule** — a closed, typed, explainable predicate model; arbitrary executable scripts
  are forbidden;
- **OfferingRelationship** — bundle, add-on, replacement, dependency and compatibility edges;
- **MarketContext** — brand, country, channel, segment, locale and evaluation time used to select a
  published projection. It is not a tenant boundary.

Localized content is keyed by BCP 47 locale. The banking pack keeps the existing Czech and English
contract, while a third shipped locale activates ADR-0150's ICU message-catalog migration rather
than adding another field to the kernel.

The generic kernel contains no bank, insurance or telecom enum. Industry packs contribute product
types, schemas, validators, UI metadata, examples and external-standard mappers. The existing bank
fields become the first banking pack. An insurance reference pack proves the boundary with coverage,
peril, exclusion, limit, deductible, premium and underwriting-question attributes; it does not make
the kernel depend on ACORD.

### 2. Immutable authoring and publication

Every edit creates or changes a mutable draft revision. A published revision is immutable. Publishing
atomically closes any superseded interval, records maker, checker, reason and content hash, and writes
the outbox event. The checker must differ from the maker. A contract or account may pin a revision id;
`effectiveAt` queries reproduce the published view for a historical instant.

Authoring and published reads are separate capabilities. Published projections never leak drafts.
Optimistic concurrency is mandatory on every mutable resource; stale writes fail instead of silently
overwriting a concurrent editor.

### 3. Compatibility boundary

`/api/v2` is the authoritative generic API. `/api/v1` remains a banking compatibility adapter backed
by the same canonical UUIDs. During the dual-write window, a v1 mutation updates its mapped banking
draft and a v2 publication refreshes the v1 read projection. Existing consumers continue to receive
the fields and status semantics their Pact contracts require.

The v1 API is not removed until ADR-0145's deprecation window is declared and telemetry proves zero
live consumers. Decimal v2 prices map to legacy JSON numbers only at the v1 boundary. Unknown or
unmappable v2 attributes are never guessed into v1 fields.

### 4. Persistence and change distribution

New normalized identity, revision, price, relationship, approval, audit and outbox tables are added by
Flyway beside the existing table. JSONB remains the payload representation for schema-governed
attributes, while identity, lifecycle, effective dates, version and lookup dimensions are relational
and indexed. Backfill is idempotent, observable and preserves all existing UUIDs.

Every accepted state change has both a durable audit row and an outbox row in its transaction.
The catalog's first event contract is schema-first AsyncAPI 3.0 with a separately versioned JSON
Schema payload and CloudEvents-compatible stable headers. This selects JSON Schema for catalog
topics without retroactively migrating the fleet-wide raw JSON debt recorded by ADR-0006. Kafka
delivery is an adapter; a standalone installation without Kafka can consume the same durable change
stream through a cursor API or webhooks without weakening the source transaction.

### 5. Standalone distribution

The project ships a reproducible OCI image, Helm chart and Docker Compose quickstart. PostgreSQL is
the only mandatory stateful dependency. Authentication uses standard OIDC discovery and configurable
scopes; a development identity profile is explicit and cannot be enabled accidentally in production.
The default installation is empty, while banking and insurance examples are opt-in imports.

The standalone boundary stays single-tenant-per-deployment per ADR-0152. Supporting multiple firms
inside one database would require a superseding ADR and a separate isolation proof.

### 6. Product Studio and interoperability

The operator UI becomes schema-driven and consumes generated API types. It supports draft/live diff,
validation, contextual preview, approval and revision history. Industry interoperability is delivered
as adapters and conformance suites (for example TMF620 or ACORD mappings), never as fields in the
kernel.

A banking revision continues to carry the document template code defined by ADR-0162. Publishing a
catalog revision does not rewrite or pin the document-service template version; the rendered and
signed document snapshot remains the evidentiary artifact. Any future change to that binding needs a
separate decision because it crosses the document-service boundary.

### Delivery and stop conditions

Work is delivered in P0-P5 vertical increments. Each increment must preserve the bank Pact contracts,
boot against a real PostgreSQL instance, exercise one full author-to-published-read path, and leave a
rollback that keeps the prior path operational. A phase is not complete because its classes or routes
exist; it is complete only when an external observation proves the behavior.

## Alternatives considered

- **Append `InsuranceConfig`, `TelecomConfig`, and further nullable blocks to `Product`.** Rejected:
  every new industry would require a kernel release, validation would remain scattered, and a product
  could accumulate mutually incompatible blocks.
- **Replace `/api/v1` and migrate every consumer in one coordinated release.** Rejected: account
  opening, billing, documents and card issuance have different failure postures. A flag day would put
  a reference-data redesign onto bank runtime paths without a compatibility window.
- **Store the whole generic catalog as an unconstrained JSON document.** Rejected: it makes authoring
  flexible but moves schema, money precision, effective-date and relationship integrity failures to
  consumers. JSONB attributes are accepted only behind a versioned ProductType schema.
- **Adopt a third-party commerce catalog as the platform authority.** Rejected: commerce products are
  a useful benchmark, but regulated contract reconstruction, bank UUID compatibility, single-tenant
  OSS operation and industry-specific validation remain our responsibility. External catalogs can
  integrate through adapters.
- **Add SaaS multi-tenancy now.** Rejected by ADR-0152 and unnecessary for the stated deployment
  model. One independently operated catalog per firm gives a smaller and more defensible isolation
  boundary.

## Consequences

**Positive**
- Existing bank consumers retain their identity and wire contract while new industries use one
  neutral model.
- Immutable revisions make the exact sold terms reproducible and auditable.
- Typed schemas allow extension without kernel changes or arbitrary code execution.
- Standalone packaging and standard OIDC remove OpenBank infrastructure as an adoption requirement.

**Negative**
- The dual-model compatibility window adds mapping code, storage and tests.
- Publication becomes more deliberate; a direct edit can no longer mutate a live offering.
- Schema and industry-pack governance become product capabilities that require long-term ownership.

**Neutral**
- Product-catalog remains reference data and does not execute billing, underwriting, account opening
  or money movement.
- Single tenancy is preserved; horizontal SaaS tenancy is neither implemented nor implied.

## Compliance impact

- PCI DSS: not directly applicable — the catalog stores no cardholder data; card product metadata is
  not PAN data.
- DORA: engaged — the catalog is an ICT dependency on bank onboarding and billing paths, so the
  compatibility window, restore proof and liveness evidence are controls.
- GDPR: usually not applicable to catalog definitions; segment and eligibility schemas must not
  contain customer records or copied personal data.
- PSD2: indirectly engaged — product configuration may affect payment-account fees and disclosures,
  but this service neither exposes XS2A nor initiates payments.
- CNB: engaged through product governance and reconstructible customer terms; publication evidence
  must identify the approved revision without claiming the catalog executes regulated decisions.

## References

- ADR-0002 — hexagonal architecture per service
- ADR-0003 / ADR-0006 / ADR-0050 — transactional outbox and event contracts
- ADR-0005 / ADR-0048 / ADR-0145 — API design, versioning and deprecation
- ADR-0105 — canonical product identity
- ADR-0138 / ADR-0143 — fee rules and runtime fee posting
- ADR-0152 — single tenancy per deployment
- ADR-0158 — account-opening catalog validation
- ADR-0212 — versioned, effective-dated compliance packs pinned on contracts
