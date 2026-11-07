// SPDX-License-Identifier: Apache-2.0

import type { z } from 'zod'
import { classifyBffFailure, type BffFailure } from './bff'

/** A validated resource response or a displayable transport/contract failure. */
export type Loaded<T> = { ok: true; data: T } | { ok: false; kind: BffFailure }

/** Fetch a same-origin BFF resource and validate its JSON before exposing it to a feature. */
export async function getJson<T>(url: string, schema: z.ZodType<T>): Promise<Loaded<T>> {
  try {
    const response = await fetch(url, { cache: 'no-store' })
    if (!response.ok) return { ok: false, kind: await classifyBffFailure(response) }
    const parsed = schema.safeParse(await response.json())
    return parsed.success ? { ok: true, data: parsed.data } : { ok: false, kind: 'error' }
  } catch {
    return { ok: false, kind: 'unreachable' }
  }
}
