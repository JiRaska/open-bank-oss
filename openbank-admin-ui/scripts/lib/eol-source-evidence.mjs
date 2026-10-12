// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { createHash } from 'node:crypto'

export const canonicalEolUrl = product => {
  if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(product)) throw new Error('invalid EOL product')
  return `https://endoflife.date/api/${product}.json`
}

const sha256 = bytes => createHash('sha256').update(bytes).digest('hex')

export const projectEolCycles = raw => {
  const cycles = JSON.parse(raw.toString('utf8'))
  if (!Array.isArray(cycles)) throw new Error('EOL response is not an array')
  return cycles.map(c => ({
    cycle: String(c.cycle),
    releaseDate: c.releaseDate ?? null,
    eol: c.eol ?? null,
    latest: c.latest ?? null,
    latestReleaseDate: c.latestReleaseDate ?? null,
    lts: c.lts ?? false,
    support: c.support ?? null,
    link: c.link ?? null,
  }))
}

export const makeEolEvidence = (product, httpStatus, fetchedAt, raw) => {
  if (httpStatus !== null && (!Number.isInteger(httpStatus) || httpStatus < 100 || httpStatus > 599)) {
    throw new Error('invalid EOL HTTP status')
  }
  if (Number.isNaN(Date.parse(fetchedAt)) || !fetchedAt.endsWith('Z')) {
    throw new Error('invalid EOL fetch time')
  }
  return {
    url: canonicalEolUrl(product),
    httpStatus,
    fetchedAt,
    responseSha256: raw === null ? null : sha256(raw),
    responseBodyBase64: raw === null ? null : raw.toString('base64'),
  }
}

// Compare the raw response, its digest and the served projection. The signed
// image inventory then binds both files, not only a self-asserted checksum.
export const verifyEolEvidence = (snapshot, evidence) => {
  if (evidence?.schema !== 'openbank.eol-source-evidence/v1' || !Array.isArray(snapshot?.components)) {
    throw new Error('unknown EOL evidence schema')
  }
  const expected = snapshot.components.filter(c => c.eolProduct)
  if (Object.keys(evidence.products ?? {}).length !== expected.length) {
    throw new Error('EOL evidence product count differs from snapshot')
  }
  for (const component of expected) {
    const source = evidence.products[component.eolProduct]
    if (!source || source.url !== canonicalEolUrl(component.eolProduct)) {
      throw new Error(`missing canonical EOL source for ${component.id}`)
    }
    if ((source.httpStatus !== null && (!Number.isInteger(source.httpStatus) ||
        source.httpStatus < 100 || source.httpStatus > 599)) || !source.fetchedAt?.endsWith('Z') ||
        Number.isNaN(Date.parse(source.fetchedAt))) {
      throw new Error(`invalid EOL fetch metadata for ${component.id}`)
    }
    const hasBody = typeof source.responseBodyBase64 === 'string'
    if (source.httpStatus !== 200) {
      if (hasBody || source.responseBodyBase64 !== null || source.responseSha256 !== null ||
          component.lifecycle?.cycles) throw new Error(`unexpected EOL projection for ${component.id}`)
      continue
    }
    if (!hasBody) throw new Error(`missing EOL body for ${component.id}`)
    const raw = Buffer.from(source.responseBodyBase64, 'base64')
    if (raw.toString('base64') !== source.responseBodyBase64 || sha256(raw) !== source.responseSha256) {
      throw new Error(`EOL response digest mismatch for ${component.id}`)
    }
    if (JSON.stringify(projectEolCycles(raw)) !== JSON.stringify(component.lifecycle?.cycles)) {
      throw new Error(`EOL projection mismatch for ${component.id}`)
    }
  }
}
