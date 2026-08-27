#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
// Compose repository governance and staged CI artifacts into the versioned,
// read-only Test Intelligence snapshot decided by ADR-0273.

import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'
import { parse as parseYaml } from 'yaml'
import { parseStringPromise } from 'xml2js'

const here = path.dirname(fileURLToPath(import.meta.url))
const args = process.argv.slice(2)
const arg = (name, fallback) => {
  const index = args.indexOf(name)
  return index >= 0 && args[index + 1] ? args[index + 1] : fallback
}
const repo = path.resolve(arg('--repo', path.resolve(here, '..', '..')))
const out = path.resolve(arg('--out', path.resolve(here, '..', 'test-intelligence.json')))
const staleAfterMs = Number(arg('--stale-after-days', '14')) * 86_400_000
const maxFutureSkewMs = 5 * 60_000
const collectedAt = new Date()
const warnings = []

const exists = file => fs.existsSync(file)
const readJson = file => {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')) } catch { return null }
}
const readText = file => {
  try { return fs.readFileSync(file, 'utf8') } catch { return '' }
}
const trustedRunUrl = (value, runId) => {
  if (!value) return false
  try {
    const url = new URL(String(value))
    const parts = url.pathname.split('/')
    return url.protocol === 'https:' && url.hostname === 'github.com'
      && !url.search && !url.hash
      && parts.length === 6 && Boolean(parts[1]) && Boolean(parts[2])
      && parts[3] === 'actions' && parts[4] === 'runs' && parts[5] === String(runId)
  } catch { return false }
}
const unsafeRunWarnings = new Set()
const safeRun = (run, source) => {
  if (!run || !trustedRunUrl(run.url, run.id)) {
    if (run && !unsafeRunWarnings.has(source)) {
      warnings.push(`untrusted run URL omitted: ${source}`)
      unsafeRunWarnings.add(source)
    }
    return undefined
  }
  return { ...run, id: String(run.id), attempt: Number(run.attempt), url: String(run.url) }
}
const observedAt = file => {
  try { return fs.statSync(file).mtime.toISOString() } catch { return null }
}
const stateFrom = (failed, executed, at) => {
  if (!at) return 'not-run'
  if (failed > 0) return 'failed'
  const observed = Date.parse(at)
  if (!Number.isFinite(observed)) return 'not-run'
  if (observed - collectedAt.getTime() > maxFutureSkewMs) return 'unknown'
  if (executed === 0) return 'skipped'
  if (collectedAt.getTime() - observed > staleAfterMs) return 'stale'
  return 'passed'
}
const freshnessAwareState = (state, at) => {
  // An old failure or explicit control gap remains actionable; age must never
  // launder it into a weaker verdict. Conversely, a successful observation is
  // not evergreen just because its signed envelope is retained longer than the
  // fleet freshness budget.
  if (state === 'failed' || state === 'blocked' || state === 'unknown' || state === 'not-run') return state
  const observed = Date.parse(at ?? '')
  if (!Number.isFinite(observed)) return 'not-run'
  if (observed - collectedAt.getTime() > maxFutureSkewMs) return 'unknown'
  if (collectedAt.getTime() - observed > staleAfterMs) return 'stale'
  return state
}

function releasedComponents() {
  return fs.readdirSync(repo, { withFileTypes: true })
    .filter(entry => entry.isDirectory() && entry.name.startsWith('openbank-'))
    .map(entry => entry.name)
    .filter(name => exists(path.join(repo, name, 'version.txt')))
    .sort()
}

function allFiles(dir, predicate) {
  if (!exists(dir)) return []
  const result = []
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) result.push(...allFiles(full, predicate))
    else if (predicate(full)) result.push(full)
  }
  return result
}

export function classifyJUnitEvidence(task, identity, component) {
  const integrationIdentity = /(?:\.integration\.|\.it\.|IT(?:$|\$|\.))/i.test(identity)
  // JVM services commonly keep true end-to-end HTTP tests in the ordinary Gradle `test`
  // task. The task name alone cannot distinguish them from unit tests, so retain the
  // explicit suite/class convention used by the CI run-envelope collector.
  const e2eIdentity = /(?:\.e2e\.|E2E(?:$|\$|\.))/i.test(identity)
  if (/integration|inttest/i.test(task) || integrationIdentity) return 'integration'
  if (/e2e|playwright/i.test(task) || e2eIdentity) return 'e2e'
  return component === 'openbank-simulation' ? 'simulation' : 'unit'
}

