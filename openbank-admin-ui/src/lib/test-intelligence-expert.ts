// SPDX-License-Identifier: Apache-2.0
import { DOMAIN_GROUPS, isDomainId } from './governance/domains'
import { TEST_KINDS, testCell, testScopeCounts } from './test-intelligence-workspace'
import type { ComponentTestPosture, EvidenceKind, TestIntelligenceReport } from './types/test-intelligence'

export type TestComponentCatalog = Record<string, { domain: string; label: string }>
export type ExpertStatus = 'all' | 'failed' | 'stale' | 'missing' | 'mismatch'
export type ExpertSort = 'priority' | 'component' | 'coverage' | 'oldest'
export interface ExpertSelection {
  query: string; domain: string; status: ExpertStatus; moneyOnly: boolean; sort: ExpertSort
  columns: EvidenceKind[]; page: number; inspect: string; kind: EvidenceKind | ''
}
export const EXPERT_PAGE_SIZE = 25
export const defaultExpertSelection = (): ExpertSelection => ({ query: '', domain: '', status: 'all', moneyOnly: false, sort: 'priority', columns: [...TEST_KINDS], page: 0, inspect: '', kind: '' })

export function readExpertSelection(search: string): ExpertSelection {
  const p = new URLSearchParams(search), value = defaultExpertSelection()
  value.query = (p.get('expertQuery') ?? '').slice(0, 160)
  const domain = p.get('expertDomain') ?? ''
  value.domain = isDomainId(domain) ? domain : ''
  const status = p.get('expertStatus') ?? ''
  value.status = ['failed', 'stale', 'missing', 'mismatch'].includes(status) ? status as ExpertStatus : 'all'
  const sort = p.get('expertSort') ?? ''
  value.sort = ['component', 'coverage', 'oldest'].includes(sort) ? sort as ExpertSort : 'priority'
  value.moneyOnly = p.get('expertMoney') === '1'
  const wanted = new Set((p.get('expertColumns') ?? TEST_KINDS.join(',')).split(','))
  const columns = TEST_KINDS.filter(kind => wanted.has(kind))
  if (columns.length) value.columns = columns
  const page = p.get('expertPage') ?? '0'
  value.page = /^\d{1,4}$/.test(page) ? Number(page) : 0
  const component = p.get('expertInspect') ?? ''
  value.inspect = /^[a-zA-Z0-9_-]{1,100}$/.test(component) ? component : ''
  const kind = p.get('expertKind') ?? ''
  value.kind = TEST_KINDS.includes(kind as EvidenceKind) ? kind as EvidenceKind : ''
  return value
}

/** Own only expert keys: navigation, tenant context and other saved-view parameters survive. */
export function expertSelectionUrl(value: ExpertSelection, search: string): string {
  const p = new URLSearchParams(search)
  const values: Record<string, string> = {
    expertQuery: value.query, expertDomain: value.domain, expertStatus: value.status === 'all' ? '' : value.status,
    expertMoney: value.moneyOnly ? '1' : '', expertSort: value.sort === 'priority' ? '' : value.sort,
    expertColumns: value.columns.length === TEST_KINDS.length ? '' : value.columns.join(','),
    expertPage: value.page ? String(value.page) : '', expertInspect: value.inspect, expertKind: value.kind,
  }
  for (const [key, item] of Object.entries(values)) { if (item) p.set(key, item); else p.delete(key) }
  return `/system/tests${p.size ? `?${p}` : ''}`
}

export interface ExpertRow {
  component: ComponentTestPosture; label: string; domain: string
  cells: Record<EvidenceKind, ReturnType<typeof testCell>>
  scope: ReturnType<typeof testScopeCounts>; latestAt: string | null
}
export function expertRows(report: TestIntelligenceReport, selection: ExpertSelection, catalog: TestComponentCatalog): ExpertRow[] {
  const needle = selection.query.trim().toLocaleLowerCase()
  const rows = report.components.map(component => {
    const ownership = catalog[component.component]?.domain ?? component.domain
    const domain = isDomainId(ownership) ? ownership : 'unclassified'
    const dates = [...component.evidence.map(item => item.observedAt), component.coverage.observedAt, component.running?.latestEvidenceAt]
      .filter((value): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value))).sort((a, b) => Date.parse(b) - Date.parse(a))
    return { component, label: catalog[component.component]?.label ?? component.component, domain,
      cells: Object.fromEntries(TEST_KINDS.map(kind => [kind, testCell(report, component, kind)])) as ExpertRow['cells'],
      scope: testScopeCounts({ ...report, components: [component] }), latestAt: dates[0] ?? null }
  }).filter(row => (!needle || `${row.component.component} ${row.label}`.toLocaleLowerCase().includes(needle))
    && (!selection.domain || row.domain === selection.domain) && (!selection.moneyOnly || row.component.moneyPath)
    && (selection.status === 'all' || selection.status === 'mismatch' ? selection.status === 'all' || row.component.running?.match === false : row.scope[selection.status] > 0))
  const priority = (row: ExpertRow) => row.scope.failed ? 4 : row.scope.missing ? 3 : row.scope.stale ? 2 : row.component.running?.match === false ? 1 : 0
  return rows.sort((a, b) => {
    let order = 0
    if (selection.sort === 'priority') order = priority(b) - priority(a) || Number(b.component.moneyPath) - Number(a.component.moneyPath)
    if (selection.sort === 'coverage') order = (a.component.coverage.lines.percentage ?? -1) - (b.component.coverage.lines.percentage ?? -1)
    if (selection.sort === 'oldest') order = (a.latestAt ? Date.parse(a.latestAt) : -Infinity) - (b.latestAt ? Date.parse(b.latestAt) : -Infinity)
    return (Number.isNaN(order) ? 0 : order) || a.component.component.localeCompare(b.component.component)
  })
}
export const EXPERT_DOMAINS = DOMAIN_GROUPS
