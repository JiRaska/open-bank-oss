// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// Generators for the catalogue reports finrep-service does not render. The properties that matter
// for a regulatory artefact: figures aggregated from the WHOLE roster, a failed or truncated read
// refusing the report (never an under-count), and underivable values flagged as gaps, not zeros.

import { describe, expect, it } from 'vitest'
import {
  ReportSourceError, defaultPeriod, generateReport, nextDeadline, periodBounds, selectablePeriods,
  type GeneratedCell, type GeneratedReport, type ReportSourceClient,
} from '@/lib/regulatory/generatedReports'

type Handler = (method: string, service: string, path: string, query: Record<string, string>, body?: unknown) => { status: number; body: unknown }

function fakeClient(handler: Handler): ReportSourceClient & { calls: string[] } {
  const calls: string[] = []
  return {
    calls,
    async request(service, method, path, options = {}) {
      calls.push(`${method} ${service}${path}`)
      return handler(method, service, path, options.query ?? {}, options.body)
    },
    async get(service, path, query) {
      calls.push(`GET ${service}${path}`)
      const res = handler('GET', service, path, query ?? {})
      if (res.status !== 200) throw new ReportSourceError(res.status === 403 ? 'unauthorized' : 'error', service, `HTTP ${res.status}`)
      return res.body
    },
  }
}

const CONFIG = { taxEntityId: 'company' }
const NOW = new Date('2026-10-17T10:00:00Z')

function cell(report: GeneratedReport, templateId: string, rowRef: string, colRef = 'c0010'): GeneratedCell {
  const found = report.templates.find(t => t.templateId === templateId)?.cells.find(c => c.rowRef === rowRef && c.colRef === colRef)
  if (!found) throw new Error(`no cell ${templateId} ${rowRef}/${colRef}`)
  return found
}

function tx(id: number, rail: string, amount: number, currencyCode = 'CZK', type = 'TRANSFER') {
  return { id: `t${id}`, rail, amount, currencyCode, type, status: 'COMPLETED' }
}

describe('periods and deadlines', () => {
  it('defaults to the last completed month / year and bounds them exactly', () => {
    expect(defaultPeriod('month', NOW)).toBe('2026-09')
    expect(defaultPeriod('year', NOW)).toBe('2025')
    expect(periodBounds('month', '2024-02')).toEqual({ start: '2024-02-01', end: '2024-02-29' })
    expect(periodBounds('year', '2025')).toEqual({ start: '2025-01-01', end: '2025-12-31' })
    expect(periodBounds('month', '2025-13')).toBeNull()
    expect(selectablePeriods('month', NOW)[0]).toBe('2026-10')
  })

  it('computes the NEXT deadline from the rule instead of a stale catalogue date', () => {
    expect(nextDeadline({ kind: 'monthly-day', day: 15 }, NOW)).toBe('2026-11-15')
    expect(nextDeadline({ kind: 'days-after-quarter', days: 30 }, NOW)).toBe('2026-10-30')
    expect(nextDeadline({ kind: 'days-after-month', days: 30 }, NOW)).toBe('2026-10-30')
    expect(nextDeadline({ kind: 'annual', month: 6, day: 30 }, NOW)).toBe('2027-06-30')
    expect(nextDeadline({ kind: 'ad-hoc' }, NOW)).toBeNull()
  })
})