async function junitEvidence(component) {
  const root = path.join(repo, component, 'build', 'test-results')
  // Gradle's TEST-*.xml convention is not universal: Vitest and Playwright emit
  // arbitrary XML names with a <testsuites> wrapper.  This is a fallback for a
  // missing run envelope, so losing those reports would turn a real CI result
  // into a false "not run" observation in the operator UI.
  const files = allFiles(root, file => file.endsWith('.xml'))
  const buckets = new Map()
  for (const file of files) {
    const relative = path.relative(root, file).split(path.sep)
    const task = relative[0] || 'test'
    let parsed
    try {
      parsed = await parseStringPromise(fs.readFileSync(file, 'utf8'), { explicitArray: true })
    } catch {
      warnings.push(`unparsable JUnit report skipped: ${path.relative(repo, file)}`)
      continue
    }
    const directSuites = Array.isArray(parsed?.testsuite) ? parsed.testsuite : parsed?.testsuite ? [parsed.testsuite] : []
    const wrappers = Array.isArray(parsed?.testsuites) ? parsed.testsuites : parsed?.testsuites ? [parsed.testsuites] : []
    const suites = directSuites.length ? directSuites : wrappers.flatMap(wrapper => wrapper.testsuite ?? [])
    if (!suites.length) continue
    // Gradle commonly puts Quarkus/API/DB `*IT` classes under the ordinary `test`
    // task. Directory-only classification was the reason those tests disappeared
    // from the integration count. Prefer explicit task metadata, then the JUnit
    // suite/class identity carried by the artifact itself.
    for (const suite of suites) {
      const attributes = suite.$ ?? {}
      const cases = suite.testcase ?? []
      const identity = [attributes.name, ...cases.map(item => item.$?.classname)].filter(Boolean).join(' ')
      const kind = classifyJUnitEvidence(task, identity, component)
      const bucket = buckets.get(kind) ?? {
        kind, source: `JUnit:${task}`, environment: 'ci', durationMs: 0,
        counts: { discovered: 0, executed: 0, passed: 0, failed: 0, skipped: 0, errors: 0 },
        observedAt: null,
      }
      const value = name => Number(attributes[name] ?? 0)
      const discovered = value('tests')
      const failures = value('failures')
      const errors = value('errors')
      const skipped = value('skipped')
      bucket.counts.discovered += discovered
      bucket.counts.failed += failures + errors
      bucket.counts.errors += errors
      bucket.counts.skipped += skipped
      bucket.counts.executed += Math.max(0, discovered - skipped)
      bucket.counts.passed += Math.max(0, discovered - failures - errors - skipped)
      bucket.durationMs += Math.round(value('time') * 1000)
      const at = observedAt(file)
      if (at && (!bucket.observedAt || at > bucket.observedAt)) bucket.observedAt = at
      buckets.set(kind, bucket)
    }
  }
  return [...buckets.values()].map(bucket => ({
    ...bucket,
    state: stateFrom(bucket.counts.failed, bucket.counts.executed, bucket.observedAt),
  }))
}

function runEnvelope(component) {
  const file = path.join(repo, component, 'build', 'test-intelligence', 'run.json')
  const run = readJson(file)
  if (!run || run.schemaVersion !== 1 || run.component !== component || !Array.isArray(run.suites)) return null
  const provenance = safeRun(run.run, component)
  return {
    evidence: [...run.suites.map(suite => ({
      kind: suite.kind, state: freshnessAwareState(suite.state, run.run?.observedAt ?? observedAt(file)), observedAt: run.run?.observedAt ?? observedAt(file),
      source: 'test-intelligence-run:v1', environment: 'ci', durationMs: suite.durationMs,
      counts: { discovered: suite.discovered, executed: suite.executed, passed: suite.passed,
        failed: suite.failed, skipped: suite.skipped, errors: suite.errors }, run: provenance,
      diagnostics: (run.diagnostics ?? []).filter(item => item.suiteKind === suite.kind
        && provenance?.url && item.url === `${provenance.url}#artifacts`).map(item => ({
        kind: item.kind, name: item.name, url: item.url, retentionDays: item.retentionDays,
        access: item.access, mayContainSensitiveData: item.mayContainSensitiveData,
      })),
    })), ...(run.specializedEvidence ?? []).map(item => ({
      kind: item.kind, state: freshnessAwareState(item.state, run.run?.observedAt ?? observedAt(file)), observedAt: run.run?.observedAt ?? observedAt(file),
      source: item.source, environment: 'ci', detail: item.detail, run: provenance,
    }))],
    coverage: run.coverage ? {
      state: stateFrom(0, 1, run.run?.observedAt ?? observedAt(file)),
      observedAt: run.run?.observedAt ?? observedAt(file), lines: run.coverage.lines,
      branches: run.coverage.branches, source: 'test-intelligence-run:v1',
    } : null,
    testImpact: run.testImpact ?? null,
    testInfrastructure: run.testInfrastructure ?? { declared: [], observed: [] },
  }
}

