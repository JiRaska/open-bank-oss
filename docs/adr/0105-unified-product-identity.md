# 105. Unified product identity across product-catalog and account-service

Date: 2026-06-22
Status: Accepted
Author(s): Jiří Raška

## Context

An account is an instance of a banking **product** (current account, savings, term
deposit, …). The product carries the rules a dozen consumers need: fee schedule,
multi-currency support, interest/overdraft config, T&Cs, eligibility. Today an account
cannot be reliably joined to its product, because the two services disagree on what a
product *is*:

- **`account-service`** stores `accounts.product_id` as a **UUID**, and in the sandbox
  these are placeholder/sentinel values (`00000000-0000-0000-0000-0000000000c2`,
  `…c3`) assigned at account open — they do not correspond to any real catalogue entry.
- **`product-catalog`** is the product authority, but it is an **in-memory seed** keyed
  by **string codes** (`prod-001` … `prod-015`); each carries the real
  `multiCurrencyConfig.supportedCurrencies`, fees, etc. (The catalog's own code notes
  the in-memory seed is a tracked follow-up.)

The two id spaces never align, so `GET product-catalog/products/{account.product_id}`
always 404s. This surfaced building customer-managed currency pockets (ADR-0109 P2):
the edge resolves an account's *openable* currencies from the product's
`supportedCurrencies`, but the account's `product_id` isn't findable in the catalog, so
the "Add currency" list is empty. The same gap blocks any product-driven feature —
fee display, eligibility, statement product labels, suitability checks.

This is not a pockets bug; it is a missing **shared product identity**.

## Decision

**`product-catalog` is the single source of product identity. Every product has one
stable canonical UUID; `account-service.accounts.product_id` references that UUID;
account opening selects a real catalogue product and stores its UUID; existing accounts
are backfilled. The catalogue is persisted so the identity is stable across restarts.**

### 1. Canonical product UUID owned by product-catalog
Each product gains a stable `id: UUID` (the canonical identity), with the existing
`prod-NNN` kept as a human-facing `code` alias. UUIDs are deterministic (seeded as
fixed constants, or UUIDv5 of the code) so they survive reseeds and match across
environments. `GET /api/v1/products/{uuid}` resolves; `…/products/by-code/{code}`
stays for humans.

### 2. Catalogue gets a persistent store
The in-memory seed becomes a Postgres-backed store (per ADR-0009) seeded by a Flyway
migration carrying the canonical UUIDs — so a product's identity is durable and the
same id is served on every restart and pod.

### 3. account-service references the catalogue UUID
`accounts.product_id` *is* the catalogue product UUID. Account opening (onboarding)
resolves the chosen product against product-catalog (by code or type) and stores its
canonical UUID — never a locally-minted sentinel.

### 4. Backfill existing accounts
A one-off migration maps each existing `product_id` (sentinel) to the canonical
catalogue UUID for the equivalent product (current → CZK Current, savings → Savings,
etc.), so live accounts resolve immediately.

### 5. Consumers resolve through the unified id
The customer-edge (ADR-0109 supported currencies), fee display, statements, and
eligibility all look the product up by `account.product_id` against product-catalog —
no per-service mapping, no heuristic.

### Rollout (phased)
- **P1** product-catalog: canonical UUIDs + persistence (Flyway seed) + lookup by UUID.
- **P2** account-service: onboarding stores the catalogue UUID.
- **P3** backfill existing accounts' `product_id`.
- **P4** consumers join via the unified id (ADR-0109 add-currency lights up; fees etc.).

## Alternatives considered

- **A — Mapping table (sentinel UUID ↔ catalogue code).** A reconciliation table the
  edge consults. Rejected: permanent indirection that drifts; encodes the mismatch
  instead of removing it.
- **B — account-service owns product identity; catalogue mirrors it.** Rejected: the
  catalogue is the product authority (terms, fees, eligibility); identity must originate
  there.
- **C — Accounts reference products by string `code` (`prod-014`).** Rejected:
  `accounts.product_id` is a UUID and UUID is the platform's id convention; codes are a
  human alias, not the join key.
- **D — Leave it; add a curated currency fallback in the edge/app.** Rejected by
  product owner — a hardcoded fallback contradicts product-catalog being authoritative
  and masks the real gap.

## Consequences

**Positive**
- One product identity the whole platform joins on — pockets/supported currencies,
  fees, statements, eligibility all resolve from the account's product.
- Catalogue identity is durable (persisted), not reset on every pod restart.
- Removes the ADR-0109 "Add currency" blocker without a fallback hack.

**Negative**
- Touches product-catalog (persistence + UUIDs), account-service (onboarding + a
  backfill migration), and a coordinated deploy.
- Backfill must map legacy sentinel product_ids correctly (manual mapping table for the
  handful of seeded product types).

**Neutral**
- `prod-NNN` codes remain as human aliases; external references by code keep working.
- Persisting the catalogue is overdue regardless (its own code flagged it).

## Compliance impact

- **CNB / consumer protection:** an account can be authoritatively tied to its product
  terms, fees and eligibility — required for correct disclosures and suitability.
- **GDPR:** product identity is not personal data.
- **PCI DSS / PSD2 / DORA:** not directly applicable; improves traceability of the
  product governing each account.

## References

- ADR-0009 — Postgres per service (catalogue persistence)
- ADR-0109 — Customer-managed currency pockets (the consumer that surfaced the gap)
- product-catalog `ProductCatalogService.seed()` (in-memory seed, `prod-NNN` codes)
- account-service `accounts.product_id` (UUID) + onboarding account-open path

