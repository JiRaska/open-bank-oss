---
date: 2026-08-12
decision-status: accepted
delivery-status: shipped
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [product-catalog, api-contract, security]
summary: "Industry packs use immutable JSON Schema 2020-12 documents from trusted classpath manifests, with local references, deterministic hashes and bounded validation."
---

# ADR-0258 — Trusted JSON Schema profile for industry packs

## Context

ADR-0257 makes product types extensible without adding each industry's fields to the catalog kernel.
That boundary stores and evaluates schemas, so changing dialect, reference resolution or hashing
later would reinterpret already-published commercial terms. General-purpose JSON Schema also permits
remote references and resource use that are inappropriate on a request path.

## Decision

Catalog packs use JSON Schema Draft 2020-12. Each immutable schema is identified by an exact
reverse-DNS id and positive integer version, declares the 2020-12 `$schema` dialect and a matching
URN `$id`, and is registered from a trusted classpath pack manifest. Re-registering the same
`(id, version)` with different bytes is a conflict.

Schemas are closed by default (`additionalProperties: false`). References may target local `$defs`
inside the same document only; network and filesystem references are rejected. Executable extension
keywords, coercion and default injection are forbidden. Documents and instances are bounded in byte
size and nesting depth, and a response returns at most 100 deterministically ordered violations.

The stored SHA-256 is computed over canonical JSON: UTF-8, object keys sorted lexicographically,
insignificant whitespace removed and JSON numbers rendered without binary floating-point conversion.
The exact schema bytes and hash are stored with every revision reference. Domain code owns a typed,
framework-free JSON value algebra; Jackson and the validator library remain infrastructure adapters.

## Consequences

- Banking and insurance packs evolve independently without kernel enums or nullable industry blocks.
- Published revisions remain reproducible even after newer schema versions are installed.
- Runtime schema upload, remote references and arbitrary validation code are not supported.
- A future dialect or trust-distribution change requires a superseding ADR and explicit migration.

## Alternatives considered

- **Unconstrained JSON documents.** Rejected because invalid money, required fields and unknown
  attributes would fail only in downstream systems.
- **Remote schema registry and references.** Rejected for the first standalone boundary because
  availability and supply-chain behavior would enter every validation request.
- **Kotlin classes for every industry.** Rejected because each new pack would require a kernel build.

## Compliance impact

The profile prevents copied personal records and executable scripts from becoming catalog content,
but pack owners remain responsible for their attribute classification. Deterministic validation and
hashes support DORA recovery evidence and reconstruction of approved product terms.

## References

- ADR-0005 — design-first APIs
- ADR-0048 — API contract versioning
- ADR-0152 — single tenancy per deployment
- ADR-0257 — industry-neutral product catalog kernel
