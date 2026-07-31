// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// RTL coverage for the EntityChip (ADR-0231 D3): pre-resolved labels render directly,
// unresolved ids resolve through the BFF, failures fall back to a shortened UUID, and the chip
// always deep-links to the entity.

import { render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { EntityChip } from '@/components/entities/EntityChip'

const PARTY_ID = 'b7c1a2d3-1111-4000-8000-0000000000aa'
const ACCOUNT_ID = 'c8d2b3e4-2222-4000-8000-0000000000bb'

describe('EntityChip (ADR-0231 D3)', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ legalName: 'Jan Novák' }), { status: 200 }),
    ))
  })
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('renders a pre-resolved label as a deep-link without fetching', () => {
    render(<EntityChip type="party" id={PARTY_ID} label="Jan Novák" />)
    const link = screen.getByRole('link')
    expect(link.getAttribute('href')).toBe(`/parties/${PARTY_ID}`)
    expect(link.textContent).toContain('Jan Novák')
    expect(vi.mocked(fetch)).not.toHaveBeenCalled()
  })

  it('resolves the label through the BFF when only the id is known', async () => {
    render(<EntityChip type="party" id={PARTY_ID} />)
    await waitFor(() => expect(screen.getByText('Jan Novák')).toBeTruthy())
    const [url] = vi.mocked(fetch).mock.calls[0] as [string]
    expect(url).toContain(`/api/v1/parties/${PARTY_ID}`)
  })

  it('links accounts to /accounts and resolves accountNumber', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ accountNumber: '192000145399/0800' }), { status: 200 }),
    ))
    render(<EntityChip type="account" id={ACCOUNT_ID} />)
    await waitFor(() => expect(screen.getByText('192000145399/0800')).toBeTruthy())
    expect(screen.getByRole('link').getAttribute('href')).toBe(`/accounts/${ACCOUNT_ID}`)
  })

  it('falls back to a shortened UUID when resolution fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('down')))
    render(<EntityChip type="party" id={PARTY_ID} />)
    await waitFor(() => expect(screen.getByRole('link').textContent).toContain('b7c1a2d3…'))
  })
})
