// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { useServiceResource } from '@/lib/services/useServiceResource'

function jsonRes(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('useServiceResource', () => {
  it('loads and exposes the raw body when no select is given', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonRes(200, [{ id: 'a' }, { id: 'b' }])))
    const { result } = renderHook(() => useServiceResource<Array<{ id: string }>>('/api/svc/x/api/v1/items'))
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.unavailable).toBeNull()
    expect(result.current.data).toEqual([{ id: 'a' }, { id: 'b' }])
  })

  it('applies select() to the raw body', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonRes(200, { cards: [{ id: 'c1' }] })))
    const { result } = renderHook(() =>
      useServiceResource('/api/svc/x/api/v1/cards', {
        select: (raw) => (raw as { cards: unknown[] }).cards,
      }),
    )
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.data).toEqual([{ id: 'c1' }])
  })

  it('classifies a 404 Unknown service as not_deployed and does not retry', async () => {
    const fetchMock = vi.fn(async () => jsonRes(404, { error: 'Unknown service: x' }))
    vi.stubGlobal('fetch', fetchMock)
    const { result } = renderHook(() => useServiceResource('/api/svc/x/api/v1/items'))
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.unavailable).toEqual({ kind: 'not_deployed' })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('auto-wakes a scaled-to-zero service: retries the 503 then fills in', async () => {
    let call = 0
    const fetchMock = vi.fn(async () => {
      call += 1
      return call < 2 ? jsonRes(503, { error: 'scaled_to_zero' }) : jsonRes(200, [{ id: 'woke' }])
    })
    vi.stubGlobal('fetch', fetchMock)
    const { result } = renderHook(() =>
      useServiceResource<Array<{ id: string }>>('/api/svc/x/api/v1/items', { retryDelayMs: 120 }),
    )
    // While waking, it shows the calm scaled_to_zero panel, not a hard failure.
    await waitFor(() => expect(result.current.waking).toBe(true))
    expect(result.current.unavailable).toEqual({ kind: 'scaled_to_zero' })
    // Then the retry succeeds and the data lands.
    await waitFor(() => expect(result.current.data).toEqual([{ id: 'woke' }]))
    expect(result.current.unavailable).toBeNull()
    expect(result.current.waking).toBe(false)
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('gives up as scaled_to_zero after exhausting wake retries', async () => {
    const fetchMock = vi.fn(async () => jsonRes(503, { error: 'scaled_to_zero' }))
    vi.stubGlobal('fetch', fetchMock)
    const { result } = renderHook(() =>
      useServiceResource('/api/svc/x/api/v1/items', { retryDelayMs: 5, maxWakeRetries: 2 }),
    )
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.unavailable).toEqual({ kind: 'scaled_to_zero' })
    // initial attempt + 2 retries
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  it('retains a per-attempt deadline when wake retries are disabled', async () => {
    let signal: AbortSignal | null = null
    vi.stubGlobal('fetch', vi.fn((_url: string | URL | Request, init?: RequestInit) => {
      signal = init?.signal as AbortSignal
      return new Promise<Response>((_resolve, reject) => {
        signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
      })
    }))

    const { result } = renderHook(() =>
      useServiceResource('/api/svc/x/api/v1/items', { timeoutMs: 10, maxWakeRetries: 0 }),
    )
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(signal?.aborted).toBe(true)
    expect(result.current.unavailable).toEqual({ kind: 'unreachable' })
  })

  it('does nothing when url is null', async () => {
    const fetchMock = vi.fn(async () => jsonRes(200, []))
    vi.stubGlobal('fetch', fetchMock)
    const { result } = renderHook(() => useServiceResource(null))
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(fetchMock).not.toHaveBeenCalled()
    expect(result.current.data).toBeNull()
  })

  it('aborts an obsolete request when the URL changes and on unmount', async () => {
    const signals: AbortSignal[] = []
    const fetchMock = vi.fn((_url: string | URL | Request, init?: RequestInit) => {
      const signal = init?.signal
      expect(signal).toBeInstanceOf(AbortSignal)
      signals.push(signal as AbortSignal)
      return new Promise<Response>((_resolve, reject) => {
        signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
      })
    })
    vi.stubGlobal('fetch', fetchMock)

    const { rerender, unmount } = renderHook(
      ({ url }) => useServiceResource(url, { timeoutMs: 60_000 }),
      { initialProps: { url: '/api/svc/x/api/v1/items/first' } },
    )
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))

    rerender({ url: '/api/svc/x/api/v1/items/second' })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    expect(signals[0]?.aborted).toBe(true)
    expect(signals[1]?.aborted).toBe(false)

    unmount()
    expect(signals[1]?.aborted).toBe(true)
  })
})
