// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Every call goes through the BFF proxy (/api/svc/<k8s-name>/…, ADR-0056), which relays the
// signed-in operator's OWN Keycloak access token as the bearer. risk-engine and lending therefore
// see the human — their @RolesAllowed, OPA and the backfill's maker != checker all evaluate the
// person at the keyboard, never a shared service account (which OPA refuses for every write here).
import type { z } from 'zod'
import { classifyBffFailure, svcUrl } from '@/lib/services/bff'
import type { UnavailableKind } from '@/components/feedback/DataUnavailable'

export const RISK = 'risk-engine'
export const LENDING = 'lending-service'
export const BACKFILL_BASE = '/api/v1/lending/ledger-backfill'

export type Loaded<T> = { ok: true; data: T } | { ok: false; kind: UnavailableKind }

export async function getJson<T>(url: string, schema: z.ZodType<T>): Promise<Loaded<T>> {
  try {
    const res = await fetch(url, { cache: 'no-store' })
    if (!res.ok) return { ok: false, kind: await classifyBffFailure(res) }
    const parsed = schema.safeParse(await res.json())
    return parsed.success ? { ok: true, data: parsed.data } : { ok: false, kind: 'error' }
  } catch {
    return { ok: false, kind: 'unreachable' }
  }
}

/** A write's outcome. `message` is the backend's own refusal text (400/409/422), never a status line. */
export type WriteResult<T> =
  | { ok: true; data: T }
  | { ok: false; status: number; kind: 'refused' | 'forbidden' | 'unavailable'; message: string | null }

export async function sendJson<T>(url: string, body: unknown, schema: z.ZodType<T>): Promise<WriteResult<T>> {
  try {
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    if (res.ok) {
      const parsed = schema.safeParse(await res.json().catch(() => null))
      return parsed.success
        ? { ok: true, data: parsed.data }
        : { ok: false, status: res.status, kind: 'unavailable', message: null }
    }
    const payload = await res.json().catch(() => null) as { error?: unknown } | null
    const message = typeof payload?.error === 'string' ? payload.error : null
    if (res.status === 401 || res.status === 403) return { ok: false, status: res.status, kind: 'forbidden', message: null }
    if (res.status === 400 || res.status === 409 || res.status === 422) {
      return { ok: false, status: res.status, kind: 'refused', message }
    }
    return { ok: false, status: res.status, kind: 'unavailable', message: null }
  } catch {
    return { ok: false, status: 0, kind: 'unavailable', message: null }
  }
}

export const riskUrl = (path: string, query?: Record<string, string>) => svcUrl(RISK, path, query)
export const backfillUrl = (path: string, query?: Record<string, string>) => svcUrl(LENDING, `${BACKFILL_BASE}${path}`, query)