function componentOwners() {
  const file = path.join(repo, 'CODEOWNERS')
  const owners = new Map()
  let fallback = 'unowned'
  if (!exists(file)) return { owners, fallback }
  for (const raw of fs.readFileSync(file, 'utf8').split('\n')) {
    const line = raw.replace(/\s+#.*$/, '').trim()
    if (!line || line.startsWith('#')) continue
    const [pattern, ...values] = line.split(/\s+/)
    if (pattern === '*') fallback = values.join(' ') || fallback
    const match = /^\/([^/*]+)\/$/.exec(pattern)
    if (match && values.length) owners.set(match[1], values.join(' '))
  }
  return { owners, fallback }
}

function testCaseHistory(currentEnvelopes) {
  const historical = allFiles(path.join(repo, 'openbank-admin-ui', 'test-run-history'), file => file.endsWith('.json'))
    .map(readJson)
  const envelopes = [...historical, ...currentEnvelopes]
    .filter(item => item?.schemaVersion === 1 && item?.run && item?.component && Array.isArray(item.testCases))
  const unique = new Map()
  for (const envelope of envelopes) {
    for (const item of envelope.testCases) {
      const key = `${envelope.component}:${envelope.run.id}:${envelope.run.attempt}:${item.fingerprint}:${item.state}`
      unique.set(key, { ...item, component: envelope.component, run: envelope.run })
    }
  }
  const grouped = new Map()
  for (const item of unique.values()) {
    const rows = grouped.get(item.fingerprint) ?? []
    rows.push(item)
    grouped.set(item.fingerprint, rows)
  }
  const ownership = componentOwners()
  return [...grouped.entries()].map(([fingerprint, rows]) => {
    rows.sort((a, b) => Date.parse(a.run.observedAt) - Date.parse(b.run.observedAt))
    const executed = rows.filter(item => item.state !== 'skipped')
    const failures = executed.filter(item => item.state === 'failed')
    const commitStates = new Map()
    for (const item of executed) {
      const states = commitStates.get(item.run.commit) ?? new Set()
      states.add(item.state)
      commitStates.set(item.run.commit, states)
    }
    const sameCommitTransitions = [...commitStates.values()].filter(states => states.has('passed') && states.has('failed')).length
    const last = rows.at(-1)
    return {
      fingerprint, component: last.component, kind: last.kind, classname: last.classname, name: last.name,
      // This is a verified path to the test definition, when a JVM report can provide one. It
      // is intentionally not a claimed production dependency or a test-selection recommendation.
      testDefinitionPath: last.testDefinitionPath ?? null,
      owner: ownership.owners.get(last.component) ?? ownership.fallback,
      state: sameCommitTransitions > 0 ? 'flaky' : last.state === 'failed' ? 'failing' : last.state === 'skipped' ? 'skipped' : 'stable',
      lastState: last.state, observations: rows.length,
      failureRate: executed.length ? Math.round(failures.length * 10_000 / executed.length) / 100 : null,
      averageDurationMs: Math.round(rows.reduce((sum, item) => sum + item.durationMs, 0) / rows.length),
      wastedDurationMs: failures.reduce((sum, item) => sum + item.durationMs, 0),
      sameCommitTransitions, lastObservedAt: last.run.observedAt,
    }
  }).sort((a, b) => {
    const priority = { flaky: 0, failing: 1, skipped: 2, stable: 3 }
    return priority[a.state] - priority[b.state] || b.wastedDurationMs - a.wastedDurationMs || a.name.localeCompare(b.name)
  }).slice(0, 2000)
}

function testImpact(currentEnvelopes) {
  // Do not infer "unaffected" from a definition path or a passing suite. The current contract
  // intentionally exposes exactly what is known: source provenance exists for some JVM tests,
  // while a verified test-to-production mapping has not been collected yet (#7207).
  const retained = currentEnvelopes.map(item => item?.testImpact)
    .filter(item => item?.schemaVersion === 1)
  const fullyDeclared = currentEnvelopes.length > 0 && retained.length === currentEnvelopes.length
  return {
    schemaVersion: 1,
    mode: 'shadow',
    mappingState: 'unknown',
    selectionState: 'unavailable',
    declaredByAllRetainedRuns: fullyDeclared,
    detail: fullyDeclared
      ? 'Every retained run explicitly reports that no verified test-to-production mapping was collected. Full suites remain authoritative.'
      : 'One or more retained runs predate the impact contract. No test-to-production mapping is assumed; full suites remain authoritative.',
  }
}

function ratio(covered, missed) {
  const total = covered + missed
  return total === 0 ? null : Math.round((covered / total) * 10_000) / 100
}

function coverage(component) {
  const candidates = [
    path.join(repo, component, 'build', 'reports', 'kover', 'report.xml'),
    path.join(repo, component, 'build', 'reports', 'kover', 'xml', 'report.xml'),
  ]
  const file = candidates.find(exists)
  if (!file) return {
    state: 'not-run', observedAt: null, source: null,
    lines: { covered: 0, missed: 0, percentage: null },
    branches: { covered: 0, missed: 0, percentage: null },
  }
  const xml = fs.readFileSync(file, 'utf8')
  const counter = type => {
    const matches = [...xml.matchAll(new RegExp(`<counter\\s+type="${type}"\\s+missed="(\\d+)"\\s+covered="(\\d+)"\\s*/>`, 'g'))]
    const match = matches.at(-1)
    const missed = Number(match?.[1] ?? 0)
    const covered = Number(match?.[2] ?? 0)
    return { covered, missed, percentage: ratio(covered, missed) }
  }
  const at = observedAt(file)
  return {
    state: stateFrom(0, 1, at), observedAt: at, source: path.relative(repo, file),
    lines: counter('LINE'), branches: counter('BRANCH'),
  }
}

function moneyPathComponents() {
  const file = path.join(repo, 'openbank-libs', 'governance', 'rules.yaml')
  try {
    const raw = parseYaml(fs.readFileSync(file, 'utf8'))
    return new Set(raw?.money_path_services ?? raw?.services?.money_path ?? [])
  } catch (error) {
    warnings.push(`money-path inventory unavailable: ${error.message}`)
    return new Set()
  }
}

function contracts() {
  const quality = readJson(path.join(repo, 'openbank-admin-ui', 'quality-report.json'))
  if (quality?.contracts) return quality.contracts.map(item => ({
    consumer: item.consumer, provider: item.provider, pactFile: item.pactFile,
    state: item.status === 'pending' ? 'unknown' : freshnessAwareState(item.status, item.verifiedAt),
    observedAt: item.verifiedAt ?? null, interactions: item.interactions?.length ?? 0,
    verificationDetail: item.status === 'pending'
      ? 'Pact Broker provider-verification verdict was unavailable when this immutable deployment snapshot was built. This is not a passing result.'
      : 'Verified by the Pact Broker provider-verification verdict retained in this deployment snapshot.',
  }))
  const dir = path.join(repo, 'pacts')
  if (!exists(dir)) return []
  return fs.readdirSync(dir).filter(name => name.endsWith('.json')).flatMap(name => {
    const pact = readJson(path.join(dir, name))
    return pact ? [{
      consumer: pact.consumer?.name ?? 'unknown', provider: pact.provider?.name ?? 'unknown',
      pactFile: name, state: 'unknown', observedAt: null, interactions: pact.interactions?.length ?? 0,
      verificationDetail: 'No Pact Broker verification snapshot was bundled. This is not a passing result.',
    }] : []
  })
}

async function mutations(components) {
  const result = []
  for (const component of components) {
    const file = path.join(repo, component, 'build', 'reports', 'pitest', 'mutations.xml')
    if (!exists(file)) continue
    const parsed = await parseStringPromise(fs.readFileSync(file, 'utf8'), { explicitArray: true })
    const items = parsed?.mutations?.mutation ?? []
    const status = name => items.filter(item => item.$?.status === name).length
    const killed = status('KILLED')
    const survived = status('SURVIVED')
    const noCoverage = status('NO_COVERAGE')
    const at = observedAt(file)
    const mutationRun = readJson(path.join(path.dirname(file), 'test-intelligence-run.json'))
    const specialized = mutationRun?.specializedEvidence?.find(item => item.kind === 'mutation')
    const provenance = safeRun(mutationRun?.run, `mutation:${component}`)
    result.push({
      component, state: specialized
        ? freshnessAwareState(specialized.state, mutationRun?.run?.observedAt ?? at)
        : stateFrom(0, items.length, at), observedAt: mutationRun?.run?.observedAt ?? at,
      total: items.length, killed, survived, noCoverage,
      score: items.length ? Math.round((killed / items.length) * 10_000) / 100 : null,
      ...(provenance ? { run: provenance } : {}),
    })
  }
  return result
}

function performance() {
  const catalogFile = path.join(repo, 'perf', 'scenarios.yaml')
  const catalog = fs.existsSync(catalogFile)
    ? parseYaml(fs.readFileSync(catalogFile, 'utf8'))?.scenarios ?? []
    : []
  const plans = new Map(catalog.map(item => [item.id, item]))
  const definitions = [
    ...allFiles(path.join(repo, 'perf', 'k6'), file => file.endsWith('.js')),
    ...fs.readdirSync(repo, { withFileTypes: true })
      .filter(entry => entry.isDirectory() && entry.name.startsWith('openbank-'))
      .flatMap(entry => allFiles(path.join(repo, entry.name, 'src', 'test', 'k6'), file => file.endsWith('.js'))),
  ]
  const definitionsByComponent = new Map()
  for (const file of definitions) {
    const component = path.relative(repo, file).split(path.sep)[0]
    if (!component.startsWith('openbank-')) continue
    definitionsByComponent.set(component, (definitionsByComponent.get(component) ?? 0) + 1)
  }
  const summaries = allFiles(path.join(repo, 'openbank-admin-ui', 'perf-artifacts'), file => file.endsWith('-summary.json'))
  // A no-target baseline has a versioned envelope but deliberately no k6 summary.
  // It is still evidence: the UI must distinguish that outcome from a lost artifact.
  const runSidecars = allFiles(path.join(repo, 'openbank-admin-ui', 'perf-artifacts'), file => file.endsWith('-summary.json.run.json'))
  return definitions.map(file => {
    const raw = fs.readFileSync(file, 'utf8')
    const thresholds = (raw.match(/thresholds\s*:/g) ?? []).length
    const relative = path.relative(repo, file).split(path.sep)
    const component = relative[0].startsWith('openbank-') ? relative[0] : null
    const localId = path.basename(file, '.js')
    const plan = plans.get(localId)
    const id = component ? `${component}-${localId.replace(/^openbank-/, '')}` : localId
    const matchesScenario = candidate => {
      const name = path.basename(candidate)
      // perf-gate names its artifact after the service, not the script.  That fallback is
      // unambiguous only while the service declares exactly one k6 scenario; otherwise leaving
      // the result not-run is safer than attaching one scenario's latency to another.
      const singleScenarioServiceSummary = component
        && definitionsByComponent.get(component) === 1
        && name === `${component}-summary.json`
      return name.includes(id) || name.includes(localId) || singleScenarioServiceSummary
    }
    const summaryFile = summaries.find(matchesScenario)
    const runSidecar = runSidecars.find(matchesScenario)
    const summary = summaryFile ? readJson(summaryFile) : null
    const summaryMeta = summaryFile ? readJson(`${summaryFile}.meta.json`) : null
    // perf-gate writes sibling <service>-summary.json and <service>-run.json files.
    // Keep the former sidecar spelling as a fallback for any older retained artifact.
    const performanceRun = summaryFile
      ? readJson(summaryFile.replace(/-summary\.json$/, '-run.json')) ?? readJson(`${summaryFile}.run.json`)
      : readJson(runSidecar)
    const specialized = performanceRun?.specializedEvidence?.find(item => item.kind === 'performance')
    const thresholdResults = summary
      ? Object.values(summary.metrics ?? {}).flatMap(metric => Object.values(metric?.thresholds ?? {}))
      : []
    // k6 summary-export uses bare booleans with inverted-looking polarity: true means
    // crossed/breached, false means passed. The object form is kept for compatible fixtures.
    const failed = thresholdResults.filter(result => result === true || result?.ok === false).length
    const number = value => typeof value === 'number' && Number.isFinite(value) ? value : null
    const ratePercent = value => {
      const rate = number(value)
      return rate === null ? null : Math.round(rate * 10_000) / 100
    }
    const metrics = summary ? {
      p95Ms: number(summary.metrics?.http_req_duration?.values?.['p(95)'] ?? summary.metrics?.http_req_duration?.['p(95)']),
      errorRatePercent: ratePercent(summary.metrics?.http_req_failed?.values?.rate ?? summary.metrics?.http_req_failed?.value),
      checkPassRatePercent: ratePercent(summary.metrics?.checks?.values?.rate ?? summary.metrics?.checks?.value),
      requests: number(summary.metrics?.http_reqs?.values?.count ?? summary.metrics?.http_reqs?.count),
    } : undefined
    const at = summaryFile ? observedAt(summaryFile) : null
    const provenance = safeRun(performanceRun?.run ?? summaryMeta?.run, `performance:${id}`)
    return {
      id, component,
      state: specialized
        ? freshnessAwareState(specialized.state, performanceRun?.run?.observedAt ?? at)
        : (summary ? stateFrom(failed, 1, at) : 'not-run'),
      observedAt: performanceRun?.run?.observedAt ?? at,
      source: path.relative(repo, file), thresholds,
      ...(plan ? { plan: {
        executionMode: plan.execution_mode,
        safetyBoundary: plan.safety_boundary,
        targetSchedule: plan.target_schedule ?? null,
        baselineReport: plan.baseline_report ?? null,
        blocker: plan.blocker ?? null,
      } } : {}),
      ...(metrics ? { metrics } : {}),
      detail: specialized?.detail ?? (summary
        ? `${thresholdResults.length} threshold result(s), ${failed} breached`
        : 'Scenario is declared; the latest k6 run artifact is not bundled into this image.'),
      ...(provenance ? { run: provenance } : {}),
    }
  })
}

function syntheticJourneys() {
  const file = path.join(repo, 'openbank-libs', 'governance', 'journeys.yaml')
  try {
    const raw = parseYaml(fs.readFileSync(file, 'utf8'))
    const latestCi = new Map()
    const history = allFiles(path.join(repo, 'openbank-admin-ui', 'test-run-history'), candidate => candidate.endsWith('.json'))
      .map(readJson).filter(item => item?.schemaVersion === 1 && item?.run)
      .sort((a, b) => Date.parse(a.run.observedAt) - Date.parse(b.run.observedAt))
    for (const envelope of history) {
      const provenance = safeRun(envelope.run, `synthetic:${envelope.component ?? 'unknown'}`)
      for (const evidence of envelope.specializedEvidence ?? []) {
        if (evidence.kind !== 'synthetic' || !evidence.source?.startsWith('journey:')) continue
        latestCi.set(evidence.source.slice('journey:'.length), {
          state: freshnessAwareState(evidence.state, envelope.run.observedAt), observedAt: envelope.run.observedAt,
          detail: evidence.detail ?? 'Synthetic run retained without detail.',
          ...(provenance ? { run: provenance } : {}),
        })
      }
    }
    return (raw?.journeys ?? []).map(item => ({
      id: item.id, title: item.name ?? item.title ?? item.id, status: item.status,
      capability: item.capability ?? '',
      state: item.status === 'active' ? 'unknown' : 'blocked', severity: item.severity,
      executor: item.workflow ? 'github-actions' : 'kubernetes-cronjob',
      schedule: item.schedule ?? item.target_schedule ?? null, environment: item.environment ?? null,
      covers: item.covers ?? item.covered_services ?? [],
      falsifies: item.falsification ?? '', blocker: item.blocked_by ?? null,
      runtimeNote: item.runtime_note ?? null,
      ...(latestCi.has(item.id) ? { ci: latestCi.get(item.id) } : {}),
    }))
  } catch (error) {
    warnings.push(`synthetic journey catalogue unavailable: ${error.message}`)
    return []
  }
}

function journeyCoverage(journeys) {
  const rules = parseYaml(fs.readFileSync(path.join(repo, 'openbank-libs', 'governance', 'rules.yaml'), 'utf8'))
  const catalog = parseYaml(fs.readFileSync(path.join(repo, 'openbank-libs', 'governance', 'journeys.yaml'), 'utf8'))
  const moneyPath = rules?.money_path_services ?? []
  const activeCoverage = new Map()
  for (const journey of journeys.filter(item => item.status === 'active')) {
    for (const component of journey.covers) {
      activeCoverage.set(component, [...(activeCoverage.get(component) ?? []), journey.id])
    }
  }
  const accountability = catalog?.money_path_accountability ?? {}
  const defaultReason = accountability.default_blocker ?? null
  const reasons = new Map((accountability.services ?? []).map(item => [item.service, item.note ?? item.blocked_by ?? defaultReason]))
  const services = moneyPath.map(component => ({
    component,
    state: activeCoverage.has(component) ? 'covered' : 'unwatched',
    journeys: activeCoverage.get(component) ?? [],
    reason: activeCoverage.has(component) ? null : reasons.get(component) ?? null,
  }))
  return {
    moneyPathTotal: moneyPath.length,
    activelyCovered: services.filter(item => item.state === 'covered').length,
    explicitlyUnwatched: services.filter(item => item.state === 'unwatched' && item.reason).length,
    services,
  }
}

function mobileClientRuns() {
  const evidenceDir = path.join(repo, 'openbank-admin-ui', 'client-test-evidence')
  const mobileFiles = allFiles(evidenceDir, file => path.basename(file).startsWith('openbank-app-') && file.endsWith('.json'))
  return mobileFiles.map(readJson)
    .filter(item => item?.schemaVersion === 1 && item?.component === 'openbank-app' && item?.run)
    .sort((a, b) => Date.parse(b.run?.observedAt ?? 0) - Date.parse(a.run?.observedAt ?? 0))
}

function clientEvidenceState(suite, observedAt) {
  // A private-client artifact is immutable CI evidence, but it is still only
  // useful while it is recent.  Do not turn a recorded failure into "stale":
  // the failure remains the more important operator verdict.
  return freshnessAwareState(suite.state, observedAt)
}

async function clientExperiences() {
  const mobileRuns = mobileClientRuns()
  // Mobile lanes complete independently.  Select the latest *execution of each
  // evidence kind*, rather than treating a later connected-device artifact as a
  // replacement for the earlier unit/visual artifact from the same app build.
  // This preserves provenance and prevents an E2E run from visually erasing
  // passing deterministic checks.
  const latestMobile = mobileRuns[0]
  const latestMobileSuites = new Map()
  for (const run of mobileRuns) {
    for (const suite of run.suites ?? []) {
      if (!latestMobileSuites.has(suite.kind)) latestMobileSuites.set(suite.kind, { suite, run })
    }
  }
  const appSource = path.join(repo, '.app-src')
  const appSourceAvailable = exists(appSource)
  // A file named RumMonitor is not evidence that it exports a useful, privacy-bounded
  // signal. Verify the closed schema at collection time: source capability stays separate
  // from runtime arrival, and a missing attribute cannot render as a passing exporter.
  const androidSource = readText(path.join(appSource, 'shared/src/androidMain/kotlin/tech/openbank/app/telemetry/RumMonitor.android.kt'))
  const iosSource = readText(path.join(appSource, 'shared/src/iosMain/kotlin/tech/openbank/app/telemetry/RumMonitor.ios.kt'))
  const tracePropagationSource = readText(path.join(appSource, 'shared/src/commonMain/kotlin/tech/openbank/app/telemetry/TraceparentPlugin.kt'))
  const iosPayloadTest = readText(path.join(appSource, 'shared/src/iosTest/kotlin/tech/openbank/app/telemetry/RumMonitorIosTest.kt'))
  const tracePropagationTest = readText(path.join(appSource, 'shared/src/commonTest/kotlin/tech/openbank/app/telemetry/TraceparentPluginTest.kt'))
  const requiredResourceAttributes = ['app.version', 'os.type', 'os.version', 'device.model']
  const sourceHas = (source, values) => values.every(value => source.includes(value))
  const correlationImplemented = sourceHas(tracePropagationSource, ['traceparent', 'x-correlation-id'])
    && sourceHas(tracePropagationTest, ['traceparent', 'x-correlation-id'])
  const androidRum = sourceHas(androidSource, [...requiredResourceAttributes, 'screen.name']) && correlationImplemented
  const iosRum = sourceHas(iosSource, [...requiredResourceAttributes, 'screen.name'])
    && sourceHas(iosPayloadTest, [...requiredResourceAttributes, 'screen.name']) && correlationImplemented
  // Tempo's bounded arrival projection deliberately does not query `os.*`: the current
  // gateway retains such attributes only when a newly built consented client happens to
  // be sampled, and older clients are allowed to omit them. A generic openbank-app trace
  // is therefore not evidence for either Android or iOS individually (ADR-0088 D4).
  const platformRum = (platform, implemented) => ({
    platform,
    capability: implemented ? 'passed' : appSourceAvailable ? 'not-run' : 'unknown',
    runtime: 'unknown',
    detail: implemented
      ? `${platform === 'android' ? 'Android' : 'iOS'} exporter source contains the allow-listed attributes, screen.name and tested trace/correlation propagation; generic Tempo arrival cannot attribute a sampled trace to this OS.`
      : appSourceAvailable
        ? `${platform === 'android' ? 'Android' : 'iOS'} source does not prove the complete allow-listed exporter contract.`
        : 'Client source was not staged for this deployment; platform capability is unknown.',
  })
  const webEvidence = runEnvelope('openbank-admin-ui')?.evidence ?? await junitEvidence('openbank-admin-ui')
  const mobileEvidence = [...latestMobileSuites.values()].map(({ suite: item, run }) => {
    const provenance = safeRun(run.run, `client:${run.component ?? 'openbank-app'}`)
    return {
      kind: item.kind, state: clientEvidenceState(item, run.run?.observedAt ?? null), observedAt: run.run?.observedAt ?? null,
      source: 'openbank-app-test-intelligence:v1', environment: 'ci', durationMs: item.durationMs,
      counts: item.counts, detail: item.detail,
      ...(provenance ? { run: provenance } : {}),
    }
  })
  if (!latestMobile) warnings.push('openbank-app execution artifact is not bundled; mobile test verdict is not inferred from source.')
  return [
    {
      id: 'admin-ui', title: 'Admin UI web', surface: 'web', platforms: ['web'], evidence: webEvidence,
      rum: {
        state: 'not-run', policy: 'rejected', observedAt: null,
        source: null, sampledSpansLast7d: null, errorSpansLast7d: null,
        detail: 'Browser RUM is intentionally rejected for the internal operator console by ADR-0088; Playwright and server-side telemetry remain the evidence path.',
      }, blocker: null,
    },
    {
      id: 'openbank-app', title: 'OpenBank customer app', surface: 'mobile', platforms: ['android', 'ios'], evidence: mobileEvidence,
      rum: {
        state: androidRum || iosRum ? 'unknown' : 'not-run', policy: 'consent-gated', observedAt: null,
        source: null, sampledSpansLast7d: null, errorSpansLast7d: null,
        platforms: [platformRum('android', androidRum), platformRum('ios', iosRum)],
        detail: androidRum || iosRum
          ? `Mobile RUM source contract is verified for ${androidRum ? 'Android' : ''}${androidRum && iosRum ? ' and ' : ''}${iosRum ? 'iOS' : ''}; runtime arrival is intentionally not inferred from a CI artifact.`
          : 'openbank-app source was not staged for this deployment, so RUM implementation status is unknown.',
      }, blocker: latestMobile ? null : 'Latest private-app CI evidence artifact was not available to this deployment.',
    },
  ]
}

async function main() {
  const names = releasedComponents()
  const simulation = 'openbank-simulation'
  const tooling = exists(path.join(repo, simulation)) ? [simulation] : []
  const moneyPath = moneyPathComponents()
  const currentEnvelopes = [...names, ...tooling]
    .map(component => readJson(path.join(repo, component, 'build', 'test-intelligence', 'run.json')))
    .filter(Boolean)
  const components = await Promise.all(names.map(async component => {
    const envelope = runEnvelope(component)
    return {
      component, released: true, moneyPath: moneyPath.has(component),
      evidence: envelope?.evidence ?? await junitEvidence(component),
      coverage: envelope?.coverage ?? coverage(component),
      testInfrastructure: envelope?.testInfrastructure ?? { declared: [], observed: [] },
    }
  }))
  if (exists(path.join(repo, simulation))) {
    const envelope = runEnvelope(simulation)
    components.push({
      component: simulation, released: false, moneyPath: false,
      evidence: envelope?.evidence ?? await junitEvidence(simulation),
      coverage: envelope?.coverage ?? coverage(simulation),
      testInfrastructure: envelope?.testInfrastructure ?? { declared: [], observed: [] },
    })
  }
  const mutationEvidence = await mutations(names)
  for (const item of mutationEvidence) {
    components.find(component => component.component === item.component)?.evidence.push({
      kind: 'mutation', state: item.state, observedAt: item.observedAt,
      source: 'Pitest:mutations.xml', environment: 'ci', detail: `${item.score ?? '—'}% mutation score`,
    })
  }
  const contractEvidence = contracts()
  for (const item of contractEvidence) {
    for (const componentName of new Set([item.consumer, item.provider])) {
      components.find(component => component.component === componentName)?.evidence.push({
        kind: 'contract', state: item.state, observedAt: item.observedAt,
        source: `Pact:${item.pactFile}`, environment: 'ci',
        detail: `${item.consumer} -> ${item.provider} (${item.interactions} interactions)`,
      })
    }
  }
  const performanceEvidence = performance()
  for (const item of performanceEvidence) {
    if (!item.component) continue
    components.find(component => component.component === item.component)?.evidence.push({
      kind: 'performance', state: item.state, observedAt: item.observedAt,
      source: `k6:${item.source}`, environment: item.id === 'money-path-smoke' ? 'sandbox' : 'ci',
      detail: item.detail,
    })
  }
  const synthetic = syntheticJourneys()
  const syntheticCoverage = journeyCoverage(synthetic)
  const clientExperience = await clientExperiences()
  const testCases = testCaseHistory(currentEnvelopes)
  const impact = testImpact(currentEnvelopes)
  const failingEvidence = components.flatMap(item => item.evidence).filter(item => item.state === 'failed').length
  const staleEvidence = components.flatMap(item => item.evidence).filter(item => item.state === 'stale').length
  const unknownEvidence = components.flatMap(item => item.evidence).filter(item => item.state === 'unknown').length
  const unresolvedEvidence = components.flatMap(item => item.evidence)
    .filter(item => ['unknown', 'not-run', 'blocked'].includes(item.state)).length
  const missingEvidence = components.filter(item => item.evidence.length === 0).length
  const historyDir = path.join(repo, 'openbank-admin-ui', 'test-intelligence-history')
  const historicalReports = allFiles(historyDir, file => file.endsWith('.json'))
    .map(readJson).filter(item => item?.collectedAt && item?.totals)
    .map(item => ({ collectedAt: item.collectedAt, ...item.totals }))
  const currentPoint = { collectedAt: collectedAt.toISOString(), components: components.length,
    componentsWithExecutionEvidence: components.filter(item => item.evidence.length > 0).length,
    failingEvidence, missingEvidence, staleEvidence, unknownEvidence, unresolvedEvidence }
  const history = [...historicalReports, currentPoint]
    .sort((a, b) => Date.parse(a.collectedAt) - Date.parse(b.collectedAt))
    .filter((item, index, all) => index === 0 || item.collectedAt !== all[index - 1].collectedAt)
    .slice(-30)
  const serviceRunEnvelopes = [
    ...allFiles(path.join(repo, 'openbank-admin-ui', 'test-run-history'), file => file.endsWith('.json')).map(readJson),
    ...currentEnvelopes,
  ].filter(item => item?.schemaVersion === 1 && item?.run && item?.component)
  const uniqueServiceRuns = new Map(serviceRunEnvelopes.map(item => [
    `${item.component}:${item.run.id}:${item.run.attempt}`, item,
  ]))
  const serviceRunHistory = [...uniqueServiceRuns.values()]
    .map(item => {
      const run = safeRun(item.run, `history:${item.component}`)
      return run ? { component: item.component, run,
        states: Object.fromEntries([
          ...(item.suites ?? []).map(suite => [suite.kind, suite.state]),
          ...(item.specializedEvidence ?? []).map(evidence => [evidence.kind, evidence.state]),
        ]),
        infrastructureStarted: (item.testInfrastructure?.observed ?? []).filter(event => event.lifecycle === 'started').length,
        infrastructureStopped: (item.testInfrastructure?.observed ?? []).filter(event => event.lifecycle === 'stopped').length } : null
    }).filter(Boolean)
  const clientRunHistory = mobileClientRuns().map(item => {
    const run = safeRun(item.run, `history:${item.component ?? 'openbank-app'}`)
    return run ? {
      component: item.component, run,
      states: Object.fromEntries((item.suites ?? []).map(suite => [suite.kind, suite.state])),
      infrastructureStarted: 0, infrastructureStopped: 0,
    } : null
  }).filter(Boolean)
  const runHistory = [...serviceRunHistory, ...clientRunHistory]
    .sort((a, b) => Date.parse(b.run.observedAt) - Date.parse(a.run.observedAt)).slice(0, 500)
  const report = {
    schemaVersion: 1, collectedAt: collectedAt.toISOString(), components,
    contracts: contractEvidence, mutations: mutationEvidence, performance: performanceEvidence,
    syntheticJourneys: synthetic, journeyCoverage: syntheticCoverage, history, runHistory, testCases, testImpact: impact,
    clientExperiences: clientExperience,
    totals: {
      components: components.length,
      componentsWithExecutionEvidence: components.filter(item => item.evidence.length > 0).length,
      moneyPathComponents: components.filter(item => item.moneyPath).length,
      failingEvidence, missingEvidence, staleEvidence, unknownEvidence, unresolvedEvidence,
    },
    warnings,
  }
  fs.writeFileSync(out, JSON.stringify(report, null, 2))
  console.log(`[collect-test-intelligence] ${report.totals.componentsWithExecutionEvidence}/${report.totals.components} components with execution evidence -> ${out}`)
}

if (args.includes('--self-test')) {
  const assert = (actual, expected, label) => {
    if (actual !== expected) throw new Error(`${label}: expected ${expected}, got ${actual}`)
  }
  assert(classifyJUnitEvidence('test', 'com.openbank.payment.PaymentApiIT', 'openbank-payment-service'), 'integration', 'IT precedence')
  assert(classifyJUnitEvidence('test', 'com.openbank.customer.OnboardingE2E', 'openbank-customer-edge'), 'e2e', 'named E2E suite')
  assert(classifyJUnitEvidence('test', 'com.openbank.UnitTest', 'openbank-service'), 'unit', 'ordinary suite')
  assert(classifyJUnitEvidence('test', 'com.openbank.simulation.DstSimulationTest', 'openbank-simulation'), 'simulation', 'simulation suite')
  console.log('collect-test-intelligence self-test: classification evidence is preserved')
} else {
  main().catch(error => { console.error(error); process.exit(1) })
}
