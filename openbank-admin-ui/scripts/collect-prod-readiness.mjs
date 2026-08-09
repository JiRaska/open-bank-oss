#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Production-readiness collector (Node.js ESM port of prod-readiness-collector.py).
//
// Derives C1–C9 maturity scores from the repo — no JDK, no credentials, pure
// filesystem read. Produces prod-readiness.json in the admin-ui working directory
// (same path the route.ts reads: process.cwd()/prod-readiness.json).
//
// Score scale per cell: 0 Absent · 1 Declared · 2 Verified · 3 Bank-grade.
//
// Wired into `prebuild` so the image always contains a fresh scorecard baked at
// build time. The admin-ui is a READ-ONLY consumer (ADR-0029 rule #7: derived data
// is never hand-edited). build-push-admin-ui.sh calls the Python version; this
// script is the Node path used by `npm run build` and the admin-ui CI step.
//
// Usage:
//   node scripts/collect-prod-readiness.mjs [--repo <path>] [--out <file>]
// Defaults: repo = two levels up from scripts/ (the monorepo root)
//           out  = ./prod-readiness.json

import { readdirSync, statSync, readFileSync, writeFileSync, existsSync } from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

const args = process.argv.slice(2)
const getArg = (flag, dflt) => {
  const i = args.indexOf(flag)
  return i >= 0 && args[i + 1] ? args[i + 1] : dflt
}

const REPO   = path.resolve(getArg('--repo', path.resolve(__dirname, '..', '..')))
const OUT    = path.resolve(getArg('--out', path.resolve(__dirname, '..', 'prod-readiness.json')))
const TODAY  = process.env.READINESS_TODAY ?? new Date().toISOString().slice(0, 10)

// ---------------------------------------------------------------------------
// Money-path services (mirrors rules.yaml: money_path_services).
// Short names (strip openbank-…-service).
// ---------------------------------------------------------------------------
const MONEY_PATH = new Set([
  'ledger', 'transaction', 'account', 'balance',
  'sepa-payment', 'sepa-instant', 'domestic-payment',
  'clearing', 'swift', 'fx',
  'lending', 'sca', 'consent', 'fraud',
])

const DIMENSIONS = [
  { code: 'C1', name: 'Kód' },
  { code: 'C2', name: 'Testy' },
  { code: 'C3', name: 'API' },
  { code: 'C4', name: 'Data' },
  { code: 'C5', name: 'Zálohy' },
  { code: 'C6', name: 'DR/BCP' },
  { code: 'C7', name: 'Security' },
  { code: 'C8', name: 'Observab.' },
  { code: 'C9', name: 'Provoz' },
]

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------
function svcDir(short) {
  return path.join(REPO, `openbank-${short}-service`)
}

function readText(file) {
  try { return readFileSync(file, 'utf-8') } catch { return '' }
}

/** Recursively collect files under dir matching the predicate (bounded). */
function findFiles(dir, predicate, found = []) {
  if (!existsSync(dir)) return found
  let entries
  try { entries = readdirSync(dir) } catch { return found }
  for (const entry of entries) {
    const full = path.join(dir, entry)
    let st
    try { st = statSync(full) } catch { continue }
    if (st.isDirectory()) findFiles(full, predicate, found)
    else if (predicate(entry, full)) found.push(full)
  }
  return found
}

function countFiles(dir, predicate) {
  return findFiles(dir, predicate).length
}

/** True if any .kt/.kts file under dir contains any of the needles. */
function grepAny(dir, needles) {
  if (!existsSync(dir)) return false
  const files = findFiles(dir, (e) => e.endsWith('.kt') || e.endsWith('.kts'))
  for (const f of files) {
    const text = readText(f)
    if (needles.some(n => text.includes(n))) return true
  }
  return false
}

/** Gitops component dir for a given service short-name. */
function gitopsComponentDir(short) {
  const aliases = [
    short,
    short.replace(/-service$/, ''),
    short === 'account'          ? 'accounts'      : null,
    short === 'balance'          ? 'balances'       : null,
    short === 'notification'     ? 'notifications'  : null,
    short === 'sepa-payment'     ? 'payments'       : null,
    short === 'sepa-instant'     ? 'payments'       : null,
    short === 'domestic-payment' ? 'payments'       : null,
    short === 'swift'            ? 'payments'       : null,
    short === 'clearing'         ? 'payments'       : null,
    short === 'sdd'              ? 'payments'       : null,
    short === 'transaction'      ? 'statements'     : null,
    short === 'standing-order'   ? 'statements'     : null,
    short === 'statement'        ? 'statements'     : null,
    short === 'tpp-registry'     ? 'registry-cache' : null,
  ].filter(Boolean)

  const components = path.join(REPO, 'openbank-infra', 'gitops', 'components')
  for (const c of aliases) {
    const d = path.join(components, c)
    if (existsSync(d)) return d
  }
  return null
}

