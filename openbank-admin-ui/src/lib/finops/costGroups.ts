// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// FinOps cost-allocation taxonomy (ADR-0062).
//
// Read-only reflection of the PLAN: catalog.json carries the reviewed
// `rules.yaml:finops_cost_groups` taxonomy into the runtime image. No UI-owned copy.
//
// CANONICAL service key = the manifest serviceName (NO `openbank-` prefix), which also equals
// the gitops Deployment metadata.name. `canonicalServiceId()` strips a leading `openbank-` and
// a trailing `-service`-less alias is NOT attempted — ids must match manifest.ts exactly. The
// data-domain (cost-center) lens is read straight from the governance manifest, so there is no
// second hand-maintained service->domain table to rot.

import type { DataDomain } from '@/lib/governance/manifest'
import { getGovernanceManifest } from '@/lib/governance/governanceLoader'
import { readFileSync } from 'node:fs'
import path from 'node:path'

export interface CostGroup {
  /** Stable id, e.g. `sepa-payment-flow`. */
  id: string
  labelEn: string
  labelCs: string
  /** Canonical service ids (manifest serviceName). A service may appear in many groups. */
  services: string[]
  /** Regulatory anchor shown in the tooltip, when the flow has one. */
  regulatoryRef?: string
}

export function costGroupsFromCatalog(parsed: unknown): readonly CostGroup[] {
  if (!parsed || typeof parsed !== 'object') throw new Error('derived catalog has no FinOps cost groups')
  const groupsValue = (parsed as { finopsCostGroups?: unknown }).finopsCostGroups
  if (!Array.isArray(groupsValue) || groupsValue.length === 0) {
    throw new Error('derived catalog has no FinOps cost groups')
  }
  const groups = groupsValue as CostGroup[]
  if (groups.some(g => !g || typeof g.id !== 'string' || !g.id
      || typeof g.labelEn !== 'string' || !g.labelEn
      || typeof g.labelCs !== 'string' || !g.labelCs
      || !Array.isArray(g.services) || g.services.length === 0
      || g.services.some(service => typeof service !== 'string' || !service))) {
    throw new Error('derived catalog has an invalid FinOps cost group')
  }
  return groups
}

function loadCostGroups(): readonly CostGroup[] {
  const file = process.env.OPENBANK_CATALOG ?? path.resolve(process.cwd(), 'catalog.json')
  return costGroupsFromCatalog(JSON.parse(readFileSync(file, 'utf-8')))
}

// The cost allocation route and its pure allocator share the exact generated taxonomy.
// A missing/malformed artifact fails closed instead of omitting a flow from showback.
export const COST_GROUPS: readonly CostGroup[] = loadCostGroups()

// service -> domain lookup, built once from the governance manifest (the domain source of truth).
const DOMAIN_BY_SERVICE: ReadonlyMap<string, DataDomain> = new Map(
  getGovernanceManifest().map(e => [e.serviceName, e.dataDomain]),
)

/** Strip a leading `openbank-` so callers can pass either convention; returns the manifest key. */
export function canonicalServiceId(id: string): string {
  return id.replace(/^openbank-/, '')
}

/** Data domain (cost-center) for a service id, or undefined if the manifest doesn't know it. */
export function domainForService(id: string): DataDomain | undefined {
  return DOMAIN_BY_SERVICE.get(canonicalServiceId(id))
}

/** All service ids the governance manifest knows — the universe a footprint must map into. */
export function knownServiceIds(): Set<string> {
  return new Set(DOMAIN_BY_SERVICE.keys())
}
