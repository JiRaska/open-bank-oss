// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Wire contracts of openbank-pension-service (participant side) and openbank-pension-fund-service
// (funds, strategies, NAV, unit register) — ADR-0334, the two services' openapi.yaml. A response
// that does not parse is treated as unavailable, never half-rendered.
import { z } from 'zod'

/** BigDecimal: Jackson writes a JSON number, but a plain-string configuration must not break the page. */
const decimal = z.union([z.number(), z.string()]).transform(Number).pipe(z.number().finite())
const nullableDecimal = z.union([decimal, z.null()]).optional().transform(v => v ?? null)
const optionalText = z.string().nullable().optional().transform(v => v ?? null)

export const CONTRACT_STATUSES = [
  'DRAFT', 'PENDING_ACTIVATION', 'ACTIVE', 'SUSPENDED', 'TERMINATING', 'PAID_OUT', 'TRANSFERRED_OUT', 'CLOSED',
] as const

export const strategyElectionSchema = z.object({
  strategyCode: z.string(),
  effectiveFrom: optionalText,
  electedAt: optionalText,
})

export const pensionContractSchema = z.object({
  contractId: z.string().min(1),
  participantPartyId: optionalText,
  productLine: z.string(),
  jurisdiction: z.string(),
  packVersion: z.number().int().nullable().optional(),
  providerEntityId: optionalText,
  providerType: optionalText,
  // Extensible: a newer lifecycle state must not make the whole contract unavailable.
  status: z.string().min(1),
  schedule: z.object({
    amount: decimal, currency: z.string(), frequency: z.string(), employerAmount: nullableDecimal,
  }).nullable().optional(),
  currentStrategy: strategyElectionSchema.nullable().optional(),
  strategyHistory: z.array(strategyElectionSchema).optional().default([]),
  beneficiaries: z.array(z.object({ name: z.string(), sharePercent: decimal, partyId: optionalText })).optional().default([]),
  startDate: optionalText,
  createdAt: optionalText,
  updatedAt: optionalText,
})
export type PensionContract = z.infer<typeof pensionContractSchema>
export const pensionContractListSchema = z.array(pensionContractSchema)

export const fundSchema = z.object({
  id: z.string().min(1),
  name: z.string(),
  isin: z.string(),
  lei: optionalText,
  currency: z.string(),
  riskClass: z.number().int(),
  mandatoryConservative: z.boolean().optional().default(false),
  managementFeeRate: decimal,
  status: z.string(),
})
export type Fund = z.infer<typeof fundSchema>
export const fundListSchema = z.array(fundSchema)

export const allocationSchema = z.object({ fundId: z.string(), weight: decimal, lowerBand: decimal, upperBand: decimal })
export type Allocation = z.infer<typeof allocationSchema>

export const strategySchema = z.object({
  id: z.string().min(1),
  name: z.string(),
  allocations: z.array(allocationSchema),
  lifecycle: z.boolean().optional().default(false),
  status: z.string(),
  version: z.number().int().optional(),
})
export type Strategy = z.infer<typeof strategySchema>
export const strategyListSchema = z.array(strategySchema)

export const strategyChangeSchema = z.object({
  id: z.string().min(1),
  strategyId: z.string(),
  proposedAllocations: z.array(allocationSchema),
  reason: z.string(),
  effectiveDate: z.string(),
  submittedBy: z.string(),
  submittedAt: optionalText,
  status: z.string(),
  decidedBy: optionalText,
  participantNotificationDate: optionalText,
  appliedAt: optionalText,
})
export type StrategyChange = z.infer<typeof strategyChangeSchema>
export const strategyChangeListSchema = z.array(strategyChangeSchema)

export const navSchema = z.object({
  id: z.string().min(1),
  fundId: z.string(),
  valuationDate: z.string(),
  grossAssets: decimal,
  accruedManagementFee: decimal,
  netAssets: decimal,
  unitsOutstanding: decimal,
  navPerUnit: decimal,
  status: z.string(),
  calculatedBy: z.string(),
  approvedBy: optionalText,
  publishedAt: optionalText,
  correctsNavId: optionalText,
})
export type Nav = z.infer<typeof navSchema>
export const navListSchema = z.array(navSchema)
export const navPublicationSchema = z.object({
  nav: navSchema,
  settledOrders: z.number().int().optional().default(0),
  corrections: z.array(z.unknown()).optional().default([]),
})