function gitopsYamlsFor(short) {
  const dir = gitopsComponentDir(short)
  if (!dir) return []
  return findFiles(dir, (e) => e.endsWith('.yaml') || e.endsWith('.yml'))
}

function gitopsFilesWithKind(short, kind) {
  return gitopsYamlsFor(short).filter(f => readText(f).includes(`kind: ${kind}`))
}

// ---------------------------------------------------------------------------
// Attestations (M-dimensions) with TTL decay
// ---------------------------------------------------------------------------
function loadAttestations() {
  const file = path.join(REPO, 'openbank-libs', 'governance', 'attestations.yaml')
  if (!existsSync(file)) return {}
  const data = {}
  let curSvc = null
  for (const raw of readText(file).split('\n')) {
    if (!raw.trim() || raw.trimStart().startsWith('#')) continue
    const indent = raw.length - raw.trimStart().length
    const line = raw.trim()
    if (indent === 0 && line.endsWith(':')) {
      curSvc = line.slice(0, -1)
      data[curSvc] = {}
    } else if (indent === 2 && line.includes('{')) {
      const k = line.split(':')[0].trim()
      const matches = [...line.matchAll(/(\w+):\s*([^\s,}]+)/g)]
      if (curSvc) data[curSvc][k] = Object.fromEntries(matches.map(m => [m[1], m[2]]))
    } else if (indent === 2 && !line.endsWith(':')) {
      // inline key: value
      const [k, ...rest] = line.split(':')
      if (curSvc && k && rest.length) data[curSvc][k.trim()] = rest.join(':').trim()
    }
  }
  return data
}

const ATT = loadAttestations()

function attestFresh(svc, key) {
  const rec = ATT[svc]?.[key]
  if (!rec || typeof rec !== 'object' || !rec.date) return false
  const ttl = parseInt(rec.ttl_days ?? '365', 10) || 365
  // Exact calendar arithmetic. The previous form approximated a year as 365 days and a
  // month as 30, which let a TTL run past its own expiry (#2365): ledger's 21-day pentest,
  // dated 2026-07-26, still counted on 2026-08-17. A TTL that outlives itself is the one
  // thing this mechanism exists to prevent.
  const DAY = 86400000
  const days = Math.round((Date.parse(`${TODAY}T00:00:00Z`) - Date.parse(`${rec.date}T00:00:00Z`)) / DAY)
  return Number.isFinite(days) && days >= 0 && days <= ttl
}

// ---------------------------------------------------------------------------
// Per-dimension scorers  (short: string) → { score, evidence }
// ---------------------------------------------------------------------------

function scoreC1Code(short) {
  const main = path.join(svcDir(short), 'src', 'main')
  if (!existsSync(main)) return { score: 0, evidence: 'no src/main' }
  const kt = countFiles(main, e => e.endsWith('.kt'))
  const hasPortFiles = countFiles(main, e => e.includes('Port') && e.endsWith('.kt')) > 0
  const hasGov = existsSync(path.join(svcDir(short), 'governance.yaml'))
  if (kt < 8) return { score: 1, evidence: `skeleton (${kt} kt files)` }
  let s = (hasPortFiles && hasGov) ? 2 : 1
  if (attestFresh(short, 'code_complete')) s = 3
  return { score: s, evidence: `${kt} kt, ports=${hasPortFiles ? 'y' : 'n'}, gov=${hasGov ? 'y' : 'n'}` }
}

function scoreC2Tests(short) {
  const test = path.join(svcDir(short), 'src', 'test')
  if (!existsSync(test)) return { score: 0, evidence: 'no src/test' }
  const kt = countFiles(test, e => e.endsWith('.kt'))
  if (kt === 0) return { score: 0, evidence: 'empty test dir' }
  const it = countFiles(test, e => e.endsWith('IT.kt'))
  const unit = kt - it
  const buildGradle = readText(path.join(svcDir(short), 'build.gradle.kts'))
  const kover = buildGradle.includes('kover {')
  let s = 1
  if (kover && kt > 0) s = 2
  if (attestFresh(short, 'coverage_floor')) s = 3
  return { score: s, evidence: `${unit} unit, ${it} IT, kover=${kover ? 'y' : 'n'}` }
}

