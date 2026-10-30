// SPDX-License-Identifier: Apache-2.0
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { discoverAppPageRoutes, readConfiguredPageExtensions } from '../../e2e/helpers/navigation-inventory'

describe('App Router route inventory discovery', () => {
  let root: string
  let appDir: string

  beforeEach(() => {
    root = mkdtempSync(join(tmpdir(), 'openbank-route-inventory-'))
    appDir = join(root, 'src', 'app')
    mkdirSync(appDir, { recursive: true })
  })

  afterEach(() => rmSync(root, { recursive: true, force: true }))

  const addPage = (relativePath: string) => {
    const file = join(appDir, relativePath)
    mkdirSync(join(file, '..'), { recursive: true })
    writeFileSync(file, 'export default function Page() { return null }\n')
  }

  it('discovers every default Next page extension and omits route-group segments', () => {
    addPage('page.tsx')
    addPage('(backoffice)/accounts/page.ts')
    addPage('(backoffice)/(finance)/ledger/page.jsx')
    addPage('services/page.js')

    expect(discoverAppPageRoutes(appDir, ['tsx', 'ts', 'jsx', 'js']))
      .toEqual(['/', '/accounts', '/ledger', '/services'])
  })

  it('excludes private folders and parallel route slots while retaining real descendants', () => {
    addPage('@modal/page.tsx')
    addPage('@modal/(.)photos/[id]/page.tsx')
    addPage('_components/page.tsx')
    addPage('reports/_components/page.tsx')
    addPage('reports/@drawer/page.tsx')
    addPage('reports/@drawer/(.)[id]/page.tsx')
    addPage('reports/page.tsx')
    addPage('reports/(archive)/annual/page.tsx')

    expect(discoverAppPageRoutes(appDir, ['tsx'])).toEqual(['/reports', '/reports/annual'])
  })

  it('fails closed when route groups produce duplicate URLs or interception needs routing context', () => {
    addPage('docs/page.tsx')
    addPage('docs/(documentation)/page.tsx')
    expect(() => discoverAppPageRoutes(appDir, ['tsx']))
      .toThrow('Multiple App Router pages resolve to the same URL: /docs')

    rmSync(join(appDir, 'docs'), { recursive: true, force: true })
    addPage('(.)photos/[id]/page.tsx')
    expect(() => discoverAppPageRoutes(appDir, ['tsx']))
      .toThrow('Unsupported intercepting route in route inventory: (.)photos')
  })

  it('reads default and static configured extensions, and rejects unsupported configuration', () => {
    const config = (source: string) => {
      const file = join(root, 'next.config.mjs')
      writeFileSync(file, source)
      return readConfiguredPageExtensions(file)
    }

    expect(config('const nextConfig = {}; export default nextConfig;')).toEqual(['tsx', 'ts', 'jsx', 'js'])
    expect(config("const nextConfig = { pageExtensions: ['tsx', 'ts'] }; export default nextConfig;"))
      .toEqual(['tsx', 'ts'])
    expect(() => config("const nextConfig = { pageExtensions: ['tsx', 'mdx'] }; export default nextConfig;"))
      .toThrow('Unsupported Next.js pageExtensions in route inventory: mdx')
    expect(() => config('const nextConfig = { pageExtensions: process.env.PAGE_EXTENSIONS }; export default nextConfig;'))
      .toThrow('`pageExtensions` must be a static array for route inventory')
  })

  it('reads the repository Next config using Next default page extensions', () => {
    expect(readConfiguredPageExtensions()).toEqual(['tsx', 'ts', 'jsx', 'js'])
  })
})
