// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

// Drift gate (ADR-0062 / ADR-0029 derive->enforce->show): the cost-group taxonomy mirror
// (costGroups.ts) must stay consistent with the governance manifest — every service named in a
// business flow must be a real, domain-classified service, or the roll-up would silently lose it.
// This fails the build the same way a Zod-validated manifest does.

import { describe, it, expect } from 'vitest'
import { COST_GROUPS, domainForService, knownServiceIds, canonicalServiceId } from '@/lib/finops/costGroups'

describe('finops cost-group taxonomy', () => {
  it('every service in every flow exists in the governance manifest', () => {
    const known = knownServiceIds()
    for (const g of COST_GROUPS) {
      for (const svc of g.services) {
        expect(known.has(canonicalServiceId(svc)), `${g.id} -> ${svc}`).toBe(true)
      }
    }
  })

  it('every service in every flow resolves to a data domain', () => {
    for (const g of COST_GROUPS) {
      for (const svc of g.services) {
        expect(domainForService(svc), `${g.id} -> ${svc}`).toBeDefined()
      }
    }
  })

  it('has unique flow ids and non-empty bilingual labels', () => {
    const ids = COST_GROUPS.map(g => g.id)
    expect(new Set(ids).size).toBe(ids.length)
    for (const g of COST_GROUPS) {
      expect(g.labelEn.length, g.id).toBeGreaterThan(0)
      expect(g.labelCs.length, g.id).toBeGreaterThan(0)
      expect(g.services.length, g.id).toBeGreaterThan(0)
    }
  })

  it('canonicalServiceId strips a leading openbank- prefix', () => {
    expect(canonicalServiceId('openbank-ledger-service')).toBe('ledger-service')
    expect(canonicalServiceId('ledger-service')).toBe('ledger-service')
  })
})
