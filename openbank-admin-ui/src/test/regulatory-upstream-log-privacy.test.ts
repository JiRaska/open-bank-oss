// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/lib/discovery', () => ({
  inCluster: () => true,
  resolveInClusterService: vi.fn(async () => ({ state: 'found', service: {}, baseUrl: 'http://transaction-service.test:8080', coverage: 'partial' })),
}))
vi.mock('@/lib/services/fleet', () => ({
  getProxyAllowlist: () => ({ 'transaction-service': { container: 'tx', port: 8080 } }),
}))

import { ReportSourceError } from '@/lib/regulatory/generatedReports'
import { createReportSourceClient } from '@/lib/regulatory/upstream'

describe('regulatory upstream failure log privacy', () => {
  const detail = 'synthetic-customer@example.test\r\nFORGED %s %o transaction=synthetic-123'
  const path = '/api/v1/transactions/synthetic-123'

  afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })

  it('keeps bearer relay and typed unreachable classification without logging the raw exception', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new Error(detail, { cause: detail }))
    vi.stubGlobal('fetch', fetchMock)
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    const client = createReportSourceClient('synthetic-operator-token')

    await expect(client.get('transaction-service', path)).rejects.toMatchObject({
      kind: 'unreachable', service: 'transaction-service', message: 'transaction-service: did not answer',
    })
    expect(fetchMock).toHaveBeenCalledExactlyOnceWith(`http://transaction-service.test:8080${path}`, expect.objectContaining({
      method: 'GET', cache: 'no-store', headers: { Authorization: 'Bearer synthetic-operator-token', Accept: 'application/json' },
    }))
    expect(log).toHaveBeenCalledExactlyOnceWith('regulatory BFF: source request unreachable')
  })

  it.each([401, 403, 500])('preserves upstream HTTP %s while withholding its body from logs and callers', async status => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(detail, { status }))
    vi.stubGlobal('fetch', fetchMock)
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    const client = createReportSourceClient('synthetic-operator-token')
    const body = { synthetic: true }

    await expect(client.request('transaction-service', 'POST', path, { body })).resolves.toEqual({ status, body: null })
    expect(fetchMock).toHaveBeenCalledExactlyOnceWith(`http://transaction-service.test:8080${path}`, expect.objectContaining({
      method: 'POST', body: JSON.stringify(body), cache: 'no-store',
      headers: { Authorization: 'Bearer synthetic-operator-token', Accept: 'application/json', 'Content-Type': 'application/json' },
    }))
    expect(log).toHaveBeenCalledExactlyOnceWith('regulatory BFF: source request rejected')
  })

  it.each([401, 403, 500])('keeps GET error classification for HTTP %s without exposing the response body', async status => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(detail, { status })))
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    const client = createReportSourceClient('synthetic-operator-token')

    await expect(client.get('transaction-service', path)).rejects.toMatchObject({
      kind: status === 401 || status === 403 ? 'unauthorized' : 'error',
      service: 'transaction-service', message: `transaction-service: HTTP ${status}`,
    })
    expect(log).toHaveBeenCalledExactlyOnceWith('regulatory BFF: source request rejected')
  })

  it('keeps successful JSON parsing and empty 204 responses unchanged', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: [{ id: 'synthetic-123' }] }), { status: 200 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    const client = createReportSourceClient('synthetic-operator-token')

    await expect(client.get('transaction-service', path)).resolves.toEqual({ data: [{ id: 'synthetic-123' }] })
    await expect(client.request('transaction-service', 'GET', path)).resolves.toEqual({ status: 204, body: null })
    expect(log).not.toHaveBeenCalled()
  })

  it('still rejects malformed successful JSON without logging its body', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(detail, { status: 200 })))
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    const client = createReportSourceClient('synthetic-operator-token')

    await expect(client.get('transaction-service', path)).rejects.toBeInstanceOf(ReportSourceError)
    expect(log).not.toHaveBeenCalled()
  })
})
