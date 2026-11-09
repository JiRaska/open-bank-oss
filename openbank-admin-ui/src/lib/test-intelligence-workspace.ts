// SPDX-License-Identifier: Apache-2.0
import type { ComponentTestPosture, EvidenceKind, EvidenceState, TestIntelligenceReport } from './types/test-intelligence'
import { aggregateEvidenceState } from './test-intelligence-state'

export const TEST_KINDS: EvidenceKind[] = ['unit', 'integration', 'contract', 'e2e', 'trace', 'mutation', 'simulation', 'performance', 'synthetic', 'visual']
export type TestWorkspaceSection = 'components' | 'attention' | 'runs' | 'learn'
export interface TestSelection { section: TestWorkspaceSection; component: string; kind: EvidenceKind | ''; test: string }

export function readTestSelection(search: string): TestSelection {
  const params = new URLSearchParams(search)
  const section = params.get('section')
  const kind = params.get('kind')
  return {
    section: section === 'attention' || section === 'runs' || section === 'learn' ? section : 'components',
    component: (params.get('component') ?? '').slice(0, 100),
    kind: TEST_KINDS.includes(kind as EvidenceKind) ? kind as EvidenceKind : '',
    test: (params.get('test') ?? '').slice(0, 160),
  }
}

export function testSelectionUrl(selection: TestSelection, search = ''): string {
  const params = new URLSearchParams(search)
  for (const [key, value] of Object.entries(selection)) {
    if (value && !(key === 'section' && value === 'components')) params.set(key, value)
    else params.delete(key)
  }
  return `/system/tests${params.size ? `?${params}` : ''}`
}

/** Applicability is a declared denominator, never guessed from an empty cell. */
export function testCell(report: TestIntelligenceReport, component: ComponentTestPosture, kind: EvidenceKind): {
  state: EvidenceState | 'not-applicable'; observations: number
} {
  const observations = component.evidence.filter(item => item.kind === kind)
  const applicability = report.layerCoverage?.find(layer => layer.kind === kind)?.components.find(row => row.component === component.component)
  if (observations.length === 0 && applicability?.status === 'not-applicable') return { state: 'not-applicable', observations: 0 }
  return { state: aggregateEvidenceState(observations.map(item => item.state), 'unknown'), observations: observations.length }
}

/** A component-level run must never be presented as an individual testcase's run. */
export function trustworthyTestRunUrl(value: string | undefined): string | null {
  if (!value) return null
  try {
    const url = new URL(value)
    return url.protocol === 'https:' && url.hostname === 'github.com' && !url.port && !url.username && !url.password
      && /^\/JiRaska\/open-bank-oss\/actions\/runs\/[1-9][0-9]*(?:\/attempts\/[1-9][0-9]*)?$/.test(url.pathname)
      && !url.search && (!url.hash || url.hash === '#artifacts') ? url.href : null
  } catch { return null }
}

export function testDefinitionUrl(component: string, file: string | null | undefined, commit: string | undefined): string | null {
  if (!/^openbank-[a-z0-9-]+$/.test(component) || !file || !/^[0-9a-f]{40}$/.test(commit ?? '')) return null
  if (!/^(?:src\/test\/|e2e\/)[a-zA-Z0-9_./$\[\]-]+\.(?:kt|java|tsx?|mjs)$/.test(file) || file.split('/').includes('..')) return null
  return `https://github.com/JiRaska/open-bank-oss/blob/${commit}/${component}/${file.split('/').map(encodeURIComponent).join('/')}`
}

/** Component counts overlap deliberately: a historical pass can also lack a required control. */
export function testScopeCounts(report: TestIntelligenceReport) {
  const failed = new Set<string>(), stale = new Set<string>(), missing = new Set<string>()
  for (const component of report.components) {
    if (!component.evidence.length) missing.add(component.component)
    for (const evidence of component.evidence) {
      if (evidence.state === 'failed') failed.add(component.component)
      if (evidence.state === 'stale') stale.add(component.component)
      if (['unknown', 'not-run', 'blocked', 'skipped'].includes(evidence.state)) missing.add(component.component)
    }
  }
  for (const control of report.requiredControls ?? []) {
    if (!control.component) continue
    if (control.state === 'failed') failed.add(control.component)
    else if (control.state === 'stale') stale.add(control.component)
    else if (control.state !== 'passed') missing.add(control.component)
  }
  for (const layer of report.layerCoverage ?? []) for (const row of layer.components) {
    if (row.status === 'missing' || row.status === 'unproven') missing.add(row.component)
  }
  const known = new Set(report.components.map(component => component.component))
  return { failed: [...failed].filter(id => known.has(id)).length, stale: [...stale].filter(id => known.has(id)).length, missing: [...missing].filter(id => known.has(id)).length }
}
