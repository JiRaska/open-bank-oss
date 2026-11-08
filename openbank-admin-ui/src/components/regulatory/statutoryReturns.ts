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
  // ADR-0367: the regulator's answer and the figures, optional so an older backend still parses.
  datapoints: z.record(z.string(), z.number()).optional(),
  acknowledgement: z.string().nullable().optional(),
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
  if (!breaches.ok) return breaches
  return { ok: true as const, data: { capability: capability.data, returns: returns.data, breaches: breaches.data } }
}

/** ČNB CEÚ daily change overview (ADR-0367, #15292): the working-day feed in tax-reporting-service. */
export const CEU_RETURN_CODE = 'CEUSIFE10'

export type CeuFiling = {
  id: string
  window: string
  revision: number
  status: string
  changes: number | null
  syntheticAccountsDropped: number | null
  syntheticRelationsDropped: number | null
  initialLoad: boolean
  submissionReference: string | null
  acknowledgement: string | null
}

/**
 * The CEÚ filings, newest window first, latest revision per window. Figures come from the
 * backend's datapoints verbatim — never re-derived here (ADR-0367 D2: no regulatory arithmetic
 * in the BFF or the browser). A missing datapoint stays null rather than becoming a zero.
 */
export function ceuFilings(returns: StatutoryReturn[]): CeuFiling[] {
  const latest = new Map<string, StatutoryReturn>()
  for (const item of returns) {
    if (item.returnCode !== CEU_RETURN_CODE) continue
    const seen = latest.get(item.period)
    if (!seen || item.revision > seen.revision) latest.set(item.period, item)
  }
  const figure = (item: StatutoryReturn, key: string) => item.datapoints?.[key] ?? null
  return [...latest.values()]
    .sort((a, b) => b.period.localeCompare(a.period))
    .map(item => ({
      id: item.id,
      window: item.period,
      revision: item.revision,
      status: item.status,
      changes: figure(item, 'changes_total'),
      syntheticAccountsDropped: figure(item, 'synthetic_accounts_dropped'),
      syntheticRelationsDropped: figure(item, 'synthetic_relations_dropped'),
      initialLoad: figure(item, 'initial_load') === 1,
      submissionReference: item.submissionReference,
      acknowledgement: item.acknowledgement ?? null,
    }))
}
