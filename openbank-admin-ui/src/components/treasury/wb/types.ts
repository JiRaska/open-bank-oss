// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Wire shapes of the ADR-0344 treasury workbench (treasury API 1.19.0 and the shared contract for
// slices not yet built). Everything beyond the legacy deal is LENIENT on purpose: unknown fields
// are ignored and additive ones are optional, so a service release that adds a column never turns
// a page into "unavailable".
import { z } from 'zod'
import { dealSchema } from '@/components/treasury/contracts'

const decimal = z.union([z.number(), z.string()]).transform(Number).pipe(z.number().finite())
const iso = z.string().min(1)
const nullishDate = iso.nullish()

export const actorTypeSchema = z.string()

export const legRateSchema = z.object({
  type: z.string(),
  rate: decimal.nullish(),
  index: z.string().nullish(),
  spread: decimal.nullish(),
  fixingLagBusinessDays: z.number().nullish(),
  resetFrequency: z.string().nullish(),
  cap: decimal.nullish(),
  floor: decimal.nullish(),
  capFloorAppliesTo: z.string().nullish(),
  rateUntilFirstFixing: decimal.nullish(),
})
export type LegRate = z.infer<typeof legRateSchema>

export const legSpecSchema = z.object({
  payReceive: z.string(),
  currency: z.string(),
  notional: decimal,
  amortisation: z.object({
    type: z.string(),
    schedule: z.array(z.object({ date: iso, amount: decimal })).optional(),
  }).nullish(),
  accrualStart: iso,
  accrualEnd: iso,
  accrualFrequency: z.string().nullish(),
  paymentFrequency: z.string().nullish(),
  stub: z.string().nullish(),
  adjustPaymentDate: z.boolean().nullish(),
  adjustAccrualPeriod: z.boolean().nullish(),
  paymentLagBusinessDays: z.number().nullish(),
  dayCount: z.string(),
  rate: legRateSchema,
  initialNotionalExchange: z.boolean().nullish(),
  finalNotionalExchange: z.boolean().nullish(),
  amortisationStart: nullishDate,
  paymentCalendars: z.array(z.string()).nullish(),
})
export type LegSpec = z.infer<typeof legSpecSchema>

const collateralSchema = z.object({
  securityIsin: z.string(),
  nominal: decimal,
  haircutPercent: decimal,
  dirtyPrice: decimal.nullish(),
  substitutionAllowed: z.boolean().nullish(),
  marginCallThreshold: decimal.nullish(),
})

const regulatorySchema = z.object({
  tradeReporting: z.object({
    uti: z.string().nullish(), counterpartyLei: z.string().nullish(),
    clearingObligation: z.boolean().nullish(), collateralisation: z.string().nullish(),
  }).nullish(),
  centralBankCodes: z.array(z.string()).nullish(),
  lcrCategory: z.string().nullish(),
  nsfrCategory: z.string().nullish(),
}).nullish()
export type RegulatoryTerms = NonNullable<z.infer<typeof regulatorySchema>>

const limitFigures = z.object({
  currency: z.string().nullish(), limit: decimal.nullish(), exposureBefore: decimal.nullish(),
  exposureAfter: decimal.nullish(), headroomAfter: decimal.nullish(), breached: z.boolean().nullish(),
})

