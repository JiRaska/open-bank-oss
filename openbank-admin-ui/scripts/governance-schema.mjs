// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// THE single definition of a per-service governance.yaml (ADR-0071 / ADR-0196).
//
// Why a Zod schema and not a hand-written JSON Schema: the rules used to exist TWICE —
// as JSON Schema in openbank-libs/governance/governance.schema.json (read by humans and
// editors, enforced by nobody) and as hand-mirrored `if` statements in
// generate-governance.mjs (enforced by CI). Two copies of one rule drift, and the drift
// is invisible until a bad governance.yaml passes the copy that matters. Here the rules
// live once; the JSON Schema file is DERIVED from this module (`jsonSchema()`, checked
// for drift by src/test/generate-governance.test.ts) and the CI gate validates against
// the same object. Zod is already an admin-ui dependency, so this costs no new package.
//
// Shape only. Claims that can be checked against the CODE (does this module actually own
// Flyway migrations? which database do its manifests point at?) are cross-checked in
// generate-governance.mjs — a declaration that contradicts the tree is a gap there.

import { z } from 'zod'

export const DATA_DOMAINS = ['core', 'payments', 'compliance', 'identity', 'open-banking', 'platform']
export const LINEAGE_ROLES = ['producer', 'consumer', 'both', 'internal']
export const CLASSIFICATIONS = ['public', 'internal', 'confidential', 'restricted', 'unknown']

// Closed on purpose: every value here is one a module in this repo demonstrably uses.
// Adding a datastore is a governance edit, not a free-text field — free text is how the
// fleet ended up with `none`, `none (stateless)`, `None (stateless)` and `"none (stateless)"`
// all meaning the same thing while `Cassandra` meant nothing at all.
export const DATASTORES = ['PostgreSQL', 'Redis', 'none']

// Placeholders that used to be written into schemaName to satisfy a gate that demanded a
// value from modules that own no database. A declaration must be a fact or absent.
export const PLACEHOLDERS = ['n/a', 'na', 'none', 'null', 'nil', '-', '--', 'tbd', 'todo', 'unknown', '?', 'not applicable']

/** True for a value that only looks like a declaration ('n/a', 'none', 'TBD', …). */
export function isPlaceholder(v) {
  return typeof v === 'string' && PLACEHOLDERS.includes(v.trim().toLowerCase())
}

const databaseName = z
  .string()
  .min(1)
  .refine(v => !isPlaceholder(v), {
    message: "must name a real database, not a placeholder — omit it and declare 'ownsNoDatabase: true' instead",
  })
  .refine(v => /^[a-z][a-z0-9_]*$/.test(v.trim()), {
    message: 'must be a Postgres database identifier (lowercase, digits, underscore)',
  })
  .describe('The database this module OWNS, e.g. `openbank_ledger`. Cross-checked against the datasource URL in application.yaml / the GitOps manifest. Omit it and declare `ownsNoDatabase: true` if the module owns none.')

const lineageNode = z.strictObject({
  serviceName: z.string().min(1).describe("Counterparty service short name, e.g. 'transaction-service'."),
  relationType: z.enum(['api', 'topic', 'datastore', 'unknown']),
  description: z.string().optional(),
})

// Fields every module declares, stateful or not.
const common = {
  dataDomain: z.enum(DATA_DOMAINS).describe('Business data domain the service belongs to.'),
  primaryDatastore: z.enum(DATASTORES).describe('Primary store the module USES. `none` means it talks to no datastore at all — a module that owns no database but caches in Redis declares `Redis` + `ownsNoDatabase: true`.'),
  dataLineageRole: z.enum(LINEAGE_ROLES).describe('Role in cross-service data lineage.'),
  dataClassification: z.enum(CLASSIFICATIONS).describe('Highest sensitivity of data the service handles (GDPR Art. 30 / DORA Art. 8).'),
  retentionPolicy: z.string().min(1).describe("Human-readable retention statement, e.g. '7 years', 'transient'. GDPR Art. 5(1)(e)."),
  evidenceExported: z.boolean().optional().describe('Whether this service exports signed compliance evidence (ADR-0029/0030).'),
  lineage: z
    .strictObject({
      upstream: z.array(lineageNode).optional(),
      downstream: z.array(lineageNode).optional(),
    })
    .optional()
    .describe('Declared upstream/downstream service relationships. Runtime interface discovery is separate.'),
  databaseLineage: z
    .strictObject({
      // Empty lists are allowed and meaningful: `dependentDatabases: []` asserts "this module
      // reads no other module's database", which is a stronger statement than omitting the key.
      ownedDatabases: z.array(databaseName).optional(),
      dependentDatabases: z.array(databaseName).optional(),
    })
    .optional()
    .describe('Declared database ownership plus the databases whose data this module DEPENDS ON — whether it reads them directly or derives its own records from their events. Names are cross-checked against the fleet by the generator; it does not assert direct SQL access. driftStatus is RUNTIME and is added by /api/services/governance, not here.'),
}

