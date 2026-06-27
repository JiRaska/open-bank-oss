// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Unit tests for the pure allocation maths (ADR-0062). No I/O — the invariants here are the
// contract the route and UI rely on: Σ byService == allocatable, allocatable + platformOverhead
// == total, byDomain rolls up byService exactly, and flows are fully-loaded (overlap allowed).

import { describe, it, expect } from 'vitest'
import { allocate, isComputeLine, type CostReportLike, type ServiceFootprint } from '@/lib/finops/allocation'
import type { CostGroup } from '@/lib/finops/costGroups'

const report = (services: { name: string; amount: number }[]): CostReportLike => ({
  available: true,
  currency: 'USD',
  periodStart: '2026-05-04',
  periodEnd: '2026-06-03',
  total: services.reduce((s, x) => s + x.amount, 0),
  services,
  collectedAt: '2026-06-03T00:00:00Z',
})

// account-service & ledger-service are `core` in the governance manifest; keycloak is unknown.
const FOOTPRINTS: ServiceFootprint[] = [
  { service: 'account-service', cpuMillis: 250, memMiB: 512 },
  { service: 'ledger-service', cpuMillis: 250, memMiB: 512 },
  { service: 'keycloak', cpuMillis: 250, memMiB: 512 },
]

const GROUPS: CostGroup[] = [
  { id: 'ledger', labelEn: 'Ledger', labelCs: 'Účetnictví', services: ['ledger-service'] },
  { id: 'acct-and-ledger', labelEn: 'Acct+Ledger', labelCs: 'Účet+účto', services: ['account-service', 'ledger-service'] },
]

describe('isComputeLine', () => {
  it('treats EC2 instance hours as compute', () => {
    expect(isComputeLine('Amazon Elastic Compute Cloud - Compute')).toBe(true)
    expect(isComputeLine('EC2 - Compute')).toBe(true)
  })
  it('treats EC2 - Other, EKS control plane and the rest as platform', () => {
    expect(isComputeLine('EC2 - Other')).toBe(false)
    expect(isComputeLine('Amazon Elastic Kubernetes Service')).toBe(false)
    expect(isComputeLine('Amazon Simple Storage Service')).toBe(false)
    expect(isComputeLine('Amazon Elastic Load Balancing')).toBe(false)
  })
})

