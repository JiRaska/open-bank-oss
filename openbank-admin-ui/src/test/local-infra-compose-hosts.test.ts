// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { afterEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { parse as parseYaml } from 'yaml'

afterEach(() => vi.unstubAllEnvs())

describe('local infrastructure probe hosts', () => {
  it('matches container names from the compose topology', async () => {
    vi.stubEnv('SERVICES_HOST', 'container')
    vi.resetModules()
    const { LOCAL_INFRA } = await import('@/lib/infra/probes')
    const composePath = path.resolve(__dirname, '../../../openbank-infra/docker-compose.yml')
    const compose = parseYaml(readFileSync(composePath, 'utf8')) as {
      services: Record<string, { container_name?: string }>
    }

    // Every local endpoint, including OpenBao, must use the container name
    // declared by the topology it is meant to check.
    const checked = LOCAL_INFRA.filter(def => def.probe.kind !== 'absent')
    expect(checked.length).toBe(11)
    for (const def of checked) {
      const expected = compose.services[def.id]?.container_name
      expect(expected, `compose container absent for ${def.id}`).toBeTruthy()
      const host = def.probe.kind === 'tcp' ? def.probe.host : new URL(def.probe.url).hostname
      expect(host, `local probe host for ${def.id}`).toBe(expected)
    }
  })

  it('keeps off-container probes on localhost', async () => {
    vi.stubEnv('SERVICES_HOST', 'localhost')
    vi.resetModules()
    const { LOCAL_INFRA } = await import('@/lib/infra/probes')
    for (const def of LOCAL_INFRA) {
      if (def.probe.kind === 'absent') continue
      const host = def.probe.kind === 'tcp' ? def.probe.host : new URL(def.probe.url).hostname
      expect(host).toBe('localhost')
    }
  })
})
