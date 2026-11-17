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
  // Lifecycle actors, optional so an older backend still parses. Shown so the operator can see
  // who assembled a revision before trying to approve it (maker != checker, enforced server-side).
  assembledBy: z.string().optional(),
  approvedBy: z.string().nullable().optional(),
})

export const returnDefinitionSchema = z.object({
  code: z.string(),
  name: z.string(),
  scope: z.string(),
  periodicity: z.string(),
  legalBasis: z.string(),
  onDemand: z.boolean(),
  filingObligation: z.boolean().optional(),
})

export const catalogueSchema = z.object({
  id: z.string(),
  version: z.number().int(),
  jurisdiction: z.string(),
  wireFormatVerified: z.boolean(),
  returns: z.array(returnDefinitionSchema),
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
export type ReturnCatalogue = z.infer<typeof catalogueSchema>
export type ReturnDefinition = z.infer<typeof returnDefinitionSchema>

/** Deadline monitoring: a list, or UNKNOWN with the backend's own reason (e.g. 503 fund roster not configured). */
export type BreachState = { known: true; items: ReturnBreach[] } | { known: false; reason: string | null }

export const statutoryReturnsUrl = (path = '') => svcUrl(service, `${base}${path}`)

export async function loadStatutoryReturns() {
  const [capability, returns, catalogues, breaches] = await Promise.all([
    getJson(statutoryReturnsUrl('/capability'), capabilitySchema),
    getJson(statutoryReturnsUrl(), z.array(returnSchema)),
    getJson(statutoryReturnsUrl('/catalogues'), z.array(catalogueSchema)),
    loadBreaches(),
  ])
  if (!capability.ok) return capability
  if (!returns.ok) return returns
  return {
    ok: true as const,
    data: {
      capability: capability.data,
      returns: returns.data,
      // A catalogue failure must not hide built revisions; null renders as "catalogue unavailable".
      catalogues: catalogues.ok ? catalogues.data : null,
      breaches,
    },
  }
}

/**
 * Deadline monitoring fails closed on its own (503 until a reporting start and fund roster are
 * configured, ADR-0336). That must not hide the returns themselves, and it must not read as "no
 * missed deadline" either: `known: false` means UNKNOWN, an empty list means none.
 */
async function loadBreaches(): Promise<BreachState> {
  try {
    const res = await fetch(statutoryReturnsUrl('/breaches'), { cache: 'no-store' })
    if (!res.ok) return { known: false, reason: await errorText(res) }
    const parsed = z.array(breachSchema).safeParse(await res.json())
    return parsed.success ? { known: true, items: parsed.data } : { known: false, reason: null }
  } catch {
    return { known: false, reason: null }
  }
}

async function errorText(res: Response): Promise<string | null> {
  const payload = await res.json().catch(() => null) as { error?: unknown; message?: unknown } | null
  if (typeof payload?.error === 'string') return payload.error
  if (typeof payload?.message === 'string') return payload.message
  return null
}

/** Latest revision for each entity, grouped by catalogue and return code. */
export function latestByCode(returns: StatutoryReturn[]): Map<string, StatutoryReturn[]> {
  const latest = new Map<string, StatutoryReturn>()
  for (const item of returns) {
    const key = JSON.stringify([item.catalogueId, item.returnCode, item.entityId])
    const seen = latest.get(key)
    if (!seen || item.period > seen.period || (item.period === seen.period && item.revision > seen.revision)) {
      latest.set(key, item)
    }
  }
  const grouped = new Map<string, StatutoryReturn[]>()
  for (const item of latest.values()) {
    const key = `${item.catalogueId}/${item.returnCode}`
    grouped.set(key, [...(grouped.get(key) ?? []), item])
  }
  return grouped
}

export const GSFT_CATALOGUE = 'cz-gsft'

/**
 * A lifecycle write's outcome. `status` and the backend's own `message` are kept verbatim so a
 * 409 (four-eyes, wrong state), 422 (validation) or 503 (no data source / no GSFT key) is shown
 * as what it is, never as a generic failure.
 */
export type ActionResult =
  | { ok: true; data: StatutoryReturn; package?: { fileName: string; encryptedPackage: string } }
  | { ok: false; status: number; message: string | null }

const deliverySchema = z.object({ statutoryReturn: returnSchema, fileName: z.string(), encryptedPackage: z.string() })

export type ReturnAction =
  | { kind: 'assemble'; body: { catalogueId: string; returnCode: string; entityId: string; period: string } }
  | { kind: 'approve'; id: string }
  | { kind: 'submitted'; id: string; reference: string }
  | { kind: 'gsft-delivery'; id: string; reference: string }
  | { kind: 'acknowledgement'; id: string; accepted: boolean; text: string }

export function actionRequest(action: ReturnAction): { url: string; body: unknown } {
  switch (action.kind) {
    case 'assemble': return { url: statutoryReturnsUrl('/assemble'), body: action.body }
    case 'approve': return { url: statutoryReturnsUrl(`/${action.id}/approve`), body: undefined }
    case 'submitted': return { url: statutoryReturnsUrl(`/${action.id}/submitted`), body: { reference: action.reference } }
    case 'gsft-delivery': return { url: statutoryReturnsUrl(`/${action.id}/gsft-delivery`), body: { reference: action.reference } }
    case 'acknowledgement': return {
      url: statutoryReturnsUrl(`/${action.id}/acknowledgement`),
      body: action.accepted ? { accepted: true, reference: action.text } : { accepted: false, reason: action.text },
    }
  }
}

export async function runReturnAction(action: ReturnAction): Promise<ActionResult> {
  const { url, body } = actionRequest(action)
  try {
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    if (!res.ok) return { ok: false, status: res.status, message: await errorText(res) }
    const json = await res.json().catch(() => null)
    if (action.kind === 'gsft-delivery') {
      // The encrypted archive is the artefact the operator hands to GSFT; keep it, never drop it.
      const delivery = deliverySchema.safeParse(json).data
      return delivery
        ? { ok: true, data: delivery.statutoryReturn, package: { fileName: delivery.fileName, encryptedPackage: delivery.encryptedPackage } }
        : { ok: false, status: res.status, message: null }
    }
    const parsed = returnSchema.safeParse(json).data
    return parsed ? { ok: true, data: parsed } : { ok: false, status: res.status, message: null }
  } catch {
    return { ok: false, status: 0, message: null }
  }
}

/** ČNB CEÚ daily change overview (ADR-0367, #15292): the working-day feed in tax-reporting-service. */
export const CEU_RETURN_CODE = 'CEUSIFE10'

export type CeuFiling = {
  id: string
  entityId: string
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
 * The CEÚ filings, newest window first, latest revision per entity and window. Figures come from the
 * backend's datapoints verbatim — never re-derived here (ADR-0367 D2: no regulatory arithmetic
 * in the BFF or the browser). A missing datapoint stays null rather than becoming a zero.
 */
export function ceuFilings(returns: StatutoryReturn[]): CeuFiling[] {
  const latest = new Map<string, StatutoryReturn>()
  for (const item of returns) {
    if (item.catalogueId !== 'cz-ceu' || item.returnCode !== CEU_RETURN_CODE) continue
    const key = JSON.stringify([item.entityId, item.period])
    const seen = latest.get(key)
    if (!seen || item.revision > seen.revision) latest.set(key, item)
  }
  const figure = (item: StatutoryReturn, key: string) => item.datapoints?.[key] ?? null
  return [...latest.values()]
    .sort((a, b) => b.period.localeCompare(a.period))
    .map(item => ({
      id: item.id,
      entityId: item.entityId,
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
