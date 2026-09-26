// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: vi.fn() }))
import { auth } from '@/auth'

const finding = {
  id: 'finding-1', detector: 'D4_DEPLOY_HEALTH', severity: 'CRITICAL',
  detectedAt: '2026-09-16T09:00:00Z', title: 'Sandbox rollout stalled',
  rawMetricValue: 1, threshold: 0, affectedResource: 'admin-ui',
  doraMetricImpacted: 'CHANGE_FAILURE_RATE', rootCause: 'rollout timeout',
  remediationKind: 'PULL_REQUEST', proposedRemediation: 'Repair the rollout gate',
  status: 'PROPOSED', diagnosedAt: null, proposedAt: null,
}

describe('DevOps insights BFF', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'operator-token' } } as never)
  })

  afterEach(() => vi.restoreAllMocks())

  it('keeps only canonical pull requests for this repository', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify([
      { ...finding, id: 'safe', proposalPrUrl: 'https://github.com/JiRaska/open-bank-oss/pull/10047' },
      { ...finding, id: 'script', proposalPrUrl: 'javascript:alert(1)' },
      { ...finding, id: 'phishing', proposalPrUrl: 'https://attacker.example/pull/10047' },
      { ...finding, id: 'lookalike', proposalPrUrl: 'https://github.com/attacker/open-bank-oss/pull/10047' },
      { ...finding, id: 'credentials', proposalPrUrl: 'https://github.com@attacker.example/JiRaska/open-bank-oss/pull/10047' },
    ]), { status: 200 })))

    const { GET } = await import('@/app/api/devops/insights/route')
    const body = await (await GET()).json()

    expect(body.findings.map((item: { id: string; proposalPrUrl: string | null }) => [item.id, item.proposalPrUrl])).toEqual([
      ['safe', 'https://github.com/JiRaska/open-bank-oss/pull/10047'],
      ['script', null],
      ['phishing', null],
      ['lookalike', null],
      ['credentials', null],
    ])
  })
})
