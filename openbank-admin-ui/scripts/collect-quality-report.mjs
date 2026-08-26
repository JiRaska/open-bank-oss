#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
// Collect pitest XML reports + pact verification results from the monorepo and
// produce quality-report.json for the admin-ui image (ADR-0063).
//
// Usage: node scripts/collect-quality-report.mjs [--repo-root <path>]
// Output: quality-report.json (in CWD, which is openbank-admin-ui/ during the build)

import fs from 'fs'
import path from 'path'
import { parseStringPromise } from 'xml2js'
import { moneyPathServices } from './lib/service-inventory.mjs'

const args = process.argv.slice(2)
const repoRootIdx = args.indexOf('--repo-root')
const REPO_ROOT = repoRootIdx >= 0 ? path.resolve(args[repoRootIdx + 1]) : path.resolve('..')
const OUT = path.resolve('quality-report.json')

const MONEY_PATH_SERVICES = moneyPathServices(REPO_ROOT)

function collectCoverage(service) {
  const xmlPath = path.join(REPO_ROOT, service, 'build', 'reports', 'kover', 'report.xml')
  if (!fs.existsSync(xmlPath)) return null
  const xml = fs.readFileSync(xmlPath, 'utf8')
  const counters = [...xml.matchAll(/<counter\s+type="LINE"\s+missed="(\d+)"\s+covered="(\d+)"\s*\/>/g)]
  const total = counters.at(-1)
  if (!total) return null
  const missed = Number(total[1])
  const covered = Number(total[2])
  return missed + covered > 0 ? Math.round((covered / (missed + covered)) * 100) : null
}

// ── Pitest ───────────────────────────────────────────────────────────────────

async function collectMutation(service) {
  const xmlPath = path.join(REPO_ROOT, service, 'build', 'reports', 'pitest', 'mutations.xml')
  if (!fs.existsSync(xmlPath)) return null

  const raw = fs.readFileSync(xmlPath, 'utf-8')
  const parsed = await parseStringPromise(raw, { explicitArray: true })
  const mutations = parsed?.mutations?.mutation ?? []

  let killed = 0, survived = 0, noCoverage = 0, total = 0
  for (const m of mutations) {
    total++
    const status = m.$.status
    if (status === 'KILLED') killed++
    else if (status === 'SURVIVED') survived++
    else if (status === 'NO_COVERAGE') noCoverage++
  }

  return {
    service,
    targetPackage: `com.openbank.${service.replace('openbank-', '').replace(/-service$/, '').replace(/-/g, '.')}.domain`,
    totalMutants: total,
    killed,
    survived,
    noCoverage,
    score: total > 0 ? Math.round((killed / total) * 100) : null,
    reportedAt: fs.statSync(xmlPath).mtime.toISOString(),
  }
}

// ── Pact contracts ────────────────────────────────────────────────────────────

function collectContracts() {
  const pactsDir = path.join(REPO_ROOT, 'pacts')
  if (!fs.existsSync(pactsDir)) return []

  return fs.readdirSync(pactsDir)
    .filter(f => f.endsWith('.json'))
    .map(f => {
      try {
        const pact = JSON.parse(fs.readFileSync(path.join(pactsDir, f), 'utf-8'))
        return {
          consumer: pact.consumer?.name ?? 'unknown',
          provider: pact.provider?.name ?? 'unknown',
          pactFile: f,
          // Default to pending; enrichWithVerification() overwrites this with the
          // real provider-verification verdict from the Pact Broker when reachable.
          status: 'pending',
          verifiedAt: null,
          interactions: (pact.interactions ?? []).map(i => ({
            description: i.description,
            status: 'pending',
          })),
        }
      } catch {
        return null
      }
    })
    .filter(Boolean)
}

// ── Pact Broker verification (ADR-0092) ────────────────────────────────────────
// The provider-verification verdict lives in the broker (pact.open-bank.tech), not
// in the git-pact files. When PACT_BROKER_URL is configured (same vars the CI
// can-i-deploy gate uses), query the broker's Matrix API per consumer/provider pair
// and fold the real verdict into each contract. Outbound-only, read-only creds.
// Fail-soft: any unreachable/erroring broker leaves the contract at 'pending' and
// never fails the image build (the same posture as a clean checkout with no pacts).

function brokerAuthHeader() {
  const user = process.env.PACT_BROKER_USERNAME
  const pass = process.env.PACT_BROKER_PASSWORD
  if (!user || !pass) return null
  return 'Basic ' + Buffer.from(`${user}:${pass}`).toString('base64')
}

