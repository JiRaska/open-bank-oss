// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod'

const number = z.number().finite().nonnegative()
const timestamp = z.iso.datetime({ offset: true })
const nullableText = z.string().nullable()
const assessment = z.object({
  period: z.string(), asOf: z.iso.date(), outstandingBalance: number, daysPastDue: number.int(),
  bucket: z.enum(['CURRENT', 'DPD_1_30', 'DPD_31_60', 'DPD_61_90', 'DPD_90_PLUS']),
  stage: z.enum(['STAGE_1', 'STAGE_2', 'STAGE_3']), expectedCreditLoss: number, modelVersion: z.string(),
})
export const decisionSchema = z.array(z.object({
  applicationId: z.string(), partyId: z.string(), status: z.string(), createdAt: timestamp,
  requestedAmount: number, currency: z.string().regex(/^[A-Z]{3}$/), termPeriods: number.int(), nominalAnnualRate: number,
  jurisdiction: nullableText, productType: nullableText, productKind: z.string(), packVersion: number.int().nullable(),
  engineOutcome: z.enum(['APPROVE', 'REFER', 'DECLINE', 'UNEVALUATED']), priceBand: nullableText,
  reasons: z.array(z.object({ code: z.string(), ruleId: nullableText })), matchedRuleIds: z.array(z.string()),
  policyVersions: z.record(z.string(), number.int()), inputSnapshotHash: nullableText, decidedEngineAt: timestamp.nullable(),
  affordability: z.object({ dsti: number, dti: number, dstiIncludingExistingDebt: number }).nullable(),
  verifiedIncomeMonthly: number.nullable(), existingDebtServiceMonthly: number.nullable(),
  ageYears: number.int().nullable(), residency: nullableText, employmentTenureMonths: number.int().nullable(),
  humanDecidedBy: nullableText, humanDecisionReason: nullableText, humanDecidedAt: timestamp.nullable(),
}))
export const outcomeSchema = z.array(z.object({ engineOutcome: z.enum(['APPROVE', 'REFER', 'DECLINE', 'UNEVALUATED']), priceBand: nullableText, count: number.int() }))
export const portfolioSchema = z.array(z.object({
  loanId: z.string(), applicationId: z.string(), partyId: z.string(), status: z.string(),
  principal: number, currency: z.string().regex(/^[A-Z]{3}$/), nominalAnnualRate: number, termPeriods: number.int(),
  disbursedAt: timestamp, assessment: assessment.nullable(),
}))
export const policySchema = z.object({
  asOf: z.iso.date(), source: z.string(), codeSeeded: z.boolean(), tables: z.array(z.object({
    kind: z.string(), name: z.string(), version: number.int(), effectiveFrom: z.iso.date(), effectiveTo: z.iso.date().nullable(),
    rules: z.array(z.object({ id: z.string(), attribute: z.string(), operator: z.string(), threshold: z.number().finite().nullable(), values: z.array(z.string()), band: nullableText, detail: z.string() })),
  })),
})
export const portfolioSummarySchema = z.array(z.object({
  currency: z.string().regex(/^[A-Z]{3}$/), asOf: z.iso.date(), loans: number.int(), unassessed: number.int(), stale: number.int(), demonstration: number.int(), pending: number.int(),
  outstanding: number, ecl: number, stage23Outstanding: number, over90Outstanding: number,
}).refine(row => row.unassessed + row.stale <= row.loans && row.demonstration <= row.loans && row.stage23Outstanding <= row.outstanding && row.over90Outstanding <= row.outstanding))
export type PortfolioSummary = z.infer<typeof portfolioSummarySchema>[number]
