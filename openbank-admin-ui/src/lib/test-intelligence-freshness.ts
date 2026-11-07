// SPDX-License-Identifier: Apache-2.0
import type { EvidenceState, TestIntelligenceReport } from '@/lib/types/test-intelligence'
import { executionEvidenceTotals } from '@/lib/test-intelligence-execution-evidence.mjs'

const evidenceFreshnessMs = () => {
  const days = Number(process.env.OPENBANK_TEST_INTELLIGENCE_STALE_AFTER_DAYS ?? 14)
  return (Number.isFinite(days) && days > 0 ? days : 14) * 86_400_000
}
const MAX_FUTURE_SKEW_MS = 5 * 60_000

export function runtimeFreshnessState(
  state: EvidenceState,
  observedAt: string | null | undefined,
): EvidenceState {
  if (state === 'failed' || state === 'blocked' || state === 'unknown' || state === 'not-run' || state === 'stale') return state
  const observed = Date.parse(observedAt ?? '')
  if (!Number.isFinite(observed)) return 'not-run'
  if (observed - Date.now() > MAX_FUTURE_SKEW_MS) return 'unknown'
  return Date.now() - observed > evidenceFreshnessMs() ? 'stale' : state
}

function currentCodeState(state: EvidenceState, running: TestIntelligenceReport['components'][number]['running']): EvidenceState {
  if (state === 'passed' && running?.runningCommit && running.match !== true && running.storeStatus !== 'hit') return 'unknown'
  return state
}

function staleTestCase<T extends TestIntelligenceReport['testCases'][number]>(item: T, now: number): T {
  if (item.state !== 'stable') return item
  const observed = Date.parse(item.lastObservedAt)
  if (!Number.isFinite(observed) || observed > now + MAX_FUTURE_SKEW_MS
      || now - observed > evidenceFreshnessMs()) return { ...item, state: 'stale' }
  return item
}

export function enforceRuntimeFreshness(report: TestIntelligenceReport, now = Date.now()): TestIntelligenceReport {
  const components = report.components.map(component => {
    const evidence = component.evidence.map(item => ({
      ...item, state: currentCodeState(runtimeFreshnessState(item.state, item.observedAt), component.running),
    }))
    const coverage = {
      ...component.coverage,
      state: currentCodeState(runtimeFreshnessState(component.coverage.state, component.coverage.observedAt), component.running),
    }
    return { ...component, evidence, coverage }
  })
  const evidence = components.flatMap(component => component.evidence)
  const unknownComponentControls = new Set(components.filter(component => component.running?.runningCommit
    && component.running.match !== true && component.running.storeStatus !== 'hit').map(component => component.component))
  const requiredControls = report.requiredControls?.map(control => {
    const freshState = runtimeFreshnessState(control.state, control.observedAt)
    return {
      ...control,
      state: freshState === 'passed' && control.component && unknownComponentControls.has(control.component) ? 'unknown' : freshState,
    }
  })
  const { componentsWithExecutionEvidence, missingEvidence } = executionEvidenceTotals(components)
  return {
    ...report,
    components,
    ...(requiredControls ? { requiredControls } : {}),
    contracts: (report.contracts ?? []).map(item => ({
      ...item, state: runtimeFreshnessState(item.state, item.observedAt),
    })),
    mutations: (report.mutations ?? []).map(item => ({
      ...item, state: runtimeFreshnessState(item.state, item.observedAt),
    })),
    performance: (report.performance ?? []).map(item => ({
      ...item, state: runtimeFreshnessState(item.state, item.observedAt),
    })),
    syntheticJourneys: (report.syntheticJourneys ?? []).map(journey => ({
      ...journey,
      ...(journey.ci ? { ci: {
        ...journey.ci, state: runtimeFreshnessState(journey.ci.state, journey.ci.observedAt),
        ...(journey.ci.variants ? { variants: journey.ci.variants.map(variant => ({
          ...variant,
          state: runtimeFreshnessState(variant.state, variant.observedAt),
        })) } : {}),
      } } : {}),
    })),
    clientExperiences: (report.clientExperiences ?? []).map(client => ({
      ...client,
      evidence: client.evidence.map(item => ({
        ...item, state: runtimeFreshnessState(item.state, item.observedAt),
      })),
    })),
    testCases: (report.testCases ?? []).map(item => staleTestCase(item, now)),
    totals: {
      ...report.totals,
      components: components.length,
      componentsWithExecutionEvidence,
      moneyPathComponents: components.filter(component => component.moneyPath).length,
      failingEvidence: evidence.filter(item => item.state === 'failed').length,
      missingEvidence,
      staleEvidence: evidence.filter(item => item.state === 'stale').length,
      unknownEvidence: evidence.filter(item => item.state === 'unknown').length,
      unresolvedEvidence: evidence.filter(item => ['unknown', 'not-run', 'blocked'].includes(item.state)).length,
      ...(requiredControls ? {
        requiredControls: requiredControls.length,
        requiredControlGaps: requiredControls.filter(control => control.state !== 'passed').length,
      } : {}),
    },
  }
}
