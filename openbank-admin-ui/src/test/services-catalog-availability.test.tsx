// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'

vi.mock('@/lib/i18n/LanguageContext', () => ({
  useLanguage: () => ({ t: (_cs: string, en: string) => en }),
}))
vi.mock('@/components/governance/CatalogDriftBanner', () => ({ CatalogDriftBanner: () => null }))

import ServicesDocsOverviewPage from '@/app/services/page'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('service documentation fleet count', () => {
  it('omits the count when the catalog API returns its 200 unavailable envelope', async () => {
    const fetch = vi.fn(async (input: string) => {
      if (input === '/api/catalog/services') {
        return new Response(JSON.stringify({ available: false, services: [] }), { status: 200 })
      }
      if (input === '/api/services/health') {
        return new Response(JSON.stringify({ source: 'static', services: [] }), { status: 200 })
      }
      if (input === '/api/services/libs/docs') {
        return new Response(JSON.stringify({ items: [{ slug: 'README', title: 'Library docs' }] }), { status: 200 })
      }
      return new Response(null, { status: 404 })
    })
    vi.stubGlobal('fetch', fetch)

    render(<ServicesDocsOverviewPage />)

    await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/catalog/services', { cache: 'no-store' }))
    await screen.findByText('Shared infrastructure library for the whole microservice fleet')
    await screen.findByText('Service inventory is unavailable; only bundled library documentation is shown.')
    expect(screen.getByText(/inventory unavailable/)).toBeTruthy()
    expect(screen.getByText('1 of 1 bundled entries')).toBeTruthy()
    expect(screen.queryByText(/static catalog/)).toBeNull()
    expect(screen.queryByText('1 of 1 documentation entries')).toBeNull()
    expect(screen.queryByText('Shared infrastructure library for all 0 microservices')).toBeNull()
    expect(fetch.mock.calls.filter(([url]) => url === '/api/catalog/services')).toHaveLength(1)
  })

  it('renders an actual count from an available catalog', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string) => {
      if (input === '/api/catalog/services') {
        return new Response(JSON.stringify({
          available: true,
          services: [{ name: 'openbank-account-service', short: 'account-service', kind: 'service', runnable: true, port: 8100 }],
        }), { status: 200 })
      }
      if (input === '/api/services/health') {
        return new Response(JSON.stringify({ source: 'static', services: [] }), { status: 200 })
      }
      if (input === '/api/services/libs/docs') {
        return new Response(JSON.stringify({ items: [{ slug: 'README', title: 'Library docs' }] }), { status: 200 })
      }
      return new Response(null, { status: 404 })
    }))

    render(<ServicesDocsOverviewPage />)

    await screen.findByText('Shared infrastructure library for all 1 microservices')
    await screen.findByText(/static catalog/)
    expect(screen.getByText('Expected services come from the build catalog; no live cluster inventory is available.')).toBeTruthy()
    expect(screen.getByText('2 of 2 documentation entries')).toBeTruthy()
  })

  it('does not label bundled documentation as live when the cluster reports zero services', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string) => {
      if (input === '/api/catalog/services') {
        return new Response(JSON.stringify({ available: false, services: [] }), { status: 200 })
      }
      if (input === '/api/services/health') {
        return new Response(JSON.stringify({ source: 'kubernetes', services: [] }), { status: 200 })
      }
      if (input === '/api/services/libs/docs') {
        return new Response(JSON.stringify({ items: [{ slug: 'README', title: 'Library docs' }] }), { status: 200 })
      }
      return new Response(null, { status: 404 })
    }))

    render(<ServicesDocsOverviewPage />)

    await screen.findByText('The live cluster reports zero service workloads and the build catalog is unavailable; only bundled library documentation is shown.')
    expect(screen.getByText(/no live workloads; catalog unavailable/)).toBeTruthy()
    expect(screen.getByText('1 of 1 bundled entries')).toBeTruthy()
    expect(screen.queryByText(/live from cluster/)).toBeNull()
    expect(screen.queryByText(/inventory unavailable/)).toBeNull()
  })

  it('keeps catalog entries distinct from an empty live inventory', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string) => {
      if (input === '/api/catalog/services') {
        return new Response(JSON.stringify({
          available: true,
          services: [{ name: 'openbank-account-service', short: 'account-service', kind: 'service', runnable: true, port: 8100 }],
        }), { status: 200 })
      }
      if (input === '/api/services/health') {
        return new Response(JSON.stringify({ source: 'kubernetes', services: [] }), { status: 200 })
      }
      if (input === '/api/services/libs/docs') {
        return new Response(JSON.stringify({ items: [{ slug: 'README', title: 'Library docs' }] }), { status: 200 })
      }
      return new Response(null, { status: 404 })
    }))

    render(<ServicesDocsOverviewPage />)

    await screen.findByText('Expected services come from the build catalog; the live cluster reports zero service workloads.')
    expect(screen.getByText(/build catalog; no live workloads/)).toBeTruthy()
    expect(screen.getByText('2 of 2 documentation entries')).toBeTruthy()
    expect(screen.queryByText(/live from cluster/)).toBeNull()
  })
})
