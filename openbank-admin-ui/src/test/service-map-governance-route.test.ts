// SPDX-License-Identifier: Apache-2.0

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const { readFile } = vi.hoisted(() => ({ readFile: vi.fn() }))
vi.mock('fs/promises', async importOriginal => {
  const actual = await importOriginal<typeof import('fs/promises')>()
  const scopedReadFile = (...args: Parameters<typeof actual.readFile>) =>
    String(args[0]).endsWith('__service-map-governance-test__.json') ? readFile(...args) : actual.readFile(...args)
  return { ...actual, default: { ...actual, readFile: scopedReadFile }, readFile: scopedReadFile }
})

import { GET } from '@/app/api/services/governance/route'

describe('Service Map governance BFF evidence', () => {
  beforeEach(() => {
    readFile.mockReset()
    process.env.OPENBANK_GOVERNANCE = '/tmp/__service-map-governance-test__.json'
  })

  afterEach(() => delete process.env.OPENBANK_GOVERNANCE)

  it('marks a missing snapshot unavailable', async () => {
    readFile.mockRejectedValue(new Error('missing'))
    expect(await (await GET()).json()).toMatchObject({ available: false, items: [], byService: {} })
  })

  it('marks a malformed snapshot unavailable instead of verified empty', async () => {
    readFile.mockResolvedValue(JSON.stringify({ schema: 'unexpected' }))
    expect(await (await GET()).json()).toMatchObject({ available: false, items: [], byService: {} })
  })

  it('keeps a valid empty snapshot distinct', async () => {
    readFile.mockResolvedValue(JSON.stringify({ services: [] }))
    expect(await (await GET()).json()).toMatchObject({ available: true, items: [], byService: {} })
  })
})
