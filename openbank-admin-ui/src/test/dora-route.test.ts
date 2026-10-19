// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The DevOps DORA route serves Deployment Frequency + Lead Time from the
// git-derived snapshot (dora.json, ADR-0061), not a live GitHub call — and never
// fabricates a number. These tests pin that behaviour.
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import path from 'path'

const FIXTURE = path.resolve(__dirname, 'fixtures/dora.sample.json')

describe('GET /api/devops/dora', () => {
  beforeEach(() => { process.env.OPENBANK_DORA_REPORT = FIXTURE })
  afterEach(() => { delete process.env.OPENBANK_DORA_REPORT; delete process.env.PROMETHEUS_URL; vi.unstubAllGlobals() })

  it('labels first-parent counts as a changes proxy and does not grade them as DORA deployments', async () => {
    const { GET } = await import('@/app/api/devops/dora/route')
    const res = await GET()
    const body = await res.json()
    expect(body.sources.git).toBe(true)
    expect(body.metrics.deploymentFrequency.perDay).toBeCloseTo(7.33, 2)
    expect(body.metrics.deploymentFrequency.count30d).toBe(220)
    expect(body.metrics.deploymentFrequency.level).toBeNull()
    expect(body.metrics.deploymentFrequency.description).toMatch(/merged changes\/day \(proxy\)/)
    expect(body.metrics.deploymentFrequency.note).toMatch(/deploy events are not collected/)
    expect(body.metrics.changeFailureRate.level).toBeNull()
    expect(body.metrics.mttr.level).toBeNull()
    expect(body.overall).toBeNull()
    expect(body.recentDeployments[0].sha).toBe('bc104fd')
  })

  it('reports lead time as null with a reason on a squash-merged trunk (never fabricated)', async () => {
    const { GET } = await import('@/app/api/devops/dora/route')
    const body = await (await GET()).json()
    expect(body.metrics.leadTime.hours).toBeNull()
    expect(body.metrics.leadTime.note).toMatch(/squash|deploy-event|phase 2/i)
  })


  it('keeps HTTP 5xx and alert-duration proxies ungraded even when their values look elite', async () => {
    process.env.PROMETHEUS_URL = 'http://prometheus.test'
    vi.stubGlobal('fetch', vi.fn(async (input: string) => {
      if (input.endsWith('/-/ready')) return new Response('ready')
      const query = new URL(input).searchParams.get('query') ?? ''
      const value = query.startsWith('count(count_over_time') ? '1' : query.startsWith('sum(sum_over_time') ? '15' : '0'
      return Response.json({ status: 'success', data: { result: [{ value: [1, value] }] } })
    }))
    const { GET } = await import('@/app/api/devops/dora/route')
    const body = await (await GET()).json()
    expect(body.metrics.changeFailureRate.pct).toBe(0)
    expect(body.metrics.changeFailureRate.level).toBeNull()
    expect(body.metrics.changeFailureRate.note).toMatch(/Proxy/)
    expect(body.metrics.mttr.hours).toBe(0)
    expect(body.metrics.mttr.level).toBeNull()
    expect(body.metrics.mttr.note).toMatch(/Approximation/)
    expect(body.overall).toBeNull()
  })

  it('degrades to git source unavailable when the snapshot is missing', async () => {
    process.env.OPENBANK_DORA_REPORT = path.resolve(__dirname, 'fixtures/does-not-exist.json')
    const { GET } = await import('@/app/api/devops/dora/route')
    const body = await (await GET()).json()
    expect(body.sources.git).toBe(false)
    expect(body.metrics.deploymentFrequency.perDay).toBeNull()
  })
})
