// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// FinOps cost-allocation taxonomy (ADR-0062).
//
// Read-only reflection of the PLAN: the showback groups are generated from
// `openbank-libs/governance/rules.yaml:finops_cost_groups` before tests and builds. The
// admin-ui displays the governed taxonomy; it cannot silently keep a shorter copy.
//
// CANONICAL service key = the manifest serviceName (NO `openbank-` prefix), which also equals
// the gitops Deployment metadata.name. `canonicalServiceId()` strips a leading `openbank-` and
// a trailing `-service`-less alias is NOT attempted — ids must match manifest.ts exactly. The
// data-domain (cost-center) lens is read straight from the governance manifest, so there is no
// second hand-maintained service->domain table to rot.

import type { DataDomain } from '@/lib/governance/manifest'
import { getGovernanceManifest } from '@/lib/governance/governanceLoader'
import generatedCostGroups from '../../../finops-cost-groups.json'

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

// Generated at build time from rules.yaml. A missing artifact fails the build rather than
// reporting a plausible but stale showback list.
export const COST_GROUPS: readonly CostGroup[] = generatedCostGroups

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
