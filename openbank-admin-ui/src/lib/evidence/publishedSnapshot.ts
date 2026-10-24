// SPDX-License-Identifier: Apache-2.0
// Validated, content-addressed runtime evidence independent of application images.
import { createHash } from 'node:crypto'

export interface EvidenceManifest {
  schemaVersion: 1
  snapshot: string
  sha256: string
  sourceSha: string
  runId: number
  runAttempt: number
  collectedAt: string
  expiresAt: string
  complete: true
}

export interface EvidenceProvenance {
  sourceSha: string
  runId: number
  runAttempt: number
  snapshot: string
  sha256: string
  collectedAt: string
  expiresAt: string
  pointerExpired: boolean
  snapshotAgeMs: number
}

export type EvidenceSnapshotResult<T> =
  | { status: 'ready' | 'stale'; snapshot: T; provenance: EvidenceProvenance; error?: string }
  | { status: 'unavailable' | 'disabled'; snapshot: null; provenance: null; error?: string }

export interface EvidenceSnapshotReaderOptions<T> {
  baseUrl?: string | (() => string | undefined)
  validateSnapshot: (value: unknown, nowMs: number) => value is T
  collectedAt: (snapshot: T) => string
  fetchImpl?: typeof fetch
  now?: () => number
  ttlMs?: number
  timeoutMs?: number
  maxBytes?: number
  maxDataAgeMs?: number
}

const SHA256 = /^[0-9a-f]{64}$/
const SOURCE_SHA = /^[0-9a-f]{40}$/
const SNAPSHOT_KEY = /^snapshots\/[0-9]+-[1-9][0-9]*-[0-9a-f]{64}\.json$/
const MAX_BYTES = 2 * 1024 * 1024
const MAX_DATA_AGE_MS = 2 * 60 * 60 * 1000
const CLOCK_SKEW_MS = 5 * 60 * 1000

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function finiteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

function iso(value: unknown): value is string {
  return typeof value === 'string'
    && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?(?:Z|[+-]\d\d:\d\d)$/.test(value)
    && Number.isFinite(Date.parse(value))
}

export function validateEvidenceManifest(value: unknown, nowMs = Date.now()): value is EvidenceManifest {
  if (!isRecord(value)) return false
  const valid = value.schemaVersion === 1
    && typeof value.snapshot === 'string' && SNAPSHOT_KEY.test(value.snapshot)
    && typeof value.sha256 === 'string' && SHA256.test(value.sha256)
    && typeof value.sourceSha === 'string' && SOURCE_SHA.test(value.sourceSha)
    && finiteNumber(value.runId) && Number.isSafeInteger(value.runId) && value.runId > 0
    && finiteNumber(value.runAttempt) && Number.isSafeInteger(value.runAttempt) && value.runAttempt > 0
    && iso(value.collectedAt) && iso(value.expiresAt)
    && Date.parse(value.collectedAt) <= nowMs + CLOCK_SKEW_MS
    && Date.parse(value.expiresAt) > Date.parse(value.collectedAt)
    && value.complete === true
  return valid && value.snapshot === `snapshots/${value.runId}-${value.runAttempt}-${value.sha256}.json`
}

export function evidenceStoreBaseUrl(raw: string | undefined): string | null {
  if (!raw) return null
  try {
    const url = new URL(raw)
    if (url.protocol !== 'https:' || url.search || url.hash || url.username || url.password) return null
    return url.toString().replace(/\/+$/, '')
  } catch { return null }
}

