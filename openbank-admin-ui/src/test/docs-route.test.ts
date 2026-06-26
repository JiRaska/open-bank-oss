// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root for details.

// Integration tests for GET /api/services/[name]/docs
// (ADR-0076 Layer 1 — BFF route integration tests)
//
// The route proxies Docs-as-Service: for runnable services it fetches from
// /q/openbank/docs on the live service; for 'libs' it reads an image-baked bundle.
// Language resolution order: ?lang query → openbank-admin-lang cookie → 'en'.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// Mock next/headers (server-only Next.js API not available in Vitest)
vi.mock('next/headers', () => ({
  cookies: vi.fn(),
}))

// Mock the docs loader — tests here cover the *route* contract
// (language resolution, cache headers, 404 shape), not the loader internals.
vi.mock('@/lib/services/docs', () => ({
  loadDocsIndex: vi.fn(),
}))

import { cookies } from 'next/headers'
import { loadDocsIndex } from '@/lib/services/docs'
import type { DocsIndex } from '@/lib/services/docs'

const DOCS_INDEX: DocsIndex = {
  service: 'account-service',
  version: '1.2.3',
  source: 'live',
  requestedLang: 'en',
  availableLanguages: ['en', 'cs'],
  items: [
    { slug: 'README', lang: 'en', title: 'Account Service', availableLanguages: ['en', 'cs'] },
    { slug: '01-overview', lang: 'en', title: 'Overview', availableLanguages: ['en', 'cs'] },
    { slug: '06-compliance', lang: 'en', title: 'Compliance', availableLanguages: ['en', 'cs'] },
  ],
}

function mockCookies(lang?: string) {
  vi.mocked(cookies).mockResolvedValue({
    get: vi.fn().mockReturnValue(lang ? { value: lang } : undefined),
  } as never)
}

function ctx(name: string) {
  return { params: Promise.resolve({ name }) }
}

describe('GET /api/services/[name]/docs', () => {
  beforeEach(() => {
    vi.resetModules()
    mockCookies()
  })
  afterEach(() => vi.restoreAllMocks())

  it('returns 200 with docs index when service has documentation', async () => {
    vi.mocked(loadDocsIndex).mockResolvedValue(DOCS_INDEX)
    const { GET } = await import('@/app/api/services/[name]/docs/route')
    const res = await GET(new Request('http://localhost/api/services/account/docs'), ctx('account'))
    expect(res.status).toBe(200)
    const body = await res.json()
    expect(body.service).toBe('account-service')
    expect(body.items).toHaveLength(3)
    expect(body.availableLanguages).toContain('cs')
  })

  it('returns 404 with stable JSON body when service has no docs', async () => {
    vi.mocked(loadDocsIndex).mockResolvedValue(null)
    const { GET } = await import('@/app/api/services/[name]/docs/route')
    const res = await GET(new Request('http://localhost/api/services/unknown-svc/docs'), ctx('unknown-svc'))
    expect(res.status).toBe(404)
    const body = await res.json()
    expect(body).toMatchObject({ error: expect.any(String), service: 'unknown-svc' })
  })

  it('passes ?lang query param to loadDocsIndex', async () => {
    vi.mocked(loadDocsIndex).mockResolvedValue({ ...DOCS_INDEX, requestedLang: 'cs' })
    const { GET } = await import('@/app/api/services/[name]/docs/route')
    await GET(new Request('http://localhost/api/services/account/docs?lang=cs'), ctx('account'))
    expect(loadDocsIndex).toHaveBeenCalledWith('account', 'cs')
  })

  it('falls back to cookie lang when no ?lang query param', async () => {
    mockCookies('cs')
    vi.mocked(loadDocsIndex).mockResolvedValue({ ...DOCS_INDEX, requestedLang: 'cs' })
    const { GET } = await import('@/app/api/services/[name]/docs/route')
    await GET(new Request('http://localhost/api/services/account/docs'), ctx('account'))
    expect(loadDocsIndex).toHaveBeenCalledWith('account', 'cs')
  })

  it('defaults to "en" when no lang query or cookie', async () => {
    mockCookies(undefined)
    vi.mocked(loadDocsIndex).mockResolvedValue(DOCS_INDEX)
    const { GET } = await import('@/app/api/services/[name]/docs/route')
    await GET(new Request('http://localhost/api/services/account/docs'), ctx('account'))
    expect(loadDocsIndex).toHaveBeenCalledWith('account', 'en')
  })

  it('sets short-lived cache header for live source', async () => {
    vi.mocked(loadDocsIndex).mockResolvedValue({ ...DOCS_INDEX, source: 'live' })
    const { GET } = await import('@/app/api/services/[name]/docs/route')
    const res = await GET(new Request('http://localhost/api/services/account/docs'), ctx('account'))
    expect(res.headers.get('Cache-Control')).toMatch(/s-maxage/)
  })

  it('sets no-store cache header for bundled (libs) source', async () => {
    vi.mocked(loadDocsIndex).mockResolvedValue({ ...DOCS_INDEX, source: 'bundle' })
    const { GET } = await import('@/app/api/services/[name]/docs/route')
    const res = await GET(new Request('http://localhost/api/services/libs/docs'), ctx('libs'))
    expect(res.headers.get('Cache-Control')).toBe('no-store')
  })

  it('?lang query overrides cookie lang', async () => {
    mockCookies('cs')
    vi.mocked(loadDocsIndex).mockResolvedValue({ ...DOCS_INDEX, requestedLang: 'en' })
    const { GET } = await import('@/app/api/services/[name]/docs/route')
    await GET(new Request('http://localhost/api/services/account/docs?lang=en'), ctx('account'))
    expect(loadDocsIndex).toHaveBeenCalledWith('account', 'en')
  })
})
