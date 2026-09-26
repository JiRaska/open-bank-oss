// SPDX-License-Identifier: Apache-2.0
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SettlementStatusPanel } from '@/components/settlement/SettlementStatusPanel'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

const id = 'bde18d9e-3e49-4f4e-98cc-a56646ccbc61'
const detail = {
  id, payerAccountId: 'd52f0505-bb8a-4a9f-b8e0-9c9c5f765876', payeeAccountId: 'ab72c875-9e17-4491-a5ef-0bdb34946a3b',
  amount: '999999999999999.9900', currency: 'CZK', status: 'BALANCE_STATE_UNKNOWN',
  createdAt: '2026-09-26T10:00:00Z', updatedAt: '2026-09-26T10:01:00Z',
}
const view = (target = id) => <LanguageProvider initialLanguage="en"><SettlementStatusPanel id={target} /></LanguageProvider>
afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('settlement financial state panel', () => {
  it('shows exact decimal text and uncertainty, then refreshes read-only to BOOKED', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(Response.json(detail))
      .mockResolvedValueOnce(Response.json({ ...detail, status: 'BOOKED' }))
    vi.stubGlobal('fetch', fetcher)
    render(view())
    expect(await screen.findByText('999999999999999.9900 CZK')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('movement is uncertain')
    fireEvent.click(screen.getByRole('button', { name: 'Refresh state' }))
    await screen.findByText('BOOKED')
    expect(screen.getByRole('status')).toHaveTextContent('transfer is booked')
    expect(fetcher).toHaveBeenCalledTimes(2)
    for (const [url, options] of fetcher.mock.calls) {
      expect(url).toBe(`/api/settlements/${id}`)
      expect(options.method).toBeUndefined()
      expect(options.body).toBeUndefined()
    }
    expect(screen.getAllByRole('button')).toHaveLength(1)
  })

  it.each(['REVERSAL_FAILED', 'LEDGER_STATE_UNKNOWN', 'LEDGER_REVERSAL_UNSUPPORTED', 'LEDGER_REVERSED'])(
    'does not label %s as successful completion', async status => {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ ...detail, status })))
      render(view())
      await screen.findByText(status)
      expect(screen.getByRole('status')).not.toHaveTextContent('transfer is booked')
    },
  )

  it('does not interpret a missing record as permission to retry', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(null, { status: 404 }))
    vi.stubGlobal('fetch', fetcher)
    render(view())
    expect(await screen.findByText(/does not prove the original request was not accepted/)).toBeInTheDocument()
    expect(fetcher).toHaveBeenCalledOnce()
    expect(screen.getAllByRole('button')).toHaveLength(1)
  })

  it('rejects an approval claim pretending to be financial state', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ ...detail, status: 'EXECUTED' })))
    render(view())
    expect(await screen.findByText(/state cannot be verified/)).toBeInTheDocument()
    expect(screen.queryByText('EXECUTED')).not.toBeInTheDocument()
  })

  it('ignores a late response for the previous transfer', async () => {
    let resolveOld!: (response: Response) => void
    const next = detail.payerAccountId
    const fetcher = vi.fn().mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
      .mockResolvedValueOnce(Response.json({ ...detail, id: next, status: 'BOOKED' }))
    vi.stubGlobal('fetch', fetcher)
    const mounted = render(view())
    await waitFor(() => expect(fetcher).toHaveBeenCalledOnce())
    mounted.rerender(view(next))
    await screen.findByText('BOOKED')
    resolveOld(Response.json(detail))
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('transfer is booked'))
    expect(screen.queryByText('BALANCE_STATE_UNKNOWN')).not.toBeInTheDocument()
  })
})
