#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
// Collect pitest XML reports + pact verification results from the monorepo and
// produce quality-report.json for the admin-ui image (ADR-0063).
//
// Usage: node scripts/collect-quality-report.mjs [--repo-root <path>]
// Output: quality-report.json (in CWD, which is openbank-admin-ui/ during the build)

import fs from 'fs'
import path from 'path'
import { execFileSync } from 'child_process'
import { pathToFileURL } from 'url'
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

function pactConsumerVersion(pactFile) {
  // `_service-ci.yml` publishes a consumer pact with the exact Git SHA of the
  // build that generated it. Pin the broker query to the last committed version
  // of this particular file; "latest" would otherwise let an unrelated, newer
  // consumer build turn an older contract green in the Admin UI.
  try {
    const version = execFileSync('git', ['log', '-1', '--format=%H', '--', `pacts/${pactFile}`], {
      cwd: REPO_ROOT,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
    }).trim()
    return /^[0-9a-f]{40}$/.test(version) ? version : null
  } catch {
    // A source archive or intentionally shallow checkout without the file's
    // history cannot prove provenance. Keep the contract pending, never latest.
    return null
  }
}

export function collectContracts() {
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
          consumerVersion: pactConsumerVersion(f),
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

// A pact left 'pending' is unresolved for one of several different reasons, and the fix for
// each differs (#7544). Classify so the admin-ui snapshot can say which applies, rather than
// flattening them all to one "unavailable" sentence:
//   - query-error: the broker itself answered with an error (bad request, auth, 5xx).
//   - no-provider-main-version: the provider has never published a main-branch version, so
//     there is nothing for provider verification to run against — re-dispatching cannot help.
//   - pending-verification: both pacticipants exist but the matrix has no verification row yet
//     — genuinely awaiting a verification run.
// (A missing consumerVersion — no Git provenance for the pact file — short-circuits below
// before any of these apply, and carries no reasonCode.)
// Never include the response body or the broker credentials in `detail` — it is bundled into
// the admin-ui image and rendered to any operator with console access.

export async function providerHasMainVersion(baseUrl, auth, provider) {
  const url = `${baseUrl.replace(/\/$/, '')}/pacticipants/${encodeURIComponent(provider)}/branches/main/latest-version`
  const headers = { Accept: 'application/hal+json' }
  if (auth) headers.Authorization = auth
  try {
    const res = await fetch(url, { headers, signal: AbortSignal.timeout(15000) })
    // A network/parse failure here proves nothing either way — do not assert the stronger
    // "no main version" claim without a real 404 to back it.
    return res.status !== 404
  } catch {
    return true
  }
}

export async function fetchPairVerification(baseUrl, auth, consumer, consumerVersion, provider) {
  if (!consumerVersion) return { status: 'pending', verifiedAt: null, providerVersion: null }
  // Matrix API — pin the consumer to the commit that authored this exact pact
  // file. The provider remains latest because it is the provider replay asked
  // to validate that consumer version. Pact Broker documents `version` as a
  // pacticipant build/version selector, normally a Git SHA.
  const qs = new URLSearchParams()
  qs.append('q[][pacticipant]', consumer)
  qs.append('q[][version]', consumerVersion)
  qs.append('q[][pacticipant]', provider)
  qs.append('q[][latest]', 'true')
  qs.append('latestby', 'cvpv')
  const url = `${baseUrl.replace(/\/$/, '')}/matrix?${qs.toString()}`

  const headers = { Accept: 'application/hal+json' }
  if (auth) headers.Authorization = auth

  const res = await fetch(url, { headers, signal: AbortSignal.timeout(15000) })
  if (!res.ok) {
    return {
      status: 'pending', verifiedAt: null, providerVersion: null, reasonCode: 'query-error',
      detail: `Pact Broker matrix query returned HTTP ${res.status} for ${consumer} → ${provider}.`,
    }
  }

  const body = await res.json()
  const rows = Array.isArray(body?.matrix) ? body.matrix : []
  const verifs = rows.map(r => r?.verificationResult).filter(Boolean)
  const providerVersion = rows.map(r => r?.providerVersion?.number).find(version => typeof version === 'string') ?? null

  if (verifs.some(v => v.success === false)) {
    const at = verifs.filter(v => v.verifiedAt).map(v => v.verifiedAt).sort().pop() ?? null
    return { status: 'failed', verifiedAt: at, providerVersion }
  }
  if (verifs.length > 0 && verifs.every(v => v.success === true)) {
    const at = verifs.map(v => v.verifiedAt).filter(Boolean).sort().pop() ?? null
    return { status: 'passed', verifiedAt: at, providerVersion }
  }
  if (!(await providerHasMainVersion(baseUrl, auth, provider))) {
    return {
      status: 'pending', verifiedAt: null, providerVersion: null, reasonCode: 'no-provider-main-version',
      detail: `${provider} has no published main-branch version in the Pact Broker, so provider verification cannot be dispatched yet.`,
    }
  }
  return {
    status: 'pending', verifiedAt: null, providerVersion: null, reasonCode: 'pending-verification',
    detail: `${provider} has a main-branch version, but no verification result for the latest ${consumer} pact yet.`,
  }
}

export async function enrichWithVerification(contracts) {
  const baseUrl = process.env.PACT_BROKER_URL
  if (!baseUrl) {
    console.log('  PACT_BROKER_URL unset — leaving contracts at pending (no broker query)')
    return contracts
  }
  const auth = brokerAuthHeader()
  let resolved = 0
  for (const c of contracts) {
    try {
      const v = await fetchPairVerification(baseUrl, auth, c.consumer, c.consumerVersion, c.provider)
      c.status = v.status
      c.verifiedAt = v.verifiedAt
      c.providerVersion = v.providerVersion
      c.interactions = c.interactions.map(i => ({ ...i, status: v.status }))
      c.reasonCode = v.reasonCode ?? null
      c.detail = v.detail ?? null
      if (v.status !== 'pending') resolved++
    } catch (e) {
      c.reasonCode = 'query-error'
      c.detail = `Pact Broker request failed while resolving ${c.consumer} → ${c.provider}: ${e.name ?? 'network or timeout error'}.`
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

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(e => { console.error(e); process.exit(1) })
}