async function fetchPairVerification(baseUrl, auth, consumer, provider) {
  // Matrix API — the same source can-i-deploy reasons over. Latest version of each
  // pacticipant, joined cvpv (consumer-version × provider-version).
  const qs = new URLSearchParams()
  qs.append('q[][pacticipant]', consumer)
  qs.append('q[][latest]', 'true')
  qs.append('q[][pacticipant]', provider)
  qs.append('q[][latest]', 'true')
  qs.append('latestby', 'cvpv')
  const url = `${baseUrl.replace(/\/$/, '')}/matrix?${qs.toString()}`

  const headers = { Accept: 'application/hal+json' }
  if (auth) headers.Authorization = auth

  const res = await fetch(url, { headers, signal: AbortSignal.timeout(15000) })
  if (!res.ok) return { status: 'pending', verifiedAt: null }

  const body = await res.json()
  const rows = Array.isArray(body?.matrix) ? body.matrix : []
  const verifs = rows.map(r => r?.verificationResult).filter(Boolean)

  if (verifs.some(v => v.success === false)) {
    const at = verifs.filter(v => v.verifiedAt).map(v => v.verifiedAt).sort().pop() ?? null
    return { status: 'failed', verifiedAt: at }
  }
  if (verifs.length > 0 && verifs.every(v => v.success === true)) {
    const at = verifs.map(v => v.verifiedAt).filter(Boolean).sort().pop() ?? null
    return { status: 'passed', verifiedAt: at }
  }
  return { status: 'pending', verifiedAt: null }
}

async function enrichWithVerification(contracts) {
  const baseUrl = process.env.PACT_BROKER_URL
  if (!baseUrl) {
    console.log('  PACT_BROKER_URL unset — leaving contracts at pending (no broker query)')
    return contracts
  }
  const auth = brokerAuthHeader()
  let resolved = 0
  for (const c of contracts) {
    try {
      const v = await fetchPairVerification(baseUrl, auth, c.consumer, c.provider)
      c.status = v.status
      c.verifiedAt = v.verifiedAt
      c.interactions = c.interactions.map(i => ({ ...i, status: v.status }))
      if (v.status !== 'pending') resolved++
    } catch (e) {
      console.log(`  broker verification lookup failed for ${c.consumer} → ${c.provider}: ${e.message} (left pending)`)
    }
  }
  console.log(`  broker verification: ${resolved}/${contracts.length} contract pair(s) resolved`)
  return contracts
}

// ── Composite score ──────────────────────────────────────────────────────────

function buildServiceScores(testResults, mutations, contracts) {
  return MONEY_PATH_SERVICES.map(service => {
    // unit/integration pass rate from test-results.json (already bundled)
    const tr = testResults?.services?.find(s => s.service === service)
    const unitScore = tr && tr.tests > 0 ? Math.round((tr.passed / tr.tests) * 100) : null

    const coverageScore = collectCoverage(service)

    const mut = mutations.find(m => m?.service === service)
    const mutationScore = mut?.score ?? null

    const serviceContracts = contracts.filter(c => c.provider === service || c.consumer === service)
    const contractScore = serviceContracts.length === 0
      ? null
      : serviceContracts.every(c => c.status === 'passed') ? 100
      : serviceContracts.some(c => c.status === 'failed') ? 0
      : null

    const components = [unitScore, coverageScore, mutationScore, contractScore].filter(v => v !== null)
    const composite = components.length > 0
      ? Math.round(components.reduce((a, b) => a + b, 0) / components.length)
      : null

    return { service, unitScore, coverageScore, mutationScore, contractScore, composite }
  })
}

// ── Main ──────────────────────────────────────────────────────────────────────

async function main() {
  console.log(`Collecting quality report from ${REPO_ROOT}`)

  // Read existing test-results.json if present (for unit score)
  let testResults = null
  try {
    testResults = JSON.parse(fs.readFileSync(path.resolve('test-results.json'), 'utf-8'))
  } catch { /* not available */ }

  const mutations = (await Promise.all(MONEY_PATH_SERVICES.map(collectMutation))).filter(Boolean)
  const contracts = await enrichWithVerification(collectContracts())
  const serviceScores = buildServiceScores(testResults, mutations, contracts)

  const report = {
    contracts,
    mutations,
    serviceScores,
    collectedAt: new Date().toISOString(),
  }

  fs.writeFileSync(OUT, JSON.stringify(report, null, 2))
  console.log(`Written: ${OUT}`)
  console.log(`  ${contracts.length} contract pair(s), ${mutations.length} mutation report(s)`)
}

main().catch(e => { console.error(e); process.exit(1) })
