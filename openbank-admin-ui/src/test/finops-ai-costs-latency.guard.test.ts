// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import path from 'path'

describe('FinOps AI cost refresh resilience', () => {
  it('bounds Prometheus calls and does not serialize independent metrics', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/api/finops/ai-costs/route.ts'), 'utf8')

    expect(source).toContain('PROMETHEUS_QUERY_TIMEOUT_MS = 5_000')
    expect(source).toContain('AbortSignal.timeout(PROMETHEUS_QUERY_TIMEOUT_MS)')
    expect(source).toContain('const [tokens24h, cost24h, cost7d, cost30d] = await Promise.all([')
    expect(source).toContain('const agentResults = await Promise.all(agentIds.map(async (agentId)')
    expect(source).toContain('const agents = agentResults.filter((entry): entry is AgentCostEntry => entry !== null)')
    expect(source).not.toContain('for (const agentId of agentIds)')
    expect(source).not.toContain('const tokens24h = await fetchFromPrometheus(')
  })
})
