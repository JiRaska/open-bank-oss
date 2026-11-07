// SPDX-License-Identifier: Apache-2.0
import { readFileSync, readdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const dockerfile = readFileSync(resolve(process.cwd(), 'Dockerfile'), 'utf8')
  .split('\n')
  .filter(line => !/^\s*#/.test(line))
  .join('\n')

describe('admin image governance inputs', () => {
  it('stages the complete governance registry tree for prebuild generators', () => {
    expect(dockerfile).toContain(
      'cp -R /repo/openbank-libs/governance /governance-src/openbank-libs/governance',
    )
    expect(dockerfile).toContain(
      'COPY --from=governance-collector /governance-src/openbank-libs/governance /openbank-libs/governance',
    )
    expect(dockerfile).toContain(
      'test -s /governance-src/openbank-libs/governance/rules.yaml',
    )
    expect(dockerfile).toContain(
      'test -s /governance-src/openbank-libs/governance/card-capabilities.yaml',
    )
    expect(dockerfile).not.toContain(
      'COPY --from=governance-collector /governance-src/openbank-libs/governance/rules.yaml',
    )
  })
})

// Next type-checks e2e files in the image; Docker excludes src/test.
describe('admin image E2E inputs', () => {
  it('keeps E2E imports outside the Docker-excluded unit-test tree', () => {
    const root = process.cwd()
    const files = readdirSync(resolve(root, 'e2e'), { recursive: true })
      .filter(file => /\.tsx?$/.test(file))
    const excluded = resolve(root, 'src/test')
    const invalid: string[] = []
    for (const file of files) {
      const path = resolve(root, 'e2e', file)
      const source = readFileSync(path, 'utf8')
      for (const match of source.matchAll(/(?:from\s*|import\s*\()\s*['"]([^'"]+)['"]/g)) {
        const specifier = match[1]
        const imported = specifier.startsWith('.')
          ? resolve(path, '..', specifier)
          : specifier.startsWith('@/') ? resolve(root, 'src', specifier.slice(2)) : null
        if (imported === excluded || imported?.startsWith(`${excluded}/`)) {
          invalid.push(`${file}: ${specifier}`)
        }
      }
    }
    expect(files.length).toBeGreaterThan(0)
    expect(invalid).toEqual([])
  })
})
