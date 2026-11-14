// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MAX_PARTY_LOOKUPS, fetchPartySummary, parsePartySummary, usePartySummaries } from '@/lib/aml/usePartyNames'

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`

afterEach(() => { vi.unstubAllGlobals() })

describe('parsePartySummary', () => {
  it('projects name, KYC, type, status and the masking flag', () => {
    expect(parsePartySummary({ legalName: 'Jan Novák', kycStatus: 'VERIFIED', partyType: 'INDIVIDUAL', status: 'ACTIVE', piiMasked: true }))
      .toEqual({ name: 'Jan Novák', kycStatus: 'VERIFIED', partyType: 'INDIVIDUAL', status: 'ACTIVE', masked: true })
  })

  it('falls back to the trading name and treats blanks and non-strings as absent', () => {
    expect(parsePartySummary({ legalName: '  ', tradingName: 'JN s.r.o.', kycStatus: 7, piiMasked: 'yes' }))
      .toEqual({ name: 'JN s.r.o.', kycStatus: null, partyType: null, status: null, masked: false })
  })

  it('rejects anything that is not an object', () => {
    expect(parsePartySummary(null)).toBeNull()
    expect(parsePartySummary('Jan')).toBeNull()
    expect(parsePartySummary([{ legalName: 'Jan' }])).toBeNull()
  })
})

describe('fetchPartySummary', () => {
  it('reads the party detail through the party-service BFF', async () => {
    const fetcher = vi.fn().mockResolvedValue(json({ legalName: 'Jan Novák' }))
    vi.stubGlobal('fetch', fetcher)
    expect(await fetchPartySummary(id(1))).toMatchObject({ name: 'Jan Novák' })
    expect(String(fetcher.mock.calls[0][0])).toContain(`party-service`)
    expect(String(fetcher.mock.calls[0][0])).toContain(`/api/v1/parties/${id(1)}`)
    expect(fetcher.mock.calls[0][1]).toMatchObject({ cache: 'no-store' })
  })

  it('answers null — never a guessed name — on a refusal or a network failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ error: 'forbidden' }, 403)))
    expect(await fetchPartySummary(id(1))).toBeNull()
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
    expect(await fetchPartySummary(id(1))).toBeNull()
  })
})

describe('usePartySummaries', () => {
  it('asks once per distinct party and maps each result, failures to null', async () => {
    const fetcher = vi.fn(async (url: string) =>
      String(url).includes(id(2)) ? json({}, 403) : json({ legalName: `Name ${String(url).slice(-1)}` }))
    vi.stubGlobal('fetch', fetcher)
    const { result } = renderHook(() => usePartySummaries([id(1), id(2), id(1), '', id(3)]))
    await waitFor(() => expect(Object.keys(result.current)).toHaveLength(3))
    expect(fetcher).toHaveBeenCalledTimes(3)
    expect(result.current[id(1)]?.name).toBe('Name 1')
    expect(result.current[id(2)]).toBeNull()
    expect(result.current[id(3)]?.name).toBe('Name 3')
  })

  it('never looks up more than the cap, and never more than six at once', async () => {
    let inFlight = 0, peak = 0
    vi.stubGlobal('fetch', vi.fn(async () => {
      inFlight++; peak = Math.max(peak, inFlight)
      await new Promise(r => setTimeout(r, 1))
      inFlight--
      return json({ legalName: 'X' })
    }))
    const ids = Array.from({ length: MAX_PARTY_LOOKUPS + 15 }, (_, i) => id(i + 1))
    const { result } = renderHook(() => usePartySummaries(ids))
    await waitFor(() => expect(Object.keys(result.current)).toHaveLength(MAX_PARTY_LOOKUPS))
    expect(fetch).toHaveBeenCalledTimes(MAX_PARTY_LOOKUPS)
    expect(peak).toBeLessThanOrEqual(6)
    expect(peak).toBeGreaterThan(1)
  })

  it('asks nothing for an empty register and stops writing after unmount', async () => {
    const fetcher = vi.fn().mockResolvedValue(json({ legalName: 'X' }))
    vi.stubGlobal('fetch', fetcher)
    renderHook(() => usePartySummaries([]))
    expect(fetcher).not.toHaveBeenCalled()

    let release: (r: Response) => void = () => {}
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(r => { release = r })))
    const { result, unmount } = renderHook(() => usePartySummaries([id(9)]))
    unmount()
    release(json({ legalName: 'Late' }))
    await new Promise(r => setTimeout(r, 5))
    expect(result.current[id(9)]).toBeUndefined()
  })
})
