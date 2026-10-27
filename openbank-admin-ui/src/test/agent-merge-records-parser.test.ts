// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { parseAgentMergeRecord, parseAgentMergeRecordList } from '@/lib/agent-merge-records/types'

const VALID_RECORD = {
  id: '3f6f8d9a-2b7c-4e1a-9c5d-8a1f2b3c4d5e',
  agentId: 'flaky-test-hunter',
  prNumber: 123,
  prUrl: 'https://github.com/openbank/open-bank-oss/pull/123',
  headBranch: 'agent/flaky-test-hunter-stabilise',
  mergedBy: 'fixture-reviewer',
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

const APP_RECORD = {
  ...VALID_RECORD,
  mergedBy: 'openbank-gitops-bot[bot]', mergedByType: 'GITHUB_APP', mergedById: 200,
  headSha: 'a'.repeat(40), mergeCommitSha: 'b'.repeat(40),
  authorization: {
    kind: 'OWNER_WORKFLOW_EXACT_HEAD', scope: 'HEAD', approvedBy: 'reviewer', approvedById: 100,
    approvedAt: '2026-10-01T09:59:00Z', statusId: 42, workflowRunId: 45,
    evidenceUrl: 'https://github.com/openbank/open-bank-oss/actions/runs/45',
  },
}

const LEGACY_RECORD = {
  ...APP_RECORD,
  authorization: {
    kind: 'LEGACY_PR_AUTO_MERGE', scope: 'PR', approvedBy: 'reviewer', approvedById: 100,
    approvedAt: '2026-10-01T09:59:00Z', evidenceUrl: VALID_RECORD.prUrl,
    pullRequestNodeId: 'PR_synthetic', mergeEventNodeId: 'ME_synthetic',
    mergeEventAt: '2026-10-01T10:00:01Z', observedAt: '2026-10-01T10:00:02Z',
  },
}

describe('merge executor and independent authorization', () => {
  it('preserves the actual App and separate exact-head human approver', () => {
    expect(parseAgentMergeRecord(APP_RECORD)).toEqual(APP_RECORD)
  })

  it('retains historical PR scope without converting it to head approval', () => {
    expect(parseAgentMergeRecord(LEGACY_RECORD)?.authorization?.scope).toBe('PR')
  })

  it.each([
    ['missing App proof', { ...APP_RECORD, authorization: null }],
    ['missing App head', { ...APP_RECORD, headSha: null }],
    ['wrong executor id', { ...APP_RECORD, mergedById: 0 }],
    ['wrong executor type', { ...APP_RECORD, mergedByType: 'UNKNOWN' }],
    ['bot with trailing space disguised as human', { ...VALID_RECORD, mergedBy: 'openbank-gitops-bot[bot] ' }],
    ['invalid human login', { ...VALID_RECORD, mergedBy: 'app/owner' }],
    ['bot disguised as old human', { ...VALID_RECORD, mergedBy: 'openbank-gitops-bot[bot]' }],
    ['bot disguised as explicit human', { ...APP_RECORD, mergedByType: 'HUMAN', authorization: null }],
    ['human carrying App proof', { ...APP_RECORD, mergedByType: 'HUMAN', mergedBy: 'reviewer' }],
    ['foreign evidence link', { ...APP_RECORD, authorization: { ...APP_RECORD.authorization, evidenceUrl: 'https://foreign.example/proof' } }],
    ['wrong proof scope', { ...APP_RECORD, authorization: { ...APP_RECORD.authorization, scope: 'PR' } }],
    ['after-merge approval', { ...APP_RECORD, authorization: { ...APP_RECORD.authorization, approvedAt: '2026-10-02T00:00:00Z' } }],
    ['unsafe workflow id', { ...APP_RECORD, authorization: { ...APP_RECORD.authorization, workflowRunId: Number.MAX_SAFE_INTEGER + 1 } }],
    ['late legacy merge', { ...LEGACY_RECORD, mergedAt: '2026-10-24T00:00:00Z' }],
    ['future observation', { ...LEGACY_RECORD, authorization: { ...LEGACY_RECORD.authorization, observedAt: '9999-01-01T00:00:00Z' } }],
    ['unsafe PR link', { ...VALID_RECORD, prUrl: 'javascript:alert(1)' }],
    ['wrong linked PR', { ...VALID_RECORD, prUrl: 'https://github.com/openbank/open-bank-oss/pull/124' }],
  ])('drops unverified evidence: %s', (_name, value) => {
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

  it('reports a malformed non-array body instead of a valid empty audit', () => {
    expect(parseAgentMergeRecordList({ error: 'upstream_error' })).toEqual({ records: [], dropped: 1 })
    expect(parseAgentMergeRecordList(undefined)).toEqual({ records: [], dropped: 1 })
  })

  it('never throws on unknown agentId strings', () => {
    const odd = { ...VALID_RECORD, id: '6c9d1e2f-5a0b-4c4d-0a1b-1d2e3f4a5b62', agentId: 'agent/unknown ?weird=1' }
    expect(() => parseAgentMergeRecordList([VALID_RECORD, odd])).not.toThrow()
    const { records } = parseAgentMergeRecordList([VALID_RECORD, odd])
    expect(records.map(r => r.agentId)).toContain('agent/unknown ?weird=1')
  })
})

const POLICY_RECORD = {
  ...LEGACY_RECORD,
  mergedAt: '2026-10-24T10:00:00Z', recordedAt: '2026-10-24T10:00:05Z',
  authorization: null,
  policyAuthorization: {
    ...LEGACY_RECORD.authorization, kind: 'POLICY_PR_AUTO_MERGE',
    mergeEventAt: '2026-10-24T10:00:01Z', observedAt: '2026-10-24T10:00:02Z',
    policyCommitSha: 'c'.repeat(40), policyBlobSha: 'd'.repeat(40),
  },
}

describe('modern PR policy proof', () => {
  it('retains modern evidence and actual App in its separate PR slot', () => {
    expect(parseAgentMergeRecord(POLICY_RECORD)).toEqual(POLICY_RECORD)
    expect(parseAgentMergeRecordList([POLICY_RECORD]).dropped).toBe(0)
  })

  it.each([
    ['dual proof', { ...POLICY_RECORD, authorization: APP_RECORD.authorization }],
    ['wrong family', { ...POLICY_RECORD, authorization: POLICY_RECORD.policyAuthorization, policyAuthorization: null }],
    ['codex downgrade', { ...POLICY_RECORD, headBranch: 'codex/fixture' }],
    ['missing context', { ...POLICY_RECORD, policyAuthorization: { ...POLICY_RECORD.policyAuthorization, policyBlobSha: undefined } }],
    ['invalid context', { ...POLICY_RECORD, policyAuthorization: { ...POLICY_RECORD.policyAuthorization, policyCommitSha: 'bad' } }],
    ['head scope', { ...POLICY_RECORD, policyAuthorization: { ...POLICY_RECORD.policyAuthorization, scope: 'HEAD' } }],
    ['workflow id', { ...POLICY_RECORD, policyAuthorization: { ...POLICY_RECORD.policyAuthorization, statusId: 42 } }],
    ['human with policy proof', { ...VALID_RECORD, policyAuthorization: POLICY_RECORD.policyAuthorization }],
  ])('rejects %s', (_label, value) => expect(parseAgentMergeRecord(value)).toBeNull())
})
