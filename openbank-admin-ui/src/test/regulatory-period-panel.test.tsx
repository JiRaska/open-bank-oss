// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { RegulatoryPeriodPanel } from '@/components/closings/RegulatoryPeriodPanel'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { ROLES } from '@/lib/auth/roles'

let identity = { id: 'checker-sub', roles: [ROLES.OPERATOR] as string[], email: 'checker@openbank.test', name: 'checker' }

vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: identity }, status: 'authenticated' }),
}))

const draft = {
  id: 'e9e37077-3b14-4e52-a450-d2a6616f616d', period: '2026-07', periodType: 'MONTH',
  from: '2026-07-01', to: '2026-07-31', status: 'DRAFT', evidenceState: 'LINES_V1',
  computedAt: '2026-08-01T00:00:00Z', accountCount: 14, contentHash: 'a'.repeat(64),
  draftedBy: 'maker@openbank.test', frozenBy: null, frozenAt: null,
}

function renderPanel() {
  return render(<LanguageProvider><RegulatoryPeriodPanel /></LanguageProvider>)
}

afterEach(() => {
  vi.unstubAllGlobals()
  identity = { id: 'checker-sub', roles: [ROLES.OPERATOR], email: 'checker@openbank.test', name: 'checker' }
})

describe('Regulatory period maker/checker panel', () => {
  it('turns a missing period into an explicit maker action without exposing GL values', async () => {
    const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
      if (init?.method === 'POST') return new Response(JSON.stringify(draft), { status: 200 })
      return new Response('', { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)
    renderPanel()

    expect(await screen.findByText('No regulatory close exists for this month')).toBeInTheDocument()
    expect(screen.queryByText(/totalDebits/i)).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Create DRAFT (maker)' }))

    expect(await screen.findByText('maker@openbank.test')).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(true)
    expect(screen.getByText('14')).toBeInTheDocument()
  })

  it('does not offer self-approval as a usable path', async () => {
    identity = { id: 'maker-sub', roles: [ROLES.OPERATOR], email: 'someone-else@openbank.test', name: 'someone else' }
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ ...draft, draftedBy: 'maker-sub' }), { status: 200 })))
    renderPanel()

    expect(await screen.findByText(/You are this draft’s maker/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Freeze period (checker)' })).toBeDisabled()
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  })

  it('requires an independent successful verification and explicit confirmation before freeze', async () => {
    const frozen = { ...draft, status: 'FROZEN', frozenBy: 'checker@openbank.test', frozenAt: '2026-08-02T00:00:00Z' }
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url.endsWith('/verify')) {
        return new Response(JSON.stringify({ period: '2026-07', status: 'DRAFT', matches: true, balanced: true, recomputedAt: '2026-08-02T00:00:00Z' }), { status: 200 })
      }
      if (url.endsWith('/freeze') && init?.method === 'POST') return new Response(JSON.stringify(frozen), { status: 200 })
      return new Response(JSON.stringify(draft), { status: 200 })
    })
    vi.stubGlobal('fetch', fetchMock)
    renderPanel()

    const freeze = await screen.findByRole('button', { name: 'Freeze period (checker)' })
    expect(freeze).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Verify independently' }))
    expect(await screen.findByText('The evidence hash matches and the trial balance is balanced.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('checkbox'))
    expect(freeze).toBeEnabled()
    fireEvent.click(freeze)

    await waitFor(() => expect(screen.getByRole('link', { name: 'Open FINREP/COREP preview' })).toHaveAttribute('href', '/regulatory'))
    expect(fetchMock.mock.calls.some(([url, init]) => String(url).endsWith('/freeze') && init?.method === 'POST')).toBe(true)
  })

  it('keeps mutation controls hidden from non-operators', async () => {
    identity = { id: 'admin-sub', roles: [ROLES.ADMIN], email: 'admin@openbank.test', name: 'admin' }
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 404 })))
    renderPanel()

    expect(await screen.findByText('No regulatory close exists for this month')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Create DRAFT (maker)' })).not.toBeInTheDocument()
  })

  it('does not misrepresent an undiscovered ledger service as a missing period', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ error: 'Unknown service: ledger-service' }), {
      status: 404, headers: { 'content-type': 'application/json' },
    })))
    renderPanel()

    expect(await screen.findByText('Ledger-service is not deployed in this environment')).toBeInTheDocument()
    expect(screen.queryByText('No regulatory close exists for this month')).not.toBeInTheDocument()
  })
})
