// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/**
 * Join the bundled Test Intelligence snapshot against what is RUNNING (#13201): for each
 * component, prefer the evidence store's posture for the running commit; otherwise keep the
 * snapshot posture and say which commit it is about and whether that is the running one.
 */
import { createHash } from 'crypto'
import type {
  ComponentTestPosture, RunningCommitObservation, TestEvidenceMeta, TestIntelligenceReport,
} from '@/lib/types/test-intelligence'
import type { CommitEvidenceResult } from './commitEvidence'
import type { RunningWorkload } from './runningCommits'

export const STALE_AFTER_MS = 24 * 3_600_000

/** The commit the snapshot's posture is about — the newest run any observation names. */
export function snapshotCommitOf(posture: ComponentTestPosture): string | null {
  const runs = posture.evidence
    .filter(item => item.run?.commit && item.observedAt)
    .sort((a, b) => Date.parse(b.observedAt ?? '') - Date.parse(a.observedAt ?? ''))
  return runs[0]?.run?.commit ?? null
}

function latestEvidenceAtOf(posture: ComponentTestPosture): string | null {
  const timestamps = [...posture.evidence.map(item => item.observedAt), posture.coverage.observedAt]
    .filter((value): value is string => typeof value === 'string' && Number.isFinite(Date.parse(value)))
  return timestamps.sort((a, b) => Date.parse(b) - Date.parse(a))[0] ?? null
}

function withRunningAge(running: RunningCommitObservation, posture: ComponentTestPosture, now: number): RunningCommitObservation {
  const latestEvidenceAt = latestEvidenceAtOf(posture)
  return {
    ...running,
    latestEvidenceAt,
    latestEvidenceAgeSeconds: latestEvidenceAt ? Math.max(0, Math.floor((now - Date.parse(latestEvidenceAt)) / 1000)) : null,
  }
}

export function sameCommit(a: string | null, b: string | null): boolean | null {
  if (!a || !b) return null
  return a.startsWith(b) || b.startsWith(a)
}

/**
 * Rows the fleet collector adds from OTHER producers (Pact broker, Pitest, k6, simulation
 * scenarios). The per-commit store carries only the component's own run envelope, so on a
 * store hit these are kept from the snapshot rather than silently dropped. They describe
 * their own runs (a pact verification, a mutation sweep), never the snapshot's commit.
 */
const CROSS_SOURCE_PREFIXES = ['Pact:', 'Pitest:', 'k6:']
export function crossSourceEvidence(posture: ComponentTestPosture): ComponentTestPosture['evidence'] {
  return posture.evidence.filter(item => item.kind === 'simulation'
    || CROSS_SOURCE_PREFIXES.some(prefix => item.source?.startsWith(prefix)))
}

export async function overlayRunningEvidence(
  report: TestIntelligenceReport,
  running: Map<string, RunningWorkload> | null,
  read: (component: string, commit: string) => Promise<CommitEvidenceResult>,
): Promise<TestIntelligenceReport> {
  const now = Date.now()
  const components = await Promise.all(report.components.map(async (posture): Promise<ComponentTestPosture> => {
    const snapshotCommit = snapshotCommitOf(posture)
    const workload = running?.get(posture.component)
    const runningCommit = workload?.commit ?? null
    const base: RunningCommitObservation = {
      runningCommit, snapshotCommit, source: 'snapshot',
      match: sameCommit(runningCommit, snapshotCommit), storeStatus: workload ? 'disabled' : 'not-running',
    }
    if (!runningCommit) return { ...posture, running: withRunningAge(base, posture, now) }
    const result = await read(posture.component, runningCommit)
    if (result.status === 'hit') {
      return {
        ...result.posture,
        evidence: [...result.posture.evidence, ...crossSourceEvidence(posture)],
        running: withRunningAge({
          ...base, source: 'running-commit', snapshotCommit: runningCommit, match: true, storeStatus: 'hit',
          evidenceCommit: result.posture.evidenceSource?.commit ?? runningCommit,
          equivalence: result.posture.evidenceSource?.equivalence ?? 'identical',
        }, result.posture, now),
      }
    }
    return { ...posture, running: withRunningAge({ ...base, storeStatus: result.status }, posture, now) }
  }))
  return { ...report, components }
}

export interface TestIntelligenceDatasetProvenance {
  status: TestEvidenceMeta['dataset']['status']
  sourceSha: string | null
  runId: number | null
  runAttempt: number | null
  snapshot: string | null
  sha256: string | null
  collectedAt: string | null
  expiresAt: string | null
  pointerExpired: boolean | null
  snapshotAgeMs: number | null
  error?: string
}

export function buildEvidenceMeta(
  report: TestIntelligenceReport,
  runningKnown: boolean,
  servedAt: number,
  dataset: TestIntelligenceDatasetProvenance = {
    status: 'disabled', sourceSha: null, runId: null, runAttempt: null, snapshot: null,
    sha256: null, collectedAt: null, expiresAt: null, pointerExpired: null, snapshotAgeMs: null,
  },
  now = Date.now(),
): TestEvidenceMeta {
  const t = Date.parse(report.collectedAt ?? '')
  // epoch-0 is the route's "nothing bundled" sentinel, not a real timestamp
  const known = Number.isFinite(t) && t > 0
  const ageMs = known ? Math.max(0, now - t) : null
  const observations = report.components.map(c => c.running).filter((r): r is RunningCommitObservation => Boolean(r))
  const fromStore = observations.filter(r => r.source === 'running-commit').length
  const aggregateSource = dataset.status === 'ready' || dataset.status === 'stale'
    ? 'published-snapshot' as const
    : dataset.status === 'unavailable' ? 'unavailable' as const : 'bundled' as const
  const source = fromStore > 0 && aggregateSource !== 'unavailable' ? 'mixed' as const : aggregateSource
  return {
    source,
    asOf: known ? new Date(t).toISOString() : null,
    ageSeconds: ageMs === null ? null : Math.floor(ageMs / 1000),
    stale: dataset.status === 'stale' || (dataset.status === 'disabled' && (ageMs === null || ageMs > STALE_AFTER_MS)),
    servedFrom: new Date(servedAt).toISOString(),
    dataset: {
      status: dataset.status, sourceSha: dataset.sourceSha, runId: dataset.runId, runAttempt: dataset.runAttempt,
      snapshot: dataset.snapshot, sha256: dataset.sha256, collectedAt: dataset.collectedAt,
      expiresAt: dataset.expiresAt, pointerExpired: dataset.pointerExpired,
      snapshotAgeSeconds: dataset.snapshotAgeMs === null ? null : Math.floor(dataset.snapshotAgeMs / 1000),
      ...(dataset.error ? { error: dataset.error } : {}),
    },
    runningCommits: {
      known: runningKnown ? observations.filter(r => r.runningCommit).length : null,
      matchingSnapshot: observations.filter(r => r.match === true).length,
      fromStore,
      fromEquivalentBuild: observations.filter(r => r.source === 'running-commit' && r.equivalence === 'tree-equivalent').length,
      fromBundledFallback: observations.filter(r => r.runningCommit && r.storeStatus !== 'hit').length,
      mismatchedEvidence: observations.filter(r => r.runningCommit && r.match !== true && r.storeStatus !== 'hit').length,
    },
  }
}

/** Weak ETag over the serialised body: identical payloads revalidate as 304. */
export function etagOf(body: string): string {
  return `W/"${createHash('sha256').update(body).digest('base64url').slice(0, 27)}"`
}

export function ifNoneMatchHits(header: string | null, etag: string): boolean {
  if (!header) return false
  return header.split(',').map(s => s.trim()).some(tag => tag === etag || tag === '*')
}
