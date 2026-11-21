// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import path from 'node:path'
import { PactV3, SpecificationVersion } from '@pact-foundation/pact'
import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: vi.fn() }))
import { auth } from '@/auth'
import { GET } from '@/app/api/devops/insights/route'

const finding = {
  id: '11111111-1111-4111-8111-111111111111', detector: 'D1_CI_PIPELINE_HEALTH', severity: 'WARNING',
  detectedAt: '2026-01-01T00:00:00Z', title: 'Synthetic CI health finding',
  rawMetricValue: 5, threshold: 2, affectedResource: 'synthetic-pipeline',
  doraMetricImpacted: null, rootCause: null, remediationKind: 'NONE',
  proposalPrUrl: null, proposedRemediation: null, status: 'OPEN',
  diagnosedAt: null, proposedAt: null,
}

describe('Admin UI DevOps findings consumer contract', () => {
  afterEach(() => {
    vi.unstubAllEnvs()
    vi.restoreAllMocks()
  })

  it.each([
    { state: 'no active DevOps findings exist', findings: [] },
    { state: 'an active DevOps finding exists', findings: [finding] },
  ])('reads findings through the authenticated BFF: $state', async ({ state, findings }) => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'pact-viewer' } } as never)
    vi.stubEnv('SERVICES_HOST', '')
    const pact = new PactV3({
      consumer: 'openbank-admin-ui', provider: 'openbank-devops-agent',
      dir: path.resolve(process.cwd(), '../pacts'), host: '127.0.0.1',
      spec: SpecificationVersion.SPECIFICATION_VERSION_V3, logLevel: 'error',
    })
    await pact.given(state)
      .uponReceiving(`read active DevOps findings for the Admin UI insights panel: ${state}`)
      .withRequest({ method: 'GET', path: '/api/v1/devops/findings', headers: { Authorization: 'Bearer pact-viewer' } })
      .willRespondWith({ status: 200, headers: { 'Content-Type': 'application/json' }, body: findings })
      .executeTest(async server => {
        vi.stubEnv('DEVOPS_AGENT_URL', server.url)
        const response = await GET()
        expect(response.status).toBe(200)
        expect(await response.json()).toEqual({ findings, available: true })
      })
  })

  it('refuses an anonymous caller before contacting the provider', async () => {
    vi.mocked(auth).mockResolvedValue(null)
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
    const response = await GET()
    expect(response.status).toBe(401)
    expect(await response.json()).toEqual({ findings: [], available: false, reason: 'unauthorized' })
    expect(fetchSpy).not.toHaveBeenCalled()
  })
})
