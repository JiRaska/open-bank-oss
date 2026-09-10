// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { parseDashboardHealth, parseGovernanceFleet } from '@/lib/dashboard/clientContract'

const governance = {
  available: true,
  timestamp: '2026-09-10T03:00:00Z',
  items: [{ serviceName: 'account-service', dataDomain: 'core' }],
}

const health = {
  services: [{ name: 'account-service', label: 'Accounts', group: 'core', status: 'UP', latencyMs: 12 }],
}

describe('dashboard evidence contract', () => {
  it('accepts a governed roster and bounded current health sample', () => {
    expect(parseGovernanceFleet(governance)).toEqual([{ name: 'account-service', group: 'core' }])
    expect(parseDashboardHealth(health)).toEqual(health.services)
  })

  it('rejects an unavailable catalogue instead of turning it into an empty fleet', () => {
    expect(() => parseGovernanceFleet({ ...governance, available: false, items: [] })).toThrow()
  })

  it('rejects malformed, duplicate and stale-looking evidence shapes', () => {
    expect(() => parseGovernanceFleet({ ...governance, timestamp: 'today' })).toThrow()
    expect(() => parseGovernanceFleet({ ...governance, items: [...governance.items, ...governance.items] })).toThrow()
    expect(() => parseDashboardHealth({ services: [{ ...health.services[0], status: 'HEALTHY' }] })).toThrow()
    expect(() => parseDashboardHealth({ services: [{ ...health.services[0], latencyMs: -1 }] })).toThrow()
  })

  it('keeps an empty successful discovery distinct from a malformed response', () => {
    expect(parseDashboardHealth({ services: [] })).toEqual([])
    expect(() => parseDashboardHealth({ services: null })).toThrow()
  })
})