export const holdingSchema = z.object({
  fundId: z.string(),
  units: decimal,
  navPerUnit: nullableDecimal,
  navDate: optionalText,
  value: nullableDecimal,
  currency: z.string(),
})
export const contractValuationSchema = z.object({
  contractId: z.string(),
  holdings: z.array(holdingSchema),
  pendingOrders: z.array(z.unknown()).optional().default([]),
})
export type ContractValuation = z.infer<typeof contractValuationSchema>

export const unitTransactionSchema = z.object({
  id: z.string(),
  fundId: z.string(),
  type: z.string(),
  units: decimal,
  amount: decimal,
  navPerUnit: decimal,
  pricedAt: z.string(),
})
export type UnitTransaction = z.infer<typeof unitTransactionSchema>
export const unitTransactionListSchema = z.array(unitTransactionSchema)

/**
 * The operator queues of backend slices S2/S3/S5 (#12350) — onboarding applications, transfers,
 * unmatched contributions, incentive claim batches, payouts and death claims — come from routes
 * still in review, each with its own row shape. A queue row is therefore read as an open record and
 * rendered column by column (dotted paths reach nested fields, e.g. `application.status`); nothing
 * beyond "it is an object" is assumed, so a shape change degrades a cell, never the whole queue.
 */
export const queueRowSchema = z.record(z.string(), z.unknown())
export type QueueRow = z.infer<typeof queueRowSchema>
export const queueSchema = z.array(queueRowSchema)

/** A dotted-path read into an open queue row. */
export function fieldOf(row: QueueRow, path: string): unknown {
  return path.split('.').reduce<unknown>((v, k) => (v && typeof v === 'object' ? (v as Record<string, unknown>)[k] : undefined), row)
}

/** The row's own id, whichever name the slice gave it. */
export function rowIdOf(row: QueueRow): string | null {
  for (const path of ['id', 'transferId', 'applicationId', 'application.applicationId', 'claimId', 'payoutId', 'contractId']) {
    const v = fieldOf(row, path)
    if (typeof v === 'string' && v.length > 0) return v
  }
  return null
}

/**
 * An annuity partner in pension-service's registry (API 1.2.0, #12383). Terms are an open record:
 * the console shows them, the service validates them. `proposedBy` edited the pending terms and
 * `activationRequestedBy` asked for activation; the approver must be neither (four-eyes).
 */
export const annuityProviderSchema = z.object({
  partnerId: z.string(),
  status: z.string(),
  liveVersion: z.number().nullable().optional(),
  liveTerms: z.record(z.string(), z.unknown()).nullable().optional(),
  approvedBy: z.string().nullable().optional(),
  approvedAt: z.string().nullable().optional(),
  proposedVersion: z.number().nullable().optional(),
  proposedTerms: z.record(z.string(), z.unknown()).nullable().optional(),
  proposedBy: z.string().nullable().optional(),
  activationRequestedBy: z.string().nullable().optional(),
  updatedAt: z.string().nullable().optional(),
})
export type AnnuityProvider = z.infer<typeof annuityProviderSchema>
export const annuityProviderListSchema = z.array(annuityProviderSchema)

/** State-contribution deadline counters (ZDPS §16/§18): what is late right now. */
export const stateContributionDeadlinesSchema = z.object({
  claimsPastFilingDeadline: z.number(),
  claimsPastExpectedPayment: z.number(),
  returnsOverdue: z.number(),
})
export type StateContributionDeadlines = z.infer<typeof stateContributionDeadlinesSchema>

/** The operator view of one onboarding application (GET /operator/onboarding/applications/{id}). */
export const operatorApplicationSchema = z.object({
  partyId: z.string().nullable().optional(),
  application: z.record(z.string(), z.unknown()),
})
export type OperatorApplication = z.infer<typeof operatorApplicationSchema>

/** Staff read of a contract's schedule or beneficiary designation (open record, rendered per field). */
export const openRecordSchema = z.record(z.string(), z.unknown())
