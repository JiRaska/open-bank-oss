// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

const CASE_STATUSES = new Set(['OPEN', 'DOCUMENTS_REQUIRED', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'EXPIRED'])
const RISK_LEVELS = new Set(['LOW', 'MEDIUM', 'HIGH', 'VERY_HIGH'])
const CHECK_TYPES = new Set(['IDENTITY', 'ADDRESS', 'PEP_SCREENING', 'SANCTIONS_SCREENING', 'ADVERSE_MEDIA'])
const CHECK_STATUSES = new Set(['PENDING', 'PASSED', 'FAILED', 'MANUAL_REVIEW'])

export interface KycCheckEvidence {
  id: string
  checkType: string
  status: string
  result?: string | null
}

export interface KycCaseEvidence {
  id: string
  partyId: string
  status: string
  riskLevel: string
  checks: KycCheckEvidence[]
  reviewedBy?: string | null
  createdAt: string
  updatedAt: string
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isText(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

function isTimestamp(value: unknown): value is string {
  return isText(value) && Number.isFinite(Date.parse(value))
}

export function parseKycCaseEvidence(value: unknown, requestedPartyId?: string): KycCaseEvidence | null {
  if (!isRecord(value) || !isText(value.id) || !isText(value.partyId)) return null
  if (requestedPartyId !== undefined && value.partyId !== requestedPartyId) return null
  if (!CASE_STATUSES.has(String(value.status)) || !RISK_LEVELS.has(String(value.riskLevel))) return null
  if (!isTimestamp(value.createdAt) || !isTimestamp(value.updatedAt) || !Array.isArray(value.checks)) return null

  const checks: KycCheckEvidence[] = []
  for (const check of value.checks) {
    if (!isRecord(check) || !isText(check.id)) return null
    if (!CHECK_TYPES.has(String(check.checkType)) || !CHECK_STATUSES.has(String(check.status))) return null
    if (check.result !== undefined && check.result !== null && typeof check.result !== 'string') return null
    checks.push({
      id: check.id,
      checkType: String(check.checkType),
      status: String(check.status),
      result: check.result as string | null | undefined,
    })
  }
  if (value.reviewedBy !== undefined && value.reviewedBy !== null && !isText(value.reviewedBy)) return null
  return {
    id: value.id,
    partyId: value.partyId,
    status: String(value.status),
    riskLevel: String(value.riskLevel),
    checks,
    reviewedBy: value.reviewedBy as string | null | undefined,
    createdAt: value.createdAt,
    updatedAt: value.updatedAt,
  }
}

export interface KycCasePageEvidence {
  items: KycCaseEvidence[]
  total: number
  page: number
  size: number
  statusFilter: string | null
}

export function parseKycCasePageEvidence(
  value: unknown,
  requestedPage: number,
  requestedSize: number,
): KycCasePageEvidence | null {
  if (!isRecord(value) || !Array.isArray(value.items)) return null
  if (value.page !== requestedPage || value.size !== requestedSize) return null
  if (!Number.isInteger(value.total) || (value.total as number) < 0 || value.items.length > requestedSize) return null
  if (value.statusFilter !== null && value.statusFilter !== undefined && !CASE_STATUSES.has(String(value.statusFilter))) return null
  const items = value.items.map(item => parseKycCaseEvidence(item))
  if (items.some(item => item === null)) return null
  return {
    items: items as KycCaseEvidence[], total: value.total as number,
    page: requestedPage, size: requestedSize, statusFilter: value.statusFilter == null ? null : String(value.statusFilter),
  }
}
