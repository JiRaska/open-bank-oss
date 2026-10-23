// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { createHash } from 'node:crypto'
import { execFileSync as nodeExecFileSync } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { validateEvidenceManifest } from '../../openbank-admin-ui/src/lib/evidence/publishedSnapshot.ts'

const SOURCE_SHA = /^[0-9a-f]{40}$/
const SNAPSHOT_KEY = /^snapshots\/([1-9][0-9]*)-([1-9][0-9]*)-([0-9a-f]{64})\.json$/
const MAX_INPUT_BYTES = 2 * 1024 * 1024
const POINTER_TTL_MS = 60 * 60 * 1000

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex')
}

function parsePositiveInteger(value, name) {
  if (typeof value !== 'string' || !/^[1-9][0-9]*$/.test(value)) throw new Error(`${name} must be a positive integer`)
  const number = Number(value)
  if (!Number.isSafeInteger(number)) throw new Error(`${name} must be a safe positive integer`)
  return number
}

function validatePrefix(prefix) {
  if (typeof prefix !== 'string' || !prefix || prefix.startsWith('/') || prefix.endsWith('/')
    || prefix.includes('//') || prefix.split('/').some(part => !part || part === '.' || part === '..')
    || !/^[a-zA-Z0-9][a-zA-Z0-9/_-]*$/.test(prefix)) {
    throw new Error('prefix must be a safe relative S3 key prefix')
  }
  return prefix
}

function errorText(error) {
  return [error?.stderr, error?.message].filter(Boolean).map(value => Buffer.isBuffer(value) ? value.toString('utf8') : String(value)).join('\n')
}

function isPreconditionFailure(error) {
  return /\((?:PreconditionFailed|412)\)/.test(errorText(error))
}

