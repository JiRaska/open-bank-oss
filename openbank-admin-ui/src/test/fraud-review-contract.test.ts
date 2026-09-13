// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { parseFraudReviewQueue } from '@/lib/fraud/fraudReviewContract'

const row = {
  scoreId: '11111111-1111-4111-8111-111111111111', amount: 125000, currency: 'CZK', rail: 'SCT_INST',
  accountId: '22222222-2222-4222-8222-222222222222', counterpartyId: null, verdict: 'REVIEW', score: 91,
  ruleVersion: 'v4', createdAt: '2026-09-09T08:15:00Z',
}

describe('fraud review response contract', () => {
  it('preserves review and rule-version evidence', () => {
    expect(parseFraudReviewQueue([row])).toMatchObject([{ verdict: 'REVIEW', score: 91, ruleVersion: 'v4' }])
  })

  it.each([
    [{ ...row, verdict: 'ALLOW' }, 'verdict'],
    [{ ...row, score: -1 }, 'score'],
    [{ ...row, amount: Number.NaN }, 'amount'],
    [{ ...row, currency: 'CZ' }, 'currency'],
    [{ ...row, createdAt: 'not-a-date' }, 'createdAt'],
  ])('rejects malformed analyst evidence', (candidate, message) => {
    expect(() => parseFraudReviewQueue([candidate])).toThrow(message)
  })
})
