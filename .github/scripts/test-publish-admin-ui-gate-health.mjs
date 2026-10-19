// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { createHash } from 'node:crypto'
import { DEFAULT_PREFIX, parseArgs, publishGateHealth } from './publish-admin-ui-gate-health.mjs'

const SOURCE_SHA = 'a'.repeat(40)
const ORIGINAL_SHA = 'b'.repeat(40)
const ENV = { GITHUB_SHA: SOURCE_SHA, GITHUB_RUN_ID: '8123', GITHUB_RUN_ATTEMPT: '2' }

function payload(collectedAt = '2026-10-19T10:00:00.000Z') {
  return {
    available: true,
    source: 'trusted-gate-health-collector',
    collectedAt,
    runsInspected: 0,
    gateDetailRunsInspected: 0,
    shardHistory: [],
    gates: [],
    estate: null,
  }
}

function hash(bytes) {
  return createHash('sha256').update(bytes).digest('hex')
}

function testHarness(seed = {}, settings = {}) {
  const directory = mkdtempSync(join(tmpdir(), 'gate-health-publisher-test-'))
  const objects = new Map(Object.entries(seed))
  const calls = []
  let etagNumber = 0
  const execFileSync = (_command, args) => {
    calls.push([...args])
    const [service, operation] = args
    assert.equal(service, 's3api')
    const value = name => args[args.indexOf(name) + 1]
    const bucket = value('--bucket')
    assert.equal(bucket, 'evidence-test-bucket')
    const key = value('--key')
    const storeKey = `${bucket}/${key}`
    if (operation === 'list-objects-v2') {
      if (settings.denyList) {
        const error = new Error('ListBucket denied')
        error.stderr = Buffer.from('An error occurred (AccessDenied) when calling the ListObjectsV2 operation')
        throw error
      }
      const prefix = value('--prefix')
      const keys = [...objects.keys()]
        .filter(candidate => candidate.startsWith(`${bucket}/`))
        .map(candidate => candidate.slice(bucket.length + 1))
        .filter(candidate => candidate.startsWith(prefix))
        .sort()
        .slice(0, Number(value('--max-keys')))
      return JSON.stringify(keys.length ? keys : null)
    }
    if (operation === 'get-object') {
      const object = objects.get(storeKey)
      if (!object) {
        const error = new Error('GetObject missing')
        error.stderr = Buffer.from('An error occurred (NoSuchKey) when calling the GetObject operation')
        throw error
      }
      writeFileSync(args.at(-1), object.bytes)
      return JSON.stringify({ ETag: object.etag, Metadata: object.metadata })
    }
    if (operation === 'put-object') {
      const existing = objects.get(storeKey)
      if (args.includes('--if-none-match') && existing) {
        const error = new Error('PutObject conflict')
        error.stderr = Buffer.from('An error occurred (PreconditionFailed) when calling the PutObject operation')
        throw error
      }
      if (args.includes('--if-match') && (!existing || value('--if-match') !== existing.etag)) {
        const error = new Error('CAS conflict')
        error.stderr = Buffer.from('An error occurred (PreconditionFailed) when calling the PutObject operation')
        throw error
      }
      const bytes = readFileSync(value('--body'))
      const rawMetadata = value('--metadata')
      objects.set(storeKey, {
        bytes,
        etag: `"etag-${++etagNumber}"`,
        metadata: rawMetadata ? { 'source-sha': rawMetadata.slice('source-sha='.length) } : {},
      })
      return '{}'
    }
    throw new Error(`unexpected aws operation ${operation}`)
  }
  const file = join(directory, 'gate-health.json')
  const putInput = value => writeFileSync(file, JSON.stringify(value))
  const publish = options => publishGateHealth({
    bucket: 'evidence-test-bucket', env: ENV, execFileSync, tempRoot: directory, now: () => Date.parse('2026-10-19T10:05:00.000Z'),
    ...options,
  })
  return { directory, objects, calls, file, putInput, publish, execFileSync, cleanup: () => rmSync(directory, { recursive: true, force: true }) }
}

function objectKey(key) { return `evidence-test-bucket/${DEFAULT_PREFIX}/${key}` }
function getOperation(call) { return call[1] }
function currentPointer(harness) {
  return JSON.parse(harness.objects.get(objectKey('current.json')).bytes.toString('utf8'))
}

