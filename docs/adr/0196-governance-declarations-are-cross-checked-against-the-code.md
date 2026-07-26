---
date: 2026-07-25
decision-status: accepted
delivery-status: shipped
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [governance, database, compliance, ci]
summary: "governance.yaml declares databaseName (the database a module owns) instead of the fictional schemaName, and every claim the code can settle — ownership, datastore, lineage, retention — is cross-checked by the generator."
---

# ADR-0196 — Governance declarations are cross-checked against the code

## Context

ADR-0071 made the governance manifest derived data: each module declares curatorial facts in
its own `governance.yaml`, a generator joins them with derived Flyway versions, and the
admin-UI iterates the result. The declarative half was never verified against anything, and
by 2026-07-25 the whole fleet had drifted from reality without a single red build:

- **`schemaName` named a schema that does not exist — in all 51 modules that declared one.**
  No migration in the repo issues `CREATE SCHEMA` or a schema-qualified `CREATE TABLE`, and
  no `application.yaml` sets `default-schema`, `flyway.schemas` or `search_path`. Every
  service's tables live in `public` of its **own database**; isolation is per-database
  (ADR-0009, one CNPG cluster per service), never per-schema. The field described a topology
  the platform does not have.
- **Eight modules named the wrong datastore.** `audit-service` declared Cassandra with no
  Cassandra anywhere in its source and eight Postgres migrations; `agent-service`,
  `balance-service` and `fx-service` declared Redis as primary while owning a Postgres
  database; `copilot-service` declared PostgreSQL with no datasource at all; `finops-agent`
  and `psd2-service` declared `none (stateless)` while owning Flyway migrations;
  `customer-edge` declared `none` while running a 30-day Redis onboarding store. Two of these
  were already documented as stale in the services' own docs and nothing acted on it.
- **The gate demanded the fiction.** `schemaName` was unconditionally required, so a module
  that genuinely owns nothing had to write `schemaName: n/a` to go green — six did. Issue
  #2165 then made the opposite failure visible: two truly stateless services (ap2, mcp)
  omitted it, the gate went red on `main`, and its own gap reporter crashed on a field the
  manifest never had, so it had never once printed which module was wrong.

The pattern is one thing, not three: **a curatorial field that nothing verifies decays into
fiction, and the decay is invisible precisely because the gate is green.** The manifest is
the platform's GDPR Art. 30 record of where personal data lives — a green gate over a wrong
answer is worse than no gate.

## Decision

**1. `databaseName` replaces `schemaName`.** Each module declares the database it **owns**
(`openbank_ledger`, …) — the thing that actually exists and that the deployment manifests
name. A module that owns none declares `ownsNoDatabase: true` and no `databaseName`; the two are
mutually exclusive. `schemaLineage.{ownedSchemas,dependentSchemas}` becomes
`databaseLineage.{ownedDatabases,dependentDatabases}`, carrying real database names.

The flag is `ownsNoDatabase`, not `stateless`, because they are different claims and only one
of them is true: `customer-edge` owns no database yet keeps **durable, TTL-less passkey
credentials** in Redis, and `copilot-service` keeps conversations there. Calling either
"stateless" would be a smaller version of the same comfortable inaccuracy this ADR removes.

**2. Every declaration the code can settle is cross-checked at generation time.**
`generate-governance.mjs` derives the evidence and fails the gate on a contradiction:

