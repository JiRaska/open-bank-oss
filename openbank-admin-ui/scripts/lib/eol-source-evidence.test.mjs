// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import test from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync, spawnSync } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  canonicalEolUrl, makeEolEvidence, projectEolCycles, verifyEolEvidence,
} from './eol-source-evidence.mjs'

const product = 'postgresql'
const raw = Buffer.from('[{"cycle":"17","latest":"17.4","eol":"2030-11-08"}]')
const snapshot = () => ({ components: [{ id: 'postgres', eolProduct: product,
  lifecycle: { cycles: projectEolCycles(raw) } }] })
const evidence = () => ({ schema: 'openbank.eol-source-evidence/v1', products: {
  [product]: makeEolEvidence(product, 200, '2026-10-12T10:00:00.000Z', raw),
} })

test('the public URL, wire digest and projection are bound', () => {
  const doc = evidence()
  assert.equal(doc.products[product].url, 'https://endoflife.date/api/postgresql.json')
  assert.match(doc.products[product].responseSha256, /^[0-9a-f]{64}$/)
  assert.doesNotThrow(() => verifyEolEvidence(snapshot(), doc))
  assert.throws(() => canonicalEolUrl('../private'), /invalid EOL product/)
})

test('a swapped response cannot retain the old evidence digest', () => {
  const doc = evidence()
  doc.products[product].responseBodyBase64 = Buffer.from('[{"cycle":"16"}]').toString('base64')
  assert.throws(() => verifyEolEvidence(snapshot(), doc), /digest mismatch/)
})

test('a rehashed swapped response cannot claim the original served projection', () => {
  const doc = evidence()
  doc.products[product] = makeEolEvidence(product, 200, '2026-10-12T10:00:00.000Z',
    Buffer.from('[{"cycle":"16"}]'))
  assert.throws(() => verifyEolEvidence(snapshot(), doc), /projection mismatch/)
})

test('missing or noncanonical source metadata fails closed', () => {
  const doc = evidence()
  doc.products[product].url = 'https://example.invalid/api/postgresql.json'
  assert.throws(() => verifyEolEvidence(snapshot(), doc), /canonical EOL source/)
  delete doc.products[product]
  assert.throws(() => verifyEolEvidence(snapshot(), doc), /product count/)
})

test('HTTP failures cannot carry a successful projection or raw body', () => {
  const doc = evidence()
  doc.products[product].httpStatus = 404
  assert.throws(() => verifyEolEvidence(snapshot(), doc), /unexpected EOL projection/)
  doc.products[product] = makeEolEvidence(product, 404, '2026-10-12T10:00:00.000Z', null)
  assert.throws(() => verifyEolEvidence(snapshot(), doc), /unexpected EOL projection/)
})

test('generator and frozen input sidecar bind the exact fetched bytes', () => {
  const temp = mkdtempSync(path.join(tmpdir(), 'admin-eol-source-'))
  try {
    const preload = path.join(temp, 'fetch.mjs')
    const raw = '[{"cycle":"17","latest":"17.4","eol":"2030-11-08"}]'
    writeFileSync(preload, `globalThis.fetch = async (url) => {
      const response = new Response(${JSON.stringify(raw)}, { status: 200 })
      Object.defineProperty(response, 'url', { value: url })
      return response
    }\n`)
    const adminUi = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
    const repo = path.resolve(adminUi, '..')
    const out = path.join(temp, 'infra-lifecycle.json')
    const source = path.join(temp, 'source-evidence.json')
    execFileSync(process.execPath, ['--import', preload,
      path.join(adminUi, 'scripts/generate-infra-lifecycle.mjs'),
      '--repo', repo, '--out', out, '--source-evidence-out', source],
    { stdio: 'pipe' })
    const view = JSON.parse(readFileSync(out, 'utf8'))
    const proof = JSON.parse(readFileSync(source, 'utf8'))
    assert.doesNotThrow(() => verifyEolEvidence(view, proof))
    assert.ok(Object.keys(proof.products).length > 0)
    const first = Object.keys(proof.products)[0]
    proof.products[first].responseBodyBase64 = Buffer.from('[{"cycle":"16"}]').toString('base64')
    assert.throws(() => verifyEolEvidence(view, proof), /digest mismatch/)
  } finally {
    rmSync(temp, { recursive: true, force: true })
  }
})

test('generator rejects redirects instead of attesting the requested URL as the responder', () => {
  const temp = mkdtempSync(path.join(tmpdir(), 'admin-eol-redirect-'))
  try {
    const preload = path.join(temp, 'fetch.mjs')
    writeFileSync(preload, `globalThis.fetch = async (url, opts) => {
      if (opts.redirect !== 'error') {
        const response = new Response('[{"cycle":"17"}]', { status: 200 })
        Object.defineProperty(response, 'url', { value: url })
        return response
      }
      throw new Error('redirect rejected')
    }\n`)
    const adminUi = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
    const repo = path.resolve(adminUi, '..')
    const out = path.join(temp, 'infra-lifecycle.json')
    const source = path.join(temp, 'source-evidence.json')
    const result = spawnSync(process.execPath, ['--import', preload,
      path.join(adminUi, 'scripts/generate-infra-lifecycle.mjs'),
      '--repo', repo, '--out', out, '--source-evidence-out', source],
    { encoding: 'utf8' })
    assert.equal(result.status, 1)
    assert.match(result.stderr, /zero lifecycle feeds resolved/)
    const proof = JSON.parse(readFileSync(source, 'utf8'))
    assert.ok(Object.values(proof.products).every(p => p.httpStatus === null &&
      p.responseBodyBase64 === null && p.url.startsWith('https://endoflife.date/api/')))
  } finally {
    rmSync(temp, { recursive: true, force: true })
  }
})
