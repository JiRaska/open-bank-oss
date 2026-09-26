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
export type Positions = z.infer<typeof positionsSchema>

/** The service's own error codes (ExceptionMappers.kt). */
export type TreasuryErrorCode = 'FOUR_EYES_VIOLATION' | 'LIMIT_BREACHED' | 'ACTOR_NOT_PERMITTED' | 'INVALID_STATE' | 'NOT_FOUND'
