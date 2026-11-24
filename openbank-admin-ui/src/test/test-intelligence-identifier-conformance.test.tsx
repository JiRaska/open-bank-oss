// SPDX-License-Identifier: Apache-2.0
import { execFileSync } from 'child_process'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'fs'
import { tmpdir } from 'os'
import path from 'path'
import { render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fuzzIsRobustnessOnly, projectIdentifierConformance } from '@/lib/test-intelligence-identifier-conformance.mjs'
import { createRunProjection } from '@/lib/test-intelligence-run-projection.mjs'
import { IdentifierConformancePanel } from '@/components/testing/IdentifierConformancePanel'
import type { IdentifierConformanceEvidence, TestIntelligenceReport } from '@/lib/types/test-intelligence'

vi.mock('@/lib/i18n/LanguageContext', () => ({
  useLanguage: () => ({ language: 'en', t: (_cs: string, en: string) => en }),
}))

const SCRIPT = path.resolve(__dirname, '../../scripts/collect-test-intelligence.mjs')
const now = new Date()
const recent = new Date(now.getTime() - 3_600_000).toISOString()
const { freshnessAwareState } = createRunProjection({ collectedAt: now, staleAfterMs: 14 * 86_400_000, maxFutureSkewMs: 300_000, warn: () => {} })
const RUN = 'https://github.com/org/repo/actions/runs/1'

const row = (overrides: Record<string, unknown>) => ({
  identifier: 'iban', module: 'openbank-libs-domain', symbol: 'Iban.parse', file: 'x.kt',
  vectors: 10, sources: ['https://www.swift.com/iban'], reference: 'stdnum.iban', differential: true,
  known_defects: [], fuzz: { fuzzer: null, label: 'none' }, pitest: 'advisory', status: 'conformance-tested',
  result: { vectors: 'pass', differential: 'pass', run: RUN, at: recent },
  ...overrides,
})
const matrix = (...validators: unknown[]) => ({ schema: 'identifier-conformance/v1', validators })

const dirs: string[] = []
afterEach(() => dirs.splice(0).forEach(dir => rmSync(dir, { recursive: true, force: true })))

function collect(staged: unknown | undefined): TestIntelligenceReport {
  const repo = mkdtempSync(path.join(tmpdir(), 'ti-identifier-conformance-'))
  dirs.push(repo)
  const write = (relative: string, body: string) => {
    mkdirSync(path.dirname(path.join(repo, relative)), { recursive: true })
    writeFileSync(path.join(repo, relative), body)
  }
  write('openbank-alpha-service/version.txt', '1.0.0\n')
  write('openbank-libs/governance/rules.yaml', 'money_path_services: []\n')
  write('openbank-libs/governance/journeys.yaml', 'version: 1\njourneys: []\n')
  if (staged !== undefined) write('openbank-admin-ui/identifier-conformance/identifier-conformance.json', JSON.stringify(staged))
  const out = path.join(repo, 'report.json')
  execFileSync('node', [SCRIPT, '--repo', repo, '--out', out])
  return JSON.parse(readFileSync(out, 'utf8')) as TestIntelligenceReport
}

