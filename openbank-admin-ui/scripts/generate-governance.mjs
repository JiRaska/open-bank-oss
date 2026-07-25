// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Generate the code-derived governance manifest (ADR-0071, sibling of
// generate-catalog.mjs). Joins each module's DECLARED governance.yaml (curatorial
// facts) with DERIVED fields (Flyway declared version from db/migration/V*.sql)
// into governance.json (schema openbank.governance/v1). This REPLACES the hand-
// edited src/lib/governance/manifest.ts (CLAUDE.md rule #7 — derived, not hand-edited).
//
// Out of scope (lives elsewhere, by design):
//   - apiVersion / moneyPath        → catalog.json (generate-catalog.mjs)
//   - flywayCurrentVersion / drift  → runtime /api/services/governance (live DB)
//
// Honest by construction: a module without governance.yaml is a GAP, never faked.
//
// Usage: node scripts/generate-governance.mjs [--repo <path>] [--out <file>]

import { readdirSync, statSync, readFileSync, writeFileSync, existsSync } from 'fs'
import path from 'path'
import { fileURLToPath, pathToFileURL } from 'url'
import { parse as parseYaml } from 'yaml'
import Ajv2020 from 'ajv/dist/2020.js'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

// governance.schema.json is the SINGLE SOURCE OF TRUTH for a governance.yaml (ADR-0071).
// It used to be referenced only from a comment, with this file re-implementing a subset of
// it by hand — so the two drifted and four services shipped a bare `lineage:` key (parses to
// null, `type: object` in the schema) that the gate accepted for months. Now the schema is
// both COMPILED (every file validated against it) and READ (the required list and the enums
// below are derived from it, never retyped).
//
// Resolved relative to THIS script, not to the scanned --repo: the schema ships with the
// generator, while --repo may be any checkout (the unit tests point it at a tmpdir).
const SCHEMA_PATH = path.resolve(__dirname, '..', '..', 'openbank-libs', 'governance', 'governance.schema.json')
const SCHEMA = JSON.parse(readFileSync(SCHEMA_PATH, 'utf-8'))

// strict mode ON deliberately: ajv then THROWS on a keyword it does not understand, so a
// future schema edit can never be silently ignored by the validator that is supposed to
// enforce it (the "the check can't express the failure, so it reports success" trap).
// strictRequired is the one lever turned off: it is a heuristic that rejects the schema's
// `then: { not: { required: ["schemaName"] } }` idiom (schemaName is declared on the parent,
// not inside the negated subschema) — it guards typos in `required`, not keyword support.
const validateSchema = new Ajv2020({ allErrors: true, strict: true, strictRequired: false }).compile(SCHEMA)

const REQUIRED = SCHEMA.required

// schemaName is NOT in REQUIRED because a stateless module legitimately owns none.
// It is validated conditionally below: statelessness must be ASSERTED (`stateless: true`),
// never inferred from an absent schemaName — otherwise a forgotten field and a service
// that owns no schema are indistinguishable, and the gate silently stops meaning anything.

// Enum constraints come FROM governance.schema.json (every top-level property carrying an
// `enum`). The friendly, remedy-carrying message below is why these are checked here at all
// — ajv already rejects the same values, just less legibly.
const ENUMS = Object.fromEntries(
  Object.entries(SCHEMA.properties)
    .filter(([, v]) => Array.isArray(v.enum))
    .map(([k, v]) => [k, v.enum]),
)

function readText(p) {
  try { return readFileSync(p, 'utf-8') } catch { return null }
}

// Derive the highest declared Flyway version from V<version>__*.sql migration files.
// Compares dotted versions numerically (V1.4 < V2.1 < V8). Null if no migrations.
function flywayDeclaredVersion(dir) {
  const md = path.join(dir, 'src', 'main', 'resources', 'db', 'migration')
  if (!existsSync(md)) return null
  const versions = readdirSync(md)
    .map(f => /^V([0-9]+(?:[._][0-9]+)*)__/.exec(f)?.[1])
    .filter(Boolean)
    .map(v => v.replace(/_/g, '.'))
  if (!versions.length) return null
  const cmp = (a, b) => {
    const pa = a.split('.').map(Number), pb = b.split('.').map(Number)
    for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
      const d = (pa[i] ?? 0) - (pb[i] ?? 0)
      if (d) return d
    }
    return 0
  }
  return 'V' + versions.sort(cmp).at(-1)
}

