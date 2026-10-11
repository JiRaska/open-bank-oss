// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { loadMoneyPathServices, parseMoneyPathServices } from '@/lib/governance/moneyPath'
import { loadFlagCatalog } from '@/lib/governance/flags'
import { loadThreatModelIndex, missingThreatModels } from '@/lib/governance/docs'

describe('money-path classifications use the governance rule', () => {
  it('reads the current declaration, including services absent from the old UI lists', async () => {
    const services = await loadMoneyPathServices()
    expect(services.size).toBeGreaterThan(14)
    expect(services.has('ledger-service')).toBe(true)
    expect(services.has('card-processing-service')).toBe(true)
  })

  it('updates membership from the source and rejects missing or malformed declarations', () => {
    expect([...parseMoneyPathServices('money_path_services:\n  - openbank-example-service\n')]).toEqual(['example-service'])
    for (const bad of ['{}', 'money_path_services: []', 'money_path_services: [foo]',
      'money_path_services: [openbank-ledger-service, openbank-ledger-service]']) {
      expect(() => parseMoneyPathServices(bad)).toThrow()
    }
  })

  it('classifies flags and threat models from the same changed declaration', async () => {
    const root = await mkdtemp(path.join(tmpdir(), 'governance-money-path-'))
    const previous = {
      docs: process.env.OPENBANK_GOVERNANCE_DOCS,
      flags: process.env.OPENBANK_FLAGS_BUNDLE,
    }
    try {
      const docs = path.join(root, 'governance')
      const flags = path.join(root, 'flags')
      await mkdir(path.join(docs, 'threat-models'), { recursive: true })
      await mkdir(flags)
      await writeFile(path.join(docs, 'rules.yaml'), 'money_path_services:\n  - openbank-card-processing-service\n')
      await writeFile(path.join(docs, 'threat-models', 'openbank-card-processing-service.md'), '# Card processing\n')
      await writeFile(path.join(flags, 'card-processing-service.yaml'), [
        'metadata:', '  labels:', '    app.kubernetes.io/name: card-processing-service',
        'data:', '  flags.json: |', '    {"flags":{"sample":{"state":"ENABLED"}}}',
      ].join('\n'))
      process.env.OPENBANK_GOVERNANCE_DOCS = docs
      process.env.OPENBANK_FLAGS_BUNDLE = flags
      expect((await loadFlagCatalog()).map(flag => flag.classification)).toEqual(['money-path'])
      expect((await loadThreatModelIndex()).map(model => model.moneyPath)).toEqual([true])
      expect(await missingThreatModels()).toEqual([])
      await writeFile(path.join(docs, 'rules.yaml'), 'money_path_services:\n  - openbank-ledger-service\n')
      expect((await loadFlagCatalog()).map(flag => flag.classification)).toEqual(['feature'])
      expect((await loadThreatModelIndex()).map(model => model.moneyPath)).toEqual([false])
      expect(await missingThreatModels()).toEqual(['ledger-service'])
    } finally {
      if (previous.docs === undefined) delete process.env.OPENBANK_GOVERNANCE_DOCS
      else process.env.OPENBANK_GOVERNANCE_DOCS = previous.docs
      if (previous.flags === undefined) delete process.env.OPENBANK_FLAGS_BUNDLE
      else process.env.OPENBANK_FLAGS_BUNDLE = previous.flags
      await rm(root, { recursive: true, force: true })
    }
  })
})
