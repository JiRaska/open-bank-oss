// SPDX-License-Identifier: Apache-2.0
import type { EvidenceState, TestIntelligenceReport } from '@/lib/types/test-intelligence'

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

export function enforceRuntimeFreshness(report: TestIntelligenceReport): TestIntelligenceReport {
  const components = report.components.map(component => ({
    ...component,
    evidence: component.evidence.map(item => ({
      ...item, state: runtimeFreshnessState(item.state, item.observedAt),
    })),
    coverage: {
      ...component.coverage,
      state: runtimeFreshnessState(component.coverage.state, component.coverage.observedAt),
    },
  }))
  const evidence = components.flatMap(component => component.evidence)
  return {
    ...report,
    components,
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
    totals: {
      ...report.totals,
      components: components.length,
      componentsWithExecutionEvidence: components.filter(component => component.evidence.length > 0).length,
      moneyPathComponents: components.filter(component => component.moneyPath).length,
      failingEvidence: evidence.filter(item => item.state === 'failed').length,
      missingEvidence: components.filter(component => component.evidence.length === 0).length,
      staleEvidence: evidence.filter(item => item.state === 'stale').length,
      unknownEvidence: evidence.filter(item => item.state === 'unknown').length,
      unresolvedEvidence: evidence.filter(item => ['unknown', 'not-run', 'blocked'].includes(item.state)).length,
    },
  }
}
