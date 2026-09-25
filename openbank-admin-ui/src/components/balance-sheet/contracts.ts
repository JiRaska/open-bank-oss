// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Wire contracts of the two backends the "Balance sheet & risk" section reads (#10618):
// openbank-risk-engine (openapi 1.3.0) and lending's ledger backfill (openapi 1.27.0). A response
// that does not parse is treated as unavailable, never half-rendered.
import { z } from 'zod'

const money = z.number().finite()
const provenance = z.enum(['synthetic', 'production'])
const tieOutStatus = z.enum(['TIED_OUT', 'UNTIED'])
const timestamp = z.string().min(1)

export const snapshotSummarySchema = z.object({
  id: z.string(), asOf: z.iso.date(), recordedAt: timestamp, provenance, status: tieOutStatus,
  positionCount: z.number().int(), mismatchCount: z.number().int(),
})
export const snapshotListSchema = z.object({ runs: z.array(snapshotSummarySchema) })

export const mismatchSchema = z.object({
  glAccountCode: z.string().nullable(), currency: z.string(), ledgerNet: money, positionsNet: money, difference: money,
})
export const snapshotRunSchema = snapshotSummarySchema.extend({
  inputHash: z.string(), mismatches: z.array(mismatchSchema),
})

export const instrumentSchema = z.object({
  id: z.string(), kind: z.string(), glAccountCode: z.string().nullable(), currency: z.string(), outstanding: money,
  valueDate: z.string().nullable(), maturityDate: z.string().nullable(),
  rateTerms: z.object({
    rateType: z.string(), currentAnnualRate: money.nullable(), index: z.string().nullable(), spread: money.nullable(),
    resetFrequencyMonths: z.number().int().nullable(), nextResetDate: z.string().nullable(),
  }).nullable(),
  counterpartyRef: z.string().nullable(), ifrs9Stage: z.string().nullable(),
  loan: z.looseObject({
    method: z.string(), periodsPerYear: z.number().int(), remainingPeriods: z.number().int(), nextDueDate: z.string().nullable(),
  }).nullable(),
})
export const instrumentsSchema = z.object({ runId: z.string(), asOf: z.string(), instruments: z.array(instrumentSchema) })

export const BUCKETS = ['overnight', '<1M', '1-3M', '3-6M', '6-12M', '1-2Y', '2-3Y', '3-5Y', '5-10Y', '>10Y'] as const
export const currencyFlowsSchema = z.object({
  currency: z.string(), discountIndex: z.string().nullable(), positions: z.number().int(), priced: z.boolean(),
  buckets: z.array(z.object({ bucket: z.string(), amount: money })), total: money, presentValue: money.nullable(),
})
export const cashFlowsSchema = z.object({
  runId: z.string(), asOf: z.string(), provenance, curveSetId: z.string(), curveSetProvenance: provenance,
  model: z.object({ id: z.string(), version: z.string(), coreRatio: money, coreRunoffYears: z.number().int(), annualDepositRate: money }),
  expandedPositions: z.number().int(), notExpanded: z.number().int(), notExpandedReason: z.string(),
  currencies: z.array(currencyFlowsSchema), unpriced: z.array(z.string()),
})

export const curveSetSummarySchema = z.object({
  id: z.string(), asOf: z.iso.date(), provenance, source: z.string(), recordedAt: timestamp, indices: z.array(z.string()),
})
export const curveSetListSchema = z.object({ curveSets: z.array(curveSetSummarySchema) })
export const curveSetSchema = z.object({
  id: z.string(), asOf: z.iso.date(), provenance, source: z.string(), recordedAt: timestamp,
  curves: z.array(z.object({
    index: z.string(), currency: z.string(),
    pillars: z.array(z.object({ date: z.string(), zeroRate: money, discountFactor: money })),
  })),
})

