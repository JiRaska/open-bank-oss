// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const state = vi.hoisted(() => ({ snapshot: { status: 'complete', services: [] as any[] } }))
vi.mock('@/lib/discovery', () => ({ discoverServicesSnapshot: async () => state.snapshot }))

import { GET } from '@/app/api/services/config/route'

const service = {
  name: 'account-service', namespace: 'accounts', port: 8100, group: 'core', ready: true,
  replicas: 1, readyReplicas: 1, scaledToZero: false,
}

beforeEach(() => {
  state.snapshot = { status: 'complete', services: [] }
  vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 200 })))
})
afterEach(() => vi.unstubAllGlobals())

describe('GET /api/services/config discovery coverage', () => {
  it('keeps a verified empty inventory as an empty array', async () => {
    const response = await GET()
    expect(response.status).toBe(200)
    expect(await response.json()).toEqual([])
  })

  it('returns incomplete status with observed positives instead of a full-looking empty result', async () => {
    state.snapshot = { status: 'partial', services: [service] }
    const response = await GET()
    const body = await response.json()

    expect(response.status).toBe(503)
    expect(body.error).toBe('discovery_unavailable')
    expect(body.discoveryStatus).toBe('partial')
    expect(body.services).toHaveLength(1)
    expect(body.services[0].name).toBe('account-service')
  })

  it('preserves namespace identity for same-named discovered services', async () => {
    state.snapshot = { status: 'complete', services: [service, { ...service, namespace: 'payments' }] }
    const response = await GET()
    const body = await response.json()

    expect(body.map((row: { name: string; namespace?: string }) => [row.name, row.namespace])).toEqual([
      ['account-service', 'accounts'], ['account-service', 'payments'],
    ])
  })

  it('does not probe static localhost services when in-cluster discovery is unavailable', async () => {
    state.snapshot = { status: 'unavailable', services: [] }
    const fetchMock = vi.mocked(fetch)
    const response = await GET()

    expect(response.status).toBe(503)
    expect(await response.json()).toMatchObject({ error: 'discovery_unavailable', discoveryStatus: 'unavailable', services: [] })
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
