// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Transport for the ADR-0344 treasury workbench. Same BFF relay as ../api.ts (the signed-in
// person's own bearer, Idempotency-Key on every write) with three additions the workbench needs:
//  * a 404/405 on a collection or action endpoint means THIS DEPLOYMENT does not serve it yet
//    (slices land one by one) — reported as `not_available`, so a page renders an explicit state
//    instead of a blank panel or a fabricated figure;
//  * the full refusal envelope (`missingReferences`, `breaches`) survives, so a 422 can be shown
//    against the field it concerns;
//  * `X-Total-Count` is returned with lists.
import type { z } from 'zod'
import { classifyBffFailure, svcUrl } from '@/lib/services/bff'
import type { UnavailableKind } from '@/components/feedback/DataUnavailable'
import { newIdempotencyKey, TREASURY_BASE } from '@/components/treasury/api'

export const RISK_SERVICE = 'risk-engine'
export const RISK_BASE = '/api/v1/risk'

export const wbUrl = (path: string, query?: Record<string, string | undefined>) =>
  svcUrl('treasury-service', `${TREASURY_BASE}${path}`, compact(query))
export const riskUrl = (path: string, query?: Record<string, string | undefined>) =>
  svcUrl(RISK_SERVICE, `${RISK_BASE}${path}`, compact(query))

function compact(q?: Record<string, string | undefined>): Record<string, string> | undefined {
  if (!q) return undefined
  const out: Record<string, string> = {}
  for (const [k, v] of Object.entries(q)) if (v !== undefined && v !== '') out[k] = v
  return Object.keys(out).length ? out : undefined
}

export type Breach = { rule: string; limit: string; actual: string }

/** The service's refusal envelope `{error, message, missingReferences, breaches}`. */
export type Refusal = {
  status: number
  code: string | null
  message: string | null
  missingReferences: string[]
  breaches: Breach[]
}

export type WbRead<T> =
  | { ok: true; data: T; total: number | null }
  | { ok: false; kind: 'not_available' | 'forbidden' | UnavailableKind; status: number }

export type WbWrite<T> =
  | { ok: true; data: T }
  | ({ ok: false; kind: 'refused' | 'forbidden' | 'not_available' | 'unavailable' } & Refusal)

/** `notFound` decides what a 404 means: `unavailable` (default) = the endpoint is not served here;
 * `missing` = the addressed record does not exist (a deal id, a version). */
export async function wbGet<T>(url: string, schema: z.ZodType<T>, opts: { notFound?: 'unavailable' | 'missing' } = {}): Promise<WbRead<T>> {
  try {
    const res = await fetch(url, { cache: 'no-store' })
    if (!res.ok) {
      if (res.status === 401 || res.status === 403) return { ok: false, kind: 'forbidden', status: res.status }
      const kind = await classifyBffFailure(res)
      if ((res.status === 404 || res.status === 405) && kind === 'not_found' && (opts.notFound ?? 'unavailable') === 'unavailable') {
        return { ok: false, kind: 'not_available', status: res.status }
      }
      return { ok: false, kind, status: res.status }
    }
    const parsed = schema.safeParse(await res.json().catch(() => null))
    if (!parsed.success) return { ok: false, kind: 'error', status: res.status }
    const header = res.headers.get('x-total-count')
    const total = header !== null && /^\d+$/.test(header) ? Number(header) : null
    return { ok: true, data: parsed.data, total }
  } catch {
    return { ok: false, kind: 'unreachable', status: 0 }
  }
}

function refusalOf(status: number, payload: unknown): Refusal {
  const p = (payload && typeof payload === 'object' ? payload : {}) as Record<string, unknown>
  const strings = (v: unknown): string[] => (Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : [])
  const breaches = Array.isArray(p.breaches)
    ? p.breaches.flatMap((b): Breach[] => {
        const o = (b ?? {}) as Record<string, unknown>
        return typeof o.rule === 'string' ? [{ rule: o.rule, limit: String(o.limit ?? ''), actual: String(o.actual ?? '') }] : []
      })
    : []
  return {
    status,
    code: typeof p.error === 'string' ? p.error : null,
    message: typeof p.message === 'string' ? p.message : null,
    missingReferences: strings(p.missingReferences),
    breaches,
  }
}

const NO_REFUSAL = (status: number): Refusal => ({ status, code: null, message: null, missingReferences: [], breaches: [] })

export async function wbSend<T>(
  method: 'POST' | 'PUT' | 'DELETE',
  url: string,
  body: unknown,
  schema: z.ZodType<T>,
  idempotencyKey: string = newIdempotencyKey(),
): Promise<WbWrite<T>> {
  try {
    const res = await fetch(url, {
      method,
      headers: { 'content-type': 'application/json', 'idempotency-key': idempotencyKey },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    if (res.ok) {
      const text = await res.text().catch(() => '')
      const json = text ? safeJson(text) : null
      const parsed = schema.safeParse(json)
      return parsed.success ? { ok: true, data: parsed.data } : { ok: false, kind: 'unavailable', ...NO_REFUSAL(res.status) }
    }
    const payload = await res.json().catch(() => null)
    const refusal = refusalOf(res.status, payload)
    if (res.status === 401 || res.status === 403) {
      // ACTOR_NOT_PERMITTED / SENIOR_APPROVAL_REQUIRED are domain 403s with a reason worth showing.
      return { ok: false, kind: refusal.code ? 'refused' : 'forbidden', ...refusal }
    }
    if ((res.status === 404 || res.status === 405) && refusal.code === null) return { ok: false, kind: 'not_available', ...refusal }
    if ([400, 404, 409, 422].includes(res.status)) return { ok: false, kind: 'refused', ...refusal }
    return { ok: false, kind: 'unavailable', ...refusal }
  } catch {
    return { ok: false, kind: 'unavailable', ...NO_REFUSAL(0) }
  }
}

function safeJson(text: string): unknown {
  try { return JSON.parse(text) } catch { return null }
}

export const wbPost = <T>(url: string, body: unknown, schema: z.ZodType<T>, key?: string) => wbSend('POST', url, body, schema, key)

/** Fetch raw text (CSV exports); same availability semantics as {@link wbGet}. */
export async function wbGetText(url: string): Promise<{ ok: true; text: string; contentType: string } | { ok: false; kind: 'not_available' | 'forbidden' | 'error' }> {
  try {
    const res = await fetch(url, { cache: 'no-store' })
    if (res.ok) return { ok: true, text: await res.text(), contentType: res.headers.get('content-type') ?? '' }
    if (res.status === 401 || res.status === 403) return { ok: false, kind: 'forbidden' }
    if (res.status === 404 || res.status === 405) return { ok: false, kind: 'not_available' }
    return { ok: false, kind: 'error' }
  } catch {
    return { ok: false, kind: 'error' }
  }
}
