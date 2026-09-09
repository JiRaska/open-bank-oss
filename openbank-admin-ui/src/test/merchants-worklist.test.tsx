// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
import { afterEach, describe, expect, it, vi } from 'vitest'
import React from 'react'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import MerchantsPage from '@/app/merchants/page'

vi.mock('@/components/auth/AuthGuard', () => ({ Can: ({ children }: { children: React.ReactNode }) => <>{children}</> }))

const catalogue = {
  data: [{ descriptorKey: 'BILLA', cleanName: 'Billa', category: 'GROCERIES', city: 'Praha', country: 'CZ' }],
  total: 1,
}
const worklist = [
  { descriptorKey: 'KAVARNAUDVOUKOCEK', occurrences: 42 },
  { descriptorKey: 'ALZACZ', occurrences: 7 },
]

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

/** Routes the two independent reads the page makes, so each can be failed on its own. */
function stubFetch(list: () => Response, unmatched: () => Response) {
  vi.stubGlobal('fetch', vi.fn((url: string) =>
    Promise.resolve(String(url).includes('/unmatched') ? unmatched() : list())))
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('merchant catalogue worklist', () => {
  it('shows the unmatched descriptors ranked, which is what makes the catalogue fillable', async () => {
    stubFetch(() => json(catalogue), () => json(worklist))

    render(React.createElement(LanguageProvider, null, React.createElement(MerchantsPage)))

    await waitFor(() => expect(screen.getByText('KAVARNAUDVOUKOCEK')).toBeInTheDocument())
    expect(screen.getByText('×42')).toBeInTheDocument()
    expect(screen.getByText('ALZACZ')).toBeInTheDocument()
  })

  /**
   * The catalogue and the worklist are separate reads. A worklist outage must not blank the rows
   * an operator is in the middle of editing — losing the work is a bigger harm than losing the
   * suggestions.
   */
  it('keeps the catalogue when the worklist read fails', async () => {
    stubFetch(() => json(catalogue), () => json({ message: 'boom' }, 500))

    render(React.createElement(LanguageProvider, null, React.createElement(MerchantsPage)))

    await waitFor(() => expect(screen.getByText('Billa')).toBeInTheDocument())
    expect(screen.getByText('Nothing unmatched in the recent window.')).toBeInTheDocument()
  })

  it('reports the service as unavailable when the catalogue itself cannot be read', async () => {
    stubFetch(() => json({ message: 'down' }, 503), () => json([]))

    render(React.createElement(LanguageProvider, null, React.createElement(MerchantsPage)))

    await waitFor(() => expect(screen.queryByText('Billa')).not.toBeInTheDocument())
  })
})
