// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The sanctions BFF defaulted to `http://openbank-sanctions-service:8123`, a host that does not
// exist in the cluster (the Service is `sanctions-service` in namespace `sanctions`), and the
// admin-ui manifest never set SANCTIONS_SERVICE_URL — so the Sanctions page's "Check Records"
// failed on every load. In-cluster the URL must be the Service DNS name.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: vi.fn() }))

import { auth } from '@/auth'

describe('sanctions BFF upstream URL', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 't' } } as never)
    global.fetch = vi.fn().mockResolvedValue(new Response('[]', { status: 200 }))
  })
  afterEach(() => {
    vi.unstubAllEnvs()
    vi.restoreAllMocks()
  })

  function calledUrl(): string {
    return vi.mocked(global.fetch).mock.calls[0][0] as string
  }

  it('addresses the in-cluster Service DNS name', async () => {
    vi.stubEnv('KUBERNETES_SERVICE_HOST', '10.0.0.1')
    vi.stubEnv('SANCTIONS_SERVICE_URL', '')
    const { forwardToSanctionsService } = await import('../lib/sanctions/upstream')
    await forwardToSanctionsService('/api/v1/sanctions')
    expect(calledUrl()).toBe('http://sanctions-service.sanctions.svc:8123/api/v1/sanctions')
  })

  it('honours an explicit SANCTIONS_SERVICE_URL override', async () => {
    vi.stubEnv('SANCTIONS_SERVICE_URL', 'http://example.test:9000/')
    const { forwardToSanctionsService } = await import('../lib/sanctions/upstream')
    await forwardToSanctionsService('/api/v1/sanctions')
    expect(calledUrl()).toBe('http://example.test:9000/api/v1/sanctions')
  })
})