/** The deal as the 1.19.0 service returns it: the legacy shape plus the additive ADR-0344 fields. */
export const ticketDealSchema = dealSchema.extend({
  kind: z.string().nullish(),
  bookId: z.string().nullish(),
  legs: z.array(legSpecSchema).nullish(),
  scheduleVersion: z.number().nullish(),
  scheduleHash: z.string().nullish(),
  version: z.number().nullish(),
  securityIsin: z.string().nullish(),
  regulatory: regulatorySchema,
  linkedDealIds: z.array(z.string()).nullish(),
  collateral: collateralSchema.nullish(),
  legalEntityId: z.string().nullish(),
  counterpartyReference: z.string().nullish(),
  channel: z.string().nullish(),
  tradeDateTime: z.string().nullish(),
  effectiveDate: nullishDate,
  validFrom: nullishDate,
  validTo: nullishDate,
  jurisdiction: z.string().nullish(),
  calendars: z.array(z.string()).nullish(),
  businessDayConvention: z.string().nullish(),
  rollDirection: z.string().nullish(),
  fixedConvention: z.boolean().nullish(),
  notionalExchange: z.boolean().nullish(),
  singleCurrency: z.boolean().nullish(),
  note: z.string().nullish(),
  settlementAccounts: z.object({ cashAccountId: z.string().nullish(), securitiesAccountId: z.string().nullish() }).nullish(),
  documents: z.array(z.object({ documentId: z.string(), type: z.string() })).nullish(),
  fees: z.array(z.object({ type: z.string(), amount: decimal, currency: z.string(), paymentDate: iso })).nullish(),
  linkedDeals: z.array(z.object({ dealId: z.string(), relation: z.string() })).nullish(),
  originalDealId: z.string().nullish(),
  securityTerms: z.object({
    securityIsin: z.string().nullish(), nominal: decimal.nullish(), cleanPrice: decimal.nullish(), yield: decimal.nullish(),
    accruedInterest: decimal.nullish(), settlementPlace: z.string().nullish(), custodyAccountId: z.string().nullish(),
  }).nullish(),
  fxTerms: z.object({
    buyCurrency: z.string().nullish(), buyAmount: decimal.nullish(), sellCurrency: z.string().nullish(), sellAmount: decimal.nullish(),
    rate: decimal.nullish(), forwardPoints: decimal.nullish(), nearDate: nullishDate, farDate: nullishDate,
    nearRate: decimal.nullish(), farRate: decimal.nullish(),
  }).nullish(),
  fraTerms: z.object({ period: z.string().nullish(), settlementConvention: z.string().nullish() }).nullish(),
  counterpartyName: z.string().nullish(),
  notional: decimal.nullish(),
  lastChangedAt: z.string().nullish(),
  lastChangedBy: z.string().nullish(),
  creditExposureCheck: z.object({
    counterparty: limitFigures.nullish(),
    product: z.object({ breached: z.boolean().nullish(), breaches: z.array(z.object({ rule: z.string(), limit: z.unknown().optional(), actual: z.unknown().optional() })).nullish() }).nullish(),
    country: limitFigures.nullish(),
    override: z.object({ by: z.string(), reason: z.string(), at: z.string() }).nullish(),
  }).nullish(),
  inputs: z.unknown().optional(),
})
export type TicketDeal = z.infer<typeof ticketDealSchema>
export const ticketDealListSchema = z.array(ticketDealSchema)

// ── cash-flow ledger (contract 3) ───────────────────────────────────────────────────────────────
export const FLOW_KINDS = ['PRINCIPAL', 'INTEREST', 'FEE', 'NOTIONAL_EXCHANGE', 'PREMIUM'] as const
export const cashFlowSchema = z.object({
  flowId: z.string(),
  legIndex: z.number(),
  periodIndex: z.number(),
  kind: z.string(),
  direction: z.string(),
  currency: z.string(),
  accrualStart: nullishDate,
  accrualEnd: nullishDate,
  fixingDate: nullishDate,
  paymentDate: iso,
  notional: decimal.nullish(),
  rate: decimal.nullish(),
  rateSource: z.string(),
  amount: decimal.nullish(),
  state: z.string(),
  manual: z.boolean().default(false),
  original: z.object({ paymentDate: nullishDate, amount: decimal.nullish() }).nullish(),
  override: z.object({
    overrideId: z.string().nullish(), reason: z.string().nullish(), by: z.string().nullish(), approvedBy: z.string().nullish(),
  }).nullish(),
  paymentId: z.string().nullish(),
  journalRefs: z.array(z.string()).nullish(),
})
export type CashFlow = z.infer<typeof cashFlowSchema>

export const cashFlowLedgerSchema = z.object({
  dealId: z.string(),
  dealVersion: z.number().nullish(),
  scheduleVersion: z.number(),
  scheduleHash: z.string().nullish(),
  generatedAt: z.string().nullish(),
  flows: z.array(cashFlowSchema),
})
export type CashFlowLedger = z.infer<typeof cashFlowLedgerSchema>

