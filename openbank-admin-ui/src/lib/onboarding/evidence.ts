// SPDX-License-Identifier: Apache-2.0

export const ONBOARDING_STAGES = [
  'REGISTERED',
  'KYC_OPEN',
  'KYC_DOCUMENTS_REQUIRED',
  'KYC_UNDER_REVIEW',
  'SCA_PENDING',
  'ACTIVE',
  'BLOCKED',
] as const

export type OnboardingStage = typeof ONBOARDING_STAGES[number]

export interface OnboardingRecord {
  partyId: string
  legalName: string | null
  email: string | null
  partyStatus: 'PENDING_KYC' | 'ACTIVE' | 'SUSPENDED' | 'CLOSED'
  kycCaseId: string | null
  kycStatus: 'OPEN' | 'DOCUMENTS_REQUIRED' | 'UNDER_REVIEW' | 'APPROVED' | 'REJECTED' | 'EXPIRED' | null
  scaEnrolled: boolean
  deviceCount: number
  funnelStage: OnboardingStage
  blockedReason: string | null
  createdAt: string
  updatedAt: string
}

export interface OnboardingRecordPage {
  items: OnboardingRecord[]
  total: number
  page: number
  size: number
  stageFilter?: OnboardingStage
}

const object = (value: unknown): Record<string, unknown> | null =>
  typeof value === 'object' && value !== null && !Array.isArray(value) ? value as Record<string, unknown> : null
const integer = (value: unknown): value is number => Number.isSafeInteger(value) && (value as number) >= 0
const nullableText = (value: unknown): value is string | null => value === null || typeof value === 'string'
// Match java.util.UUID.fromString semantics used by the service: UUID syntax is
// authoritative here; version and variant bits are not constrained by the API.
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)
const instant = (value: unknown): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value))
const member = <T extends readonly string[]>(value: unknown, values: T): value is T[number] =>
  typeof value === 'string' && values.includes(value)

export function parseFunnelCounts(value: unknown): Record<OnboardingStage, number> | null {
  const raw = object(value)
  if (!raw || Object.keys(raw).length !== ONBOARDING_STAGES.length) return null
  for (const stage of ONBOARDING_STAGES) if (!integer(raw[stage])) return null
  return raw as Record<OnboardingStage, number>
}

export function parseOnboardingRecordPage(
  value: unknown,
  expectedPage: number,
  expectedStage: OnboardingStage | '',
): OnboardingRecordPage | null {
  const raw = object(value)
  if (!raw || !Array.isArray(raw.items) || raw.items.length > 20 || !integer(raw.total) ||
    raw.page !== expectedPage || raw.size !== 20 || raw.items.length > raw.total) return null
  if (expectedStage ? raw.stageFilter !== expectedStage : raw.stageFilter !== undefined && raw.stageFilter !== null) return null

  const items: OnboardingRecord[] = []
  for (const candidate of raw.items) {
    const item = object(candidate)
    if (!item || !uuid(item.partyId) || !nullableText(item.legalName) || !nullableText(item.email) ||
      !member(item.partyStatus, ['PENDING_KYC', 'ACTIVE', 'SUSPENDED', 'CLOSED'] as const) ||
      !(item.kycCaseId === null || uuid(item.kycCaseId)) ||
      !(item.kycStatus === null || member(item.kycStatus, ['OPEN', 'DOCUMENTS_REQUIRED', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'EXPIRED'] as const)) ||
      typeof item.scaEnrolled !== 'boolean' || !integer(item.deviceCount) ||
      !member(item.funnelStage, ONBOARDING_STAGES) || !nullableText(item.blockedReason) ||
      !instant(item.createdAt) || !instant(item.updatedAt)) return null
    if (expectedStage && item.funnelStage !== expectedStage) return null
    items.push(item as unknown as OnboardingRecord)
  }
  return { items, total: raw.total, page: expectedPage, size: 20, ...(expectedStage && { stageFilter: expectedStage }) }
}