function scoreC3Api(short) {
  const openapiPath = path.join(svcDir(short), 'src', 'main', 'resources', 'openapi.yaml')
  if (!existsSync(openapiPath)) return { score: 0, evidence: 'no openapi.yaml' }
  const testDir = path.join(svcDir(short), 'src', 'test')
  const contract = grepAny(testDir, ['Pact', 'ContractTest', 'contract'])
  const buildGradle = readText(path.join(svcDir(short), 'build.gradle.kts'))
  const diffGate = buildGradle.includes('oasdiff') || buildGradle.includes('spectral')
  let s = 1
  if (contract) s = 2
  if (attestFresh(short, 'contract_verified')) s = 3
  return {
    score: s,
    evidence: `openapi=y, contract=${contract ? 'y' : 'n'}, diffgate=${diffGate ? 'y' : 'n'}`,
  }
}

function scoreC4Data(short) {
  const migs = findFiles(svcDir(short), e => /^V\d+.*\.sql$/.test(e))
  if (migs.length === 0) return { score: 1, evidence: 'no flyway (stateless?)' }
  const rollback = migs.some(m => readText(m).toLowerCase().includes('rollback'))
    || grepAny(svcDir(short), ['rollback'])
  const s = rollback ? 2 : 1
  return { score: s, evidence: `${migs.length} migrations, rollback_note=${rollback ? 'y' : 'n'}` }
}

function scoreC5Backup(short) {
  const clusters = gitopsFilesWithKind(short, 'Cluster')
  const cnpg = clusters.filter(f => f.includes('postgres') || readText(f).toLowerCase().includes('cnpg'))
  if (cnpg.length === 0) return { score: 0, evidence: 'no CNPG cluster' }
  const hasBackup = cnpg.some(f => readText(f).includes('barmanObjectStore'))
  if (!hasBackup) return { score: 1, evidence: 'cluster present, NO backup' }
  const s = attestFresh(short, 'restore_drill') ? 3 : 2
  return { score: s, evidence: 'backup configured' + (s === 3 ? ' + drill' : '') }
}

function scoreC6Dr(short) {
  if (attestFresh(short, 'dr_drill')) return { score: 3, evidence: 'DR drill exercised' }
  const runbooksDir = path.join(REPO, 'docs', 'runbooks')
  const rb = path.join(runbooksDir, `svc-${short}.md`)
  if (existsSync(rb) && /^#+\s*disaster recovery/im.test(readText(rb))) {
    return { score: 2, evidence: 'service DR procedure documented' }
  }
  let hasRunbooks = false
  try { hasRunbooks = existsSync(runbooksDir) && readdirSync(runbooksDir).some(e => e.endsWith('.md')) } catch { /* */ }
  return hasRunbooks
    ? { score: 1, evidence: 'generic runbooks only' }
    : { score: 0, evidence: 'no DR' }
}

function hasSignedProvenance(short) {
  const pipeline = path.join(REPO, '.github', 'workflows', 'release-please.yml')
  const pipelineOk = existsSync(pipeline) && readText(pipeline).includes('release-evidence')
  const released = existsSync(path.join(svcDir(short), 'version.txt'))
  return pipelineOk && released
}

function hasVexTriage(short) {
  const vexDir = path.join(REPO, 'openbank-libs', 'governance', 'vex')
  return [`${short}-service`, short].some(n => existsSync(path.join(vexDir, `${n}.openvex.json`)))
}

function scoreC7Security(short) {
  const tm = existsSync(path.join(REPO, 'docs', 'threat-models', `openbank-${short}-service.md`))
  const netpol = gitopsFilesWithKind(short, 'NetworkPolicy').length > 0
  const sectest = grepAny(
    path.join(svcDir(short), 'src', 'test'),
    ['Security', 'schemathesis', 'Authz'],
  )
  const prov = hasSignedProvenance(short)
  const bits = [tm, netpol, sectest, prov].filter(Boolean).length
  if (bits === 0) return { score: 0, evidence: 'no threat-model/netpol/sectest/provenance' }
  let s = bits === 1 ? 1 : 2
  if (attestFresh(short, 'pentest')) s = 3
  const ev = [
    tm && 'threat-model',
    netpol && 'netpol',
    sectest && 'sec-test',
    prov && 'signed-provenance',
    hasVexTriage(short) && 'vex-triage',
  ].filter(Boolean)
  return { score: s, evidence: ev.join(', ') }
}