test('CLI accepts an explicit evidence bucket and otherwise uses EVIDENCE_BUCKET', () => {
  assert.deepEqual(parseArgs(['--file', 'gate-health.json', '--bucket', 'evidence-test-bucket'], { EVIDENCE_BUCKET: 'fallback-bucket' }), {
    file: 'gate-health.json', bucket: 'evidence-test-bucket', prefix: DEFAULT_PREFIX,
  })
  assert.deepEqual(parseArgs(['--file', 'gate-health.json'], { EVIDENCE_BUCKET: 'evidence-test-bucket' }), {
    file: 'gate-health.json', bucket: 'evidence-test-bucket', prefix: DEFAULT_PREFIX,
  })
})

test('invalid payload causes zero object-store writes', () => {
  const h = testHarness()
  try {
    h.putInput({ ...payload(), available: false })
    assert.throws(() => h.publish({ file: h.file }), /failed schema validation/)
    assert.equal(h.calls.filter(call => getOperation(call) === 'put-object').length, 0)
  } finally { h.cleanup() }
})

test('publishes an immutable content-addressed snapshot before the CAS pointer', () => {
  const h = testHarness()
  try {
    h.putInput(payload())
    const result = h.publish({ file: h.file })
    const puts = h.calls.filter(call => getOperation(call) === 'put-object')
    assert.equal(puts.length, 2)
    assert.equal(puts[0][puts[0].indexOf('--key') + 1], `${DEFAULT_PREFIX}/${result.snapshot}`)
    assert.equal(puts[0][puts[0].indexOf('--if-none-match') + 1], '*')
    assert.equal(puts[1][puts[1].indexOf('--key') + 1], `${DEFAULT_PREFIX}/current.json`)
    assert.equal(puts[1][puts[1].indexOf('--if-none-match') + 1], '*')
    assert.equal(getOperation(h.calls[0]), 'list-objects-v2')
    const pointer = currentPointer(h)
    assert.equal(pointer.sha256, hash(readFileSync(h.file)))
    assert.equal(pointer.expiresAt, '2026-10-19T11:05:00.000Z')
    assert.equal(pointer.complete, true)
  } finally { h.cleanup() }
})

test('fails closed on denied current-pointer listing before any writes', () => {
  const h = testHarness({}, { denyList: true })
  try {
    h.putInput(payload())
    assert.throws(() => h.publish({ file: h.file }), /ListBucket denied/)
    assert.equal(h.calls.filter(call => getOperation(call) === 'put-object').length, 0)
  } finally { h.cleanup() }
})

test('a prefix sibling is not treated as current.json', () => {
  const h = testHarness({
    [objectKey('current.json.backup')]: { bytes: Buffer.from('{}'), etag: '"sibling"', metadata: {} },
  })
  try {
    h.putInput(payload())
    h.publish({ file: h.file })
    assert.equal(getOperation(h.calls[0]), 'list-objects-v2')
    assert.equal(h.calls.some(call => getOperation(call) === 'get-object' && call.includes(`${DEFAULT_PREFIX}/current.json`)), false)
    assert.equal(currentPointer(h).complete, true)
  } finally { h.cleanup() }
})

test('does not overwrite a current pointer after a compare-and-swap conflict', () => {
  const oldManifest = {
    schemaVersion: 1, snapshot: `snapshots/1-1-${'c'.repeat(64)}.json`, sha256: 'c'.repeat(64),
    sourceSha: ORIGINAL_SHA, runId: 1, runAttempt: 1, collectedAt: '2026-10-19T09:00:00.000Z',
    expiresAt: '2026-10-19T10:00:00.000Z', complete: true,
  }
  const h = testHarness({
    [objectKey('current.json')]: { bytes: Buffer.from(JSON.stringify(oldManifest)), etag: '"old-etag"', metadata: {} },
  })
  const originalExec = h.publish
  try {
    h.putInput(payload('2026-10-19T10:00:00.000Z'))
    // Inject a concurrent writer immediately after current.json was read.
    const base = originalExec
    h.publish = options => base({
      ...options,
      execFileSync(command, args, execOptions) {
        if (args[1] === 'put-object' && args.includes('--if-match')) {
          const current = h.objects.get(objectKey('current.json'))
          current.etag = '"concurrent-writer"'
        }
        return h.execFileSync(command, args, execOptions)
      },
    })
    assert.throws(() => h.publish({ file: h.file }), /CAS conflict/)
    assert.deepEqual(currentPointer(h), oldManifest)
    assert.equal(h.objects.has(objectKey('snapshots/8123-2-' + hash(readFileSync(h.file)) + '.json')), true)
  } finally { h.cleanup() }
})

