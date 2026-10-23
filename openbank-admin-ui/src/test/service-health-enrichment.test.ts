// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/lib/discovery', () => ({
  discoverServicesSnapshot: vi.fn(),
  prettyLabel: vi.fn((name: string) => name),
}))

import { discoverServicesSnapshot } from '@/lib/discovery'

type Discovered = {
  name: string
  namespace: string
  port: number
  group: 'core'
  ready: boolean
  readyReplicas: number
  image?: string
}

function service(image = 'registry/openbank-account:one'): Discovered {
  return {
    name: 'account-service', namespace: 'accounts', port: 8100,
    group: 'core', ready: true, readyReplicas: 1, image,
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

function infoResponse(version: string) {
  return new Response(JSON.stringify({ version, gitCommit: `commit-${version}`, stack: { java: { version: '21' } } }), {
    status: 200, headers: { 'Content-Type': 'application/json' },
  })
}

async function routeGet() {
  const { GET } = await import('@/app/api/services/health/route')
  return GET()
}

async function accountEntry(response: Promise<Response>) {
  const body = await (await response).json()
  return body.services.find((entry: { name: string }) => entry.name === 'account-service')
}

async function flushPromises() {
  await Promise.resolve()
  await Promise.resolve()
  await Promise.resolve()
}

async function accountEntryWhenVersion(expectedVersion: string) {
  const deadline = Date.now() + 2_000
  let entry = await accountEntry(routeGet())
  while (entry.version !== expectedVersion && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 10))
    entry = await accountEntry(routeGet())
  }
  return entry
}

