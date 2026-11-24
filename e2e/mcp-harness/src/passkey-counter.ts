// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { createHash, randomUUID } from 'node:crypto'
import { closeSync, constants, fstatSync, fsyncSync, openSync, readFileSync, renameSync, unlinkSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'

interface CredentialCounter {
  credentialId: string
  rpId: string
  signCount?: number
}

// Each journey invokes two ceremonies. Reserve additional values so an interrupted run or
// authenticator-side retry does not make the next process reuse a signed count.
const COUNTS_PER_RUN = 32
const MAX_COUNTER = 0xffff_ffff

export function passkeyCounterIdentity(credential: CredentialCounter): string {
  if (!credential.rpId || !credential.credentialId) throw new Error('passkey fixture is missing its credential or RP ID')
  return createHash('sha256').update(JSON.stringify([credential.rpId, credential.credentialId])).digest('hex')
}

function validCounter(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 && value <= MAX_COUNTER
}

/** Called by a single serialized process per credential, before either passkey assertion. */
export function reservePasskeyCounter(path: string, credential: CredentialCounter): number {
  const credentialSha256 = passkeyCounterIdentity(credential)
  const mountedCount = credential.signCount ?? 0
  if (!validCounter(mountedCount)) throw new Error('invalid mounted passkey counter')
  let previous
  try {
    const fd = openSync(path, constants.O_RDONLY | constants.O_NOFOLLOW)
    try {
      const metadata = fstatSync(fd)
      if (!metadata.isFile() || (metadata.mode & 0o077) !== 0 || metadata.size > 1024) {
        throw new Error()
      }
      previous = JSON.parse(readFileSync(fd, 'utf8'))
    } finally {
      closeSync(fd)
    }
  } catch {
    throw new Error('durable passkey counter state is missing, unreadable or corrupt')
  }
  if (previous?.version !== 1 || previous.credentialSha256 !== credentialSha256 ||
      !validCounter(previous.reservedThrough)) {
    throw new Error('durable passkey counter state does not match the mounted credential')
  }
  const start = Math.max(previous.reservedThrough, mountedCount)
  if (start > MAX_COUNTER - COUNTS_PER_RUN) throw new Error('durable passkey counter exhausted')

  const next = { version: 1, credentialSha256, reservedThrough: start + COUNTS_PER_RUN }
  const directory = dirname(path)
  const staging = join(directory, `.passkey-counter-${randomUUID()}`)
  try {
    const fd = openSync(staging, 'wx', 0o600)
    try {
      writeFileSync(fd, `${JSON.stringify(next)}\n`, 'utf8')
      fsyncSync(fd)
    } finally {
      closeSync(fd)
    }
    renameSync(staging, path)
    const directoryFd = openSync(directory, 'r')
    try {
      fsyncSync(directoryFd)
    } finally {
      closeSync(directoryFd)
    }
  } catch {
    throw new Error('could not persist the passkey counter reservation')
  } finally {
    try { unlinkSync(staging) } catch { /* rename removed it, or staging was never created */ }
  }
  return start
}
