// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// infra-vulns.json ships as a placeholder (`scannedAt: null`, `images: {}`) until a real Grype scan
// populates it. A placeholder is not a scan result: the lifecycle view must say "not scanned", never
// "0 vulnerabilities". The tell that this matters is a placeholder that is NOT empty - a stale
// snapshot with `scannedAt` stripped, or one populated by hand - which used to read as a clean scan.

import { describe, it, expect, afterEach } from 'vitest'
import { mkdtempSync, writeFileSync, rmSync } from 'fs'
import { tmpdir } from 'os'
import path from 'path'

import { GET } from '@/app/api/infra/lifecycle/route'

type View = { vulnScannedAt: string | null; components: { id: string; cve: { scanned: boolean; total: number } }[] }

const dirs: string[] = []
afterEach(() => {
  delete process.env.OPENBANK_INFRA_VULNS
  for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true })
})

async function viewWith(vulns: unknown): Promise<View> {
  const dir = mkdtempSync(path.join(tmpdir(), 'infra-vulns-'))
  dirs.push(dir)
  const file = path.join(dir, 'infra-vulns.json')
  writeFileSync(file, JSON.stringify(vulns))
  process.env.OPENBANK_INFRA_VULNS = file
  const res = await GET()
  expect(res.status).toBe(200)
  return res.json() as Promise<View>
}

describe('infra vulnerability view: placeholder vs scan', () => {
  it('reads "not scanned" for the shipped placeholder', async () => {
    const view = await viewWith({ schema: 'openbank.infra-vulns/v1', scannedAt: null, images: {} })
    expect(view.vulnScannedAt).toBeNull()
    expect(view.components.length).toBeGreaterThan(0)
    expect(view.components.filter(c => c.cve.scanned)).toEqual([])
  })

  it('reads "not scanned" even when a snapshot without scannedAt carries image rows', async () => {
    const probe = await viewWith({ scannedAt: null, images: {} })
    const id = probe.components[0].id
    const view = await viewWith({
      scannedAt: null,
      images: { [id]: { critical: 0, high: 0, medium: 0, low: 0, total: 0, top: [] } },
    })
    expect(view.components.find(c => c.id === id)?.cve.scanned).toBe(false)
  })

  it('control: a snapshot WITH scannedAt reports that component as scanned', async () => {
    // Without this the tests above could pass against a route that never reports scanned at all.
    const probe = await viewWith({ scannedAt: null, images: {} })
    const id = probe.components[0].id
    const view = await viewWith({
      scannedAt: '2026-10-10T00:00:00Z',
      images: { [id]: { critical: 0, high: 0, medium: 0, low: 0, total: 0, top: [] } },
    })
    const c = view.components.find(x => x.id === id)
    expect(c?.cve.scanned).toBe(true)
    expect(c?.cve.total).toBe(0)
  })
})
