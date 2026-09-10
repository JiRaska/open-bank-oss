// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
import { afterEach, describe, expect, it, vi } from 'vitest'
import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import MerchantsPage from '@/app/merchants/page'

vi.mock('@/components/auth/AuthGuard', () => ({ Can: ({ children }: { children: React.ReactNode }) => <>{children}</> }))

const HASH = 'a'.repeat(64)

const withLogo = { descriptorKey: 'BILLA', cleanName: 'Billa', logoContentHash: HASH }
const withoutLogo = { descriptorKey: 'ALZACZ', cleanName: 'Alza.cz', logoContentHash: null }

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

/**
 * A fetch stub that records every call, so a test can assert what the page did NOT send — which is
 * the only way to see a client-side guard working. One that merely checks the error message would
 * pass just as well against a page that uploaded the file and rendered the server's complaint.
 */
function stubFetch(rows: unknown[], onWrite: () => Response = () => json({ contentHash: HASH }, 201)) {
  const calls: Array<{ url: string; method: string }> = []
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    calls.push({ url: String(url), method: init?.method ?? 'GET' })
    if (init?.method && init.method !== 'GET') return Promise.resolve(onWrite())
    if (String(url).includes('/unmatched')) return Promise.resolve(json([]))
    return Promise.resolve(json({ data: rows, total: rows.length }))
  }))
  return calls
}

const renderPage = () => render(React.createElement(LanguageProvider, null, React.createElement(MerchantsPage)))

const pngFile = (bytes: number, name = 'logo.png') =>
  new File([new Uint8Array(bytes)], name, { type: 'image/png' })

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('merchant logos', () => {
  it('renders the stored logo through the BFF, cache-busted by the content hash', async () => {
    stubFetch([withLogo])

    renderPage()

    const img = await screen.findByAltText('Billa') as HTMLImageElement
    expect(img.src).toContain('/api/svc/transaction-service/api/v1/merchants/BILLA/logo')
    expect(img.src).toContain('size=64')
    // The token is what makes a year-long immutable cache safe: replaced bytes are a new URL.
    expect(img.src).toContain(`v=${HASH.slice(0, 16)}`)
  })

  /**
   * A merchant with no logo gets a monogram HERE and nothing at all on the customer path. The
   * distinction is the point: on an operator screen whose job is to find the gaps a placeholder
   * makes the gap visible, while next to a real payment it would read as the merchant's actual
   * mark.
   */
  it('shows a monogram, not an image, for a merchant with no stored logo', async () => {
    stubFetch([withoutLogo])

    renderPage()

    await waitFor(() => expect(screen.getByText('Alza.cz')).toBeInTheDocument())
    expect(screen.queryByAltText('Alza.cz')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Alza.cz — no logo')).toBeInTheDocument()
  })

  it('uploads a picked file as raw bytes to the logo route', async () => {
    const calls = stubFetch([withoutLogo])

    renderPage()
    await waitFor(() => expect(screen.getByText('Alza.cz')).toBeInTheDocument())
    fireEvent.change(screen.getByTestId('logo-input-ALZACZ'), { target: { files: [pngFile(1024)] } })

    await waitFor(() => expect(calls.some(c => c.method === 'PUT')).toBe(true))
    const put = calls.find(c => c.method === 'PUT')!
    expect(put.url).toContain('/api/v1/merchants/ALZACZ/logo')
    // Reloaded afterwards, or the row would keep showing the monogram it just replaced.
    expect(calls.filter(c => c.method === 'GET').length).toBeGreaterThan(2)
  })

  it('refuses an oversized file without sending it', async () => {
    const calls = stubFetch([withoutLogo])

    renderPage()
    await waitFor(() => expect(screen.getByText('Alza.cz')).toBeInTheDocument())
    fireEvent.change(screen.getByTestId('logo-input-ALZACZ'), {
      target: { files: [pngFile(512 * 1024 + 1)] },
    })

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('the limit is 512 kB'))
    expect(calls.some(c => c.method === 'PUT')).toBe(false)
  })

  /**
   * The service says WHY it refused — not a raster format, implausible dimensions, no catalogue
   * row. Swallowing that for a generic "upload failed" would leave an operator with a file they
   * cannot fix.
   */
  it('surfaces the service’s own rejection reason', async () => {
    stubFetch([withoutLogo], () => json({ message: 'logo upload is not a PNG, JPEG or GIF image' }, 400))

    renderPage()
    await waitFor(() => expect(screen.getByText('Alza.cz')).toBeInTheDocument())
    fireEvent.change(screen.getByTestId('logo-input-ALZACZ'), { target: { files: [pngFile(64)] } })

    await waitFor(() => expect(screen.getByRole('alert'))
      .toHaveTextContent('logo upload is not a PNG, JPEG or GIF image'))
  })

  it('offers logo removal only where a logo exists', async () => {
    stubFetch([withLogo, withoutLogo])

    renderPage()
    await waitFor(() => expect(screen.getByText('Billa')).toBeInTheDocument())

    expect(screen.getByLabelText('Delete the logo for BILLA')).toBeInTheDocument()
    expect(screen.queryByLabelText('Delete the logo for ALZACZ')).not.toBeInTheDocument()
  })
})
