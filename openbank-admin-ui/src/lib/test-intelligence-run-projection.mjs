// SPDX-License-Identifier: Apache-2.0

/**
 * The one projection from a Test Intelligence run envelope (`<svc>/build/test-intelligence/run.json`)
 * to the evidence rows of a `ComponentTestPosture`. Shared by the fleet collector (bundled
 * snapshot) and the per-commit evidence publisher (#13201), so the store can never serve a
 * posture shaped differently from the one the page already renders. Pure: no file system, no
 * clock — the caller supplies both, which is what lets the publisher and the collector agree.
 */

/**
 * @param {{ collectedAt: Date, staleAfterMs: number, maxFutureSkewMs?: number, warn?: (message: string) => void }} options
 */
export function createRunProjection({ collectedAt, staleAfterMs, maxFutureSkewMs = 5 * 60_000, warn = () => {} }) {
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
        warn(`untrusted run URL omitted: ${source}`)
        unsafeRunWarnings.add(source)
      }
      return undefined
    }
    return { ...run, id: String(run.id), attempt: Number(run.attempt), url: String(run.url) }
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

  const thresholdObservation = summary => {
    if (!summary || typeof summary !== 'object' || Array.isArray(summary)
        || !summary.metrics || typeof summary.metrics !== 'object' || Array.isArray(summary.metrics)) {
      return { valid: false, count: 0, failed: 0 }
    }
    const results = []
    let malformed = false
    for (const metric of Object.values(summary.metrics)) {
      if (!metric || typeof metric !== 'object' || Array.isArray(metric)
          || !Object.hasOwn(metric, 'thresholds')) continue
      if (!metric.thresholds || typeof metric.thresholds !== 'object' || Array.isArray(metric.thresholds)) {
        malformed = true
        continue
      }
      const metricResults = Object.values(metric.thresholds)
      if (metricResults.length === 0) malformed = true
      results.push(...metricResults)
    }
    const valid = !malformed && results.length > 0 && results.every(result =>
      typeof result === 'boolean'
        || (result && typeof result === 'object' && !Array.isArray(result) && typeof result.ok === 'boolean'))
    // Never let one malformed sibling erase a concrete breached threshold.
    const failed = results.filter(result =>
      result === true || (result && typeof result === 'object' && !Array.isArray(result) && result.ok === false)).length
    return { valid, count: results.length, failed }
  }

  const retainedThresholdObservation = value => {
    if (!value || typeof value !== 'object' || Array.isArray(value)
        || !Number.isSafeInteger(value.evaluated) || value.evaluated <= 0
        || !Number.isSafeInteger(value.breached) || value.breached < 0
        || value.breached > value.evaluated) return undefined
    return { valid: true, count: value.evaluated, failed: value.breached }
  }

  const thresholdBackedState = (claimedState, rawObservation, retainedObservation) => {
    // Recorded failures remain actionable even if the optional summary artifact was lost.
    // A pass is different: prose cannot prove that the producer saw typed outcomes (the legacy
    // producer rendered malformed objects as "0 breached"), so it needs the raw summary until
    // the versioned envelope carries a structured denominator.
    const retained = retainedThresholdObservation(retainedObservation)
    if (rawObservation?.failed > 0 || retained?.failed > 0) return 'failed'
    if (claimedState === 'failed') return 'failed'
    if (!['passed', 'stale'].includes(claimedState)) return claimedState
    const observation = rawObservation ?? retained
    if (!observation?.valid) return 'unknown'
    return claimedState
  }

  // #7451. Re-derive `matched` from the two SHAs rather than trusting the envelope's boolean: an
  // attestation whose verdict disagrees with its own evidence is dropped, not rendered.
  const SHA = /^[0-9a-f]{7,40}$/
  const validBuildAttestation = value => Boolean(value) && typeof value === 'object'
    && typeof value.requestedSha === 'string' && SHA.test(value.requestedSha)
    && (value.observedSha === null || (typeof value.observedSha === 'string' && SHA.test(value.observedSha)))
    && typeof value.matched === 'boolean'
    && value.matched === (value.observedSha !== null
      && (value.observedSha.startsWith(value.requestedSha) || value.requestedSha.startsWith(value.observedSha)))

  const retainActionableFailure = (previous, next, validRecovery) =>
    previous?.state === 'failed' && next.state !== 'failed' && !validRecovery ? previous : next

  const normalizeThresholdEvidence = evidence => {
    if (evidence.kind !== 'performance' && (evidence.kind !== 'synthetic' || evidence.variant)) return evidence
    const retained = retainedThresholdObservation(evidence.thresholdResults)
    const { thresholdResults: _untrustedThresholdResults, ...rest } = evidence
    return {
      ...rest,
      state: thresholdBackedState(evidence.state, undefined, evidence.thresholdResults),
      ...(retained ? { thresholdResults: { evaluated: retained.count, breached: retained.failed } } : {}),
    }
  }

  /**
   * Project a parsed run envelope for `component`. `fallbackObservedAt` stands in when the
   * envelope carries no `run.observedAt` (the collector passes the file mtime). Returns null
   * for an envelope that is not this component's, or not a known schema version.
   */
  function projectRunEnvelope(run, component, fallbackObservedAt = null) {
    if (!run || ![1, 2].includes(run.schemaVersion) || run.component !== component || !Array.isArray(run.suites)) return null
    const at = run.run?.observedAt ?? fallbackObservedAt
    const provenance = safeRun(run.run, component)
    return {
      evidence: [...run.suites.map(suite => ({
        kind: suite.kind, state: freshnessAwareState(suite.state, at), observedAt: at,
        source: `test-intelligence-run:v${run.schemaVersion}`, environment: 'ci', durationMs: suite.durationMs,
        counts: { discovered: suite.discovered, executed: suite.executed, passed: suite.passed,
          failed: suite.failed, skipped: suite.skipped, errors: suite.errors }, run: provenance,
        diagnostics: (run.diagnostics ?? []).filter(item => item.suiteKind === suite.kind
          && provenance?.url && item.url === `${provenance.url}#artifacts`).map(item => ({
          kind: item.kind, name: item.name, url: item.url, retentionDays: item.retentionDays,
          access: item.access, mayContainSensitiveData: item.mayContainSensitiveData,
        })),
      })), ...(run.specializedEvidence ?? []).map(item => {
        const normalized = normalizeThresholdEvidence(item)
        return {
          kind: normalized.kind,
          state: freshnessAwareState(normalized.state, at),
          observedAt: at,
          source: item.source, environment: 'ci', detail: item.detail, run: provenance,
          ...(normalized.variant ? { variant: normalized.variant } : {}),
          ...(validBuildAttestation(normalized.buildAttestation) ? { buildAttestation: normalized.buildAttestation } : {}),
          ...(normalized.thresholdResults ? { thresholdResults: normalized.thresholdResults } : {}),
        }
      })],
      coverage: run.coverage ? {
        state: stateFrom(0, 1, at),
        observedAt: at, lines: run.coverage.lines,
        branches: run.coverage.branches, source: `test-intelligence-run:v${run.schemaVersion}`,
      } : null,
      testImpact: run.testImpact ?? null,
      testInfrastructure: run.testInfrastructure ?? { declared: [], observed: [] },
    }
  }

  return {
    safeRun, stateFrom, freshnessAwareState, thresholdObservation, retainedThresholdObservation,
    thresholdBackedState, validBuildAttestation, retainActionableFailure, normalizeThresholdEvidence,
    projectRunEnvelope,
  }
}

