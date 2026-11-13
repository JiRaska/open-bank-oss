// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { aggregateEvidenceState } from '@/lib/test-intelligence-state'
import type {
  EvidenceKind, EvidenceState, TestIntelligenceReport,
} from '@/lib/types/test-intelligence'

/**
 * The latest real execution of one test kind for one component.
 *
 * `current` comes from the posture the report is about; `history` comes from a retained CI
 * attempt when the current posture carries no observation of that kind. A history row is
 * shown as what it is — the last time this kind actually ran — never as the current verdict.
 */
export interface LastRun {
  state: EvidenceState
  observedAt: string | null
  source: 'current' | 'history'
}

const NOT_EXECUTED: ReadonlySet<EvidenceState> = new Set(['not-run', 'unknown', 'blocked'])

export function lastRunFor(report: TestIntelligenceReport, component: string, kind: EvidenceKind): LastRun | null {
  const posture = report.components.find(item => item.component === component)
  const current = (posture?.evidence ?? []).filter(item => item.kind === kind && !NOT_EXECUTED.has(item.state))
  if (current.length > 0) {
    const observed = current.map(item => item.observedAt).filter((value): value is string => Boolean(value)).sort()
    return { state: aggregateEvidenceState(current.map(item => item.state)), observedAt: observed.at(-1) ?? null, source: 'current' }
  }
  const retained = report.runHistory
    .filter(point => point.component === component)
    .flatMap(point => {
      const state = point.states[kind]
      return state && !NOT_EXECUTED.has(state) ? [{ state, observedAt: point.run.observedAt }] : []
    })
    .sort((a, b) => a.observedAt.localeCompare(b.observedAt))
  const latest = retained.at(-1)
  return latest ? { ...latest, source: 'history' } : null
}

export function ageSeconds(observedAt: string | null, now: number): number | null {
  if (!observedAt) return null
  const at = Date.parse(observedAt)
  return Number.isFinite(at) ? Math.max(0, Math.floor((now - at) / 1000)) : null
}

/** Compact, language-aware age: "5 min", "3 h", "2 d". */
export function formatRelativeAge(seconds: number | null, language: 'cs' | 'en'): string {
  if (seconds === null) return language === 'cs' ? 'nikdy' : 'never'
  if (seconds < 60) return language === 'cs' ? 'právě teď' : 'just now'
  if (seconds < 3600) return `${Math.floor(seconds / 60)} min`
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} h`
  return `${Math.floor(seconds / 86400)} d`
}

/** Plain-language state names; the raw enum is a developer term, not an operator one. */
export function stateLabel(state: EvidenceState, language: 'cs' | 'en'): string {
  const labels: Record<EvidenceState, [string, string]> = {
    passed: ['prošlo', 'passed'],
    failed: ['selhalo', 'failed'],
    skipped: ['přeskočeno', 'skipped'],
    stale: ['zastaralé', 'stale'],
    blocked: ['blokováno', 'blocked'],
    'not-run': ['bez běhu', 'no run'],
    unknown: ['neznámé', 'unknown'],
  }
  return labels[state][language === 'cs' ? 0 : 1]
}

/** A run older than this is shown as aging, so a once-green suite does not read as current. */
export const STALE_AFTER_SECONDS = 7 * 86400

export interface TestOverview {
  lastRunAt: string | null
  executed: number
  passed: number
  failed: number
  flaky: number
  failingComponents: string[]
  moneyPath: { total: number; allGreen: number }
  componentsRecentlyRun: number
  components: number
  verdict: 'healthy' | 'attention' | 'failing' | 'no-data'
}

/**
 * One audience-neutral summary. Counts only what actually executed; a component with no
 * recent run lowers `componentsRecentlyRun` and is never counted as passed.
 */
export function buildTestOverview(report: TestIntelligenceReport, now: number): TestOverview {
  const timestamps: string[] = []
  let executed = 0
  let passed = 0
  let failed = 0
  const failingComponents: string[] = []
  let componentsRecentlyRun = 0
  let moneyPathGreen = 0
  for (const component of report.components) {
    const ran = component.evidence.filter(item => !NOT_EXECUTED.has(item.state))
    for (const item of ran) {
      if (item.observedAt) timestamps.push(item.observedAt)
      executed += item.counts?.executed ?? 0
      passed += item.counts?.passed ?? 0
      failed += item.counts?.failed ?? 0
    }
    const latest = ran.map(item => ageSeconds(item.observedAt, now)).filter((value): value is number => value !== null)
    if (latest.length > 0 && Math.min(...latest) <= STALE_AFTER_SECONDS) componentsRecentlyRun += 1
    const hasFailure = component.evidence.some(item => item.state === 'failed')
    if (hasFailure) failingComponents.push(component.component)
    if (component.moneyPath && ran.length > 0 && !hasFailure && ran.every(item => item.state === 'passed')) moneyPathGreen += 1
  }
  for (const point of report.runHistory) timestamps.push(point.run.observedAt)
  timestamps.sort()
  const flaky = report.testCases.filter(item => item.state === 'flaky').length
  const moneyPathTotal = report.components.filter(item => item.moneyPath).length
  const verdict: TestOverview['verdict'] = report.components.length === 0 ? 'no-data'
    : failingComponents.length > 0 ? 'failing'
      : componentsRecentlyRun < report.components.length || flaky > 0 || moneyPathGreen < moneyPathTotal ? 'attention'
        : 'healthy'
  return {
    lastRunAt: timestamps.at(-1) ?? null,
    executed, passed, failed, flaky, failingComponents,
    moneyPath: { total: moneyPathTotal, allGreen: moneyPathGreen },
    componentsRecentlyRun, components: report.components.length, verdict,
  }
}
