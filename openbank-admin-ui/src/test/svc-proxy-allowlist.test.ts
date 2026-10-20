// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// /api/svc/<key> sends the operator's bearer to an upstream chosen by <key>. Both resolution paths
// (off-cluster allowlist, in-cluster discovery map) must treat the key as DATA: Object.prototype
// members (`constructor`, `__proto__`, `toString`) are truthy on a plain `{}` and used to resolve to
// `http://constructor.undefined.svc:undefined` with the bearer attached. The in-cluster path must
// additionally be gated by the catalog allowlist, so a discovered workload that is not a catalog
// business service (an agent, a sink, an unknown deployment) is not reachable through the BFF.

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

const state = vi.hoisted(() => ({ inCluster: false, status: 'complete' as 'complete' | 'partial' | 'unavailable', services: [] as any[] }))

vi.mock('@/auth', () => ({ auth: async () => ({ user: { accessToken: 'tok', email: 'op@example.com' } }) }))
vi.mock('@/lib/discovery', () => ({
  inCluster: () => state.inCluster,
  resolveInClusterService: async (name: string) => {
    const matches = state.services.filter((service) => service.name === name)
    if (matches.length > 1) return { state: 'unknown', coverage: state.status === 'complete' ? 'partial' : state.status }
    if (matches.length === 1) return { state: 'found', service: matches[0], baseUrl: `http://${name}.${matches[0].namespace}.svc:${matches[0].port}`, coverage: state.status === 'partial' ? 'partial' : 'complete' }
    return state.status === 'complete' ? { state: 'not_found', coverage: 'complete' } : { state: 'unknown', coverage: state.status }
  },
}))

import { GET } from '@/app/api/svc/[service]/[...path]/route'
import { NextRequest } from 'next/server'

async function call(service: string) {
  const req = new NextRequest(`http://localhost/api/svc/${encodeURIComponent(service)}/x`)
  return GET(req, { params: Promise.resolve({ service, path: ['x'] }) })
}

let fetchMock: ReturnType<typeof vi.fn>
beforeEach(() => {
  state.status = 'complete'
  state.services = [
    { name: 'account-service', namespace: 'core', port: 8100, scaledToZero: false },
    { name: 'mcp-service', namespace: 'agents', port: 8150, scaledToZero: false },
    { name: 'devops-agent', namespace: 'agents', port: 8142, scaledToZero: false },
    { name: 'not-in-catalog-service', namespace: 'x', port: 9999, scaledToZero: false },
  ]
  fetchMock = vi.fn(async () => new Response('{}', { status: 200 }))
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => { vi.unstubAllGlobals(); state.inCluster = false })

for (const mode of ['off-cluster', 'in-cluster'] as const) {
  describe(`svc proxy key lookup (${mode})`, () => {
    beforeEach(() => { state.inCluster = mode === 'in-cluster' })

    for (const key of ['__proto__', 'constructor', 'toString', 'hasOwnProperty', 'valueOf']) {
      it(`${key} is Unknown service and never reaches fetch with the bearer`, async () => {
        const res = await call(key)
        expect(res.status).toBe(404)
        expect(fetchMock).not.toHaveBeenCalled()
      })
    }

    it('mcp-service is Unknown service (it has its own /api/agent/mcp route)', async () => {
      const res = await call('mcp-service')
      expect(res.status).toBe(404)
      expect(fetchMock).not.toHaveBeenCalled()
    })

    it('control: a real catalog business service still proxies', async () => {
      const res = await call('account-service')
      expect(res.status).toBe(200)
      expect(fetchMock).toHaveBeenCalledTimes(1)
    })
  })
}

describe('in-cluster path is gated by the catalog allowlist', () => {
  beforeEach(() => { state.inCluster = true })
  it('a known positive remains proxyable from a partial snapshot', async () => {
    state.status = 'partial'
    state.services = [{ name: 'account-service', namespace: 'core', port: 8100, scaledToZero: false }]
    const res = await call('account-service')
    expect(res.status).toBe(200)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('absence from a partial snapshot is unknown, not 404', async () => {
    state.status = 'partial'
    state.services = []
    const res = await call('ledger-service')
    expect(res.status).toBe(503)
    expect(await res.json()).toEqual({ error: 'discovery_unavailable' })
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('a discovered workload that is not a catalog business service is not proxied', async () => {
    for (const key of ['not-in-catalog-service', 'devops-agent']) {
      const res = await call(key)
      expect(res.status, key).toBe(404)
    }
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
