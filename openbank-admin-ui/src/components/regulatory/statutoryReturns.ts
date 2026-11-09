// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { z } from 'zod'
import { svcUrl } from '@/lib/services/bff'
import { getJson } from '@/components/balance-sheet/api'

const base = '/api/v1/statutory-returns'
const service = 'tax-reporting-service'

export const capabilitySchema = z.object({
  dataSourceAvailable: z.boolean(),
  wireFormatAvailable: z.boolean(),
  note: z.string(),
})

export const returnSchema = z.object({
  id: z.string().uuid(),
  catalogueId: z.string(),
  returnCode: z.string(),
  entityId: z.string(),
  period: z.string(),
  revision: z.number().int(),
  status: z.string(),
  dueDate: z.string(),
  submittedAt: z.string().nullable(),
  submissionReference: z.string().nullable(),
})

export const breachSchema = z.object({
  catalogueId: z.string(),
  returnCode: z.string(),
  entityId: z.string(),
  period: z.string(),
  dueDate: z.string(),
  kind: z.string(),
})

export type ReturnCapability = z.infer<typeof capabilitySchema>
export type StatutoryReturn = z.infer<typeof returnSchema>
export type ReturnBreach = z.infer<typeof breachSchema>

export const statutoryReturnsUrl = (path = '') => svcUrl(service, `${base}${path}`)

export async function loadStatutoryReturns() {
  const [capability, returns, breaches] = await Promise.all([
    getJson(statutoryReturnsUrl('/capability'), capabilitySchema),
    getJson(statutoryReturnsUrl(), z.array(returnSchema)),
    getJson(statutoryReturnsUrl('/breaches'), z.array(breachSchema)),
  ])
  if (!capability.ok) return capability
  if (!returns.ok) return returns
  // Deadline monitoring fails closed on its own (503 until a reporting start is configured,
  // ADR-0336). That must not hide the returns themselves, and it must not read as "no missed
  // deadline" either: null means UNKNOWN, an empty array means none.
  const missed = breaches.ok ? breaches.data : null
  return { ok: true as const, data: { capability: capability.data, returns: returns.data, breaches: missed } }
}