| Declared | Checked against |
|---|---|
| `ownsNoDatabase: true` | presence of `src/main/resources/db/migration/V*.sql` |
| `databaseName` | the Postgres URL in `application.yaml` or the module's GitOps manifest (`%test`/`%it` profile databases excluded) |
| `primaryDatastore` | migrations, datasource URL, `redis:` config / `QUARKUS_REDIS_HOSTS`, or a `pg` client dependency |
| `databaseLineage.ownedDatabases` | the module's own `databaseName` |
| `databaseLineage.dependentDatabases` | the set of databases the fleet actually declares (a dependency edge, not a claim of direct SQL access — audit-service derives its records from other services' events) |
| `retentionPolicy` | must not be a placeholder when the module stores anything (GDPR Art. 5(1)(e)) |

Where the tree cannot settle a claim, the manifest says so per row
(`databaseNameEvidence: 'derived' | 'declared-only'`) and counts it in
`totals.unverifiedDatabaseNames`, rather than presenting a claim as a fact.

**3. The schema has one source.** The rules live in
`openbank-admin-ui/scripts/governance-schema.mjs` (Zod — already an admin-UI dependency, so
no new package); `openbank-libs/governance/governance.schema.json` is **derived** from it via
`--emit-schema` and a unit test fails on drift. Previously the same rules existed twice — as
hand-written JSON Schema that nothing enforced and as hand-mirrored `if` statements in the
generator — which is how the enforced copy and the documented copy came to disagree.

**4. `primaryDatastore` is a closed enum** (`PostgreSQL`, `Redis`, `none`). Free text is how
the fleet ended up with `none`, `none (stateless)`, `None (stateless)` and `"none (stateless)"`
all meaning the same thing while `Cassandra` meant nothing at all.

**5. Every rule has a test that makes it fire.** The gate's failure path is the only path its
defects have ever lived on; a rule with no failing fixture is indistinguishable from no rule.

## Alternatives considered

- **Keep `schemaName`, document it as a "logical" name.** Cheapest, and it keeps the field
  stable for consumers. Rejected: a logical name that maps to nothing is unverifiable by
  construction, so the gate could never tell a correct value from an invented one — which is
  exactly the state this ADR exists to end.
- **Introduce real Postgres schemas so `schemaName` becomes true.** Rejected: it inverts the
  cost. Per-service databases (ADR-0009) already give stronger isolation than schemas; a
  fleet-wide migration to schema-qualified tables would be a large, risky change made solely
  to justify a metadata field.
- **Validate `governance.yaml` with a JSON Schema validator (ajv) against the existing file.**
  Rejected in favour of Zod-as-source: ajv adds a dependency to a banking repo for something
  Zod already there does, gives worse messages than the hand-written ones CI prints, and
  leaves the JSON Schema hand-maintained — the duplication that caused the drift.
- **Fix the eight wrong declarations and stop there.** Rejected: it corrects the instances and
  leaves the mechanism that produced them, which is the actual defect.

## Consequences

**Positive**
- A wrong declaration now fails the build with the contradiction and the fix named, instead of
  sitting green for months.
- The GDPR Art. 30 record names a database an auditor can actually connect to.
- The JSON Schema and the enforced rules cannot disagree; the test fails if they do.

**Negative**
- A one-off breaking change to the `governance.yaml` vocabulary: every module and every
  consumer of `schemaName` / `schemaLineage` had to be updated in one PR.
- The closed `primaryDatastore` enum means adding a datastore requires a schema change. This
  is intended, and it is friction.

**Neutral**
- Evidence derivation is textual (datasource URLs, `redis:` keys). A module configured
  entirely outside this repo is reported as `declared-only` rather than being failed — honest,
  but not proof.
- Cassandra left the enum because nothing uses it; re-adding it is a one-line change.

## Compliance impact

- PCI DSS: not applicable — no cardholder data is described by this change.
- DORA:    supports the ICT asset/data inventory this manifest feeds; no specific article is
           claimed here.
- GDPR:    Art. 30 (record of processing) and Art. 5(1)(e) (storage limitation) — the manifest
           is the fleet's machine-readable record of which store holds what and for how long,
           and both fields are now verified rather than asserted.
- PSD2:    not applicable — no change to payment interfaces.
- CNB:     not applicable — no reporting obligation is affected.

## References

- ADR-0071 — governance manifest as derived data (this ADR keeps its mechanism, replaces one
  field and adds verification).
- ADR-0009 — one CNPG Postgres cluster per service (why isolation is per-database).
- Issue #2165 — the gate red on `main` plus its never-working gap reporter.
- `openbank-admin-ui/scripts/governance-schema.mjs`, `scripts/generate-governance.mjs`,
  `src/test/generate-governance.test.ts`.
