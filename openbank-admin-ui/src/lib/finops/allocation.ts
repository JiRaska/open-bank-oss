// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// FinOps cost allocation — requests-weighted showback (ADR-0062).
//
// Pure function, NO I/O: the route reads the cost snapshot + footprints and calls allocate().
// Keeping it pure makes the allocation maths unit-testable without a cluster or AWS access.
//
// Model (OpenCost-style requests-based showback):
//  1. Classify each AWS Cost Explorer line as COMPUTE (the shared, allocatable pool) or
//     PLATFORM (control plane / NAT / LB / storage — "platform tax", shown but never split).
//  2. Weight each service by its declared resource requests: a 50/50 split between a CPU pool
//     and a RAM pool (the OpenCost default), normalised so Σ weights = 1.
//  3. allocated$(svc) = COMPUTE_pool × weight(svc). Roll up to domain (cost-center) and to
//     business flow. Flows OVERLAP (a service is in every flow that needs it) so Σ flows can
//     exceed the total — that is intentional fully-loaded showback, not a bug.
//
// Invariant: Σ byService == allocatable, and allocatable + platformOverhead == total.

import { COST_GROUPS, domainForService, type CostGroup } from './costGroups'
import type { DataDomain } from '@/lib/governance/manifest'

// CPU vs RAM cost split. OpenCost defaults to weighting the two request dimensions equally;
// we mirror that. Tunable here if a measured node-cost breakdown ever justifies otherwise.
const CPU_WEIGHT = 0.5
const RAM_WEIGHT = 0.5

// Services with a resource footprint but unknown to the governance manifest (in-cluster
// platform infra: keycloak, redis, external-dns, apicurio, admin-ui itself). They genuinely
// consume compute, so we DO allocate to them — but under the `platform` domain, and we record
// them in `unmapped` for transparency rather than silently folding their cost into business
// services.
const FALLBACK_DOMAIN: DataDomain = 'platform'

export interface CostLine {
  name: string
  amount: number
}

export interface CostReportLike {
  available: boolean
  currency: string
  periodStart?: string
  periodEnd?: string
  total: number
  services: CostLine[]
  collectedAt?: string | null
}

export interface ServiceFootprint {
  service: string
  cpuMillis: number
  memMiB: number
}

export interface ServiceAllocation {
  service: string
  domain: DataDomain
  amount: number
  pct: number
  cpuMillis: number
  memMiB: number
}

export interface DomainAllocation {
  domain: DataDomain
  amount: number
  pct: number
  serviceCount: number
}

export interface FlowAllocation {
  id: string
  labelEn: string
  labelCs: string
  amount: number
  /** Share of the fleet total — can exceed 100% across flows (fully-loaded overlap). */
  pct: number
  services: string[]
  regulatoryRef?: string
}

export interface AllocationResult {
  available: boolean
  currency: string
  periodStart: string
  periodEnd: string
  /** Whole cloud bill (== costReport.total). */
  total: number
  /** The COMPUTE pool that was split across services. */
  allocatable: number
  /** total - allocatable: control plane, NAT, LB, storage. Shown, never allocated. */
  platformOverhead: number
  byService: ServiceAllocation[]
  byDomain: DomainAllocation[]
  byFlow: FlowAllocation[]
  /** Footprint services not found in the governance manifest (allocated under `platform`). */
  unmapped: string[]
  method: 'requests-weighted-showback'
  collectedAt: string | null
}

const round2 = (n: number) => Math.round(n * 100) / 100

// Explicit classification — NOT a heuristic. AWS Cost Explorer SERVICE dimension returns names
// like "Amazon Elastic Compute Cloud - Compute" (EC2 instance hours = the worker nodes we run
// pods on) vs "EC2 - Other" (NAT/EBS/EIP) and "Amazon Elastic Kubernetes Service" (the $0.10/hr
// control plane). Only true instance-hours are the allocatable compute pool; everything else,
// including the ambiguous "EC2 - Other", is conservatively treated as platform overhead so we
// never inflate per-service allocation with costs we can't attribute to a pod.
export function isComputeLine(name: string): boolean {
  const n = name.toLowerCase()
  if (n.includes('ec2 - other')) return false
  return n.includes('elastic compute cloud - compute') || n.includes('ec2 - compute')
}

function emptyResult(report: CostReportLike): AllocationResult {
  return {
    available: false,
    currency: report.currency || 'USD',
    periodStart: report.periodStart ?? '',
    periodEnd: report.periodEnd ?? '',
    total: round2(report.total ?? 0),
    allocatable: 0,
    platformOverhead: round2(report.total ?? 0),
    byService: [],
    byDomain: [],
    byFlow: [],
    unmapped: [],
    method: 'requests-weighted-showback',
    collectedAt: report.collectedAt ?? null,
  }
}

