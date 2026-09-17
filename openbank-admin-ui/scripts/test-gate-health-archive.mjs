// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import assert from 'node:assert/strict'
import { execFileSync, spawnSync } from 'node:child_process'
import { existsSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

test('only the exact gate JSON member is read; extra ZIP entries are never extracted', () => {
  const dir = mkdtempSync(path.join(tmpdir(), 'gate-archive-test-'))
  const marker = `gate-archive-escape-${process.pid}-${Date.now()}.json`
  const escaped = path.join(tmpdir(), marker)
  assert.equal(existsSync(escaped), false)
  try {
    const validZip = path.join(dir, 'valid.zip')
    const extraZip = path.join(dir, 'extra.zip')
    const makeZip = String.raw`
import sys, zipfile
valid, extra, marker = sys.argv[1:]
for target, with_extra in ((valid, False), (extra, True)):
    with zipfile.ZipFile(target, 'w') as archive:
        archive.writestr('gate-results-gitops-api.json', '[{"id":"known","group":"gitops-api","mode":"enforced","status":"ok"}]')
        if with_extra:
            archive.writestr('../' + marker, 'must never be extracted')
`
    execFileSync('python3', ['-c', makeZip, validZip, extraZip, marker])
    const preloader = path.join(dir, 'fake-fetch.mjs')
    writeFileSync(preloader, String.raw`
import { readFileSync, statSync } from 'node:fs'
const zipPath = process.env.FAKE_ZIP_PATH
const created_at = new Date(Date.now() - 60_000).toISOString()
globalThis.fetch = async (url) => {
  const u = String(url)
  if (u.includes('/ci.yml/runs?')) return new Response(JSON.stringify({ workflow_runs: [{
    id: 7, created_at, status: 'completed', event: 'push', head_branch: 'main', head_sha: 'a'.repeat(40),
  }] }), { status: 200 })
  if (u.includes('/runs/7/jobs?')) return new Response(JSON.stringify({ jobs: [] }), { status: 200 })
  if (u.includes('/runs/7/artifacts?')) return new Response(JSON.stringify({ artifacts: [{
    id: 9, name: 'gate-results-gitops-api', size_in_bytes: statSync(zipPath).size,
  }] }), { status: 200 })
  if (u.includes('/artifacts/9/zip')) return new Response(readFileSync(zipPath), { status: 200 })
  throw new Error('unexpected request')
}
`)
    const collector = fileURLToPath(new URL('./collect-gate-health.mjs', import.meta.url))
    for (const [name, zipPath, expected] of [
      ['valid', validZip, 1], ['extra-entry', extraZip, 0],
    ]) {
      assert.ok(statSync(zipPath).size > 0)
      const out = path.join(dir, `${name}.json`)
      const result = spawnSync(process.execPath, [
        '--import', preloader, collector, '--runs', '1', '--gate-detail-runs', '1', '--out', out,
      ], {
        encoding: 'utf8',
        env: { ...process.env, GITHUB_TOKEN: 'dummy', GITHUB_REPOSITORY: 'JiRaska/open-bank-oss',
          FAKE_ZIP_PATH: zipPath },
      })
      assert.equal(result.status, 0, result.stderr)
      const report = JSON.parse(readFileSync(out, 'utf8'))
      assert.equal(report.available, true)
      assert.equal(report.gateDetailRunsInspected, expected)
      assert.equal(report.gates.length, expected)
    }
    assert.equal(existsSync(escaped), false)
  } finally {
    rmSync(dir, { recursive: true, force: true })
    rmSync(escaped, { force: true })
  }
})
