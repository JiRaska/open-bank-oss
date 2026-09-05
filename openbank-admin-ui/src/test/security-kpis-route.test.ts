// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { promises as fs } from 'fs'
import os from 'os'
import path from 'path'

// /api/security/kpis — ADR-0056 envelope over the CI-generated snapshot.
// The three states that must never blur: baked snapshot (available), absent file
// (not_deployed — an older image, NOT "everything is fine"), corrupt file (error).

let tmpDir: string

beforeEach(async () => {
  vi.resetModules()
  tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), 'kpis-'))
})

afterEach(() => {
  delete process.env.OPENBANK_SECURITY_KPIS
})

async function loadGet() {
  const { GET } = await import('@/app/api/security/kpis/route')
  return GET
}

describe('GET /api/security/kpis', () => {
  it('serves the baked snapshot with per-signal availability intact', async () => {
    const snapshot = {
      generatedAt: '2026-09-04T21:00:00Z',
      netpol: { available: true, covered: 59, total: 73, coveragePct: 81, gateGreen: true },
      freshness: { available: false, reason: 'skipped' },
      credentials: { available: true, staticSecrets: 158, withDeadline: 0, overdue: 0 },
    }
    const file = path.join(tmpDir, 'security-kpis.json')
    await fs.writeFile(file, JSON.stringify(snapshot))
    process.env.OPENBANK_SECURITY_KPIS = file

    const res = await (await loadGet())()
    expect(res.status).toBe(200)
    const body = await res.json()
    expect(body.available).toBe(true)
    expect(body.kpis.netpol.coveragePct).toBe(81)
    // A degraded collector must pass through as-is, never flatten the envelope.
    expect(body.kpis.freshness.available).toBe(false)
  })

  it('absent snapshot file is not_deployed, HTTP 200', async () => {
    process.env.OPENBANK_SECURITY_KPIS = path.join(tmpDir, 'does-not-exist.json')
    const res = await (await loadGet())()
    expect(res.status).toBe(200)
    await expect(res.json()).resolves.toEqual({ available: false, reason: 'not_deployed' })
  })

  it('corrupt snapshot file is error, not a 500', async () => {
    const file = path.join(tmpDir, 'security-kpis.json')
    await fs.writeFile(file, '{not json')
    process.env.OPENBANK_SECURITY_KPIS = file
    const res = await (await loadGet())()
    expect(res.status).toBe(200)
    await expect(res.json()).resolves.toMatchObject({ available: false, reason: 'error' })
  })
})
