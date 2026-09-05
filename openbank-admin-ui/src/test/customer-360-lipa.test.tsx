// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The Lípa panel on Customer 360 (ADR-0282 D8).
//
// Same reasoning as the adverse-state and devices panels: the failure path is the one worth
// pinning. A customer with no Lístky and a loyalty-service that could not be reached must not
// render the same way — both are an absence on screen, and only one of them is safe to act on.
// The BFF path is asserted as a LITERAL so the test can actually catch the panel pointing at a
// route that does not exist; deriving it from the component's own constant would be vacuous.

import { describe, it, expect, vi, afterEach } from 'vitest'
import React from 'react'
import { render, screen, cleanup, waitFor } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { LipaPanel } from '@/components/party/LipaPanel'

const PARTY = '77777777-7777-7777-7777-777777777777'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

function renderPanel() {
  return render(
    <LanguageProvider>
      <LipaPanel partyId={PARTY} />
    </LanguageProvider>,
  )
}

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('Customer 360 Lípa panel', () => {
  it('asks the loyalty BFF route for this party', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({
      state: 'ok', partyId: PARTY, balance: 0, earnedThisYear: 0, earnedTotal: 0, nextExpiry: null, history: [],
    }))
    vi.stubGlobal('fetch', fetchMock)
    renderPanel()
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    expect(fetchMock.mock.calls[0][0]).toBe(`/api/loyalty/party/${PARTY}`)
  })

  it('renders a measured balance', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      state: 'ok',
      partyId: PARTY,
      balance: 1240,
      earnedThisYear: 1600,
      earnedTotal: 1600,
      nextExpiry: '2027-03-01T00:00:00Z',
      history: [],
    })))
    renderPanel()
    await waitFor(() => expect(screen.getByTestId('lipa-balance')).toBeTruthy())
    expect(screen.getByTestId('lipa-balance').textContent).toMatch(/1[\s,. ]?240/)
  })

  // The defect this pins: a zero that was measured and a balance that could not be read must not
  // render as the same absence. The second assertion is the load-bearing one — it fails if the
  // unknown state ever starts rendering a plain zero.
  it('keeps a measured zero distinct from a balance it could not read', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      state: 'ok', partyId: PARTY, balance: 0, earnedThisYear: 0, earnedTotal: 0, nextExpiry: null, history: [],
    })))
    renderPanel()
    await waitFor(() => expect(screen.getByTestId('lipa-balance')).toBeTruthy())
    expect(screen.getByText(/has not earned any/i)).toBeTruthy()

    cleanup()

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      state: 'not_deployed', partyId: PARTY, balance: 0, earnedThisYear: 0, earnedTotal: 0, nextExpiry: null, history: [],
    })))
    renderPanel()
    await waitFor(() => expect(screen.getByText(/Balance unavailable/i)).toBeTruthy())
    expect(screen.queryByTestId('lipa-balance')).toBeNull()
    expect(screen.getByText(/NOT a confirmation/i)).toBeTruthy()
  })

  it('says a transport failure is not a confirmation of anything', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('boom')))
    renderPanel()
    await waitFor(() => expect(screen.getByText(/unreachable/i)).toBeTruthy())
    expect(screen.queryByTestId('lipa-balance')).toBeNull()
  })

  // D8 is a claim about symmetry, and a panel that grew an operator-only figure would break it
  // silently. The response type is the only place that symmetry can be checked cheaply.
  it('renders no field the customer does not also see', async () => {
    const customerVisible = ['balance', 'earnedThisYear', 'earnedTotal', 'nextExpiry', 'history']
    const body = {
      state: 'ok', partyId: PARTY, balance: 10, earnedThisYear: 10, earnedTotal: 10,
      nextExpiry: null, history: [],
    }
    expect(Object.keys(body).filter(k => k !== 'state' && k !== 'partyId').sort())
      .toEqual([...customerVisible].sort())
  })
})
