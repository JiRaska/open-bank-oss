// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

export interface AmlCase {
  id: string
  customerName: string
  customerType: string
  riskLevel: string
  status: string
  score: number
  timestamp: string
}

export interface AmlCaseSnapshot {
  cases: AmlCase[]
  excludedCount: number
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)

const isNonEmptyString = (value: unknown): value is string =>
  typeof value === 'string' && value.trim().length > 0

const isAmlCase = (value: unknown): value is AmlCase => {
  if (!isRecord(value)) return false
  return isNonEmptyString(value.id)
    && isNonEmptyString(value.customerName)
    && isNonEmptyString(value.customerType)
    && isNonEmptyString(value.riskLevel)
    && isNonEmptyString(value.status)
    && typeof value.score === 'number'
    && Number.isFinite(value.score)
    && value.score >= 0
    && value.score <= 100
    && isNonEmptyString(value.timestamp)
    && !Number.isNaN(Date.parse(value.timestamp))
}

/** Keep usable evidence while making partial upstream corruption measurable to the operator. */
export function parseAmlCaseSnapshot(raw: unknown): AmlCaseSnapshot {
  const candidate = Array.isArray(raw)
    ? raw
    : isRecord(raw) && Array.isArray(raw.cases)
      ? raw.cases
      : []
  const cases = candidate.filter(isAmlCase)
  return { cases, excludedCount: candidate.length - cases.length }
}
