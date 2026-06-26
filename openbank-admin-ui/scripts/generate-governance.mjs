// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

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
import { fileURLToPath } from 'url'
import { parse as parseYaml } from 'yaml'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const args = process.argv.slice(2)
const getArg = (flag, dflt) => {
  const i = args.indexOf(flag)
  return i >= 0 && args[i + 1] ? args[i + 1] : dflt
}

const REPO = path.resolve(getArg('--repo', path.resolve(__dirname, '..', '..')))
const OUT = path.resolve(getArg('--out', path.resolve(__dirname, '..', 'governance.json')))

const REQUIRED = ['dataDomain', 'primaryDatastore', 'schemaName', 'dataLineageRole', 'dataClassification', 'retentionPolicy']

// Enum constraints mirror governance.schema.json — an out-of-enum value is a gap,
// so a future bad edit fails the CI drift gate instead of passing silently.
const ENUMS = {
  dataDomain: ['core', 'payments', 'compliance', 'identity', 'open-banking', 'platform'],
  dataLineageRole: ['producer', 'consumer', 'both', 'internal'],
  dataClassification: ['public', 'internal', 'confidential', 'restricted', 'unknown'],
}

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
  const missing = REQUIRED.filter(k => decl?.[k] == null || decl[k] === '')
  if (missing.length) gaps.push(`${name}: governance.yaml missing ${missing.join(', ')}`)
  for (const [k, allowed] of Object.entries(ENUMS)) {
    if (decl?.[k] != null && !allowed.includes(decl[k])) {
      gaps.push(`${name}: ${k}='${decl[k]}' not in [${allowed.join(', ')}]`)
    }
  }

  services.push({
    serviceName: short,
    dataDomain: decl.dataDomain ?? null,
    primaryDatastore: decl.primaryDatastore ?? null,
    schemaName: decl.schemaName ?? null,
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

const manifest = {
  schema: 'openbank.governance/v1',
  generator: 'generate-governance.mjs',
  source: 'code-derived (governance.yaml + db/migration) — ADR-0071 / ADR-0029 D3',
  collectedAt: null,
  totals,
  services,
}

writeFileSync(OUT, JSON.stringify(manifest, null, 2) + '\n')
console.log(`[generate-governance] ${totals.modules} modules (${totals.withLineage} with lineage, ${totals.withGaps} gaps) → ${OUT}`)
if (gaps.length) {
  console.log('[generate-governance] gaps:')
  for (const g of gaps) console.log(`  - ${g}`)
}
