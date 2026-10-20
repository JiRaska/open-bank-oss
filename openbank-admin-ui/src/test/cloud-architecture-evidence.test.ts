import { describe, expect, it } from 'vitest'
import { recentArchitectureProbe } from '@/lib/cloud-architecture-evidence'

const now = Date.parse('2026-10-19T16:00:00Z')
const up = { id: 'kafka', status: 'UP' as const, latencyMs: 3, checkedAt: new Date(now).toISOString() }
describe('architecture observed state', () => {
  it('retains actual probe outcomes without converting unknown into an outage', () => {
    for (const status of ['UP', 'DOWN', 'UNKNOWN'] as const) {
      expect(recentArchitectureProbe({ ...up, status }, now)?.status).toBe(status)
    }
  })
  it('does not present absent, expired, malformed or future measurements as live', () => {
    expect(recentArchitectureProbe(null, now)).toBeNull()
    for (const checkedAt of [null, 'invalid', new Date(now - 90_001).toISOString(), new Date(now + 60_000).toISOString()]) {
      expect(recentArchitectureProbe({ ...up, checkedAt }, now)).toBeNull()
    }
  })
})
