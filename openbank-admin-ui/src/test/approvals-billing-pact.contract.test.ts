// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import path from 'node:path'
import { MatchersV3, PactV3, SpecificationVersion } from '@pact-foundation/pact'
import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({
  auth: vi.fn(),
}))

import { auth } from '@/auth'
import { GET } from '@/app/api/approvals/pending/route'

const OPERATOR_TOKEN = 'pact-operator-token'

describe('Admin UI billing approval consumer contract', () => {
  afterEach(() => {
    vi.unstubAllEnvs()
    vi.restoreAllMocks()
  })

  it('relays the operator identity and maps billing audit context through the real BFF handler', async () => {
    vi.mocked(auth).mockResolvedValue({
      user: { accessToken: OPERATOR_TOKEN, roles: ['ROLE_OPERATOR'] },
    } as never)

    // serverSvcUrl deliberately addresses localhost outside Kubernetes. Pact owns billing's
    // real local port while every other federated source is allowed to degrade to unavailable.
    vi.stubEnv('KUBERNETES_SERVICE_HOST', '')
    vi.stubEnv('SERVICES_HOST', '127.0.0.1')
    vi.stubEnv('AGENT_SERVICE_URL', 'http://127.0.0.1:65534/mcp')

    const pact = new PactV3({
      consumer: 'openbank-admin-ui',
      provider: 'openbank-billing-service',
      dir: path.resolve(process.cwd(), '../pacts'),
      host: '127.0.0.1',
      port: 8132,
      spec: SpecificationVersion.SPECIFICATION_VERSION_V3,
      logLevel: 'error',
    })

    await pact
      .given('a pending billing approval exists')
      .uponReceiving('list pending billing approvals for the Admin UI inbox')
      .withRequest({
        method: 'GET',
        path: '/api/v1/fees/approvals',
        query: { limit: '50' },
        headers: {
          Authorization: MatchersV3.regex(
            /^Bearer [A-Za-z0-9._-]+$/,
            `Bearer ${OPERATOR_TOKEN}`,
          ),
        },
      })
      .willRespondWith({
        status: 200,
        headers: { 'Content-Type': 'application/json' },
        body: [
          {
            id: 'billing-approval-4',
            action: 'billing.post',
            resourceId: 'fee-4',
            makerId: 'maker.billing',
            createdAt: '2026-08-31T11:00:42Z',
          },
        ],
      })
      .executeTest(async () => {
        const response = await GET()
        const body = await response.json()

        expect(response.status).toBe(200)
        expect(body.sources.billing).toBe('ok')
        expect(body.items).toEqual([
          {
            id: 'billing-approval-4',
            domain: 'billing',
            action: 'billing.post',
            resourceId: 'fee-4',
            maker: 'maker.billing',
            proposedAt: '2026-08-31T11:00:42Z',
          },
        ])
      })
  })
})