async function readBoundedBody(response: Response, maxBytes: number): Promise<Uint8Array> {
  const declaredLength = Number(response.headers.get('content-length'))
  if (Number.isFinite(declaredLength) && declaredLength > maxBytes) throw new Error('snapshot exceeds size limit')
  if (!response.body) {
    const bytes = new Uint8Array(await response.arrayBuffer())
    if (bytes.byteLength > maxBytes) throw new Error('snapshot exceeds size limit')
    return bytes
  }
  const reader = response.body.getReader()
  const chunks: Uint8Array[] = []
  let size = 0
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      size += value.byteLength
      if (size > maxBytes) {
        await reader.cancel()
        throw new Error('snapshot exceeds size limit')
      }
      chunks.push(value)
    }
  } finally { reader.releaseLock() }
  const bytes = new Uint8Array(size)
  let offset = 0
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength }
  return bytes
}

function validSnapshotUrl(base: string, key: string): string {
  if (!SNAPSHOT_KEY.test(key)) throw new Error('invalid snapshot key')
  const url = new URL(`${base}/${key}`)
  const origin = new URL(base)
  if (url.origin !== origin.origin || !url.pathname.startsWith(`${origin.pathname.replace(/\/$/, '')}/snapshots/`)) throw new Error('snapshot URL escaped store base')
  return url.toString()
}

/** JSON-derived evidence is shared between readers; callers cannot alter verified bytes' meaning. */
function freezeJson(value: unknown): void {
  const pending: object[] = value !== null && typeof value === 'object' ? [value] : []
  while (pending.length) {
    const item = pending.pop()!
    for (const child of Object.values(item)) {
      if (child !== null && typeof child === 'object') pending.push(child)
    }
    Object.freeze(item)
  }
}

function pointerIdentity(pointer: EvidenceManifest): string {
  return JSON.stringify([pointer.schemaVersion, pointer.snapshot, pointer.sha256, pointer.sourceSha,
    pointer.runId, pointer.runAttempt, pointer.collectedAt, pointer.expiresAt, pointer.complete])
}

