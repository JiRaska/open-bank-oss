// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { parseTempoSearch, parseTempoTrace } from '@/lib/observability/tempo-evidence'

describe('Tempo evidence parsers', () => {
  it('accepts a verified empty search and rejects malformed or duplicate summaries', () => {
    expect(parseTempoSearch({ traces: [] })).toEqual([])
    expect(parseTempoSearch({})).toBeNull()
    expect(parseTempoSearch({ traces: [{ traceID: 'not-hex' }] })).toBeNull()
    expect(parseTempoSearch({ traces: [
      { traceID: '0123456789abcdef' },
      { traceID: '0123456789abcdef' },
    ] })).toBeNull()
  })

  it('accepts a valid empty trace and validates span timing and identity', () => {
    expect(parseTempoTrace({ batches: [] })).toEqual([])
    expect(parseTempoTrace({})).toBeNull()
    expect(parseTempoTrace({ batches: [{ scopeSpans: [{ spans: [{
      spanId: 'span-1', name: 'GET /accounts', startTimeUnixNano: '20', endTimeUnixNano: '10',
    }] }] }] })).toBeNull()
  })

  it('flattens valid scope spans with their verified service name', () => {
    expect(parseTempoTrace({ batches: [{
      resource: { attributes: [{ key: 'service.name', value: { stringValue: 'ledger-service' } }] },
      scopeSpans: [{ spans: [{ spanId: 'span-1', name: 'POST /entries', startTimeUnixNano: '10', endTimeUnixNano: '25' }] }],
    }] })).toEqual([{ spanId: 'span-1', name: 'POST /entries', service: 'ledger-service', startNano: 10, endNano: 25 }])
  })

  it('accepts real epoch nanoseconds even though they exceed safe integer precision', () => {
    const spans = parseTempoTrace({ batches: [{ scopeSpans: [{ spans: [{
      spanId: 'span-epoch', name: 'GET /health',
      startTimeUnixNano: '1788955200000000000', endTimeUnixNano: '1788955200001000000',
    }] }] }] })
    expect(spans).toHaveLength(1)
    expect(spans?.[0].endNano).toBeGreaterThan(spans?.[0].startNano ?? 0)
  })
})
