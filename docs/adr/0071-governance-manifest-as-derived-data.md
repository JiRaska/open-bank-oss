---
date: 2026-06-07
decision-status: accepted
delivery-status: shipped
authors: [Jiří Raška]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [governance, admin-ui, docs]
summary: "The admin-UI governance manifest becomes derived data: each service declares curatorial facts in its own governance.yaml, a generator emits governance.json, and every surface iterates it instead of hand-coded lists."
---

# Governance manifest as derived data (per-service governance.yaml)

## Context

ADR-0029 (governance as code) set the rule, restated as non-negotiable #7 in the
contributor guide: **catalog & coverage data are derived, never hand-edited.** The
release/API axis already obeys it — `openbank-admin-ui/scripts/generate-catalog.mjs`
derives `catalog.json` and `service-graph.json` from `version.txt`, `openapi.yaml`
and `rules.yaml`, and the admin-UI serves them through `/api/catalog/*`.

One artefact never made the move: `openbank-admin-ui/src/lib/governance/manifest.ts`
— an **825-line hand-maintained** `GovernanceManifestEntry[]` (data domain, data
classification, retention, primary datastore, schema name, Flyway versions, and
upstream/downstream lineage). It is read by ~7 surfaces (service-map, BCP, health,
dashboard, governance API, finops). Being hand-edited, it has rotted exactly as the
rule warns:

- **Phantom entries:** `analytics-sink`, `api-gateway` (no `version.txt`, not modules).
- **Typo:** `sepa-instant-service` (the module is `sepa-instant`), which also makes
  `sepa-instant` look "missing".
- **Missing:** `admin-ui`, and the manifest lags new services (onboarding-service,
  customer-edge, statement-service) until someone remembers to hand-add them.

Downstream, every page that hard-codes a `SERVICES` list off the back of this drifts
too: BCP covers 27/35, the API catalog 27, health 28. A new service does **not** appear
until edited into multiple lists by hand.

The reason this one resisted derivation: most of its fields — `dataDomain`,
`dataClassification`, `retentionPolicy`, `dataLineageRole`, `lineage` — are **curatorial
facts not expressed anywhere in code**. You cannot derive what was never declared. The
fix is therefore two-part: give those facts a declarative home as-code, then derive.

## Decision

We will make the governance manifest **derived data**, mirroring the catalog pattern.

**1. Per-service `governance.yaml` is the declarative source.** Each released module gets
a `governance.yaml` at its root (next to `version.txt`), validated against
`openbank-libs/governance/governance.schema.json`. It carries only the **curatorial**
facts that cannot be derived: `dataDomain`, `primaryDatastore`, `schemaName`,
`dataLineageRole`, `dataClassification`, `retentionPolicy`, `evidenceExported`, and
`lineage` (upstream/downstream edges). It is reviewed *with the service*, in the service's
own hexagon (ADR-0002) — the team that owns the data owns its classification.

The schema is **compiled and enforced**, not merely cited: `generate-governance.mjs`
validates every `governance.yaml` against it and derives its required list and enums from
it, so each violation becomes a `gap` and fails the CI gate. It was advisory for its first
months — referenced only from a comment, with the generator re-implementing a subset of it
by hand — and the two drifted: four services shipped a bare `lineage:` key (YAML parses that
to `null`, the schema says `type: object`) and the gate stayed green. One source of truth,
read by the checker, is the fix.

> **Amended by ADR-0196.** `schemaName` named a Postgres schema that existed nowhere in the
> fleet — isolation is per-database (ADR-0009) and every service's tables live in `public`.
> It is replaced by `databaseName` (with an explicit `ownsNoDatabase: true` for modules that
> own none), `schemaLineage` by `databaseLineage`, and every claim the code can settle is now
> cross-checked by the generator instead of being taken on trust. The single source of truth
> for the schema itself moved from a hand-written `governance.schema.json` (compiled with
> ajv) to a Zod schema in `scripts/governance-schema.mjs`, from which `governance.schema.json`
> is now DERIVED — no separate JSON Schema author to keep in sync, and no new dependency
> (Zod was already an admin-UI dependency).