// Exported (not just run as a CLI) so the gate's rules are unit-testable without
// spawning a subprocess per case — see src/test/generate-governance.test.ts.
export function buildManifest(REPO) {
  // A module is a released component iff it has version.txt (admin-ui keeps it too).
  const modules = readdirSync(REPO)
    .filter(n => n.startsWith('openbank-'))
    .filter(n => { try { return statSync(path.join(REPO, n)).isDirectory() } catch { return false } })
    .filter(n => existsSync(path.join(REPO, n, 'version.txt')))
    .sort()

  const services = []
  const gaps = []
  for (const name of modules) {
    const dir = path.join(REPO, name)
    const short = name.replace(/^openbank-/, '')
    const raw = readText(path.join(dir, 'governance.yaml'))
    if (raw == null) { gaps.push(`${name}: missing governance.yaml`); continue }

    let decl
    try { decl = parseYaml(raw) } catch (e) { gaps.push(`${name}: unparseable governance.yaml (${e.message})`); continue }
    // Top-level keys already reported by a friendly rule below — the schema would flag the
    // same thing in ajv's terser wording, and one defect must produce exactly one gap.
    const handled = new Set()

    const missing = REQUIRED.filter(k => decl?.[k] == null || decl[k] === '')
    if (missing.length) {
      gaps.push(`${name}: governance.yaml missing ${missing.join(', ')}`)
      for (const k of missing) handled.add(k)
    }

    // Conditional schemaName rule (mirrors governance.schema.json's if/then/else).
    const stateless = decl?.stateless === true
    const hasSchemaName = decl?.schemaName != null && decl.schemaName !== ''
    if (decl?.stateless != null && decl.stateless !== true) {
      gaps.push(`${name}: stateless must be 'true' or omitted, got '${decl.stateless}'`)
      handled.add('stateless')
    }
    if (stateless && hasSchemaName) {
      gaps.push(`${name}: declares stateless: true but also schemaName='${decl.schemaName}' — a stateless module owns no schema`)
    }
    if (!stateless && !hasSchemaName) {
      gaps.push(`${name}: governance.yaml missing schemaName (add 'stateless: true' instead if the module owns no DB schema)`)
    }

    for (const [k, allowed] of Object.entries(ENUMS)) {
      if (decl?.[k] != null && !allowed.includes(decl[k])) {
        gaps.push(`${name}: ${k}='${decl[k]}' not in [${allowed.join(', ')}]`)
        handled.add(k)
      }
    }

    // Everything the friendly rules above do NOT cover — wrong types (a bare `lineage:` key
    // parses to null, not an object), unknown keys (additionalProperties: false), a malformed
    // lineage node, an empty string where minLength: 1 is required. This is the layer that
    // makes governance.schema.json enforced rather than advisory.
    if (!validateSchema(decl)) {
      for (const e of validateSchema.errors) {
        // The conditional schemaName/stateless rule lives in the schema's `allOf` and is
        // reported above with the remedy spelled out; ajv's "must NOT be valid" is strictly
        // worse. Skip that branch only.
        if (e.schemaPath.startsWith('#/allOf')) continue
        const concern = e.instancePath ? e.instancePath.split('/')[1] : e.params?.missingProperty
        if (concern && handled.has(concern)) continue
        gaps.push(`${name}: governance.yaml violates governance.schema.json — ${e.instancePath || '(root)'} ${e.message}`)
      }
    }

    services.push({
      serviceName: short,
      dataDomain: decl.dataDomain ?? null,
      primaryDatastore: decl.primaryDatastore ?? null,
      schemaName: decl.schemaName ?? null,
      stateless: stateless || undefined,
      dataLineageRole: decl.dataLineageRole ?? null,
      dataClassification: decl.dataClassification ?? 'unknown',
      retentionPolicy: decl.retentionPolicy ?? 'unknown',
      evidenceExported: typeof decl.evidenceExported === 'boolean' ? decl.evidenceExported : undefined,
      flywayDeclaredVersion: flywayDeclaredVersion(dir),
      lineage: decl.lineage ?? undefined,
      schemaLineage: decl.schemaLineage ?? undefined,
    })
  }

  const totals = {
    modules: services.length,
    withLineage: services.filter(s => s.lineage).length,
    evidenceExported: services.filter(s => s.evidenceExported).length,
    withGaps: gaps.length,
  }

  return {
    schema: 'openbank.governance/v1',
    generator: 'generate-governance.mjs',
    source: 'code-derived (governance.yaml + db/migration) — ADR-0071 / ADR-0029 D3',
    collectedAt: null,
    totals,
    // The gap LIST, not just totals.withGaps — so the CI gate can name the offending
    // modules from the artifact instead of guessing at a shape that never existed
    // (issue #2165: the old reporter read `.modules`, which is not a field here, and
    // therefore threw on every single failure without ever printing a gap).
    gaps,
    services,
  }
}

function main() {
  const args = process.argv.slice(2)
  const getArg = (flag, dflt) => {
    const i = args.indexOf(flag)
    return i >= 0 && args[i + 1] ? args[i + 1] : dflt
  }
  const REPO = path.resolve(getArg('--repo', path.resolve(__dirname, '..', '..')))
  const OUT = path.resolve(getArg('--out', path.resolve(__dirname, '..', 'governance.json')))

  const manifest = buildManifest(REPO)
  const { totals, gaps } = manifest
  writeFileSync(OUT, JSON.stringify(manifest, null, 2) + '\n')
  console.log(`[generate-governance] ${totals.modules} modules (${totals.withLineage} with lineage, ${totals.withGaps} gaps) → ${OUT}`)
  if (gaps.length) {
    console.log('[generate-governance] gaps:')
    for (const g of gaps) console.log(`  - ${g}`)
  }
}

// CLI entrypoint only — importing this module (tests) must not write or log.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main()