export function createEvidenceSnapshotReader<T>(options: EvidenceSnapshotReaderOptions<T>) {
  const now = options.now ?? Date.now
  const fetchImpl = options.fetchImpl ?? ((...args: Parameters<typeof fetch>) => fetch(...args))
  const ttlMs = options.ttlMs ?? 30_000
  const timeoutMs = options.timeoutMs ?? 3_000
  const maxBytes = options.maxBytes ?? MAX_BYTES
  const maxDataAgeMs = options.maxDataAgeMs ?? MAX_DATA_AGE_MS
  let current: EvidenceSnapshotResult<T> | null = null
  let verifiedIdentity: string | null = null
  let currentBase: string | null = null
  let generation = 0
  let nextRefreshAt = 0
  let inflight: Promise<EvidenceSnapshotResult<T>> | null = null

  const currentState = (value = current): EvidenceSnapshotResult<T> | null => {
    if (!value) return null
    if (value.snapshot === null || value.provenance === null) return { ...value }
    const pointerExpired = Date.parse(value.provenance.expiresAt) <= now()
    const snapshotAgeMs = Math.max(0, now() - Date.parse(value.provenance.collectedAt))
    const provenance = { ...value.provenance, pointerExpired, snapshotAgeMs }
    return { ...value, status: pointerExpired || snapshotAgeMs > maxDataAgeMs || Boolean(value.error) ? 'stale' : 'ready', provenance }
  }

  async function refresh(base: string, epoch: number): Promise<EvidenceSnapshotResult<T>> {
    const previous = current
    const previousIdentity = verifiedIdentity
    const signal = AbortSignal.timeout(timeoutMs)
    const request = (url: string) => fetchImpl(url, {
      method: 'GET', headers: { Accept: 'application/json' }, cache: 'no-store',
      credentials: 'omit', redirect: 'error', signal,
    })
    try {
      const pointerResponse = await request(`${base}/current.json`)
      if (!pointerResponse.ok) throw new Error(`manifest HTTP ${pointerResponse.status}`)
      const pointerBytes = await readBoundedBody(pointerResponse, 16 * 1024)
      let pointer: unknown
      try { pointer = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(pointerBytes)) } catch { throw new Error('malformed manifest') }
      if (!validateEvidenceManifest(pointer, now())) throw new Error('invalid manifest schema')

      const identity = pointerIdentity(pointer)
      let data: unknown
      if (previous?.snapshot !== null && previous?.snapshot !== undefined && identity === previousIdentity) {
        data = previous.snapshot
      } else {
        const snapshotResponse = await request(validSnapshotUrl(base, pointer.snapshot))
        if (!snapshotResponse.ok) throw new Error(`snapshot HTTP ${snapshotResponse.status}`)
        const bytes = await readBoundedBody(snapshotResponse, maxBytes)
        const digest = createHash('sha256').update(bytes).digest('hex')
        if (digest !== pointer.sha256) throw new Error('snapshot digest mismatch')
        let parsed: unknown
        try { parsed = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)) } catch { throw new Error('malformed snapshot') }
        freezeJson(parsed)
        data = parsed
      }
      // Time-sensitive validity is checked on every pointer poll, including body reuse.
      if (!options.validateSnapshot(data, now())) throw new Error('invalid snapshot schema')
      if (isRecord(data) && Object.hasOwn(data, 'sourceSha') && data.sourceSha !== pointer.sourceSha) throw new Error('snapshot source does not match manifest')
      if (options.collectedAt(data) !== pointer.collectedAt) throw new Error('snapshot collection time does not match manifest')

      const pointerExpired = Date.parse(pointer.expiresAt) <= now()
      const snapshotAgeMs = Math.max(0, now() - Date.parse(options.collectedAt(data)))
      const provenance: EvidenceProvenance = {
        sourceSha: pointer.sourceSha, runId: pointer.runId, runAttempt: pointer.runAttempt,
        snapshot: pointer.snapshot, sha256: pointer.sha256, collectedAt: pointer.collectedAt,
        expiresAt: pointer.expiresAt, pointerExpired, snapshotAgeMs,
      }
      const result: EvidenceSnapshotResult<T> = { status: pointerExpired || snapshotAgeMs > maxDataAgeMs ? 'stale' : 'ready', snapshot: data, provenance }
      if (epoch === generation) {
        current = result
        verifiedIdentity = identity
        nextRefreshAt = now() + ttlMs
      }
      return currentState(result)!
    } catch (error) {
      if (epoch === generation) nextRefreshAt = now() + ttlMs
      const lastGood = currentState(previous)
      if (lastGood?.snapshot && lastGood.provenance) {
        const result: EvidenceSnapshotResult<T> = { ...lastGood, status: 'stale', error: error instanceof Error ? error.message : 'evidence store unavailable' }
        if (epoch === generation) current = result
        return currentState(result)!
      }
      const result: EvidenceSnapshotResult<T> = { status: 'unavailable', snapshot: null, provenance: null, error: error instanceof Error ? error.message : 'evidence store unavailable' }
      if (epoch === generation) current = result
      return { ...result }
    }
  }

  return {
    async read(): Promise<EvidenceSnapshotResult<T>> {
      const rawBase = typeof options.baseUrl === 'function' ? options.baseUrl() : options.baseUrl
      const base = evidenceStoreBaseUrl(rawBase)
      if (currentBase !== base) {
        generation++
        currentBase = base
        current = null
        verifiedIdentity = null
        nextRefreshAt = 0
        inflight = null
      }
      if (rawBase === undefined) return { status: 'disabled', snapshot: null, provenance: null }
      if (!base) return { status: 'unavailable', snapshot: null, provenance: null, error: 'invalid evidence store URL' }
      const existing = currentState()
      if (existing && now() < nextRefreshAt) return existing
      if (inflight) return inflight
      const task = refresh(base, generation).finally(() => { if (inflight === task) inflight = null })
      inflight = task
      return task
    },
    reset() { generation++; currentBase = null; current = null; verifiedIdentity = null; nextRefreshAt = 0; inflight = null },
  }
}
