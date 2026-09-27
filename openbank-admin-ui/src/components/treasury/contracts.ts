// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Wire contract of openbank-treasury-service (ADR-0315, openapi.yaml + TreasuryDtos.kt). A response
// that does not parse is treated as unavailable, never half-rendered.
import { z } from 'zod'

/** BigDecimal: Jackson writes a JSON number, but a plain-string configuration must not break the page. */
const decimal = z.union([z.number(), z.string()]).transform(Number).pipe(z.number().finite())
const timestamp = z.string().min(1)

export const PRODUCTS = ['MM_PLACEMENT', 'MM_BORROWING', 'CNB_DEPOSIT_FACILITY'] as const
export const DEAL_STATES = ['DRAFT', 'PENDING_APPROVAL', 'BOOKED', 'SETTLED', 'MATURED', 'CANCELLED', 'REVERSED'] as const
export const CURRENCIES = ['CZK', 'EUR'] as const
/** The central bank's counterparty id (Deal.CNB_COUNTERPARTY_ID). */
export const CNB_COUNTERPARTY_ID = 'CNB'

export const productSchema = z.enum(PRODUCTS)
export const dealStateSchema = z.enum(DEAL_STATES)

export const limitCheckSchema = z.object({
  currency: z.string(), limit: decimal, exposureBefore: decimal, exposureAfter: decimal,
  headroomAfter: decimal, breached: z.boolean(),
})

export const transitionSchema = z.object({
  from: dealStateSchema.nullable(), to: dealStateSchema, actor: z.string(), actorType: z.string(),
  at: timestamp, note: z.string().nullable(),
})

export const journalRefSchema = z.object({
  event: z.string(), idempotencyKey: z.string(), journalId: z.string(), postedAt: timestamp,
})

/** A senior approver's recorded override of a limit breach (ADR-0315 D4). */
export const limitOverrideSchema = z.object({
  by: z.string(), reason: z.string(), at: timestamp, coversExposureUpTo: decimal, limitAtOverride: decimal,
})

export const dealSchema = z.object({
  dealId: z.string(),
  product: productSchema,
  counterpartyId: z.string(),
  currency: z.string(),
  principal: decimal,
  rate: decimal,
  dayCount: z.string(),
  days: z.number().int(),
  interest: decimal,
  tradeDate: z.iso.date(),
  valueDate: z.iso.date(),
  maturityDate: z.iso.date(),
  state: dealStateSchema,
  createdBy: z.string(),
  createdByType: z.string(),
  submittedBy: z.string().nullable(),
  approvedBy: z.string().nullable(),
  rationale: z.string().nullable(),
  limitCheck: limitCheckSchema.nullable(),
  // Optional too, not just nullable: older fixtures/mocks in this test suite predate the field,
  // and a response that omits it (rather than sending null) must not fail parsing (unavailable).
  limitOverride: limitOverrideSchema.nullable().optional(),
  createdAt: timestamp,
  updatedAt: timestamp,
  history: z.array(transitionSchema),
  journals: z.array(journalRefSchema),
})
export const dealListSchema = z.array(dealSchema)

export const counterpartySchema = z.object({
  counterpartyId: z.string(), name: z.string(), kind: z.enum(['BANK', 'CENTRAL_BANK']), synthetic: z.boolean(),
  currency: z.string(), limit: decimal, exposure: decimal, headroom: decimal,
})
export const counterpartyListSchema = z.array(counterpartySchema)

export const limitUtilisationEntrySchema = z.object({
  counterpartyId: z.string(), name: z.string(), synthetic: z.boolean(), currency: z.string(),
  limit: decimal, utilised: decimal, available: decimal, utilisationPercent: decimal,
  breached: z.boolean(), activeOverrides: z.number().int(),
})
export const limitUtilisationSchema = z.object({ limits: z.array(limitUtilisationEntrySchema) })

export const positionsSchema = z.object({
  asOf: z.iso.date(),
  positions: z.array(z.object({
    currency: z.string(), placed: decimal, borrowed: decimal, atCnb: decimal, net: decimal,
  })),
})

