// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// BFF contract of /api/reporting/[queryId] (ADR-0286): denials are the only non-200s; a
// warehouse failure degrades to `available: false`, and a denied or malformed request never
// reaches ClickHouse.

import { afterEach, describe, expect, it, vi } from 'vitest'
import { NextRequest } from 'next/server'
import { getReport } from '@/lib/reporting/registry'
import { parseReportResult } from '@/lib/reporting/clientContract'
import { clickHouseRiskLargeSettledRows } from '../../e2e/fixtures/clickhouse-risk-large-settled'

const permissionMock = vi.fn()
vi.mock('@/lib/auth/api-permission', () => ({
  requireApiPermission: (p: string) => permissionMock(p),
}))

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  permissionMock.mockReset()
})

function allow() {
  permissionMock.mockResolvedValue({ ok: true })
}
function deny() {
  permissionMock.mockResolvedValue({ ok: false, status: 403, error: 'forbidden' })
}

function req(url: string): NextRequest {
  return new NextRequest(`http://localhost${url}`)
}

const PARAMS = Promise.resolve({ queryId: 'risk-failures-daily' })

describe('reporting BFF /api/reporting/[queryId]', () => {
  it('requests ISO DateTime64 output and returns rows accepted by the strict client contract', async () => {
    allow()
    const fetchSpy = vi.fn(async () => Response.json({ data: clickHouseRiskLargeSettledRows }))
    vi.stubGlobal('fetch', fetchSpy)
    const { GET } = await import('@/app/api/reporting/[queryId]/route')
    const entry = getReport('risk-large-settled')!
    const res = await GET(
      req('/api/reporting/risk-large-settled?from=2026-09-20&to=2026-10-19&minAmount=100000'),
      { params: Promise.resolve({ queryId: entry.id }) },
    )

    expect(res.status).toBe(200)
    const body = await res.json()
    expect(String(fetchSpy.mock.calls[0][0])).toContain('date_time_output_format=iso')
    expect(parseReportResult(body, entry)).toMatchObject({
      available: true,
      rows: [{ settled_at: '2026-10-19T12:34:56.789Z' }],
      rowCount: 1,
    })
  })

  it.each([
    ['missing data', {}],
    ['non-array data', { data: { day: '2026-09-01' } }],
    ['non-object row', { data: [null] }],
  ])('degrades malformed successful ClickHouse JSON (%s) instead of reporting an empty success', async (_label, payload) => {
    allow()
    vi.stubGlobal('fetch', vi.fn(async () => Response.json(payload)))
    const { GET } = await import('@/app/api/reporting/[queryId]/route')
    const entry = getReport('risk-failures-daily')!
    const res = await GET(
      req('/api/reporting/risk-failures-daily?from=2026-08-01&to=2026-09-01'),
      { params: Promise.resolve({ queryId: entry.id }) },
    )
    expect(res.status).toBe(200)
    expect(await res.json()).toMatchObject({ available: false, error: 'ClickHouse query failed', rows: [] })
  })

  it('404s an unknown report id without touching the warehouse', async () => {
    const fetchSpy = vi.fn()
    vi.stubGlobal('fetch', fetchSpy)
    const { GET } = await import('@/app/api/reporting/[queryId]/route')
    const res = await GET(req('/api/reporting/nope'), { params: Promise.resolve({ queryId: 'nope' }) })
    expect(res.status).toBe(404)
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('403s when the entry permission is missing, without touching the warehouse', async () => {
    deny()
    const fetchSpy = vi.fn()
    vi.stubGlobal('fetch', fetchSpy)
    const { GET } = await import('@/app/api/reporting/[queryId]/route')
    const res = await GET(req('/api/reporting/risk-failures-daily?from=2026-08-01&to=2026-09-01'), { params: PARAMS })
    expect(res.status).toBe(403)
    expect(permissionMock).toHaveBeenCalledWith('compliance:view')
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('400s on an invalid parameter and never builds SQL from it', async () => {
    allow()
    const fetchSpy = vi.fn()
    vi.stubGlobal('fetch', fetchSpy)
    const { GET } = await import('@/app/api/reporting/[queryId]/route')
    const res = await GET(
      req("/api/reporting/risk-failures-daily?from=2026-08-01' OR 1=1 --&to=2026-09-01"),
      { params: PARAMS },
    )
    expect(res.status).toBe(400)
    const body = await res.json()
    expect(body.error).toContain('from')
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('runs the registry query and returns typed rows on success', async () => {
    allow()
    const fetchSpy = vi.fn(async () => Response.json({
      data: [{ day: '2026-09-01', failed_count: '13', distinct_transactions: '13' }],
    }))
    vi.stubGlobal('fetch', fetchSpy)
    const { GET } = await import('@/app/api/reporting/[queryId]/route')
    const res = await GET(req('/api/reporting/risk-failures-daily?from=2026-08-01&to=2026-09-01'), { params: PARAMS })
    expect(res.status).toBe(200)
    const body = await res.json()
    expect(body.available).toBe(true)
    expect(body.rowCount).toBe(1)
    expect(body.rows[0].failed_count).toBe('13')

    // The SQL sent to ClickHouse contains only validated values.
    const sql = String(fetchSpy.mock.calls[0][1]?.body)
    expect(sql).toContain("toDate('2026-08-01')")
    expect(sql).toContain('gold_risk_failures_daily')
  })

  it('degrades to available:false when ClickHouse errors (never a raw 5xx)', async () => {
    allow()
    vi.stubGlobal('fetch', vi.fn(async () => new Response('boom', { status: 500 })))
    const { GET } = await import('@/app/api/reporting/[queryId]/route')
    const res = await GET(req('/api/reporting/risk-failures-daily?from=2026-08-01&to=2026-09-01'), { params: PARAMS })
    expect(res.status).toBe(200)
    const body = await res.json()
    expect(body.available).toBe(false)
    expect(body.error).toContain('ClickHouse')
  })
})

describe('reporting catalogue BFF /api/reporting', () => {
  it('returns registry metadata without any SQL builder', async () => {
    allow()
    const { GET } = await import('@/app/api/reporting/route')
    const res = await GET()
    expect(res.status).toBe(200)
    const body = await res.json()
    expect(body.reports.length).toBeGreaterThan(0)
    const text = JSON.stringify(body)
    expect(text).not.toContain('SELECT')
    expect(text).not.toContain('openbank_analytics.')
  })

  it('denies the catalogue without compliance:view', async () => {
    deny()
    const { GET } = await import('@/app/api/reporting/route')
    const res = await GET()
    expect(res.status).toBe(403)
  })
})