describe('Kubernetes health metadata enrichment', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.mocked(discoverServicesSnapshot).mockResolvedValue({ status: 'complete', services: [service()] as never })
    delete process.env.SERVICES_HOST
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    delete process.env.SERVICES_HOST
  })

  it('returns Kubernetes readiness while the optional info request is still pending', async () => {
    const info = deferred<Response>()
    const fetchMock = vi.fn(() => info.promise)
    vi.stubGlobal('fetch', fetchMock)
    const responsePromise = routeGet()
    const response = await Promise.race([
      responsePromise,
      // Keep this far below the enrichment's eight-second timeout, while allowing
      // cold module startup and a loaded Vitest worker to schedule the route.
      new Promise<never>((_, reject) => setTimeout(() => reject(new Error('health route waited for info')), 1_000)),
    ])
    const entry = await accountEntry(Promise.resolve(response))
    expect(entry.status).toBe('UP')
    expect(entry.reachable).toBe(true)
    expect(entry.version).toBeNull()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    info.resolve(infoResponse('1.0.0'))
    await flushPromises()
  })

  it('serves refreshed metadata from cache on the next poll', async () => {
    const info = deferred<Response>()
    const fetchMock = vi.fn(() => info.promise)
    vi.stubGlobal('fetch', fetchMock)
    const first = await accountEntry(routeGet())
    expect(first.version).toBeNull()
    info.resolve(infoResponse('2.0.0'))
    const second = await accountEntryWhenVersion('2.0.0')
    expect(second.version).toBe('2.0.0')
    expect(second.gitCommit).toBe('commit-2.0.0')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('coalesces concurrent info probes for the same service and desired image', async () => {
    const info = deferred<Response>()
    const fetchMock = vi.fn(() => info.promise)
    vi.stubGlobal('fetch', fetchMock)
    const [first, second] = await Promise.all([routeGet(), routeGet()])
    expect((await accountEntry(Promise.resolve(first))).status).toBe('UP')
    expect((await accountEntry(Promise.resolve(second))).status).toBe('UP')
    expect(fetchMock).toHaveBeenCalledTimes(1)
    info.resolve(infoResponse('3.0.0'))
    await flushPromises()
  })

  it('keys cached metadata by the desired image', async () => {
    const firstInfo = deferred<Response>()
    const secondInfo = deferred<Response>()
    const fetchMock = vi.fn()
      .mockReturnValueOnce(firstInfo.promise)
      .mockReturnValueOnce(secondInfo.promise)
    vi.stubGlobal('fetch', fetchMock)
    await routeGet()
    firstInfo.resolve(infoResponse('4.0.0'))
    await flushPromises()
    vi.mocked(discoverServicesSnapshot).mockResolvedValue({ status: 'complete', services: [service('registry/openbank-account:two')] as never })
    const changed = await accountEntry(routeGet())
    expect(changed.version).toBeNull()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    secondInfo.resolve(infoResponse('5.0.0'))
    await flushPromises()
  })

  it('expires metadata instead of presenting it as fresh', async () => {
    vi.useFakeTimers()
    const firstInfo = deferred<Response>()
    const secondInfo = deferred<Response>()
    const fetchMock = vi.fn()
      .mockReturnValueOnce(firstInfo.promise)
      .mockReturnValueOnce(secondInfo.promise)
    vi.stubGlobal('fetch', fetchMock)
    await routeGet()
    firstInfo.resolve(infoResponse('6.0.0'))
    await flushPromises()
    expect((await accountEntry(routeGet())).version).toBe('6.0.0')
    await vi.advanceTimersByTimeAsync(60_001)
    const expired = await accountEntry(routeGet())
    expect(expired.version).toBeNull()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    secondInfo.resolve(infoResponse('7.0.0'))
    await flushPromises()
  })

  it('keeps cache bounded under discovery pressure and refreshes the oldest entry', async () => {
    const services = Array.from({ length: 512 }, (_, index) => ({
      ...service(), name: `service-${String(index).padStart(3, '0')}`,
    }))
    const fetchMock = vi.fn(async () => infoResponse('bounded'))
    vi.stubGlobal('fetch', fetchMock)
    vi.mocked(discoverServicesSnapshot).mockResolvedValue({ status: 'complete', services: services as never })
    await routeGet()
    await flushPromises()

    const newest = { ...service(), name: 'service-512' }
    vi.mocked(discoverServicesSnapshot).mockResolvedValue({ status: 'complete', services: [...services, newest] as never })
    await routeGet()
    await flushPromises()
    const third = await routeGet()
    const body = await third.json()
    expect(body.services.find((entry: { name: string }) => entry.name === newest.name).version).toBe('bounded')
    expect(body.services.find((entry: { name: string }) => entry.name === services[0].name).version).toBeNull()
    expect(fetchMock).toHaveBeenCalledTimes(514)
  })

  it('drops an enrichment that completes after its service leaves discovery', async () => {
    const lateInfo = deferred<Response>()
    const fetchMock = vi.fn()
      .mockReturnValueOnce(lateInfo.promise)
      .mockResolvedValueOnce(infoResponse('fresh-after-return'))
    vi.stubGlobal('fetch', fetchMock)
    await routeGet()
    vi.mocked(discoverServicesSnapshot).mockResolvedValue({ status: 'complete', services: [] })
    await routeGet()
    lateInfo.resolve(infoResponse('late-stale'))
    await flushPromises()

    vi.mocked(discoverServicesSnapshot).mockResolvedValue({ status: 'complete', services: [service()] as never })
    const returned = await accountEntry(routeGet())
    expect(returned.version).toBeNull()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    await flushPromises()
    expect((await accountEntry(routeGet())).version).toBe('fresh-after-return')
  })

  it('bounds info response body parsing by the same eight-second deadline', async () => {
    vi.useFakeTimers()
    const body = deferred<{ version: string }>()
    const stalledResponse = { ok: true, json: () => body.promise } as unknown as Response
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(stalledResponse)
      .mockResolvedValueOnce(infoResponse('body-retry'))
    vi.stubGlobal('fetch', fetchMock)
    const response = await routeGet()
    expect((await accountEntry(Promise.resolve(response))).status).toBe('UP')
    await vi.advanceTimersByTimeAsync(8_001)
    await flushPromises()

    const retry = await accountEntry(routeGet())
    expect(retry.status).toBe('UP')
    expect(retry.version).toBeNull()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    await flushPromises()
    expect((await accountEntry(routeGet())).version).toBe('body-retry')
  })

  it('keeps readiness UP and retries a failed enrichment on the next poll', async () => {
    const fetchMock = vi.fn()
      .mockRejectedValueOnce(new Error('info unavailable'))
      .mockResolvedValueOnce(infoResponse('8.0.0'))
    vi.stubGlobal('fetch', fetchMock)
    const first = await accountEntry(routeGet())
    expect(first.status).toBe('UP')
    expect(first.version).toBeNull()
    await flushPromises()
    const second = await accountEntry(routeGet())
    expect(second.status).toBe('UP')
    expect(fetchMock).toHaveBeenCalledTimes(2)
    await flushPromises()
    const third = await accountEntry(routeGet())
    expect(third.version).toBe('8.0.0')
  })

  it('preserves off-cluster static health and synchronous info enrichment', async () => {
    vi.mocked(discoverServicesSnapshot).mockResolvedValue({ status: 'static', services: [] })
    process.env.SERVICES_HOST = 'localhost'
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL) => {
      if (String(input).includes('/q/health/ready')) return new Response('{"status":"UP"}', { status: 200 })
      return infoResponse('9.0.0')
    }))
    const response = await routeGet()
    const body = await response.json()
    const account = body.services.find((entry: { name: string }) => entry.name === 'account-service')
    expect(body.source).toBe('static')
    expect(account.status).toBe('UP')
    expect(account.version).toBe('9.0.0')
  })
})
