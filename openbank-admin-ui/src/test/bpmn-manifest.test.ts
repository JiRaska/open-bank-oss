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

function unservedRoutes(
  process: BpmnProcess, service: string, paths: Record<string, Record<string, unknown>>,
): string[] {
  return process.steps.flatMap((step) =>
    step.relatedServices?.includes(service)
      ? (step.apis ?? []).filter((api) => {
          const [method, route] = api.split(' ')
          return !method || !route || !paths[route]?.[method.toLowerCase()]
        })
      : [],
  )
}

function servicePaths(service: string): Record<string, Record<string, unknown>> {
  const spec = parse(readFileSync(join(process.cwd(), '..', `openbank-${service}`,
    'src', 'main', 'resources', 'openapi.yaml'), 'utf8')) as {
    paths: Record<string, Record<string, unknown>>
  }
  return spec.paths
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
    const accountPaths = servicePaths('account-service')
    const notificationPaths = servicePaths('notification-service')
    expect(unservedRoutes(diagram, 'account-service', accountPaths)).toEqual([])
    expect(unservedRoutes(diagram, 'notification-service', notificationPaths)).toEqual([])
    expect(diagram.steps.find((step) => step.id === 'create')?.apis).toContain('POST /api/v1/accounts')
    expect(diagram.steps.find((step) => step.id === 'create')?.emits).toEqual([
      'openbank.accounts.account.created', 'openbank.notification.requests',
    ])
    expect(diagram.steps.find((step) => step.id === 'notify')?.apis).toBeUndefined()
    expect(diagram.steps.some((step) => step.id === 'iban')).toBe(false)
    expect(diagram.flows.find((flow) => flow.from === 'create' && flow.to === 'gw2')).toBeTruthy()
    expect(diagram.steps.find((step) => step.id === 'gw2')?.label).toBe('Stav ACTIVE?')
    expect(diagram.flows.find((flow) => flow.from === 'gw2' && flow.to === 'notify')).toMatchObject({
      label: 'Ano', kind: 'async', topic: 'openbank.notification.requests',
    })
    expect(diagram.flows.find((flow) => flow.from === 'gw2' && flow.to === 'pending')?.label).toBe('Ne')
    expect(diagram.steps.find((step) => step.id === 'pending')?.label).toBe('Čeká na aktivaci')
    expect(diagram.flows.some((flow) => flow.from === 'create' && flow.to === 'notify')).toBe(false)
    expect(diagram.flows.some((flow) => flow.from === 'iban' || flow.to === 'iban')).toBe(false)

    // The former BPMN route must fail this check against the real published spec.
    const stale = {
      ...diagram,
      steps: [...diagram.steps, {
        id: 'iban', type: 'task' as const, x: 740, y: 100, label: 'IBAN',
        apis: ['POST /api/v1/accounts/{id}/iban'], relatedServices: ['account-service'],
      }],
    }
    expect(unservedRoutes(stale, 'account-service', accountPaths)).toEqual(['POST /api/v1/accounts/{id}/iban'])
    const staleNotification = {
      ...diagram,
      steps: diagram.steps.map((step) => step.id === 'notify'
        ? { ...step, apis: ['POST /api/v1/notifications'] }
        : step),
    }
    expect(unservedRoutes(staleNotification, 'notification-service', notificationPaths))
      .toEqual(['POST /api/v1/notifications'])
  })

  it('keeps account closure on the implemented close operation and request topic', () => {
    const diagram = loadBpmnProcess('account-closure')
    const accountPaths = servicePaths('account-service')
    const notificationPaths = servicePaths('notification-service')
    expect(unservedRoutes(diagram, 'account-service', accountPaths)).toEqual([])
    expect(unservedRoutes(diagram, 'notification-service', notificationPaths)).toEqual([])
    expect(diagram.steps.find((step) => step.id === 'close')?.apis)
      .toEqual(['POST /api/v1/accounts/{accountId}/close'])
    expect(diagram.steps.find((step) => step.id === 'close')?.emits).toEqual([
      'openbank.accounts.account.status-changed', 'openbank.notification.requests',
    ])
    expect(diagram.steps.find((step) => step.id === 'notify')?.consumes)
      .toEqual(['openbank.notification.requests'])
    expect(diagram.steps.find((step) => step.id === 'notify')?.apis).toBeUndefined()
    expect(diagram.steps.find((step) => step.id === 'reject')?.label).toBe('Uzavření odmítnuto')
    expect(diagram.flows.find((flow) => flow.from === 'gw1' && flow.to === 'notify')).toMatchObject({
      kind: 'async', topic: 'openbank.notification.requests',
    })
    expect(diagram.flows.find((flow) => flow.from === 'gw1' && flow.to === 'reject')?.label).toBe('Ne')
    for (const obsolete of ['transfer', 'freeze', 'ledger', 'archive']) {
      expect(diagram.steps.some((step) => step.id === obsolete)).toBe(false)
    }

    const staleRoutes = ['PUT /api/v1/accounts/{id}/freeze', 'PUT /api/v1/accounts/{id}/archive']
    const stale = {
      ...diagram,
      steps: [...diagram.steps, {
        id: 'old', type: 'task' as const, x: 0, y: 0, label: 'old',
        apis: staleRoutes, relatedServices: ['account-service'],
      }],
    }
    expect(unservedRoutes(stale, 'account-service', accountPaths)).toEqual(staleRoutes)
  })

  it('keeps card issuance on its published route and card event, without invented PIN delivery', () => {
    const diagram = loadBpmnProcess('card-issuance')
    const cardPaths = servicePaths('card-issuance-service')
    const notificationPaths = servicePaths('notification-service')
    expect(unservedRoutes(diagram, 'card-issuance-service', cardPaths)).toEqual([])
    expect(unservedRoutes(diagram, 'notification-service', notificationPaths)).toEqual([])
    expect(diagram.steps.find((step) => step.id === 'issue')?.apis).toEqual(['POST /api/v1/cards'])
    expect(diagram.steps.find((step) => step.id === 'issue')?.emits).toEqual(['card.issued.v1'])
    expect(diagram.steps.some((step) => step.id === 'notify')).toBe(false)
    expect(diagram.flows.find((flow) => flow.from === 'issue' && flow.to === 'end')).toBeTruthy()
    expect(diagram.flows.some((flow) => flow.topic === 'openbank.notification.requests')).toBe(false)

    const staleNotification = {
      ...diagram,
      steps: [...diagram.steps, {
        id: 'notify', type: 'task' as const, x: 600, y: 100, label: 'Notifikace PINu',
        apis: ['POST /api/v1/notifications'], relatedServices: ['notification-service'],
      }],
    }
    expect(unservedRoutes(staleNotification, 'notification-service', notificationPaths))
      .toEqual(['POST /api/v1/notifications'])
  })

  it('keeps daily interest accrual separate from capitalization and ledger credit', () => {
    const diagram = loadBpmnProcess('interest')
    const interestPaths = servicePaths('interest-service')
    expect(unservedRoutes(diagram, 'interest-service', interestPaths)).toEqual([])
    expect(diagram.steps.find((step) => step.id === 'calc')?.apis)
      .toEqual(['POST /api/v1/interest/accrue/all'])
    expect(diagram.flows.find((flow) => flow.from === 'calc' && flow.to === 'end')).toBeTruthy()
    for (const obsolete of ['post', 'balance', 'notify']) {
      expect(diagram.steps.some((step) => step.id === obsolete)).toBe(false)
    }
    expect(diagram.flows.some((flow) => flow.topic === 'openbank.notification.requests')).toBe(false)

    // The former calculate route cannot be used to trigger the real accrual use case.
    const stale = {
      ...diagram,
      steps: diagram.steps.map((step) => step.id === 'calc'
        ? { ...step, apis: ['POST /api/v1/interest/calculate'] }
        : step),
    }
    expect(unservedRoutes(stale, 'interest-service', interestPaths))
      .toEqual(['POST /api/v1/interest/calculate'])
  })
})