/**
 * Length of the commit prefix auto-deploy writes into an image tag (`sandbox-${GITHUB_SHA::8}`
 * in .github/workflows/auto-deploy.yml). The evidence store is keyed by EXACTLY that prefix,
 * because the reader derives the commit from the running image tag and asks for nothing else.
 * A test reads the workflow, so changing one without the other fails CI.
 */
export const DEPLOY_TAG_SHA_LENGTH = 8

const FULL_SHA = /^[0-9a-f]{40}$/
const COMPONENT = /^openbank-[a-z0-9-]{2,60}$/

/** `<component>/<commit>/test-intelligence.json` for a full 40-hex commit; throws on anything else. */
export function evidenceStoreKey(component, fullSha) {
  if (!COMPONENT.test(String(component))) throw new Error(`invalid component: ${component}`)
  if (!FULL_SHA.test(String(fullSha))) throw new Error(`expected a full 40-hex commit, got: ${fullSha}`)
  return `${component}/${fullSha.slice(0, DEPLOY_TAG_SHA_LENGTH)}/test-intelligence.json`
}

/**
 * The `ComponentTestPosture` the evidence store serves for one component at one commit.
 * Refuses (throws) rather than publish something the reader would reject or, worse, accept
 * about the wrong build: the envelope must be this component's and this commit's run, and
 * every projected observation that names a run must name this commit.
 *
 * `commit` is the commit the tests RAN on. `keyCommit` (default: `commit`) is the running
 * image's commit the object is stored under. They may differ only when the caller has PROVEN
 * the two byte-identical in every build input of the service (pact-version-tree-equivalent.sh)
 * and says so with `equivalence: 'tree-equivalent'`; the posture then carries that claim in
 * `evidenceSource` so the reader can tell "tested on this commit" from "tested on an
 * equivalent one".
 */
export function componentPostureFromRun(run, {
  component, commit, keyCommit = commit, equivalence = 'identical', released, moneyPath, collectedAt, staleAfterMs,
}) {
  evidenceStoreKey(component, commit)
  evidenceStoreKey(component, keyCommit)
  if (equivalence === 'identical' && keyCommit !== commit) throw new Error(`identical evidence must be keyed by its own commit, not ${keyCommit}`)
  if (equivalence !== 'identical' && equivalence !== 'tree-equivalent') throw new Error(`unknown equivalence: ${equivalence}`)
  if (run?.run?.commit !== commit) throw new Error(`envelope is for commit ${run?.run?.commit ?? 'none'}, not ${commit}`)
  const warnings = []
  const projection = createRunProjection({ collectedAt, staleAfterMs, warn: message => warnings.push(message) })
  const envelope = projection.projectRunEnvelope(run, component, null)
  if (!envelope) throw new Error(`run envelope is not a v1/v2 envelope for ${component}`)
  const foreign = envelope.evidence.find(item => item.run && item.run.commit !== commit)
  if (foreign) throw new Error(`observation ${foreign.kind} names commit ${foreign.run.commit}, not ${commit}`)
  return {
    posture: {
      component, released: Boolean(released), moneyPath: Boolean(moneyPath),
      evidence: envelope.evidence,
      coverage: envelope.coverage ?? {
        state: 'not-run', observedAt: null, source: null,
        lines: { covered: 0, missed: 0, percentage: null },
        branches: { covered: 0, missed: 0, percentage: null },
      },
      testInfrastructure: envelope.testInfrastructure,
      evidenceSource: { commit, equivalence },
    },
    warnings,
  }
}
