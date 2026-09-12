// SPDX-License-Identifier: Apache-2.0
import { act, fireEvent, render, screen, waitFor, cleanup } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ReportingPage from '@/app/reporting/page'
import { dailyCounts, ReportTrend } from '@/components/reporting/ReportTrend'
import { WarehouseDashboard } from '@/components/reporting/WarehouseDashboard'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { REPORT_REGISTRY, validateParams } from '@/lib/reporting/registry'

const wrap = (child: React.ReactNode) => <LanguageProvider initialLanguage="en">{child}</LanguageProvider>
afterEach(() => { cleanup(); vi.useRealTimers(); vi.unstubAllGlobals() })

it('aggregates additive daily counts across currencies without adding money or filling missing days', () => {
  expect(dailyCounts([
    { day: '2026-09-01', settled_count: '2', settled_amount: '100', currency_code: 'EUR' },
    { day: '2026-09-01', settled_count: '3', settled_amount: '100', currency_code: 'CZK' },
    { day: '2026-09-03', settled_count: '4' },
    { day: '2026-09-04', settled_count: null },
  ], 'settled_count')).toEqual([{ day: '2026-09-01', count: 5 }, { day: '2026-09-03', count: 4 }])
})
it('does not present a truncated result as a period total', () => {
  render(wrap(<ReportTrend reportId="risk-settlement-daily" rows={[{ day: '2026-09-01', settled_count: 5 }]} truncated />))
  expect(screen.queryByLabelText('Daily report trend')).not.toBeInTheDocument()
})
it('rejects impossible dates and inverted date ranges before querying', () => {
  // By id, not by position: REPORT_REGISTRY[0] silently becomes a DIFFERENT report the moment an
  // entry is added above it, and a date-parameter assertion aimed at a month-parameter report
  // fails for a reason that has nothing to do with dates. That is what happened when the
  // financial pack landed (#8976).
  const dateReport = REPORT_REGISTRY.find((entry) => entry.id === 'risk-settlement-daily')!
  expect(validateParams(dateReport, { from: '2026-02-30', to: '2026-03-01' }).ok).toBe(false)
  expect(validateParams(dateReport, { from: '2026-09-08', to: '2026-09-01' }).ok).toBe(false)
  expect(validateParams(dateReport, { from: '2024-02-29', to: '2024-03-01' }).ok).toBe(true)
})

describe('report ownership', () => {
  it('discards an in-flight result when the operator selects a different report', async () => {
    let resolve!: (response: Response) => void
    vi.stubGlobal('fetch', vi.fn((url: string) => url === '/api/reporting'
      ? Promise.resolve(Response.json({ reports: REPORT_REGISTRY }))
      : new Promise<Response>((done) => { resolve = done })))
    render(wrap(<ReportingPage />))
    await screen.findByRole('button', { name: /Daily settled transaction volume/ })
    fireEvent.click(screen.getByRole('button', { name: 'Run report' }))
    fireEvent.click(screen.getByRole('button', { name: /Daily failed transactions/ }))
    await act(async () => resolve(Response.json({ available: true, reportId: 'risk-settlement-daily', rows: [{ day: 'STALE RESULT' }], columns: [{ key: 'day', labelCs: 'Den', labelEn: 'Day', format: 'text' }], rowCount: 1 })))
    expect(screen.queryByText('STALE RESULT')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Run report' })).toBeEnabled()
  })
  it('distinguishes a warehouse outage from an empty successful report', async () => {
    vi.stubGlobal('fetch', vi.fn((url: string) => Promise.resolve(Response.json(url === '/api/reporting'
      ? { reports: REPORT_REGISTRY }
      : { available: false, reportId: REPORT_REGISTRY[0].id, rows: [], columns: [], rowCount: 0 }))))
    render(wrap(<ReportingPage />))
    await screen.findByRole('button', { name: /Daily settled transaction volume/ })
    fireEvent.click(screen.getByRole('button', { name: 'Run report' }))
    await waitFor(() => expect(screen.queryByText('Loading report…')).not.toBeInTheDocument())
    expect(screen.getByText(/ClickHouse/)).toBeInTheDocument()
    expect(screen.queryByText('No data yet')).not.toBeInTheDocument()
  })
})

it('never treats a frame load event as proof of successful SSO', () => {
  vi.useFakeTimers()
  render(wrap(<WarehouseDashboard from="2026-09-01" to="2026-09-08" />))
  const frame = screen.getByTitle('Trends and data quality')
  expect(frame.getAttribute('src')).toContain('/tools/grafana/d/openbank-business-warehouse?')
  expect(frame.getAttribute('src')).toContain('kiosk=1')
  expect(frame.getAttribute('src')).toContain('theme=light')
  expect(screen.getByRole('link', { name: 'Explore in Grafana' }).getAttribute('href')).not.toContain('kiosk=')
  fireEvent.load(frame)
  act(() => vi.advanceTimersByTime(25_500))
  expect(screen.getByText('Charts could not be loaded yet')).toBeInTheDocument()
  expect(frame).not.toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: 'Refresh charts' }))
  expect(screen.getByText('Loading charts and verifying your session…')).toBeInTheDocument()
})

it('shows the frame only after the dashboard has rendered', () => {
  vi.useFakeTimers()
  render(wrap(<WarehouseDashboard from="2026-09-01" to="2026-09-08" />))
  const frame = screen.getByTitle('Trends and data quality')
  const doc = document.implementation.createHTMLDocument()
  doc.body.innerHTML = '<div class="react-grid-layout"></div>'
  Object.defineProperty(frame, 'contentDocument', { value: doc })
  act(() => vi.advanceTimersByTime(500))
  expect(frame).toBeVisible()
  expect(frame).toHaveStyle({ height: '1420px', overflow: 'hidden' })
  expect(frame).toHaveAttribute('scrolling', 'no')
  expect(screen.queryByText('Charts could not be loaded yet')).not.toBeInTheDocument()
})
