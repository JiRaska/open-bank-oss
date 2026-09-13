// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import {
  type Decision, type LoanRisk, type Policy,
  byJurisdiction, decisionsToCsv, disposition, outcomeTotals, overrideMatrix, overrideRate,
  portfolioMix, priceBandTotals, reasonPareto, ruleHits, threshold, vintage, weeklyOutcomes,
} from '@/components/lending/risk/model'

function decision(over: Partial<Decision> = {}): Decision {
  return {
    applicationId: 'a1', partyId: 'p1', status: 'FOUR_EYES', createdAt: '2026-09-01T10:00:00Z',
    requestedAmount: 120000, currency: 'CZK', termPeriods: 12, nominalAnnualRate: 0.08,
    jurisdiction: 'CZ', productType: 'CONSUMER_CREDIT', productKind: 'UNSECURED', packVersion: 1,
    engineOutcome: 'APPROVE', priceBand: 'PRIME', reasons: [], matchedRuleIds: [],
    policyVersions: { AFFORDABILITY: 1 }, inputSnapshotHash: 'h', decidedEngineAt: '2026-09-01T10:00:05Z',
    affordability: { dsti: 0.17, dti: 2, dstiIncludingExistingDebt: 0.27 },
    verifiedIncomeMonthly: 60000, existingDebtServiceMonthly: 6000, ageYears: 35, residency: 'CZ',
    employmentTenureMonths: 48, humanDecidedBy: null, humanDecisionReason: null, humanDecidedAt: null,
    ...over,
  }
}

function loan(over: Partial<LoanRisk> = {}): LoanRisk {
  return {
    loanId: 'l1', applicationId: 'a1', partyId: 'p1', status: 'ACTIVE', principal: 100000,
    currency: 'CZK', nominalAnnualRate: 0.08, termPeriods: 12, disbursedAt: '2026-07-15T00:00:00Z',
    assessment: null, ...over,
  }
}

const assessment = (stage: string, bucket: string, outstanding: number, ecl: number) => ({
  period: '2026-08', asOf: '2026-08-31', outstandingBalance: outstanding, daysPastDue: 0,
  bucket, stage, expectedCreditLoss: ecl, modelVersion: 'noop-flat-v1',
})

