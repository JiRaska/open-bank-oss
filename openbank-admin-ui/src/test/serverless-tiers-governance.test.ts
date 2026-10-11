// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { parseDocument } from 'yaml'
import moneyPathT0 from '../../finops-money-path.json'
import { serverlessTierFor } from '@/lib/finops/serverlessTiers'

describe('governed serverless tier badges', () => {
  it('keeps every money-path service always on, including newly classified services', () => {
    for (const id of moneyPathT0) {
      expect(serverlessTierFor(id), id).toEqual({ tier: 'T0', status: 'always_on', adr: 'ADR-0057' })
    }
    expect(moneyPathT0).toContain('pension')
  })

  it('retains explicit non-money-path assignments and no badge for unknown services', () => {
    const source = readFileSync(path.resolve(process.cwd(), '../openbank-libs/governance/rules.yaml'), 'utf8')
    const rules = parseDocument(source, { uniqueKeys: true })
    expect(rules.errors).toHaveLength(0)
    const declared = rules.toJS()?.finops_tiers?.declared
    expect(serverlessTierFor('product-catalog')).toEqual({
      tier: declared?.['openbank-product-catalog'],
      status: 'always_on',
      adr: 'ADR-0083',
    })
    expect(serverlessTierFor('notification')?.tier).toBe(declared?.['openbank-notification-service'])
    expect(serverlessTierFor('unknown-service')).toBeUndefined()
  })
})
