// SPDX-License-Identifier: Apache-2.0
// Server-side helpers: live CI-published JSON first, bundled file as fallback, with provenance.
// The GitHub/CI token is a server env var and is never sent to the browser.
import { promises as fs } from 'fs'
import path from 'path'
import type { TestEvidenceMeta, TestInventory } from '@/lib/types/test-results'

export const STALE_AFTER_MS = 24 * 3_600_000

export function buildEvidenceMeta(
  source: 'live' | 'bundled', asOf: string | null | undefined, inventory: TestInventory | null,
  now: number = Date.now(), liveError?: string,
): TestEvidenceMeta {
  const t = Date.parse(asOf ?? '')
  // epoch-0 is the collector's "never ran" sentinel, not a real timestamp
  const known = Number.isFinite(t) && t > 0
  const ageMs = known ? Math.max(0, now - t) : null
  return {
    source, asOf: known ? new Date(t).toISOString() : null,
    ageSeconds: ageMs === null ? null : Math.floor(ageMs / 1000),
    stale: ageMs === null || ageMs > STALE_AFTER_MS,
    ...(liveError ? { liveError } : {}),
    inventory,
  }
}

/** Fetch JSON from a CI-published URL. Returns the payload or an error string (never throws). */
export async function fetchLiveJson<T>(urlEnv: string, tokenEnv: string):
  Promise<{ data: T } | { error: string } | null> {
  const url = process.env[urlEnv]
  if (!url) return null
  try {
    const u = new URL(url)
    if (u.protocol !== 'https:') return { error: 'live URL must be https' }
    const token = process.env[tokenEnv]
    const res = await fetch(u, {
      cache: 'no-store', signal: AbortSignal.timeout(8000),
      headers: { Accept: 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    })
    if (!res.ok) return { error: `live source HTTP ${res.status}` }
    return { data: await res.json() as T }
  } catch (e) {
    return { error: e instanceof Error ? e.message : 'live source unreachable' }
  }
}

export async function readInventory(): Promise<TestInventory | null> {
  try {
    const f = process.env.OPENBANK_TEST_INVENTORY ?? path.resolve(process.cwd(), 'test-inventory.json')
    const j = JSON.parse(await fs.readFile(f, 'utf-8')) as TestInventory
    if (typeof j.totalFiles !== 'number' || !j.kotlin) return null
    if (Date.parse(j.collectedAt) <= 0) return null
    return { collectedAt: j.collectedAt, kotlin: { unit: j.kotlin.unit, integration: j.kotlin.integration }, adminUi: j.adminUi, e2e: j.e2e, totalFiles: j.totalFiles }
  } catch { return null }
}
