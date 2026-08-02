// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it, vi, beforeEach } from 'vitest'
import { resolvePartyNames } from '@/lib/campaigns/party-names'

/**
 * The campaign console showed `05a02ef1` where a marketer needed a name. The lookup crosses into
 * party-service, so the properties that matter are about blast radius and failure, not formatting.
 */
describe('party name resolution', () => {
  beforeEach(() => vi.unstubAllGlobals())

  it('asks party-service once per DISTINCT id', async () => {
    const fetchMock = vi.fn(async () => ({ ok: true, json: async () => ({ legalName: 'Jana Nováková' }) }))
    vi.stubGlobal('fetch', fetchMock)

    // The same party appears on several rows — the send log repeats it across steps.
    const names = await resolvePartyNames({}, ['p1', 'p1', 'p2', 'p1'])

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(names).toEqual({ p1: 'Jana Nováková', p2: 'Jana Nováková' })
  })

  /**
   * The screen must survive a name it cannot get. A campaign going blank because party-service is
   * slow would trade a readability problem for an outage.
   */
  it('omits a party it cannot resolve rather than failing', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) =>
        String(url).includes('gone')
          ? { ok: false, status: 404, json: async () => ({}) }
          : { ok: true, json: async () => ({ legalName: 'Petr Svoboda' }) },
      ),
    )

    const names = await resolvePartyNames({}, ['ok', 'gone'])

    expect(names).toEqual({ ok: 'Petr Svoboda' })
    expect(names.gone).toBeUndefined()
  })

  it('survives party-service throwing outright', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('connection refused') }))

    await expect(resolvePartyNames({}, ['p1'])).resolves.toEqual({})
  })

  /** One console request must not become an unbounded burst against party-service. */
  it('caps the fan-out', async () => {
    const fetchMock = vi.fn(async () => ({ ok: true, json: async () => ({ legalName: 'X' }) }))
    vi.stubGlobal('fetch', fetchMock)

    await resolvePartyNames({}, Array.from({ length: 500 }, (_, i) => `p${i}`))

    expect(fetchMock.mock.calls.length).toBeLessThanOrEqual(60)
  })

  it('does not call out at all when there is nothing to resolve', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    expect(await resolvePartyNames({}, ['', ''])).toEqual({})
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