/**
 * Allocate a cost snapshot across services / domains / business flows by resource requests.
 * Pure: deterministic for given inputs, no I/O. Degrades to available:false when the snapshot
 * is unavailable, so the route can always 200.
 */
export function allocate(
  report: CostReportLike,
  footprints: ServiceFootprint[],
  groups: readonly CostGroup[] = COST_GROUPS,
): AllocationResult {
  if (!report?.available || !Array.isArray(report.services) || report.services.length === 0) {
    return emptyResult(report ?? { available: false, currency: 'USD', total: 0, services: [] })
  }

  const total = round2(report.services.reduce((s, l) => s + (Number(l.amount) || 0), 0))
  const allocatable = round2(
    report.services.filter(l => isComputeLine(l.name)).reduce((s, l) => s + (Number(l.amount) || 0), 0),
  )
  const platformOverhead = round2(total - allocatable)

  // Sanitise footprints: positive numbers only, dedupe by service (keep the larger request).
  const valid = new Map<string, ServiceFootprint>()
  for (const f of footprints ?? []) {
    const cpu = Number(f?.cpuMillis) || 0
    const mem = Number(f?.memMiB) || 0
    if (!f?.service || (cpu <= 0 && mem <= 0)) continue
    const prev = valid.get(f.service)
    if (!prev || cpu + mem > prev.cpuMillis + prev.memMiB) valid.set(f.service, { service: f.service, cpuMillis: cpu, memMiB: mem })
  }
  const fp = [...valid.values()]

  const cpuTotal = fp.reduce((s, f) => s + f.cpuMillis, 0)
  const memTotal = fp.reduce((s, f) => s + f.memMiB, 0)

  // weight = CPU_WEIGHT·(cpu share) + RAM_WEIGHT·(ram share), guarding against /0 when a
  // dimension is entirely absent (fall back to the present dimension; if both are 0, no split).
  const weightOf = (f: ServiceFootprint): number => {
    const cpuShare = cpuTotal > 0 ? f.cpuMillis / cpuTotal : 0
    const memShare = memTotal > 0 ? f.memMiB / memTotal : 0
    if (cpuTotal > 0 && memTotal > 0) return CPU_WEIGHT * cpuShare + RAM_WEIGHT * memShare
    return cpuTotal > 0 ? cpuShare : memShare // only one dimension present
  }

  const unmapped: string[] = []
  const byService: ServiceAllocation[] = fp
    .map(f => {
      const amount = round2(allocatable * weightOf(f))
      const domain = domainForService(f.service)
      if (!domain) unmapped.push(f.service)
      return {
        service: f.service,
        domain: domain ?? FALLBACK_DOMAIN,
        amount,
        pct: total > 0 ? round2((amount / total) * 100) : 0,
        cpuMillis: f.cpuMillis,
        memMiB: f.memMiB,
      }
    })
    .filter(s => s.amount > 0)
    .sort((a, b) => b.amount - a.amount)

  // byDomain: roll up byService. Σ byDomain == Σ byService by construction.
  const domainAcc = new Map<DataDomain, { amount: number; count: number }>()
  for (const s of byService) {
    const cur = domainAcc.get(s.domain) ?? { amount: 0, count: 0 }
    domainAcc.set(s.domain, { amount: cur.amount + s.amount, count: cur.count + 1 })
  }
  const byDomain: DomainAllocation[] = [...domainAcc.entries()]
    .map(([domain, v]) => ({ domain, amount: round2(v.amount), pct: total > 0 ? round2((v.amount / total) * 100) : 0, serviceCount: v.count }))
    .sort((a, b) => b.amount - a.amount)

  // byFlow: fully-loaded — sum every footprinted service in the flow. Overlaps intended.
  const amountOf = new Map(byService.map(s => [s.service, s.amount]))
  const byFlow: FlowAllocation[] = groups
    .map(g => {
      const amount = round2(g.services.reduce((s, svc) => s + (amountOf.get(svc) ?? 0), 0))
      return {
        id: g.id,
        labelEn: g.labelEn,
        labelCs: g.labelCs,
        amount,
        pct: total > 0 ? round2((amount / total) * 100) : 0,
        services: g.services,
        regulatoryRef: g.regulatoryRef,
      }
    })
    .filter(f => f.amount > 0)
    .sort((a, b) => b.amount - a.amount)

  return {
    available: true,
    currency: report.currency || 'USD',
    periodStart: report.periodStart ?? '',
    periodEnd: report.periodEnd ?? '',
    total,
    allocatable,
    platformOverhead,
    byService,
    byDomain,
    byFlow,
    unmapped: [...new Set(unmapped)],
    method: 'requests-weighted-showback',
    collectedAt: report.collectedAt ?? null,
  }
}
