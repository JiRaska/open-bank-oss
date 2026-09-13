// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it } from 'vitest'

const originalBuildSha = process.env.BUILD_GIT_SHA

afterEach(() => {
  if (originalBuildSha === undefined) delete process.env.BUILD_GIT_SHA
  else process.env.BUILD_GIT_SHA = originalBuildSha
})

describe('public build attestation', () => {
  it('exposes only the minimal build identity with no-store semantics', async () => {
    process.env.BUILD_GIT_SHA = 'abcdef123456'
    const { GET } = await import('@/app/.well-known/openbank-build-attestation/route')

    const response = await GET()

    expect(response.headers.get('cache-control')).toBe('no-store')
    expect(response.headers.get('x-content-type-options')).toBe('nosniff')
    expect(await response.json()).toEqual({ component: 'admin-ui', gitSha: 'abcdef123456' })
  })
})