describe('SDAT PLAT — payment statistics', () => {
  it('walks every transaction page and splits counts and volumes by rail and currency', async () => {
    const page1 = Array.from({ length: 200 }, (_, i) => tx(i, 'SEPA_CT', 10, 'EUR'))
    const page2 = [tx(1000, 'DOMESTIC', 1500), tx(1001, 'SWIFT', 99.5, 'USD'), tx(1002, 'CARD', 250), tx(1003, 'FEE', 5, 'CZK', 'FEE'), tx(1004, 'SEPA_INST', 20, 'EUR')]
    const client = fakeClient((_m, _s, _p, q) => {
      expect(q).toMatchObject({ status: 'COMPLETED', dateFrom: '2026-09-01', dateTo: '2026-09-30' })
      return { status: 200, body: { data: q.offset === '0' ? page1 : page2 } }
    })

    const report = await generateReport('sdat-payments', '2026-09', client, CONFIG, NOW)

    expect(client.calls).toHaveLength(2)
    expect(report.closed).toBe(true)
    expect(cell(report, 'PLAT-01', 'r0010').value).toBe(205)
    expect(cell(report, 'PLAT-01', 'r0020').value).toBe(204) // the fee is not a payment
    expect(cell(report, 'PLAT-01', 'r0030').value).toBe(201)
    expect(cell(report, 'PLAT-01', 'r0031').value).toBe(1)
    expect(cell(report, 'PLAT-01', 'r0040').value).toBe(1)
    expect(cell(report, 'PLAT-01', 'r0050').value).toBe(1)
    expect(cell(report, 'PLAT-02', 'r0100')).toMatchObject({ value: 1750, currency: 'CZK' })
    expect(cell(report, 'PLAT-02', 'r0110')).toMatchObject({ value: 2020, currency: 'EUR' })
    expect(report.templates.every(t => !t.hasDataGaps)).toBe(true)
  })

  it('refuses the whole report when a later page fails — never an under-count', async () => {
    const client = fakeClient((_m, _s, _p, q) => (q.offset === '0'
      ? { status: 200, body: { data: Array.from({ length: 200 }, (_, i) => tx(i, 'DOMESTIC', 1)) } }
      : { status: 503, body: null }))
    await expect(generateReport('sdat-payments', '2026-09', client, CONFIG, NOW)).rejects.toBeInstanceOf(ReportSourceError)
  })

  it('marks a report over the running month as not closed', async () => {
    const client = fakeClient(() => ({ status: 200, body: { data: [] } }))
    const report = await generateReport('sdat-payments', '2026-10', client, CONFIG, NOW)
    expect(report.closed).toBe(false)
  })
})

describe('SDAT VKLA — deposit statistics', () => {
  it('sums month-end balances per currency and weights the rate by balance', async () => {
    const accounts = [
      { id: 'a1', currencyCode: 'CZK' }, { id: 'a2', currencyCode: 'CZK' }, { id: 'a3', currencyCode: 'EUR' }, { id: 'a4', currencyCode: 'CZK' },
    ]
    const balances: Record<string, number> = { a1: 1000, a2: 3000, a3: 500, a4: -200 }
    const rates: Record<string, number | null> = { a1: 0.01, a2: 0.03, a3: 0.02, a4: null }
    const client = fakeClient((_m, service, path, q) => {
      if (service === 'account-service') return { status: 200, body: { data: accounts, pagination: { nextCursor: null, hasMore: false } } }
      const id = /\/(a\d)\//.exec(path)?.[1] ?? ''
      if (service === 'balance-service') {
        expect(q.asOf).toBe('2026-09-30')
        return { status: 200, body: { currentBalance: balances[id] } }
      }
      return rates[id] === null ? { status: 204, body: null } : { status: 200, body: { annualRate: rates[id] } }
    })

    const report = await generateReport('sdat-accounts', '2026-09', client, CONFIG, NOW)

    expect(cell(report, 'VKLA-01', 'r0100')).toMatchObject({ value: 4000, currency: 'CZK' })
    expect(cell(report, 'VKLA-01', 'r0110')).toMatchObject({ value: 500, currency: 'EUR' })
    expect(cell(report, 'VKLA-01', 'r0200')).toMatchObject({ value: 200, currency: 'CZK' })
    expect(cell(report, 'VKLA-02', 'r0010').value).toBe(4)
    // (1000·1 % + 3000·3 %) / 4000 = 2.5 %
    expect(cell(report, 'VKLA-02', 'r0300')).toMatchObject({ value: 2.5, currency: '%' })
    expect(cell(report, 'VKLA-02', 'r0310')).toMatchObject({ value: 2, currency: '%' })
  })

  it('flags an unresolved variable rate as a data gap instead of averaging around it', async () => {
    const client = fakeClient((_m, service) => {
      if (service === 'account-service') return { status: 200, body: { data: [{ id: 'a1', currencyCode: 'CZK' }], pagination: { hasMore: false } } }
      if (service === 'balance-service') return { status: 200, body: { currentBalance: 100 } }
      return { status: 200, body: { rateType: 'VARIABLE', annualRate: null } }
    })
    const report = await generateReport('sdat-accounts', '2026-09', client, CONFIG, NOW)
    expect(cell(report, 'VKLA-02', 'r0300').isDataGap).toBe(true)
    expect(report.templates.find(t => t.templateId === 'VKLA-02')?.hasDataGaps).toBe(true)
  })
})

