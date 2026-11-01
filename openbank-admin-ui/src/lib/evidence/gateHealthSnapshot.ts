// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { createEvidenceSnapshotReader, evidenceStoreBaseUrl, validateEvidenceManifest } from './publishedSnapshot.ts'
import type { EvidenceManifest, EvidenceProvenance, EvidenceSnapshotReaderOptions, EvidenceSnapshotResult } from './publishedSnapshot.ts'

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

export type GateHealthManifest = EvidenceManifest
export type GateHealthProvenance = EvidenceProvenance
export type GateHealthSnapshotResult = EvidenceSnapshotResult<GateHealthSnapshotData>
export interface GateHealthSnapshotReaderOptions extends Omit<EvidenceSnapshotReaderOptions<GateHealthSnapshotData>, 'baseUrl' | 'validateSnapshot' | 'collectedAt'> {
  baseUrl?: string
}
export const validateGateHealthManifest = validateEvidenceManifest
export const gateHealthStoreBaseUrl = evidenceStoreBaseUrl

export function createGateHealthSnapshotReader(options: GateHealthSnapshotReaderOptions = {}) {
  return createEvidenceSnapshotReader<GateHealthSnapshotData>({
    ...options,
    baseUrl: () => options.baseUrl ?? process.env.OPENBANK_GATE_HEALTH_STORE_URL,
    validateSnapshot: validateGateHealthSnapshotData,
    collectedAt: snapshot => snapshot.collectedAt,
  })
}
