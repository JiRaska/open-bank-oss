// SPDX-License-Identifier: Apache-2.0
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { runnableModules } from '../../scripts/service-inventory.mjs'

const roots: string[] = []
function fixture(): string {
  const root = mkdtempSync(path.join(tmpdir(), 'service-inventory-'))
  roots.push(root)
  return root
}
function module(root: string, name: string, build: string): void {
  const dir = path.join(root, name)
  mkdirSync(path.join(dir, 'src', 'main'), { recursive: true })
  writeFileSync(path.join(dir, 'build.gradle.kts'), build)
}

afterEach(() => { for (const root of roots.splice(0)) rmSync(root, { recursive: true, force: true }) })

describe('shared runnable backend inventory', () => {
  it('includes suffixless Quarkus apps and excludes UI, library, and spec-only directories', () => {
    const root = fixture()
    module(root, 'openbank-product-catalog', 'plugins { id("openbank.quarkus-service") }\nimplementation(project(":openbank-libs-runtime"))')
    module(root, 'openbank-ordinary-service', 'plugins { id("openbank.quarkus-service") }\nimplementation(project(":openbank-libs"))')
    module(root, 'openbank-ui-helper', 'plugins { id("org.jetbrains.kotlin.jvm") }')
    mkdirSync(path.join(root, 'openbank-spec-only', 'src', 'main'), { recursive: true })
    expect(runnableModules(root).map(row => row.name)).toEqual([
      'openbank-ordinary-service', 'openbank-product-catalog',
    ])
    expect(runnableModules(root).find(row => row.name === 'openbank-product-catalog')?.short).toBe('product-catalog')
  })

  it('includes directly applied Quarkus apps without a service suffix', () => {
    const root = fixture()
    module(root, 'openbank-analytics-sink', 'plugins { alias(libs.plugins.quarkus) }')
    expect(runnableModules(root).map(row => row.short)).toEqual(['analytics-sink'])
  })

  it('fails closed when suffixless and -service modules collapse to the same identity', () => {
    const root = fixture()
    const build = 'plugins { id("openbank.quarkus-service") }\nimplementation(project(":openbank-libs-runtime"))'
    module(root, 'openbank-foo', build)
    module(root, 'openbank-foo-service', build)
    expect(() => runnableModules(root)).toThrow(/short-name collision 'foo'/)
  })
})