describe('FAÚ SAR', () => {
  it('reports escalated/blocked cases decided in the period, with the transaction amount', async () => {
    const cases = [
      { id: 'c1', partyId: 'p1', customerReference: 'CUST-1', status: 'ESCALATED', riskLevel: 'HIGH', alertCode: 'STRUCTURING', transactionId: 'tx1', screenedAt: '2026-09-02T10:00:00Z', decidedAt: '2026-09-03T10:00:00Z', updatedAt: '2026-09-03T10:00:00Z' },
      { id: 'c2', partyId: 'p2', customerReference: 'CUST-2', status: 'CLEARED', riskLevel: 'LOW', alertCode: 'X', screenedAt: '2026-09-02T10:00:00Z', decidedAt: '2026-09-03T10:00:00Z', updatedAt: '2026-09-03T10:00:00Z' },
      { id: 'c3', partyId: 'p3', customerReference: 'CUST-3', status: 'BLOCKED', riskLevel: 'CRITICAL', alertCode: 'SANCTIONS', screenedAt: '2026-08-02T10:00:00Z', decidedAt: '2026-08-03T10:00:00Z', updatedAt: '2026-08-03T10:00:00Z' },
    ]
    const client = fakeClient((_m, service) => (service === 'aml-service'
      ? { status: 200, body: cases }
      : { status: 200, body: { amount: 9800, currencyCode: 'CZK' } }))

    const report = await generateReport('aml-far', '2026-09', client, { ...CONFIG, mlroContact: 'mlro@bank.example' }, NOW)

    expect(cell(report, 'SAR-00', 'r0010').value).toBe(1)
    expect(cell(report, 'SAR-00', 'r0020').text).toBe('mlro@bank.example')
    expect(cell(report, 'SAR-01', 'l001', 'customer').text).toBe('CUST-1')
    expect(cell(report, 'SAR-01', 'l001', 'amount')).toMatchObject({ value: 9800, currency: 'CZK' })
  })

  it('leaves the MLRO contact as a gap when it is not configured', async () => {
    const client = fakeClient(() => ({ status: 200, body: [] }))
    const report = await generateReport('aml-far', '2026-09', client, CONFIG, NOW)
    expect(cell(report, 'SAR-00', 'r0020').isDataGap).toBe(true)
  })
})

