// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// RTL coverage for the EntityChip (ADR-0231 D3): pre-resolved labels render directly,
// unresolved ids resolve through the BFF, failures fall back to a shortened UUID, and the chip
// always deep-links to the entity.

import { render, screen, waitFor } from '@testing-library/react'
import type { ReactElement, ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SessionProvider } from 'next-auth/react'
import { EntityChip } from '@/components/entities/EntityChip'
import { ROLES } from '@/lib/auth/roles'

const sessionState = vi.hoisted(() => ({ roles: [] as string[] }))
vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: { roles: sessionState.roles } } }),
  SessionProvider: ({ children }: { children: ReactNode }) => children,
}))

const PARTY_ID = 'b7c1a2d3-1111-4000-8000-0000000000aa'
const ACCOUNT_ID = 'c8d2b3e4-2222-4000-8000-0000000000bb'

describe('EntityChip (ADR-0231 D3)', () => {
  const renderChip = (chip: ReactElement) => render(
    <SessionProvider session={{ user: { roles: [ROLES.VIEWER] } } as never}>{chip}</SessionProvider>,
  )

  beforeEach(() => {
    sessionState.roles = [ROLES.VIEWER]
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ legalName: 'Jan Novák' }), { status: 200 }),
    ))
  })
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('renders a pre-resolved label as a deep-link without fetching', () => {
    renderChip(<EntityChip type="party" id={PARTY_ID} label="Jan Novák" />)
    const link = screen.getByRole('link')
    expect(link.getAttribute('href')).toBe(`/parties/${PARTY_ID}`)
    expect(link.textContent).toContain('Jan Novák')
    expect(vi.mocked(fetch)).not.toHaveBeenCalled()
  })

  it('resolves the label through the BFF when only the id is known', async () => {
    renderChip(<EntityChip type="party" id={PARTY_ID} />)
    await waitFor(() => expect(screen.getByText('Jan Novák')).toBeTruthy())
    const [url] = vi.mocked(fetch).mock.calls[0] as [string]
    expect(url).toContain(`/api/v1/parties/${PARTY_ID}`)
  })

  it('links accounts to /accounts and resolves accountNumber', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ accountNumber: '192000145399/0800' }), { status: 200 }),
    ))
    renderChip(<EntityChip type="account" id={ACCOUNT_ID} />)
    await waitFor(() => expect(screen.getByText('192000145399/0800')).toBeTruthy())
    expect(screen.getByRole('link').getAttribute('href')).toBe(`/accounts/${ACCOUNT_ID}`)
  })

  it('falls back to a shortened UUID when resolution fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('down')))
    renderChip(<EntityChip type="party" id={PARTY_ID} />)
    await waitFor(() => expect(screen.getByRole('link').textContent).toContain('b7c1a2d3…'))
  })

  it('never shows the previous entity label while a new identity is resolving', async () => {
    const secondId = 'd9e3c4f5-3333-4000-8000-0000000000cc'
    let resolveSecond!: (response: Response) => void
    const secondResponse = new Promise<Response>(resolve => { resolveSecond = resolve })
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ legalName: 'Jan Novák' }), { status: 200 }))
      .mockReturnValueOnce(secondResponse))

    const view = renderChip(<EntityChip type="party" id={PARTY_ID} />)
    await screen.findByText('Jan Novák')

    view.rerender(
      <SessionProvider session={{ user: { roles: [ROLES.VIEWER] } } as never}>
        <EntityChip type="party" id={secondId} />
      </SessionProvider>,
    )

    expect(screen.queryByText('Jan Novák')).toBeNull()
    expect(screen.getByText('d9e3c4f5…')).toBeTruthy()

    resolveSecond(new Response(JSON.stringify({ legalName: 'Marie Nová' }), { status: 200 }))
    await screen.findByText('Marie Nová')
  })

  it('masks a resolver-loaded party name immediately when party permission is revoked', async () => {
    const view = renderChip(<EntityChip type="party" id={PARTY_ID} />)
    await screen.findByText('Jan Novák')

    sessionState.roles = []
    view.rerender(
      <SessionProvider session={{ user: { roles: [] } } as never}>
        <EntityChip type="party" id={PARTY_ID} />
      </SessionProvider>,
    )

    expect(screen.queryByRole('link')).toBeNull()
    expect(screen.queryByText('Jan Novák')).toBeNull()
    expect(screen.getByText('b7c1a2d3…')).toBeTruthy()
  })

  it('ignores a resolver response that arrives after party permission is revoked', async () => {
    let resolveParty!: (response: Response) => void
    const response = new Promise<Response>(resolve => { resolveParty = resolve })
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(response))

    const view = renderChip(<EntityChip type="party" id={PARTY_ID} />)
    await waitFor(() => expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1))

    sessionState.roles = []
    view.rerender(
      <SessionProvider session={{ user: { roles: [] } } as never}>
        <EntityChip type="party" id={PARTY_ID} />
      </SessionProvider>,
    )
    resolveParty(new Response(JSON.stringify({ legalName: 'Jan Novák' }), { status: 200 }))

    await waitFor(() => expect(screen.queryByText('Jan Novák')).toBeNull())
    expect(screen.queryByRole('link')).toBeNull()
    expect(screen.getByText('b7c1a2d3…')).toBeTruthy()
  })
})
