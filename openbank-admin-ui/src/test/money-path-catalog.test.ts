// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { afterEach, describe, expect, it } from 'vitest'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { parse as parseYaml, parseAllDocuments } from 'yaml'
import { moneyPathAvailabilitySlo, moneyPathServices } from '@/lib/governance/moneyPath'
import { loadFlagCatalog } from '@/lib/governance/flags'
import { loadThreatModelIndex, missingThreatModels } from '@/lib/governance/docs'

const priorCatalog = process.env.OPENBANK_CATALOG
const priorFlagsBundle = process.env.OPENBANK_FLAGS_BUNDLE
const temporary: string[] = []

function catalog(services: unknown): void {
  const dir = mkdtempSync(path.join(os.tmpdir(), 'money-path-catalog-'))
  temporary.push(dir)
  const file = path.join(dir, 'catalog.json')
  writeFileSync(file, JSON.stringify({ services }))
  process.env.OPENBANK_CATALOG = file
}

afterEach(() => {
  if (priorCatalog === undefined) delete process.env.OPENBANK_CATALOG
  else process.env.OPENBANK_CATALOG = priorCatalog
  if (priorFlagsBundle === undefined) delete process.env.OPENBANK_FLAGS_BUNDLE
  else process.env.OPENBANK_FLAGS_BUNDLE = priorFlagsBundle
  for (const dir of temporary.splice(0)) rmSync(dir, { recursive: true, force: true })
})

describe('money-path classification from the generated catalog', () => {
  it('takes the set from catalog moneyPath fields, including newly added services', () => {
    catalog([
      { name: 'openbank-ledger-service', moneyPath: true },
      { name: 'openbank-sdd-service', moneyPath: true },
      { name: 'openbank-notification-service', moneyPath: false },
    ])
    expect([...moneyPathServices()].sort()).toEqual(['ledger-service', 'sdd-service'])
  })

  it('does not silently downgrade when classification is missing', () => {
    catalog([{ name: 'openbank-ledger-service' }])
    expect(() => moneyPathServices()).toThrow(/classification/)
    catalog([{ name: 'openbank-ledger-service', moneyPath: false }])
    expect(() => moneyPathServices()).toThrow(/no money-path/)
  })

  it('the generated catalog agrees exactly with the authoritative rules list', () => {
    const repo = path.resolve(__dirname, '../../..')
    const rules = parseYaml(readFileSync(path.join(repo, 'openbank-libs/governance/rules.yaml'), 'utf-8')) as {
      money_path_services: string[]; slo: { availability_target: string; availability_window: string }
    }
    const generated = JSON.parse(readFileSync(path.join(repo, 'openbank-admin-ui/catalog.json'), 'utf-8')) as {
      services: Array<{ name: string; moneyPath: boolean }>
    }
    catalog(generated.services)
    expect([...moneyPathServices()].sort()).toEqual(rules.money_path_services.map(name => name.replace(/^openbank-/, '')).sort())
  })

  it('declares every governed availability SLO with its target and window', () => {
    const repo = path.resolve(__dirname, '../../..')
    const rules = parseYaml(readFileSync(path.join(repo, 'openbank-libs/governance/rules.yaml'), 'utf-8')) as {
      money_path_services: string[]; slo: { availability_target: string; availability_window: string }
    }
    const declarations = parseAllDocuments(readFileSync(path.join(repo,
      'openbank-infra/gitops/components/observability/pyrra-slo-money-path.yaml'), 'utf-8'))
      .map(document => document.toJSON() as { metadata: { name: string } })
      .filter(document => document?.metadata?.name?.endsWith('-availability'))
      .map(document => document.metadata.name).sort()
    const actual = moneyPathAvailabilitySlo()
    expect(actual.names).toEqual(declarations)
    expect(actual.names.length).toBe(rules.money_path_services.length)
    expect(actual.target).toBeCloseTo(Number(rules.slo.availability_target) / 100, 12)
    expect(actual.window).toBe(rules.slo.availability_window)
  })

  it('missing SLO policy cannot be reported as a configured budget', () => {
    catalog([{ name: 'openbank-ledger-service', moneyPath: true }])
    expect(() => moneyPathAvailabilitySlo()).toThrow(/no availability SLO/)
  })

  it('classifies flag rows from the derived set instead of the former fixed 14', async () => {
    catalog([{ name: 'openbank-sdd-service', moneyPath: true }])
    const dir = mkdtempSync(path.join(os.tmpdir(), 'money-path-flags-'))
    temporary.push(dir)
    mkdirSync(path.join(dir, 'bundle'))
    process.env.OPENBANK_FLAGS_BUNDLE = path.join(dir, 'bundle')
    writeFileSync(path.join(dir, 'bundle', 'sdd.yaml'), `metadata:\n  labels:\n    app.kubernetes.io/name: sdd-service\ndata:\n  flags.json: '{"flags":{"debit":{"state":"ENABLED"}}}'\n`)
    expect((await loadFlagCatalog()).map(flag => flag.classification)).toEqual(['money-path'])
  })

  it('the threat-model gap uses the complete derived money-path set', async () => {
    catalog([{ name: 'openbank-sdd-service', moneyPath: true }])
    const models = await loadThreatModelIndex()
    const present = models.some(model => model.service === 'sdd-service')
    expect(models.find(model => model.service === 'sdd-service')?.moneyPath).toBe(present ? true : undefined)
    expect((await missingThreatModels()).includes('sdd-service')).toBe(!present)
  })
})
