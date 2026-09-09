// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
import { afterEach, describe, expect, it, vi } from 'vitest'
import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import MerchantsPage from '@/app/merchants/page'

const merchant = {
  descriptorKey: 'BILLA',
  cleanName: 'Billa',
  logoUrl: null,
  logoContentHash: 'a'.repeat(64),
  category: null,
  lat: null,
  lon: null,
  city: null,
  country: null,
  updatedAt: '2026-09-09T08:00:00Z',
}
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })

function renderPage(write: () => Response = () => json({}, 204)) {
  const calls: Array<{ url: string; method: string }> = []
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    calls.push({ url: String(url), method })
    if (method !== 'GET') return Promise.resolve(write())
    if (String(url).includes('/unmatched')) return Promise.resolve(json([]))
    return Promise.resolve(json({ data: [merchant], total: 1 }))
  }))
  render(<LanguageProvider><MerchantsPage /></LanguageProvider>)
  return calls
}

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('merchant destructive action review', () => {
  it('does not delete an entry until the operator confirms its exact impact', async () => {
    const calls = renderPage()
    fireEvent.click(await screen.findByLabelText('Delete BILLA'))

    const dialog = screen.getByRole('alertdialog', { name: 'Delete the merchant entry?' })
    expect(dialog).toHaveTextContent('Billa')
    expect(dialog).toHaveTextContent('BILLA')
    expect(dialog).toHaveTextContent('will no longer be enriched')
    expect(calls.some(call => call.method === 'DELETE')).toBe(false)

    fireEvent.click(screen.getByRole('button', { name: 'Delete entry' }))
    await waitFor(() => expect(calls.some(call => call.method === 'DELETE' && !call.url.endsWith('/logo'))).toBe(true))
  })

  it('distinguishes logo removal and preserves the dialog for a retry after failure', async () => {
    const calls = renderPage(() => json({ message: 'down' }, 503))
    fireEvent.click(await screen.findByLabelText('Delete the logo for BILLA'))

    const dialog = screen.getByRole('alertdialog', { name: 'Delete the merchant logo?' })
    expect(dialog).toHaveTextContent('only the stored image will be removed')
    fireEvent.click(screen.getByRole('button', { name: 'Delete logo' }))

    await waitFor(() => expect(calls.some(call => call.method === 'DELETE' && call.url.endsWith('/logo'))).toBe(true))
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('could not be deleted')
  })

  it('puts initial focus on the safe action and lets Escape cancel', async () => {
    renderPage()
    fireEvent.click(await screen.findByLabelText('Delete BILLA'))

    expect(screen.getByRole('button', { name: 'Keep' })).toHaveFocus()
    fireEvent.keyDown(screen.getByRole('alertdialog'), { key: 'Escape' })
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })
})
