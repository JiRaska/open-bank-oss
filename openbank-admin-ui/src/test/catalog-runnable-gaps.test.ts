// SPDX-License-Identifier: Apache-2.0
import { execFileSync } from 'node:child_process'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'

const repoRoot = path.resolve(__dirname, '../../..')
const generator = path.join(repoRoot, 'openbank-admin-ui/scripts/generate-catalog.mjs')
const roots: string[] = []

afterEach(() => { for (const root of roots.splice(0)) rmSync(root, { recursive: true, force: true }) })

describe('catalog evidence gaps for runnable apps', () => {
  it('reports missing version, release registration, and governance for a suffixless runnable app', () => {
    const repo = mkdtempSync(path.join(tmpdir(), 'catalog-runnable-gaps-'))
    roots.push(repo)
    const moduleDir = path.join(repo, 'openbank-analytics-sink')
    mkdirSync(path.join(moduleDir, 'src', 'main'), { recursive: true })
    writeFileSync(path.join(moduleDir, 'build.gradle.kts'),
      'plugins { id("openbank.quarkus-service") }\nimplementation(project(":openbank-libs-runtime"))\n')
    mkdirSync(path.join(repo, 'openbank-libs', 'governance'), { recursive: true })
    writeFileSync(path.join(repo, 'openbank-libs/governance/rules.yaml'), 'money_path_services: []\n')
    writeFileSync(path.join(repo, 'release-please-config.json'), '{"packages":{}}\n')
    writeFileSync(path.join(repo, '.release-please-manifest.json'), '{}\n')
    const output = path.join(repo, 'catalog.json')
    execFileSync('node', [generator, '--repo', repo, '--out', output], { stdio: 'pipe' })
    const catalog = JSON.parse(readFileSync(output, 'utf8'))
    const app = catalog.services.find((service: { name: string }) => service.name === 'openbank-analytics-sink')
    expect(app.runnable).toBe(true)
    expect(app.gaps).toContain('missing version.txt')
    expect(app.gaps).toContain('runnable backend component is not release-registered')
    expect(app.gaps).toContain('runnable backend component has no governance.yaml')
  })
})
