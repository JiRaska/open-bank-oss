// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict'
import test from 'node:test'
import { registerHooks } from 'node:module'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier === 'yaml' || specifier === 'zod') {
      throw new Error(`non-content evidence publishing must not resolve UI dependency ${specifier}`)
    }
    return nextResolve(specifier, context)
  },
})

test('non-content publisher registry loads without UI schema dependencies', async () => {
  const { DATASETS, publishDataset } = await import('./publish-admin-ui-evidence.mjs')
  assert.deepEqual(Object.keys(DATASETS).sort(), ['content', 'gate-health', 'readiness', 'security-kpis', 'test-intelligence'])

  const directory = mkdtempSync(join(tmpdir(), 'admin-evidence-import-test-'))
  const file = join(directory, 'invalid.json')
  writeFileSync(file, '{}')
  try {
    assert.throws(() => publishDataset({
      dataset: 'readiness', file, bucket: 'fixture-evidence',
      env: { EVIDENCE_SOURCE_SHA: 'a'.repeat(40), GITHUB_RUN_ID: '1', GITHUB_RUN_ATTEMPT: '1' },
    }), /schema validation/)
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
})
