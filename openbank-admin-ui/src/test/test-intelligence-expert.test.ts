// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { defaultExpertSelection, expertRows, expertSelectionUrl, readExpertSelection } from '@/lib/test-intelligence-expert'
import { TEST_KINDS, readTestSelection } from '@/lib/test-intelligence-workspace'
import { workspaceComponent, workspaceReport } from '../../e2e/fixtures/test-intelligence-workspace'

describe('expert matrix selection and truthful evidence', () => {
  it('round-trips a bounded shareable selection without removing navigation context', () => {
    const selected = { ...defaultExpertSelection(), query: 'accounts', columns: ['unit', 'e2e'] as const, page: 2, inspect: workspaceComponent.component, kind: 'unit' as const }
    const url = expertSelectionUrl({ ...selected, columns: [...selected.columns] }, '?section=expert&tenant=demo')
    expect(readExpertSelection(url.split('?')[1])).toEqual({ ...selected, columns: [...selected.columns] })
    expect(url).toContain('tenant=demo')
    expect(readTestSelection(url.split('?')[1]).section).toBe('expert')
  })
  it('rejects invalid filters and keeps at least one supported column', () => {
    const value = readExpertSelection('?expertPage=-1&expertStatus=green&expertColumns=bad&expertInspect=../../secret&expertKind=bad&expertDomain=bad')
    expect(value).toEqual(defaultExpertSelection())
    expect(readExpertSelection('?expertQuery=' + 'a'.repeat(300)).query).toHaveLength(160)
    expect(value.columns).toEqual(TEST_KINDS)
  })
  it('distinguishes recorded success, missing required verification and build mismatch', () => {
    const report = workspaceReport()
    const original = JSON.stringify(report)
    const row = expertRows(report, defaultExpertSelection(), {})[0]
    expect(row.cells.unit.state).toBe('passed')
    expect(row.cells.mutation.state).toBe('unknown')
    expect(expertRows(report, { ...defaultExpertSelection(), status: 'missing' }, {})).toHaveLength(1)
    expect(expertRows(report, { ...defaultExpertSelection(), status: 'failed' }, {})).toHaveLength(0)
    expect(expertRows(report, { ...defaultExpertSelection(), status: 'mismatch' }, {})).toHaveLength(0)
    expect(JSON.stringify(report)).toBe(original)
  })
  it('filters catalog labels and money paths and sorts coverage deterministically', () => {
    const other = { ...workspaceComponent, component: 'openbank-other-service', moneyPath: false, coverage: { ...workspaceComponent.coverage, lines: { covered: null, missed: null, percentage: null } } }
    const report = workspaceReport({ components: [workspaceComponent, other] })
    expect(expertRows(report, { ...defaultExpertSelection(), sort: 'coverage' }, {})[0].component.component).toBe(other.component)
    expect(expertRows(report, { ...defaultExpertSelection(), moneyOnly: true }, {})).toHaveLength(1)
    expect(expertRows(report, { ...defaultExpertSelection(), query: 'Accounts' }, { [workspaceComponent.component]: { label: 'Accounts', domain: 'core' } })).toHaveLength(1)
  })
})
