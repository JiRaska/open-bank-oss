// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
import { afterEach, describe, expect, it, vi } from 'vitest'
import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import MerchantsPage from '@/app/merchants/page'

vi.mock('@/components/auth/AuthGuard', () => ({ Can: ({ children }: { children: React.ReactNode }) => <>{children}</> }))

const alza = { descriptorKey: 'ALZACZ', cleanName: 'Alza.cz', logoContentHash: null }

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

function stubFetch(sources: unknown, onWrite: () => Response = () => json({}, 201)) {
  const calls: Array<{ url: string; method: string; body?: string }> = []
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    calls.push({ url: String(url), method: init?.method ?? 'GET', body: init?.body as string | undefined })
    if (init?.method && init.method !== 'GET') return Promise.resolve(onWrite())
    const u = String(url)
    if (u.includes('/logo-sources')) {
      return Promise.resolve(sources instanceof Response ? sources : json(sources))
    }
    if (u.includes('/unmatched')) return Promise.resolve(json([]))
    if (u.includes('/locations')) return Promise.resolve(json([]))
    return Promise.resolve(json({ data: [alza], total: 1 }))
  }))
  return calls
}

const renderPage = () => render(React.createElement(LanguageProvider, null, React.createElement(MerchantsPage)))

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('merchant logo ingest', () => {
  /**
   * Ingest is off unless an allowlist was configured, and off is a real answer rather than a
   * failure. Offering an action that is certain to be refused wastes the operator's time and teaches
   * them to ignore errors.
   */
  it('does not offer the fetch action when ingest is disabled', async () => {
    stubFetch({ enabled: false, allowedHosts: [] })

    renderPage()

    await waitFor(() => expect(screen.getByText('Alza.cz')).toBeInTheDocument())
    expect(screen.queryByLabelText('Fetch a logo for ALZACZ')).not.toBeInTheDocument()
  })

  /** A failed read of the configuration is treated as off, for the same reason. */
  it('treats an unreadable configuration as disabled', async () => {
    stubFetch(json({ message: 'boom' }, 500))

    renderPage()

    await waitFor(() => expect(screen.getByText('Alza.cz')).toBeInTheDocument())
    expect(screen.queryByLabelText('Fetch a logo for ALZACZ')).not.toBeInTheDocument()
  })

  /**
   * When it IS on, the allowlist is shown. An operator who cannot see which hosts are permitted will
   * paste one that is not, and read the refusal as the feature being broken.
   */
  it('shows the allowed hosts so the operator does not guess', async () => {
    stubFetch({ enabled: true, allowedHosts: ['upload.wikimedia.org'] })

    renderPage()
    await waitFor(() => expect(screen.getByText('Alza.cz')).toBeInTheDocument())
    fireEvent.click(screen.getByLabelText('Fetch a logo for ALZACZ'))

    expect(await screen.findByText('upload.wikimedia.org')).toBeInTheDocument()
  })

  it('puts the source URL to the ingest route', async () => {
    const calls = stubFetch({ enabled: true, allowedHosts: ['upload.wikimedia.org'] })

    renderPage()
    await waitFor(() => expect(screen.getByText('Alza.cz')).toBeInTheDocument())
    fireEvent.click(screen.getByLabelText('Fetch a logo for ALZACZ'))
    fireEvent.change(await screen.findByLabelText('Image URL'), {
      target: { value: 'https://upload.wikimedia.org/alza.png' },
    })
    fireEvent.click(screen.getByText('Fetch'))

    // PUT rather than POST: the ingest is an upsert keyed by the descriptor, so a retry after a
    // timeout is safe — which is what the idempotency gate was right to insist on.
    await waitFor(() => expect(calls.some(c => c.method === 'PUT')).toBe(true))
    const put = calls.find(c => c.method === 'PUT')!
    expect(put.url).toContain('/api/v1/merchants/ALZACZ/logo/fetch')
    expect(JSON.parse(put.body!)).toMatchObject({ sourceUrl: 'https://upload.wikimedia.org/alza.png' })
  })

  /**
   * The service distinguishes "not allowlisted" from "resolves inward" from "answered a redirect",
   * and each is a different action for the operator. Collapsing them into "fetch failed" leaves them
   * with a URL they cannot fix.
   */
  it('surfaces the service’s own refusal reason', async () => {
    stubFetch(
      { enabled: true, allowedHosts: ['upload.wikimedia.org'] },
      () => json({ message: "host 'evil.example.com' is not in the configured allowlist" }, 400),
    )

    renderPage()
    await waitFor(() => expect(screen.getByText('Alza.cz')).toBeInTheDocument())
    fireEvent.click(screen.getByLabelText('Fetch a logo for ALZACZ'))
    fireEvent.change(await screen.findByLabelText('Image URL'), {
      target: { value: 'https://evil.example.com/x.png' },
    })
    fireEvent.click(screen.getByText('Fetch'))

    await waitFor(() => expect(screen.getByRole('alert'))
      .toHaveTextContent('is not in the configured allowlist'))
  })

  it('will not post an empty URL', async () => {
    const calls = stubFetch({ enabled: true, allowedHosts: ['upload.wikimedia.org'] })

    renderPage()
    await waitFor(() => expect(screen.getByText('Alza.cz')).toBeInTheDocument())
    fireEvent.click(screen.getByLabelText('Fetch a logo for ALZACZ'))

    expect((await screen.findByText('Fetch') as HTMLButtonElement).disabled).toBe(true)
    expect(calls.some(c => c.method === 'PUT')).toBe(false)
  })
})
