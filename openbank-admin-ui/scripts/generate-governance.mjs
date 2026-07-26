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
// Honest by construction, in two layers (ADR-0196):
//   1. SHAPE — validated against scripts/governance-schema.mjs, the single definition of
//      a governance.yaml (openbank-libs/governance/governance.schema.json is DERIVED from
//      it). A module without governance.yaml is a GAP, never faked.
//   2. TRUTH — every declaration that CAN be checked against the tree IS checked here:
//      Flyway migrations vs `ownsNoDatabase`, the declared databaseName vs the datasource URL
//      in application.yaml / the GitOps manifest, the declared datastore vs the wiring
//      that exists. A curatorial field nobody verifies decays into fiction — this fleet
//      shipped 51 governance.yaml naming a `<x>_schema` that existed in no migration and
//      no config, and eight modules naming the wrong datastore outright, all while the
//      gate was green.
//
// Usage: node scripts/generate-governance.mjs [--repo <path>] [--out <file>] [--emit-schema]

import { readdirSync, statSync, readFileSync, writeFileSync, existsSync } from 'fs'
import path from 'path'
import { fileURLToPath, pathToFileURL } from 'url'
import { parse as parseYaml } from 'yaml'
import { validateDeclaration, jsonSchema, isPlaceholder } from './governance-schema.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

const SCHEMA_FILE = path.join('openbank-libs', 'governance', 'governance.schema.json')

function readText(p) {
  try { return readFileSync(p, 'utf-8') } catch { return null }
}

function migrationFiles(dir) {
  const md = path.join(dir, 'src', 'main', 'resources', 'db', 'migration')
  if (!existsSync(md)) return []
  try { return readdirSync(md).filter(f => /^V.*\.sql$/.test(f)) } catch { return [] }
}

