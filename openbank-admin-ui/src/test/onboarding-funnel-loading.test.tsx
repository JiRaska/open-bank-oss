// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import OnboardingPage from '@/app/onboarding/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

const FUNNEL = { REGISTERED: 12, KYC_OPEN: 4, KYC_DOCUMENTS_REQUIRED: 2, KYC_UNDER_REVIEW: 1, SCA_PENDING: 3, ACTIVE: 40, BLOCKED: 1 }
const RECORDS = { items: [], total: 0, page: 0, size: 20 }

function response(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

function renderPage() {
  return render(<LanguageProvider initialLanguage="en"><OnboardingPage /></LanguageProvider>)
}

function routedFetch(handlers: { funnel: () => Promise<Response>; records: () => Promise<Response> }) {
  return vi.fn((input: RequestInfo | URL) => {
    const url = String(input)
    if (url.includes('/onboarding/funnel')) return handlers.funnel()
    if (url.includes('/onboarding/records')) return handlers.records()
    throw new Error(`unexpected fetch: ${url}`)
  })
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('onboarding funnel loading (issue #8233)', () => {
  it('never renders a fabricated 0 for the funnel tiles while the request is in flight', async () => {
    const funnelDeferred = deferred<Response>()
    vi.stubGlobal('fetch', routedFetch({
      funnel: () => funnelDeferred.promise,
      records: () => Promise.resolve(response(200, RECORDS)),
    }))

    renderPage()

    // Explicit, accessible loading announcement — not a silent zero.
    expect(await screen.findByRole('status', { name: 'Loading funnel counts…' })).toBeInTheDocument()
    expect(screen.queryByText('0')).not.toBeInTheDocument()

    await act(async () => {
      funnelDeferred.resolve(response(200, FUNNEL))
      await funnelDeferred.promise
    })

    expect(await screen.findByText('12')).toBeVisible()
    expect(screen.queryByRole('status', { name: 'Loading funnel counts…' })).not.toBeInTheDocument()
  })

  it('keeps stage tiles unclickable-as-zero and shows real counts once the funnel resolves', async () => {
    vi.stubGlobal('fetch', routedFetch({
      funnel: () => Promise.resolve(response(200, FUNNEL)),
      records: () => Promise.resolve(response(200, RECORDS)),
    }))

    renderPage()

    const group = await screen.findByRole('group', { name: 'Onboarding stage filters' })
    expect(within(group).getByText('12')).toBeVisible()
    expect(within(group).getByText('40')).toBeVisible()
    fireEvent.click(within(group).getByRole('button', { name: /Registered/ }))
    expect(within(group).getByRole('button', { name: /Registered/ })).toHaveAttribute('aria-pressed', 'true')
  })

  it('keeps Refresh busy until BOTH the records and the funnel requests resolve', async () => {
    const funnelDeferred = deferred<Response>()
    const recordsDeferred = deferred<Response>()
    vi.stubGlobal('fetch', routedFetch({
      funnel: () => Promise.resolve(response(200, FUNNEL)),
      records: () => Promise.resolve(response(200, RECORDS)),
    }))

    renderPage()
    await screen.findByText('12')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Refresh onboarding' })).not.toBeDisabled())

    // Re-arm fetch: funnel resolves quickly, records stays pending.
    vi.stubGlobal('fetch', routedFetch({
      funnel: () => funnelDeferred.promise,
      records: () => recordsDeferred.promise,
    }))
    fireEvent.click(screen.getByRole('button', { name: 'Refresh onboarding' }))

    expect(screen.getByRole('button', { name: 'Refresh onboarding' })).toBeDisabled()

    await act(async () => {
      funnelDeferred.resolve(response(200, FUNNEL))
      await funnelDeferred.promise
    })
    // Funnel alone resolved — records is still pending, so Refresh must stay busy.
    expect(screen.getByRole('button', { name: 'Refresh onboarding' })).toBeDisabled()

    await act(async () => {
      recordsDeferred.resolve(response(200, RECORDS))
      await recordsDeferred.promise
    })

    await waitFor(() => expect(screen.getByRole('button', { name: 'Refresh onboarding' })).not.toBeDisabled())
  })

  it('does not fabricate a zero when the funnel request fails', async () => {
    vi.stubGlobal('fetch', routedFetch({
      funnel: () => Promise.reject(new Error('network down')),
      records: () => Promise.resolve(response(200, RECORDS)),
    }))

    renderPage()

    expect(await screen.findByText(/onboarding-service/i)).toBeVisible()
    expect(screen.queryByRole('group', { name: 'Onboarding stage filters' })).not.toBeInTheDocument()
    expect(screen.queryByText('0')).not.toBeInTheDocument()
  })
})
