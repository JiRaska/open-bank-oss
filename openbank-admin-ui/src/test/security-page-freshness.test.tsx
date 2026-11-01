// SPDX-License-Identifier: Apache-2.0
import React from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, render, screen } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import SecurityPage from '@/app/security/page'

vi.mock('@/components/auth/AuthGuard', () => ({ AuthGuard: ({ children }: { children: React.ReactNode }) => children }))

const report = (generatedAt: string, serviceResults: unknown[] = []) => ({
  reportId: 'b6ea92e9-c4b4-48af-88f5-b23705db5ef8', generatedAt,
  totalServices: serviceResults.length, reachableServices: serviceResults.length, serviceResults,
  platformScore: 0, platformGrade: 'F', criticalFindings: 0, highFindings: 0,
  owaspCoverage: {
    A01_BROKEN_ACCESS_CONTROL: 0, A02_CRYPTOGRAPHIC_FAILURES: 0, A03_INJECTION: 0,
    A04_INSECURE_DESIGN: 0, A05_SECURITY_MISCONFIGURATION: 0, A06_VULNERABLE_COMPONENTS: 0,
    A07_AUTH_FAILURES: 0, A08_SOFTWARE_INTEGRITY_FAILURES: 0, A09_LOGGING_MONITORING_FAILURES: 0,
    A10_SSRF: 0,
  },
  complianceStatus: {},
})

afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.useRealTimers(); vi.restoreAllMocks() })

describe('Security Scanner report freshness', () => {
  it('clears a previously selected service when a later report has no observations', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-20T12:00:00.000Z'))
    const service = {
      serviceName: 'ledger-service', serviceUrl: 'http://ledger-service', scannedAt: '2026-10-20T12:00:00Z',
      durationMs: 1, reachable: true, findings: [], score: 95, grade: 'A+', headersPresent: {}, openApiAvailable: false,
    }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ available: true, report: report('2026-10-20T12:00:00Z', [service]), source: 'scanner', freshness: 'fresh', scannerStatus: 'available' }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ available: true, report: report('2026-10-20T12:00:00Z'), source: 'scanner', freshness: 'fresh', scannerStatus: 'available' }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    render(<LanguageProvider initialLanguage="en"><SecurityPage /></LanguageProvider>)
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    expect(screen.getAllByText('ledger-service')).toHaveLength(2)

    await act(async () => { await vi.advanceTimersByTimeAsync(5 * 60 * 1000) })

    expect(screen.queryByText('ledger-service')).not.toBeInTheDocument()
    expect(screen.queryByText('ledger-service')).not.toBeInTheDocument()
  })

  it('shows a stale CI fallback during scanner outage, then recovers to the scanner on visible polling', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-20T12:00:00.000Z'))
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({
        available: true, report: report('2026-10-20T11:00:00.000Z'),
        source: 'ci-bundle', freshness: 'stale', scannerStatus: 'unavailable',
      }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        available: true, report: report('2026-10-20T12:00:00.000Z'),
        source: 'scanner', freshness: 'fresh', scannerStatus: 'available',
      }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)

    const view = render(<LanguageProvider initialLanguage="en"><SecurityPage /></LanguageProvider>)
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })

    expect(screen.getByRole('alert')).toHaveTextContent('Report source: CI bundle')
    expect(screen.getByRole('alert')).toHaveTextContent('stale')
    expect(screen.getByRole('alert')).toHaveTextContent('scanner is unavailable')
    expect(fetchMock).toHaveBeenCalledTimes(1)

    await act(async () => { await vi.advanceTimersByTimeAsync(5 * 60 * 1000) })

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(screen.getByRole('status')).toHaveTextContent('Report source: security scanner')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(String(fetchMock.mock.calls[1][0])).toBe('/api/security')
    expect(fetchMock.mock.calls[1][1]).toMatchObject({ cache: 'no-store' })

    view.unmount()
    await act(async () => { await vi.advanceTimersByTimeAsync(10 * 60 * 1000) })
    expect(fetchMock).toHaveBeenCalledTimes(2)
  // The isolated React page render is CPU-heavy in parallel test runs; polling time is advanced with fake timers, not waited in real time.
  }, 15_000)
})
