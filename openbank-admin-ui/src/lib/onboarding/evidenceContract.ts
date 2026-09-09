// SPDX-License-Identifier: Apache-2.0

export const ONBOARDING_STAGES = [
  'REGISTERED', 'KYC_OPEN', 'KYC_UNDER_REVIEW', 'SCA_PENDING', 'ACTIVE', 'BLOCKED',
] as const

export type OnboardingStage = typeof ONBOARDING_STAGES[number]

export interface OnboardingRecordEvidence {
  partyId: string
  legalName: string | null
  email: string | null
  partyStatus: string
  kycCaseId: string | null
  kycStatus: string | null
  scaEnrolled: boolean
  deviceCount: number
  funnelStage: OnboardingStage
  blockedReason: string | null
  createdAt: string
  updatedAt: string
}

export interface OnboardingRecordPageEvidence {
  items: OnboardingRecordEvidence[]
  total: number
  page: number
  size: number
  stageFilter?: string
}

function record(value: unknown): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new Error('Invalid onboarding evidence')
  return value as Record<string, unknown>
}

function text(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.trim() === '') throw new Error(`Invalid ${field}`)
  return value
}

function nullableText(value: unknown, field: string): string | null {
  return value === null ? null : text(value, field)
}

function count(value: unknown, field: string): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) throw new Error(`Invalid ${field}`)
  return value
}

function instant(value: unknown, field: string): string {
  const parsed = text(value, field)
  if (Number.isNaN(Date.parse(parsed))) throw new Error(`Invalid ${field}`)
  return parsed
}

function stage(value: unknown): OnboardingStage {
  if (typeof value !== 'string' || !ONBOARDING_STAGES.includes(value as OnboardingStage)) {
    throw new Error('Invalid onboarding stage')
  }
  return value as OnboardingStage
}

export function parseFunnelCounts(raw: unknown): Record<OnboardingStage, number> {
  const body = record(raw)
  return Object.fromEntries(ONBOARDING_STAGES.map(item => [item, count(body[item], `${item} count`)])) as Record<OnboardingStage, number>
}

export function parseOnboardingPage(raw: unknown, expectedPage: number): OnboardingRecordPageEvidence {
  const body = record(raw)
  if (!Array.isArray(body.items) || body.page !== expectedPage) throw new Error('Mismatched onboarding page')
  const items = body.items.map(value => {
    const item = record(value)
    if (typeof item.scaEnrolled !== 'boolean') throw new Error('Invalid SCA state')
    return {
      partyId: text(item.partyId, 'party id'),
      legalName: nullableText(item.legalName, 'legal name'),
      email: nullableText(item.email, 'email'),
      partyStatus: text(item.partyStatus, 'party status'),
      kycCaseId: nullableText(item.kycCaseId, 'KYC case id'),
      kycStatus: nullableText(item.kycStatus, 'KYC status'),
      scaEnrolled: item.scaEnrolled,
      deviceCount: count(item.deviceCount, 'device count'),
      funnelStage: stage(item.funnelStage),
      blockedReason: nullableText(item.blockedReason, 'blocked reason'),
      createdAt: instant(item.createdAt, 'created timestamp'),
      updatedAt: instant(item.updatedAt, 'updated timestamp'),
    }
  })
  const stageFilter = body.stageFilter === undefined ? undefined : text(body.stageFilter, 'stage filter')
  return {
    items,
    total: count(body.total, 'record total'),
    page: count(body.page, 'page'),
    size: count(body.size, 'page size'),
    ...(stageFilter === undefined ? {} : { stageFilter }),
  }
}