describe('FATCA', () => {
  it('assembles the return in tax-reporting-service when none exists and reads its lines', async () => {
    let assembled = false
    const client = fakeClient((method, _s, path, _q, body) => {
      if (method === 'POST') {
        expect(body).toEqual({ catalogueId: 'cz-tax-information-exchange', returnCode: 'FATCA', entityId: 'company', period: '2025' })
        assembled = true
        return { status: 200, body: { id: 'r1', revision: 1, status: 'ASSEMBLED', contentHash: 'abc', dueDate: '2026-06-30', datapoints: { reportable_persons: 1, reportable_accounts: 1, total_period_end_balance: 1200, total_interest_credited: 12 } } }
      }
      if (path.endsWith('/lines')) {
        return { status: 200, body: { lines: [{ holder_name: 'John Doe', party_ref: 'p1', account_ref: 'acc1', residency_country: 'US', tin: null, currency: 'USD', period_end_balance: '1200.00', interest_credited: '12.00' }] } }
      }
      return { status: 200, body: [] }
    })

    const report = await generateReport('fatca-report', '2025', client, CONFIG, NOW)

    expect(assembled).toBe(true)
    expect(cell(report, 'FATCA-00', 'r0040').value).toBe(1)
    expect(cell(report, 'FATCA-01', 'l001', 'name').text).toBe('John Doe')
    expect(cell(report, 'FATCA-01', 'l001', 'balance')).toMatchObject({ value: 1200, currency: 'USD' })
    // A US person with no TIN on file is a reportable gap, not a blank.
    expect(cell(report, 'FATCA-01', 'l001', 'tin').isDataGap).toBe(true)
  })
})

describe('ECB payment statistics', () => {
  it('counts accounts, live cards and cashless payments; ATMs come from configuration only', async () => {
    const client = fakeClient((_m, service) => {
      if (service === 'account-service') return { status: 200, body: { data: [{ id: 'a1' }, { id: 'a2' }], pagination: { hasMore: false } } }
      if (service === 'card-issuance-service') return { status: 200, body: [{ status: 'ACTIVE', cardType: 'DEBIT' }, { status: 'CANCELLED', cardType: 'DEBIT' }] }
      return { status: 200, body: { data: [tx(1, 'CARD', 100), tx(2, 'INTERNAL', 50), tx(3, 'DOMESTIC', 10)] } }
    })
    const gapped = await generateReport('ecb-payments', '2025', client, CONFIG, NOW)
    expect(cell(gapped, 'PAYM-01', 'r0010').value).toBe(2)
    expect(cell(gapped, 'PAYM-01', 'r0020').value).toBe(1)
    expect(cell(gapped, 'PAYM-01', 'r0030').isDataGap).toBe(true)
    expect(cell(gapped, 'PAYM-02', 'r0100').value).toBe(2)

    const configured = await generateReport('ecb-payments', '2025', client, { ...CONFIG, atmCount: 0 }, NOW)
    expect(cell(configured, 'PAYM-01', 'r0030')).toMatchObject({ value: 0 })
    expect(cell(configured, 'PAYM-01', 'r0030').isDataGap).toBeUndefined()
  })
})

describe('DORA ICT incidents', () => {
  it('lists the major (P1/P2) incidents detected in the period', async () => {
    const incidents = [
      { id: 'i1', title: 'Core outage', description: 'DB failover', category: 'AVAILABILITY', severity: 'P1_CRITICAL', status: 'RESOLVED', affectedServices: ['ledger'], detectedAt: '2026-09-10T08:00:00Z', containedAt: null, resolvedAt: '2026-09-10T09:30:00Z', rtoMinutes: 60, rpoMinutes: 5, reportedToRegulator: false, regulatoryReportId: null },
      { id: 'i2', title: 'Minor', description: '', category: 'AVAILABILITY', severity: 'P4_LOW', status: 'OPEN', affectedServices: [], detectedAt: '2026-09-11T08:00:00Z', reportedToRegulator: false },
      { id: 'i3', title: 'Old', description: '', category: 'AVAILABILITY', severity: 'P1_CRITICAL', status: 'CLOSED', affectedServices: [], detectedAt: '2026-08-11T08:00:00Z', reportedToRegulator: true },
    ]
    const client = fakeClient(() => ({ status: 200, body: incidents }))
    const report = await generateReport('dora-ict', '2026-09', client, CONFIG, NOW)
    expect(cell(report, 'DORA-00', 'r0010').value).toBe(2)
    expect(cell(report, 'DORA-00', 'r0020').value).toBe(1)
    expect(cell(report, 'DORA-01', 'l001', 'rto').text).toContain('60 / 5')
    expect(cell(report, 'DORA-01', 'l001', 'rto').text).toContain('90 min')
  })
})
