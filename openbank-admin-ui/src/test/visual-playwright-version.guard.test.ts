// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it } from 'vitest'
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { spawnSync } from 'node:child_process'

const script = readFileSync(resolve('scripts/visual.sh'), 'utf8')
const version = JSON.parse(readFileSync(resolve('package-lock.json'), 'utf8'))
  .packages['node_modules/@playwright/test'].version
const roots: string[] = []
afterEach(() => { for (const root of roots.splice(0)) rmSync(root, { recursive: true, force: true }) })

function run(drift?: string) {
  const root = mkdtempSync(join(tmpdir(), 'visual-version-'))
  roots.push(root)
  const ui = join(root, 'ui')
  const bin = join(root, 'bin')
  mkdirSync(join(ui, 'scripts'), { recursive: true })
  mkdirSync(bin)
  writeFileSync(join(ui, 'scripts/visual.sh'), script)
  const names = ['@playwright/test', 'playwright', 'playwright-core']
  writeFileSync(join(ui, 'package-lock.json'), JSON.stringify({
    packages: Object.fromEntries(names.map(name => [`node_modules/${name}`, {
      version: name === drift ? '0.0.0' : version,
    }])),
  }))
  // These commands record admission only; no image is pulled or executed.
  writeFileSync(join(bin, 'git'), '#!/bin/sh\nprintf ".\\n"\n', { mode: 0o700 })
  writeFileSync(join(bin, 'docker'), '#!/bin/sh\nprintf "DOCKER_ADMITTED\\n"\n', { mode: 0o700 })
  return spawnSync('bash', [join(ui, 'scripts/visual.sh')], {
    cwd: ui, encoding: 'utf8',
    env: { ...process.env, CI: 'true', PATH: `${bin}:${process.env.PATH}` },
  })
}

describe('visual rendering browser version admission', () => {
  it('admits the rendering environment for matching locked versions', () => {
    const result = run()
    expect(result.status).toBe(0)
    expect(result.stdout).toContain('DOCKER_ADMITTED')
  })
  for (const name of ['@playwright/test', 'playwright', 'playwright-core']) {
    it(`rejects ${name} drift before launching Docker`, () => {
      const result = run(name)
      expect(result.status).not.toBe(0)
      expect(result.stderr).toContain(`Visual Playwright image and locked ${name} versions differ`)
      expect(result.stdout).not.toContain('DOCKER_ADMITTED')
    })
  }
})
