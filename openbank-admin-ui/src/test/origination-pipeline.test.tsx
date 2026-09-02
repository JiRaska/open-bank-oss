// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The pipeline exists to tell a credit officer where work is STUCK. So the tests are about the ways
// it could tell them something false:
//
//  - tinting a terminal stage by age (a loan disbursed four days ago is not a four-day-old problem;
//    the first render did exactly this and showed nine successful disbursements in red),
//  - presenting a CAPPED list as if it were the book, which turns a staffing decision into a guess,
//  - counting finished applications as "in flight".

import { describe, it, expect, afterEach, vi } from 'vitest'
import React from 'react'
import { render, screen, cleanup, waitFor, fireEvent } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { SessionProvider } from '@/components/auth/SessionProvider'
import { OriginationPipeline, summarise, wrap, type PipelineItem } from '@/components/lending/OriginationPipeline'
import LendingPage from '@/app/lending/page'

const HOUR = 3_600_000
const NOW = Date.parse('2026-08-02T12:00:00Z')

const at = (hoursAgo: number) => new Date(NOW - hoursAgo * HOUR).toISOString()

const item = (status: string, hoursAgo: number, amount = 100_000, i = 0): PipelineItem => ({
  id: `${status}-${i}`,
  status,
  createdAt: at(hoursAgo),
  requestedAmount: { amount, currency: 'CZK' },
})

