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
  const components = await Promise.all(report.components.map(async (posture): Promise<ComponentTestPosture> => {
    const snapshotCommit = snapshotCommitOf(posture)
    const workload = running?.get(posture.component)
    const runningCommit = workload?.commit ?? null
    const base: RunningCommitObservation = {
      runningCommit, snapshotCommit, source: 'snapshot',
      match: sameCommit(runningCommit, snapshotCommit), storeStatus: workload ? 'disabled' : 'not-running',
    }
    if (!runningCommit) return { ...posture, running: base }
    const result = await read(posture.component, runningCommit)
    if (result.status === 'hit') {
      return {
        ...result.posture,
        evidence: [...result.posture.evidence, ...crossSourceEvidence(posture)],
        running: { ...base, source: 'running-commit', snapshotCommit: runningCommit, match: true, storeStatus: 'hit' },
      }
    }
    return { ...posture, running: { ...base, storeStatus: result.status } }
  }))
  return { ...report, components }
}

export function buildEvidenceMeta(report: TestIntelligenceReport, runningKnown: boolean, servedAt: number, now = Date.now()): TestEvidenceMeta {
  const t = Date.parse(report.collectedAt ?? '')
  // epoch-0 is the route's "nothing bundled" sentinel, not a real timestamp
  const known = Number.isFinite(t) && t > 0
  const ageMs = known ? Math.max(0, now - t) : null
  const observations = report.components.map(c => c.running).filter((r): r is RunningCommitObservation => Boolean(r))
  const fromStore = observations.filter(r => r.source === 'running-commit').length
  return {
    source: fromStore > 0 ? 'running-commit' : 'bundled',
    asOf: known ? new Date(t).toISOString() : null,
    ageSeconds: ageMs === null ? null : Math.floor(ageMs / 1000),
    stale: ageMs === null || ageMs > STALE_AFTER_MS,
    servedFrom: new Date(servedAt).toISOString(),
    runningCommits: {
      known: runningKnown ? observations.filter(r => r.runningCommit).length : null,
      matchingSnapshot: observations.filter(r => r.match === true).length,
      fromStore,
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
