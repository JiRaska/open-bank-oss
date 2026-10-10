// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Every call goes through the BFF proxy (/api/svc/<service>/…, ADR-0056), which relays the
// signed-in operator's OWN Keycloak token. pension-fund-service therefore records the human at the
// keyboard as maker (calculatedBy, submittedBy) and checker (approvedBy, decidedBy), and its
// four-eyes check compares two real people.
import type { z } from 'zod'
import { svcUrl } from '@/lib/services/bff'
import { getJson, type Loaded } from '@/components/balance-sheet/api'

export const PENSION = 'pension-service'
export const PENSION_FUND = 'pension-fund-service'

export { getJson, type Loaded }

export const pensionUrl = (path: string, query?: Record<string, string>) =>
  svcUrl(PENSION, `/api/v2/pension${path}`, query)

export const fundUrl = (path: string, query?: Record<string, string>) =>
  svcUrl(PENSION_FUND, `/api/v1${path}`, query)

/** A write's outcome. `code` is the service's own refusal (`{error}`), rendered readably. */
export type WriteResult<T> =
  | { ok: true; data: T }
  | { ok: false; status: number; kind: 'refused' | 'forbidden' | 'unavailable'; code: string | null }

/** One key per user intent (one click); a network retry of that click replays instead of acting twice. */
export function newIdempotencyKey(): string {
  return globalThis.crypto.randomUUID()
}

export async function sendJson<T>(
  method: 'POST' | 'PUT',
  url: string,
  body: unknown,
  schema: z.ZodType<T>,
  idempotencyKey: string = newIdempotencyKey(),
): Promise<WriteResult<T>> {
  try {
    const res = await fetch(url, {
      method,
      headers: { 'content-type': 'application/json', 'idempotency-key': idempotencyKey },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    if (res.ok) {
      const parsed = schema.safeParse(await res.json().catch(() => null))
      return parsed.success
        ? { ok: true, data: parsed.data }
        : { ok: false, status: res.status, kind: 'unavailable', code: null }
    }
    const payload = await res.json().catch(() => null) as { error?: unknown } | null
    const code = typeof payload?.error === 'string' ? payload.error : null
    if (res.status === 401 || res.status === 403) return { ok: false, status: res.status, kind: 'forbidden', code: null }
    if (res.status === 400 || res.status === 404 || res.status === 409 || res.status === 422) {
      return { ok: false, status: res.status, kind: 'refused', code }
    }
    return { ok: false, status: res.status, kind: 'unavailable', code: null }
  } catch {
    return { ok: false, status: 0, kind: 'unavailable', code: null }
  }
}
