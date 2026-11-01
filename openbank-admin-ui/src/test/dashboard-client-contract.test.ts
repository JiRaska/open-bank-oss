// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { parseDashboardHealth, parseGovernanceFleet } from '@/lib/dashboard/clientContract'
import { aggregateHealthStatuses, aggregateNamedWorkloadStatuses } from '@/lib/health/workloadHealth'

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

  it('preserves namespace workload identities and rejects only true identity duplicates', () => {
    const workloads = [
      { ...health.services[0], namespace: 'tenant-a', port: 8080 },
      { ...health.services[0], namespace: 'tenant-b', port: 8081 },
    ]
    expect(parseDashboardHealth({ services: workloads }).map(entry => `${entry.namespace}/${entry.name}`)).toEqual([
      'tenant-a/account-service', 'tenant-b/account-service',
    ])
    expect(() => parseDashboardHealth({ services: [workloads[0], { ...workloads[0], port: 8082 }] })).toThrow()
  })

  it('aggregates service instances conservatively and treats no observations as unknown', () => {
    expect(aggregateHealthStatuses([])).toBe('UNKNOWN')
    expect(aggregateHealthStatuses(['UP', 'UP'])).toBe('UP')
    expect(aggregateHealthStatuses(['UP', 'UNKNOWN'])).toBe('UNKNOWN')
    expect(aggregateHealthStatuses(['UNKNOWN', 'DOWN'])).toBe('DOWN')
    expect(aggregateHealthStatuses(['UP', 'BROKEN'])).toBe('UNKNOWN')
    expect(aggregateHealthStatuses(['DOWN', 'UP'])).toBe(aggregateHealthStatuses(['UP', 'DOWN']))
    const workloads = [
      { name: 'account-service', container: 'openbank-account-service', status: 'UP' as const },
      { name: 'account-service', container: 'openbank-account-service', status: 'DOWN' as const },
    ]
    const aliases = new Set(['account-service', 'openbank-account-service'])
    expect(aggregateNamedWorkloadStatuses(workloads, aliases)).toBe('DOWN')
    expect(aggregateNamedWorkloadStatuses([...workloads].reverse(), aliases)).toBe('DOWN')
    expect(aggregateNamedWorkloadStatuses([], aliases)).toBe('UNKNOWN')
  })
})
