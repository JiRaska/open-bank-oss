// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// BPMN manifests must validate against BpmnProcessSchema and be internally
// consistent (flow endpoints reference real steps, ids are unique, async edges
// carry a topic). loadBpmnProcess() throws (Zod) on a malformed/drifted
// manifest, so this fails the same way `next build` does — the manifest is the
// contract.
import { describe, it, expect } from 'vitest'
import { existsSync } from 'node:fs'
import { resolve } from 'node:path'
import { loadAllBpmnProcesses, listBpmnSlugs, loadBpmnProcess } from '@/lib/docs/bpmn/load'

describe('bpmn manifests', () => {
  it('every manifest validates against BpmnProcessSchema', () => {
    const procs = loadAllBpmnProcesses() // throws on an invalid manifest
    expect(procs.length).toBeGreaterThan(0)
  })

  it('links every process to existing implementation code', () => {
    for (const entry of loadAllBpmnProcesses()) {
      expect(entry.sourceRefs.length, entry.slug).toBeGreaterThan(0)
      for (const ref of entry.sourceRefs) {
        expect(existsSync(resolve(process.cwd(), '..', ref)), `${entry.slug}: ${ref}`).toBe(true)
      }
    }
  })

  it('ships the canonical banking processes', () => {
    const slugs = listBpmnSlugs()
    for (const expected of [
      'account-opening', 'sepa-payment', 'kyc-process', 'aml-screening',
      'international-wire', 'account-closure',
      'domestic-payment', 'fx-conversion', 'settlement-booking',
      'business-onboarding-timers', 'campaign-journey',
    ]) {
      expect(slugs, expected).toContain(expected)
    }
  })

  it('maps new money workflows to their owning and downstream services', () => {
    for (const [slug, required] of [
      ['domestic-payment', ['domestic-payment', 'transaction-service']],
      ['fx-conversion', ['fx-service', 'sanctions-service']],
      ['settlement-booking', ['settlement-service', 'balance-service', 'ledger-service']],
      ['business-onboarding-timers', ['kyb-service']],
      ['campaign-journey', ['campaign-service', 'consent-service']],
    ] as const) {
      const process = loadBpmnProcess(slug)
      const mapped = new Set(process.steps.flatMap((step) => step.relatedServices ?? []))
      for (const service of required) {
        expect(process.services, `${slug}: listed ${service}`).toContain(service)
        expect(mapped.has(service), `${slug}: mapped ${service}`).toBe(true)
      }
    }
  })

  it('keeps step ids unique within a process', () => {
    for (const p of loadAllBpmnProcesses()) {
      const ids = p.steps.map((s) => s.id)
      expect(new Set(ids).size, p.slug).toBe(ids.length)
    }
  })

  it('only references existing steps in flows', () => {
    for (const p of loadAllBpmnProcesses()) {
      const ids = new Set(p.steps.map((s) => s.id))
      for (const f of p.flows) {
        expect(ids.has(f.from), `${p.slug}: flow.from ${f.from}`).toBe(true)
        expect(ids.has(f.to), `${p.slug}: flow.to ${f.to}`).toBe(true)
      }
    }
  })

  it('gives every async edge a Kafka topic', () => {
    for (const p of loadAllBpmnProcesses()) {
      for (const f of p.flows.filter((x) => x.kind === 'async')) {
        expect(f.topic, `${p.slug}: async ${f.from}->${f.to}`).toBeTruthy()
      }
    }
  })

  it('loads a single process by slug', () => {
    const auth = loadBpmnProcess('account-opening')
    expect(auth.slug).toBe('account-opening')
    expect(auth.steps.length).toBeGreaterThan(0)
  })
})
