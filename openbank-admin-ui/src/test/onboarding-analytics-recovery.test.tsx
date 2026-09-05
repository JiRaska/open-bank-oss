// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import OnboardingAnalyticsPage from '@/app/onboarding/analytics/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

vi.mock('recharts', () => ({
  ResponsiveContainer: ({ children }: { children: React.ReactNode }) => children,
  BarChart: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  Bar: ({ children }: { children?: React.ReactNode }) => <div>{children}</div>,
  XAxis: () => null,
  YAxis: () => null,
  CartesianGrid: () => null,
  Tooltip: () => null,
  Cell: () => null,
  LabelList: () => null,
  LineChart: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  Line: () => null,
  PieChart: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  Pie: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  Legend: () => null,
}))

const SNAPSHOT = {
  available: true,
  from: '2026-08-03',
  to: '2026-09-02',
  steps: [
    { step: 'WELCOME', stepOrdinal: 1, viewed: 40, completed: 30, holdAbandons: 0, dropOffPct: 25, medianSeconds: 12 },
    { step: 'SIGN', stepOrdinal: 6, viewed: 20, completed: 18, holdAbandons: 0, dropOffPct: 10, medianSeconds: 45 },
  ],
  signOutcomes: [{ day: '2026-08-31', attempts: 20, successes: 18, failures: 2 }],
  failReasons: [{ reason: 'OTP_EXPIRED', failures: 2 }],
  kycMethods: [{ method: 'BANK_ID', sessions: 30 }],
}

function response(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

function renderPage() {
  return render(<LanguageProvider initialLanguage="en"><OnboardingAnalyticsPage /></LanguageProvider>)
}

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  vi.setSystemTime(new Date('2026-09-02T12:00:00Z'))
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('onboarding analytics recovery', () => {
  it('retains the last successful funnel during an operational outage and replaces it after retry', async () => {
    const recovered = { ...SNAPSHOT, steps: SNAPSHOT.steps.map(step => step.step === 'WELCOME' ? { ...step, viewed: 50 } : step) }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(200, SNAPSHOT))
      .mockResolvedValueOnce(response(200, { ...SNAPSHOT, available: false, error: 'ClickHouse timeout', steps: [] }))
      .mockResolvedValueOnce(response(200, recovered))
    vi.stubGlobal('fetch', fetchMock)

    renderPage()
    expect(await screen.findByText('40')).toBeVisible()

    fireEvent.click(screen.getByRole('button', { name: 'Refresh onboarding analytics' }))

    expect(await screen.findByRole('status', { name: 'Onboarding analytics freshness' })).toHaveTextContent('last successful snapshot')
    expect(screen.getByText('40')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: 'Retry onboarding analytics' }))

    expect(await screen.findByText('50')).toBeVisible()
    await waitFor(() => expect(screen.queryByRole('status', { name: 'Onboarding analytics freshness' })).not.toBeInTheDocument())
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  it.each([401, 403])('purges the retained funnel after HTTP %s', async status => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(response(200, SNAPSHOT))
      .mockResolvedValueOnce(response(status, { error: status === 401 ? 'unauthorized' : 'forbidden' })))

    renderPage()
    expect(await screen.findByText('40')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: 'Refresh onboarding analytics' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(status === 401 ? 'Session expired' : 'Access denied')
    expect(screen.queryByText('40')).not.toBeInTheDocument()
    expect(screen.queryByText('OTP_EXPIRED')).not.toBeInTheDocument()
  })

  it('never renders a previous range while a new range is loading', async () => {
    const nextRange = deferred<Response>()
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(200, SNAPSHOT))
      .mockReturnValueOnce(nextRange.promise)
    vi.stubGlobal('fetch', fetchMock)

    renderPage()
    expect(await screen.findByText('40')).toBeVisible()
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-08-10' } })

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    expect(screen.queryByText('40')).not.toBeInTheDocument()
    expect(screen.getByRole('status')).toBeVisible()
  })

  it('rejects a successful response whose range does not match the request', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(200, {
      ...SNAPSHOT,
      from: '2026-07-01',
      to: '2026-07-31',
    })))

    renderPage()

    expect(await screen.findByText('ClickHouse gold marts are unavailable.')).toBeVisible()
    expect(screen.queryByText('40')).not.toBeInTheDocument()
  })

  it('treats an older access denial as stronger than a newer pending success', async () => {
    const denied = deferred<Response>()
    const newer = deferred<Response>()
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(200, SNAPSHOT))
      .mockReturnValueOnce(denied.promise)
      .mockReturnValueOnce(newer.promise)
    vi.stubGlobal('fetch', fetchMock)

    renderPage()
    expect(await screen.findByText('40')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: 'Refresh onboarding analytics' }))
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-08-10' } })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))

    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
    await act(async () => { denied.resolve(response(403, { error: 'forbidden' })) })
    expect(await screen.findByRole('alert')).toHaveTextContent('Access denied')
    const newerSignal = (fetchMock.mock.calls[2]?.[1] as RequestInit | undefined)?.signal
    expect(newerSignal?.aborted).toBe(true)
    await act(async () => {
      newer.resolve(response(200, {
        ...SNAPSHOT,
        from: '2026-08-10',
        steps: SNAPSHOT.steps.map(step => ({ ...step, viewed: 99 })),
      }))
      await newer.promise
      await new Promise(resolve => window.setTimeout(resolve, 0))
    })

    expect(screen.getByRole('alert')).toHaveTextContent('Access denied')
    expect(screen.queryByText('99')).not.toBeInTheDocument()
    expect(screen.queryByText('40')).not.toBeInTheDocument()
  })

  it('gives the initial loading status an accessible message', () => {
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(new Promise(() => undefined)))

    renderPage()

    expect(screen.getByRole('status')).toHaveTextContent('Loading onboarding analytics')
  })

  it('aborts and ignores a late response after unmount', async () => {
    const late = deferred<Response>()
    const readBody = vi.fn().mockResolvedValue(SNAPSHOT)
    const fetchMock = vi.fn().mockReturnValue(late.promise)
    vi.stubGlobal('fetch', fetchMock)

    const view = renderPage()
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    const signal = (fetchMock.mock.calls[0]?.[1] as RequestInit | undefined)?.signal
    view.unmount()
    expect(signal?.aborted).toBe(true)

    await act(async () => {
      late.resolve({ ok: true, status: 200, json: readBody } as Response)
      await late.promise
      await new Promise(resolve => window.setTimeout(resolve, 0))
    })
    expect(readBody).not.toHaveBeenCalled()
  })
})
