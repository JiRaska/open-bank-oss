// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { parseAgentMergeRecord, parseAgentMergeRecordList } from '@/lib/agent-merge-records/types'

const VALID_RECORD = {
  id: '3f6f8d9a-2b7c-4e1a-9c5d-8a1f2b3c4d5e',
  agentId: 'flaky-test-hunter',
  prNumber: 123,
  prUrl: 'https://github.com/openbank/open-bank-oss/pull/123',
  headBranch: 'agent/flaky-test-hunter-stabilise',
  mergedBy: 'jiri.raska',
  mergedAt: '2026-10-01T10:00:00Z',
  title: 'Stabilise flaky balance reconciliation test',
  proposalId: '8b3f2f5e-1111-4222-8333-944455556666',
  recordedAt: '2026-10-01T10:00:05Z',
  source: 'github-actions',
}

describe('parseAgentMergeRecord', () => {
  it('accepts a valid record with every field populated', () => {
    expect(parseAgentMergeRecord(VALID_RECORD)).toEqual(VALID_RECORD)
  })

  it('accepts a record with proposalId absent (a webhook knows PR numbers, not proposal ids)', () => {
    const { proposalId: _proposalId, ...withoutProposal } = VALID_RECORD
    const parsed = parseAgentMergeRecord(withoutProposal)
    expect(parsed).not.toBeNull()
    expect(parsed?.proposalId).toBeNull()
  })

  it.each([
    ['missing id', { ...VALID_RECORD, id: undefined }],
    ['non-uuid id', { ...VALID_RECORD, id: 'not-a-uuid' }],
    ['blank agentId', { ...VALID_RECORD, agentId: '  ' }],
    ['zero prNumber', { ...VALID_RECORD, prNumber: 0 }],
    ['fractional prNumber', { ...VALID_RECORD, prNumber: 1.5 }],
    ['string prNumber', { ...VALID_RECORD, prNumber: '123' }],
    ['unparseable mergedAt', { ...VALID_RECORD, mergedAt: 'not-a-date' }],
    ['unparseable recordedAt', { ...VALID_RECORD, recordedAt: 'yesterday' }],
    ['non-uuid proposalId', { ...VALID_RECORD, proposalId: 'prop-42' }],
    ['missing source', { ...VALID_RECORD, source: '' }],
    ['null row', null],
    ['array row', [VALID_RECORD]],
  ])('rejects a malformed record: %s', (_label, value) => {
    expect(parseAgentMergeRecord(value)).toBeNull()
  })
})

describe('parseAgentMergeRecordList', () => {
  it('drops malformed rows instead of failing the whole list', () => {
    const { records, dropped } = parseAgentMergeRecordList([
      VALID_RECORD,
      { ...VALID_RECORD, id: '4a7b9c0d-3e8f-4a2b-8d1e-9b2c3d4e5f60', prNumber: -1 },
      'garbage',
      { ...VALID_RECORD, id: '5b8c0d1e-4f9a-4b3c-9e2f-0c3d4e5f6a71', mergedAt: null },
    ])
    expect(records).toHaveLength(1)
    expect(records[0].id).toBe(VALID_RECORD.id)
    expect(dropped).toBe(3)
  })

  it('keeps only the first row when ids repeat', () => {
    const { records, dropped } = parseAgentMergeRecordList([VALID_RECORD, VALID_RECORD])
    expect(records).toHaveLength(1)
    expect(dropped).toBe(1)
  })

  it('returns an empty result for a non-array body', () => {
    expect(parseAgentMergeRecordList({ error: 'upstream_error' })).toEqual({ records: [], dropped: 0 })
    expect(parseAgentMergeRecordList(undefined)).toEqual({ records: [], dropped: 0 })
  })

  it('never throws on unknown agentId strings', () => {
    const odd = { ...VALID_RECORD, id: '6c9d1e2f-5a0b-4c4d-0a1b-1d2e3f4a5b62', agentId: 'agent/unknown ?weird=1' }
    expect(() => parseAgentMergeRecordList([VALID_RECORD, odd])).not.toThrow()
    const { records } = parseAgentMergeRecordList([VALID_RECORD, odd])
    expect(records.map(r => r.agentId)).toContain('agent/unknown ?weird=1')
  })
})
