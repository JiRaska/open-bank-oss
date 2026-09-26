// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { parseMapGovernance, parseMapHealth, parseServiceMapGraph } from '@/lib/governance/service-map-evidence'

describe('Service Map evidence parsers', () => {
  it('validates health entries and rejects duplicate ports', () => {
    expect(parseMapHealth({ services: [{ port: 8100, status: 'UP' }] })).toEqual([{ port: 8100, status: 'UP' }])
    expect(parseMapHealth({ services: [{ port: 8100, status: 'BROKEN' }] })).toBeNull()
    expect(parseMapHealth({ services: [{ port: 0, status: 'UP' }] })).toBeNull()
    expect(parseMapHealth({ services: [{ port: 8100, status: 'UP' }, { port: 8100, status: 'DOWN' }] })).toBeNull()
  })

  it('keeps unavailable governance distinct and rejects contradictory evidence', () => {
    expect(parseMapGovernance({ available: false, byService: {} })).toEqual({ available: false, byService: {} })
    expect(parseMapGovernance({ available: false, byService: { account: {} } })).toBeNull()
    expect(parseMapGovernance({ byService: {} })).toBeNull()
  })

  it('validates graph envelopes and rejects malformed or contradictory snapshots', () => {
    const empty = { available: true, nodes: [], edges: [], infraNodes: [], externalNodes: [], infraEdges: [], externalEdges: [] }
    expect(parseServiceMapGraph(empty)?.available).toBe(true)
    expect(parseServiceMapGraph({ ...empty, nodes: 'invalid' })).toBeNull()
    expect(parseServiceMapGraph({ ...empty, available: false, edges: [{ from: 'a', to: 'b', via: 'b', type: 'rest' }] })).toBeNull()
    expect(parseServiceMapGraph({ ...empty, nodes: [{ name: 'account' }, { name: 'account' }] })).toBeNull()
  })
})
