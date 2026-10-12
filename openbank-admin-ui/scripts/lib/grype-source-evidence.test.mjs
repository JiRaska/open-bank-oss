// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import test from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { immutableImageRef, sha256, verifiedDbStatus, verifyInfraVulnSnapshot } from './grype-source-evidence.mjs'

const digest = `sha256:${'a'.repeat(64)}`
const image = 'docker.io/library/postgres:18.6'
const row = () => ({ image, imageDigest: digest,
  scannedImage: immutableImageRef(image, digest), scanAt: '2026-10-12T11:00:00Z',
  reportSha256: 'c'.repeat(64), critical: 0, high: 1, medium: 0, low: 0, total: 1 })
const snapshot = () => ({ schema: 'openbank.infra-vulns/v2', scannedAt: '2026-10-12T11:00:01Z',
  scanner: { name: 'grype', version: '0.118.0', db: { schemaVersion: 6, sha256: 'd'.repeat(64) } },
  images: { postgres: row() } })

test('immutable image and DB metadata are valid only for the expected tag', () => {
  assert.equal(immutableImageRef(image, digest), `docker.io/library/postgres@${digest}`)
  assert.deepEqual(verifiedDbStatus({ valid: true, schemaVersion: '6' }, 'd'.repeat(64)),
    { schemaVersion: 6, sha256: 'd'.repeat(64) })
  assert.throws(() => verifiedDbStatus({ valid: false, schemaVersion: 6 }, 'd'.repeat(64)))
  assert.doesNotThrow(() => verifyInfraVulnSnapshot(snapshot(), { postgres: image }))
  assert.throws(() => verifyInfraVulnSnapshot(snapshot(), { postgres: 'docker.io/library/postgres:18.7' }),
    /source drift/)
  const moved = snapshot()
  moved.images.postgres.scannedImage = `docker.io/library/postgres@sha256:${'b'.repeat(64)}`
  assert.throws(() => verifyInfraVulnSnapshot(moved, { postgres: image }), /source drift/)
  const old = snapshot()
  old.scanner.db.sha256 = ''
  assert.throws(() => verifyInfraVulnSnapshot(old, { postgres: image }), /source metadata/)
})

function fixture({ moveTag = false, mutateDb = false, grypeFailure = false,
  secondGrypeFailure = false, missingMatches = false } = {}) {
  const temp = mkdtempSync(path.join(tmpdir(), 'admin-grype-source-'))
  const bin = path.join(temp, 'bin')
  const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..')
  const scanner = path.join(repo, 'openbank-admin-ui/scripts/scan-infra-vulns.mjs')
  const db = path.join(temp, 'vulnerability.db')
  const out = path.join(temp, 'infra-vulns.json')
  const count = path.join(temp, 'crane-count')
  const scanCount = path.join(temp, 'grype-scan-count')
  mkdirSync(bin)
  writeFileSync(db, 'fixture-db-bytes')
  const crane = path.join(bin, 'crane')
  writeFileSync(crane, `#!/usr/bin/env node
const { readFileSync, writeFileSync } = require('node:fs')
const file = ${JSON.stringify(count)}
let n = 0
try { n = Number(readFileSync(file, 'utf8')) } catch {}
n++
writeFileSync(file, String(n))
process.stdout.write('sha256:' + (process.env.TEST_MOVE_TAG === '1' && n === 2 ? 'b' : 'a').repeat(64) + '\\n')
`)
  chmodSync(crane, 0o755)
  const grype = path.join(bin, 'grype')
  writeFileSync(grype, `#!/usr/bin/env node
const { appendFileSync, readFileSync, writeFileSync } = require('node:fs')
const args = process.argv.slice(2)
if (args[0] === 'version') console.log(JSON.stringify({ version: '0.118.0' }))
else if (args[0] === 'db') console.log(JSON.stringify({ valid: true, schemaVersion: '6', path: ${JSON.stringify(db)} }))
else {
  let n = 0
  try { n = Number(readFileSync(${JSON.stringify(scanCount)}, 'utf8')) } catch {}
  n++
  writeFileSync(${JSON.stringify(scanCount)}, String(n))
  if (process.env.TEST_MUTATE_DB === '1') appendFileSync(${JSON.stringify(db)}, 'changed')
  console.log(JSON.stringify(process.env.TEST_MISSING_MATCHES === '1' && n === 2 ? {} :
    { matches: [{ vulnerability: { severity: 'High', id: 'CVE-fixture' }, artifact: { name: 'fixture' } }] }))
  if (process.env.TEST_GRYPE_FAIL === '1' || (process.env.TEST_FAIL_SECOND === '1' && n === 2)) process.exitCode = 1
}
`)
  chmodSync(grype, 0o755)
  const result = spawnSync(process.execPath, [scanner, '--repo', repo, '--out', out], {
    encoding: 'utf8', env: { ...process.env, PATH: `${bin}:${process.env.PATH}`,
      TEST_MOVE_TAG: moveTag ? '1' : '0', TEST_MUTATE_DB: mutateDb ? '1' : '0',
      TEST_GRYPE_FAIL: grypeFailure ? '1' : '0', TEST_FAIL_SECOND: secondGrypeFailure ? '1' : '0',
      TEST_MISSING_MATCHES: missingMatches ? '1' : '0' },
  })
  return { temp, out, result }
}

test('offline producer records immutable digest, DB checksum and real scan time', () => {
  const run = fixture()
  try {
    assert.equal(run.result.status, 0, run.result.stderr)
    const doc = JSON.parse(readFileSync(run.out, 'utf8'))
    assert.equal(doc.schema, 'openbank.infra-vulns/v2')
    assert.equal(doc.scanner.db.sha256, sha256(Buffer.from('fixture-db-bytes')))
    assert.ok(Object.keys(doc.images).length > 0)
    for (const entry of Object.values(doc.images)) {
      assert.equal(entry.imageDigest, digest)
      assert.equal(entry.scannedImage, immutableImageRef(entry.image, digest))
      assert.match(entry.scanAt, /^\d{4}-\d{2}-\d{2}T/)
      assert.equal(entry.high, 1)
    }
  } finally { rmSync(run.temp, { recursive: true, force: true }) }
})

test('producer refuses a tag moved between resolution and scan', () => {
  const run = fixture({ moveTag: true })
  try {
    assert.equal(run.result.status, 1)
    assert.match(run.result.stderr, /tag moved during Grype scan/)
    assert.throws(() => readFileSync(run.out), /ENOENT/)
  } finally { rmSync(run.temp, { recursive: true, force: true }) }
})

test('producer refuses a DB mutation, failed scan or missing matches', () => {
  for (const option of [{ mutateDb: true }, { grypeFailure: true },
    { secondGrypeFailure: true }, { missingMatches: true }]) {
    const run = fixture(option)
    try {
      assert.equal(run.result.status, 1)
      assert.throws(() => readFileSync(run.out), /ENOENT/)
    } finally { rmSync(run.temp, { recursive: true, force: true }) }
  }
})