// ── Lending ledger backfill ───────────────────────────────────────────────────────────────────
export const backfillTieOutSchema = z.object({
  currency: z.string(), loansReceivableAfter: money, lendingUnpaidPrincipal: money, ties: z.boolean(),
})
export const backfillPlanSchema = z.object({
  plan: z.object({
    cutoverDate: z.string(), planHash: z.string(), tieOut: z.array(backfillTieOutSchema),
    loans: z.array(z.object({
      loanId: z.string(), currency: z.string(), status: z.string(), unpaidPrincipal: money,
      unsupportedReason: z.string().nullable().optional(), legs: z.array(z.unknown()),
    })),
  }),
  executable: z.boolean(), journalCount: z.number().int(),
  glTotals: z.array(z.object({ code: z.string(), currency: z.string(), debit: money, credit: money, net: money })),
})
export const backfillStateSchema = z.enum(['PROPOSED', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'EXECUTED'])
export const backfillRequestSchema = z.object({
  id: z.string(), state: backfillStateSchema, cutoverDate: z.string(), planHash: z.string(),
  loanCount: z.number().int(), legCount: z.number().int(), proposedBy: z.string(),
  decidedBy: z.string().nullable(), decisionReason: z.string().nullable(), executedBy: z.string().nullable(),
  proposedAt: z.string().nullable().optional(), decidedAt: z.string().nullable().optional(),
  executedAt: z.string().nullable().optional(),
})
export const backfillRequestListSchema = z.object({ requests: z.array(backfillRequestSchema) })
export const backfillExecutionSchema = z.object({
  execution: z.looseObject({
    requestId: z.string(), executed: z.boolean(), complete: z.boolean(),
    loans: z.array(z.looseObject({ loanId: z.string(), status: z.string(), legsPosted: z.number().int(), legsTotal: z.number().int() })),
  }),
})

// IRRBB (risk-engine GET /snapshots/{id}/irrbb, ADR-0313 phase 1).
export const SCENARIOS = ['parallel-up', 'parallel-down', 'steepener', 'flattener', 'short-up', 'short-down'] as const
const nullableMoney = money.nullable()
export const gapBucketSchema = z.object({ bucket: z.string(), assets: money, liabilities: money, gap: money, cumulativeGap: money })
export const currencyGapSchema = z.object({
  currency: z.string(), buckets: z.array(gapBucketSchema), totalAssets: money, totalLiabilities: money, totalGap: money,
})
export const currencyScenarioSchema = z.object({
  currency: z.string(), basePv: money, shockedPv: money, deltaEve: money, eveLoss: money, deltaNii: nullableMoney.optional(),
})
export const scenarioSchema = z.object({
  scenario: z.enum(SCENARIOS), currencies: z.array(currencyScenarioSchema), aggregateLoss: nullableMoney.optional(),
})
export const irrbbSchema = z.object({
  runId: z.string(), asOf: z.string(), provenance, curveSetId: z.string(), curveSetProvenance: provenance, curveSetSource: z.string(),
  gaps: z.array(currencyGapSchema),
  scenarios: z.array(scenarioSchema),
  worstCase: z.object({
    scenario: z.enum(SCENARIOS).nullable().optional(), loss: nullableMoney.optional(), currency: z.string().nullable().optional(),
    byCurrency: z.record(z.string(), z.enum(SCENARIOS)),
  }),
  outlierTest: z.object({
    tier1Supplied: z.boolean(), tier1Capital: nullableMoney.optional(), currency: z.string().nullable().optional(),
    threshold: money, ratio: nullableMoney.optional(), breached: z.boolean().nullable().optional(), note: z.string(),
  }),
  shockNotConfigured: z.array(z.string()),
  unpriced: z.array(z.string()),
  assumptions: z.object({
    model: z.object({ id: z.string(), version: z.string() }).passthrough(),
    shockSizes: z.array(z.object({ currency: z.string(), parallelBp: money, shortBp: money, longBp: money })),
    shockSource: z.string(), shortDecayYears: money,
    postShockFloor: z.object({ atZeroBp: money, slopeBpPerYear: money }).nullable().optional(),
    postShockFloorSource: z.string(), nmdRepricing: z.string(), floatingRepricing: z.string(),
    eveBasis: z.string(), niiBasis: z.string(), niiHorizonMonths: z.number().int(), currencyAggregation: z.string(),
  }),
})

export type Irrbb = z.infer<typeof irrbbSchema>
export type CurrencyGap = z.infer<typeof currencyGapSchema>
export type ScenarioName = typeof SCENARIOS[number]
export type SnapshotSummary = z.infer<typeof snapshotSummarySchema>
export type SnapshotRun = z.infer<typeof snapshotRunSchema>
export type Instrument = z.infer<typeof instrumentSchema>
export type CashFlows = z.infer<typeof cashFlowsSchema>
export type CurrencyFlows = z.infer<typeof currencyFlowsSchema>
export type CurveSetSummary = z.infer<typeof curveSetSummarySchema>
export type CurveSet = z.infer<typeof curveSetSchema>
export type BackfillPlan = z.infer<typeof backfillPlanSchema>
export type BackfillRequest = z.infer<typeof backfillRequestSchema>
export type BackfillExecution = z.infer<typeof backfillExecutionSchema>
export type Provenance = z.infer<typeof provenance>
