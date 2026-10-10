// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import { postgresEol, postgresPins } from '@/lib/postgres-platform-lifecycle'
import { derivePlatformVersions } from '../../scripts/lib/platform-versions.mjs'

const repo = path.resolve(__dirname, '..', '..', '..')

describe('PostgreSQL platform lifecycle', () => {
  it('shows every distinct GitOps image pin, including the minority version', () => {
    const pins = postgresPins(derivePlatformVersions(repo).components.postgres)
    expect(pins).toEqual(expect.arrayContaining([
      expect.objectContaining({ version: '18.6' }),
      expect.objectContaining({ version: '18.1' }),
    ]))
    expect(pins.reduce((sum, pin) => sum + pin.manifests, 0)).toBeGreaterThan(1)
  })

  it('uses the checked-in generated lifecycle source for the pinned major', () => {
    const snapshot = JSON.parse(readFileSync(path.join(repo, 'openbank-admin-ui/infra-lifecycle.json'), 'utf8'))
    const cycle = snapshot.components.find((c: { id: string }) => c.id === 'postgres')
      .lifecycle.cycles.find((c: { cycle: string }) => c.cycle === '18')
    expect(postgresEol('18.6', snapshot)).toBe(cycle.eol)
    expect(postgresEol('18.1', snapshot)).toBe(cycle.eol)
  })

  it('fails closed when a lifecycle row is missing or malformed', () => {
    const base = { schema: 'openbank.infra-lifecycle/v1', components: [{ id: 'postgres', lifecycle: { cycles: [] } }] }
    expect(postgresEol('18.6', base)).toBeNull()
    expect(postgresEol('18.6', { ...base, components: [{ id: 'postgres', lifecycle: { cycles: [{ cycle: '18', eol: '2030-02-30' }] } }] })).toBeNull()
    expect(postgresEol('18.6', null)).toBeNull()
    expect(postgresPins(null)).toEqual([])
  })
})
