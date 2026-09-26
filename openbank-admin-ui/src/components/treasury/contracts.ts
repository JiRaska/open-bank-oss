// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Wire contract of openbank-treasury-service (ADR-0315, openapi.yaml + TreasuryDtos.kt). A response
// that does not parse is treated as unavailable, never half-rendered.
import { z } from 'zod'

/** BigDecimal: Jackson writes a JSON number, but a plain-string configuration must not break the page. */
const decimal = z.union([z.number(), z.string()]).transform(Number).pipe(z.number().finite())
const timestamp = z.string().min(1)

export const PRODUCTS = ['MM_PLACEMENT', 'MM_BORROWING', 'CNB_DEPOSIT_FACILITY', 'CNB_LOMBARD', 'FX_SPOT'] as const
export const DEAL_STATES = ['DRAFT', 'PENDING_APPROVAL', 'BOOKED', 'SETTLED', 'MATURED', 'CANCELLED', 'REVERSED'] as const
export const CURRENCIES = ['CZK', 'EUR'] as const
export const FX_SIDES = ['BUY', 'SELL'] as const
/** The central bank's counterparty id (Deal.CNB_COUNTERPARTY_ID). */
export const CNB_COUNTERPARTY_ID = 'CNB'

/** Closed set: what the FORM may submit as `product` (openapi.yaml DraftDealRequest.product, a
 * closed enum on the request even though the response's ProductType is x-extensible). */
export const productSchema = z.enum(PRODUCTS)
// Draft creation offers only products this UI knows how to book. The response contract is
// extensible: a newer backend product must not make the entire deal list unavailable.
export const responseProductSchema = z.string().min(1)
export const dealStateSchema = z.enum(DEAL_STATES)
export const fxSideSchema = z.enum(FX_SIDES)

/**
 * `Deal.product` (openapi.yaml `ProductType`) is declared `x-extensible-enum` (1.2.0, #10896): the
 * product set grows server-side, and a client MUST tolerate a value it does not recognise — a
 * closed zod enum here would make an unrelated server release fail every deal list/detail render.
 * Rendering falls back to the raw string (`productLabel`'s `default` branch).
 */
export const dealProductSchema = z.string().min(1)

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

/** `Deal.fx` (openapi.yaml `FxTerms`) — FX_SPOT only; null for money-market deals (#10896). */
export const fxTermsSchema = z.object({
  side: fxSideSchema,
  buyCurrency: z.string(),
  buyAmount: decimal,
  sellCurrency: z.string(),
  sellAmount: decimal,
  dealRate: decimal,
  midRate: decimal.nullable(),
  rateFlag: z.string().nullable(),
})

export const dealSchema = z.object({
  dealId: z.string(),
  // ProductType is extensible on responses; unknown future products remain renderable.
  product: dealProductSchema,
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
  fx: fxTermsSchema.nullable().default(null),
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
/** The deal's product as rendered: any non-empty string, since ProductType is x-extensible. */
export type DealProduct = z.infer<typeof dealProductSchema>
export type FxSide = z.infer<typeof fxSideSchema>
export type FxTerms = z.infer<typeof fxTermsSchema>
export type DealState = z.infer<typeof dealStateSchema>
export type Deal = z.infer<typeof dealSchema>
export type Counterparty = z.infer<typeof counterpartySchema>
export type LimitUtilisationEntry = z.infer<typeof limitUtilisationEntrySchema>
export type Positions = z.infer<typeof positionsSchema>

/** The service's own error codes (ExceptionMappers.kt). */
export type TreasuryErrorCode = 'FOUR_EYES_VIOLATION' | 'LIMIT_BREACHED' | 'ACTOR_NOT_PERMITTED' | 'INVALID_STATE' | 'NOT_FOUND'
