// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// BPMN manifests must validate against BpmnProcessSchema and be internally
// consistent (flow endpoints reference real steps, ids are unique, async edges
// carry a topic). loadBpmnProcess() throws (Zod) on a malformed/drifted
// manifest, so this fails the same way `next build` does — the manifest is the
// contract.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { parse } from 'yaml'
import { loadAllBpmnProcesses, listBpmnSlugs, loadBpmnProcess } from '@/lib/docs/bpmn/load'
import type { BpmnProcess } from '@/lib/docs/bpmn/schema'

function unservedAccountRoutes(process: BpmnProcess, paths: Record<string, Record<string, unknown>>): string[] {
  return process.steps.flatMap((step) =>
    step.relatedServices?.includes('account-service')
      ? (step.apis ?? []).filter((api) => {
          const [method, route] = api.split(' ')
          return !method || !route || !paths[route]?.[method.toLowerCase()]
        })
      : [],
  )
}

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

  it('keeps account-opening account calls on published account-service routes', () => {
    const diagram = loadBpmnProcess('account-opening')
    const spec = parse(readFileSync(join(process.cwd(), '..', 'openbank-account-service',
      'src', 'main', 'resources', 'openapi.yaml'), 'utf8')) as {
      paths: Record<string, Record<string, unknown>>
    }
    expect(unservedAccountRoutes(diagram, spec.paths)).toEqual([])
    expect(diagram.steps.find((step) => step.id === 'create')?.apis).toContain('POST /api/v1/accounts')
    expect(diagram.steps.some((step) => step.id === 'iban')).toBe(false)
    expect(diagram.flows.find((flow) => flow.from === 'create' && flow.to === 'notify')).toMatchObject({
      kind: 'async', topic: 'openbank.notification.requests',
    })
    expect(diagram.flows.some((flow) => flow.from === 'iban' || flow.to === 'iban')).toBe(false)

    // The former BPMN route must fail this check against the real published spec.
    const stale = {
      ...diagram,
      steps: [...diagram.steps, {
        id: 'iban', type: 'task' as const, x: 740, y: 100, label: 'IBAN',
        apis: ['POST /api/v1/accounts/{id}/iban'], relatedServices: ['account-service'],
      }],
    }
    expect(unservedAccountRoutes(stale, spec.paths)).toEqual(['POST /api/v1/accounts/{id}/iban'])
  })
})
