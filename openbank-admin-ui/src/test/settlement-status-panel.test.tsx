// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
vi.mock('next-auth/react', () => ({ useSession: () => ({ data: { user: { roles: ['ROLE_OPERATOR'] } } }) }))
import { SettlementStatusPanel } from '@/components/settlement/SettlementStatusPanel'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

const id = 'bde18d9e-3e49-4f4e-98cc-a56646ccbc61'
const detail = {
  id, payerAccountId: 'd52f0505-bb8a-4a9f-b8e0-9c9c5f765876', payeeAccountId: 'ab72c875-9e17-4491-a5ef-0bdb34946a3b',
  amount: '999999999999999.9900', currency: 'CZK', status: 'PENDING',
  createdAt: '2026-09-26T10:00:00Z', updatedAt: '2026-09-26T10:01:00Z',
  recoveryRequired: true, recoveryReason: 'BALANCE_STATE_UNKNOWN',
}
const booked = { ...detail, status: 'BOOKED', recoveryRequired: false, recoveryReason: null }
const view = (target = id) => <LanguageProvider initialLanguage="en"><SettlementStatusPanel id={target} /></LanguageProvider>
const settlementCalls = (fetcher: ReturnType<typeof vi.fn>) =>
  fetcher.mock.calls.filter(([url]) => String(url).startsWith('/api/settlements/'))
// EntityChip resolves account labels through the BFF; answer those with a not-found so the panel's
// own reads stay countable.
const route = (settlement: () => Promise<Response>) => vi.fn((url: string) =>
  String(url).startsWith('/api/settlements/') ? settlement() : Promise.resolve(new Response(null, { status: 404 })))
afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('settlement status panel', () => {
  it('shows exact decimal text and uncertainty, then refreshes read-only to BOOKED', async () => {
    const answers = [detail, booked]
    const fetcher = route(() => Promise.resolve(Response.json(answers.shift())))
    vi.stubGlobal('fetch', fetcher)
    render(view())
    expect(await screen.findByText('999999999999999.9900 CZK')).toBeInTheDocument()
    expect(screen.getAllByRole('status')[0]).toHaveTextContent('may have committed')
    fireEvent.click(screen.getByRole('button', { name: 'Refresh state' }))
    await screen.findByText('BOOKED')
    expect(screen.getAllByRole('status')[0]).toHaveTextContent('transfer is booked')
    const calls = settlementCalls(fetcher)
    expect(calls).toHaveLength(2)
    for (const [url, options] of calls) {
      expect(url).toBe(`/api/settlements/${id}`)
      expect(options.method).toBeUndefined()
      expect(options.body).toBeUndefined()
    }
  })

  it('never renders the transfer or account ids as primary text', async () => {
    vi.stubGlobal('fetch', route(() => Promise.resolve(Response.json(booked))))
    render(view())
    await screen.findByText('BOOKED')
    for (const raw of [detail.id, detail.payerAccountId, detail.payeeAccountId]) {
      expect(screen.queryByText(raw)).not.toBeInTheDocument()
    }
    expect(screen.getByText('Settlement')).toBeInTheDocument()
  })

  it.each(['REVERSAL_FAILED', 'LEDGER_STATE_UNKNOWN', 'LEDGER_REVERSAL_UNSUPPORTED', 'LEDGER_REVERSED'])(
    'does not label %s as successful completion', async status => {
      vi.stubGlobal('fetch', route(() => Promise.resolve(Response.json({ ...booked, status }))))
      render(view())
      await screen.findByText(status)
      expect(screen.getAllByRole('status')[0]).not.toHaveTextContent('transfer is booked')
    },
  )

  it('does not interpret a missing record as permission to retry', async () => {
    const fetcher = route(() => Promise.resolve(new Response(null, { status: 404 })))
    vi.stubGlobal('fetch', fetcher)
    render(view())
    expect(await screen.findByText(/does not prove the original request was not accepted/)).toBeInTheDocument()
    expect(settlementCalls(fetcher)).toHaveLength(1)
    expect(screen.getAllByRole('button')).toHaveLength(1)
  })

  it('rejects an approval claim pretending to be financial state', async () => {
    vi.stubGlobal('fetch', route(() => Promise.resolve(Response.json({ ...booked, status: 'EXECUTED' }))))
    render(view())
    expect(await screen.findByText(/state cannot be verified/)).toBeInTheDocument()
    expect(screen.queryByText('EXECUTED')).not.toBeInTheDocument()
  })

  it('ignores a late response for the previous transfer', async () => {
    let resolveOld!: (response: Response) => void
    const next = detail.payerAccountId
    const answers: Array<() => Promise<Response>> = [
      () => new Promise(resolve => { resolveOld = resolve }),
      () => Promise.resolve(Response.json({ ...booked, id: next })),
    ]
    const fetcher = route(() => answers.shift()!())
    vi.stubGlobal('fetch', fetcher)
    const mounted = render(view())
    await waitFor(() => expect(settlementCalls(fetcher)).toHaveLength(1))
    mounted.rerender(view(next))
    await screen.findByText('BOOKED')
    resolveOld(Response.json(detail))
    await waitFor(() => expect(screen.getAllByRole('status')[0]).toHaveTextContent('transfer is booked'))
    expect(screen.queryByText(/may have committed/)).not.toBeInTheDocument()
  })
})
