// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  DayCloseParticipantsPanel,
  parseDayCloseCompleteness,
} from '@/components/closings/DayCloseParticipantsPanel'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

const held = {
  businessDate: '2026-10-16',
  complete: false,
  missing: ['interest'],
  refused: ['billing'],
  participants: [
    { participant: 'interest', status: 'MISSING', journalsPosted: 0, itemsProcessed: 0, reason: null, reportedBy: null, reportedAt: null, late: false },
    { participant: 'billing', status: 'REFUSED', journalsPosted: 0, itemsProcessed: 4, reason: 'capitalization: 4 posting(s) refused', reportedBy: 'service-account-openbank-billing', reportedAt: '2026-10-17T01:30:00Z', late: true },
  ],
}

function renderPanel() {
  return render(<LanguageProvider><DayCloseParticipantsPanel /></LanguageProvider>)
}

afterEach(() => vi.unstubAllGlobals())

describe('Day-close participants panel (ADR-0342)', () => {
  it('names the missing participant and the held day, and shows a refusal with its reason', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(held), { status: 200 })))
    renderPanel()

    expect(await screen.findByText(/Missing: interest; Refused \(work was not posted\): billing/)).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(/until the participant reports it ran or an operator explicitly waives it/)
    expect(screen.getByText('capitalization: 4 posting(s) refused')).toBeInTheDocument()
    expect(screen.getByText('Refused')).toBeInTheDocument()
    expect(screen.getByText('late')).toBeInTheDocument()
  })

  it('degrades through the shared unavailable panel when ledger-service is not deployed', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ error: 'Unknown service' }), { status: 404 })))
    renderPanel()

    expect(await screen.findByText(/Ledger-service is not deployed/i)).toBeInTheDocument()
    expect(screen.queryByText(/HTTP 404/)).not.toBeInTheDocument()
  })

  it('rejects a body whose status is outside the contract instead of rendering it', () => {
    expect(parseDayCloseCompleteness({ ...held, participants: [{ participant: 'x', status: 'DONE' }] })).toBeNull()
    expect(parseDayCloseCompleteness(held)?.missing).toEqual(['interest'])
    expect(parseDayCloseCompleteness(held)?.refused).toEqual(['billing'])
    // An older ledger that does not send `refused` still parses.
    const { refused: _omit, ...legacy } = held
    expect(parseDayCloseCompleteness(legacy)?.refused).toEqual([])
  })
})
