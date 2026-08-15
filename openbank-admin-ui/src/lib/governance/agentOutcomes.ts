// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Per-charter agent outcome metrics (issue #4462), derived from the ADR-0031 D4 HITL
// proposal store — NOT from the audit trail.
//
// WHY NOT THE AUDIT TRAIL. The issue proposes sourcing this from `AuditEvent` records.
// That source cannot carry it honestly: 71% of live audit rows name no actor at all
// (`actor_id IS NULL`; ADR-0133 chain, #3994, PR #4693 recovered the SOURCE-service half
// but the ACTOR half is a genuine producer-side omission), so an acceptance rate computed
// over the attributable rows would be the rate of whichever flows happen to populate the
// field — a biased sample wearing a measurement's clothes.
//
// `agent_proposal` has no such gap. `decided_by` and `decided_at` are written by the same
// transaction that sets the terminal state (ProposalService), so EVERY decided proposal is
// fully attributed and fully timed. Measured on the live store 2026-08-13: 73 proposals,
// 41 decided, 41 of 41 carrying both `decided_by` and `decided_at` — 100% attribution.
//
// The denominator problem is therefore a different one, and it is real: 32 of 73 proposals
// (44%) have never been decided. A rate over the decided 41 is an honest statement about
// decided proposals and a dishonest one about proposals, so [AgentOutcomeMetrics] carries
// its own coverage and the UI is required to render it beside the number.

/** The proposal fields the metrics need — the shape /api/iaops/agents/[agentId] already returns. */
export interface ProposalOutcomeInput {
  state: string
  proposedAt: string
  decidedAt: string | null
}

export interface AgentOutcomeMetrics {
  /** Every proposal this charter has ever raised (within the page cap — see [truncated]). */
  total: number
  /** Reached a terminal state. The acceptance-rate denominator. */
  decided: number
  /** Still PROPOSED. Not in any denominator; rendered so the rate cannot be read as fleet-wide. */
  pending: number
  approved: number
  rejected: number
  /**
   * approved / decided, 0..1 — or null when [decided] is below [MIN_DECIDED_FOR_RATE].
   * Never approved / total: an undecided proposal is not a rejected one.
   */
  approvalRate: number | null
  /** Median and 95th percentile decision latency, seconds. Null below the sample threshold. */
  latencyP50Seconds: number | null
  latencyP95Seconds: number | null
  /**
   * Decided proposals that yielded a usable latency — its own denominator, which is NOT
   * [decided]: a row whose timestamps are unparseable, pre-2000 (the `Instant.EPOCH`-default
   * trap, #3882) or negative-duration is excluded rather than folded in as a zero.
   */
  latencySamples: number
  /** Decided rows excluded from the latency sample, and why they must be visible. */
  latencyExcluded: number
  /** True when [decided] < [MIN_DECIDED_FOR_RATE]: show "insufficient data", never a 100%. */
  insufficientData: boolean
  /**
   * The proposal list hit the API page cap, so [total] is a floor and every rate is computed
   * over a truncated window. ProposalResource caps `?state=all` at 100 rows per agent.
   */
  truncated: boolean
  /** Most recent decision, ISO. A queue that is fed but not reviewed shows up here, not in the rate. */
  lastDecisionAt: string | null
}

/**
 * Below this many decided proposals no rate is reported (issue #4462 acceptance criterion:
 * "a charter with <N proposals in window shows insufficient data, never a misleading 100%").
 * Five is the smallest count at which a single decision cannot move the rate by 50 points.
 */
export const MIN_DECIDED_FOR_RATE = 5

/** The API page cap ProposalResource applies to `?state=all` (MAX_LIST). */
export const PROPOSAL_PAGE_CAP = 100

/** Timestamps at or before this are a data-class default, not an event time (#3882). */
const IMPLAUSIBLY_OLD_MS = Date.UTC(2000, 0, 1)

function parseInstant(value: string | null | undefined): number | null {
  if (!value) return null
  const ms = Date.parse(value)
  if (!Number.isFinite(ms)) return null
  return ms > IMPLAUSIBLY_OLD_MS ? ms : null
}

/**
 * Nearest-rank percentile over an ascending array — no interpolation, so every reported
 * value is a latency some proposal actually had.
 */
function percentile(sortedAscending: number[], fraction: number): number {
  const rank = Math.ceil(fraction * sortedAscending.length)
  return sortedAscending[Math.min(Math.max(rank, 1), sortedAscending.length) - 1]
}

export function deriveAgentOutcomes(proposals: ProposalOutcomeInput[]): AgentOutcomeMetrics {
  const approved = proposals.filter(p => p.state === 'APPROVED').length
  const rejected = proposals.filter(p => p.state === 'REJECTED').length
  const decided = approved + rejected
  const pending = proposals.length - decided

  const latencies: number[] = []
  let latencyExcluded = 0
  let lastDecisionMs: number | null = null
  for (const p of proposals) {
    if (p.state !== 'APPROVED' && p.state !== 'REJECTED') continue
    const from = parseInstant(p.proposedAt)
    const to = parseInstant(p.decidedAt)
    if (from === null || to === null || to < from) {
      latencyExcluded += 1
      continue
    }
    latencies.push((to - from) / 1000)
    if (lastDecisionMs === null || to > lastDecisionMs) lastDecisionMs = to
  }
  latencies.sort((a, b) => a - b)

  const enoughToRate = decided >= MIN_DECIDED_FOR_RATE
  const enoughToTime = latencies.length >= MIN_DECIDED_FOR_RATE

  return {
    total: proposals.length,
    decided,
    pending,
    approved,
    rejected,
    approvalRate: enoughToRate ? approved / decided : null,
    latencyP50Seconds: enoughToTime ? percentile(latencies, 0.5) : null,
    latencyP95Seconds: enoughToTime ? percentile(latencies, 0.95) : null,
    latencySamples: latencies.length,
    latencyExcluded,
    insufficientData: !enoughToRate,
    truncated: proposals.length >= PROPOSAL_PAGE_CAP,
    lastDecisionAt: lastDecisionMs === null ? null : new Date(lastDecisionMs).toISOString(),
  }
}

/** "2 d 12 h" / "3 h 5 m" / "45 s" — a latency a reviewer can read without dividing. */
export function formatLatency(seconds: number): string {
  if (seconds < 60) return `${Math.round(seconds)} s`
  if (seconds < 3600) return `${Math.floor(seconds / 60)} m`
  if (seconds < 86400) {
    const h = Math.floor(seconds / 3600)
    const m = Math.floor((seconds % 3600) / 60)
    return m === 0 ? `${h} h` : `${h} h ${m} m`
  }
  const d = Math.floor(seconds / 86400)
  const h = Math.floor((seconds % 86400) / 3600)
  return h === 0 ? `${d} d` : `${d} d ${h} h`
}