// Two variants rather than one object plus a refinement: the ownership rule then holds in
// the DERIVED JSON Schema too (it emits an anyOf), instead of being a constraint only this
// file's code knows about. `ownsNoDatabase` is an ASSERTION — a module that owns no database
// must say so. Inferring it from an absent databaseName would make a forgotten field and a
// module that genuinely owns nothing indistinguishable, which is the failure this whole gate
// exists to prevent.
//
// The flag says `ownsNoDatabase`, not `stateless`, because those are different claims:
// customer-edge owns no database yet keeps durable passkey credentials in Redis, and copilot
// keeps conversations there. Calling either "stateless" would be the same kind of comfortable
// inaccuracy this ADR is removing.
//
// Each variant simply omits the other's key; `strictObject` then rejects it as unknown, so
// "a module with a database must not also claim it has none" needs no extra rule and survives
// into the derived JSON Schema (which cannot express a `z.undefined()` field at all).
export const STATEFUL = z
  .strictObject({ ...common, databaseName })
  .describe('A module that owns a database: it declares databaseName and must NOT declare ownsNoDatabase.')

export const OWNS_NO_DATABASE = z
  .strictObject({ ...common, ownsNoDatabase: z.literal(true) })
  .describe('A module that owns no database: it asserts ownsNoDatabase: true and declares no databaseName.')

export const GOVERNANCE = z.union([STATEFUL, OWNS_NO_DATABASE])

/**
 * Validate one parsed governance.yaml. Returns an array of human-readable problems
 * (empty = valid). Messages name the field and the fix, because they are what CI prints.
 *
 * Dispatches on the `ownsNoDatabase` key rather than handing the union to Zod, so a module
 * with a database gets "missing databaseName" instead of the union's unreadable both-branches-failed
 * report.
 */
export function validateDeclaration(decl) {
  if (decl == null || typeof decl !== 'object' || Array.isArray(decl)) {
    return ['governance.yaml must be a YAML mapping']
  }

  // Guard the discriminator itself: `ownsNoDatabase: false` is not "owns one", it is a
  // misunderstanding of an assertion flag, and `ownsNoDatabase: "true"` is a YAML quoting slip.
  if ('ownsNoDatabase' in decl && decl.ownsNoDatabase !== true) {
    return [`ownsNoDatabase must be 'true' or omitted entirely, got '${JSON.stringify(decl.ownsNoDatabase)}' — it is an assertion, not a tri-state`]
  }
  const ownsNoDatabase = decl.ownsNoDatabase === true

  // Caught before the schema so the message names the contradiction rather than reporting
  // an unexpected key against the owns-nothing variant.
  if (ownsNoDatabase && decl.databaseName != null) {
    return [`declares ownsNoDatabase: true but also databaseName='${decl.databaseName}' — a module that owns no database has no database name`]
  }
  if (!ownsNoDatabase && decl.databaseName == null) {
    return ["missing databaseName (add 'ownsNoDatabase: true' instead if the module owns no database)"]
  }

  const result = (ownsNoDatabase ? OWNS_NO_DATABASE : STATEFUL).safeParse(decl)
  if (result.success) return []
  return result.error.issues.map(i => {
    const at = i.path.length ? i.path.join('.') : '(root)'
    return `${at}: ${i.message}`
  })
}

/** The DERIVED JSON Schema. openbank-libs/governance/governance.schema.json is this, on disk. */
export function jsonSchema() {
  const emitted = z.toJSONSchema(GOVERNANCE, { target: 'draft-2020-12', io: 'input' })
  return {
    $schema: 'https://json-schema.org/draft/2020-12/schema',
    $id: 'https://open-bank.tech/governance/governance.schema.json',
    title: 'openbank per-service governance.yaml',
    description:
      'Declarative, curatorial governance facts for one released module (ADR-0071/ADR-0196). Lives at the module root next to version.txt and is reviewed WITH the service. Carries ONLY what cannot be derived from code. Derived fields (flywayDeclaredVersion, apiVersion, moneyPath) and runtime fields (flywayCurrentVersion, flywayDrift, driftStatus) MUST NOT appear here — the generator and the runtime path own those respectively.',
    $comment:
      'DERIVED FILE — do not hand-edit. Generated from openbank-admin-ui/scripts/governance-schema.mjs (the single source of these rules); regenerate with `node scripts/generate-governance.mjs --emit-schema`. src/test/generate-governance.test.ts fails if this file and that module disagree.',
    ...emitted,
  }
}