**2. Derivable fields are derived, never declared.** `flywayDeclaredVersion` comes from
`src/main/resources/db/migration/V*.sql` (max version); `apiVersion`/`apiTitle` and
`moneyPath` come from the existing catalog inputs. Declaring them in `governance.yaml`
would just reintroduce drift.

**3. A generator emits `governance.json`.** `generate-governance.mjs` (a sibling of
`generate-catalog.mjs`) joins each module's `governance.yaml` + derived fields into
`openbank-admin-ui/governance.json` (schema `openbank.governance/v1`), and reports `gaps`
for any service missing a `governance.yaml`. The admin-UI serves it via
`/api/catalog/governance` and **all surfaces iterate over it** — no hand-coded service
lists. `manifest.ts` keeps the TypeScript *types* and becomes a thin loader; its hardcoded
data is deleted.

**4. Runtime drift stays on the runtime path.** `flywayCurrentVersion`, `flywayDrift`, and
schema `driftStatus` require a live database and are **out of scope** for this static
artefact; they remain served by `/api/services/governance` and are merged in at view time.
The static artefact carries the *declared* posture; the runtime path carries the *actual*.

**5. CI gate, advisory → enforce.** CI regenerates `catalog.json` + `governance.json` and
diffs against the committed copies. Initially a **warning / PR comment** (advisory); once
the fleet has `governance.yaml` everywhere and drift is drained, it flips to **fail**
(enforce) — the same phased rollout as ADR-0034 (OPA advisory→enforce).

## Alternatives considered

- **Central `governance-data.yaml` for all 35 services** (next to `rules.yaml`). One file,
  fewer paths — but it is edited *away from* the service, so classification/retention drift
  from the code that owns them, and merge contention grows. It is `manifest.ts` reincarnated
  as YAML. Rejected: violates the per-service ownership ADR-0002/0029 intends.
- **Reuse the Docs-as-Service per-service schema (ADR-0019).** Natural home for per-service
  metadata, but Docs-as-Service is still a pilot; coupling this fix to its fleet rollout
  would stall both. Rejected for now — `governance.yaml` can later be folded into it without
  changing consumers (they read `governance.json`, not the source).
- **Keep `manifest.ts`, just fix the entries.** Restores accuracy for a day; the next new
  service re-drifts it. Rejected — treats the symptom, not non-negotiable #7.
- **Derive everything, declare nothing.** Impossible: classification/retention/lineage are
  not present in code. Rejected as infeasible.

## Consequences

**Positive**
- Non-negotiable #7 finally holds for the governance axis; a new service appears on every
  surface automatically once it has a `governance.yaml`.
- Drift becomes a CI failure, not a thing humans must notice.
- Data classification/retention are reviewed with the service that owns the data.

**Negative**
- Introduces a new per-service artefact (`governance.yaml` × ~35) — a one-time fleet sweep
  to seed, tracked as a governance issue (ADR-0052).
- Two-source read (static declared + runtime actual) is slightly more complex than one array;
  mitigated by merging in the loader.

**Neutral**
- `manifest.ts` shrinks to types + loader; consumers keep the `GovernanceManifestEntry` shape.
- Generator and CI gate follow patterns that already exist (`generate-catalog.mjs`).

## Compliance impact

- PCI DSS: not applicable directly (no cardholder data in the manifest).
- DORA:    Art. 8 (ICT asset/risk register) — an accurate, derived data-asset inventory with
           classification/retention is materially better evidence than a hand-edited list.
- GDPR:    Art. 30 (records of processing), Art. 5(1)(e) (storage limitation) — `dataClassification`
           + `retentionPolicy` declared per service and kept honest by the CI gate.
- PSD2:    not applicable.
- CNB:     supports outsourcing/ICT register expectations via a trustworthy asset inventory.

## References

- ADR-0002 — hexagonal architecture per service
- ADR-0019 — Docs-as-Service (separate per-service metadata track)
- ADR-0029 — governance as code (non-negotiable #7: derived, never hand-edited)
- ADR-0034 — OPA unified authz (advisory → enforce rollout precedent)
- ADR-0048 — two version axes (release vs API contract)
- `openbank-admin-ui/scripts/generate-catalog.mjs`, `src/lib/governance/manifest.ts`
