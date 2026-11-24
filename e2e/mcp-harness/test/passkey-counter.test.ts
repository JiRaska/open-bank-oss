// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import assert from 'node:assert/strict'
import { chmodSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { passkeyCounterIdentity, reservePasskeyCounter } from '../src/passkey-counter.ts'

const credential = { rpId: 'sandbox.test', credentialId: 'synthetic-credential', signCount: 12 }
const state = (reservedThrough: number, identity = passkeyCounterIdentity(credential)) =>
  JSON.stringify({ version: 1, credentialSha256: identity, reservedThrough })

test('durable reservations skip values committed by an interrupted previous process', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'mcp-counter-test-'))
  t.after(() => rmSync(directory, { recursive: true }))
  const path = join(directory, 'counter.json')
  writeFileSync(path, state(3), { mode: 0o600 })
  const first = reservePasskeyCounter(path, credential)
  const firstState = JSON.parse(readFileSync(path, 'utf8'))
  assert.equal(first, 12)
  assert.ok(firstState.reservedThrough > first + 2)
  // No browser ran or returned a counter. The next process can still start safely on the same backup.
  assert.equal(reservePasskeyCounter(path, credential), firstState.reservedThrough)
  const after = readFileSync(path, 'utf8')
  assert.match(after, /credentialSha256/)
  assert.doesNotMatch(after, /synthetic-credential|sandbox\.test/)
  assert.equal(statSync(path).mode & 0o777, 0o600)
  assert.deepEqual(readdirSync(directory), ['counter.json'])
})

test('durable mode refuses missing, malformed, mismatched, unsafe or exhausted state', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'mcp-counter-test-'))
  t.after(() => rmSync(directory, { recursive: true }))
  const path = join(directory, 'counter.json')
  assert.throws(() => reservePasskeyCounter(path, credential), /missing, unreadable or corrupt/)
  writeFileSync(path, 'broken state', { mode: 0o600 })
  assert.throws(() => reservePasskeyCounter(path, credential), /missing, unreadable or corrupt/)
  writeFileSync(path, state(12, 'different-fixture'))
  assert.throws(() => reservePasskeyCounter(path, credential), /does not match/)
  writeFileSync(path, state(0xffff_ffff))
  assert.throws(() => reservePasskeyCounter(path, credential), /exhausted/)
  writeFileSync(path, state(12))
  const link = join(directory, 'alias.json')
  symlinkSync(path, link)
  assert.throws(() => reservePasskeyCounter(link, credential), /missing, unreadable or corrupt/)
  chmodSync(path, 0o644)
  assert.throws(() => reservePasskeyCounter(path, credential), /missing, unreadable or corrupt/)
  assert.equal(readFileSync(path, 'utf8'), state(12))
})