export const cashFlowOverrideSchema = z.object({
  overrideId: z.string(),
  dealId: z.string().nullish(),
  flowId: z.string(),
  status: z.string(),
  paymentDate: nullishDate,
  amount: decimal.nullish(),
  reason: z.string(),
  proposedBy: z.string(),
  proposedByType: z.string().nullish(),
  proposedAt: z.string(),
  decidedBy: z.string().nullish(),
  decidedAt: z.string().nullish(),
  decisionReason: z.string().nullish(),
})
export type CashFlowOverride = z.infer<typeof cashFlowOverrideSchema>

export const cashFlowConflictSchema = z.object({
  conflictId: z.string(),
  overrideId: z.string(),
  legIndex: z.number(),
  periodIndex: z.number(),
  fromScheduleVersion: z.number().nullish(),
  toScheduleVersion: z.number().nullish(),
  detectedAt: z.string(),
  reason: z.string(),
})
export const cashFlowConflictListSchema = z.array(cashFlowConflictSchema)

export const dealVersionSchema = z.object({
  version: z.number(),
  scheduleVersion: z.number().nullish(),
  createdBy: z.string().nullish(),
  createdAt: z.string(),
  changes: z.array(z.string()).nullish(),
})
export const dealHistorySchema = z.object({
  transitions: z.array(z.object({
    from: z.string().nullish(), to: z.string(), actor: z.string(), actorType: z.string(), at: z.string(), note: z.string().nullish(),
  })).default([]),
  versions: z.array(dealVersionSchema).default([]),
})
export type DealHistory = z.infer<typeof dealHistorySchema>

// ── reference data (contract 1, 8.6) ────────────────────────────────────────────────────────────
export const referenceRecordSchema = z.object({
  kind: z.string(),
  key: z.string(),
  version: z.number(),
  status: z.string(),
  validFrom: iso,
  validTo: nullishDate,
  verified: z.boolean().default(false),
  payload: z.record(z.string(), z.unknown()).default({}),
  createdBy: z.string(),
  createdByType: z.string().nullish(),
  createdAt: z.string(),
  approvedBy: z.string().nullish(),
  approvedAt: z.string().nullish(),
  verifiedBy: z.string().nullish(),
  reason: z.string().nullish(),
  decisionReason: z.string().nullish(),
  approvalNote: z.string().nullish(),
})
export type ReferenceRecord = z.infer<typeof referenceRecordSchema>
export const referenceListSchema = z.array(referenceRecordSchema)
export const referenceHistorySchema = z.object({
  kind: z.string(), key: z.string(), active: referenceRecordSchema.nullish(), versions: z.array(referenceRecordSchema),
})
export const referenceDependenciesSchema = z.object({
  kind: z.string(), key: z.string(),
  dependants: z.array(z.object({ type: z.string(), id: z.string(), field: z.string().nullish() })),
})
export const userActivitySchema = z.array(z.object({
  at: z.string(), actor: z.string(), action: z.string(), kind: z.string(), key: z.string(), version: z.number(),
}))
export const versionHistorySchema = z.array(z.object({ version: z.string(), description: z.string(), installedOn: z.string() }))

// ── calendar, views, closes ─────────────────────────────────────────────────────────────────────
export const EVENT_TYPES = ['REFIXING', 'INTEREST_PAYMENT', 'PRINCIPAL_PAYMENT', 'MATURITY', 'OPTION_EXERCISE', 'MARGIN_CALL', 'SETTLEMENT'] as const
export const treasuryEventSchema = z.object({
  date: iso, type: z.string(), description: z.string(), dealId: z.string(),
  flowId: z.string().nullish(), paymentId: z.string().nullish(), responsibleRole: z.string().nullish(),
})
export const treasuryEventListSchema = z.array(treasuryEventSchema)
export type TreasuryEvent = z.infer<typeof treasuryEventSchema>

