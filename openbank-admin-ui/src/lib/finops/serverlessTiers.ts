// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// FinOps workload-tier visualisation data (ADR-0057 / ADR-0083).
//
// Read-only reflection of the PLAN. The money-path T0 baseline is generated from
// rules.yaml at build time; explicit pilot assignments remain below until governed
// classifier data replaces them. Keys are the short service-card ids used by the
// services page (e.g. `notification`, `product-catalog`).

import moneyPathT0 from '../../../finops-money-path.json'

export type Tier = 'T0' | 'T1' | 'T2' | 'T3'

// live      — actually scaling to/from zero in the sandbox now
// planned   — a tier change is designed (ADR) but not yet implemented
// candidate — eligible by trigger, no plan committed yet
// always_on — T0 by policy (money-path / regulator-continuous), never scales to zero
export type TierStatus = 'live' | 'planned' | 'candidate' | 'always_on'

export interface ServerlessTierInfo {
  tier: Tier
  status: TierStatus
  /** ADR the assignment traces to, shown in the tooltip. */
  adr?: string
}

// Mechanism (the technology) per tier — descriptive keys; the UI maps them to bilingual
// copy. T1 leads with the KEDA HTTP add-on (ADR-0083 recommendation), Knative noted as
// the considered alternative.
export const TIER_MECHANISM: Record<Tier, { tech: string; knative?: boolean }> = {
  T0: { tech: 'always-on' },
  T1: { tech: 'KEDA HTTP add-on + Quarkus native', knative: true },
  T2: { tech: 'KEDA · Kafka consumer-group lag' },
  T3: { tech: 'Kubernetes CronJob' },
}

// Generated from rules.yaml at build time; never maintain a second service list here.
const MONEY_PATH_T0: ReadonlySet<string> = new Set(moneyPathT0)

// Explicit, evidence-backed assignments (rules.yaml: finops_tiers.declared + ADR-0083).
const EXPLICIT: Record<string, ServerlessTierInfo> = {
  // proven pilot, live in the sandbox (KEDA Kafka-lag, minReplicas 0)
  notification: { tier: 'T2', status: 'live', adr: 'ADR-0057' },
  // T1 pilot failed the in-cluster caller path; rules.yaml declares T0 until that path is repaired.
  'product-catalog': { tier: 'T0', status: 'always_on', adr: 'ADR-0083' },
  // T2-eligible event consumers (@Incoming) not yet deployed -> candidates
  audit: { tier: 'T2', status: 'candidate', adr: 'ADR-0057' },
  'analytics-sink': { tier: 'T2', status: 'candidate', adr: 'ADR-0057' },
}

const MONEY_PATH_INFO: ServerlessTierInfo = { tier: 'T0', status: 'always_on', adr: 'ADR-0057' }

/** Tier assignment for a service id, or undefined if unclassified (no badge). */
export function serverlessTierFor(id: string): ServerlessTierInfo | undefined {
  if (MONEY_PATH_T0.has(id)) return MONEY_PATH_INFO
  if (EXPLICIT[id]) return EXPLICIT[id]
  return undefined
}

/** True for any tier that scales to/from zero (everything below T0). */
export function scalesToZero(tier: Tier): boolean {
  return tier !== 'T0'
}
