// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// BPMN manifests must validate against BpmnProcessSchema and be internally
// consistent (flow endpoints reference real steps, ids are unique, async edges
// carry a topic). loadBpmnProcess() throws (Zod) on a malformed/drifted
// manifest, so this fails the same way `next build` does — the manifest is the
// contract.
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, it, expect } from 'vitest'
import { parse } from 'yaml'
import { loadAllBpmnProcesses, listBpmnSlugs, loadBpmnProcess } from '@/lib/docs/bpmn/load'

const MODULE_ALIASES: Record<string, string> = {
  'sepa-payment': 'openbank-sepa-payment',
}

function normalizeRoute(route: string): string {
  return route.replace(/\{[^}]+\}/g, '{}')
}

function serviceModule(service: string): string {
  return MODULE_ALIASES[service] ?? `openbank-${service}`
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

  it('maps every documented API to an operation in a related service OpenAPI contract', () => {
    const repoRoot = path.resolve(process.cwd(), '..')
    const processes = loadAllBpmnProcesses()
    const contracts = new Map<string, { prefix: string; paths: Record<string, Record<string, unknown>> }>()
    for (const process of processes) {
      for (const service of process.services) {
        const serviceModuleName = serviceModule(service)
        if (contracts.has(serviceModuleName)) continue
        const specPath = path.join(repoRoot, serviceModuleName, 'src/main/resources/openapi.yaml')
        try {
          const spec = parse(readFileSync(specPath, 'utf8')) as {
            paths?: Record<string, Record<string, unknown>>
            servers?: Array<{ url?: string }>
          }
          const prefix = spec.servers?.[0]?.url ? new URL(spec.servers[0].url).pathname.replace(/\/$/, '') : ''
          contracts.set(serviceModuleName, { prefix, paths: spec.paths ?? {} })
        } catch {
          contracts.set(serviceModuleName, { prefix: '', paths: {} })
        }
      }
    }

    const errors: string[] = []
    for (const process of processes) {
      for (const step of process.steps) {
        for (const api of step.apis ?? []) {
          const match = /^(GET|POST|PUT|PATCH|DELETE)\s+(\/\S+)$/.exec(api)
          if (!match) {
            errors.push(`${process.slug}/${step.id}: malformed API reference ${api}`)
            continue
          }
          const [, method, route] = match
          const services = step.relatedServices ?? []
          const declared = services.some((service) => {
            const contract = contracts.get(serviceModule(service)) ?? { prefix: '', paths: {} }
            return Object.entries(contract.paths).some(([specRoute, operations]) =>
              normalizeRoute(`${contract.prefix}${specRoute}`) === normalizeRoute(route) &&
              Object.keys(operations ?? {}).some((operation) => operation.toUpperCase() === method),
            )
          })
          if (!declared) errors.push(`${process.slug}/${step.id}: ${api} is not in a related service OpenAPI contract (${services.join(', ') || 'no related services'})`)
        }
      }
    }
    expect(errors, errors.join('\n')).toEqual([])
  })
})
