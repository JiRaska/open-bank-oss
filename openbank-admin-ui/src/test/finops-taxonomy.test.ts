// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Drift gate (ADR-0062 / ADR-0029 derive->enforce->show): the cost-group taxonomy mirror
// (costGroups.ts) must stay consistent with the governance manifest — every service named in a
// business flow must be a real, domain-classified service, or the roll-up would silently lose it.
// This fails the build the same way a Zod-validated manifest does.

import { afterEach, describe, it, expect, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { parse } from 'yaml'
import { getRegistry } from '@/lib/services/fleet'
import * as fleet from '@/lib/services/fleet'
import { k8sNameOf } from '@/lib/services/registry'
import { COST_GROUPS, domainForService, knownServiceIds, canonicalServiceId, governanceServiceIdForWorkload } from '@/lib/finops/costGroups'

afterEach(() => vi.restoreAllMocks())

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

  it('joins every registered workload identity to its actual source owner', () => {
    for (const service of getRegistry()) {
      const expectedId = service.container.replace(/^openbank-/, '')
      const declaration = parse(readFileSync(resolve('..', service.container, 'governance.yaml'), 'utf8'))
      expect(governanceServiceIdForWorkload(k8sNameOf(service)), service.container).toBe(expectedId)
      expect(domainForService(k8sNameOf(service)), service.container).toBe(declaration.dataDomain)
    }
  })

  it('does not assign an ambiguous workload name to an arbitrary business owner', () => {
    const ledger = getRegistry().find(service => service.id === 'ledger')!
    const wealth = getRegistry().find(service => service.id === 'wealth')!
    vi.spyOn(fleet, 'getRegistry').mockReturnValue([ledger, { ...wealth, k8sName: k8sNameOf(ledger) }])
    expect(governanceServiceIdForWorkload(k8sNameOf(ledger))).toBeUndefined()
    expect(domainForService(k8sNameOf(ledger))).toBeUndefined()
  })

  it('resolves the declared scanner workload alias without inventing other suffix aliases', () => {
    expect(governanceServiceIdForWorkload('security-scanner-service')).toBe('security-scanner')
    expect(domainForService('security-scanner-service')).toBe(domainForService('security-scanner'))
    expect(domainForService('openbank-security-scanner-service')).toBe('platform')
    expect(governanceServiceIdForWorkload('unregistered-service')).toBe('unregistered-service')
    expect(domainForService('unregistered-service')).toBeUndefined()
  })

  it('canonicalServiceId strips a leading openbank- prefix', () => {
    expect(canonicalServiceId('openbank-ledger-service')).toBe('ledger-service')
    expect(canonicalServiceId('ledger-service')).toBe('ledger-service')
  })
})
