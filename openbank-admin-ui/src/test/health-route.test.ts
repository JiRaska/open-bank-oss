// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root for details.

// Integration tests for GET /api/services/health
// (ADR-0076 Layer 1 — BFF route integration tests)
//
// The route probes each service via /q/health/ready (Quarkus SmallRye health)
// and enriches with /api/v1/info (version/stack). When in-cluster, K8s discovery
// takes precedence over the static probe list.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// Mock discovery — off-cluster by default (returns null → static probe path)
vi.mock('@/lib/discovery', () => ({
  discoverServices: vi.fn().mockResolvedValue(null),
  prettyLabel: vi.fn((name: string) => name),
  inCluster: vi.fn().mockReturnValue(false),
  resolveInClusterBaseUrl: vi.fn().mockResolvedValue(null),
}))

import { discoverServices } from '@/lib/discovery'

/** Build a minimal fetch mock: /q/health/ready → healthStatus, /api/v1/info → infoPayload */
function mockServiceFetch(healthStatus: number, infoPayload?: object) {
  return vi.fn(async (url: string | URL) => {
    const u = String(url)
    if (u.includes('/q/health/ready')) {
      return new Response(healthStatus === 200 ? '{"status":"UP"}' : '{"status":"DOWN"}', {
        status: healthStatus,
        headers: { 'Content-Type': 'application/json' },
      })
    }
    if (u.includes('/api/v1/info')) {
      if (!infoPayload) return new Response('', { status: 404 })
      return new Response(JSON.stringify(infoPayload), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    }
    return new Response('', { status: 404 })
  })
}

describe('GET /api/services/health', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.mocked(discoverServices).mockResolvedValue(null)
    // Use container-mode so probes target predictable hostnames
    process.env.SERVICES_HOST = 'localhost'
  })
  afterEach(() => {
    vi.restoreAllMocks()
    delete process.env.SERVICES_HOST
  })

  it('marks service UP when /q/health/ready returns 200', async () => {
    vi.stubGlobal('fetch', mockServiceFetch(200, { version: '1.2.3', gitCommit: 'abc123' }))
    const { GET } = await import('@/app/api/services/health/route')
    const res = await GET()
    expect(res.status).toBe(200)
    const body = await res.json()
    const acct = body.services.find((s: { name: string }) => s.name === 'account-service')
    expect(acct).toBeDefined()
    expect(acct.status).toBe('UP')
    expect(acct.reachable).toBe(true)
    expect(acct.latencyMs).toBeGreaterThanOrEqual(0)
  })

  it('marks service DOWN (reachable) when /q/health/ready returns 503', async () => {
    vi.stubGlobal('fetch', mockServiceFetch(503))
    const { GET } = await import('@/app/api/services/health/route')
    const res = await GET()
    const body = await res.json()
    const acct = body.services.find((s: { name: string }) => s.name === 'account-service')
    expect(acct.status).toBe('DOWN')
    expect(acct.reachable).toBe(true)
  })

  it('marks service DOWN (unreachable) when fetch throws (connection refused)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('ECONNREFUSED')))
    const { GET } = await import('@/app/api/services/health/route')
    const res = await GET()
    const body = await res.json()
    const acct = body.services.find((s: { name: string }) => s.name === 'account-service')
    expect(acct.status).toBe('DOWN')
    expect(acct.reachable).toBe(false)
    expect(acct.latencyMs).toBeNull()
  })

  it('enriches UP service with version and stack from /api/v1/info', async () => {
    vi.stubGlobal('fetch', mockServiceFetch(200, {
      version: '2.0.0',
      gitCommit: 'deadbeef',
      stack: { kotlin: { version: '2.0.0' }, quarkus: { version: '3.8.0', lts: true } },
    }))
    const { GET } = await import('@/app/api/services/health/route')
    const body = await (await GET()).json()
    const acct = body.services.find((s: { name: string }) => s.name === 'account-service')
    expect(acct.version).toBe('2.0.0')
    expect(acct.gitCommit).toBe('deadbeef')
    expect(acct.stack?.quarkus?.lts).toBe(true)
  })

  it('response always has no-store cache header', async () => {
    vi.stubGlobal('fetch', mockServiceFetch(200))
    const { GET } = await import('@/app/api/services/health/route')
    const res = await GET()
    expect(res.headers.get('Cache-Control')).toBe('no-store')
  })

  it('uses kubernetes discovery when discoverServices returns data', async () => {
    vi.mocked(discoverServices).mockResolvedValue([
      {
        name: 'account-service', namespace: 'openbank', port: 8100,
        group: 'core', ready: true, readyReplicas: 1,
      } as never,
    ])
    // Info probe for the discovered service
    vi.stubGlobal('fetch', mockServiceFetch(200, { version: '3.0.0' }))
    const { GET } = await import('@/app/api/services/health/route')
    const body = await (await GET()).json()
    expect(body.source).toBe('kubernetes')
    expect(body.services[0].status).toBe('UP')
  })

  it('falls back to static probe list when discoverServices returns null', async () => {
    vi.mocked(discoverServices).mockResolvedValue(null)
    vi.stubGlobal('fetch', mockServiceFetch(200))
    const { GET } = await import('@/app/api/services/health/route')
    const body = await (await GET()).json()
    expect(body.source).toBe('static')
    expect(body.services.length).toBeGreaterThan(0)
  })

  it('response includes byContainer index keyed by container name', async () => {
    vi.stubGlobal('fetch', mockServiceFetch(200))
    const { GET } = await import('@/app/api/services/health/route')
    const body = await (await GET()).json()
    expect(body.byContainer['openbank-account-service']).toBeDefined()
    expect(body.byContainer['openbank-ledger-service']).toBeDefined()
  })
})