// Derive the highest declared Flyway version from V<version>__*.sql migration files.
// Compares dotted versions numerically (V1.4 < V2.1 < V8). Null if no migrations.
function flywayDeclaredVersion(dir) {
  const versions = migrationFiles(dir)
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

// Every *.yaml under the module's GitOps component directory, concatenated. The deployment
// manifest is where a service with no application.yaml datasource block (psd2-service,
// security-scanner: connection injected as QUARKUS_DATASOURCE_* env) states which database
// it actually connects to, so skipping it would leave exactly those modules unverifiable —
// the two that most need verifying.
function gitopsText(REPO, short) {
  for (const r of [short, short.replace(/-service$/, '')]) {
    const dir = path.join(REPO, 'openbank-infra', 'gitops', 'components', r)
    if (!existsSync(dir)) continue
    try {
      return readdirSync(dir)
        .filter(f => f.endsWith('.yaml') || f.endsWith('.yml'))
        .map(f => readText(path.join(dir, f)) ?? '')
        .join('\n')
    } catch { /* unreadable component dir → treated as no evidence */ }
  }
  return ''
}

// The database name out of any Postgres URL: jdbc:postgresql://host:5432/openbank_x,
// vertx-reactive:postgresql://…/openbank_x, or a bare reactive postgresql://…/openbank_x.
// Defaulted config (`${JDBC_URL:jdbc:postgresql://localhost:5432/openbank_x}`) matches too —
// the default IS this module's statement of which database it owns.
function postgresDatabaseFrom(text) {
  if (!text) return null
  const names = new Set()
  const re = /postgresql:\/\/[^\s/}"']+\/([A-Za-z_][A-Za-z0-9_]*)/g
  let m
  while ((m = re.exec(text)) !== null) {
    // `%test` / `%it` profiles override the datasource with their own throwaway database
    // (openbank_vop_it, …). Counting those would make every service with an IT profile
    // ambiguous, i.e. unverifiable — the opposite of the intent.
    if (/_(it|test|dev)$/.test(m[1])) continue
    names.add(m[1])
  }
  // Ambiguity IS still respected: if a module's manifests point at two different production
  // databases we cannot say which one it owns, so we decline to verify rather than guess.
  return names.size === 1 ? [...names][0] : null
}

// What the CODE says about this module's persistence, independent of what it claims.
function evidenceFor(REPO, name, dir) {
  const short = name.replace(/^openbank-/, '')
  const appYaml = readText(path.join(dir, 'src', 'main', 'resources', 'application.yaml')) ?? ''
  const gitops = gitopsText(REPO, short)
  const pkg = readText(path.join(dir, 'package.json')) ?? ''

  const migrations = migrationFiles(dir)
  const derivedDatabase = postgresDatabaseFrom(appYaml) ?? postgresDatabaseFrom(gitops)
  const usesRedis = /(^|\n)\s*redis:\s*(\n|$)/.test(appYaml) || /QUARKUS_REDIS_HOSTS/.test(gitops)
  // A non-Quarkus module (admin-ui) has no application.yaml; a `pg` dependency is its
  // equivalent statement that it talks to Postgres.
  const pgClient = /"pg"\s*:/.test(pkg)

  return {
    ownsRelationalSchema: migrations.length > 0,
    migrationCount: migrations.length,
    derivedDatabase,
    usesRedis,
    usesPostgres: migrations.length > 0 || derivedDatabase != null || pgClient,
  }
}

// Cross-check the declaration against the evidence. Each rule states the contradiction AND
// the fix, because these strings are what a contributor sees in the failing CI step.
// `fleetDatabases` is every databaseName declared by any module, so a lineage edge can be
// checked against something real instead of being a free-text name nobody resolves.
function truthGaps(decl, ev, fleetDatabases) {
  const gaps = []
  const ownsNoDatabase = decl.ownsNoDatabase === true
  const declared = decl.primaryDatastore

  if (ev.ownsRelationalSchema && ownsNoDatabase) {
    gaps.push(`declares ownsNoDatabase: true but owns ${ev.migrationCount} Flyway migration(s) under src/main/resources/db/migration — drop 'ownsNoDatabase' and declare databaseName`)
  }
  if (!ev.ownsRelationalSchema && !ownsNoDatabase) {
    gaps.push(`declares databaseName='${decl.databaseName}' but owns no Flyway migrations — a module that creates no tables owns no database; declare 'ownsNoDatabase: true' instead`)
  }
  if (ev.ownsRelationalSchema && declared !== 'PostgreSQL') {
    gaps.push(`declares primaryDatastore='${declared}' but owns ${ev.migrationCount} Postgres Flyway migration(s) — the primary store is PostgreSQL`)
  }
  if (decl.databaseName != null && ev.derivedDatabase != null && decl.databaseName !== ev.derivedDatabase) {
    gaps.push(`declares databaseName='${decl.databaseName}' but its datasource URL points at '${ev.derivedDatabase}'`)
  }
  if (declared === 'none' && (ev.usesPostgres || ev.usesRedis)) {
    const wired = [ev.usesPostgres && 'Postgres', ev.usesRedis && 'Redis'].filter(Boolean).join(' + ')
    gaps.push(`declares primaryDatastore='none' but is wired to ${wired}`)
  }
  if (declared === 'Redis' && !ev.usesRedis) {
    gaps.push("declares primaryDatastore='Redis' but no Redis config exists (no `redis:` in application.yaml, no QUARKUS_REDIS_HOSTS in its GitOps manifest)")
  }
  if (declared === 'PostgreSQL' && !ev.usesPostgres) {
    gaps.push("declares primaryDatastore='PostgreSQL' but no Postgres wiring exists (no migrations, no datasource URL, no pg client)")
  }

  // GDPR Art. 5(1)(e): a module that stores anything must state how long for. 'N/A' on a
  // service holding onboarding data in Redis is not a retention policy, it is a blank.
  if (declared !== 'none' && isPlaceholder(decl.retentionPolicy)) {
    gaps.push(`declares retentionPolicy='${decl.retentionPolicy}' while storing data in ${declared} — state an actual period (GDPR Art. 5(1)(e))`)
  }

  const owned = decl.databaseLineage?.ownedDatabases
  if (owned && (owned.length !== 1 || owned[0] !== decl.databaseName)) {
    gaps.push(`databaseLineage.ownedDatabases=[${owned.join(', ')}] contradicts databaseName='${decl.databaseName ?? '(none)'}' — a module owns exactly the database it declares`)
  }
  for (const dep of decl.databaseLineage?.dependentDatabases ?? []) {
    if (dep === decl.databaseName) {
      gaps.push(`databaseLineage.dependentDatabases lists its own database '${dep}'`)
    } else if (!fleetDatabases.has(dep)) {
      gaps.push(`databaseLineage.dependentDatabases names '${dep}', which no module in the fleet declares as its databaseName`)
    }
  }
  return gaps
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

  // Pass 1: read + shape-validate, so pass 2 can check a lineage edge against the set of
  // databases the fleet actually declares.
  const parsed = []
  for (const name of modules) {
    const dir = path.join(REPO, name)
    const raw = readText(path.join(dir, 'governance.yaml'))
    if (raw == null) { gaps.push(`${name}: missing governance.yaml`); continue }
    let decl
    try { decl = parseYaml(raw) } catch (e) { gaps.push(`${name}: unparseable governance.yaml (${e.message})`); continue }
    const shapeProblems = validateDeclaration(decl)
    for (const p of shapeProblems) gaps.push(`${name}: ${p}`)
    parsed.push({ name, dir, decl, shapeProblems })
  }
  // Only shape-valid declarations contribute a name — otherwise a typo'd database would
  // vouch for the lineage edge that points at it.
  const fleetDatabases = new Set(
    parsed.filter(p => !p.shapeProblems.length && typeof p.decl?.databaseName === 'string').map(p => p.decl.databaseName),
  )

  // Pass 2: truth cross-checks + manifest rows.
  for (const { name, dir, decl, shapeProblems } of parsed) {
    const short = name.replace(/^openbank-/, '')
    const ev = evidenceFor(REPO, name, dir)
    // The truth rules read fields the shape rules may have just rejected, so on a malformed
    // declaration they would pile noise on top of the real error. Shape first, then truth.
    if (!shapeProblems.length) {
      for (const p of truthGaps(decl, ev, fleetDatabases)) gaps.push(`${name}: ${p}`)
    }

    const ownsNoDatabase = decl?.ownsNoDatabase === true
    services.push({
      serviceName: short,
      dataDomain: decl.dataDomain ?? null,
      primaryDatastore: decl.primaryDatastore ?? null,
      databaseName: decl.databaseName ?? null,
      // How believable this row is: 'derived' = the declared databaseName matched a datasource
      // URL found in the tree; 'declared-only' = nothing in the code could confirm it. Surfaced
      // rather than hidden, so a consumer can tell a verified fact from a bare claim.
      databaseNameEvidence: decl.databaseName == null ? null : (ev.derivedDatabase != null ? 'derived' : 'declared-only'),
      ownsNoDatabase: ownsNoDatabase || undefined,
      dataLineageRole: decl.dataLineageRole ?? null,
      dataClassification: decl.dataClassification ?? 'unknown',
      retentionPolicy: decl.retentionPolicy ?? 'unknown',
      evidenceExported: typeof decl.evidenceExported === 'boolean' ? decl.evidenceExported : undefined,
      flywayDeclaredVersion: flywayDeclaredVersion(dir),
      lineage: decl.lineage ?? undefined,
      databaseLineage: decl.databaseLineage ?? undefined,
    })
  }

  const totals = {
    modules: services.length,
    withLineage: services.filter(s => s.lineage).length,
    evidenceExported: services.filter(s => s.evidenceExported).length,
    // A declared database that nothing in the tree could confirm. Not a gap (a module may be
    // configured entirely outside this repo) but not a fact either — counted so the number is
    // visible instead of quietly growing.
    unverifiedDatabaseNames: services.filter(s => s.databaseNameEvidence === 'declared-only').length,
    withGaps: gaps.length,
  }

  return {
    schema: 'openbank.governance/v1',
    generator: 'generate-governance.mjs',
    source: 'code-derived (governance.yaml + db/migration + datasource URLs) — ADR-0071 / ADR-0196 / ADR-0029 D3',
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

  // Opt-in: regenerating the derived JSON Schema is a deliberate act, so the default run (the
  // CI gate, `npm test`) never dirties the tree. The unit test asserts the file is current.
  if (args.includes('--emit-schema')) {
    const target = path.join(REPO, SCHEMA_FILE)
    writeFileSync(target, JSON.stringify(jsonSchema(), null, 2) + '\n')
    console.log(`[generate-governance] wrote derived JSON Schema → ${target}`)
  }

  const manifest = buildManifest(REPO)
  const { totals, gaps } = manifest
  writeFileSync(OUT, JSON.stringify(manifest, null, 2) + '\n')
  console.log(`[generate-governance] ${totals.modules} modules (${totals.withLineage} with lineage, ${totals.unverifiedDatabaseNames} unverified databaseName, ${totals.withGaps} gaps) → ${OUT}`)
  if (gaps.length) {
    console.log('[generate-governance] gaps:')
    for (const g of gaps) console.log(`  - ${g}`)
  }
}

// CLI entrypoint only — importing this module (tests) must not write or log.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main()
