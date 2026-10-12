// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { createHash } from 'node:crypto'
import { createReadStream } from 'node:fs'

export const DIGEST = /^sha256:[0-9a-f]{64}$/
const INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/

export function immutableImageRef(ref, digest) {
  if (!DIGEST.test(digest) || !/^[A-Za-z0-9][A-Za-z0-9._:/-]*$/.test(ref) || ref.includes('@')) {
    throw new Error('invalid image reference or digest')
  }
  const tag = ref.lastIndexOf(':')
  if (tag <= ref.lastIndexOf('/') || tag === ref.length - 1) throw new Error('image tag is missing')
  return `${ref.slice(0, tag)}@${digest}`
}

export const sha256 = bytes => createHash('sha256').update(bytes).digest('hex')

export async function sha256File(file) {
  const hash = createHash('sha256')
  for await (const chunk of createReadStream(file)) hash.update(chunk)
  return hash.digest('hex')
}

export const scanInstant = () => new Date().toISOString()

export function verifiedDbStatus(status, dbSha256) {
  if (status?.valid !== true || !Number.isInteger(Number(status.schemaVersion)) ||
      Number(status.schemaVersion) <= 0 || !/^[0-9a-f]{64}$/.test(dbSha256)) {
    throw new Error('Grype vulnerability DB is unavailable or invalid')
  }
  return { schemaVersion: Number(status.schemaVersion), sha256: dbSha256 }
}

export function verifyInfraVulnSnapshot(snapshot, expectedRefs) {
  if (snapshot?.schema !== 'openbank.infra-vulns/v2' ||
      !snapshot.scannedAt || !INSTANT.test(snapshot.scannedAt) ||
      Number.isNaN(Date.parse(snapshot.scannedAt)) ||
      snapshot.scanner?.name !== 'grype' || !/^\d+\.\d+\.\d+$/.test(snapshot.scanner.version) ||
      !Number.isInteger(snapshot.scanner.db?.schemaVersion) ||
      !/^[0-9a-f]{64}$/.test(snapshot.scanner.db?.sha256 ?? '')) {
    throw new Error('infra vulnerability source metadata is incomplete')
  }
  for (const [id, item] of Object.entries(snapshot.images ?? {})) {
    if (expectedRefs[id] !== item.image || !DIGEST.test(item.imageDigest ?? '') ||
        immutableImageRef(item.image, item.imageDigest) !== item.scannedImage ||
        !INSTANT.test(item.scanAt ?? '') || Number.isNaN(Date.parse(item.scanAt)) ||
        !/^[0-9a-f]{64}$/.test(item.reportSha256 ?? '')) {
      throw new Error(`infra vulnerability image source drift for ${id}`)
    }
    for (const key of ['critical', 'high', 'medium', 'low', 'total']) {
      if (!Number.isInteger(item[key]) || item[key] < 0) throw new Error(`invalid CVE count for ${id}`)
    }
  }
}
