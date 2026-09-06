---
date: 2026-09-06
decision-status: accepted
delivery-status: shipped
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [resilience, payments]
summary: "SDD's two creation POSTs (mandates, collections/authorise) stay free of a synthetic Idempotency-Key: the scheme's natural keys already make a retry a no-op, and the #8351 gate records them as ADR-linked exceptions."
---

# ADR-0285 — SDD creation POSTs are idempotent on scheme natural keys, not synthetic keys

## Context

The #8351 idempotency inventory (M2 exit criterion) requires every money-path POST to
either enforce a declared idempotency handle (`Idempotency-Key` / `X-Request-ID` header or
a required `idempotencyKey` body property) or carry an ADR-linked exception. Two
sdd-service creation POSTs declared no handle:

- `POST /api/v1/sdd/mandates` — register a debtor mandate
- `POST /api/v1/sdd/collections/authorise` — authorise an inbound collection

## Decision

Both endpoints keep their natural-key idempotency and take the ADR-linked exception; no
synthetic key is introduced.

- **Mandates** dedup on `(creditorIdentifier, UMR)`: the SDD scheme makes the UMR unique
  per creditor, `SddMandateService.register` returns the stored mandate for a repeated
  pair, and the replay is covered by
  `registering an existing reference is idempotent and does not re-save`.
- **Collection authorisation** is unique per `(mandateId, UMR, dueDate)` — the same
  triple the debit consumer books under as `so-sdd-{mandateId}-{umr}-{dueDate}`, which is
  both the outbound `Idempotency-Key` toward transaction-service and the consumer's dedup
  key. This PR additionally closes the last observable replay effect: a retried authorise
  for the SAME `dueDate` previously re-stamped `lastCollectionDate` and re-emitted
  `sdd.collection.authorised.v1` (deduped downstream, but emitted); the use case now
  short-circuits on `lastCollectionDate == dueDate` and replays the decision with no save
  and no outbox row.

## Why not a synthetic key

- The natural keys are mandated by the scheme itself (UMR per creditor; dueDate per
  collection). A synthetic header would add a second, weaker dedup domain without removing
  the first — two sources of truth for "the same request".
- Requiring a new header is a breaking change for every existing caller (the edge and the
  bank's own backoffice) to buy a guarantee the code already provides.
- The measured failure mode a synthetic key prevents (retry double-creates) is already
  impossible: mandates by the `(CID, UMR)` lookup, collections by the deterministic
  downstream booking key — and, after this PR, by the use-case replay guard as well.

## Consequences

- The two endpoints stay in `idempotency-coverage-baseline.txt` with reasons pointing at
  this ADR; the shrink-only gate tolerates them as documented exceptions.
- openapi.yaml (1.1.1) documents the natural-key semantics on both operations so a new
  integrator does not invent a key the service would silently ignore.
- Any FUTURE sdd creation POST without a scheme-natural dedup domain does NOT inherit this
  exception — it needs the synthetic handle like the rest of the fleet.

## Alternatives considered

- **Synthetic `Idempotency-Key` header on both POSTs** — rejected: both operations already carry a
  durable natural key the ecosystem understands (the UMR is the SEPA mandate's identity;
  (mandateId, UMR, dueDate) is the collection's), and the downstream debit consumer already dedups
  on it. A second, synthetic key would give one fact two identities and force every caller to
  invent and store a key it already has.
- **Do nothing and keep the entries in the coverage baseline** — rejected: the baseline exists to
  shrink; an exception that is not written down as a decision is indistinguishable from a gap.

## Compliance impact

Not applicable to any new obligation — the decision STRENGTHENS an existing control: SEPA
rulebook idempotency for collections is exactly "one collection, one debit", which the natural
key now guarantees at the authorise boundary as well as at the debit consumer.