export type Product = z.infer<typeof productSchema>
export type DealState = z.infer<typeof dealStateSchema>
export type Deal = z.infer<typeof dealSchema>
export type Counterparty = z.infer<typeof counterpartySchema>
export type LimitUtilisationEntry = z.infer<typeof limitUtilisationEntrySchema>
export type Positions = z.infer<typeof positionsSchema>

/** The service's own error codes (ExceptionMappers.kt). */
export type TreasuryErrorCode = 'FOUR_EYES_VIOLATION' | 'LIMIT_BREACHED' | 'ACTOR_NOT_PERMITTED' | 'INVALID_STATE' | 'NOT_FOUND'

// Nostro reconciliation (ADR-0315 D7, #10896): NostroResource + the NostroStatement* /
// NostroReconciliation schemas in openapi.yaml (1.4.0). The ledger balances, the differences and
// `reconciled` are nullable there: a null is not the same claim as a 0 (or a false), and the page
// must never render one as the other.
export const nostroStatementSchema = z.object({
  id: z.string(),
  statementId: z.string(),
  iban: z.string(),
  glCode: z.string(),
  currency: z.string(),
  statementDate: z.iso.date(),
  openingBalance: decimal,
  closingBalance: decimal,
  entryCount: z.number().int(),
  sha256: z.string(),
  uploadedBy: z.string(),
  uploadedAt: timestamp,
})

export const matchTypeSchema = z.enum(['EXACT', 'AMOUNT_DATE'])
export const statementDirectionSchema = z.enum(['CRDT', 'DBIT'])
export const ledgerSideSchema = z.enum(['DEBIT', 'CREDIT'])

export const nostroStatementEntrySchema = z.object({
  sequence: z.number().int(),
  amount: decimal,
  currency: z.string(),
  direction: statementDirectionSchema,
  bookingDate: z.iso.date(),
  reference: z.string().nullable(),
})

export const nostroLedgerLineSchema = z.object({
  journalId: z.string(),
  lineId: z.string(),
  transactionId: z.string(),
  entryDate: z.iso.date(),
  side: ledgerSideSchema,
  amount: decimal,
  currency: z.string(),
  description: z.string().nullable(),
})

export const nostroMatchSchema = z.object({
  matchType: matchTypeSchema,
  entry: nostroStatementEntrySchema,
  ledgerLine: nostroLedgerLineSchema,
})

export const nostroReconciliationSchema = z.object({
  statementUuid: z.string(),
  statementId: z.string(),
  iban: z.string(),
  glCode: z.string(),
  currency: z.string(),
  statementDate: z.iso.date(),
  statementOpeningBalance: decimal,
  // NULL when the ledger cannot state the balance in the statement currency — it exposes only
  // base-currency (CZK) balances, so a EUR nostro has none; balanceNotStated carries the reason.
  ledgerOpeningBalance: decimal.nullable(),
  openingDifference: decimal.nullable(),
  statementClosingBalance: decimal,
  ledgerClosingBalance: decimal.nullable(),
  closingDifference: decimal.nullable(),
  balanceNotStated: z.string().nullish(),
  // NULL = every item matched but the balances could not be compared: undetermined, not a pass.
  reconciled: z.boolean().nullable(),
  matches: z.array(nostroMatchSchema),
  unmatchedStatementEntries: z.array(nostroStatementEntrySchema),
  unmatchedLedgerLines: z.array(nostroLedgerLineSchema),
})

export type NostroStatement = z.infer<typeof nostroStatementSchema>
export type MatchType = z.infer<typeof matchTypeSchema>
export type NostroStatementEntry = z.infer<typeof nostroStatementEntrySchema>
export type NostroLedgerLine = z.infer<typeof nostroLedgerLineSchema>
export type NostroMatch = z.infer<typeof nostroMatchSchema>
export type NostroReconciliation = z.infer<typeof nostroReconciliationSchema>