describe('identifier conformance ingestion', () => {
  it('a missing artifact is no evidence, never green', () => {
    const report = collect(undefined)
    expect(report.identifierConformance).toMatchObject({ state: 'absent', validators: [], run: null, observedAt: null })
  })

  it('a malformed artifact is no evidence and is warned about', () => {
    const report = collect({ schema: 'something-else', validators: [row({})] })
    expect(report.identifierConformance?.state).toBe('invalid')
    expect(report.identifierConformance?.validators).toEqual([])
    expect(report.warnings.some(item => item.includes('identifier-conformance'))).toBe(true)
  })

  it('keeps provenance and passes a fully verified validator', () => {
    const report = collect(matrix(row({})))
    expect(report.identifierConformance).toMatchObject({ state: 'present', run: RUN, observedAt: recent })
    expect(report.identifierConformance?.validators[0]).toMatchObject({ state: 'passed', lastRun: { at: recent, url: RUN } })
  })

  it('a not-run differential never projects as passed', () => {
    const projected = projectIdentifierConformance(matrix(
      row({ result: { vectors: 'pass', differential: 'not-run', run: RUN, at: recent } }),
      row({ identifier: 'lei', result: undefined }),
    ), freshnessAwareState)
    expect(projected.validators.map(item => [item.identifier, item.state, item.differential.result])).toEqual([
      ['iban', 'not-run', 'not-run'], ['lei', 'not-run', 'not-run'],
    ])
  })

  it('preserves producer verdicts and requires measured status for a pass', () => {
    const projected = projectIdentifierConformance(matrix(
      row({ status: 'failed', result: { vectors: 'fail', differential: 'fail', run: RUN, at: recent } }),
      row({ status: 'not-measured', result: { vectors: 'not-run', differential: 'not-run' } }),
      row({ status: 'declared' }),
    ), freshnessAwareState)
    expect(projected.validators.map(({ status, state }) => [status, state])).toEqual([
      ['failed', 'failed'], ['not-measured', 'not-run'], ['declared', 'not-run'],
    ])
  })

  it('a pass with no run time is not a pass', () => {
    const projected = projectIdentifierConformance(matrix(row({ result: { vectors: 'pass', differential: 'pass', run: RUN, at: null } })), freshnessAwareState)
    expect(projected.validators[0].state).toBe('not-run')
  })

  it('an unverified ad-hoc copy is unknown whatever its results say', () => {
    const projected = projectIdentifierConformance(matrix(row({ status: 'unverified-ad-hoc-copy', vectors: 0 })), freshnessAwareState)
    expect(projected.validators[0]).toMatchObject({ status: 'unverified-ad-hoc-copy', state: 'unknown' })
  })

  it('a validator without an independent reference shows no-reference, and a failure wins over a known defect', () => {
    const projected = projectIdentifierConformance(matrix(
      row({ differential: false, reference: null }),
      row({ identifier: 'rc', status: 'known-defect', known_defects: ['#1'], result: { vectors: 'fail', differential: 'pass', run: RUN, at: recent } }),
    ), freshnessAwareState)
    expect(projected.validators.map(item => [item.differential.result, item.state])).toEqual([['no-reference', 'passed'], ['pass', 'failed']])
  })

  it('derives robustness-only from the matrix label, not from a fuzzer list', () => {
    expect(fuzzIsRobustnessOnly({ fuzzer: 'F', label: 'robustness-only' })).toBe(true)
    expect(fuzzIsRobustnessOnly({ fuzzer: 'F', label: 'none' })).toBe(true)
    expect(fuzzIsRobustnessOnly({ fuzzer: 'F', label: 'semantic' })).toBe(false)
    expect(fuzzIsRobustnessOnly({ fuzzer: null, label: 'none' })).toBe(false)
  })
})

describe('IdentifierConformancePanel', () => {
  const evidence = (rows: unknown[]): IdentifierConformanceEvidence =>
    projectIdentifierConformance(matrix(...rows), freshnessAwareState) as IdentifierConformanceEvidence

  it('renders a missing dimension as no evidence', () => {
    render(<IdentifierConformancePanel evidence={undefined} />)
    expect(screen.getByTestId('identifier-conformance-absent').textContent).toContain('No evidence')
  })

  it('renders an ad-hoc copy as unverified, a not-run differential as not-run, and labels robustness-only fuzzers', () => {
    render(<IdentifierConformancePanel evidence={evidence([
      row({ identifier: 'copy', module: 'openbank-x-service', status: 'unverified-ad-hoc-copy', vectors: 0 }),
      row({ identifier: 'lei', result: { vectors: 'pass', differential: 'not-run', run: RUN, at: recent }, fuzz: { fuzzer: 'LeiFuzz', label: 'robustness-only' } }),
      row({ identifier: 'iban', fuzz: { fuzzer: 'IbanFuzz', label: 'semantic' } }),
    ])} />)
    const copy = within(screen.getByTestId('conformance-copy-openbank-x-service'))
    expect(copy.getByText('Unverified (ad-hoc copy)')).toBeTruthy()
    expect(copy.queryByText('passed')).toBeNull()
    const lei = within(screen.getByTestId('conformance-lei-openbank-libs-domain'))
    expect(lei.getAllByText('not-run').length).toBe(1)
    expect(lei.queryByText('passed')).toBeNull()
    expect(lei.getByText('robustness only — not conformance evidence')).toBeTruthy()
    const iban = within(screen.getByTestId('conformance-iban-openbank-libs-domain'))
    expect(iban.queryByText('robustness only — not conformance evidence')).toBeNull()
    expect(iban.getByRole('link').getAttribute('href')).toBe(RUN)
  })
})
