// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Every call goes through the BFF proxy (/api/svc/treasury-service/…, ADR-0056), which relays the
// signed-in person's OWN Keycloak access token. treasury-service therefore records the human at
// the keyboard as dealer / approver, and its four-eyes check compares two real people — a shared
// service account would be refused outright (ACTOR_NOT_PERMITTED, 403).
import type { z } from 'zod'
import { svcUrl } from '@/lib/services/bff'
import { getJson, type Loaded } from '@/components/balance-sheet/api'

export const TREASURY = 'treasury-service'
export const TREASURY_BASE = '/api/v1/treasury'

export { getJson, type Loaded }

export const treasuryUrl = (path: string, query?: Record<string, string>) =>
  svcUrl(TREASURY, `${TREASURY_BASE}${path}`, query)

/**
 * A write's outcome. `code`/`message` are the service's own refusal (`{error, message}`), which the
 * page renders readably — a 422 FOUR_EYES_VIOLATION or LIMIT_BREACHED must reach the person even
 * when the UI believed the action was allowed.
 */
export type WriteResult<T> =
  | { ok: true; data: T }
  | { ok: false; status: number; kind: 'refused' | 'forbidden' | 'unavailable'; code: string | null; message: string | null }

/** One key per user intent (one click). treasury-service requires it on every POST and replays
 * the deal's current state for a repeated key instead of acting twice. */
export function newIdempotencyKey(): string {
  return globalThis.crypto.randomUUID()
}

/**
 * POST with an `Idempotency-Key` (required by every treasury write, 400 if absent). Pass the key of
 * the intent being retried to keep it stable; by default each call is a new intent. The BFF
 * forwards the header unchanged (FORWARD_HEADERS in /api/svc).
 */
export async function postJson<T>(url: string, body: unknown, schema: z.ZodType<T>, idempotencyKey: string = newIdempotencyKey()): Promise<WriteResult<T>> {
  try {
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'idempotency-key': idempotencyKey },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    if (res.ok) {
      const parsed = schema.safeParse(await res.json().catch(() => null))
      return parsed.success
        ? { ok: true, data: parsed.data }
        : { ok: false, status: res.status, kind: 'unavailable', code: null, message: null }
    }
    const payload = await res.json().catch(() => null) as { error?: unknown; message?: unknown } | null
    const code = typeof payload?.error === 'string' ? payload.error : null
    const message = typeof payload?.message === 'string' ? payload.message : null
    // ACTOR_NOT_PERMITTED is a domain 403 with a reason worth showing; a bare 401/403 is RBAC.
    if (res.status === 403 && code === 'ACTOR_NOT_PERMITTED') return { ok: false, status: 403, kind: 'refused', code, message }
    if (res.status === 401 || res.status === 403) return { ok: false, status: res.status, kind: 'forbidden', code: null, message: null }
    if (res.status === 400 || res.status === 404 || res.status === 409 || res.status === 422) {
      return { ok: false, status: res.status, kind: 'refused', code, message: message ?? (code && code.includes(' ') ? code : null) }
    }
    return { ok: false, status: res.status, kind: 'unavailable', code: null, message: null }
  } catch {
    return { ok: false, status: 0, kind: 'unavailable', code: null, message: null }
  }
}

export type DealAction = 'submit' | 'approve' | 'reject' | 'cancel' | 'settle' | 'mature' | 'reverse'

/** POST /deals/{id}/{action}; reject and reverse carry `{reason}`. */
export const dealActionUrl = (dealId: string, action: DealAction) =>
  treasuryUrl(`/deals/${encodeURIComponent(dealId)}/${action}`)