function scoreC8Observability(short) {
  const podmonFile = path.join(
    REPO, 'openbank-infra', 'gitops', 'components', 'observability',
    'podmonitor-openbank-services.yaml',
  )
  const monitored = readText(podmonFile).includes(short)
  const alerts = gitopsFilesWithKind(short, 'PrometheusRule').length > 0
  const metrics = grepAny(
    path.join(svcDir(short), 'src', 'main'),
    ['MeterRegistry', 'DomainMetrics', '@Counted', '@Timed'],
  )
  if (!monitored && !metrics) return { score: 0, evidence: 'not scraped' }
  let s = 1
  if (monitored && metrics) s = 2
  if (attestFresh(short, 'slo_defined')) s = 3
  const ev = [monitored && 'scraped', metrics && 'metrics', alerts && 'alerts'].filter(Boolean)
  return { score: s, evidence: ev.join(', ') || 'none' }
}

function scoreC9Ops(short) {
  if (attestFresh(short, 'oncall')) return { score: 3, evidence: 'on-call + break-glass audited' }
  const rb = path.join(REPO, 'docs', 'runbooks', `svc-${short}.md`)
  return existsSync(rb)
    ? { score: 2, evidence: 'service runbook' }
    : { score: 1, evidence: 'no per-service runbook' }
}

const SCORERS = [
  ['C1', scoreC1Code],
  ['C2', scoreC2Tests],
  ['C3', scoreC3Api],
  ['C4', scoreC4Data],
  ['C5', scoreC5Backup],
  ['C6', scoreC6Dr],
  ['C7', scoreC7Security],
  ['C8', scoreC8Observability],
  ['C9', scoreC9Ops],
]

// ---------------------------------------------------------------------------
// Gate logic
// ---------------------------------------------------------------------------
function computeGate(short, scores) {
  const mp = MONEY_PATH.has(short)
  const critical = new Set(['C1', 'C5', 'C7'])
  for (const { code } of DIMENSIONS) {
    const need = mp && critical.has(code) ? 3 : 2
    if ((scores[code] ?? 0) < need) return 'NO-GO'
  }
  return 'GO'
}

// ---------------------------------------------------------------------------
// Service discovery
// ---------------------------------------------------------------------------
function allServices() {
  const out = []
  for (const entry of readdirSync(REPO).sort()) {
    const m = entry.match(/^openbank-(.+)-service$/)
    if (!m) continue
    const d = path.join(REPO, entry)
    try { if (!statSync(d).isDirectory()) continue } catch { continue }
    out.push(m[1])
  }
  return out
}

// ---------------------------------------------------------------------------
// Collect
// ---------------------------------------------------------------------------
function collectService(short) {
  const scores = {}
  const evidence = {}
  for (const [code, scorer] of SCORERS) {
    const { score, evidence: ev } = scorer(short)
    scores[code] = score
    evidence[code] = ev
  }
  const gate = computeGate(short, scores)
  return { service: short, money_path: MONEY_PATH.has(short), scores, evidence, gate }
}

// ---------------------------------------------------------------------------
// Guard: keep a non-empty existing bundle rather than clobbering with all-zeros
// ---------------------------------------------------------------------------
function existingBundleCount() {
  try {
    const existing = JSON.parse(readFileSync(OUT, 'utf-8'))
    return Array.isArray(existing.services) ? existing.services.length : 0
  } catch { return 0 }
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------
const services = allServices()
const results = services.map(collectService)
const go = results.filter(r => r.gate === 'GO').length

if (results.length === 0) {
  const existing = existingBundleCount()
  if (existing > 0) {
    console.log(`[collect-prod-readiness] no services found — kept existing bundle (${existing} services)`)
    process.exit(0)
  }
}

const payload = {
  generated_for: TODAY,
  dimensions: DIMENSIONS,
  services: results,
}

writeFileSync(OUT, JSON.stringify(payload, null, 2))
console.log(
  `[collect-prod-readiness] ${results.length} services scored, ${go} GO / ${results.length - go} NO-GO → ${OUT}`,
)
