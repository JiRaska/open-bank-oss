// SPDX-License-Identifier: Apache-2.0
import { spawnSync } from 'node:child_process'
import { cpSync, mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const UI_ROOT = process.cwd()
const REPO = resolve(UI_ROOT, '..')
const scripts: Record<string, string> = JSON.parse(readFileSync(join(UI_ROOT, 'package.json'), 'utf8')).scripts
const dockerfile = readFileSync(join(UI_ROOT, 'Dockerfile'), 'utf8')
  .split('\n')
  .filter(line => !/^\s*#/.test(line))
  .join('\n')

const READINESS = 'node scripts/collect-prod-readiness.mjs'

// The image build stage stages governance registries and a few generator inputs at `/`, but no
// backend module. The readiness collector scores every runnable module and refuses an inventory
// missing declared money-path modules, so running it there fails every deploy build (2026-10-20
// to 10-22: no admin-ui image was pushed). The workflow produces prod-readiness.json over the
// full checkout and the runtime stage COPYs that file; the build stage must not run the collector.
describe('admin image prebuild', () => {
  it('the readiness collector cannot run against the image build stage layout', () => {
    const staged = mkdtempSync(join(tmpdir(), 'admin-ui-image-root-'))
    try {
      cpSync(join(REPO, 'openbank-libs', 'governance'), join(staged, 'openbank-libs', 'governance'), { recursive: true })
      const run = spawnSync(process.execPath, [
        join(UI_ROOT, 'scripts', 'collect-prod-readiness.mjs'),
        '--repo', staged, '--out', join(staged, 'out.json'), '--source-sha', '0'.repeat(40),
      ], { encoding: 'utf8' })
      expect(run.status).not.toBe(0)
      expect(run.stderr).toContain('missing from runnable service inventory')
    } finally {
      rmSync(staged, { recursive: true, force: true })
    }
  })

  it('runs every prebuild generator except the readiness collector inside the image', () => {
    expect(scripts['prebuild:image']).toBeTruthy()
    expect(scripts['prebuild:image']).not.toContain('collect-prod-readiness')
    // Local builds keep the collector; the image chain is the rest of the same hook, not a copy.
    expect(scripts.prebuild).toBe(`${READINESS} && npm run prebuild:image`)
  })

  it('the image build RUN uses the image chain and suppresses the full prebuild hook', () => {
    const start = dockerfile.indexOf('--mount=type=secret,id=glitchtip_token')
    const run = dockerfile.slice(start, dockerfile.indexOf('\n\n', start))
    expect(run).toContain('npm run prebuild:image')
    expect(run).toMatch(/npm run build --ignore-scripts\s*$/)
    expect(run).not.toMatch(/npm run build(?! --ignore-scripts)/)
  })
})