function Providers({ children }: { children: React.ReactNode }) {
  return React.createElement(SessionProvider, null, React.createElement(LanguageProvider, null, children))
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('summarise', () => {
  it('counts per state and keeps the OLDEST age, not the newest', () => {
    const s = summarise([item('ASSESSMENT', 2, 1, 1), item('ASSESSMENT', 90, 1, 2)], NOW)
    expect(s.get('ASSESSMENT')?.count).toBe(2)
    // The queue's health is set by the item that has waited longest; averaging or taking the newest
    // would hide the one the desk needs to act on.
    expect(Math.round(s.get('ASSESSMENT')!.oldestHours!)).toBe(90)
  })

  it('survives an item with no timestamp instead of producing NaN', () => {
    const s = summarise([{ id: 'x', status: 'SUBMITTED' }], NOW)
    expect(s.get('SUBMITTED')?.count).toBe(1)
    expect(s.get('SUBMITTED')?.oldestHours).toBeNull()
  })
})

describe('wrap', () => {
  it('breaks a long stage name onto two lines rather than truncating it', () => {
    expect(wrap('Připraveno k čerpání')).toEqual(['Připraveno k', 'čerpání'])
    expect(wrap('Podáno')).toEqual(['Podáno'])
  })
})

describe('OriginationPipeline', () => {
  // Pin Date.now() to the same epoch the test items were built relative to; without this the
  // component's summarise(items) call uses real clock time, making items created "1 hour ago"
  // look hours-old relative to the test's fixed NOW, flipping 'ok' → 'warn'.
  beforeEach(() => { vi.useFakeTimers(); vi.setSystemTime(NOW) })
  it('tints a waiting stage by how long the oldest item has waited', () => {
    render(React.createElement(Providers, null, React.createElement(OriginationPipeline, {
      items: [item('ASSESSMENT', 1), item('FOUR_EYES', 100, 1, 2), item('KYC_PENDING', 30, 1, 3)],
      cap: 100, lang: 'en',
    })))
    expect(screen.getByTestId('stage-ASSESSMENT').getAttribute('data-tone')).toBe('ok')
    expect(screen.getByTestId('stage-KYC_PENDING').getAttribute('data-tone')).toBe('warn')
    expect(screen.getByTestId('stage-FOUR_EYES').getAttribute('data-tone')).toBe('bad')
  })

  it('never tints a TERMINAL stage by age', () => {
    render(React.createElement(Providers, null, React.createElement(OriginationPipeline, {
      items: [item('DISBURSED', 400), item('DECLINED', 400, 1, 2)],
      cap: 100, lang: 'en',
    })))
    // Disbursed 400 hours ago is finished, not 400 hours late. The first render painted these red
    // and told the desk it had problems it did not have.
    expect(screen.getByTestId('stage-DISBURSED').getAttribute('data-tone')).toBe('done')
  })

  it('says so when the list is capped, instead of presenting it as the whole book', () => {
    const items = Array.from({ length: 20 }, (_, i) => item('ASSESSMENT', 1, 1, i))
    render(React.createElement(Providers, null, React.createElement(OriginationPipeline, {
      items, cap: 20, lang: 'en',
    })))
    expect(screen.getByTestId('cap-note').textContent).toMatch(/are NOT in these numbers/)
  })

  it('does not cry "capped" when the server returned everything', () => {
    render(React.createElement(Providers, null, React.createElement(OriginationPipeline, {
      items: [item('ASSESSMENT', 1)], cap: 100, lang: 'en',
    })))
    expect(screen.getByTestId('cap-note').textContent).not.toMatch(/NOT in these numbers/)
  })
})

describe('lending console', () => {
  const APPS = [
    { ...item('ASSESSMENT', 1, 250_000, 1), partyId: 'p1' },
    { ...item('FOUR_EYES', 100, 400_000, 2), partyId: 'p2' },
    { ...item('DISBURSED', 500, 900_000, 3), partyId: 'p3' },
  ]
  const LOANS = [
    { id: 'l1', partyId: 'p9', status: 'ACTIVE', principal: { amount: 1_000_000, currency: 'CZK' } },
    { id: 'l2', partyId: 'p8', status: 'DELINQUENT', principal: { amount: 500_000, currency: 'CZK' } },
  ]

  function mockFetch() {
    const json = (b: unknown) => new Response(JSON.stringify(b), { status: 200, headers: { 'content-type': 'application/json' } })
    return vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/auth/session')) return new Response('null', { status: 200, headers: { 'content-type': 'application/json' } })
      if (url.includes('/loans/active')) {
        await new Promise(resolve => setTimeout(resolve, 50))
        return json(LOANS)
      }
      if (url.includes('/applications/recent')) {
        await new Promise(resolve => setTimeout(resolve, 50))
        return json(APPS)
      }
      return json({})
    })
  }

  // ONE render for the whole console, asserted several ways. Four separate renders of this page
  // pushed unrelated test files past their timeouts under parallel load — the suite's cost is
  // shared, so a heavy test is not only its own problem.
  it('summarises the desk: in-flight excludes finished work, trouble is flagged, stages are human', async () => {
    vi.stubGlobal('fetch', mockFetch())
    render(React.createElement(Providers, null, React.createElement(LendingPage)))

    // 3 applications, one DISBURSED — "in flight" is 2, not 3. Counting the finished one would
    // inflate the desk's own workload figure.
    await waitFor(() => expect(screen.getByText('Applications in flight').closest('.stat-card')?.textContent).toMatch(/2/))
    expect(screen.getByText('Loans in trouble').closest('.stat-card')?.textContent).toMatch(/1/)

    // A credit officer reads "Four-eyes review"; the enum stays as the title so the screen and the
    // machine can never be describing different things.
    expect(screen.getAllByText('Four-eyes review').length).toBeGreaterThan(0)
    expect(screen.getByTitle('FOUR_EYES')).toBeTruthy()
  })

  it('clicking a stage filters the queue to it', async () => {
    vi.stubGlobal('fetch', mockFetch())
    render(React.createElement(Providers, null, React.createElement(LendingPage)))

    await waitFor(() => expect(screen.getByTestId('stage-FOUR_EYES')).toBeTruthy())
    fireEvent.click(screen.getByTestId('stage-FOUR_EYES'))

    await waitFor(() => expect(screen.getByTestId('clear-stage')).toBeTruthy())
    // A filter that leaves everything visible is a filter that lied about being applied.
    expect(screen.queryByText('250,000 CZK')).toBeNull()
  })
})
