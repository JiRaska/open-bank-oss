// SPDX-License-Identifier: Apache-2.0
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { spawnSync } from 'node:child_process'
import YAML from 'yaml'
import { describe, expect, it } from 'vitest'

function generate(missing = false) {
  const temp = mkdtempSync(resolve(tmpdir(), 'catalog-types-'))
  try {
    const document = YAML.parse(readFileSync(resolve(process.cwd(), '../openbank-product-catalog/src/main/resources/openapi.yaml'), 'utf8'))
    document.components.schemas.ClosureRoot = {
      type: 'object', properties: { leaves: { type: 'array', items: { $ref: '#/components/schemas/ClosureLeaf' } } },
    }
    document.components.schemas.ClosureLeaf = {
      type: 'object', properties: { parent: { $ref: `#/components/schemas/${missing ? 'MissingType' : 'ClosureRoot'}` } },
    }
    document.paths['/api/v2/type-closure'] = { post: {
      operationId: 'closureTest',
      requestBody: { content: { 'application/json': { schema: { $ref: '#/components/schemas/ClosureRoot' } } } },
      responses: { 200: { content: { 'application/json': { schema: { $ref: '#/components/schemas/ClosureLeaf' } } } } },
    } }
    const input = resolve(temp, 'openbank-product-catalog/src/main/resources')
    const moduleRoot = resolve(temp, 'openbank-admin-ui')
    mkdirSync(input, { recursive: true })
    mkdirSync(moduleRoot, { recursive: true })
    writeFileSync(resolve(input, 'openapi.yaml'), YAML.stringify(document))
    const result = spawnSync(process.execPath, ['scripts/generate-product-catalog-v2-types.mjs'], {
      cwd: process.cwd(), encoding: 'utf8',
      env: { ...process.env, OPENBANK_REPO_ROOT: temp, OPENBANK_ADMIN_UI_MODULE_ROOT: moduleRoot },
    })
    return { ...result, output: result.status === 0 ? readFileSync(resolve(moduleRoot, 'src/lib/generated/product-catalog-v2.ts'), 'utf8') : '' }
  } finally {
    rmSync(temp, { recursive: true, force: true })
  }
}

describe('catalog OpenAPI type dependency closure', () => {
  it('emits operation schemas, recursive array dependencies and current revision approval types', () => {
    const result = generate()
    expect(result.status, result.stderr).toBe(0)
    for (const name of ['ClosureRoot', 'ClosureLeaf', 'RevisionApprovalRequest', 'RevisionApprovalResponse']) {
      expect(result.output.match(new RegExp(`export type ${name} =`, 'g'))).toHaveLength(1)
    }
    expect(result.output).toContain('Array<ClosureLeaf>')
    expect(result.output).toContain('"parent"?: ClosureRoot')
  })

  it('rejects a dangling transitive reference instead of generating an undeclared TypeScript name', () => {
    const result = generate(true)
    expect(result.status).not.toBe(0)
    expect(result.stderr).toContain('Missing schema reference: #/components/schemas/MissingType')
  })
})

// Execute the workflow's actual path filter against an API-only commit, not a copied list.
it('selects the full UI lane for a backend catalog OpenAPI-only change', () => {
  const workflow = YAML.parse(readFileSync(resolve(process.cwd(), '../.github/workflows/ci.yml'), 'utf8'))
  const detector = workflow.jobs['changes-ui'].steps.find((step: { id?: string }) => step.id === 'check').run
  const command = detector.match(/N=\$\((git diff --name-only[\s\S]*?\| wc -l \| tr -d ' ')\)/)?.[1]
  expect(command).toBeTruthy()
  const temp = mkdtempSync(resolve(tmpdir(), 'catalog-ui-selection-'))
  try {
    const git = (...args: string[]) => {
      const result = spawnSync('git', ['-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
        '-c', 'commit.gpgsign=false', ...args], { cwd: temp, encoding: 'utf8' })
      expect(result.status, result.stderr).toBe(0)
      return result.stdout.trim()
    }
    git('init', '-q')
    const api = resolve(temp, 'openbank-product-catalog/src/main/resources/openapi.yaml')
    mkdirSync(resolve(api, '..'), { recursive: true })
    writeFileSync(api, 'openapi: 3.1.0')
    git('add', 'openbank-product-catalog/src/main/resources/openapi.yaml')
    git('commit', '-qm', 'fixture baseline')
    const base = git('rev-parse', 'HEAD')
    writeFileSync(api, 'openapi: 3.1.1')
    git('add', 'openbank-product-catalog/src/main/resources/openapi.yaml')
    git('commit', '-qm', 'fixture API change')
    const result = spawnSync('sh', ['-c', command!], { cwd: temp, encoding: 'utf8', env: { ...process.env, BASE: base } })
    expect(result.status, result.stderr).toBe(0)
    expect(Number(result.stdout.trim())).toBe(1)
  } finally {
    rmSync(temp, { recursive: true, force: true })
  }
})
