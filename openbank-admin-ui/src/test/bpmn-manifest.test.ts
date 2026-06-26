// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

// BPMN manifests must validate against BpmnProcessSchema and be internally
// consistent (flow endpoints reference real steps, ids are unique, async edges
// carry a topic). loadBpmnProcess() throws (Zod) on a malformed/drifted
// manifest, so this fails the same way `next build` does — the manifest is the
// contract.
import { describe, it, expect } from 'vitest'
import { loadAllBpmnProcesses, listBpmnSlugs, loadBpmnProcess } from '@/lib/docs/bpmn/load'

describe('bpmn manifests', () => {
  it('every manifest validates against BpmnProcessSchema', () => {
    const procs = loadAllBpmnProcesses() // throws on an invalid manifest
    expect(procs.length).toBeGreaterThan(0)
  })

  it('ships the canonical banking processes', () => {
    const slugs = listBpmnSlugs()
    for (const expected of [
      'account-opening', 'sepa-payment', 'kyc-process', 'aml-screening',
      'international-wire', 'account-closure',
    ]) {
      expect(slugs, expected).toContain(expected)
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
