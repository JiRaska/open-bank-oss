// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it, vi, beforeEach } from 'vitest'

vi.mock('@/auth', () => ({ auth: async () => ({ user: { accessToken: 'tok' } }) }))

/**
 * The campaign-detail bundle fans out with `Promise.all` and names the results by POSITION. That is
 * a contract nothing enforces: inserting a read in the middle hands every later name someone else's
 * result, and the types still check because each slot has the same `{data, state}` shape.
 *
 * It shipped exactly that way. `journey` ended up holding the summary object, so the page passed a
 * non-array to the funnel and the whole screen died on `TypeError: s.map is not a function` — while
 * the real 404 on /journey never surfaced, because its state had landed under `sendSummary`.
 *
 * So this asserts what each key actually came from, by giving every upstream a distinguishable body.
 */
describe('campaign detail bundle', () => {
  beforeEach(() => vi.resetModules())

  it('maps every key to the upstream it names', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        const u = String(url)
        const body = u.endsWith('/enrolments')
          ? [{ from: 'enrolments' }]
          : u.includes('/sends/summary')
            ? { from: 'summary' }
            : u.includes('/journey')
              ? [{ from: 'journey' }]
              : u.includes('/incentives')
                ? { from: 'incentives' }
              : u.includes('/sends')
                ? [{ from: 'sends' }]
                : { from: 'campaign' }
        return { ok: true, status: 200, json: async () => body, headers: new Headers({ 'x-total-count': '7', 'x-page': '0', 'x-page-size': '50' }) }
      }),
    )

    const { GET } = await import('@/app/api/campaigns/[id]/route')
    const res = await GET(new Request('http://x/api/campaigns/abc'), { params: Promise.resolve({ id: 'abc' }) })
    const d = await res.json()

    expect(d.campaign).toEqual({ from: 'campaign' })
    expect(d.enrolments).toEqual([{ from: 'enrolments' }])
    expect(d.sends.items).toEqual([{ from: 'sends' }])
    expect(d.sendSummary).toEqual({ from: 'summary' })
    expect(d.journey).toEqual([{ from: 'journey' }])
    expect(d.incentives).toEqual({ from: 'incentives' })
    expect(d.sources.incentives).toBe('ok')
  })

  /**
   * The consequence that actually reached a user: whenever the journey slot is reported as usable,
   * it must be something the funnel can iterate. A shape check here is worth more than the mapping
   * assertion alone, because it fails for ANY future way of getting a non-array into that slot.
   */
  it('never reports the journey as ok while holding a non-array', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        const u = String(url)
        // /journey is missing upstream — the state the sandbox was actually in.
        if (u.includes('/journey')) return { ok: false, status: 404, json: async () => ({ code: 'NOT_FOUND' }) }
        return { ok: true, status: 200, json: async () => ({}), headers: new Headers() }
      }),
    )

    const { GET } = await import('@/app/api/campaigns/[id]/route')
    const res = await GET(new Request('http://x/api/campaigns/abc'), { params: Promise.resolve({ id: 'abc' }) })
    const d = await res.json()

    expect(d.sources.journey).toBe('not_deployed')
    expect(Array.isArray(d.journey)).toBe(true)
  })

  it('keeps an uninitialised incentive projection unavailable instead of reporting zero outcomes', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        const u = String(url)
        if (u.includes('/incentives')) return { ok: false, status: 503, json: async () => ({ error: 'projection not ready' }), headers: new Headers() }
        return { ok: true, status: 200, json: async () => ({}), headers: new Headers() }
      }),
    )

    const { GET } = await import('@/app/api/campaigns/[id]/route')
    const res = await GET(new Request('http://x/api/campaigns/abc'), { params: Promise.resolve({ id: 'abc' }) })
    const d = await res.json()

    expect(d.incentives).toBeNull()
    expect(d.sources.incentives).toBe('not_ready')
  })

  it('reads A/B measurement only for a campaign that configured variant B', async () => {
    const fetchMock = vi.fn(async (url: string) => {
      const u = String(url)
      if (u.endsWith('/content-experiment')) {
        return { ok: true, status: 200, json: async () => ({ from: 'content-experiment' }), headers: new Headers() }
      }
      if (u.endsWith('/abc')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({ steps: [{ variantBVariables: { offerTitle: 'B' } }] }),
          headers: new Headers(),
        }
      }
      return { ok: true, status: 200, json: async () => [], headers: new Headers() }
    })
    vi.stubGlobal('fetch', fetchMock)

    const { GET } = await import('@/app/api/campaigns/[id]/route')
    const res = await GET(new Request('http://x/api/campaigns/abc'), { params: Promise.resolve({ id: 'abc' }) })
    const d = await res.json()

    expect(d.contentExperiment).toEqual({ from: 'content-experiment' })
    expect(d.sources.contentExperiment).toBe('ok')
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/campaigns/abc/content-experiment'),
      expect.anything(),
    )
  })

  it('relays a draft revision with the caller token but never a maker identity from the browser', async () => {
    const fetchMock = vi.fn(async () => ({
      ok: true,
      status: 200,
      text: async () => JSON.stringify({ id: 'abc', state: 'DRAFT', name: 'Updated draft' }),
    }))
    vi.stubGlobal('fetch', fetchMock)

    const { PUT } = await import('@/app/api/campaigns/[id]/route')
    const res = await PUT(
      new Request('http://x/api/campaigns/abc', {
        method: 'PUT',
        body: JSON.stringify({ name: 'Updated draft', createdBy: 'forged@openbank.test' }),
      }),
      { params: Promise.resolve({ id: 'abc' }) },
    )

    expect(await res.json()).toMatchObject({ state: 'ok', campaign: { name: 'Updated draft' } })
    expect(fetchMock).toHaveBeenCalledWith(
      // The BFF resolves its service host from the deployment environment.  The contract we own
      // here is the protected campaign resource and the payload it receives, not a test-only host.
      expect.stringContaining('/api/v1/campaigns/abc'),
      expect.objectContaining({
        method: 'PUT',
        headers: expect.objectContaining({ authorization: 'Bearer tok' }),
        body: expect.not.stringContaining('createdBy'),
      }),
    )
  })

  it('creates a reusable campaign draft with the caller token and no browser-supplied identity', async () => {
    const fetchMock = vi.fn(async () => ({
      ok: true,
      status: 201,
      text: async () => JSON.stringify({ id: 'copy-123', state: 'DRAFT', name: 'Copy of spring offer' }),
    }))
    vi.stubGlobal('fetch', fetchMock)

    const { POST } = await import('@/app/api/campaigns/[id]/duplicate/route')
    const res = await POST(
      new Request('http://x/api/campaigns/abc/duplicate', { method: 'POST' }),
      { params: Promise.resolve({ id: 'abc' }) },
    )

    expect(await res.json()).toMatchObject({ state: 'ok', campaign: { id: 'copy-123', state: 'DRAFT' } })
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/campaigns/abc/duplicate'),
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ authorization: 'Bearer tok' }),
      }),
    )
    expect(fetchMock.mock.calls[0]?.[1]).not.toHaveProperty('body')
  })
})
