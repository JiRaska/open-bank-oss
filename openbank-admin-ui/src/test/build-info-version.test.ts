import { describe, expect, it } from 'vitest'
import nextPackage from 'next/package.json'
import { GET } from '@/app/api/build-info/route'

describe('running framework metadata', () => {
  it('reports the installed runtime rather than a historical literal', async () => {
    const response = await GET()
    const body = await response.json()
    expect(body.next).toBe(nextPackage.version)
    expect(body.node).toBe(process.versions.node)
    expect(response.headers.get('cache-control')).toBe('no-store')
  })
})
