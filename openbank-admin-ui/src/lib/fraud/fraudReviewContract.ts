// SPDX-License-Identifier: Apache-2.0

export interface FraudReviewEvidence {
  scoreId: string
  amount: number
  currency: string
  rail: string
  accountId: string | null
  counterpartyId: string | null
  verdict: 'REVIEW'
  score: number
  ruleVersion: string
  createdAt: string
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function stringField(record: Record<string, unknown>, field: string): string {
  const value = record[field]
  if (typeof value !== 'string' || value.trim() === '') throw new Error(`Invalid fraud ${field}`)
  return value
}

function nullableUuid(record: Record<string, unknown>, field: string): string | null {
  const value = record[field]
  if (value === null) return null
  const parsed = stringField(record, field)
  if (!UUID.test(parsed)) throw new Error(`Invalid fraud ${field}`)
  return parsed
}

function parseFraudReview(value: unknown): FraudReviewEvidence {
  if (!isRecord(value)) throw new Error('Invalid fraud review evidence')
  const scoreId = stringField(value, 'scoreId')
  const amount = value.amount
  const score = value.score
  const currency = stringField(value, 'currency')
  const verdict = stringField(value, 'verdict')
  const createdAt = stringField(value, 'createdAt')
  if (!UUID.test(scoreId)) throw new Error('Invalid fraud scoreId')
  if (typeof amount !== 'number' || !Number.isFinite(amount) || amount <= 0) throw new Error('Invalid fraud amount')
  if (typeof score !== 'number' || !Number.isInteger(score) || score < 0) throw new Error('Invalid fraud score')
  if (!/^[A-Z]{3}$/.test(currency)) throw new Error('Invalid fraud currency')
  if (verdict !== 'REVIEW') throw new Error('Invalid fraud verdict')
  if (Number.isNaN(Date.parse(createdAt))) throw new Error('Invalid fraud createdAt')

  return {
    scoreId,
    amount,
    currency,
    rail: stringField(value, 'rail'),
    accountId: nullableUuid(value, 'accountId'),
    counterpartyId: nullableUuid(value, 'counterpartyId'),
    verdict: 'REVIEW',
    score,
    ruleVersion: stringField(value, 'ruleVersion'),
    createdAt,
  }
}

export function parseFraudReviewQueue(raw: unknown): FraudReviewEvidence[] {
  if (!Array.isArray(raw)) throw new Error('Invalid fraud review queue')
  return raw.map(parseFraudReview)
}
