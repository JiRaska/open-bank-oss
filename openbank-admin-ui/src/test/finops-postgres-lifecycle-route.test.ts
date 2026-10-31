// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

vi.mock('@/lib/live-platform-versions', () => ({
  getPlatformView: vi.fn(async () => ({
    items: {
      kubernetes: { declared: '1.36', live: null },
      postgres: { declared: '18.6', live: [{ version: '18.6', count: 50 }, { version: '18.1', count: 1 }] },
      kafka: { declared: null, live: null },
      strimziOperator: { declared: null, live: null },
      apicurio: { declared: null, live: null },
      valkey: { declared: null, live: null },
    },
  })),
  getEksLifecycle: vi.fn(async () => ({
    source: 'snapshot',
    lifecycle: {
      _meta: { last_refreshed: '2026-10-10' },
      versions: { '1.36': { eks_release: '2026-01-01', end_of_standard_support: '2027-12-31', end_of_extended_support: '2028-12-31' } },
    },
  })),
}))

import { GET } from '@/app/api/finops/lifecycle/route'

describe('FinOps platform lifecycle route', () => {
  it('shows both PostgreSQL GitOps pins and their sourced major EOL', async () => {
    const response = await GET()
    expect(response.status).toBe(200)
    const body = await response.json()
    const postgres = body.components.filter((c: { name: string }) => c.name.startsWith('PostgreSQL'))
    const snapshot = JSON.parse(readFileSync(path.resolve(__dirname, '..', '..', 'infra-lifecycle.json'), 'utf8'))
    const eol = snapshot.components.find((c: { id: string }) => c.id === 'postgres')
      .lifecycle.cycles.find((c: { cycle: string }) => c.cycle === '18').eol
    expect(postgres).toEqual(expect.arrayContaining([
      expect.objectContaining({ version: '18.6', standardEnd: eol }),
      expect.objectContaining({ version: '18.1', standardEnd: eol }),
    ]))
    expect(postgres).toHaveLength(2)
    expect(postgres.find((c: { version: string }) => c.version === '18.6').name).toContain('50 running pods')
    expect(postgres.find((c: { version: string }) => c.version === '18.1').name).toContain('1 GitOps manifest')
  })
})