describe('credit-risk aggregation', () => {
  it('takes book totals from the DB summary, not from the capped list', () => {
    const totals = outcomeTotals([
      { engineOutcome: 'APPROVE', priceBand: 'PRIME', count: 40 },
      { engineOutcome: 'APPROVE', priceBand: 'STANDARD', count: 25 },
      { engineOutcome: 'REFER', priceBand: null, count: 30 },
      { engineOutcome: 'DECLINE', priceBand: null, count: 5 },
    ])
    expect(totals).toMatchObject({ APPROVE: 65, REFER: 30, DECLINE: 5, total: 100 })
    expect(priceBandTotals([
      { engineOutcome: 'APPROVE', priceBand: 'STANDARD', count: 25 },
      { engineOutcome: 'APPROVE', priceBand: 'PRIME', count: 40 },
      { engineOutcome: 'REFER', priceBand: 'PRIME', count: 9 },
    ])).toEqual([{ band: 'PRIME', count: 40 }, { band: 'STANDARD', count: 25 }])
  })

  it('counts an override only where the human went the OTHER way', () => {
    const rows = [
      decision({ applicationId: '1', engineOutcome: 'APPROVE', status: 'DISBURSED' }),
      decision({ applicationId: '2', engineOutcome: 'APPROVE', status: 'DECLINED' }),
      decision({ applicationId: '3', engineOutcome: 'DECLINE', status: 'DECLINED' }),
      decision({ applicationId: '4', engineOutcome: 'DECLINE', status: 'OFFERED' }),
      decision({ applicationId: '5', engineOutcome: 'REFER', status: 'FOUR_EYES' }),
      decision({ applicationId: '6', engineOutcome: 'APPROVE', status: 'EXPIRED' }),
    ]
    const matrix = overrideMatrix(rows)
    const approve = matrix.find(r => r.engine === 'APPROVE')!
    expect(approve).toMatchObject({ approved: 1, declined: 1, lapsed: 1, overridden: 1 })
    expect(matrix.find(r => r.engine === 'DECLINE')!.overridden).toBe(1)
    // Denominator is disposed decisions only: 4 (two approved, two declined), not all six.
    expect(overrideRate(matrix)).toBeCloseTo(2 / 4, 10)
    // A book where nothing has been disposed of has no rate — never a confident zero.
    expect(overrideRate(overrideMatrix([decision({ status: 'FOUR_EYES' })]))).toBeNull()
    expect(disposition('REFLECTION_PERIOD')).toBe('approved')
    expect(disposition('KYC_PENDING')).toBe('inFlight')
  })

  it('reads thresholds from the policy payload rather than hard-coding them', () => {
    const policy: Policy = {
      asOf: '2026-09-05', source: 'StarterCreditPolicy', codeSeeded: true,
      tables: [
        { kind: 'AFFORDABILITY', name: 'old', version: 1, effectiveFrom: '2020-01-01', effectiveTo: '2026-01-01', rules: [{ id: 'o', attribute: 'DSTI', operator: 'LTE', threshold: 0.6, values: [], band: null, detail: '' }] },
        { kind: 'AFFORDABILITY', name: 'new', version: 2, effectiveFrom: '2026-01-01', effectiveTo: null, rules: [{ id: 'n', attribute: 'DSTI', operator: 'LTE', threshold: 0.45, values: [], band: null, detail: '' }] },
        { kind: 'ELIGIBILITY', name: 'e', version: 1, effectiveFrom: '2020-01-01', effectiveTo: null, rules: [{ id: 'r', attribute: 'RESIDENCY', operator: 'IN', threshold: null, values: ['CZ'], band: null, detail: '' }] },
      ],
    }
    expect(threshold(policy, 'AFFORDABILITY', 'DSTI')).toBe(0.45)
    // No numeric threshold and no policy at all both yield null — the chart then draws no line.
    expect(threshold(policy, 'ELIGIBILITY', 'RESIDENCY')).toBeNull()
    expect(threshold(policy, 'AFFORDABILITY', 'LTV')).toBeNull()
    expect(threshold(null, 'AFFORDABILITY', 'DSTI')).toBeNull()
  })

  it('paretoes reasons by code AND rule, and counts rule hits', () => {
    const rows = [
      decision({ reasons: [{ code: 'AFFORDABILITY_FAILED', ruleId: 'af-dsti' }], matchedRuleIds: ['el-age'] }),
      decision({ reasons: [{ code: 'AFFORDABILITY_FAILED', ruleId: 'af-dsti' }, { code: 'INPUT_MISSING', ruleId: null }], matchedRuleIds: ['el-age', 'af-dti'] }),
    ]
    expect(reasonPareto(rows)[0]).toEqual({ code: 'AFFORDABILITY_FAILED', ruleId: 'af-dsti', count: 2 })
    expect(ruleHits(rows).get('el-age')).toBe(2)
    expect(ruleHits(rows).get('af-dti')).toBe(1)
    expect(ruleHits(rows).get('never-fires')).toBeUndefined()
  })

  it('keeps currencies apart and never sums CZK with EUR', () => {
    const mixes = portfolioMix([
      loan({ loanId: 'a', currency: 'CZK', assessment: assessment('STAGE_1', 'CURRENT', 100000, 3000) }),
      loan({ loanId: 'b', currency: 'CZK', assessment: assessment('STAGE_3', 'DPD_90_PLUS', 100000, 45000) }),
      loan({ loanId: 'c', currency: 'EUR', assessment: assessment('STAGE_1', 'CURRENT', 5000, 150) }),
      loan({ loanId: 'd', currency: 'CZK', assessment: null }),
    ])
    expect(mixes.map(m => m.currency)).toEqual(['CZK', 'EUR'])
    const czk = mixes[0]
    expect(czk.totalOutstanding).toBe(200000)
    expect(czk.coverage).toBeCloseTo(48000 / 200000, 10)
    expect(czk.stage23Share).toBeCloseTo(0.5, 10)
    expect(czk.npl90Share).toBeCloseTo(0.5, 10)
    // An unassessed loan is counted as unassessed, never as Stage 1 with zero ECL.
    expect(czk.unassessed).toBe(1)
    expect(czk.stages.find(s => s.stage === 'STAGE_1')!.count).toBe(1)
    expect(czk.modelVersions).toEqual(['noop-flat-v1'])
  })

  it('an empty or wholly unassessed book yields null ratios, not zeroes', () => {
    const [mix] = portfolioMix([loan({ assessment: null })])
    expect(mix.coverage).toBeNull()
    expect(mix.stage23Share).toBeNull()
    expect(mix.npl90Share).toBeNull()
    expect(portfolioMix([])).toEqual([])
  })

  it('buckets vintages by disbursement month and keeps unassessed loans visible', () => {
    const rows = vintage([
      loan({ loanId: 'a', disbursedAt: '2026-07-15T00:00:00Z', assessment: assessment('STAGE_1', 'CURRENT', 1, 0) }),
      loan({ loanId: 'b', disbursedAt: '2026-07-20T00:00:00Z', assessment: assessment('STAGE_3', 'DPD_90_PLUS', 1, 1) }),
      loan({ loanId: 'c', disbursedAt: '2026-08-02T00:00:00Z', assessment: null }),
    ])
    expect(rows.map(r => r.month)).toEqual(['2026-07', '2026-08'])
    expect(rows[0]).toMatchObject({ loans: 2, STAGE_1: 1, STAGE_3: 1, unassessed: 0 })
    expect(rows[1]).toMatchObject({ loans: 1, unassessed: 1 })
  })

  it('groups weeks from the engine timestamp and jurisdictions from the row', () => {
    const rows = [
      decision({ applicationId: '1', decidedEngineAt: '2026-09-01T10:00:00Z', engineOutcome: 'APPROVE' }),
      decision({ applicationId: '2', decidedEngineAt: '2026-09-06T10:00:00Z', engineOutcome: 'REFER' }),
      decision({ applicationId: '3', decidedEngineAt: '2026-09-07T10:00:00Z', engineOutcome: 'DECLINE', jurisdiction: 'DE' }),
    ]
    const weeks = weeklyOutcomes(rows)
    expect(weeks.map(w => w.week)).toEqual(['2026-08-31', '2026-09-07'])
    expect(weeks[0]).toMatchObject({ APPROVE: 1, REFER: 1 })
    expect(byJurisdiction(rows)).toEqual([
      { jurisdiction: 'CZ', APPROVE: 1, REFER: 1, DECLINE: 0, total: 2 },
      { jurisdiction: 'DE', APPROVE: 0, REFER: 0, DECLINE: 1, total: 1 },
    ])
  })

  it('exports notebook-ready CSV with evidence intact and separators escaped', () => {
    const csv = decisionsToCsv([decision({
      reasons: [{ code: 'AFFORDABILITY_FAILED', ruleId: 'af-dsti' }, { code: 'INPUT_MISSING', ruleId: null }],
      matchedRuleIds: ['el-age', 'af-dti'],
      policyVersions: { ELIGIBILITY: 1, AFFORDABILITY: 2 },
      humanDecisionReason: 'refused, "policy" says no\nsecond line',
    })])
    const [header, row] = csv.split('\r\n')
    expect(header.split(',')).toContain('dstiIncludingExistingDebt')
    expect(row).toContain('AFFORDABILITY_FAILED:af-dsti;INPUT_MISSING:-')
    expect(row).toContain('el-age;af-dti')
    expect(row).toContain('ELIGIBILITY=1;AFFORDABILITY=2')
    // A comma, a quote and a newline inside a field must not break the row structure.
    expect(row).toContain('"refused, ""policy"" says no\nsecond line"')
    expect(csv.endsWith('\r\n')).toBe(true)
    // Missing affordability exports as empty, never as 0.
    expect(decisionsToCsv([decision({ affordability: null })]).split('\r\n')[1]).toContain(',,,')
  })
})