export const savedFilterSchema = z.object({ name: z.string(), filter: z.record(z.string(), z.unknown()), updatedAt: z.string().nullish() })
export const savedFilterListSchema = z.array(savedFilterSchema)
export const positionCloseSchema = z.object({
  snapshotId: z.string().nullish(), date: z.string().nullish(), bookId: z.string().nullish(), scenario: z.string().nullish(),
  special: z.boolean().nullish(), closedBy: z.string().nullish(), closedAt: z.string().nullish(),
})

// ── fixings, market data, payments, day completion (contract 4, 5, 6, 8.5) ─────────────────────
export const fixingRowSchema = z.object({
  index: z.string(), fixingDate: iso, status: z.string(), rate: decimal.nullish(), source: z.string().nullish(),
  observedAt: z.string().nullish(), dealCount: z.number().nullish(), flowIds: z.array(z.string()).nullish(),
  blockReason: z.string().nullish(),
  quality: z.object({ stale: z.boolean().nullish(), jumpBp: decimal.nullish(), missing: z.boolean().nullish() }).nullish(),
})
export type FixingRow = z.infer<typeof fixingRowSchema>
export const fixingListSchema = z.array(fixingRowSchema)
export const fixingRunSchema = z.object({
  fixed: z.array(z.unknown()).default([]), blocked: z.array(z.unknown()).default([]),
})
export const manualFixingSchema = z.object({
  index: z.string(), fixingDate: z.string().nullish(), date: z.string().nullish(), rate: decimal, status: z.string().nullish(),
  reason: z.string().nullish(), source: z.string().nullish(), proposedBy: z.string().nullish(),
})

export const qualitySchema = z.object({
  suspicious: z.array(z.object({
    index: z.string(), date: z.string(), value: decimal, previous: decimal.nullish(), jumpBp: decimal.nullish(),
    staleDays: z.number().nullish(), reason: z.string().nullish(),
  })).default([]),
  missing: z.array(z.object({ index: z.string(), date: z.string(), dealCount: z.number().nullish() })).default([]),
})
export const factorRowSchema = z.object({
  index: z.string(), date: z.string(), value: decimal, source: z.string().nullish(),
})
export const factorListSchema = z.array(factorRowSchema)
export const rateSheetSchema = z.object({
  name: z.string(), effectiveFrom: z.string().nullish(), status: z.string().nullish(), publishedBy: z.string().nullish(),
  rates: z.array(z.object({ label: z.string(), value: decimal })).nullish(),
}).loose()
export const rateSheetListSchema = z.array(rateSheetSchema)

export const PAYMENT_STATUSES = ['PROPOSED', 'RELEASED', 'SENT', 'SETTLED', 'FAILED', 'CANCELLED'] as const
export const paymentSchema = z.object({
  paymentId: z.string(), status: z.string(), direction: z.string(), counterpartyId: z.string(),
  counterpartyName: z.string().nullish(), currency: z.string(), amount: decimal, valueDate: iso, rail: z.string().nullish(),
  ssi: z.union([z.string(), z.record(z.string(), z.unknown())]).nullish(),
  nettedFlowIds: z.array(z.string()).nullish(), dealIds: z.array(z.string()).nullish(),
  idempotencyKey: z.string().nullish(), railReference: z.string().nullish(), releasedBy: z.string().nullish(),
  createdBy: z.string().nullish(), createdAt: z.string().nullish(),
})
export type Payment = z.infer<typeof paymentSchema>
export const paymentListSchema = z.array(paymentSchema)

export const DAY_STEPS = ['FIXINGS', 'ACCRUALS', 'PAYMENTS', 'REVALUATION', 'CORRECTIONS', 'MATURITIES'] as const
export const dayCompletionSchema = z.object({
  date: z.string(),
  steps: z.array(z.object({
    step: z.string(), status: z.string(), count: z.number().nullish(), detail: z.string().nullish(), at: z.string().nullish(),
  })),
})