function aws(execFileSync, args) {
  return execFileSync('aws', ['s3api', ...args], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
}

function option(args, name, value) {
  args.push(name, String(value))
}

function getObject(execFileSync, bucket, key, destination) {
  const response = aws(execFileSync, [
    'get-object', '--bucket', bucket, '--key', key,
    '--query', '{ETag:ETag,Metadata:Metadata}', '--output', 'json', destination,
  ])
  let metadata
  try { metadata = JSON.parse(response) } catch { throw new Error('S3 GetObject returned invalid response metadata') }
  if (!metadata || typeof metadata.ETag !== 'string' || !metadata.ETag) throw new Error('S3 GetObject omitted its ETag')
  return { exists: true, etag: metadata.ETag, metadata: metadata.Metadata ?? {} }
}

function listCurrentExists(execFileSync, bucket, pointerKey) {
  const response = aws(execFileSync, [
    'list-objects-v2', '--bucket', bucket, '--prefix', pointerKey, '--max-keys', '1',
    '--query', 'Contents[].Key', '--output', 'json',
  ])
  let keys
  try { keys = JSON.parse(response) } catch { throw new Error('S3 ListObjectsV2 returned invalid response') }
  if (keys === null) return false
  if (!Array.isArray(keys) || keys.some(key => typeof key !== 'string')) throw new Error('S3 ListObjectsV2 returned invalid keys')
  return keys.includes(pointerKey)
}

function loadJson(bytes, description) {
  try { return JSON.parse(bytes.toString('utf8')) } catch { throw new Error(`${description} is not valid JSON`) }
}

function safeManifest(bytes, nowMs) {
  const manifest = loadJson(bytes, 'current manifest')
  if (!validateEvidenceManifest(manifest, nowMs)) throw new Error('current manifest failed schema validation')
  return manifest
}

function manifestBytes({ snapshot, sha256: digest, sourceSha, runId, runAttempt, collectedAt, now }) {
  const manifest = {
    schemaVersion: 1,
    snapshot,
    sha256: digest,
    sourceSha,
    runId,
    runAttempt,
    collectedAt,
    expiresAt: new Date(now + POINTER_TTL_MS).toISOString(),
    complete: true,
  }
  if (!validateEvidenceManifest(manifest, now)) throw new Error('publisher produced an invalid manifest')
  return Buffer.from(`${JSON.stringify(manifest)}\n`, 'utf8')
}

function putImmutableSnapshot(execFileSync, bucket, objectKey, bodyPath, sourceSha, expectedDigest, tempDirectory, readFile) {
  try {
    aws(execFileSync, [
      'put-object', '--bucket', bucket, '--key', objectKey, '--body', bodyPath,
      '--content-type', 'application/json', '--metadata', `source-sha=${sourceSha}`,
      '--if-none-match', '*', '--output', 'json',
    ])
  } catch (error) {
    if (!isPreconditionFailure(error)) throw error
    // A content-addressed key is idempotent only when the bytes already stored under it agree.
    const existingPath = join(tempDirectory, 'existing-snapshot.json')
    const existing = getObject(execFileSync, bucket, objectKey, existingPath)
    const existingBytes = readFile(existingPath)
    if (sha256(existingBytes) !== expectedDigest) throw new Error('immutable snapshot key exists with different content')
    if (existing.metadata['source-sha'] !== sourceSha) throw new Error('immutable snapshot key has different source SHA metadata')
  }
}

function readCurrent(execFileSync, bucket, pointerKey, tempDirectory, readFile, nowMs) {
  // The role intentionally has ListBucket permission for this one exact key only. S3
  // returns 403 for GetObject on an absent key when ListBucket is unavailable, so test
  // exact presence first; a prefix sibling must never be mistaken for current.json.
  if (!listCurrentExists(execFileSync, bucket, pointerKey)) return null
  const path = join(tempDirectory, 'current.json')
  const result = getObject(execFileSync, bucket, pointerKey, path)
  const manifest = safeManifest(readFile(path), nowMs)
  return { manifest, etag: result.etag }
}

function writeCurrent(execFileSync, bucket, pointerKey, pointerPath, expectedEtag) {
  const args = ['put-object', '--bucket', bucket, '--key', pointerKey, '--body', pointerPath, '--content-type', 'application/json']
  if (expectedEtag === null) option(args, '--if-none-match', '*')
  else option(args, '--if-match', expectedEtag)
  aws(execFileSync, args)
}

/** Publish a verified evidence snapshot and CAS the current pointer. AWS CLI execution is injectable. */
export function publishEvidenceSnapshot({
  file,
  bucket,
  prefix,
  validateSnapshot,
  collectedAt: snapshotCollectedAt,
  snapshotSourceSha,
  rollback,
  env = process.env,
  execFileSync = nodeExecFileSync,
  now = Date.now,
  createTempDirectory = mkdtempSync,
  removeDirectory = rmSync,
  readFile = readFileSync,
  writeFile = writeFileSync,
  tempRoot = tmpdir(),
} = {}) {
  if (typeof validateSnapshot !== 'function' || typeof snapshotCollectedAt !== 'function') throw new Error('dataset validation contract is required')
  if (typeof bucket !== 'string' || !bucket.trim()) throw new Error('EVIDENCE_BUCKET is required')
  const safePrefix = validatePrefix(prefix)
  // Only reviewed repository content contains the bounded Markdown corpora.
  const maxInputBytes = safePrefix === 'admin-ui/content' ? 8 * 1024 * 1024 : MAX_INPUT_BYTES
  const sourceSha = env.EVIDENCE_SOURCE_SHA ?? env.GITHUB_SHA
  if (typeof sourceSha !== 'string' || !SOURCE_SHA.test(sourceSha)) throw new Error('publication source SHA must be a full 40-character lowercase SHA')
  const runId = parsePositiveInteger(env.GITHUB_RUN_ID, 'GITHUB_RUN_ID')
  const runAttempt = parsePositiveInteger(env.GITHUB_RUN_ATTEMPT, 'GITHUB_RUN_ATTEMPT')
  if (Boolean(rollback) === Boolean(file)) throw new Error('provide exactly one of file or rollback')

  const directory = createTempDirectory(join(tempRoot, 'admin-ui-evidence-'))
  try {
    const pointerKey = `${safePrefix}/current.json`
    let pointerSourceSha = sourceSha
    let pointerRunId = runId
    let pointerRunAttempt = runAttempt
    let payloadBytes
    let payload
    let snapshot
    let digest

    if (rollback) {
      const match = SNAPSHOT_KEY.exec(rollback)
      if (!match) throw new Error('rollback must name a snapshots/<runId>-<attempt>-<sha256>.json key')
      const snapshotPath = join(directory, 'rollback-snapshot.json')
      const object = getObject(execFileSync, bucket, `${safePrefix}/${rollback}`, snapshotPath)
      if (!object.exists) throw new Error('rollback snapshot does not exist')
      payloadBytes = readFile(snapshotPath)
      if (payloadBytes.byteLength > maxInputBytes) throw new Error('rollback snapshot exceeds size limit')
      digest = sha256(payloadBytes)
      if (digest !== match[3]) throw new Error('rollback snapshot filename hash does not match its bytes')
      payload = loadJson(payloadBytes, 'rollback snapshot')
      if (!validateSnapshot(payload, now())) throw new Error('rollback snapshot failed schema validation')
      if (typeof object.metadata['source-sha'] !== 'string' || !SOURCE_SHA.test(object.metadata['source-sha'])) throw new Error('rollback snapshot has invalid source SHA metadata')
      pointerSourceSha = object.metadata['source-sha']
      pointerRunId = parsePositiveInteger(match[1], 'rollback runId')
      pointerRunAttempt = parsePositiveInteger(match[2], 'rollback runAttempt')
      snapshot = rollback
    } else {
      if (typeof file !== 'string' || !file) throw new Error('file is required')
      payloadBytes = readFile(file)
      if (payloadBytes.byteLength > maxInputBytes) throw new Error('evidence input exceeds size limit')
      payload = loadJson(payloadBytes, 'evidence input')
      if (!validateSnapshot(payload, now())) throw new Error('evidence input failed schema validation')
      digest = sha256(payloadBytes)
      snapshot = `snapshots/${runId}-${runAttempt}-${digest}.json`
    }

    // All input and rollback checks finish before the first object-store write.
    const collectedAt = snapshotCollectedAt(payload)
    if (snapshotSourceSha && snapshotSourceSha(payload) !== pointerSourceSha) throw new Error('snapshot source SHA does not match publication source')
    const timestamp = Date.parse(collectedAt)
    if (!Number.isFinite(timestamp)) throw new Error('collectedAt must be a valid producer timestamp')
    const current = readCurrent(execFileSync, bucket, pointerKey, directory, readFile, now())
    if (!rollback && current && Date.parse(current.manifest.collectedAt) > timestamp) {
      throw new Error('refusing to replace current evidence with an older collectedAt')
    }

    const pointer = manifestBytes({ snapshot, sha256: digest, sourceSha: pointerSourceSha, runId: pointerRunId, runAttempt: pointerRunAttempt, collectedAt, now: now() })
    const pointerPath = join(directory, 'manifest.json')
    writeFile(pointerPath, pointer)

    if (!rollback) {
      const snapshotPath = join(directory, 'snapshot.json')
      writeFile(snapshotPath, payloadBytes)
      putImmutableSnapshot(execFileSync, bucket, `${safePrefix}/${snapshot}`, snapshotPath, pointerSourceSha, digest, directory, readFile)
    }

    writeCurrent(execFileSync, bucket, pointerKey, pointerPath, current?.etag ?? null)
    return { snapshot, sha256: digest, collectedAt, rollback: Boolean(rollback) }
  } finally {
    removeDirectory(directory, { recursive: true, force: true })
  }
}
