// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { createHash } from 'node:crypto'

export interface GateHealthShardRun {
  runId: number
  sha: string
  event: string
  createdAt: string
  shards: { name: string; conclusion: string | null; seconds: number | null }[]
}

export interface GateHealthGate {
  id: string
  group: string
  mode: string
  status: string
  lastRed: { runId: number; sha: string; createdAt: string } | null
  flaky: boolean
  selftestDeclared: boolean
  minSubjects: number | null
  budgetSeconds: number | null
  runsObserved: number
}

export interface GateHealthSnapshotData {
  available: true
  source: string
  collectedAt: string
  runsInspected: number
  gateDetailRunsInspected: number
  shardHistory: GateHealthShardRun[]
  gates: GateHealthGate[]
  estate: {
    total: number
    enforced: number
    advisory: number
    withSelftest: number
    withFloor: number
    withBudget: number
    flaky: number
  } | null
}

export interface GateHealthManifest {
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

export interface GateHealthProvenance {
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

export type GateHealthSnapshotResult =
  | { status: 'ready' | 'stale'; snapshot: GateHealthSnapshotData; provenance: GateHealthProvenance; error?: string }
  | { status: 'unavailable' | 'disabled'; snapshot: null; provenance: null; error?: string }

export interface GateHealthSnapshotReaderOptions {
  baseUrl?: string
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
  return typeof value === 'string' && Number.isFinite(Date.parse(value)) && /^\d{4}-\d\d-\d\dT/.test(value)
}

/** Shared publisher/reader contract for the existing collector's gate-health.json shape. */
export function validateGateHealthSnapshotData(value: unknown, nowMs = Date.now()): value is GateHealthSnapshotData {
  if (!isRecord(value) || value.available !== true || typeof value.source !== 'string' || !value.source || !iso(value.collectedAt)) return false
  if (Date.parse(value.collectedAt) > nowMs + CLOCK_SKEW_MS) return false
  if (!finiteNumber(value.runsInspected) || !finiteNumber(value.gateDetailRunsInspected)) return false
  if (!Array.isArray(value.shardHistory) || !Array.isArray(value.gates)) return false
  for (const row of value.shardHistory) {
    if (!isRecord(row) || !finiteNumber(row.runId) || typeof row.sha !== 'string' || typeof row.event !== 'string' || !iso(row.createdAt) || !Array.isArray(row.shards)) return false
    for (const shard of row.shards) {
      if (!isRecord(shard) || typeof shard.name !== 'string' || (shard.conclusion !== null && typeof shard.conclusion !== 'string') || (shard.seconds !== null && !finiteNumber(shard.seconds))) return false
    }
  }
  for (const gate of value.gates) {
    if (!isRecord(gate) || ['id', 'group', 'mode', 'status'].some(key => typeof gate[key] !== 'string')) return false
    if (typeof gate.flaky !== 'boolean' || typeof gate.selftestDeclared !== 'boolean' || !finiteNumber(gate.runsObserved)) return false
    for (const key of ['minSubjects', 'budgetSeconds']) if (gate[key] !== null && !finiteNumber(gate[key])) return false
    if (gate.lastRed !== null && (!isRecord(gate.lastRed) || !finiteNumber(gate.lastRed.runId) || typeof gate.lastRed.sha !== 'string' || !iso(gate.lastRed.createdAt))) return false
  }
  if (value.estate !== null) {
    const estate = value.estate
    if (!isRecord(estate) || ['total', 'enforced', 'advisory', 'withSelftest', 'withFloor', 'withBudget', 'flaky'].some(key => !finiteNumber(estate[key]))) return false
  }
  return true
}

export function validateGateHealthManifest(value: unknown, nowMs = Date.now()): value is GateHealthManifest {
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

export function gateHealthStoreBaseUrl(raw: string | undefined): string | null {
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

export function createGateHealthSnapshotReader(options: GateHealthSnapshotReaderOptions = {}) {
  const now = options.now ?? Date.now
  const fetchImpl = options.fetchImpl ?? ((...args: Parameters<typeof fetch>) => fetch(...args))
  const ttlMs = options.ttlMs ?? 30_000
  const timeoutMs = options.timeoutMs ?? 3_000
  const maxBytes = options.maxBytes ?? MAX_BYTES
  const maxDataAgeMs = options.maxDataAgeMs ?? MAX_DATA_AGE_MS
  let current: GateHealthSnapshotResult | null = null
  let nextRefreshAt = 0
  let inflight: Promise<GateHealthSnapshotResult> | null = null

  const currentState = (): GateHealthSnapshotResult | null => {
    if (!current || current.snapshot === null || current.provenance === null) return current
    const pointerExpired = Date.parse(current.provenance.expiresAt) <= now()
    const snapshotAgeMs = Math.max(0, now() - Date.parse(current.provenance.collectedAt))
    const provenance = { ...current.provenance, pointerExpired, snapshotAgeMs }
    return { ...current, status: pointerExpired || snapshotAgeMs > maxDataAgeMs || Boolean(current.error) ? 'stale' : 'ready', provenance }
  }

  async function refresh(base: string): Promise<GateHealthSnapshotResult> {
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
      if (!validateGateHealthManifest(pointer, now())) throw new Error('invalid manifest schema')

      const snapshotResponse = await request(validSnapshotUrl(base, pointer.snapshot))
      if (!snapshotResponse.ok) throw new Error(`snapshot HTTP ${snapshotResponse.status}`)
      const bytes = await readBoundedBody(snapshotResponse, maxBytes)
      const digest = createHash('sha256').update(bytes).digest('hex')
      if (digest !== pointer.sha256) throw new Error('snapshot digest mismatch')
      let data: unknown
      try { data = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)) } catch { throw new Error('malformed snapshot') }
      if (!validateGateHealthSnapshotData(data, now())) throw new Error('invalid snapshot schema')
      if (data.collectedAt !== pointer.collectedAt) throw new Error('snapshot collection time does not match manifest')

      const pointerExpired = Date.parse(pointer.expiresAt) <= now()
      const snapshotAgeMs = Math.max(0, now() - Date.parse(data.collectedAt))
      const provenance: GateHealthProvenance = {
        sourceSha: pointer.sourceSha, runId: pointer.runId, runAttempt: pointer.runAttempt,
        snapshot: pointer.snapshot, sha256: pointer.sha256, collectedAt: pointer.collectedAt,
        expiresAt: pointer.expiresAt, pointerExpired, snapshotAgeMs,
      }
      current = { status: pointerExpired || snapshotAgeMs > maxDataAgeMs ? 'stale' : 'ready', snapshot: data, provenance }
      nextRefreshAt = now() + ttlMs
      return current
    } catch (error) {
      nextRefreshAt = now() + ttlMs
      const lastGood = currentState()
      if (lastGood?.snapshot && lastGood.provenance) {
        current = { ...lastGood, status: 'stale', error: error instanceof Error ? error.message : 'evidence store unavailable' }
        return current
      }
      current = { status: 'unavailable', snapshot: null, provenance: null, error: error instanceof Error ? error.message : 'evidence store unavailable' }
      return current
    }
  }

  return {
    async read(): Promise<GateHealthSnapshotResult> {
      const base = gateHealthStoreBaseUrl(options.baseUrl ?? process.env.OPENBANK_GATE_HEALTH_STORE_URL)
      const rawBase = options.baseUrl ?? process.env.OPENBANK_GATE_HEALTH_STORE_URL
      if (rawBase === undefined) return { status: 'disabled', snapshot: null, provenance: null }
      if (!base) return { status: 'unavailable', snapshot: null, provenance: null, error: 'invalid evidence store URL' }
      const existing = currentState()
      if (existing && now() < nextRefreshAt) return existing
      if (inflight) return inflight
      inflight = refresh(base).finally(() => { inflight = null })
      return inflight
    },
    reset() { current = null; nextRefreshAt = 0; inflight = null },
  }
}