/** Cash-flow analysis result (8.4): tolerant of a flat list or per-currency groups. */
export type AnalysisFlow = { date: string; currency: string; amount: number; cumulative: number | null; dealId: string | null; legIndex: number | null; kind: string | null }
export function normaliseAnalysis(raw: unknown): { currencies: { currency: string; flows: AnalysisFlow[] }[] } | null {
  const num = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : typeof v === 'string' && v.trim() !== '' && Number.isFinite(Number(v)) ? Number(v) : null)
  const str = (v: unknown): string | null => (typeof v === 'string' && v ? v : null)
  const flow = (o: unknown, currency?: string): AnalysisFlow | null => {
    if (!o || typeof o !== 'object') return null
    const r = o as Record<string, unknown>
    const date = str(r.date) ?? str(r.paymentDate) ?? str(r.bucket)
    const amount = num(r.amount) ?? num(r.net)
    const ccy = str(r.currency) ?? currency
    if (!date || amount === null || !ccy) return null
    return { date, currency: ccy, amount, cumulative: num(r.cumulative), dealId: str(r.dealId), legIndex: num(r.legIndex), kind: str(r.kind) }
  }
  const group = (flows: AnalysisFlow[]) => {
    const map = new Map<string, AnalysisFlow[]>()
    for (const f of flows) map.set(f.currency, [...(map.get(f.currency) ?? []), f])
    return { currencies: [...map.entries()].map(([currency, list]) => ({ currency, flows: list })) }
  }
  if (Array.isArray(raw)) return group(raw.map(o => flow(o)).filter((f): f is AnalysisFlow => f !== null))
  if (!raw || typeof raw !== 'object') return null
  const r = raw as Record<string, unknown>
  if (Array.isArray(r.flows)) return group(r.flows.map(o => flow(o)).filter((f): f is AnalysisFlow => f !== null))
  const byCcy = r.currencies ?? r.byCurrency
  if (Array.isArray(byCcy)) {
    return group(byCcy.flatMap(c => {
      const o = (c ?? {}) as Record<string, unknown>
      const ccy = str(o.currency) ?? undefined
      return Array.isArray(o.flows) ? o.flows.map(f => flow(f, ccy)).filter((f): f is AnalysisFlow => f !== null) : []
    }))
  }
  return null
}

// ── risk-engine reports ─────────────────────────────────────────────────────────────────────────
export const provenanceSchema = z.string()
const header = z.object({
  runId: z.string().nullish(), asOf: z.string(), curveSetId: z.string().nullish(), bookId: z.string().nullish(),
  currency: z.string().nullish(), provenance: provenanceSchema, valuationInputHash: z.string().nullish(), modelVersion: z.string().nullish(),
})
const dv01 = z.object({ bucket: z.string(), dv01: decimal })
export const valuationSchema = z.object({
  header, deals: z.array(z.object({
    dealId: z.string(), pv: decimal.nullish(), accrued: decimal.nullish(), clean: decimal.nullish(), dirty: decimal.nullish(),
    bookId: z.string().nullish(), flowSource: z.string().nullish(), unpriced: z.string().nullish(),
  }).loose()).default([]),
  book: z.object({ pv: decimal, accrued: decimal, clean: decimal, dirty: decimal }).nullish(),
  dataGap: z.string().nullish(),
})
export const sensitivitySchema = z.object({
  header, bucketSetId: z.string().nullish(), pv01: decimal.nullish(), dv01Buckets: z.array(dv01).default([]),
  fxDelta: z.array(z.object({ currency: z.string(), delta: decimal.nullish() }).loose()).default([]), dataGap: z.string().nullish(),
})
export const varSchema = z.object({
  header, confidence: decimal, horizonDays: z.number(), windowFrom: z.string().nullish(), windowTo: z.string().nullish(),
  scenarioCount: z.number(), skippedScenarios: z.number().nullish(), minimumScenarios: z.number().nullish(),
  valueAtRisk: decimal.nullish(), expectedShortfall: decimal.nullish(), dataGap: z.string().nullish(),
})
export const fxPositionSchema = z.object({
  header, currencies: z.array(z.object({
    currency: z.string(), position: decimal, positionReporting: decimal.nullish(), fxRate: decimal.nullish(), fxDelta: decimal.nullish(),
  })).default([]), dataGap: z.string().nullish(),
})
export const snapshotListSchema = z.object({
  runs: z.array(z.object({ id: z.string(), asOf: z.string(), provenance: z.string(), status: z.string() }).loose()),
})
export const irrbbSchema = z.unknown()
