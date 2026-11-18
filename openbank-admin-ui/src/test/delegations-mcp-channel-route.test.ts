// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0384 D4: the MCP channel health BFF route. Absent series must read as null (not measured),
// never 0 — at t=0, before the ADR-0383 customer path exists, that is the state of every cluster.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: vi.fn() }))
import { auth } from '@/auth'

const SESSION = { user: { accessToken: 'operator-token', roles: ['ROLE_OPERATOR'] } }

function vector(result: { metric: Record<string, string>; value: [number, string] }[]) {
  return new Response(JSON.stringify({ status: 'success', data: { resultType: 'vector', result } }), { status: 200 })
}

function respondBy(map: (q: string) => Response) {
  vi.mocked(global.fetch).mockImplementation(async (url: unknown) => {
    const q = decodeURIComponent(String(url).split('query=')[1] ?? '')
    return map(q)
  })
}

beforeEach(() => {
  vi.resetModules()
  vi.mocked(auth).mockResolvedValue(SESSION as never)
  global.fetch = vi.fn() as never
})
afterEach(() => vi.restoreAllMocks())

async function get() {
  const { GET } = await import('@/app/api/delegations/mcp-channel/route')
  return GET()
}

describe('GET /api/delegations/mcp-channel', () => {
  it('rejects an unauthenticated caller', async () => {
    vi.mocked(auth).mockResolvedValue(null as never)
    expect((await get()).status).toBe(401)
  })

  it('t=0: reachable Prometheus with no customer series reports null, never 0', async () => {
    respondBy(() => vector([]))
    const body = await (await get()).json()
    expect(body.state).toBe('ok')
    expect(body.customerCalls).toBeNull()
    expect(body.syntheticCalls).toBeNull()
    expect(body.rejectsByLayer).toBeNull()
    expect(body.activeGrants).toBeNull()
    expect(body.firingAlerts).toEqual([])
  })

  it('an unreachable Prometheus is unavailable, not an idle channel', async () => {
    vi.mocked(global.fetch).mockRejectedValue(new Error('ECONNREFUSED'))
    expect(await (await get()).json()).toEqual({ state: 'unavailable' })
  })

  it('maps measured series and only ever sends the fixed queries', async () => {
    respondBy(q => {
      if (q.includes('ALERTS')) return vector([{ metric: { alertname: 'McpCustomerDpopRejectSpike' }, value: [0, '1'] }])
      if (q.includes('by (layer)')) return vector([
        { metric: { layer: 'dpop' }, value: [0, '12.4'] },
        { metric: { layer: 'throttle' }, value: [0, '3'] },
      ])
      if (q.includes('synthetic="true"')) return vector([{ metric: {}, value: [0, '5'] }])
      if (q.includes('channel="customer"')) return vector([{ metric: {}, value: [0, '40.2'] }])
      return vector([{ metric: {}, value: [0, '0'] }])
    })
    const body = await (await get()).json()
    expect(body).toMatchObject({
      customerCalls: 40,
      syntheticCalls: 5,
      agentCalls: 0,
      rejectsByLayer: { dpop: 12, throttle: 3 },
      firingAlerts: ['McpCustomerDpopRejectSpike'],
    })
    const sent = vi.mocked(global.fetch).mock.calls.map(c => decodeURIComponent(String(c[0])))
    expect(sent).toHaveLength(5)
    for (const q of sent) expect(q).not.toMatch(/party|grant_id|jkt|sub=/)
  })
})