test('pointer created after an absent listing wins the initial-pointer CAS', () => {
  const h = testHarness()
  const winner = {
    schemaVersion: 1, snapshot: `snapshots/2-1-${'d'.repeat(64)}.json`, sha256: 'd'.repeat(64),
    sourceSha: ORIGINAL_SHA, runId: 2, runAttempt: 1, collectedAt: '2026-10-19T10:02:00.000Z',
    expiresAt: '2026-10-19T11:02:00.000Z', complete: true,
  }
  try {
    h.putInput(payload())
    h.publish = options => {
      const original = options?.execFileSync ?? h.execFileSync
      return publishGateHealth({
        bucket: 'evidence-test-bucket', env: ENV, tempRoot: h.directory,
        now: () => Date.parse('2026-10-19T10:05:00.000Z'), ...options,
        execFileSync(command, args, execOptions) {
          const result = original(command, args, execOptions)
          if (args[1] === 'list-objects-v2') {
            h.objects.set(objectKey('current.json'), { bytes: Buffer.from(JSON.stringify(winner)), etag: '"winner"', metadata: {} })
          }
          return result
        },
      })
    }
    assert.throws(() => h.publish({ file: h.file }), /PutObject conflict/)
    assert.deepEqual(currentPointer(h), winner)
  } finally { h.cleanup() }
})

test('accepts immutable 412 only when the stored bytes and producer metadata match the hash key', () => {
  const h = testHarness()
  try {
    h.putInput(payload())
    const digest = hash(readFileSync(h.file))
    const snapshot = `snapshots/8123-2-${digest}.json`
    h.objects.set(objectKey(snapshot), { bytes: readFileSync(h.file), etag: '"same"', metadata: { 'source-sha': SOURCE_SHA } })
    const result = h.publish({ file: h.file })
    assert.equal(result.snapshot, snapshot)
    assert.equal(h.calls.filter(call => getOperation(call) === 'put-object').length, 2)

    const mismatch = testHarness()
    try {
      mismatch.putInput(payload())
      mismatch.objects.set(objectKey(snapshot), { bytes: Buffer.from('{"wrong":true}'), etag: '"wrong"', metadata: { 'source-sha': SOURCE_SHA } })
      assert.throws(() => mismatch.publish({ file: mismatch.file }), /different content/)
      assert.equal(mismatch.calls.filter(call => getOperation(call) === 'put-object').length, 1)
    } finally { mismatch.cleanup() }
  } finally { h.cleanup() }
})

test('rollback preserves the snapshot observation time and producer provenance while refreshing only the pointer', () => {
  const bytes = Buffer.from(JSON.stringify(payload('2026-10-01T08:00:00.000Z')))
  const digest = hash(bytes)
  const key = `snapshots/44-3-${digest}.json`
  const h = testHarness({
    [objectKey('current.json')]: {
      bytes: Buffer.from(JSON.stringify({
        schemaVersion: 1, snapshot: `snapshots/1-1-${'c'.repeat(64)}.json`, sha256: 'c'.repeat(64),
        sourceSha: ORIGINAL_SHA, runId: 1, runAttempt: 1, collectedAt: '2026-10-19T10:01:00.000Z',
        expiresAt: '2026-10-19T11:01:00.000Z', complete: true,
      })), etag: '"current-etag"', metadata: {},
    },
    [objectKey(key)]: { bytes, etag: '"snapshot-etag"', metadata: { 'source-sha': ORIGINAL_SHA } },
  })
  try {
    const result = h.publish({ rollback: key })
    const pointer = currentPointer(h)
    assert.equal(result.rollback, true)
    assert.equal(pointer.snapshot, key)
    assert.equal(pointer.collectedAt, '2026-10-01T08:00:00.000Z')
    assert.equal(pointer.sourceSha, ORIGINAL_SHA)
    assert.equal(pointer.runId, 44)
    assert.equal(pointer.runAttempt, 3)
    assert.equal(pointer.expiresAt, '2026-10-19T11:05:00.000Z')
    assert.equal(h.calls.filter(call => getOperation(call) === 'put-object').length, 1)
  } finally { h.cleanup() }
})