describe('allocate', () => {
  it('splits the COMPUTE pool by requests and keeps platform overhead separate', () => {
    const r = allocate(
      report([
        { name: 'Amazon Elastic Compute Cloud - Compute', amount: 90 },
        { name: 'Amazon Elastic Kubernetes Service', amount: 10 },
      ]),
      FOOTPRINTS,
      GROUPS,
    )
    expect(r.available).toBe(true)
    expect(r.total).toBe(100)
    expect(r.allocatable).toBe(90)
    expect(r.platformOverhead).toBe(10)
    // three equal footprints -> each gets a third of the 90 compute pool
    expect(r.byService).toHaveLength(3)
    for (const s of r.byService) expect(s.amount).toBeCloseTo(30, 1)
  })

  it('holds the core invariants: Σ byService == allocatable, + overhead == total', () => {
    const r = allocate(
      report([
        { name: 'Amazon Elastic Compute Cloud - Compute', amount: 73.33 },
        { name: 'EC2 - Other', amount: 26.67 },
      ]),
      FOOTPRINTS,
      GROUPS,
    )
    const sumSvc = r.byService.reduce((s, x) => s + x.amount, 0)
    expect(sumSvc).toBeCloseTo(r.allocatable, 1)
    expect(r.allocatable + r.platformOverhead).toBeCloseTo(r.total, 1)
    const sumDom = r.byDomain.reduce((s, x) => s + x.amount, 0)
    expect(sumDom).toBeCloseTo(sumSvc, 1)
  })

  it('rolls services up to their manifest domain; unknown footprint -> platform + unmapped', () => {
    const r = allocate(report([{ name: 'Amazon Elastic Compute Cloud - Compute', amount: 90 }]), FOOTPRINTS, GROUPS)
    const core = r.byDomain.find(d => d.domain === 'core')
    const platform = r.byDomain.find(d => d.domain === 'platform')
    expect(core?.serviceCount).toBe(2)           // account + ledger
    expect(platform?.serviceCount).toBe(1)       // keycloak
    expect(r.unmapped).toEqual(['keycloak'])
  })

  it('computes fully-loaded flows that overlap (Σ flows can exceed total)', () => {
    const r = allocate(report([{ name: 'Amazon Elastic Compute Cloud - Compute', amount: 90 }]), FOOTPRINTS, GROUPS)
    const ledger = r.byFlow.find(f => f.id === 'ledger')
    const both = r.byFlow.find(f => f.id === 'acct-and-ledger')
    expect(ledger?.amount).toBeCloseTo(30, 1)              // ledger-service alone
    expect(both?.amount).toBeCloseTo(60, 1)                // account + ledger
    // ledger-service is in BOTH flows, so the flow sum exceeds the once-counted cost of the
    // services those flows cover (overlap is intentional fully-loaded showback).
    const sumFlows = r.byFlow.reduce((s, f) => s + f.amount, 0)
    const covered = new Set(GROUPS.flatMap(g => g.services))
    const sumCovered = r.byService.filter(s => covered.has(s.service)).reduce((s, x) => s + x.amount, 0)
    expect(sumFlows).toBeGreaterThan(sumCovered)
  })

  it('weights by both cpu and ram (50/50), not by service count', () => {
    const r = allocate(
      report([{ name: 'Amazon Elastic Compute Cloud - Compute', amount: 100 }]),
      [
        { service: 'account-service', cpuMillis: 300, memMiB: 0 },   // all cpu
        { service: 'ledger-service', cpuMillis: 0, memMiB: 300 },    // all ram
      ],
      GROUPS,
    )
    // cpu pool (50) goes entirely to account, ram pool (50) entirely to ledger
    expect(r.byService.find(s => s.service === 'account-service')?.amount).toBeCloseTo(50, 1)
    expect(r.byService.find(s => s.service === 'ledger-service')?.amount).toBeCloseTo(50, 1)
  })

  describe('edge cases', () => {
    it('degrades to available:false when the snapshot is unavailable', () => {
      const r = allocate({ available: false, currency: 'USD', total: 0, services: [] }, FOOTPRINTS, GROUPS)
      expect(r.available).toBe(false)
      expect(r.byService).toEqual([])
    })

    it('puts everything in platform overhead when there is no compute line', () => {
      const r = allocate(report([{ name: 'Amazon Elastic Kubernetes Service', amount: 50 }]), FOOTPRINTS, GROUPS)
      expect(r.allocatable).toBe(0)
      expect(r.platformOverhead).toBe(50)
      expect(r.byService).toEqual([])              // nothing to allocate -> no service rows
    })

    it('never divides by zero when footprints are empty', () => {
      const r = allocate(report([{ name: 'Amazon Elastic Compute Cloud - Compute', amount: 90 }]), [], GROUPS)
      expect(r.allocatable).toBe(90)
      expect(r.byService).toEqual([])
      expect(r.byFlow).toEqual([])
    })

    it('ignores footprints with non-positive requests', () => {
      const r = allocate(
        report([{ name: 'Amazon Elastic Compute Cloud - Compute', amount: 60 }]),
        [
          { service: 'account-service', cpuMillis: 200, memMiB: 200 },
          { service: 'ledger-service', cpuMillis: 0, memMiB: 0 },     // dropped
        ],
        GROUPS,
      )
      expect(r.byService).toHaveLength(1)
      expect(r.byService[0].service).toBe('account-service')
      expect(r.byService[0].amount).toBeCloseTo(60, 1)
    })
  })
})
